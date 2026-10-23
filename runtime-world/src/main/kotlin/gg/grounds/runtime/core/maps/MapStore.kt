package gg.grounds.runtime.core.maps

import com.google.gson.JsonParser
import gg.grounds.runtime.core.world.MapLight
import io.nats.client.Nats
import io.nats.client.Options
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.slf4j.LoggerFactory

/**
 * The maps a game server plays: which version of each map is pinned, its bundle on local disk, and
 * that bundle lit (see [MapLight]).
 *
 * The map service itself is never called. It publishes `pins/<env>.json` to the CDN and that file
 * names, per map address, the content-addressed bundle to fetch — so a pod boots and loads maps
 * with service-maps down.
 *
 * ```
 * GET https://maps.grounds.gg/pins/stage.json
 * { "environment": "stage",
 *   "maps": { "survivalgames/aura": { "version": 3, "bundleSha256": "…",
 *                                      "bundleUrl": "https://…/….tar.zst" } } }
 * ```
 *
 * Bundles are immutable and named by their digest, so the unpacked copy is kept under that digest
 * (after checking the download against it) and never fetched again; its lit copy is kept beside it
 * under `lit-v1/<digest>`. Lighting a map takes seconds, so [warm] does it for every map a game can
 * pick before a round asks, and [fetch] hands out the unlit copy, and starts the light in the
 * background, when a round gets there first.
 *
 * A digest that is no longer pinned is deleted again once no round has been handed it for
 * [roundGrace] (see [evict]); a game server lives for days and would otherwise keep every version
 * it ever played.
 *
 * @param environment which pin file to read. Read from `MAPS_ENVIRONMENT`, deliberately not
 *   `GROUNDS_ENV`: that one is the runtime's own and accepts only prod/test/dev, while a pin file
 *   is named after a deployment environment like `stage`
 * @param cacheDir where bundles are unpacked and lit, keyed by digest (`MAPS_CACHE_DIR`)
 * @param natsUrl where service-maps announces pin file changes (`NATS_URL`); [keepWarm] then warms
 *   on every announcement instead of only on its timer
 * @param roundGrace how long after a round was handed a map its files must stay: the world is read
 *   chunk by chunk while the round runs, so it cannot go before the round is over
 */
class MapStore(
    private val cdnBase: String = System.getenv("MAPS_CDN_BASE") ?: DEFAULT_CDN_BASE,
    private val environment: String = System.getenv("MAPS_ENVIRONMENT") ?: DEFAULT_ENVIRONMENT,
    private val cacheDir: Path = Path.of(System.getenv("MAPS_CACHE_DIR") ?: DEFAULT_CACHE_DIR),
    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
    private val natsUrl: String? = System.getenv("NATS_URL")?.takeIf { it.isNotBlank() },
    private val roundGrace: Duration = DEFAULT_ROUND_GRACE,
) {
    private val log = LoggerFactory.getLogger(MapStore::class.java)

    /**
     * Lights one map at a time: lighting already uses every core, and a pod lighting several maps
     * at once would starve the rounds it is hosting.
     */
    private val lighter: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "grounds-map-light").apply { isDaemon = true }
        }

    /** Lights in progress or done, by bundle digest, so each map is lit once. */
    private val lighting = ConcurrentHashMap<String, CompletableFuture<Path?>>()

    /** When a round was last handed each digest, in epoch millis; see [evict]. */
    private val handedOut = ConcurrentHashMap<String, Long>()

    /** What the pin file says about one map. */
    data class PinnedMap(
        val address: String,
        val version: Int,
        val bundleSha256: String,
        val bundleUrl: String,
        /** The map service's lit copy of this bundle, when it has made one. */
        val lit: LitBundle? = null,
    )

    /**
     * A lit world (see [MapLight]) the map service made from a pinned bundle, as a bundle of its
     * own. Used when its [format] is the one this runtime lights to; otherwise the map is lit here.
     */
    data class LitBundle(val format: Int, val bundleSha256: String, val bundleUrl: String)

    /**
     * A map on local disk: [path] is the world directory, [lit] whether it is the lit copy, and
     * [downloaded] whether getting it took a download rather than the cache.
     */
    data class Fetched(
        val pinned: PinnedMap,
        val path: Path,
        val downloaded: Boolean,
        val lit: Boolean,
    ) {
        val version: Int
            get() = pinned.version
    }

    /** Every pinned map, by address. Empty when the pin file cannot be read. */
    fun pins(): Map<String, PinnedMap> {
        val url = "$cdnBase/pins/$environment.json"
        return try {
            get(url)?.let(::parsePins) ?: emptyMap()
        } catch (e: Exception) {
            log.warn("Could not read the pin file at {}", url, e)
            emptyMap()
        }
    }

    /**
     * What [address] is pinned to in this environment, or null if it is not pinned — a normal
     * answer, not an error: a map exists in the registry long before anyone moves a pin to it.
     */
    fun resolve(address: String): PinnedMap? =
        pins()[address].also {
            if (it == null) log.info("{} is not pinned in {}", address, environment)
        }

    /**
     * Every map pinned under [namespace] — the pool a round picks from when the matchmaker names no
     * map. Empty when the pin file cannot be read.
     */
    fun pinned(namespace: String): List<String> =
        pins().keys.filter { it.startsWith("$namespace/") }.sorted()

    /** Whether [pinned] is already unpacked here, so materialising it costs no download. */
    fun isCached(pinned: PinnedMap): Boolean = Files.exists(rawDir(pinned).resolve(COMPLETE_MARKER))

    /** Whether [pinned] is already lit here. */
    fun isLit(pinned: PinnedMap): Boolean = MapLight.isLit(litDir(pinned))

    /**
     * Downloads and unpacks [pinned] unless it already is, returning the world directory, or null
     * if it cannot be had. A download whose digest is not the pinned one is refused.
     */
    fun materialise(pinned: PinnedMap): Path? {
        val target = rawDir(pinned)
        if (Files.exists(target.resolve(COMPLETE_MARKER))) return target

        return try {
            Files.createDirectories(cacheDir)
            // Unpack beside the target and move into place, so an interrupted unpack never leaves
            // a half-world that a later call would hand out.
            val staging = Files.createTempDirectory(cacheDir, ".${pinned.bundleSha256.take(8)}-")
            try {
                unpack(pinned.address, pinned.bundleUrl, pinned.bundleSha256, staging)
                Files.createFile(staging.resolve(COMPLETE_MARKER))
                runCatching { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE) }
            } finally {
                // Left over when another call materialised the same map first: theirs is
                // identical, the digest says so.
                if (Files.exists(staging)) staging.toFile().deleteRecursively()
            }
            log.info("Materialised {} v{} at {}", pinned.address, pinned.version, target)
            target
        } catch (e: Exception) {
            log.error("Could not materialise {} from {}", pinned.address, pinned.bundleUrl, e)
            null
        }
    }

    /**
     * [pinned]'s lit copy, lighting it now if needed — blocking, seconds for a large map. Null if
     * the map cannot be had or lit. Concurrent calls for one map light it once.
     */
    fun lit(pinned: PinnedMap): Path? =
        lightAsync(pinned).join().also {
            handedOut[pinned.bundleSha256] = System.currentTimeMillis()
        }

    /** Starts lighting [pinned] unless it is lit or being lit. */
    fun lightAsync(pinned: PinnedMap): CompletableFuture<Path?> {
        val target = litDir(pinned)
        if (MapLight.isLit(target)) return CompletableFuture.completedFuture(target)
        return lighting.computeIfAbsent(pinned.bundleSha256) {
            CompletableFuture.supplyAsync(
                    {
                        try {
                            download(pinned)?.let {
                                return@supplyAsync it
                            }
                            val raw = materialise(pinned) ?: return@supplyAsync null
                            MapLight.bake(raw, target)
                            target
                        } catch (e: Exception) {
                            log.error("Could not light {} v{}", pinned.address, pinned.version, e)
                            null
                        }
                    },
                    lighter,
                )
                // Async: a callback that ran inside computeIfAbsent could not remove the entry.
                .whenCompleteAsync { result, _ ->
                    // A map that could not be had or lit is tried again by the next caller.
                    if (result == null) lighting.remove(pinned.bundleSha256)
                }
        }
    }

    /**
     * The map at [address] on local disk: lit if it already is, otherwise the unlit copy — and its
     * light is started in the background so the next round on it gets the lit one. Null when the
     * map is not pinned or cannot be downloaded.
     */
    fun fetch(address: String): Fetched? {
        val pinned = resolve(address) ?: return null
        handedOut[pinned.bundleSha256] = System.currentTimeMillis()
        val lit = litDir(pinned)
        if (MapLight.isLit(lit)) return Fetched(pinned, lit, downloaded = false, lit = true)
        // The map service's lit copy is a download like any other, not a wait for light.
        download(pinned)?.let {
            return Fetched(pinned, it, downloaded = true, lit = true)
        }
        val cached = isCached(pinned)
        val raw = materialise(pinned) ?: return null
        lightAsync(pinned)
        return Fetched(pinned, raw, downloaded = !cached, lit = false)
    }

    /**
     * Downloads and lights, in the background and one at a time, every map pinned under
     * [namespaces] — all maps a round on this server may pick, so the pick is lit when it is made.
     * Completes when all are done, then [evict]s what [pins] no longer names; a map that fails is
     * logged and skipped.
     */
    fun warm(
        namespaces: Collection<String>,
        pins: Map<String, PinnedMap> = pins(),
    ): CompletableFuture<Void> {
        val pinned = pins.values.filter { map -> namespaces.any { map.address.startsWith("$it/") } }
        val started = System.nanoTime()
        log.info("Warming {} maps of {}", pinned.size, namespaces)
        val all = pinned.sortedBy { it.address }.map(::lightAsync)
        return CompletableFuture.allOf(*all.toTypedArray()).whenComplete { _, _ ->
            log.info(
                "Warmed {} of {} maps of {} in {} s",
                all.count { it.getNow(null) != null },
                pinned.size,
                namespaces,
                (System.nanoTime() - started) / 1_000_000_000,
            )
            evict(pins)
        }
    }

    /**
     * Deletes every cached digest, unpacked and lit, that [pins] no longer names and no round was
     * handed within [roundGrace].
     *
     * Nothing goes when [pins] is empty: that is what an unreadable pin file looks like, and it
     * says nothing about what is still in use.
     */
    internal fun evict(pins: Map<String, PinnedMap>) {
        if (pins.isEmpty()) return
        val pinned = pins.values.mapTo(mutableSetOf()) { it.bundleSha256 }
        val cutoff = System.currentTimeMillis() - roundGrace.toMillis()
        for (dir in listOf(cacheDir, cacheDir.resolve(LIT_DIR))) {
            if (!Files.isDirectory(dir)) continue
            Files.list(dir)
                .use { it.toList() }
                .filter { path ->
                    val digest = path.fileName.toString()
                    // Only digest-named entries: staging directories start with a dot, and the lit
                    // directory sits among the unpacked ones.
                    DIGEST.matches(digest) && digest !in pinned && (handedOut[digest] ?: 0) < cutoff
                }
                .forEach { path ->
                    val digest = path.fileName.toString()
                    // Before the files: a finished light would otherwise keep handing out a path
                    // that no longer exists if this digest is pinned again.
                    lighting.remove(digest)
                    path.toFile().deleteRecursively()
                    log.info("Evicted {} {}, no longer pinned", dir.fileName, digest)
                }
        }
    }

    /**
     * [warm] now and then every [period], so a map pinned while the server runs — a new map, or a
     * new version of one — is lit before rounds pick it. Cheap when nothing changed: one read of
     * the pin file.
     */
    fun keepWarm(namespaces: Collection<String>, period: Duration = DEFAULT_WARM_PERIOD) {
        warmer.scheduleWithFixedDelay(
            { runCatching { warm(namespaces).join() } },
            0,
            period.toSeconds(),
            TimeUnit.SECONDS,
        )
        natsUrl?.let { warmOnAnnouncement(it, namespaces) }
    }

    /**
     * Warms from every pin file service-maps announces, so a new map is lit within seconds of its
     * pin rather than at the next timer tick. The announcement is the pin file itself, which the
     * CDN may not be serving yet. The timer stays: an announcement missed while disconnected is
     * caught by it.
     */
    private fun warmOnAnnouncement(url: String, namespaces: Collection<String>) {
        val connection =
            Nats.connectReconnectOnConnect(
                Options.Builder().server(url).connectionName("maps-$environment").build()
            )
        connection
            .createDispatcher { message ->
                val pins =
                    runCatching { parsePins(message.data.decodeToString()) }
                        .onFailure { log.warn("Ignoring an unreadable pin announcement", it) }
                        .getOrNull() ?: return@createDispatcher
                // On the warm thread, so it queues behind a timer warm instead of racing it.
                warmer.execute { runCatching { warm(namespaces, pins).join() } }
            }
            .subscribe("maps.pins.$environment")
    }

    /**
     * A random map pinned under [namespace], for a round the matchmaker named no map for — one that
     * is already lit when there is any, so a round never waits for light while others are ready.
     * Null when nothing is pinned or the pin file cannot be read.
     */
    fun pick(namespace: String, random: Random = Random.Default): String? {
        val pool = pins().values.filter { it.address.startsWith("$namespace/") }
        val lit = pool.filter(::isLit)
        return (lit.ifEmpty { pool }).randomOrNull(random)?.address.also {
            if (it != null)
                log.info(
                    "Picked {} of {} pinned {} maps ({} lit)",
                    it,
                    pool.size,
                    namespace,
                    lit.size,
                )
        }
    }

    private val warmer =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "grounds-map-warm").apply { isDaemon = true }
        }

    /**
     * Downloads the map service's lit copy of [pinned] into place, or returns null when there is
     * none of this runtime's format or it cannot be had — the map is then lit here instead.
     */
    private fun download(pinned: PinnedMap): Path? {
        val lit = pinned.lit?.takeIf { it.format == MapLight.FORMAT } ?: return null
        val target = litDir(pinned)
        if (MapLight.isLit(target)) return target
        return try {
            Files.createDirectories(target.parent)
            val staging =
                Files.createTempDirectory(target.parent, ".${pinned.bundleSha256.take(8)}-")
            try {
                unpack(pinned.address, lit.bundleUrl, lit.bundleSha256, staging)
                check(MapLight.isLit(staging)) { "the lit bundle of ${pinned.address} is not lit" }
                runCatching { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE) }
            } finally {
                if (Files.exists(staging)) staging.toFile().deleteRecursively()
            }
            log.info("Downloaded lit {} v{} at {}", pinned.address, pinned.version, target)
            target
        } catch (e: Exception) {
            log.warn("Could not download lit {} from {}", pinned.address, lit.bundleUrl, e)
            null
        }
    }

    private fun rawDir(pinned: PinnedMap): Path = cacheDir.resolve(pinned.bundleSha256)

    private fun litDir(pinned: PinnedMap): Path =
        cacheDir.resolve(LIT_DIR).resolve(pinned.bundleSha256)

    /** Parses the pin file into address to entry. */
    internal fun parsePins(body: String): Map<String, PinnedMap> {
        val maps =
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("maps") ?: return emptyMap()
        return maps.entrySet().associate { (address, value) ->
            val entry = value.asJsonObject
            address to
                PinnedMap(
                    address = address,
                    version = entry.get("version").asInt,
                    bundleSha256 = entry.get("bundleSha256").asString,
                    bundleUrl = entry.get("bundleUrl").asString,
                    lit =
                        entry
                            .get("lit")
                            ?.takeIf { it.isJsonObject }
                            ?.asJsonObject
                            ?.let { lit ->
                                LitBundle(
                                    format = lit.get("format").asInt,
                                    bundleSha256 = lit.get("bundleSha256").asString,
                                    bundleUrl = lit.get("bundleUrl").asString,
                                )
                            },
                )
        }
    }

    /** Downloads the bundle at [url] into [into], refusing it unless its digest is [sha256]. */
    private fun unpack(address: String, url: String, sha256: String, into: Path) {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() == 200) {
            "bundle fetch returned ${response.statusCode()} for $url"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(BufferedInputStream(response.body()), digest).use { raw ->
            // Not closed on its own: closing it would close [raw] before the digest has seen the
            // whole file.
            val tar = TarArchiveInputStream(ZstdCompressorInputStream(raw))
            var entry = tar.nextEntry
            while (entry != null) {
                val destination = resolveSafely(into, entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                entry = tar.nextEntry
            }
            // The digest covers the whole file, padding after the archive included.
            raw.transferTo(OutputStream.nullOutputStream())
        }
        val actual = HexFormat.of().formatHex(digest.digest())
        require(actual.equals(sha256, ignoreCase = true)) {
            "bundle of $address has digest $actual, pinned is $sha256"
        }
    }

    /**
     * Resolves an archive entry inside [root], refusing anything that escapes it: bundles originate
     * as uploads from map creators, and a `../` in an entry name is the oldest archive attack there
     * is.
     */
    private fun resolveSafely(root: Path, name: String): Path {
        val resolved = root.resolve(name).normalize()
        require(resolved.startsWith(root.normalize())) { "archive entry escapes the bundle: $name" }
        return resolved
    }

    private fun get(url: String): String? {
        val request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .GET()
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) return null
        require(response.statusCode() == 200) { "GET $url returned ${response.statusCode()}" }
        return response.body()
    }

    private companion object {
        const val DEFAULT_CDN_BASE = "https://maps.grounds.gg"
        const val DEFAULT_ENVIRONMENT = "stage"
        const val DEFAULT_CACHE_DIR = "/tmp/grounds-maps"

        /** Lit copies, by bundle digest; the suffix follows [MapLight.MARKER]'s version. */
        const val LIT_DIR = "lit-v1"

        /** Written last, so its presence means the unpack finished. */
        const val COMPLETE_MARKER = ".complete"

        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L

        val DEFAULT_WARM_PERIOD: Duration = Duration.ofMinutes(10)

        /** Longer than any round: a round reads its world until it ends. */
        val DEFAULT_ROUND_GRACE: Duration = Duration.ofHours(3)

        val DIGEST = Regex("[0-9a-f]{64}")
    }
}

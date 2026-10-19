package gg.grounds.runtime.core.maps

import com.google.gson.JsonParser
import gg.grounds.runtime.core.world.MapLight
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
 * @param environment which pin file to read. Read from `MAPS_ENVIRONMENT`, deliberately not
 *   `GROUNDS_ENV`: that one is the runtime's own and accepts only prod/test/dev, while a pin file
 *   is named after a deployment environment like `stage`
 * @param cacheDir where bundles are unpacked and lit, keyed by digest (`MAPS_CACHE_DIR`)
 */
class MapStore(
    private val cdnBase: String = System.getenv("MAPS_CDN_BASE") ?: DEFAULT_CDN_BASE,
    private val environment: String = System.getenv("MAPS_ENVIRONMENT") ?: DEFAULT_ENVIRONMENT,
    private val cacheDir: Path = Path.of(System.getenv("MAPS_CACHE_DIR") ?: DEFAULT_CACHE_DIR),
    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build(),
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

    /** What the pin file says about one map. */
    data class PinnedMap(
        val address: String,
        val version: Int,
        val bundleSha256: String,
        val bundleUrl: String,
    )

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
                unpack(pinned, staging)
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
    fun lit(pinned: PinnedMap): Path? = lightAsync(pinned).join()

    /** Starts lighting [pinned] unless it is lit or being lit. */
    fun lightAsync(pinned: PinnedMap): CompletableFuture<Path?> {
        val target = litDir(pinned)
        if (MapLight.isLit(target)) return CompletableFuture.completedFuture(target)
        return lighting.computeIfAbsent(pinned.bundleSha256) {
            CompletableFuture.supplyAsync(
                    {
                        try {
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
        val lit = litDir(pinned)
        if (MapLight.isLit(lit)) return Fetched(pinned, lit, downloaded = false, lit = true)
        val cached = isCached(pinned)
        val raw = materialise(pinned) ?: return null
        lightAsync(pinned)
        return Fetched(pinned, raw, downloaded = !cached, lit = false)
    }

    /**
     * Downloads and lights, in the background and one at a time, every map pinned under
     * [namespaces] — all maps a round on this server may pick, so the pick is lit when it is made.
     * Completes when all are done; a map that fails is logged and skipped.
     */
    fun warm(namespaces: Collection<String>): CompletableFuture<Void> {
        val pinned =
            pins().values.filter { map -> namespaces.any { map.address.startsWith("$it/") } }
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
                )
        }
    }

    private fun unpack(pinned: PinnedMap, into: Path) {
        val request = HttpRequest.newBuilder(URI.create(pinned.bundleUrl)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        require(response.statusCode() == 200) {
            "bundle fetch returned ${response.statusCode()} for ${pinned.bundleUrl}"
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
        require(actual.equals(pinned.bundleSha256, ignoreCase = true)) {
            "bundle of ${pinned.address} has digest $actual, pinned is ${pinned.bundleSha256}"
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
    }
}

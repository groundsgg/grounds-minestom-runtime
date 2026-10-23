package gg.grounds.runtime.core.maps

import com.sun.net.httpserver.HttpServer
import gg.grounds.runtime.core.world.MapLight
import gg.grounds.runtime.core.world.TestWorlds
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.minestom.server.MinecraftServer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MapStoreTest {
    private lateinit var server: HttpServer
    private lateinit var cache: Path
    private val bundles = mutableMapOf<String, ByteArray>()
    private val bundleFetches = AtomicInteger()
    private var pins = "{}"

    @BeforeEach
    fun start() {
        MinecraftServer.init()
        cache = Files.createTempDirectory("grounds-maps")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body =
                when {
                    path == "/pins/stage.json" -> pins.toByteArray()
                    path.startsWith("/bundle/") -> {
                        bundleFetches.incrementAndGet()
                        bundles[path.removePrefix("/bundle/")]
                    }
                    else -> null
                }
            if (body == null) exchange.sendResponseHeaders(404, -1)
            else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        MinecraftServer.stopCleanly()
    }

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun store(roundGrace: Duration = Duration.ofHours(3)) =
        MapStore(
            cdnBase = base(),
            environment = "stage",
            cacheDir = cache,
            natsUrl = null,
            roundGrace = roundGrace,
        )

    /** Publishes [world] as a bundle and pins it as [address]; [digest] overrides the real one. */
    private fun publish(vararg maps: Pair<String, Path>, digest: String? = null) {
        val entries =
            maps.joinToString(",") { (address, world) ->
                val bundle = bundle(world)
                val sha =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bundle))
                bundles[sha] = bundle
                """"$address": {"version": 2, "bundleSha256": "${digest ?: sha}",
                   "bundleUrl": "${base()}/bundle/$sha"}"""
            }
        pins = """{"environment": "stage", "maps": {$entries}}"""
    }

    /**
     * Pins [world] as [address] together with the map service's lit copy of it, of light [format];
     * [litDigest] overrides the lit copy's real digest.
     */
    private fun publishWithLit(
        address: String,
        world: Path,
        format: Int = MapLight.FORMAT,
        litDigest: String? = null,
    ) {
        val raw = bundle(world)
        val rawSha = sha(raw)
        bundles[rawSha] = raw
        val litWorld = world.resolveSibling("${world.fileName}-service-lit")
        MapLight.bake(world, litWorld)
        val lit = bundle(litWorld)
        val litSha = sha(lit)
        bundles[litSha] = lit
        pins =
            """{"environment": "stage", "maps": {"$address": {"version": 2,
               "bundleSha256": "$rawSha", "bundleUrl": "${base()}/bundle/$rawSha",
               "lit": {"format": $format, "bundleSha256": "${litDigest ?: litSha}",
                       "bundleUrl": "${base()}/bundle/$litSha"}}}}"""
    }

    private fun sha(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun `the map service's lit copy is downloaded instead of lighting the map here`() {
        publishWithLit("survivalgames/flat", TestWorlds.unlit())
        val store = store()

        val fetched = store.fetch("survivalgames/flat")!!

        assertTrue(fetched.lit)
        assertTrue(MapLight.isLit(fetched.path))
        assertEquals(1, bundleFetches.get(), "only the lit bundle, never the raw one")
        assertTrue(store.isLit(fetched.pinned))
        assertFalse(store.isCached(fetched.pinned))
    }

    @Test
    fun `a lit copy of another light format is ignored and the map lit here`() {
        publishWithLit("survivalgames/flat", TestWorlds.unlit(), format = MapLight.FORMAT + 1)
        val store = store()

        val fetched = store.fetch("survivalgames/flat")!!

        assertFalse(fetched.lit)
        assertTrue(MapLight.isLit(store.lightAsync(fetched.pinned).get(60, TimeUnit.SECONDS)!!))
    }

    @Test
    fun `a lit copy that is not its pinned digest is refused and the map lit here`() {
        publishWithLit("survivalgames/flat", TestWorlds.unlit(), litDigest = "00".repeat(32))
        val store = store()

        val fetched = store.fetch("survivalgames/flat")!!

        assertFalse(fetched.lit)
        assertTrue(fetched.downloaded)
        assertTrue(MapLight.isLit(store.lightAsync(fetched.pinned).get(60, TimeUnit.SECONDS)!!))
    }

    @Test
    fun `a pinned map is downloaded once, checked against its digest, then lit`() {
        publish("survivalgames/flat" to TestWorlds.unlit())
        val store = store()

        val first = store.fetch("survivalgames/flat")!!
        assertTrue(first.downloaded)
        assertFalse(first.lit)
        assertFalse(MapLight.isLit(first.path))
        assertEquals(2, first.version)

        val lit = store.lightAsync(first.pinned).get(60, TimeUnit.SECONDS)!!
        val second = store.fetch("survivalgames/flat")!!

        assertTrue(second.lit)
        assertFalse(second.downloaded)
        assertEquals(lit, second.path)
        assertTrue(MapLight.isLit(second.path))
        assertEquals(1, bundleFetches.get())
    }

    @Test
    fun `a bundle that is not the pinned digest is refused`() {
        publish("survivalgames/flat" to TestWorlds.unlit(), digest = "00".repeat(32))

        assertNull(store().fetch("survivalgames/flat"))
        assertEquals(
            0,
            Files.list(cache).use { s ->
                s.filter { !it.fileName.toString().startsWith("lit") }.count()
            },
        )
    }

    @Test
    fun `an unpinned map is null, not an error`() {
        publish("survivalgames/flat" to TestWorlds.unlit())

        assertNull(store().fetch("survivalgames/other"))
        assertEquals(listOf("survivalgames/flat"), store().pinned("survivalgames"))
        assertEquals(emptyList<String>(), store().pinned("getdown"))
    }

    @Test
    fun `concurrent rounds on one unlit map light it once`() {
        publish("survivalgames/flat" to TestWorlds.unlit())
        val store = store()
        val pinned = store.resolve("survivalgames/flat")!!

        val lights = (1..4).map { store.lightAsync(pinned) }

        assertTrue(lights.all { it === lights[0] })
        assertTrue(MapLight.isLit(lights[0].get(60, TimeUnit.SECONDS)!!))
        assertEquals(1, bundleFetches.get())
    }

    @Test
    fun `warming lights every map of the server's namespaces and nothing else`() {
        publish(
            "survivalgames/a" to distinct("a"),
            "survivalgames-dm/b" to distinct("b"),
            "getdown/c" to distinct("c"),
        )
        val store = store()

        store.warm(listOf("survivalgames", "survivalgames-dm")).get(120, TimeUnit.SECONDS)

        assertTrue(store.isLit(store.resolve("survivalgames/a")!!))
        assertTrue(store.isLit(store.resolve("survivalgames-dm/b")!!))
        assertFalse(store.isLit(store.resolve("getdown/c")!!))
        assertFalse(store.isCached(store.resolve("getdown/c")!!))
        assertTrue(store.fetch("survivalgames/a")!!.lit)
    }

    @Test
    fun `a re-pinned map's old version is deleted once no round needs it`() {
        publish("survivalgames/a" to distinct("v1"))
        val store = store(roundGrace = Duration.ZERO)
        store.warm(listOf("survivalgames")).get(120, TimeUnit.SECONDS)
        val old = store.resolve("survivalgames/a")!!

        publish("survivalgames/a" to distinct("v2"))
        store.warm(listOf("survivalgames")).get(120, TimeUnit.SECONDS)

        assertFalse(store.isCached(old))
        assertFalse(store.isLit(old))
        assertTrue(store.isLit(store.resolve("survivalgames/a")!!))
    }

    @Test
    fun `a version a round was just handed stays until the round can be over`() {
        publish("survivalgames/a" to distinct("v1"))
        val store = store()
        val playing = store.fetch("survivalgames/a")!!

        publish("survivalgames/a" to distinct("v2"))
        store.warm(listOf("survivalgames")).get(120, TimeUnit.SECONDS)

        assertTrue(Files.isDirectory(playing.path))
    }

    @Test
    fun `an unreadable pin file deletes nothing`() {
        publish("survivalgames/a" to distinct("v1"))
        val store = store(roundGrace = Duration.ZERO)
        store.warm(listOf("survivalgames")).get(120, TimeUnit.SECONDS)
        val cached = store.resolve("survivalgames/a")!!

        store.evict(emptyMap())

        assertTrue(store.isLit(cached))
    }

    @Test
    fun `a random pick prefers maps that are already lit`() {
        publish("survivalgames/a" to distinct("a"), "survivalgames/b" to distinct("b"))
        val store = store()

        val first = store.pick("survivalgames")!!
        assertTrue(first in listOf("survivalgames/a", "survivalgames/b"))
        store.lit(store.resolve("survivalgames/b")!!)

        repeat(20) { assertEquals("survivalgames/b", store.pick("survivalgames")) }
        assertNull(store.pick("getdown"))
    }

    /** The real `pins/stage.json` as the CDN served it, copied verbatim. */
    @Test
    fun `parses the real pin file`() {
        val real =
            """{"environment":"stage","maps":{"lobby/mainlobby":{"version":1,""" +
                """"bundleSha256":"c3b9d2734e5d28d29d7b96b7b9ab4cedfdf4e51d73200787b105726aa364ab4b",""" +
                """"bundleUrl":"https://maps.grounds.gg/bundle/sha256/c3/c3b9d2734e5d28d29d7b96b7b9ab4cedfdf4e51d73200787b105726aa364ab4b.tar.zst",""" +
                """"sizeBytes":47223636,"estLoadedMib":null}}}"""

        val lobby = store().parsePins(real).getValue("lobby/mainlobby")

        assertEquals(1, lobby.version)
        assertEquals(
            "c3b9d2734e5d28d29d7b96b7b9ab4cedfdf4e51d73200787b105726aa364ab4b",
            lobby.bundleSha256,
        )
        assertTrue(lobby.bundleUrl.endsWith(".tar.zst"))
        assertNull(store().parsePins(real)["survivalgames/4-4-farm"])
        assertTrue(store().parsePins("""{"environment":"prod","maps":{}}""").isEmpty())
    }

    @Test
    fun `an address with a slash in its namespace survives parsing`() {
        // Creator maps are `u/<creator>/<name>`, so an address is not reliably two segments.
        val pins =
            store()
                .parsePins(
                    """{"environment":"stage","maps":{"u/hendrik/my-map":""" +
                        """{"version":2,"bundleSha256":"ab","bundleUrl":"https://x/ab.tar.zst"}}}"""
                )

        assertEquals(2, pins.getValue("u/hendrik/my-map").version)
    }

    /** A test world whose bundle differs from every other's, so the two get different digests. */
    private fun distinct(name: String): Path =
        TestWorlds.unlit().also { Files.writeString(it.resolve("name.txt"), name) }

    private fun bundle(world: Path): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(ZstdCompressorOutputStream(out)).use { tar ->
            Files.walk(world).use { paths ->
                for (path in paths.sorted().toList()) {
                    val name = world.relativize(path).toString()
                    if (name.isEmpty()) continue
                    if (Files.isDirectory(path)) {
                        tar.putArchiveEntry(TarArchiveEntry("$name/"))
                    } else {
                        val entry = TarArchiveEntry(name).apply { size = Files.size(path) }
                        tar.putArchiveEntry(entry)
                        Files.copy(path, tar)
                    }
                    tar.closeArchiveEntry()
                }
            }
        }
        return out.toByteArray()
    }
}

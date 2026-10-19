package gg.grounds.runtime.core.maps

import com.sun.net.httpserver.HttpServer
import gg.grounds.runtime.core.world.MapLight
import gg.grounds.runtime.core.world.TestWorlds
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

    private fun store() = MapStore(cdnBase = base(), environment = "stage", cacheDir = cache)

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
            "survivalgames/a" to TestWorlds.unlit(),
            "survivalgames-dm/b" to TestWorlds.unlit(),
            "getdown/c" to TestWorlds.unlit(),
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
    fun `a random pick prefers maps that are already lit`() {
        publish("survivalgames/a" to TestWorlds.unlit(), "survivalgames/b" to TestWorlds.unlit())
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

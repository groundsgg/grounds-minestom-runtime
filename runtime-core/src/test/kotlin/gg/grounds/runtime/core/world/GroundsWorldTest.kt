package gg.grounds.runtime.core.world

import java.nio.file.Path
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.ChunkLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.LightingChunk
import net.minestom.server.world.DimensionType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GroundsWorldTest {

    @BeforeEach
    fun init() {
        MinecraftServer.init()
    }

    @AfterEach
    fun stop() {
        MinecraftServer.stopCleanly()
    }

    @Test
    fun `world is sent to clients under the map's key and seed`(@TempDir map: Path) {
        val identity = WorldIdentity.of("survivalgames/breeze", 3)

        val instance = GroundsWorld.open(map, identity)

        assertEquals("grounds:survivalgames_breeze_v3", instance.dimensionName)
        assertEquals(identity.hashedSeed, instance.hashedSeed)
        assertEquals(DimensionType.OVERWORLD, instance.dimensionType)
    }

    @Test
    fun `world is registered like createInstanceContainer`() {
        val instance = GroundsWorld.create(WorldIdentity.of("duel/classic"))

        assertTrue(MinecraftServer.getInstanceManager().instances.contains(instance))
    }

    @Test
    fun `opened world loads lighting chunks`(@TempDir map: Path) {
        val instance = GroundsWorld.open(map, WorldIdentity.of("lobby/mainlobby"))

        assertInstanceOf(LightingChunk::class.java, instance.loadChunk(0, 0).join())
    }

    @Test
    fun `two rounds on one map are separate worlds with one identity`(@TempDir map: Path) {
        val identity = WorldIdentity.of("bedwars/sky")

        val first = GroundsWorld.open(map, identity)
        val second = GroundsWorld.open(map, identity)

        assertNotSame(first, second)
        assertEquals(first.dimensionName, second.dimensionName)
    }

    @Test
    fun `relighting loader marks every loaded chunk for lighting`() {
        val instance = GroundsWorld.create(WorldIdentity.of("test/relight"))
        val chunk = RecordingChunk(instance)

        val loaded = RelightingLoader(FixedLoader(chunk)).loadChunk(instance, 0, 0)

        assertSame(chunk, loaded)
        assertTrue(chunk.generated)
    }

    @Test
    fun `relighting loader passes a missing chunk through for generation`() {
        val instance = GroundsWorld.create(WorldIdentity.of("test/relight"))

        assertNull(RelightingLoader(FixedLoader(null)).loadChunk(instance, 0, 0))
    }

    @Test
    fun `relighting loader keeps the delegate's behaviour`() {
        val instance = GroundsWorld.create(WorldIdentity.of("test/relight"))
        val chunk = RecordingChunk(instance)
        val delegate = FixedLoader(chunk)
        val loader = RelightingLoader(delegate)

        loader.loadInstance(instance)
        loader.saveInstance(instance)
        loader.saveChunk(chunk)
        loader.saveChunks(listOf(chunk))
        loader.unloadChunk(chunk)

        assertTrue(loader.supportsParallelLoading())
        assertTrue(loader.supportsParallelSaving())
        assertEquals(
            listOf("loadInstance", "saveInstance", "saveChunk", "saveChunk", "unloadChunk"),
            delegate.calls,
        )
    }

    private class RecordingChunk(instance: Instance) : LightingChunk(instance, 0, 0) {
        var generated = false

        override fun onGenerate() {
            generated = true
        }
    }

    private class FixedLoader(private val chunk: Chunk?) : ChunkLoader {
        val calls = mutableListOf<String>()

        override fun loadInstance(instance: Instance) {
            calls += "loadInstance"
        }

        override fun loadChunk(instance: Instance, chunkX: Int, chunkZ: Int): Chunk? = chunk

        override fun saveInstance(instance: Instance) {
            calls += "saveInstance"
        }

        override fun saveChunk(chunk: Chunk) {
            calls += "saveChunk"
        }

        override fun unloadChunk(chunk: Chunk) {
            calls += "unloadChunk"
        }

        override fun supportsParallelLoading() = true

        override fun supportsParallelSaving() = true
    }
}

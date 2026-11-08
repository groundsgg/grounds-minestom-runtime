package gg.grounds.runtime.core.world

import java.nio.file.Files
import java.nio.file.Path
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoveredMapLightTest {
    @TempDir lateinit var root: Path

    @BeforeEach
    fun init() {
        MinecraftServer.init()
    }

    @AfterEach
    fun stop() {
        MinecraftServer.stopCleanly()
    }

    @Test
    fun `floor lighting passes through carpet within a section`() {
        checkCover(Block.ORANGE_CARPET, 64, 13)
    }

    @Test
    fun `floor lighting passes through carpet across a section boundary`() {
        checkCover(Block.WHITE_CARPET, 63, 13)
    }

    @Test
    fun `floor lighting passes through glass`() {
        checkCover(Block.GLASS, 64, 13)
    }

    @Test
    fun `floor lighting cannot pass through a stone cover`() {
        checkCover(Block.STONE, 64, 0)
    }

    private fun checkCover(cover: Block, sourceY: Int, expected: Int) {
        val raw = Files.createDirectory(root.resolve("raw"))
        val instances = MinecraftServer.getInstanceManager()
        val authored = instances.createInstanceContainer(AnvilLoader(raw))
        authored.setGenerator { unit ->
            unit.modifier().fillHeight(sourceY - 1, sourceY + 1, Block.STONE)
            unit.modifier().setBlock(8, sourceY, 8, Block.SEA_LANTERN)
            unit.modifier().setBlock(8, sourceY + 1, 8, cover)
        }
        authored.loadChunk(0, 0).join()
        authored.saveChunksToStorage().join()
        instances.unregisterInstance(authored)

        val lit = root.resolve("lit")
        MapLight.bake(raw, lit)
        val loaded = GroundsWorld.prepare(lit, WorldIdentity.of("test/covered")).ready.join()
        val chunk = loaded.getChunk(0, 0)!!
        assertEquals(15, TestWorlds.blockLightAt(chunk, 8, sourceY, 8))
        assertEquals(
            expected,
            TestWorlds.blockLightAt(chunk, 8, sourceY + 2, 8),
            "light above ${cover.key()} at source Y=$sourceY",
        )
    }
}

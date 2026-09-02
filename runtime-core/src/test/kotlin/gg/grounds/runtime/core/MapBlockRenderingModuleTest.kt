package gg.grounds.runtime.core

import gg.grounds.modules.ServiceRegistry
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import java.nio.file.Path
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.utils.block.BlockUtils
import net.minestom.server.world.DimensionType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MapBlockRenderingModuleTest {
    private val context =
        object : GroundsServerContext {
            override val serverType = ServerType.LOBBY
            override val environment = RuntimeEnvironment.TEST
            override val services: ServiceRegistry
                get() = error("Rendering needs no services")

            override fun eventNode(name: String): EventNode<Event> =
                error("Rendering needs no events")

            override fun onShutdown(action: () -> Unit) = error("Rendering needs no tasks")
        }

    @BeforeEach
    fun init() {
        MinecraftServer.init()
    }

    @AfterEach
    fun stop() {
        MinecraftServer.stopCleanly()
    }

    @Test
    fun `dummy handler drops a map head profile`() {
        val block =
            Block.PLAYER_HEAD.withNbt(headNbt())
                .withHandler(MinecraftServer.getBlockManager().getHandlerOrDummy("minecraft:skull"))
        assertFalse(BlockUtils.extractClientNbt(block)!!.keySet().contains("profile"))
    }

    @Test
    fun `floor and wall heads retain profile while private tags stay server side`() {
        MapBlockRenderingModule().install(context)
        val handler = MinecraftServer.getBlockManager().getHandler("minecraft:skull")!!
        for (type in listOf(Block.PLAYER_HEAD, Block.PLAYER_WALL_HEAD)) {
            val original = headNbt()
            val block = type.withNbt(original).withHandler(handler)
            val sent = BlockUtils.extractClientNbt(block)!!
            assertEquals(original.getCompound("profile"), sent.getCompound("profile"))
            assertEquals(setOf("profile", "note_block_sound", "custom_name"), sent.keySet())
            assertEquals(original, block.nbt())
            assertFalse(handler.isTickable)
        }
    }

    @Test
    fun `signs and banners send only their rendering data`() {
        MapBlockRenderingModule().install(context)
        val cases =
            listOf(
                Triple(
                    Block.OAK_SIGN,
                    "minecraft:sign",
                    listOf("front_text", "back_text", "is_waxed"),
                ),
                Triple(
                    Block.OAK_HANGING_SIGN,
                    "minecraft:hanging_sign",
                    listOf("front_text", "back_text", "is_waxed"),
                ),
                Triple(Block.WHITE_BANNER, "minecraft:banner", listOf("patterns", "CustomName")),
            )
        for ((type, id, tags) in cases) {
            val nbt = CompoundBinaryTag.builder().putString("server_only", "hidden")
            tags.forEach { nbt.putString(it, "authored-$it") }
            val handler = MinecraftServer.getBlockManager().getHandler(id)!!
            val sent = BlockUtils.extractClientNbt(type.withNbt(nbt.build()).withHandler(handler))!!
            assertEquals(tags.toSet(), sent.keySet())
            tags.forEach { assertEquals("authored-$it", sent.getString(it)) }
        }
    }

    @Test
    fun `registration preserves custom handlers and is idempotent`() {
        val manager = MinecraftServer.getBlockManager()
        val custom =
            object : BlockHandler {
                override fun getKey() = Key.key("minecraft:skull")
            }
        manager.registerHandler("minecraft:skull") { custom }
        val module = MapBlockRenderingModule()
        module.install(context)
        val sign = manager.getHandler("minecraft:sign")
        module.install(context)
        assertSame(custom, manager.getHandler("minecraft:skull"))
        assertSame(sign, manager.getHandler("minecraft:sign"))
        assertNull(manager.getHandler("minecraft:chest"))
    }

    @Test
    fun `missing optional render tags are not fabricated`() {
        MapBlockRenderingModule().install(context)
        val handler = MinecraftServer.getBlockManager().getHandler("minecraft:skull")!!
        assertTrue(BlockUtils.extractClientNbt(Block.PLAYER_HEAD.withHandler(handler))!!.isEmpty)
    }

    @Test
    fun `Anvil map reload keeps the texture profile and rendering handler`(@TempDir world: Path) {
        MapBlockRenderingModule().install(context)
        val manager = MinecraftServer.getInstanceManager()
        val source = manager.createInstanceContainer()
        val loader = AnvilLoader(world, DimensionType.OVERWORLD.key())
        source.chunkLoader = loader
        val chunk = source.loadChunk(0, 0).join()
        val handler = MinecraftServer.getBlockManager().getHandler("minecraft:skull")!!
        source.setBlock(1, 64, 1, Block.PLAYER_HEAD.withNbt(headNbt()).withHandler(handler))
        source.saveChunkToStorage(chunk).join()
        source.unloadChunk(chunk)
        val target = manager.createInstanceContainer()
        target.chunkLoader = AnvilLoader(world, DimensionType.OVERWORLD.key())
        target.loadChunk(0, 0).join()
        val loaded = target.getBlock(1, 64, 1)
        assertSame(handler, loaded.handler())
        assertEquals(
            headNbt().getCompound("profile"),
            BlockUtils.extractClientNbt(loaded)!!.getCompound("profile"),
        )
        assertEquals("hidden", loaded.nbt()!!.getString("server_only"))
        assertFalse(BlockUtils.extractClientNbt(loaded)!!.keySet().contains("server_only"))
    }

    private fun headNbt() =
        CompoundBinaryTag.builder()
            .put(
                "profile",
                CompoundBinaryTag.builder()
                    .putString("name", "AuthoredHead")
                    .put(
                        "properties",
                        ListBinaryTag.from(
                            listOf(
                                CompoundBinaryTag.builder()
                                    .putString("name", "textures")
                                    .putString("value", "eyJ0ZXh0dXJlcyI6e319")
                                    .build()
                            )
                        ),
                    )
                    .build(),
            )
            .putString("note_block_sound", "minecraft:block.note_block.pling")
            .putString("custom_name", "Decoration")
            .putString("server_only", "hidden")
            .build()
}

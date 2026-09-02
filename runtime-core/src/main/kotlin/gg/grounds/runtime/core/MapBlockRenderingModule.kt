package gg.grounds.runtime.core

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import net.kyori.adventure.key.Key
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.tag.Tag

/**
 * Preserves authored block-entity appearance without enabling gameplay interactions.
 *
 * Install before any module loads map chunks: AnvilLoader attaches a handler to each block when
 * loading it, and its dummy fallback exposes no client NBT. Existing handlers retain ownership.
 * Registration lives for the Minestom process; stopping this module cannot detach handlers from
 * already-loaded blocks. A new process gets its own BlockManager.
 */
class MapBlockRenderingModule : GroundsModule {
    override val id = "grounds.map-rendering"

    override fun install(ctx: GroundsServerContext) {
        val manager = MinecraftServer.getBlockManager()
        for ((namespace, tags) in RENDER_TAGS) {
            if (manager.getHandler(namespace) != null) continue
            val handler = RenderingHandler(Key.key(namespace), tags.map { Tag.NBT(it) })
            manager.registerHandler(namespace) { handler }
        }
    }

    private class RenderingHandler(private val key: Key, private val tags: List<Tag<*>>) :
        BlockHandler {
        override fun getKey(): Key = key

        override fun getBlockEntityTags(): Collection<Tag<*>> = tags
    }

    private companion object {
        val SIGN_TAGS = listOf("front_text", "back_text", "is_waxed")
        val RENDER_TAGS =
            mapOf(
                "minecraft:skull" to listOf("profile", "note_block_sound", "custom_name"),
                "minecraft:sign" to SIGN_TAGS,
                "minecraft:hanging_sign" to SIGN_TAGS,
                "minecraft:banner" to listOf("patterns", "CustomName"),
            )
    }
}

package gg.grounds.runtime.core.world

import java.nio.file.Files
import java.nio.file.Path
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.DynamicChunk
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.data.LightData

/** Maps on disk the way converted maps arrive: blocks, no light. */
internal object TestWorlds {
    const val GROUND_Y = 64

    /** Top of the roof over part of chunk 0,0, so light has to reach under it sideways. */
    const val ROOF_Y = GROUND_Y + 5

    /** A light source, so block light is part of what is compared. */
    val GLOWSTONE = Vec(3.0, GROUND_Y.toDouble(), 12.0)

    /** Chunks with blocks: stone up to [GROUND_Y]. */
    val GROUND = (-2..2).flatMap { x -> (-2..2).map { z -> x to z } }

    /** Saved but all air, as converted maps carry thousands of. */
    val AIR = listOf(4 to 0, 4 to 1)

    /**
     * A flat stone world over [GROUND] with a roof and a glowstone, plus the all-air chunks [AIR],
     * saved by a loader that stores no light.
     */
    fun unlit(): Path {
        val dir = Files.createTempDirectory("grounds-world")
        val instances = MinecraftServer.getInstanceManager()
        val instance = instances.createInstanceContainer(AnvilLoader(dir))
        instance.setGenerator { unit ->
            val start = unit.absoluteStart()
            if ((start.chunkX() to start.chunkZ()) !in GROUND) return@setGenerator
            unit.modifier().fillHeight(start.blockY(), GROUND_Y, Block.STONE)
            if (start.chunkX() == 0 && start.chunkZ() == 0) {
                for (x in 0..7) for (z in 0..7) unit.modifier().setBlock(x, ROOF_Y, z, Block.STONE)
                unit.modifier().setBlock(GLOWSTONE, Block.GLOWSTONE)
            }
        }
        (GROUND + AIR).forEach { (x, z) -> instance.loadChunk(x, z).join() }
        instance.saveChunksToStorage().join()
        instances.unregisterInstance(instance)
        return dir
    }

    /** The light a player is sent with [chunk], as Minestom computes it for the chunk's packet. */
    fun lightSentFor(chunk: Chunk): LightData {
        val compute =
            DynamicChunk::class.java.getDeclaredMethod("createLightData", Boolean::class.java)
        compute.isAccessible = true
        return compute.invoke(chunk, true) as LightData
    }

    fun sameLight(a: LightData, b: LightData): Boolean =
        a.skyMask() == b.skyMask() &&
            a.blockMask() == b.blockMask() &&
            a.emptySkyMask() == b.emptySkyMask() &&
            a.emptyBlockMask() == b.emptyBlockMask() &&
            a.skyLight().size == b.skyLight().size &&
            a.skyLight().indices.all { a.skyLight()[it].contentEquals(b.skyLight()[it]) } &&
            a.blockLight().size == b.blockLight().size &&
            a.blockLight().indices.all { a.blockLight()[it].contentEquals(b.blockLight()[it]) }

    fun skyAt(chunk: Chunk, x: Int, y: Int, z: Int): Int =
        chunk.getSectionAt(y).skyLight().getLevel(x and 15, y and 15, z and 15)

    fun blockLightAt(chunk: Chunk, x: Int, y: Int, z: Int): Int =
        chunk.getSectionAt(y).blockLight().getLevel(x and 15, y and 15, z and 15)
}

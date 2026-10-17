package gg.grounds.runtime.core.world

import java.nio.file.Path
import java.util.UUID
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.ChunkLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.registry.RegistryKey
import net.minestom.server.world.DimensionType

/**
 * The one way games and the lobby open a world, in place of
 * `InstanceManager.createInstanceContainer`.
 *
 * Every world is sent to clients under its own [WorldIdentity], so LOD mods keep one cache per map,
 * and uses [LightingChunk], so Minestom computes its light. The instance is registered with the
 * instance manager, as `createInstanceContainer` does.
 *
 * Moving a player between worlds with different identities sends a Respawn, so the client starts a
 * fresh level; between worlds of the same identity it does not.
 */
object GroundsWorld {

    /**
     * Opens the Anvil map in [worldPath], the directory that contains `region/`.
     *
     * Every chunk is relit when it loads. Converted maps carry no sky or block light, and Minestom
     * only lights chunks it generates, so a loaded chunk would otherwise reach the client dark.
     */
    fun open(
        worldPath: Path,
        identity: WorldIdentity,
        dimensionType: RegistryKey<DimensionType> = DimensionType.OVERWORLD,
    ): InstanceContainer =
        register(identity, RelightingLoader(AnvilLoader(worldPath)), dimensionType)

    /** A world with nothing on disk, for arenas a game builds or generates itself. */
    fun create(
        identity: WorldIdentity,
        dimensionType: RegistryKey<DimensionType> = DimensionType.OVERWORLD,
    ): InstanceContainer = register(identity, null, dimensionType)

    private fun register(
        identity: WorldIdentity,
        loader: ChunkLoader?,
        dimensionType: RegistryKey<DimensionType>,
    ): InstanceContainer {
        val instance = InstanceContainer(UUID.randomUUID(), dimensionType, loader, identity.key)
        instance.hashedSeed = identity.hashedSeed
        instance.setChunkSupplier(::LightingChunk)
        MinecraftServer.getInstanceManager().registerInstance(instance)
        return instance
    }
}

/**
 * Treats every chunk [delegate] loads like a generated one for lighting: `onGenerate` invalidates
 * the light of a [LightingChunk] and its loaded neighbours, so it is computed before the chunk is
 * first sent. Everything else, such as `loadInstance`, `unloadChunk` and parallel loading, is
 * [delegate]'s own.
 */
internal class RelightingLoader(private val delegate: ChunkLoader) : ChunkLoader by delegate {
    override fun loadChunk(instance: Instance, chunkX: Int, chunkZ: Int): Chunk? =
        delegate.loadChunk(instance, chunkX, chunkZ)?.also { it.onGenerate() }
}

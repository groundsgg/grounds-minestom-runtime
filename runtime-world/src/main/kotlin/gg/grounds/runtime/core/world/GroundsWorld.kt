package gg.grounds.runtime.core.world

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
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
     * Opens the Anvil map in [worldPath], the directory that contains `region/`. Chunks load as
     * players come near them.
     *
     * A lit world (see [MapLight]) is sent with the light it stores. Any other map is relit chunk
     * by chunk as it loads: converted maps carry no light, and Minestom only lights chunks it
     * generates, so a loaded chunk would otherwise reach the client dark. That relight runs when a
     * chunk is first sent, on the tick — prefer [prepare] with a lit world for anything players
     * join.
     */
    fun open(
        worldPath: Path,
        identity: WorldIdentity,
        dimensionType: RegistryKey<DimensionType> = DimensionType.OVERWORLD,
    ): InstanceContainer = register(container(identity, loader(worldPath), dimensionType))

    /**
     * Opens the map in [worldPath] and loads **all** of it off the tick thread, so it is ready
     * before anyone joins: no chunk loads while players move, and nothing is lit on the tick.
     *
     * The world is built on an instance nobody can join or tick yet: every chunk the map has is
     * loaded, [edit] runs (blocks a round changes, such as chests — set them on the instance as
     * usual), whatever still needs light is lit, and only then is the instance registered and
     * [PreparedWorld.ready] completed. A lit world needs no light here beyond what [edit] changed;
     * any other map is lit in full, which takes seconds but still stays off the tick.
     *
     * [PreparedWorld.instance] is usable at once for anything but players — listeners, tags.
     */
    fun prepare(
        worldPath: Path,
        identity: WorldIdentity,
        dimensionType: RegistryKey<DimensionType> = DimensionType.OVERWORLD,
        edit: (InstanceContainer) -> Unit = {},
    ): PreparedWorld {
        val instance = container(identity, loader(worldPath), dimensionType)
        val ready =
            CompletableFuture.supplyAsync(
                {
                    try {
                        MapLight.presentChunks(worldPath.resolve("region"))
                            .map { (x, z) -> instance.loadChunk(x, z) }
                            .forEach { it.join() }
                        edit(instance)
                        instance.chunks.forEach { chunk ->
                            if (chunk is GroundsChunk && chunk.needsLight()) chunk.computeLight()
                        }
                    } catch (t: Throwable) {
                        instance.chunks.toList().forEach(instance::unloadChunk)
                        throw t
                    }
                    register(instance)
                },
                { task -> Thread.ofVirtual().name("grounds-world-prepare").start(task) },
            )
        return PreparedWorld(instance, ready)
    }

    /**
     * For a game that builds its round inside the StartMatch handler and needs the world there: a
     * lit world is [prepare]d and returned once ready, which takes a fraction of a second; any
     * other map is [open]ed as before, so the handler never lights a whole map against the
     * matchmaker's deadline.
     */
    fun load(
        worldPath: Path,
        identity: WorldIdentity,
        dimensionType: RegistryKey<DimensionType> = DimensionType.OVERWORLD,
    ): InstanceContainer =
        if (MapLight.isLit(worldPath)) prepare(worldPath, identity, dimensionType).ready.join()
        else open(worldPath, identity, dimensionType)

    /**
     * Loads the chunks within [radius] of [center] in [instance] and computes their light, off the
     * tick thread — for a world that stays up, such as a lobby, so its first visitor does not wait
     * for it. On a registered instance the light is computed under the instance's monitor, which
     * the tick also takes: run it before anyone is there.
     */
    fun preload(instance: InstanceContainer, center: Point, radius: Int): CompletableFuture<Void> =
        CompletableFuture.runAsync(
            {
                val cx = center.chunkX()
                val cz = center.chunkZ()
                val chunks =
                    (cx - radius..cx + radius)
                        .flatMap { x ->
                            (cz - radius..cz + radius).map { z -> instance.loadChunk(x, z) }
                        }
                        .map { it.join() }
                chunks.forEach { if (it is GroundsChunk && it.needsLight()) it.computeLight() }
            },
            { task -> Thread.ofVirtual().name("grounds-world-preload").start(task) },
        )

    /** A world with nothing on disk, for arenas a game builds or generates itself. */
    fun create(
        identity: WorldIdentity,
        dimensionType: RegistryKey<DimensionType> = DimensionType.OVERWORLD,
    ): InstanceContainer = register(container(identity, null, dimensionType))

    private fun loader(worldPath: Path): ChunkLoader =
        // Stripped: the leftover NBT of a converted map is a third of its memory, and nothing reads
        // it.
        MapLoader(AnvilLoader(worldPath), lit = MapLight.isLit(worldPath), strip = true)

    private fun container(
        identity: WorldIdentity,
        loader: ChunkLoader?,
        dimensionType: RegistryKey<DimensionType>,
    ): InstanceContainer {
        val instance = InstanceContainer(UUID.randomUUID(), dimensionType, loader, identity.key)
        instance.hashedSeed = identity.hashedSeed
        instance.setChunkSupplier(::GroundsChunk)
        return instance
    }

    private fun register(instance: InstanceContainer): InstanceContainer {
        MinecraftServer.getInstanceManager().registerInstance(instance)
        return instance
    }
}

/**
 * A world [GroundsWorld.prepare] is loading. [instance] can be wired up at once; players go in once
 * [ready] completes, which is also when it is registered.
 */
class PreparedWorld(
    val instance: InstanceContainer,
    val ready: CompletableFuture<InstanceContainer>,
) {
    /**
     * Waits up to [timeoutMillis] for the world, from a thread that may block — a player's
     * configuration, for instance, which keeps them on the loading screen meanwhile. Returns
     * whether it is ready; false on timeout or if preparing it failed.
     */
    fun await(timeoutMillis: Long): Boolean =
        try {
            ready.get(timeoutMillis, TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            false
        }
}

/** A [LightingChunk] whose light can be computed ahead of its first packet. */
internal class GroundsChunk(instance: Instance, chunkX: Int, chunkZ: Int) :
    LightingChunk(instance, chunkX, chunkZ) {

    fun needsLight(): Boolean =
        sections.any { it.skyLight().requiresUpdate() || it.blockLight().requiresUpdate() }

    /** Computes, and caches for the chunk's packet, the light a client is sent. */
    fun computeLight() {
        createLightData(true)
    }
}

/**
 * Loads chunks through [delegate] and decides how each is lit.
 * - A chunk of a lit world ([lit]) that stores its light (`isLightOn`) is used as it is.
 * - Every other loaded chunk is treated like a generated one: `onGenerate` invalidates the light of
 *   a [LightingChunk] and its loaded neighbours, so it is computed before the chunk is first sent.
 * - A chunk a lit world does not have is all air under open sky: it is supplied fully sky-lit
 *   rather than generated, which would invalidate the light of the map chunks beside it.
 *
 * [strip] drops the NBT a loaded chunk carries beyond blocks, biomes and block entities.
 */
internal class MapLoader(
    private val delegate: ChunkLoader,
    private val lit: Boolean,
    private val strip: Boolean = false,
) : ChunkLoader {
    override fun loadChunk(instance: Instance, chunkX: Int, chunkZ: Int): Chunk? {
        val chunk = delegate.loadChunk(instance, chunkX, chunkZ)
        if (chunk == null) {
            return if (lit && instance.generator() == null) openSky(instance, chunkX, chunkZ)
            else null
        }
        // Nullable on purpose: a chunk without the tag returns null, which Java hands back as a
        // platform type that would unbox, and throw, in a plain comparison.
        val lightOn: Byte? = chunk.getTag(MapLight.LIGHT_ON)
        val stored = lit && lightOn == ON
        if (strip) chunk.tagHandler().updateContent(CompoundBinaryTag.empty())
        if (!stored) chunk.onGenerate()
        return chunk
    }

    private fun openSky(instance: Instance, chunkX: Int, chunkZ: Int): Chunk =
        instance.chunkSupplier.createChunk(instance, chunkX, chunkZ).also { chunk ->
            chunk.sections.forEach { it.setSkyLight(FULL_SKY) }
        }

    // Spelled out: Kotlin's `by` delegation does not forward Java default methods, and AnvilLoader
    // overrides most of them. Without these, its level data, region cache and parallel loading
    // would be dropped silently.
    override fun loadInstance(instance: Instance) = delegate.loadInstance(instance)

    override fun saveInstance(instance: Instance) = delegate.saveInstance(instance)

    override fun saveChunk(chunk: Chunk) = delegate.saveChunk(chunk)

    override fun saveChunks(chunks: Collection<Chunk>) = delegate.saveChunks(chunks)

    override fun unloadChunk(chunk: Chunk) = delegate.unloadChunk(chunk)

    override fun supportsParallelLoading() = delegate.supportsParallelLoading()

    override fun supportsParallelSaving() = delegate.supportsParallelSaving()

    private companion object {
        const val ON: Byte = 1

        /** Sky light 15 in every block of a section. */
        val FULL_SKY = ByteArray(2048) { -1 }
    }
}

package gg.grounds.runtime.core.world

import java.io.DataInputStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.anvil.AnvilLoader
import net.minestom.server.registry.RegistryKey
import net.minestom.server.tag.Tag
import net.minestom.server.world.DimensionType
import org.slf4j.LoggerFactory

/**
 * Lights a map once and writes it back lit, so no round computes its light again.
 *
 * Converted maps carry no light, and Minestom computes light when a chunk is first sent: inside the
 * tick, under the instance's monitor, for every chunk around a joining player. That is the "Loading
 * terrain…" stall. A **lit world** is the same map with that work done ahead of time:
 * - every saved chunk has all its sections lit the way Minestom lights them for a client, and
 *   carries `isLightOn = 1`;
 * - all-air chunks are left out: a missing chunk of a lit world is all air under open sky, which
 *   [GroundsWorld] supplies fully sky-lit without loading anything;
 * - the NBT the format upgrade left behind (proto-chunk status, carving masks, post-processing
 *   lists) is dropped, which takes a third off the memory a loaded map needs;
 * - [MARKER] in the world directory says all of the above holds.
 *
 * Any step that unpacks a map can produce this — the pod today, the map service at publish time
 * later — and [GroundsWorld] trusts stored light only in a world that carries the marker.
 */
object MapLight {
    /**
     * The version of this contract — of how a lit world is lit and laid out. Raised when either
     * changes (a Minestom lighting fix, say), which makes every lit world of an older format count
     * as unlit, so it is lit again.
     */
    const val FORMAT = 1

    /** Present in a lit world's directory. */
    const val MARKER = ".grounds-lit-v$FORMAT"

    internal val LIGHT_ON: Tag<Byte> = Tag.Byte("isLightOn")

    private val log = LoggerFactory.getLogger(MapLight::class.java)

    /** Whether [worldPath] is a lit world. */
    fun isLit(worldPath: Path): Boolean = Files.exists(worldPath.resolve(MARKER))

    /**
     * Lights the map in [source] (the directory that contains `region/`) and writes it as a lit
     * world to [target]. Everything but `region/` is copied unchanged. Does nothing if [target] is
     * already a lit world.
     *
     * Blocking and CPU-heavy — up to tens of seconds for the largest maps — so call it off the tick
     * thread. The map is lit on an instance nobody ticks or joins, so no running round waits on its
     * monitor. Writes into a staging directory beside [target] and moves it into place, so a crash
     * never leaves a half-lit world; if another bake of the same map got there first, that one is
     * kept.
     *
     * @return the number of chunks written; all-air chunks are not
     */
    fun bake(
        source: Path,
        target: Path,
        dimensionType: RegistryKey<DimensionType> = DimensionType.OVERWORLD,
    ): Int {
        if (isLit(target)) return 0
        val started = System.nanoTime()
        val parent = target.toAbsolutePath().parent
        Files.createDirectories(parent)
        val staging = Files.createTempDirectory(parent, ".${target.fileName}-")
        try {
            copyAllBut(source, staging, "region")
            val saved = lightAndSave(source, staging, dimensionType)
            Files.createFile(staging.resolve(MARKER))
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: FileSystemException) {
                // Another bake of the same map won the race; it is the same light.
                if (!isLit(target)) throw e
            }
            log.info(
                "Lit {} in {} ms ({} chunks kept)",
                source,
                (System.nanoTime() - started) / 1_000_000,
                saved,
            )
            return saved
        } finally {
            if (Files.exists(staging)) staging.toFile().deleteRecursively()
        }
    }

    private fun lightAndSave(
        source: Path,
        out: Path,
        dimensionType: RegistryKey<DimensionType>,
    ): Int {
        // Never registered, so never ticked: its monitor, which lighting holds, is ours alone.
        val instance =
            InstanceContainer(
                UUID.randomUUID(),
                dimensionType,
                MapLoader(AnvilLoader(source), lit = false, strip = true),
            )
        instance.setChunkSupplier(::GroundsChunk)
        val chunks =
            presentChunks(source.resolve("region"))
                .map { (x, z) -> instance.loadChunk(x, z) }
                .map(CompletableFuture<Chunk>::join)
        val saver = AnvilLoader(out)
        var saved = 0
        try {
            // The light a client is sent, computed for every chunk while all of its neighbours are
            // loaded. Not LightingChunk.relight: that skips the sky sections around a column's
            // highest block, which would then be stored unlit.
            chunks.forEach { (it as GroundsChunk).computeLight() }
            for (chunk in chunks) {
                check(
                    chunk.sections.none {
                        it.skyLight().requiresUpdate() || it.blockLight().requiresUpdate()
                    }
                ) {
                    "chunk ${chunk.chunkX},${chunk.chunkZ} of $source still needs light"
                }
                if (isAllAir(chunk)) continue
                chunk.setTag(LIGHT_ON, 1)
                saver.saveChunk(chunk)
                saver.unloadChunk(chunk)
                saved++
            }
        } finally {
            // A loaded chunk is a tick partition even in an unregistered instance; unloading is
            // what lets the dispatcher, and then the collector, drop it.
            chunks.forEach(instance::unloadChunk)
        }
        return saved
    }

    internal fun isAllAir(chunk: Chunk): Boolean =
        chunk.sections.all { it.blockPalette().singleValue() == 0 }

    /** Every chunk a world's region files hold, read from the files' location tables. */
    fun presentChunks(regionDir: Path): List<Pair<Int, Int>> {
        if (!Files.isDirectory(regionDir)) return emptyList()
        val name = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")
        val chunks = mutableListOf<Pair<Int, Int>>()
        val files = Files.list(regionDir).use { it.sorted().toList() }
        for (file in files) {
            val match = name.matchEntire(file.fileName.toString()) ?: continue
            if (Files.size(file) < LOCATION_TABLE_BYTES) continue
            val regionX = match.groupValues[1].toInt()
            val regionZ = match.groupValues[2].toInt()
            DataInputStream(Files.newInputStream(file).buffered()).use { table ->
                for (i in 0 until CHUNKS_PER_REGION) {
                    if (table.readInt() != 0) {
                        chunks += (regionX * 32 + (i and 31)) to (regionZ * 32 + (i shr 5))
                    }
                }
            }
        }
        return chunks
    }

    private fun copyAllBut(source: Path, target: Path, skip: String) {
        val paths = Files.walk(source).use { it.toList() }
        for (path in paths) {
            val relative = source.relativize(path)
            if (relative.toString().isEmpty() || relative.getName(0).toString() == skip) continue
            val destination = target.resolve(relative.toString())
            if (Files.isDirectory(path)) Files.createDirectories(destination)
            else Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private const val CHUNKS_PER_REGION = 1024
    private const val LOCATION_TABLE_BYTES = 4096L
}

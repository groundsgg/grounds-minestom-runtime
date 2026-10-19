package gg.grounds.runtime.core.world

import gg.grounds.runtime.core.world.TestWorlds.GROUND
import gg.grounds.runtime.core.world.TestWorlds.GROUND_Y
import gg.grounds.runtime.core.world.TestWorlds.lightSentFor
import gg.grounds.runtime.core.world.TestWorlds.sameLight
import gg.grounds.runtime.core.world.TestWorlds.skyAt
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Vec
import net.minestom.server.instance.block.Block
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PreparedWorldTest {

    @BeforeEach
    fun init() {
        MinecraftServer.init()
    }

    @AfterEach
    fun stop() {
        MinecraftServer.stopCleanly()
    }

    @Test
    fun `a prepared world has every chunk of the map loaded and lit before anyone joins`() {
        val lit = litWorld()

        val world = GroundsWorld.prepare(lit, WorldIdentity.of("test/prepared"))
        val instance = world.ready.get(30, TimeUnit.SECONDS)

        assertSame(world.instance, instance)
        assertTrue(MinecraftServer.getInstanceManager().instances.contains(instance))
        assertEquals(GROUND.toSet(), instance.chunks.map { it.chunkX to it.chunkZ }.toSet())
        assertTrue(instance.chunks.none { (it as GroundsChunk).needsLight() })
    }

    @Test
    fun `an unlit map is prepared lit too`() {
        val raw = TestWorlds.unlit()

        val instance =
            GroundsWorld.prepare(raw, WorldIdentity.of("test/prepared"))
                .ready
                .get(30, TimeUnit.SECONDS)

        assertTrue(instance.chunks.none { (it as GroundsChunk).needsLight() })
        assertEquals(15, skyAt(instance.getChunk(1, 1)!!, 20, GROUND_Y + 2, 20))
    }

    /** A block a round changes is lit before the world is handed out, not on the first send. */
    @Test
    fun `blocks changed while preparing are lit before the world is ready`() {
        val lit = litWorld()

        val instance =
            GroundsWorld.prepare(lit, WorldIdentity.of("test/prepared")) { world ->
                    world.setBlock(HOLE_X, GROUND_Y - 1, HOLE_Z, Block.AIR)
                }
                .ready
                .get(30, TimeUnit.SECONDS)

        val chunk = instance.getChunkAt(HOLE_X.toDouble(), HOLE_Z.toDouble())!!
        assertTrue(instance.chunks.none { (it as GroundsChunk).needsLight() })
        assertEquals(15, skyAt(chunk, HOLE_X, GROUND_Y - 1, HOLE_Z))
    }

    @Test
    fun `a failed preparation completes exceptionally and keeps nothing loaded`() {
        val lit = litWorld()

        val world =
            GroundsWorld.prepare(lit, WorldIdentity.of("test/prepared")) { error("broken edit") }

        assertThrows(Exception::class.java) { world.ready.get(30, TimeUnit.SECONDS) }
        assertFalse(world.await(10))
        assertTrue(world.instance.chunks.isEmpty())
        assertFalse(MinecraftServer.getInstanceManager().instances.contains(world.instance))
    }

    /**
     * Many rounds start at once on one server, on the same map and on different ones. Each gets its
     * own world: its blocks are its own, and its light is the map's.
     */
    @Test
    fun `rounds prepared in parallel from the same and from different maps are independent`() {
        val maps = listOf(litWorld(), litWorld())
        val pool = Executors.newFixedThreadPool(8)
        val rounds =
            (0 until 12).map { round ->
                CompletableFuture.supplyAsync(
                        {
                            GroundsWorld.prepare(
                                    maps[round % 2],
                                    WorldIdentity.of("test/map${round % 2}"),
                                ) { world ->
                                    // Each round digs its own hole.
                                    world.setBlock(round, GROUND_Y - 1, 20, Block.AIR)
                                }
                                .ready
                        },
                        pool,
                    )
                    .thenCompose { it }
            }
        val instances = rounds.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(12, instances.toSet().size)
        instances.forEachIndexed { round, instance ->
            for (other in 0 until 12) {
                val expected = if (other == round) Block.AIR else Block.STONE
                assertTrue(
                    instance.getBlock(other, GROUND_Y - 1, 20).compare(expected),
                    "round $round, hole $other",
                )
            }
            assertTrue(instance.chunks.none { (it as GroundsChunk).needsLight() })
        }
        // Away from the holes, every round of a map sends the map's light.
        val reference = instances[0].getChunk(-2, -2)!!
        instances
            .filterIndexed { round, _ -> round % 2 == 0 }
            .forEach { instance ->
                assertTrue(
                    sameLight(lightSentFor(reference), lightSentFor(instance.getChunk(-2, -2)!!))
                )
            }
        instances.forEach(MinecraftServer.getInstanceManager()::unregisterInstance)
        instances.forEach { assertTrue(it.chunks.isEmpty()) }
    }

    @Test
    fun `loading a lit world returns it prepared, an unlit map just opened`() {
        val lit = litWorld()
        val raw = TestWorlds.unlit()

        val prepared = GroundsWorld.load(lit, WorldIdentity.of("test/lit"))
        val opened = GroundsWorld.load(raw, WorldIdentity.of("test/raw"))

        val instances = MinecraftServer.getInstanceManager().instances
        assertTrue(instances.contains(prepared) && instances.contains(opened))
        assertEquals(GROUND.toSet(), prepared.chunks.map { it.chunkX to it.chunkZ }.toSet())
        assertTrue(opened.chunks.isEmpty())
    }

    @Test
    fun `preloading lights the area around a point`() {
        val raw = TestWorlds.unlit()
        val instance = GroundsWorld.open(raw, WorldIdentity.of("test/lobby"))

        GroundsWorld.preload(instance, Vec(0.0, 70.0, 0.0), 1).get(30, TimeUnit.SECONDS)

        assertEquals(9, instance.chunks.size)
        assertTrue(instance.chunks.none { (it as GroundsChunk).needsLight() })
    }

    private fun litWorld(): Path {
        val raw = TestWorlds.unlit()
        val lit = raw.resolveSibling("${raw.fileName}-lit")
        MapLight.bake(raw, lit)
        return lit
    }

    private companion object {
        const val HOLE_X = 20
        const val HOLE_Z = 20
    }
}

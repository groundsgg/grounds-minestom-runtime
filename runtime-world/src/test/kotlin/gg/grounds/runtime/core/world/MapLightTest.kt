package gg.grounds.runtime.core.world

import gg.grounds.runtime.core.world.TestWorlds.AIR
import gg.grounds.runtime.core.world.TestWorlds.GLOWSTONE
import gg.grounds.runtime.core.world.TestWorlds.GROUND
import gg.grounds.runtime.core.world.TestWorlds.GROUND_Y
import gg.grounds.runtime.core.world.TestWorlds.ROOF_Y
import gg.grounds.runtime.core.world.TestWorlds.blockLightAt
import gg.grounds.runtime.core.world.TestWorlds.lightSentFor
import gg.grounds.runtime.core.world.TestWorlds.sameLight
import gg.grounds.runtime.core.world.TestWorlds.skyAt
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import net.minestom.server.MinecraftServer
import net.minestom.server.instance.InstanceContainer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MapLightTest {

    @BeforeEach
    fun init() {
        MinecraftServer.init()
    }

    @AfterEach
    fun stop() {
        MinecraftServer.stopCleanly()
    }

    @Test
    fun `a lit world keeps every chunk with blocks and drops the all-air ones`() {
        val raw = TestWorlds.unlit()
        val lit = raw.resolveSibling("${raw.fileName}-lit")

        val saved = MapLight.bake(raw, lit)

        assertTrue(MapLight.isLit(lit))
        assertFalse(MapLight.isLit(raw))
        assertEquals(GROUND.size, saved)
        assertEquals(GROUND.toSet(), MapLight.presentChunks(lit.resolve("region")).toSet())
        assertEquals((GROUND + AIR).toSet(), MapLight.presentChunks(raw.resolve("region")).toSet())
    }

    /**
     * The light a lit world sends is exactly the light the map got when it was relit on load — sky
     * light under the roof, block light around the glowstone and all.
     */
    @Test
    fun `a lit world sends the same light as relighting the map`() {
        val raw = TestWorlds.unlit()
        val lit = raw.resolveSibling("${raw.fileName}-lit")
        MapLight.bake(raw, lit)

        val relit = loadAll(GroundsWorld.open(raw, WorldIdentity.of("test/relit")), GROUND + AIR)
        val stored = loadAll(GroundsWorld.open(lit, WorldIdentity.of("test/lit")), GROUND)

        for ((x, z) in GROUND) {
            assertTrue(
                sameLight(
                    lightSentFor(relit.getChunk(x, z)!!),
                    lightSentFor(stored.getChunk(x, z)!!),
                ),
                "light of chunk $x,$z",
            )
        }
        val home = stored.getChunk(0, 0)!!
        assertEquals(15, skyAt(home, 12, GROUND_Y + 2, 12))
        val underRoof = skyAt(home, 2, GROUND_Y + 1, 2)
        assertTrue(underRoof in 1..14, "sky light under the roof is $underRoof")
        assertEquals(14, blockLightAt(home, GLOWSTONE.blockX(), GROUND_Y + 1, GLOWSTONE.blockZ()))
    }

    @Test
    fun `chunks of a lit world are not relit when they load`() {
        val raw = TestWorlds.unlit()
        val lit = raw.resolveSibling("${raw.fileName}-lit")
        MapLight.bake(raw, lit)

        val stored = loadAll(GroundsWorld.open(lit, WorldIdentity.of("test/lit")), GROUND)

        for ((x, z) in GROUND) assertFalse((stored.getChunk(x, z) as GroundsChunk).needsLight())
    }

    @Test
    fun `chunks of an unlit map are relit when they load`() {
        val raw = TestWorlds.unlit()

        val relit = loadAll(GroundsWorld.open(raw, WorldIdentity.of("test/relit")), GROUND)

        assertTrue((relit.getChunk(0, 0) as GroundsChunk).needsLight())
    }

    /** A chunk a lit world does not store is open sky: full sky light, and nothing relit. */
    @Test
    fun `a chunk a lit world does not have is open sky`() {
        val raw = TestWorlds.unlit()
        val lit = raw.resolveSibling("${raw.fileName}-lit")
        MapLight.bake(raw, lit)
        val stored = loadAll(GroundsWorld.open(lit, WorldIdentity.of("test/lit")), GROUND)

        val air = stored.loadChunk(4, 0).join()

        assertTrue(air is GroundsChunk)
        assertFalse((air as GroundsChunk).needsLight())
        assertEquals(15, skyAt(air, 0, GROUND_Y - 30, 0))
        assertEquals(15, skyAt(air, 8, ROOF_Y, 8))
        // Its neighbours keep the light they were loaded with.
        assertFalse((stored.getChunk(2, 0) as GroundsChunk).needsLight())
    }

    /**
     * Rounds on island maps always load such chunks; tearing the round down must not trip on them.
     */
    @Test
    fun `a world with open-sky chunks unregisters cleanly`() {
        val raw = TestWorlds.unlit()
        val lit = raw.resolveSibling("${raw.fileName}-lit")
        MapLight.bake(raw, lit)
        val stored = loadAll(GroundsWorld.open(lit, WorldIdentity.of("test/lit")), GROUND)
        stored.loadChunk(4, 0).join()
        stored.loadChunk(40, 40).join()

        MinecraftServer.getInstanceManager().unregisterInstance(stored)

        assertTrue(stored.chunks.isEmpty())
    }

    @Test
    fun `lighting a lit world again does nothing`() {
        val raw = TestWorlds.unlit()
        val lit = raw.resolveSibling("${raw.fileName}-lit")
        MapLight.bake(raw, lit)
        val before = Files.getLastModifiedTime(lit.resolve(MapLight.MARKER))

        assertEquals(0, MapLight.bake(raw, lit))
        assertEquals(before, Files.getLastModifiedTime(lit.resolve(MapLight.MARKER)))
    }

    @Test
    fun `two bakes of one map at once leave one lit world`() {
        val raw = TestWorlds.unlit()
        val lit = raw.resolveSibling("${raw.fileName}-lit")

        val bakes = (1..3).map { CompletableFuture.runAsync { MapLight.bake(raw, lit) } }
        bakes.forEach { it.join() }

        assertTrue(MapLight.isLit(lit))
        assertEquals(GROUND.toSet(), MapLight.presentChunks(lit.resolve("region")).toSet())
        val leftovers =
            Files.list(lit.parent).use { s ->
                s.filter { it.fileName.toString().startsWith(".${lit.fileName}-") }.count()
            }
        assertEquals(0, leftovers)
    }

    private fun loadAll(
        instance: InstanceContainer,
        chunks: List<Pair<Int, Int>>,
    ): InstanceContainer {
        chunks.map { (x, z) -> instance.loadChunk(x, z) }.forEach { it.join() }
        return instance
    }
}

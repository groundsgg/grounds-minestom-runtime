package gg.grounds.runtime.match

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchRegistryTest {
    @Test
    fun `accept is idempotent on matchId`() {
        var startCount = 0
        val registry = MatchRegistry(FakeMatchCounters())
        val match = testMatch("match-1")

        val first = registry.accept(match) { startCount++ }
        val second = registry.accept(match) { startCount++ }

        assertTrue(first)
        assertTrue(second)
        assertEquals(1, startCount)
    }

    @Test
    fun `a handler that throws is refused and not registered`() {
        val registry = MatchRegistry(FakeMatchCounters())
        val match = testMatch("match-2")

        val accepted = registry.accept(match) { throw IllegalStateException("no room") }

        assertFalse(accepted)
        assertEquals(0, registry.live())
    }

    @Test
    fun `finish decrements the counter exactly once even when called twice`() {
        val counters = FakeMatchCounters()
        val registry = MatchRegistry(counters)
        val match = testMatch("match-3")
        registry.accept(match) {}

        registry.finish(match.matchId)
        registry.finish(match.matchId)

        assertEquals(1, counters.decrements)
    }

    @Test
    fun `finishing a match that was never accepted does not decrement`() {
        val counters = FakeMatchCounters()
        val registry = MatchRegistry(counters)

        registry.finish("never-accepted")

        assertEquals(0, counters.decrements)
    }

    @Test
    fun `a refused match gives its slot back`() {
        // Allocation incremented the counter before the match ever got here. If the
        // server then declines it, nothing will call finish, so without this the slot
        // is advertised as taken for the rest of the server's life.
        val counters = FakeMatchCounters()
        val registry = MatchRegistry(counters)

        val accepted =
            registry.accept(testMatch("m1")) { throw IllegalStateException("no map for this mode") }

        assertFalse(accepted)
        assertEquals(1, counters.decrements, "a refusal must release the slot it was given")
        assertEquals(0, registry.live())
    }

    @Test
    fun `a handler that throws an Error refuses rather than escaping`() {
        // The one that actually happened: a gamemode missing a class from its jar throws
        // NoClassDefFoundError, which is an Error and not an Exception. It went straight
        // through the old `catch (e: Exception)`, killed the gRPC worker thread, and left
        // the slot leaked — every server on stage retired itself this way.
        val counters = FakeMatchCounters()
        val registry = MatchRegistry(counters)

        val accepted =
            registry.accept(testMatch("m1")) {
                throw NoClassDefFoundError("net/kyori/adventure/text/minimessage/MiniMessage")
            }

        assertFalse(accepted)
        assertEquals(1, counters.decrements)
    }

    @Test
    fun `a refusal after a successful accept does not release the live match's slot`() {
        val counters = FakeMatchCounters()
        val registry = MatchRegistry(counters)
        registry.accept(testMatch("m1")) {}

        registry.accept(testMatch("m2")) { throw IllegalStateException("boom") }

        assertEquals(1, counters.decrements)
        assertEquals(1, registry.live(), "the accepted match must still be hosted")
    }

    private fun testMatch(matchId: String): PushedMatch =
        PushedMatch(matchId, modeId = "duel", teams = listOf(listOf(UUID.randomUUID())))

    private class FakeMatchCounters : MatchCounters {
        var decrements = 0
        var reconciledTo: Int? = null

        override fun decrement() {
            decrements++
        }

        override fun reconcile(live: Int) {
            reconciledTo = live
        }
    }
}

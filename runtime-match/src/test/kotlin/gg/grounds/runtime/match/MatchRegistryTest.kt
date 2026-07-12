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

    private fun testMatch(matchId: String): PushedMatch =
        PushedMatch(matchId, modeId = "duel", teams = listOf(listOf(UUID.randomUUID())))

    private class FakeMatchCounters : MatchCounters {
        var decrements = 0

        override fun decrement() {
            decrements++
        }
    }
}

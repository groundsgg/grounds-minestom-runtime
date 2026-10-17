package gg.grounds.runtime.match

import java.time.Duration
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdleReadinessTest {
    private var now = 0L
    private var counter: Int? = 0
    private val state = FakeState()
    private lateinit var registry: MatchRegistry
    private val readiness =
        IdleReadiness({ registry.idle() }, { counter }, state, GRACE, clock = { now })

    init {
        registry = MatchRegistry(NoCounters, null) { readiness.busy() }
    }

    @Test
    fun `an idle server is readied once the grace period is over`() {
        readiness.tick()
        advance(GRACE.minusSeconds(1))
        readiness.tick()
        assertEquals(0, state.readies, "not before the grace period is over")

        advance(Duration.ofSeconds(1))
        readiness.tick()
        readiness.tick()

        assertEquals(1, state.readies)
    }

    @Test
    fun `a live match keeps the server from being readied`() {
        registry.accept(testMatch("m1")) {}

        repeat(10) {
            readiness.tick()
            advance(GRACE)
        }

        assertEquals(0, state.readies)
    }

    @Test
    fun `a match being accepted keeps the server from being readied`() {
        readiness.tick()
        advance(GRACE)

        registry.accept(testMatch("m1")) { readiness.tick() }

        assertEquals(0, state.readies)
    }

    @Test
    fun `a match during the grace period restarts it`() {
        readiness.tick()
        advance(GRACE.minusSeconds(5))
        registry.accept(testMatch("m1")) {}
        readiness.tick()
        registry.finish("m1")

        readiness.tick()
        advance(GRACE.minusSeconds(1))
        readiness.tick()
        assertEquals(0, state.readies, "the grace period starts again after the match")

        advance(Duration.ofSeconds(1))
        readiness.tick()
        assertEquals(1, state.readies)
    }

    @Test
    fun `a slot on the counter keeps the server from being readied`() {
        // Allocation raised the counter; its StartMatch has not arrived yet.
        counter = 1
        readiness.tick()
        advance(GRACE)
        readiness.tick()
        assertEquals(0, state.readies)

        counter = null
        advance(GRACE)
        readiness.tick()
        assertEquals(0, state.readies, "a sidecar that does not answer is not a 0")
    }

    @Test
    fun `a match pushed after the server was readied is accepted and allocates it`() {
        readyNow()
        var started = false

        val accepted = registry.accept(testMatch("m1")) { started = true }

        assertTrue(accepted)
        assertTrue(started)
        assertEquals(1, state.allocates)

        registry.finish("m1")
        readiness.tick()
        advance(GRACE)
        readiness.tick()
        assertEquals(2, state.readies, "readied again once the match is over")
    }

    @Test
    fun `an allocation that lands while Ready moves the server back to Allocated`() {
        readyNow()

        counter = 1
        readiness.tick()

        assertEquals(1, state.allocates)
    }

    @Test
    fun `a failed allocate is retried`() {
        readyNow()
        state.fail = true
        registry.accept(testMatch("m1")) {}
        assertEquals(0, state.allocates)

        state.fail = false
        readiness.tick()

        assertEquals(1, state.allocates)
    }

    @Test
    fun `re-readying is on by default and can be turned off`() {
        assertEquals(IdleReadiness.DEFAULT_GRACE, IdleReadiness.graceFromEnv { null })
        assertEquals(
            Duration.ofSeconds(30),
            IdleReadiness.graceFromEnv { if (it == IdleReadiness.GRACE_ENV) "30" else null },
        )
        assertNull(
            IdleReadiness.graceFromEnv { if (it == IdleReadiness.ENABLED_ENV) "false" else null }
        )
        assertNull(
            IdleReadiness.graceFromEnv { if (it == IdleReadiness.ENABLED_ENV) "0" else null }
        )
    }

    private fun readyNow() {
        readiness.tick()
        advance(GRACE)
        readiness.tick()
        assertEquals(1, state.readies)
    }

    private fun advance(by: Duration) {
        now += by.toNanos()
    }

    private fun testMatch(matchId: String): PushedMatch =
        PushedMatch(matchId, modeId = "duel", teams = listOf(listOf(UUID.randomUUID())))

    private class FakeState : GameServerState {
        var readies = 0
        var allocates = 0
        var fail = false

        override fun ready(): Boolean = !fail && ++readies > 0

        override fun allocate(): Boolean = !fail && ++allocates > 0
    }

    private object NoCounters : MatchCounters {
        override fun decrement() {}

        override fun reconcile(live: Int) {}
    }

    private companion object {
        val GRACE: Duration = Duration.ofSeconds(20)
    }
}

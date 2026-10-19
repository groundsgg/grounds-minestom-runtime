package gg.grounds.runtime.match

import gg.grounds.grpc.match.MatchTeam
import gg.grounds.grpc.match.StartMatchRequest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rounds that get ready after StartMatch, while their players are still in the queue. */
class PreparedMatchTest {
    private val signals = mutableListOf<Triple<String, Boolean, String>>()
    private var routed: Boolean? = true
    private val signal = MatchReadySignal { matchId, ok, reason ->
        signals += Triple(matchId, ok, reason)
        routed
    }
    private val decrements = mutableListOf<Unit>()
    private val counters =
        object : MatchCounters {
            override fun decrement() {
                decrements += Unit
            }

            override fun reconcile(live: Int) {}
        }

    private class Handler : PreparedMatchHandler {
        val ready = CompletableFuture<Unit>()
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<Pair<String, String>>()
        var preparedCalls = 0

        override fun start(match: PushedMatch) {
            started += match.matchId
        }

        override fun prepared(match: PushedMatch): CompletionStage<*> {
            preparedCalls++
            return ready
        }

        override fun cancel(matchId: String, reason: String) {
            cancelled += matchId to reason
        }
    }

    private fun service(handler: MatchHandler, registry: MatchRegistry = MatchRegistry(counters)) =
        MatchHostService(registry, handler, null, { emptyList() }, signal, Executor { it.run() })

    private fun request(waitForReady: Boolean = true): StartMatchRequest =
        StartMatchRequest.newBuilder()
            .setMatchId("m1")
            .setModeId("survivalgames-solo")
            .setMapAddress("survivalgames/aura")
            .setWaitForReady(waitForReady)
            .addTeams(MatchTeam.newBuilder().addPlayerIds(UUID.randomUUID().toString()))
            .build()

    @Test
    fun `answers at once, and reports the round ready once it is prepared`() {
        val handler = Handler()
        val service = service(handler)

        val reply = service.startMatch(request())

        assertTrue(reply.accepted)
        assertTrue(reply.signalsReady)
        assertEquals(listOf("m1"), handler.started)
        assertTrue(signals.isEmpty(), "not ready yet")

        handler.ready.complete(Unit)

        assertEquals(listOf(Triple("m1", true, "")), signals)
        assertTrue(handler.cancelled.isEmpty())
    }

    @Test
    fun `a matchmaker that does not wait gets the players sent at once`() {
        val handler = Handler()

        val reply = service(handler).startMatch(request(waitForReady = false))

        assertTrue(reply.accepted)
        assertFalse(reply.signalsReady)
        assertEquals(0, handler.preparedCalls)
    }

    @Test
    fun `a handler that does not prepare never signals`() {
        val reply = service(MatchHandler {}).startMatch(request())

        assertTrue(reply.accepted)
        assertFalse(reply.signalsReady)
    }

    @Test
    fun `a round that cannot be prepared is reported failed and cancelled`() {
        val handler = Handler()
        val registry = MatchRegistry(counters)
        service(handler, registry).startMatch(request())

        handler.ready.completeExceptionally(IllegalStateException("map unreadable"))

        assertEquals(listOf(Triple("m1", false, "map unreadable")), signals)
        assertEquals("m1", handler.cancelled.single().first)
        assertEquals(0, registry.live())
        assertEquals(1, decrements.size)
    }

    @Test
    fun `a round the matchmaker gave up on is cancelled when it gets ready`() {
        val handler = Handler()
        routed = false
        val registry = MatchRegistry(counters)
        service(handler, registry).startMatch(request())

        handler.ready.complete(Unit)

        assertEquals("m1", handler.cancelled.single().first)
        assertEquals(0, registry.live())
    }

    @Test
    fun `a retried push neither starts nor watches the round twice`() {
        val handler = Handler()
        val service = service(handler)

        service.startMatch(request())
        val retry = service.startMatch(request())
        handler.ready.complete(Unit)

        assertTrue(retry.signalsReady)
        assertEquals(1, handler.preparedCalls)
        assertEquals(1, signals.size)
    }

    @Test
    fun `a cancel from the matchmaker discards the round and frees its slot once`() {
        val handler = Handler()
        val registry = MatchRegistry(counters)
        val service = service(handler, registry)
        service.startMatch(request())

        service.cancel("m1", "matchmaker: timeout")
        service.cancel("m1", "matchmaker: timeout")

        assertEquals(0, registry.live())
        assertEquals(1, decrements.size)
        assertEquals("matchmaker: timeout", handler.cancelled.first().second)
    }
}

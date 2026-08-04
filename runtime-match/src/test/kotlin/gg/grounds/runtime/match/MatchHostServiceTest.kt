package gg.grounds.runtime.match

import gg.grounds.grpc.match.MatchTeam
import gg.grounds.grpc.match.StartMatchRequest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchHostServiceTest {
    @Test
    fun `refuses an empty roster`() {
        val service = service {}
        val request = StartMatchRequest.newBuilder().setMatchId("m1").setModeId("duel").build()

        val reply = service.startMatch(request)

        assertFalse(reply.accepted)
        assertEquals("empty roster", reply.reason)
    }

    @Test
    fun `refuses a malformed player id`() {
        val service = service {}
        val request =
            StartMatchRequest.newBuilder()
                .setMatchId("m1")
                .setModeId("duel")
                .addTeams(MatchTeam.newBuilder().addPlayerIds("not-a-uuid"))
                .build()

        val reply = service.startMatch(request)

        assertFalse(reply.accepted)
        assertEquals("malformed player id", reply.reason)
    }

    @Test
    fun `refuses a match the registry will not take`() {
        val service = service { error("no arena") }

        val reply = service.startMatch(rosterOfOne())

        assertFalse(reply.accepted)
        assertEquals("match host refused the match", reply.reason)
    }

    @Test
    fun `accepts a valid roster`() {
        var started: PushedMatch? = null
        val service = service { match -> started = match }

        val reply = service.startMatch(rosterOfOne())

        assertTrue(reply.accepted)
        assertEquals("", reply.reason)
        assertEquals("m1", started?.matchId)
    }

    private fun rosterOfOne(): StartMatchRequest =
        StartMatchRequest.newBuilder()
            .setMatchId("m1")
            .setModeId("duel")
            .addTeams(MatchTeam.newBuilder().addPlayerIds(UUID.randomUUID().toString()))
            .build()

    private fun service(handler: MatchHandler): MatchHostService =
        MatchHostService(MatchRegistry(NoopCounters), handler)

    private object NoopCounters : MatchCounters {
        override fun decrement() {}

        override fun reconcile(live: Int) {}
    }
}

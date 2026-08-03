package gg.grounds.runtime.match

import gg.grounds.grpc.match.MatchTeam
import gg.grounds.grpc.match.StartMatchReply
import gg.grounds.grpc.match.StartMatchRequest
import io.grpc.stub.StreamObserver
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

        val reply = call(service, request)

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

        val reply = call(service, request)

        assertFalse(reply.accepted)
    }

    @Test
    fun `accepts a valid roster`() {
        var started: PushedMatch? = null
        val service = service { match -> started = match }
        val request =
            StartMatchRequest.newBuilder()
                .setMatchId("m1")
                .setModeId("duel")
                .addTeams(MatchTeam.newBuilder().addPlayerIds(UUID.randomUUID().toString()))
                .build()

        val reply = call(service, request)

        assertTrue(reply.accepted)
        assertEquals("m1", started?.matchId)
    }

    private fun service(handler: MatchHandler): MatchHostService =
        MatchHostService(MatchRegistry(NoopCounters), handler)

    private fun call(service: MatchHostService, request: StartMatchRequest): StartMatchReply {
        val observer = CapturingObserver()
        service.startMatch(request, observer)
        return observer.replies.single()
    }

    private object NoopCounters : MatchCounters {
        override fun decrement() {}

        override fun reconcile(live: Int) {}
    }

    private class CapturingObserver : StreamObserver<StartMatchReply> {
        val replies = mutableListOf<StartMatchReply>()

        override fun onNext(value: StartMatchReply) {
            replies.add(value)
        }

        override fun onError(t: Throwable) {
            throw t
        }

        override fun onCompleted() = Unit
    }
}

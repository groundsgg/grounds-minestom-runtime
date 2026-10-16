package gg.grounds.runtime.match

import gg.grounds.grpc.match.MatchTeam
import gg.grounds.grpc.match.StartMatchRequest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.opentelemetry.api.OpenTelemetry
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

    @Test
    fun `marks the push before the handler and the accept after it`() {
        val meters = SimpleMeterRegistry()
        val timeline = RuntimeMatchTimeline(meters, OpenTelemetry.noop().getTracer("test"))
        var trackedDuringStart = 0
        val service =
            MatchHostService(
                MatchRegistry(NoopCounters, timeline),
                { trackedDuringStart = timeline.tracked() },
                timeline,
            )

        service.startMatch(rosterOfOne(), PushHeaders.NONE)
        service.startMatch(rosterOfOne(), PushHeaders.NONE)

        assertEquals(1, trackedDuringStart)
        assertEquals(
            1,
            meters.get("grounds.match.time.to.accept").tag("result", "ok").timer().count(),
        )
    }

    @Test
    fun `a refused match is marked failed and forgotten`() {
        val meters = SimpleMeterRegistry()
        val timeline = RuntimeMatchTimeline(meters, OpenTelemetry.noop().getTracer("test"))
        val service =
            MatchHostService(MatchRegistry(NoopCounters, timeline), { error("no arena") }, timeline)

        service.startMatch(rosterOfOne(), PushHeaders.NONE)

        assertEquals(
            1,
            meters.get("grounds.match.time.to.accept").tag("result", "failed").timer().count(),
        )
        assertEquals(0, timeline.tracked())
    }

    @Test
    fun `finishing the match in the registry ends its timeline`() {
        val timeline =
            RuntimeMatchTimeline(SimpleMeterRegistry(), OpenTelemetry.noop().getTracer("test"))
        val registry = MatchRegistry(NoopCounters, timeline)
        val service = MatchHostService(registry, {}, timeline)

        service.startMatch(rosterOfOne(), PushHeaders.NONE)
        registry.finish("m1")

        assertEquals(0, timeline.tracked())
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

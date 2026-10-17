package gg.grounds.runtime.match

import gg.grounds.grpc.match.MatchTeam
import gg.grounds.grpc.match.StartMatchRequest
import gg.grounds.modules.ServiceKey
import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.serviceKey
import gg.grounds.runtime.PlayerPrefetch
import gg.grounds.runtime.playerPrefetches
import gg.grounds.runtime.registerPlayerPrefetch
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

    @Test
    fun `hands the roster to every prefetch before the handler runs`() {
        val players = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val events = mutableListOf<String>()
        val prefetched = mutableListOf<Collection<UUID>>()
        val prefetch = PlayerPrefetch { roster, reason ->
            prefetched += roster
            events += "prefetch $reason"
        }
        val service =
            MatchHostService(MatchRegistry(NoopCounters), { events += "start" }, null) {
                listOf(prefetch, prefetch)
            }

        val reply = service.startMatch(roster("m1", listOf(players.take(2), players.drop(2))))

        assertTrue(reply.accepted)
        assertEquals(listOf("prefetch match m1", "prefetch match m1", "start"), events)
        assertEquals(listOf(players, players), prefetched)
    }

    @Test
    fun `a prefetch that throws does not cost the match`() {
        var started = false
        val service =
            MatchHostService(MatchRegistry(NoopCounters), { started = true }, null) {
                listOf(PlayerPrefetch { _, _ -> error("service down") })
            }

        val reply = service.startMatch(rosterOfOne())

        assertTrue(reply.accepted)
        assertTrue(started)
    }

    @Test
    fun `finds every prefetch a module registered`() {
        val services = MapServiceRegistry()
        val first = PlayerPrefetch { _, _ -> }
        val second = PlayerPrefetch { _, _ -> }
        services.register(serviceKey<String>(), "unrelated")
        services.registerPlayerPrefetch("first", first)
        services.registerPlayerPrefetch("second", second)

        assertEquals(setOf(first, second), services.playerPrefetches().toSet())
    }

    private fun roster(matchId: String, teams: List<List<UUID>>): StartMatchRequest =
        StartMatchRequest.newBuilder()
            .setMatchId(matchId)
            .setModeId("duel")
            .addAllTeams(
                teams.map { team ->
                    MatchTeam.newBuilder().addAllPlayerIds(team.map(UUID::toString)).build()
                }
            )
            .build()

    private fun rosterOfOne(): StartMatchRequest =
        StartMatchRequest.newBuilder()
            .setMatchId("m1")
            .setModeId("duel")
            .addTeams(MatchTeam.newBuilder().addPlayerIds(UUID.randomUUID().toString()))
            .build()

    private fun service(handler: MatchHandler): MatchHostService =
        MatchHostService(MatchRegistry(NoopCounters), handler)

    private class MapServiceRegistry : ServiceRegistry {
        private val services = linkedMapOf<ServiceKey<*>, Any>()

        override fun <T : Any> register(key: ServiceKey<T>, service: T) {
            services[key] = service
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> get(key: ServiceKey<T>): T? = services[key] as T?

        override fun keys(): Set<ServiceKey<*>> = services.keys.toSet()

        override fun getUntyped(key: ServiceKey<*>): Any? = services[key]
    }

    private object NoopCounters : MatchCounters {
        override fun decrement() {}

        override fun reconcile(live: Int) {}
    }
}

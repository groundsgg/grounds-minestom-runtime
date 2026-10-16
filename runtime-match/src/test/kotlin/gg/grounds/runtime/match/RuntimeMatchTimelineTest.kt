package gg.grounds.runtime.match

import gg.grounds.runtime.match.MatchTimeline.MapSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Names, labels and buckets here are a contract with service-match and the Grafana dashboard: a
 * change that breaks one of these tests blanks a panel in another repo.
 */
class RuntimeMatchTimelineTest {
    private val clock = MutableClock()
    private val meters = SimpleMeterRegistry()
    private val lines = mutableListOf<String>()
    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val match = PushedMatch("m1", "duel", listOf(listOf(alice), listOf(bob)))

    private fun timeline(
        tracer: Tracer = OpenTelemetry.noop().getTracer("test"),
        meterRegistry: MeterRegistry = meters,
    ) = RuntimeMatchTimeline(meterRegistry, tracer, clock, Duration.ofHours(2)) { lines += it }

    private fun timer(name: String, vararg tags: String): Timer =
        meters.get(name).tags(*tags).timer()

    private fun seconds(timer: Timer): Double = timer.totalTime(TimeUnit.MILLISECONDS) / 1000.0

    @Test
    fun `records every stage as seconds since the push, labelled by mode`() {
        val headers = PushHeaders(enqueuedAt = mapOf(alice to clock.millis() - 20_000))
        val timeline = timeline()

        assertTrue(timeline.pushed(match, headers))
        clock.advance(150)
        timeline.accepted("m1", ok = true)
        clock.advance(850)
        timeline.mapLoaded("m1", MapSource.DOWNLOAD)
        clock.advance(1_000)
        timeline.playerJoined("m1", alice)
        clock.advance(1_000)
        timeline.playerJoined("m1", bob)
        clock.advance(10_000)
        timeline.started("m1")

        assertEquals(
            0.15,
            seconds(timer(RuntimeMatchTimeline.ACCEPT, "mode", "duel", "result", "ok")),
        )
        assertEquals(
            1.0,
            seconds(timer(RuntimeMatchTimeline.MAP, "mode", "duel", "source", "download")),
        )
        assertEquals(2.0, seconds(timer(RuntimeMatchTimeline.FIRST_JOIN, "mode", "duel")))
        assertEquals(3.0, seconds(timer(RuntimeMatchTimeline.ALL_JOINED, "mode", "duel")))
        assertEquals(13.0, seconds(timer(RuntimeMatchTimeline.START, "mode", "duel")))
        // Alice queued 20s before the push and joined 2s after it; Bob sent no enqueue time.
        val queueToJoin = timer(RuntimeMatchTimeline.QUEUE_TO_JOIN, "mode", "duel")
        assertEquals(1, queueToJoin.count())
        assertEquals(22.0, seconds(queueToJoin))
    }

    @Test
    fun `labels are only the contract's`() {
        val timeline = timeline()
        timeline.pushed(match, PushHeaders(enqueuedAt = mapOf(alice to clock.millis())))
        timeline.accepted("m1", ok = true)
        timeline.mapLoaded("m1", MapSource.CACHE)
        timeline.playerJoined("m1", alice)
        timeline.playerJoined("m1", bob)
        timeline.started("m1")

        // Timers only: SimpleMeterRegistry adds its own `.histogram` gauges for the buckets, which
        // Prometheus exposes as the timer's `le` instead.
        val keys =
            meters.meters.filterIsInstance<Timer>().associate {
                it.id.name to it.id.tags.map { tag -> tag.key }.toSet()
            }
        assertEquals(
            mapOf(
                RuntimeMatchTimeline.ACCEPT to setOf("mode", "result"),
                RuntimeMatchTimeline.MAP to setOf("mode", "source"),
                RuntimeMatchTimeline.FIRST_JOIN to setOf("mode"),
                RuntimeMatchTimeline.ALL_JOINED to setOf("mode"),
                RuntimeMatchTimeline.START to setOf("mode"),
                RuntimeMatchTimeline.QUEUE_TO_JOIN to setOf("mode"),
            ),
            keys,
        )
        assertEquals("cache", meters.get(RuntimeMatchTimeline.MAP).timer().id.getTag("source"))
    }

    @Test
    fun `exports the contract's Prometheus names and buckets`() {
        val prometheus = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val timeline = timeline(meterRegistry = prometheus)
        timeline.pushed(match, PushHeaders(enqueuedAt = mapOf(alice to clock.millis())))
        timeline.accepted("m1", ok = false)
        timeline.pushed(match.copy(matchId = "m2"), PushHeaders(enqueuedAt = mapOf(alice to 1L)))
        timeline.accepted("m2", ok = true)
        timeline.mapLoaded("m2", MapSource.CACHE)
        timeline.playerJoined("m2", alice)
        timeline.playerJoined("m2", bob)
        timeline.started("m2")

        val body = prometheus.scrape()
        listOf(
                "grounds_match_time_to_accept_seconds",
                "grounds_match_time_to_map_seconds",
                "grounds_match_time_to_first_join_seconds",
                "grounds_match_time_to_all_joined_seconds",
                "grounds_match_time_to_start_seconds",
                "grounds_match_queue_to_join_seconds",
            )
            .forEach { name ->
                assertTrue(body.contains("${name}_bucket{"), "missing $name\n$body")
            }
        assertTrue(body.contains("result=\"failed\""), body)
        assertTrue(body.contains("result=\"ok\""), body)

        val les =
            body
                .lines()
                .filter { it.startsWith("grounds_match_time_to_start_seconds_bucket{") }
                .map { Regex("le=\"([^\"]+)\"").find(it)!!.groupValues[1] }
        assertEquals(
            listOf(
                    0.1,
                    0.25,
                    0.5,
                    1.0,
                    2.0,
                    3.0,
                    5.0,
                    7.5,
                    10.0,
                    15.0,
                    20.0,
                    30.0,
                    45.0,
                    60.0,
                    90.0,
                    120.0,
                    180.0,
                    300.0,
                )
                .map { it.toString() } + "+Inf",
            les.map { if (it == "+Inf") it else it.toDouble().toString() },
        )
        prometheus.close()
    }

    @Test
    fun `logs one line per mark and a summary at start`() {
        val timeline = timeline()
        timeline.pushed(match, PushHeaders(formedAt = clock.millis() - 1_500))
        clock.advance(12)
        timeline.accepted("m1", ok = true)
        clock.advance(300)
        timeline.mapLoaded("m1", MapSource.CACHE)
        clock.advance(1_000)
        timeline.playerJoined("m1", alice)
        clock.advance(1_000)
        timeline.playerJoined("m1", bob)
        clock.advance(2_000)
        timeline.started("m1")
        clock.advance(60_000)
        timeline.finished("m1")

        assertEquals(
            listOf(
                "Match timeline (match=m1, mode=duel, stage=push, at=+0ms)",
                "Match timeline (match=m1, mode=duel, stage=accept, at=+12ms)",
                "Match timeline (match=m1, mode=duel, stage=map, at=+312ms)",
                "Match timeline (match=m1, mode=duel, stage=first_join, at=+1312ms)",
                "Match timeline (match=m1, mode=duel, stage=all_joined, at=+2312ms)",
                "Match timeline (match=m1, mode=duel, stage=start, at=+4312ms)",
                "Match timeline summary (match=m1, mode=duel, push=+0ms, accept=+12ms, " +
                    "map=+312ms, first_join=+1312ms, all_joined=+2312ms, start=+4312ms, " +
                    "joined=2/2, formed=-1500ms)",
                "Match timeline (match=m1, mode=duel, stage=finish, at=+64312ms)",
            ),
            lines,
        )
    }

    @Test
    fun `without headers the queue and trace links are simply skipped`() {
        val timeline = timeline()
        timeline.pushed(match, PushHeaders.NONE)
        timeline.accepted("m1", ok = true)
        timeline.playerJoined("m1", alice)
        timeline.playerJoined("m1", bob)

        assertNull(meters.find(RuntimeMatchTimeline.QUEUE_TO_JOIN).timer())
        assertEquals(1, timer(RuntimeMatchTimeline.ALL_JOINED, "mode", "duel").count())
    }

    @Test
    fun `a wait that comes out negative is clock skew and is not recorded`() {
        val timeline = timeline()
        timeline.pushed(match, PushHeaders(enqueuedAt = mapOf(alice to clock.millis() + 60_000)))
        timeline.playerJoined("m1", alice)

        assertNull(meters.find(RuntimeMatchTimeline.QUEUE_TO_JOIN).timer())
    }

    @Test
    fun `repeats, strangers and unknown matches are ignored`() {
        val timeline = timeline()
        timeline.pushed(match, PushHeaders.NONE)
        timeline.playerJoined("m1", alice)
        timeline.playerJoined("m1", alice)
        timeline.playerJoined("m1", UUID.randomUUID())
        timeline.mapLoaded("m1", MapSource.CACHE)
        timeline.mapLoaded("m1", MapSource.DOWNLOAD)
        timeline.started("m1")
        timeline.started("m1")
        timeline.playerJoined("nope", alice)
        timeline.started("nope")
        timeline.mapLoaded("nope", MapSource.CACHE)
        timeline.finished("nope")

        assertEquals(1, timer(RuntimeMatchTimeline.FIRST_JOIN, "mode", "duel").count())
        assertNull(meters.find(RuntimeMatchTimeline.ALL_JOINED).timer())
        assertEquals(1, meters.get(RuntimeMatchTimeline.MAP).timers().sumOf { it.count() })
        assertEquals(1, timer(RuntimeMatchTimeline.START, "mode", "duel").count())
    }

    @Test
    fun `a retried push does not restart the timeline`() {
        val timeline = timeline()
        assertTrue(timeline.pushed(match, PushHeaders.NONE))
        clock.advance(500)
        assertFalse(timeline.pushed(match, PushHeaders.NONE))
        timeline.accepted("m1", ok = true)
        timeline.accepted("m1", ok = true)

        val accept = timer(RuntimeMatchTimeline.ACCEPT, "mode", "duel", "result", "ok")
        assertEquals(1, accept.count())
        assertEquals(0.5, seconds(accept))
    }

    @Test
    fun `state is forgotten on finish, on a failed accept and after the timeout`() {
        val timeline = timeline()
        timeline.pushed(match, PushHeaders.NONE)
        timeline.accepted("m1", ok = true)
        timeline.finished("m1")
        assertEquals(0, timeline.tracked())

        timeline.pushed(match.copy(matchId = "m2"), PushHeaders.NONE)
        timeline.accepted("m2", ok = false)
        assertEquals(0, timeline.tracked())
        assertEquals(
            1,
            timer(RuntimeMatchTimeline.ACCEPT, "mode", "duel", "result", "failed").count(),
        )

        timeline.pushed(match.copy(matchId = "m3"), PushHeaders.NONE)
        clock.advance(Duration.ofMinutes(119).toMillis())
        timeline.pushed(match.copy(matchId = "m4"), PushHeaders.NONE)
        timeline.expire()
        assertEquals(2, timeline.tracked())
        clock.advance(Duration.ofMinutes(2).toMillis())
        timeline.expire()
        assertEquals(1, timeline.tracked())
        assertTrue(
            lines.contains("Match timeline (match=m3, mode=duel, stage=expired, at=+7260000ms)")
        )
    }

    @Test
    fun `continues the pushed trace with the contract's spans and events`() {
        val exporter = InMemorySpanExporter.create()
        val provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val timeline = timeline(tracer = MatchTracing.of(provider).tracer)
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val parentId = "00f067aa0ba902b7"

        timeline.pushed(match, PushHeaders(traceparent = "00-$traceId-$parentId-01"))
        clock.advance(100)
        timeline.mapLoaded("m1", MapSource.CACHE)
        clock.advance(100)
        timeline.accepted("m1", ok = true)
        clock.advance(1_000)
        timeline.playerJoined("m1", alice)
        clock.advance(1_000)
        timeline.playerJoined("m1", bob)
        clock.advance(1_000)
        timeline.started("m1")
        clock.advance(1_000)
        timeline.finished("m1")

        val spans = exporter.finishedSpanItems.associateBy(SpanData::getName)
        assertEquals(setOf("match duel", "accept", "map", "wait for players"), spans.keys)
        val root = spans.getValue("match duel")
        assertEquals(traceId, root.traceId)
        assertEquals(parentId, root.parentSpanId)
        assertEquals("m1", root.attributes.asMap().entries.first { it.key.key == "match.id" }.value)
        assertEquals(
            listOf("player joined", "player joined", "started"),
            root.events.map { it.name },
        )
        assertEquals(4_200, millis(root))
        listOf("accept", "map", "wait for players").forEach { name ->
            val child = spans.getValue(name)
            assertEquals(traceId, child.traceId)
            assertEquals(root.spanId, child.parentSpanId)
        }
        assertEquals(200, millis(spans.getValue("accept")))
        assertEquals(100, millis(spans.getValue("map")))
        assertEquals(2_000, millis(spans.getValue("wait for players")))
        provider.close()
    }

    @Test
    fun `without a traceparent the match starts its own trace, and a refusal is an error`() {
        val exporter = InMemorySpanExporter.create()
        val provider =
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val timeline = timeline(tracer = MatchTracing.of(provider).tracer)

        timeline.pushed(match, PushHeaders.NONE)
        timeline.accepted("m1", ok = false)

        val root = exporter.finishedSpanItems.first { it.name == "match duel" }
        assertFalse(root.parentSpanContext.isValid)
        assertEquals(StatusCode.ERROR, root.status.statusCode)
        provider.close()
    }

    private fun millis(span: SpanData): Long =
        TimeUnit.NANOSECONDS.toMillis(span.endEpochNanos - span.startEpochNanos)

    private class MutableClock(var now: Instant = Instant.parse("2026-10-16T12:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now

        fun advance(millis: Long) {
            now = now.plusMillis(millis)
        }
    }
}

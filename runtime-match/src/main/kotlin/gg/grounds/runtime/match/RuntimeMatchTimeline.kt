package gg.grounds.runtime.match

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * The runtime's [MatchTimeline]: one [Match] per pushed match, from the push until it finishes,
 * fails to start, or [expire]s.
 *
 * Marks arrive from the NATS dispatcher (push, accept, often the map) and from the Minestom tick
 * thread (joins, start), so every match's state is only touched under its own lock.
 *
 * Children of the match span are written when their end is known, with explicit timestamps, so no
 * span is ever left open between two threads.
 */
internal class RuntimeMatchTimeline(
    private val meters: MeterRegistry,
    private val tracer: Tracer,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAge: Duration = DEFAULT_MAX_AGE,
    private val log: (String) -> Unit = logger::info,
) : MatchTimeline {
    private val matches = ConcurrentHashMap<String, Match>()

    /**
     * The push arrived. Returns false for a match already tracked — the matchmaker retrying on a
     * lost reply — whose timeline must not restart.
     */
    fun pushed(match: PushedMatch, headers: PushHeaders): Boolean {
        var fresh = false
        matches.computeIfAbsent(match.matchId) {
            fresh = true
            val now = clock.instant()
            val span =
                tracer
                    .spanBuilder("match ${match.modeId}")
                    .setParent(parent(headers.traceparent))
                    .setSpanKind(SpanKind.CONSUMER)
                    .setStartTimestamp(now)
                    .setAttribute(MODE, match.modeId)
                    .setAttribute(MATCH_ID, match.matchId)
                    .setAttribute(PLAYERS, match.teams.sumOf { it.size }.toLong())
                    .startSpan()
            Match(match, headers, now, span).also { mark(it, "push", now) }
        }
        return fresh
    }

    /**
     * [MatchHandler.start] returned ([ok]) or threw. A match that failed to start is over: nothing
     * will ever finish it, so it is forgotten here.
     */
    fun accepted(matchId: String, ok: Boolean) {
        withMatch(matchId) { match ->
            if ("accept" in match.stages) return
            val now = clock.instant()
            record(ACCEPT, "accept", match, now, "result", if (ok) "ok" else "failed")
            child(match, "accept", match.pushedAt, now) { span ->
                if (!ok) span.setStatus(StatusCode.ERROR, "handler refused the match")
            }
            mark(match, if (ok) "accept" else "accept_failed", now)
            if (!ok) {
                matches.remove(matchId, match)
                match.ended = true
                match.span.setStatus(StatusCode.ERROR, "handler refused the match")
                match.span.end(now)
            }
        }
    }

    /**
     * A [PreparedMatchHandler]'s round is ready ([ok]) or could not be prepared. From here the
     * players are on their way; before it they were still in the queue.
     */
    fun prepared(matchId: String, ok: Boolean) {
        withMatch(matchId) { match ->
            if ("prepared" in match.stages) return
            val now = clock.instant()
            record(PREPARED, "prepared", match, now, "result", if (ok) "ok" else "failed")
            child(match, "prepare", match.pushedAt, now) { span ->
                if (!ok) span.setStatus(StatusCode.ERROR, "round could not be prepared")
            }
            mark(match, if (ok) "prepared" else "prepare_failed", now)
        }
    }

    override fun mapLoaded(matchId: String, source: MatchTimeline.MapSource) {
        withMatch(matchId) { match ->
            if ("map" in match.stages) return
            val now = clock.instant()
            val label = source.name.lowercase()
            record(MAP, "map", match, now, "source", label)
            child(match, "map", match.pushedAt, now) { span -> span.setAttribute(SOURCE, label) }
            mark(match, "map", now)
        }
    }

    override fun playerJoined(matchId: String, playerId: UUID) {
        withMatch(matchId) { match ->
            if (playerId !in match.roster || !match.joined.add(playerId)) return
            val now = clock.instant()
            match.span.addEvent("player joined", Attributes.of(PLAYER_ID, playerId.toString()), now)

            val enqueuedAt = match.headers.enqueuedAt[playerId]
            if (enqueuedAt != null) {
                // Another host's clock: a negative wait is skew, not a measurement.
                val waited = Duration.between(Instant.ofEpochMilli(enqueuedAt), now)
                if (!waited.isNegative) timer(QUEUE_TO_JOIN, match).record(waited)
            }

            var stage = "join"
            if ("first_join" !in match.stages) {
                record(FIRST_JOIN, "first_join", match, now)
                stage = "first_join"
            }
            if (match.joined.size == match.roster.size) {
                record(ALL_JOINED, "all_joined", match, now)
                endWaiting(match, now)
                stage = "all_joined"
            }
            mark(match, stage, now)
        }
    }

    override fun started(matchId: String) {
        withMatch(matchId) { match ->
            if ("start" in match.stages) return
            val now = clock.instant()
            record(START, "start", match, now)
            endWaiting(match, now)
            match.span.addEvent("started", now)
            mark(match, "start", now)
            log(summary(match))
        }
    }

    override fun finished(matchId: String) {
        val match = matches.remove(matchId) ?: return
        synchronized(match) { end(match, "finish", clock.instant()) }
    }

    /** Forgets every match pushed longer than `maxAge` ago that nothing finished. */
    fun expire() {
        val now = clock.instant()
        matches.values
            .filter { Duration.between(it.pushedAt, now) > maxAge }
            .forEach { match ->
                if (!matches.remove(match.pushedMatch.matchId, match)) return@forEach
                synchronized(match) {
                    match.span.setStatus(StatusCode.ERROR, "never finished")
                    end(match, "expired", now)
                }
            }
    }

    /** Matches currently tracked; for tests. */
    fun tracked(): Int = matches.size

    /** Runs [block] under the match's lock, unless the match is unknown or already over. */
    private inline fun withMatch(matchId: String, block: (Match) -> Unit) {
        val match = matches[matchId] ?: return
        synchronized(match) { if (!match.ended) block(match) }
    }

    private fun end(match: Match, stage: String, now: Instant) {
        match.ended = true
        endWaiting(match, now)
        mark(match, stage, now)
        match.span.end(now)
    }

    /** `wait for players`: from the accept until everyone is in, or until waiting stops. */
    private fun endWaiting(match: Match, now: Instant) {
        if (match.waitingEnded) return
        val from = match.stages["accept"] ?: return
        match.waitingEnded = true
        child(match, "wait for players", from, now) { span ->
            span.setAttribute(JOINED, match.joined.size.toLong())
            span.setAttribute(PLAYERS, match.roster.size.toLong())
        }
    }

    private fun child(
        match: Match,
        name: String,
        from: Instant,
        to: Instant,
        edit: (Span) -> Unit,
    ) {
        val span =
            tracer
                .spanBuilder(name)
                .setParent(Context.root().with(match.span))
                .setStartTimestamp(from)
                .startSpan()
        edit(span)
        span.end(to)
    }

    /** Records [name] as push → [now], and [now] as [stage] in the summary. */
    private fun record(
        name: String,
        stage: String,
        match: Match,
        now: Instant,
        tagKey: String? = null,
        tagValue: String? = null,
    ) {
        match.stages[stage] = now
        timer(name, match, tagKey, tagValue).record(Duration.between(match.pushedAt, now))
    }

    private fun timer(
        name: String,
        match: Match,
        tagKey: String? = null,
        tagValue: String? = null,
    ): Timer {
        val builder =
            Timer.builder(name)
                .tag("mode", match.pushedMatch.modeId)
                .serviceLevelObjectives(*BUCKETS)
        if (tagKey != null && tagValue != null) builder.tag(tagKey, tagValue)
        return builder.register(meters)
    }

    private fun mark(match: Match, stage: String, at: Instant) {
        log(
            "Match timeline (match=${match.pushedMatch.matchId}, " +
                "mode=${match.pushedMatch.modeId}, stage=$stage, at=${offset(match, at)})"
        )
    }

    private fun summary(match: Match): String {
        val stages = buildList {
            match.stages.forEach { (stage, at) -> add("$stage=${offset(match, at)}") }
            add("joined=${match.joined.size}/${match.roster.size}")
            match.headers.formedAt?.let { add("formed=${offset(match, Instant.ofEpochMilli(it))}") }
        }
        return "Match timeline summary (match=${match.pushedMatch.matchId}, " +
            "mode=${match.pushedMatch.modeId}, ${stages.joinToString(", ")})"
    }

    private fun offset(match: Match, at: Instant): String {
        val millis = Duration.between(match.pushedAt, at).toMillis()
        return if (millis < 0) "${millis}ms" else "+${millis}ms"
    }

    private fun parent(traceparent: String?): Context {
        if (traceparent == null) return Context.root()
        return W3CTraceContextPropagator.getInstance()
            .extract(Context.root(), mapOf(PushHeaders.TRACEPARENT to traceparent), HeaderGetter)
    }

    private object HeaderGetter : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys

        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
    }

    private class Match(
        val pushedMatch: PushedMatch,
        val headers: PushHeaders,
        val pushedAt: Instant,
        val span: Span,
    ) {
        val roster: Set<UUID> = pushedMatch.teams.flatten().toSet()
        val joined = HashSet<UUID>()
        /** Stage → when it was first reached, in order; each stage is recorded once. */
        val stages = linkedMapOf("push" to pushedAt)
        var waitingEnded = false
        var ended = false
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MatchTimeline::class.java)

        /**
         * Long enough for any round to finish on its own; a match still here after it was simply
         * never finished, and keeping it would leak one entry per such match forever.
         */
        val DEFAULT_MAX_AGE: Duration = Duration.ofHours(2)

        private const val PREFIX = "grounds.match."
        const val ACCEPT = "${PREFIX}time.to.accept"
        const val MAP = "${PREFIX}time.to.map"
        const val PREPARED = "${PREFIX}time.to.prepared"
        const val FIRST_JOIN = "${PREFIX}time.to.first.join"
        const val ALL_JOINED = "${PREFIX}time.to.all.joined"
        const val START = "${PREFIX}time.to.start"
        const val QUEUE_TO_JOIN = "${PREFIX}queue.to.join"

        /** The contract's one bucket layout, shared with service-match's histograms. */
        val BUCKETS: Array<Duration> =
            doubleArrayOf(
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
                .map { Duration.ofMillis((it * 1000).toLong()) }
                .toTypedArray()

        private val MODE = AttributeKey.stringKey("mode")
        private val MATCH_ID = AttributeKey.stringKey("match.id")
        private val PLAYERS = AttributeKey.longKey("players")
        private val JOINED = AttributeKey.longKey("players.joined")
        private val PLAYER_ID = AttributeKey.stringKey("player.id")
        private val SOURCE = AttributeKey.stringKey("source")
    }
}

package gg.grounds.runtime.match

import gg.grounds.grpc.match.StartMatchReply
import gg.grounds.grpc.match.StartMatchRequest
import gg.grounds.runtime.PlayerPrefetch
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import org.slf4j.LoggerFactory

/**
 * Turns a [StartMatchRequest] the matchmaker pushed at this server into a [PushedMatch] and hands
 * it to [MatchRegistry.accept].
 *
 * Transport-free on purpose: [MatchHostModule] owns the NATS subscription and only ever asks this
 * class the one question. That the exchange is request-reply rather than a fire-and-forget event is
 * the whole point — the reply is what the matchmaker uses to decide whether to route the players
 * here or put them back on the queue, so a match nobody answered for has to end up back in the
 * queue rather than nowhere.
 *
 * Which is also why [startMatch] always returns a reply and never throws: a thrown exception leaves
 * the matchmaker unable to tell "refused" from "lost".
 */
class MatchHostService
internal constructor(
    private val registry: MatchRegistry,
    private val handler: MatchHandler,
    private val timeline: RuntimeMatchTimeline?,
    private val prefetches: () -> List<PlayerPrefetch> = { emptyList() },
    /** Where a [PreparedMatchHandler]'s rounds are reported ready; null: never wait for them. */
    private val readySignal: MatchReadySignal? = null,
    /** Runs the ready signal, which blocks on HTTP; never the dispatcher or the tick. */
    private val signalling: Executor = Executor { task -> Thread.ofVirtual().start(task) },
) {
    constructor(registry: MatchRegistry, handler: MatchHandler) : this(registry, handler, null)

    private val logger = LoggerFactory.getLogger(MatchHostService::class.java)

    fun startMatch(request: StartMatchRequest): StartMatchReply =
        startMatch(request, PushHeaders.NONE)

    internal fun startMatch(request: StartMatchRequest, headers: PushHeaders): StartMatchReply {
        val teams = request.teamsList
        if (teams.all { it.playerIdsList.isEmpty() }) {
            return reply(accepted = false, reason = "empty roster")
        }

        val roster =
            try {
                teams.map { team -> team.playerIdsList.map(UUID::fromString) }
            } catch (e: IllegalArgumentException) {
                return reply(accepted = false, reason = "malformed player id")
            }

        val match = PushedMatch(request.matchId, request.modeId, roster, request.mapAddress)
        // Before the handler runs: a game loads its map inside start(), and marks it then.
        val fresh = timeline?.pushed(match, headers) ?: false
        prefetch(match)
        val accepted =
            try {
                registry.accept(match, handler)
            } catch (t: Throwable) {
                // Over gRPC an escaping throw still reached the matchmaker as an error status. Over
                // NATS it reaches nobody at all: the dispatcher swallows it, no reply is published,
                // and the players sit in a match that was never refused and never started. Anything
                // we could not take is a refusal.
                logger.error("Match host failed on match {}", match.matchId, t)
                false
            }
        if (fresh) timeline?.accepted(match.matchId, accepted)
        // Only when the matchmaker asked to wait, the game prepares its rounds, and there is a way
        // to tell the matchmaker: otherwise the players are sent at once, as before.
        val signalsReady =
            accepted &&
                request.waitForReady &&
                handler is PreparedMatchHandler &&
                readySignal != null
        if (signalsReady && fresh) watchPreparation(handler as PreparedMatchHandler, match)
        return if (accepted) {
            reply(accepted = true, reason = "", signalsReady = signalsReady)
        } else {
            logger.warn("Match host refused match {}", match.matchId)
            reply(accepted = false, reason = "match host refused the match")
        }
    }

    /**
     * Before the handler: the matchmaker routes the players here as soon as this answers, and a
     * game's start() can take seconds. A prefetch that throws costs nothing but the log line.
     */
    private fun prefetch(match: PushedMatch) {
        val players = match.teams.flatten()
        for (prefetch in prefetches()) {
            try {
                prefetch.prefetch(players, "match ${match.matchId}")
            } catch (e: Exception) {
                logger.warn("Player prefetch failed for match {}", match.matchId, e)
            }
        }
    }

    /**
     * Reports [match] ready once [handler] has prepared it. A round that failed to get ready, or
     * that the matchmaker no longer routes (it gave up in the meantime), is cancelled here.
     */
    private fun watchPreparation(handler: PreparedMatchHandler, match: PushedMatch) {
        @Suppress("UNCHECKED_CAST")
        val stage: CompletionStage<Any?> =
            try {
                handler.prepared(match) as CompletionStage<Any?>
            } catch (t: Throwable) {
                CompletableFuture.failedFuture(t)
            }
        stage.whenCompleteAsync(
            { _, error ->
                val ok = error == null
                timeline?.prepared(match.matchId, ok)
                if (!ok) logger.error("Match {} could not be prepared", match.matchId, error)
                val routed =
                    readySignal?.send(
                        match.matchId,
                        ok,
                        error?.let { it.message ?: it.javaClass.simpleName } ?: "",
                    )
                if (!ok) cancel(match.matchId, "preparation failed")
                else if (routed == false) cancel(match.matchId, "the matchmaker gave it up")
            },
            signalling,
        )
    }

    /**
     * The matchmaker gave up on [matchId] before its players came, or its round could not be
     * prepared: discard the round and free its slot.
     */
    fun cancel(matchId: String, reason: String) {
        logger.warn("Match {} cancelled before its players came: {}", matchId, reason)
        try {
            (handler as? PreparedMatchHandler)?.cancel(matchId, reason)
        } catch (e: Exception) {
            logger.error("Match handler failed to cancel match {}", matchId, e)
        }
        registry.finish(matchId)
    }

    private fun reply(
        accepted: Boolean,
        reason: String,
        signalsReady: Boolean = false,
    ): StartMatchReply =
        StartMatchReply.newBuilder()
            .setAccepted(accepted)
            .setReason(reason)
            .setSignalsReady(signalsReady)
            .build()
}

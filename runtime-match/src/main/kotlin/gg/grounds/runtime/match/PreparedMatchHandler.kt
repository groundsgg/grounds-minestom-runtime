package gg.grounds.runtime.match

import java.util.concurrent.CompletionStage

/**
 * A [MatchHandler] whose rounds take a while to get ready — a map to load and light, an arena to
 * build — and that would rather keep its players in the queue meanwhile than make them wait on a
 * loading screen.
 *
 * [start] still answers the push at once (accept or refuse; the matchmaker gives it five seconds
 * and does not retry). The round then gets ready in the background, and [prepared] says when. The
 * runtime tells the matchmaker, which only then sends the players: until then they stay in the
 * queue, shown as "preparing". If it is not ready in time, or enough players leave the queue, the
 * matchmaker gives the round up and the runtime calls [cancel].
 *
 * With a matchmaker too old to wait (it does not ask), the players are sent the moment the match is
 * accepted, as before; a handler must still cope with players arriving before [prepared].
 */
interface PreparedMatchHandler : MatchHandler {

    /**
     * Completes once the round [start] accepted for [match] is ready for its players, or
     * exceptionally if it cannot be. Called once per match, after [start] returned.
     */
    fun prepared(match: PushedMatch): CompletionStage<*>

    /**
     * The matchmaker gave up on [matchId] before its players came — it was not ready in time, or
     * too many of them left the queue. Discard the round. The runtime frees its slot itself.
     */
    fun cancel(matchId: String, reason: String) {}
}

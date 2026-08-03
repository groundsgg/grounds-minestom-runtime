package gg.grounds.runtime.match

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Tracks the matches this server is currently hosting.
 *
 * The matchmaker's StartMatch call and its reply can each be lost independently over the network,
 * so a retried call is indistinguishable from a fresh push. [accept] and [finish] are both
 * idempotent on `matchId` so a retry can never build the same arena twice, and finishing can never
 * free the same slot twice.
 */
class MatchRegistry(private val counters: MatchCounters) {
    private val logger = LoggerFactory.getLogger(MatchRegistry::class.java)
    private val live = ConcurrentHashMap<String, PushedMatch>()

    /**
     * Hands [match] to [handler]. Returns true once the match is hosted, whether this call actually
     * started it or an earlier call already did.
     *
     * A matchId already present short-circuits without invoking [handler] again: the matchmaker
     * retries on a lost reply, and re-running the handler would build a second arena for a match
     * that is already running. If [handler] throws, the match is not registered — the server is
     * refusing the match, not hosting one it never actually built.
     *
     * **A refusal releases the slot.** Allocation already incremented the counter before this call;
     * if the server then declines the match, nothing will ever call [finish] for it, so the slot
     * would be advertised as taken forever. One such refusal per round is all it takes to retire a
     * server: it stays `Ready`, reports itself full, and every later allocation fails against it
     * with "no server with a free slot" while it sits there empty. That is not hypothetical — it
     * took stage down in both regions for hours.
     */
    fun accept(match: PushedMatch, handler: MatchHandler): Boolean {
        return try {
            // computeIfAbsent runs the mapping function at most once per key even
            // under concurrent calls, which is exactly the "handler runs once"
            // guarantee accept() needs. Its value type can't hold a "refused"
            // marker (PushedMatch isn't nullable), so a refusal is signalled by
            // throwing out of the mapping function instead — caught right here,
            // before it can escape as an unregistered mapping.
            live.computeIfAbsent(match.matchId) {
                try {
                    handler.start(match)
                } catch (t: Throwable) {
                    // Throwable, not Exception. A gamemode that fails to start because a class is
                    // missing from its jar throws NoClassDefFoundError, which is an Error — it went
                    // straight past a `catch (e: Exception)` here, killed the gRPC worker thread,
                    // and left the slot leaked because nothing below ran. An Error out of a handler
                    // is exactly as much a refusal as an exception is.
                    throw HandlerRefusedException(t)
                }
                match
            }
            true
        } catch (e: HandlerRefusedException) {
            logger.error("Match handler refused match {}", match.matchId, e.cause)
            counters.decrement()
            false
        }
    }

    private class HandlerRefusedException(cause: Throwable) : RuntimeException(cause)

    /**
     * Removes [matchId] and decrements the Agones counter, but only if the match was actually live.
     * `finish` must itself be safe to call more than once (the gamemode may call it defensively) —
     * decrementing on a repeat call would advertise a slot this server doesn't actually have free.
     */
    fun finish(matchId: String) {
        if (live.remove(matchId) != null) {
            counters.decrement()
        }
    }

    fun live(): Int = live.size
}

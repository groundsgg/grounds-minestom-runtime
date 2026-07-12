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
                } catch (e: Exception) {
                    throw HandlerRefusedException(e)
                }
                match
            }
            true
        } catch (e: HandlerRefusedException) {
            logger.error("Match handler refused match {}", match.matchId, e.cause)
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

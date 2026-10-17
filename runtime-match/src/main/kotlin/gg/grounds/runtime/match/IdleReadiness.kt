package gg.grounds.runtime.match

import java.time.Duration
import org.slf4j.LoggerFactory

/** This GameServer's Agones state, as far as [IdleReadiness] moves it. Never throws. */
interface GameServerState {
    /** Returns true once the SDK accepted the call. */
    fun ready(): Boolean

    /** Returns true once the SDK accepted the call. */
    fun allocate(): Boolean
}

/**
 * Puts a matchmade server back to `Ready` once it has hosted nothing for [grace].
 *
 * The matchmaker's allocation moves a server to `Allocated`, and nothing ever moved it back: when
 * its last match finished it sat there `Allocated` and empty for the rest of its life. Agones only
 * replaces `Ready` GameServers in a Fleet rolling update, so such a server never got a new image,
 * and the autoscaler counted it as busy.
 *
 * `Ready` costs the matchmaker nothing. Its allocation prefers `Allocated` servers with a free slot
 * but falls back to `Ready` ones with a free slot, and allocating a `Ready` server moves it
 * straight back to `Allocated`.
 *
 * **Idle** means no match is live, none is being accepted ([MatchRegistry.idle]), and the `matches`
 * counter is 0. The counter matters because allocation raises it *before* its StartMatch arrives: a
 * server at 0 live matches but 1 on the counter has players on their way.
 *
 * **The race.** An allocation can still land between reading the counter at 0 and the SDK applying
 * `ready()`, leaving the server `Ready` with a match on its way. It accepts that StartMatch as
 * usual, and [busy] moves it back to `Allocated` before the match is built, so a rolling update or
 * scale-down cannot delete it mid-match. [tick] does the same if the counter goes up while it sits
 * `Ready`, in case that StartMatch never comes.
 *
 * Both methods hold the same lock, so a push that starts while [tick] is readying waits for it and
 * then sees [readied].
 */
class IdleReadiness(
    private val idle: () -> Boolean,
    /** The `matches` counter, or null when the sidecar does not say. */
    private val advertised: () -> Int?,
    private val state: GameServerState,
    private val grace: Duration,
    private val clock: () -> Long = System::nanoTime,
) {
    private val logger = LoggerFactory.getLogger(IdleReadiness::class.java)
    private var idleSince: Long? = null
    private var readied = false

    /** A match is being accepted or is running. */
    @Synchronized
    fun busy() {
        idleSince = null
        if (readied && state.allocate()) {
            readied = false
            logger.info("Hosting a match again; marked this GameServer Allocated")
        }
    }

    /** Called periodically. */
    @Synchronized
    fun tick() {
        if (!idle()) return busy()
        if (readied) {
            // Allocated by the matchmaker while Ready: its StartMatch is on the way.
            if ((advertised() ?: 0) > 0) busy()
            return
        }
        val now = clock()
        val since = idleSince ?: now.also { idleSince = it }
        if (now - since < grace.toNanos()) return
        // Not 0 means a match is on its way, or the sidecar did not answer. Either way, wait
        // another grace period.
        if (advertised() != 0 || !state.ready()) {
            idleSince = now
            return
        }
        readied = true
        logger.info("No match for {}s; marked this GameServer Ready", grace.seconds)
    }

    companion object {
        const val ENABLED_ENV = "GROUNDS_MATCHMAKING_REREADY"
        const val GRACE_ENV = "GROUNDS_MATCHMAKING_REREADY_GRACE_SECONDS"
        val DEFAULT_GRACE: Duration = Duration.ofSeconds(20)

        /** The grace period, or null when [ENABLED_ENV] turns re-readying off. */
        fun graceFromEnv(getenv: (String) -> String? = System::getenv): Duration? {
            val enabled = getenv(ENABLED_ENV)?.trim()?.lowercase()
            if (enabled == "false" || enabled == "0") return null
            val seconds = getenv(GRACE_ENV)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            return seconds?.let(Duration::ofSeconds) ?: DEFAULT_GRACE
        }
    }
}

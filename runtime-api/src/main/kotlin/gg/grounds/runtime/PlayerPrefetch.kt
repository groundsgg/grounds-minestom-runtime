package gg.grounds.runtime

import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.qualifiedServiceKey
import java.util.UUID

/**
 * Loads per-player state for players who are about to connect, so their login does not wait on it.
 *
 * The match host calls every registered [PlayerPrefetch] with the roster of each match pushed to
 * this server, a few seconds before the first of those players connects.
 *
 * [prefetch] runs on the thread that answers the push, and the matchmaker is waiting for that
 * answer. It must hand the work off and return at once, and it must not throw. A prefetch only
 * saves time: the login has to work without one.
 */
fun interface PlayerPrefetch {
    /** @param reason what announced the players, for logs, e.g. `match 22e9d838`. */
    fun prefetch(players: Collection<UUID>, reason: String)
}

/**
 * Registers [prefetch] under the key of module [moduleId], so that several modules can each add
 * one.
 */
fun ServiceRegistry.registerPlayerPrefetch(moduleId: String, prefetch: PlayerPrefetch) {
    register(qualifiedServiceKey<PlayerPrefetch>(moduleId), prefetch)
}

/** Every [PlayerPrefetch] registered so far. */
fun ServiceRegistry.playerPrefetches(): List<PlayerPrefetch> =
    keys()
        .filter { key -> key.type == PlayerPrefetch::class }
        .mapNotNull { key -> getUntyped(key) as? PlayerPrefetch }

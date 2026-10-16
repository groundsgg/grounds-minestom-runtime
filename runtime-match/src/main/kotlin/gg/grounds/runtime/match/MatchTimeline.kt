package gg.grounds.runtime.match

import java.util.UUID

/**
 * Where the seconds between a pushed match and a running round went, per match.
 *
 * The runtime marks what it sees itself: the push arriving and [MatchHandler.start] returning (or
 * throwing). Only the gamemode knows when its map is ready, when a player is really in, and when
 * the round is actually being played, so it reports those through this interface. Every mark is one
 * INFO line (`Match timeline (match=<id>, mode=<mode>, stage=<stage>, at=+<ms>ms)`), a histogram in
 * seconds labelled by `mode` only, and part of the match's trace.
 *
 * Get it from [MatchHostModule.timeline] once the module has installed. Every call is safe from any
 * thread, is idempotent, and ignores a matchId the runtime is not hosting — so a gamemode can call
 * it without guarding.
 *
 * ```kotlin
 * override fun start(match: PushedMatch) {
 *     val map = maps.load(match.mapAddress) // however the game fetches its map
 *     val source = if (map.fromCache) MapSource.CACHE else MapSource.DOWNLOAD
 *     host.timeline.mapLoaded(match.matchId, source) // MatchTimeline.MapSource
 * }
 *
 * // A player is "joined" on their first spawn in the match's world, not on connect.
 * events.addListener(PlayerSpawnEvent::class.java) { event ->
 *     if (event.isFirstSpawn) host.timeline.playerJoined(matchId, event.player.uuid)
 * }
 *
 * // The countdown is over and the round is being played.
 * host.timeline.started(matchId)
 * ```
 *
 * [finished] needs no call of its own: [MatchRegistry.finish] calls it.
 */
interface MatchTimeline {
    /** The match's map is loaded and ready to play on, from [source]. Recorded once per match. */
    fun mapLoaded(matchId: String, source: MapSource)

    /**
     * [playerId] is in the match's world. Call it once the player is actually there (first spawn),
     * not when the connection starts. Repeats and players not on the pushed roster are ignored.
     */
    fun playerJoined(matchId: String, playerId: UUID)

    /** The round is being played (countdown over). Recorded once per match. */
    fun started(matchId: String)

    /** The match is over; ends its trace and forgets it. [MatchRegistry.finish] calls this. */
    fun finished(matchId: String)

    /** Whether a map came from this server's local cache or had to be downloaded first. */
    enum class MapSource {
        CACHE,
        DOWNLOAD,
    }
}

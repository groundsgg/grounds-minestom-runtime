package gg.grounds.runtime.match

import java.util.UUID

/**
 * A match the matchmaker has already decided and pushed to this server.
 *
 * [teams] is the roster in draft order. The matchmaker balanced these teams before pushing the
 * match; re-shuffling them here would mean the server plays a different match than the one whose
 * result gets reported back and rated.
 *
 * @property mapAddress which map to play, as service-maps addresses it — `<namespace>/<name>`, e.g.
 *   `bedwars/4-4-platzangst`. Empty when the matchmaker has no map for this mode, which is normal
 *   for a game that builds its own arena; a game that needs one should refuse the match rather than
 *   fall back to a map of its own choosing, or which map is played would depend on which pod
 *   happened to be allocated.
 */
data class PushedMatch(
    val matchId: String,
    val modeId: String,
    val teams: List<List<UUID>>,
    val mapAddress: String = "",
)

package gg.grounds.runtime.match

import java.util.UUID

/**
 * A match the matchmaker has already decided and pushed to this server.
 *
 * [teams] is the roster in draft order. The matchmaker balanced these teams before pushing the
 * match; re-shuffling them here would mean the server plays a different match than the one whose
 * result gets reported back and rated.
 */
data class PushedMatch(val matchId: String, val modeId: String, val teams: List<List<UUID>>)

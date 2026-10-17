package gg.grounds.runtime.match

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.timer.Scheduler
import net.minestom.server.timer.TaskSchedule

/**
 * Sends players back to a lobby when their match is over, without a "Match over" message.
 *
 * A game server cannot move a player itself; only the proxy can. [toLobby] asks plugin-match to do
 * it with the `Lobby` verb on the `grounds:match` plugin-message channel, which the proxy turns
 * into a plain server switch: no kick, no chat line. Whoever is still connected after `kickAfter`
 * (a proxy without the verb yet, no lobby to go to, a lost message) is kicked with an empty reason,
 * and plugin-match's kick fallback redirects them silently too.
 */
object MatchExit {
    /** plugin-match's channel, shared with the lobby's mode list and queue requests. */
    const val CHANNEL = "grounds:match"

    /** The verb asking the proxy to send this connection's player to a lobby. No arguments. */
    const val LOBBY = "Lobby"

    /** How long the proxy gets to move a player before the fallback kick. */
    @JvmField val DEFAULT_KICK_AFTER: Duration = Duration.ofSeconds(5)

    /**
     * The `Lobby` message: one `DataOutputStream.writeUTF` string, the wire shape of every verb on
     * [CHANNEL].
     */
    @JvmStatic
    fun lobbyMessage(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.writeUTF(LOBBY) }
        return bytes.toByteArray()
    }

    /**
     * Asks the proxy to move each of [players] to a lobby, then kicks those still connected after
     * [kickAfter] with an empty reason.
     *
     * Call it once a match is finished, in place of kicking its players. [players] is copied, so a
     * live collection such as `instance.players` is fine.
     */
    @JvmStatic
    @JvmOverloads
    fun toLobby(players: Collection<Player>, kickAfter: Duration = DEFAULT_KICK_AFTER) =
        toLobby(players, kickAfter, MinecraftServer.getSchedulerManager())

    internal fun toLobby(players: Collection<Player>, kickAfter: Duration, scheduler: Scheduler) {
        val leaving = players.toList()
        if (leaving.isEmpty()) return
        val message = lobbyMessage()
        leaving.forEach { it.sendPluginMessage(CHANNEL, message) }
        scheduler
            .buildTask { leaving.filter { it.isOnline }.forEach { it.kick(Component.empty()) } }
            .delay(TaskSchedule.duration(kickAfter))
            .schedule()
    }
}

package gg.grounds.duel

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.GroundsServer
import gg.grounds.runtime.match.MatchHandler
import gg.grounds.runtime.match.MatchHostModule
import gg.grounds.runtime.match.MatchRegistry
import gg.grounds.runtime.match.PushedMatch
import gg.grounds.vanilla.Vanilla
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.block.Block
import org.slf4j.LoggerFactory

/**
 * A 1v1 duel Minestom server the matchmaker drives.
 *
 * service-match allocates one of these, then pushes a match over gRPC (MatchHost, handled by
 * [MatchHostModule]); this builds an arena for it, seats the two players it named, and lets them
 * fight. When one dies the other wins, the arena is torn down and the Agones `matches` counter is
 * decremented so the slot frees.
 *
 * Deliberately small. Real combat comes from `gg.grounds.vanilla` (the same PvP the rest of the
 * platform uses) with a single [Vanilla.install]; the only game logic here is "two players in, one
 * death, match over". Ratings are not reported back yet — a match plays and frees its slot, which
 * is what the pipeline needs; ReportMatchResult to service-match is a follow-up.
 */

/** One running duel: its arena, and who is in it. */
private class DuelArena(
    val matchId: String,
    val instance: InstanceContainer,
    /** playerId -> where they spawn. Two corners, facing in. */
    val spawns: Map<UUID, Pos>,
)

/**
 * Holds every running duel and, as the [MatchHandler], turns a pushed match into an arena. One
 * instance is shared between the MatchHost server (which calls [start]) and the Minestom module
 * (which routes joins and reacts to deaths).
 */
private class DuelService : MatchHandler {
    private val log = LoggerFactory.getLogger(DuelService::class.java)
    private val byMatch = ConcurrentHashMap<String, DuelArena>()
    private val playerMatch = ConcurrentHashMap<UUID, String>()

    /** Set once by the module at start — the MatchHost registry, for the counter. */
    lateinit var registry: MatchRegistry

    /** MatchHost pushed a match. Build its arena; throwing here refuses it. */
    override fun start(match: PushedMatch) {
        val players = match.teams.flatten()
        require(players.size == 2) {
            "duel is 1v1: expected 2 players, got ${players.size} (match=${match.matchId})"
        }

        val instance = MinecraftServer.getInstanceManager().createInstanceContainer()
        instance.setChunkSupplier(::LightingChunk)
        // A flat stone platform. fillHeight is [min, max), so this tops out at
        // y=40 and players stand on y=41.
        instance.setGenerator { unit -> unit.modifier().fillHeight(0, 41, Block.STONE) }

        val spawns =
            mapOf(
                players[0] to Pos(-6.5, 41.0, 0.5, 90f, 0f),
                players[1] to Pos(6.5, 41.0, 0.5, -90f, 0f),
            )
        byMatch[match.matchId] = DuelArena(match.matchId, instance, spawns)
        players.forEach { playerMatch[it] = match.matchId }
        log.info("Duel arena ready (match={}, players={})", match.matchId, players)
    }

    fun arenaFor(playerId: UUID): DuelArena? = playerMatch[playerId]?.let { byMatch[it] }

    /** [loser] died — their opponent wins and the match ends. */
    fun onDeath(loser: UUID) {
        val matchId = playerMatch[loser] ?: return
        finish(matchId, loser)
    }

    /** A player left mid-duel — treat it as a loss and end the match. */
    fun onLeave(playerId: UUID) {
        val matchId = playerMatch[playerId] ?: return
        finish(matchId, playerId)
    }

    private fun finish(matchId: String, loser: UUID?) {
        val arena = byMatch.remove(matchId) ?: return
        arena.spawns.keys.forEach { playerMatch.remove(it) }

        // Free the Agones slot. Idempotent — safe if a death and a disconnect
        // race for the same match.
        registry.finish(matchId)

        // Send everyone still connected back to the proxy's fallback (the lobby).
        val cm = MinecraftServer.getConnectionManager()
        arena.spawns.keys
            .mapNotNull { cm.getOnlinePlayerByUuid(it) }
            .forEach { it.kick("Match over") }

        log.info("Duel finished (match={}, loser={})", matchId, loser)
    }
}

private class DuelModule(private val service: DuelService, private val host: MatchHostModule) :
    GroundsModule {
    private val log = LoggerFactory.getLogger(DuelModule::class.java)

    override val id: String = "grounds.duel"

    override fun install(ctx: GroundsServerContext) {
        // PvP: combat, damage, knockback, death — for every instance at once.
        Vanilla.install(MinecraftServer.getGlobalEventHandler())

        val events = MinecraftServer.getGlobalEventHandler()

        // Seat a joining player in the arena the matchmaker put them in. On a
        // matchmade server there is no other reason to be here, so a player with
        // no arena (a stray connection) is refused rather than dropped onto a
        // non-existent instance.
        events.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            val player = event.player
            val arena = service.arenaFor(player.uuid)
            if (arena == null) {
                log.warn("Connection with no match, refusing (player={})", player.uuid)
                player.kick("No match found")
                return@addListener
            }
            event.spawningInstance = arena.instance
            player.respawnPoint = arena.spawns.getValue(player.uuid)
        }

        // One death ends the duel: the other player wins.
        events.addListener(PlayerDeathEvent::class.java) { event ->
            service.onDeath(event.player.uuid)
        }

        // A rage-quit is a loss, not a stuck match.
        events.addListener(PlayerDisconnectEvent::class.java) { event ->
            service.onLeave(event.player.uuid)
        }
    }

    override fun start() {
        // The MatchHost registry only exists after its module installed; wiring
        // it here (all installs are done) lets a finished duel free the counter.
        service.registry = host.matches
    }
}

private class DuelModuleProvider(
    private val service: DuelService,
    private val host: MatchHostModule,
) : GroundsModuleProvider {
    override val id: String = "grounds.duel"
    override val version: String = "local"
    override val serverTypes: Set<ServerType> = setOf(ServerType.MINIGAME)

    override fun create(): GroundsModule = DuelModule(service, host)
}

fun main() {
    val service = DuelService()
    // MatchHostModule needs the handler up front; the module and its provider
    // then share the one service so join-routing and death both see the same
    // arenas the matchmaker pushed.
    val matchHost = MatchHostModule(service)

    GroundsServer.builder()
        .discoverProviders()
        // Agones lifecycle: with GROUNDS_MATCHMAKING=1, ready() once, then hands
        // off — it must not ready the server back into the pool mid-match.
        .useProvider("grounds.agones")
        .use(matchHost)
        .use(DuelModuleProvider(service, matchHost))
        .build()
        .start()
}

package gg.grounds.runtime.match

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import io.grpc.Server
import io.grpc.ServerBuilder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Runs the MatchHost gRPC server that lets the matchmaker push matches onto this runtime.
 * Plaintext: this is a pod-to-pod call inside the project's own vCluster, and the vCluster boundary
 * is already the tenancy boundary — there is no foreign network hop here to secure with TLS.
 *
 * Deliberately ships with no `META-INF/services` SPI provider. `GroundsModuleProvider.create()` is
 * no-arg, but this module needs the game's own [MatchHandler] to build arenas with, so it cannot be
 * auto-discovered like other modules — the gamemode constructs `MatchHostModule(myHandler)` itself
 * in its own provider.
 */
class MatchHostModule(
    private val handler: MatchHandler,
    private val port: Int = System.getenv("GROUNDS_MATCH_HOST_PORT")?.toIntOrNull() ?: 9090,
) : GroundsModule {
    override val id: String = "grounds.match-host"

    private val logger = LoggerFactory.getLogger(MatchHostModule::class.java)
    private lateinit var service: MatchHostService
    private lateinit var counters: AgonesCounters
    private var server: Server? = null
    private var drift: ScheduledExecutorService? = null
    private var suspected = 0

    /** So the gamemode can call [MatchRegistry.finish] when a match ends. */
    lateinit var matches: MatchRegistry
        private set

    override fun install(ctx: GroundsServerContext) {
        counters = AgonesCounters()
        matches = MatchRegistry(counters)
        service = MatchHostService(matches, handler)
    }

    override fun start() {
        server = ServerBuilder.forPort(port).addService(service).build().start()
        logger.info("MatchHost gRPC server listening on port {}", port)

        drift =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "match-slot-drift").apply { isDaemon = true }
            }
        drift?.scheduleAtFixedRate(
            ::correctDrift,
            DRIFT_INTERVAL_SECONDS,
            DRIFT_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    /**
     * Put the advertised count back to what this server is actually running.
     *
     * The counter is a shared number: the matchmaker raises it when it allocates a slot, this
     * server lowers it when a match ends. Any path that raises without lowering strands a slot
     * permanently, and a server whose slots are all stranded goes on reporting itself full while
     * sitting idle — no allocation ever lands on it again and nothing recovers it but a restart.
     *
     * **Only ever downwards, and only after two agreeing observations.** Between the matchmaker
     * incrementing the counter and its StartMatch arriving here, the count is legitimately one
     * higher than the number of matches running; correcting on a single reading would hand that
     * slot to somebody else and put two matches where one fits. Two readings a minute apart cannot
     * both fall inside that window. Never upwards, because a count *below* what is running is the
     * matchmaker's business and raising it here would fight the allocation it is in the middle of.
     */
    private fun correctDrift() {
        try {
            val live = matches.live()
            val advertised = counters.advertised() ?: return
            if (advertised <= live) {
                suspected = 0
                return
            }
            suspected++
            if (suspected < DRIFT_CONFIRMATIONS) {
                logger.debug(
                    "Slot count looks high ({} advertised, {} running); waiting",
                    advertised,
                    live,
                )
                return
            }
            logger.warn(
                "Correcting leaked match slots: {} advertised, {} actually running",
                advertised,
                live,
            )
            counters.reconcile(live)
            suspected = 0
        } catch (e: Exception) {
            logger.warn("Slot drift check failed", e)
        }
    }

    override fun stop() {
        drift?.shutdownNow()
        drift = null
        server?.shutdown()
        server = null
    }

    private companion object {
        const val DRIFT_INTERVAL_SECONDS = 60L
        /** Two agreeing readings, so a match in flight is never mistaken for a leak. */
        const val DRIFT_CONFIRMATIONS = 2
    }
}

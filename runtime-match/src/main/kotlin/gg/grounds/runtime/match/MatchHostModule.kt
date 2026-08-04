package gg.grounds.runtime.match

import com.google.protobuf.InvalidProtocolBufferException
import gg.grounds.grpc.match.StartMatchReply
import gg.grounds.grpc.match.StartMatchRequest
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Message
import io.nats.client.Nats
import io.nats.client.Options
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Answers the matchmaker's StartMatch over NATS request-reply, on the one subject that names this
 * server: `match.host.<gameServerName>.start`.
 *
 * Request-reply rather than an event, because the matchmaker cannot act on the push until it knows
 * the answer: an accepted match means it routes the players here, a refused one means they go back
 * on the queue. A published event would tell it neither.
 *
 * Deliberately ships with no `META-INF/services` SPI provider. `GroundsModuleProvider.create()` is
 * no-arg, but this module needs the game's own [MatchHandler] to build arenas with, so it cannot be
 * auto-discovered like other modules — the gamemode constructs `MatchHostModule(myHandler)` itself
 * in its own provider.
 */
class MatchHostModule(
    private val handler: MatchHandler,
    // Agones names the pod after the GameServer it created, so `HOSTNAME` inside that pod is
    // literally the GameServer name the matchmaker allocated and is now addressing. The explicit
    // override exists for runs that are not a GameServer pod at all.
    private val serverName: String? =
        System.getenv("GROUNDS_MATCH_HOST_NAME") ?: System.getenv("HOSTNAME"),
    private val natsUrl: String = System.getenv("NATS_URL") ?: DEFAULT_NATS_URL,
) : GroundsModule {
    override val id: String = "grounds.match-host"

    private val logger = LoggerFactory.getLogger(MatchHostModule::class.java)
    private lateinit var service: MatchHostService
    private lateinit var counters: AgonesCounters
    private var connection: Connection? = null
    private var dispatcher: Dispatcher? = null
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
        subscribe()

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
     * A server that cannot work out its own name must not subscribe at all. The subject carries the
     * addressing, so guessing one wrong would silently answer another server's StartMatch and take
     * matches meant for it.
     */
    private fun subscribe() {
        if (serverName.isNullOrBlank()) {
            logger.error(
                "Neither GROUNDS_MATCH_HOST_NAME nor HOSTNAME is set; not accepting matches"
            )
            return
        }
        val connection = connect() ?: return
        val subject = "match.host.$serverName.start"
        this.connection = connection
        dispatcher =
            connection
                .createDispatcher { message -> respond(connection, message) }
                .subscribe(subject)
        logger.info("MatchHost listening on {}", subject)
    }

    /**
     * A broker that is down leaves this server running without a responder rather than refusing to
     * boot: the matchmaker still falls back to gRPC for one release, and a gamemode that will not
     * start is worse than one that cannot be given matches.
     */
    private fun connect(): Connection? =
        try {
            val builder = Options.Builder().server(natsUrl).connectionName("$id/$serverName")
            // The projected SA token as the NATS bearer, re-read per (re)connect so kubelet
            // rotation is picked up. Absent file means no token, which is right for local runs.
            val tokenPath = Path.of(System.getenv("GROUNDS_TOKEN_FILE") ?: DEFAULT_TOKEN_FILE)
            if (Files.exists(tokenPath)) {
                builder.tokenSupplier { Files.readString(tokenPath).trim().toCharArray() }
            }
            Nats.connect(builder.build())
        } catch (e: Exception) {
            logger.error("Failed to connect to NATS at {}; not accepting matches", natsUrl, e)
            null
        }

    private fun respond(connection: Connection, message: Message) {
        val replyTo = message.replyTo
        if (replyTo == null) {
            logger.warn("Dropping a StartMatch with no reply subject; there is nobody to answer")
            return
        }
        val reply =
            try {
                service.startMatch(StartMatchRequest.parseFrom(message.data))
            } catch (e: InvalidProtocolBufferException) {
                // Refuse out loud rather than let the parse throw out of the dispatcher.
                // A throw here publishes nothing, so the matchmaker waits out its whole
                // deadline and then reads a contract mismatch as a server that went quiet
                // — the one failure it has no way to tell apart from a lost message.
                logger.error("Unparseable StartMatch on {}", message.subject, e)
                StartMatchReply.newBuilder()
                    .setAccepted(false)
                    .setReason("malformed request")
                    .build()
            }
        connection.publish(replyTo, reply.toByteArray())
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
        dispatcher?.let { connection?.closeDispatcher(it) }
        dispatcher = null
        connection?.close()
        connection = null
    }

    private companion object {
        const val DEFAULT_NATS_URL = "nats://nats.nats.svc.cluster.local:4222"
        const val DEFAULT_TOKEN_FILE = "/var/run/secrets/grounds/token"
        const val DRIFT_INTERVAL_SECONDS = 60L
        /** Two agreeing readings, so a match in flight is never mistaken for a leak. */
        const val DRIFT_CONFIRMATIONS = 2
    }
}

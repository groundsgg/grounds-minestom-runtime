package gg.grounds.runtime.match

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import io.grpc.Server
import io.grpc.ServerBuilder
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
    private var server: Server? = null

    /** So the gamemode can call [MatchRegistry.finish] when a match ends. */
    lateinit var matches: MatchRegistry
        private set

    override fun install(ctx: GroundsServerContext) {
        matches = MatchRegistry(AgonesCounters())
        service = MatchHostService(matches, handler)
    }

    override fun start() {
        server = ServerBuilder.forPort(port).addService(service).build().start()
        logger.info("MatchHost gRPC server listening on port {}", port)
    }

    override fun stop() {
        server?.shutdown()
        server = null
    }
}

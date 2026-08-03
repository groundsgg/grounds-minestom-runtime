package gg.grounds.runtime.core.metrics

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.MetricsConfig
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.minestom.server.MinecraftServer
import net.minestom.server.event.server.ServerTickMonitorEvent
import org.slf4j.LoggerFactory

/**
 * A Prometheus endpoint on the game server itself.
 *
 * A region's Agones metrics say how many servers are Ready and how long an allocation took. They
 * say nothing about what happens inside one: a server that is Allocated and tick-locked at 300ms
 * looks, from outside, exactly like a healthy one. This is the inside view — the tick, the players
 * on it, and the JVM underneath.
 *
 * ## Why these numbers
 * - **Tick duration.** The one metric a Minecraft server is judged by. Minestom publishes it per
 *   tick via [ServerTickMonitorEvent], and a Timer turns that into `_count` (ticks, so the rate is
 *   TPS), `_sum` (so sum/count is mean MSPT) and `_max` (the spike, which is what a player feels
 *   and what an average hides).
 * - **Acquisition time.** How long the tick spent waiting to acquire threads before doing work. A
 *   rising tick time with flat acquisition is the game logic; both rising together is contention,
 *   and the two are indistinguishable from tick time alone.
 * - **Players, instances, entities, chunks.** The workload behind the tick. A minigame creates an
 *   instance per match and drops it after, so instances that only ever grow is a leak — the kind
 *   that ends as an OOM an hour later with nothing in the log.
 * - **JVM and process.** Micrometer's own binders, so the names (`jvm_memory_used_bytes`,
 *   `jvm_gc_pause_seconds`, `process_cpu_usage`) are the ones the service dashboards already read.
 *   A game server and a service then answer the same query.
 *
 * ## Why a plain JDK HTTP server
 *
 * `com.sun.net.httpserver` ships with the JVM. The alternative is a second framework on a process
 * whose whole job is to tick 20 times a second, for one endpoint that returns a string. It runs on
 * one daemon thread of its own, so a scrape never touches the tick thread.
 *
 * Exposed on a **separate port from Minecraft**, unauthenticated, and bound inside the pod: the
 * satellite's Alloy reaches it by pod IP, and a game server's port 25565 is the one thing players
 * can talk to — the two must not be the same socket.
 */
class RuntimeMetrics
private constructor(
    private val registry: PrometheusMeterRegistry,
    private val http: HttpServer,
    private val closeables: List<AutoCloseable>,
) : AutoCloseable {

    /**
     * The bound port. Equals the configured one unless that was 0, which asks for any free port.
     */
    val port: Int
        get() = http.address.port

    override fun close() {
        // Zero, not a grace period: this runs on the shutdown path of a process Kubernetes is
        // already tearing down, and a scrape in flight is worth nothing next to the delay.
        http.stop(0)
        closeables.forEach { closeable ->
            runCatching { closeable.close() }
                .onFailure { failure -> logger.warn("Failed to close a metrics binder", failure) }
        }
        registry.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RuntimeMetrics::class.java)

        /** What Prometheus expects a text-format body to be labelled as. */
        private const val CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"

        /**
         * Build the registry, bind the endpoint, and start collecting.
         *
         * Call after `MinecraftServer.init()`: the tick listener attaches to the global event
         * handler, which does not exist before it. The gauges are read lazily on scrape, so they
         * tolerate being registered before anything is running.
         */
        fun start(
            config: MetricsConfig,
            serverType: ServerType,
            environment: RuntimeEnvironment,
            snapshot: GameSnapshot = GameSnapshot.minestom(),
            attachTickListener: (Timer, Timer) -> Unit = ::attachMinestomTickListener,
        ): RuntimeMetrics {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            // Two labels on every series this process publishes. `cluster` and `pod` are stamped by
            // the satellite's agent, and `app` by the pod's Helm instance — what neither can say is
            // whether this is a lobby or a minigame, which is the split every panel groups by.
            registry
                .config()
                .commonTags(
                    Tags.of(
                        "server_type",
                        serverType.name.lowercase(),
                        "environment",
                        environment.name.lowercase(),
                    )
                )

            val closeables = bindJvmAndProcess(registry)
            bindGameGauges(registry, snapshot)
            attachTickListener(tickTimer(registry), acquisitionTimer(registry))

            val http = HttpServer.create(InetSocketAddress(config.host, config.port), 0)
            http.createContext("/") { exchange -> handle(exchange, config.path, registry) }
            http.executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "grounds-metrics").apply { isDaemon = true }
                }
            http.start()

            val metrics = RuntimeMetrics(registry, http, closeables)
            logger.info(
                "Metrics endpoint listening on http://{}:{}{}",
                config.host,
                metrics.port,
                config.path,
            )
            return metrics
        }

        private fun handle(
            exchange: HttpExchange,
            path: String,
            registry: PrometheusMeterRegistry,
        ) {
            exchange.use {
                if (exchange.requestURI.path != path) {
                    exchange.sendResponseHeaders(404, -1)
                    return@use
                }
                val body = registry.scrape().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", CONTENT_TYPE)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { stream -> stream.write(body) }
            }
        }

        /**
         * Micrometer's standard binders. `JvmGcMetrics` registers a notification listener that has
         * to be closed, which is why this returns them rather than dropping them on the floor — a
         * test that starts and stops a server would otherwise leak one per run.
         */
        private fun bindJvmAndProcess(registry: MeterRegistry): List<AutoCloseable> {
            val gc = JvmGcMetrics()
            val memory = JvmMemoryMetrics()
            val threads = JvmThreadMetrics()
            val classes = ClassLoaderMetrics()
            val processor = ProcessorMetrics()
            val uptime = UptimeMetrics()
            listOf(gc, memory, threads, classes, processor, uptime).forEach { binder ->
                binder.bindTo(registry)
            }
            return listOf(gc)
        }

        /**
         * `strongReference(true)` on every one of them, and it is not optional: Micrometer holds a
         * gauge's state object **weakly** by default, so a snapshot nobody else keeps alive is
         * collected and every game gauge starts reporting NaN — minutes or hours in, with the
         * endpoint still up and the JVM series still correct.
         */
        private fun bindGameGauges(registry: MeterRegistry, snapshot: GameSnapshot) {
            gauge(registry, "minecraft.players.online", "Players in the world", snapshot) {
                it.playersOnline().toDouble()
            }
            gauge(registry, "minecraft.instances", "Instances the server holds", snapshot) {
                it.instances().toDouble()
            }
            gauge(registry, "minecraft.entities", "Entities across every instance", snapshot) {
                it.entities().toDouble()
            }
            gauge(
                registry,
                "minecraft.chunks.loaded",
                "Chunks loaded across every instance",
                snapshot,
            ) {
                it.chunksLoaded().toDouble()
            }
        }

        private fun gauge(
            registry: MeterRegistry,
            name: String,
            description: String,
            snapshot: GameSnapshot,
            read: (GameSnapshot) -> Double,
        ) {
            Gauge.builder(name, snapshot, read)
                .description(description)
                .strongReference(true)
                .register(registry)
        }

        private fun tickTimer(registry: MeterRegistry): Timer =
            Timer.builder("minecraft.tick.duration")
                .description("Time the server spent computing one tick")
                .register(registry)

        private fun acquisitionTimer(registry: MeterRegistry): Timer =
            Timer.builder("minecraft.tick.acquisition")
                .description("Time one tick spent acquiring threads before doing work")
                .register(registry)

        /**
         * Minestom reports both times in **milliseconds as a double**; Micrometer takes a whole
         * number of some unit, so they are recorded in microseconds. Nanoseconds would overflow
         * nothing but buy precision no tick has, and milliseconds would round a 0.4ms tick to zero
         * — which is most of them on an idle server, and would read as "the server is not ticking".
         */
        private fun attachMinestomTickListener(tick: Timer, acquisition: Timer) {
            MinecraftServer.getGlobalEventHandler().addListener(
                ServerTickMonitorEvent::class.java
            ) { event ->
                val monitor = event.tickMonitor
                tick.record((monitor.tickTime * 1_000).toLong(), TimeUnit.MICROSECONDS)
                acquisition.record(
                    (monitor.acquisitionTime * 1_000).toLong(),
                    TimeUnit.MICROSECONDS,
                )
            }
        }
    }
}

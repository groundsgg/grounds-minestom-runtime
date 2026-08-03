package gg.grounds.runtime.core.metrics

import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.MetricsConfig
import io.micrometer.core.instrument.Timer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The endpoint is the contract: a name that changes here is a panel that goes blank on a dashboard
 * in another repo, and nothing in between would say so.
 *
 * Port 0 on every test — a fixed one makes the suite fail on whichever machine happens to be
 * running something else, which reads as a broken change.
 */
class RuntimeMetricsTest {

    private val snapshot =
        object : GameSnapshot {
            var players = 7
            var instanceCount = 3
            var entityCount = 42
            var chunks = 1_024

            override fun playersOnline(): Int = players

            override fun instances(): Int = instanceCount

            override fun entities(): Int = entityCount

            override fun chunksLoaded(): Int = chunks
        }

    private fun start(
        path: String = "/metrics",
        serverType: ServerType = ServerType.MINIGAME,
        onTick: (Timer, Timer) -> Unit = { _, _ -> },
    ): RuntimeMetrics =
        RuntimeMetrics.start(
            config = MetricsConfig(enabled = true, host = "127.0.0.1", port = 0, path = path),
            serverType = serverType,
            environment = RuntimeEnvironment.TEST,
            snapshot = snapshot,
            attachTickListener = onTick,
        )

    private fun scrape(metrics: RuntimeMetrics, path: String = "/metrics"): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${metrics.port}$path")).build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `publishes the game gauges with the server type as a label`() {
        start().use { metrics ->
            val body = scrape(metrics).body()

            assertHas(body, "minecraft_players_online")
            assertHas(body, "minecraft_instances")
            assertHas(body, "minecraft_entities")
            assertHas(body, "minecraft_chunks_loaded")
            assertHas(body, """server_type="minigame"""")
            assertHas(body, """environment="test"""")
        }
    }

    @Test
    fun `gauges read the game on every scrape rather than at registration`() {
        start().use { metrics ->
            assertTrue(scrape(metrics).body().lines().any { it.matches(playersAt(7.0)) })

            snapshot.players = 19
            assertTrue(scrape(metrics).body().lines().any { it.matches(playersAt(19.0)) })
        }
    }

    @Test
    fun `records the tick as a timer in seconds`() {
        var tickTimer: Timer? = null
        start(onTick = { tick, _ -> tickTimer = tick }).use { metrics ->
            // Minestom reports milliseconds; 50ms is one full tick's budget.
            tickTimer!!.record(50_000, TimeUnit.MICROSECONDS)

            val body = scrape(metrics).body()
            assertHas(body, "minecraft_tick_duration_seconds_count")
            assertHas(body, "minecraft_tick_acquisition_seconds_count")
            val sum =
                body
                    .lines()
                    .first { it.startsWith("minecraft_tick_duration_seconds_sum") }
                    .substringAfterLast(' ')
                    .toDouble()
            assertEquals(0.05, sum, 1e-6)
        }
    }

    @Test
    fun `publishes the JVM names the service dashboards already query`() {
        start().use { metrics ->
            val body = scrape(metrics).body()

            assertHas(body, "jvm_memory_used_bytes")
            assertHas(body, "jvm_threads_live_threads")
            assertHas(body, "process_cpu_usage")
            // The GC binder, by a meter it registers eagerly. `jvm_gc_pause_seconds` — the one the
            // service dashboards graph — only exists after the first collection, so asserting on it
            // here would pass or fail on whether this JVM happened to collect during the test.
            assertHas(body, "jvm_gc_memory_allocated_bytes_total")
        }
    }

    @Test
    fun `serves the configured path only`() {
        start(path = "/q/metrics").use { metrics ->
            assertEquals(200, scrape(metrics, "/q/metrics").statusCode())
            assertEquals(404, scrape(metrics, "/metrics").statusCode())
        }
    }

    @Test
    fun `stops answering once closed`() {
        val metrics = start()
        assertEquals(200, scrape(metrics).statusCode())
        metrics.close()

        val failure = runCatching { scrape(metrics) }
        assertTrue(failure.isFailure, "the endpoint still answered after close()")
    }

    private fun assertHas(body: String, needle: String) =
        assertTrue(body.contains(needle), "the endpoint published no `$needle`")

    private fun playersAt(value: Double) =
        Regex("""minecraft_players_online\{[^}]*} ${Regex.escape(value.toString())}""")
}

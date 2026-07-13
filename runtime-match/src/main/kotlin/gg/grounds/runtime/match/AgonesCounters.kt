package gg.grounds.runtime.match

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.slf4j.LoggerFactory

/**
 * Decrements the Agones `matches` Counter on this GameServer through the Agones SDK sidecar's local
 * REST API (injected into every GameServer pod).
 *
 * Allocation increments the counter — that's the matchmaker's job, done at allocation time. Only
 * the server itself knows when a hosted match actually ends, so decrementing is this class's whole
 * job.
 *
 * Never throws: a counter that fails to decrement just leaks one advertised slot, which is a cheap
 * problem. Taking down a server that is hosting other, live matches over a sidecar hiccup would be
 * a far more expensive one.
 */
class AgonesCounters(
    port: Int = System.getenv("AGONES_SDK_HTTP_PORT")?.toIntOrNull() ?: 9358,
    private val counterName: String = "matches",
) : MatchCounters {
    private val logger = LoggerFactory.getLogger(AgonesCounters::class.java)
    private val client = HttpClient.newHttpClient()
    private val endpoint = URI.create("http://localhost:$port/v1beta1/counters/$counterName")

    override fun decrement() {
        try {
            val request =
                HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .method(
                        "PATCH",
                        HttpRequest.BodyPublishers.ofString(
                            """{"name":"$counterName","countDiff":-1}"""
                        ),
                    )
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 200..299) {
                logger.warn(
                    "Agones counter decrement got HTTP {} from {}",
                    response.statusCode(),
                    endpoint,
                )
            }
        } catch (e: Exception) {
            logger.warn("Agones counter decrement failed", e)
        }
    }
}

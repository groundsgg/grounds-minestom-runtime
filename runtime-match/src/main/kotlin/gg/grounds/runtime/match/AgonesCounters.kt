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

    override fun decrement() = patch("""{"name":"$counterName","countDiff":-1}""", "decrement")

    override fun reconcile(live: Int) =
        patch("""{"name":"$counterName","count":$live}""", "reconcile to $live")

    private fun patch(body: String, what: String) {
        try {
            val request =
                HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 200..299) {
                logger.warn(
                    "Agones counter {} got HTTP {} from {}",
                    what,
                    response.statusCode(),
                    endpoint,
                )
            }
        } catch (e: Exception) {
            logger.warn("Agones counter {} failed", what, e)
        }
    }

    /** The counter's advertised count, or null if the sidecar will not say. */
    fun advertised(): Int? =
        try {
            val request = HttpRequest.newBuilder(endpoint).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) null
            else COUNT.find(response.body())?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            logger.warn("Agones counter read failed", e)
            null
        }

    private companion object {
        // The sidecar answers with the counter object; count is the only field this needs and a
        // JSON dependency is not worth carrying into every gameserver for one integer.
        val COUNT = Regex("\"count\"\\s*:\\s*\"?(\\d+)\"?")
    }
}

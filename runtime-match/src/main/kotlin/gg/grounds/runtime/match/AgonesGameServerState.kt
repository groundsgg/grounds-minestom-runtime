package gg.grounds.runtime.match

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory

/** [GameServerState] through the Agones SDK sidecar's local REST API. */
class AgonesGameServerState(
    port: Int = System.getenv("AGONES_SDK_HTTP_PORT")?.toIntOrNull() ?: 9358
) : GameServerState {
    private val logger = LoggerFactory.getLogger(AgonesGameServerState::class.java)
    private val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
    private val base = URI.create("http://localhost:$port")

    override fun ready(): Boolean = post("/ready")

    override fun allocate(): Boolean = post("/allocate")

    private fun post(path: String): Boolean =
        try {
            val request =
                HttpRequest.newBuilder(base.resolve(path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build()
            val status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status !in 200..299) logger.warn("Agones {} got HTTP {}", path, status)
            status in 200..299
        } catch (e: Exception) {
            logger.warn("Agones {} failed", path, e)
            false
        }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

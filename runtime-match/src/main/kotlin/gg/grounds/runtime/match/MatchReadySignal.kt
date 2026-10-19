package gg.grounds.runtime.match

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.slf4j.LoggerFactory

/** Tells the matchmaker a prepared round is ready, or failed. */
fun interface MatchReadySignal {
    /**
     * Returns whether the matchmaker is routing the players here. False means it gave the round up
     * (too late, players left) and the round should be discarded; null means it could not be told.
     */
    fun send(matchId: String, ok: Boolean, reason: String): Boolean?
}

/**
 * [MatchReadySignal] over service-match's REST API: `POST /v1/match/matches/{id}/ready`, with the
 * pod's ServiceAccount token as for the match result. A few quick retries, because a lost signal
 * keeps the players waiting until the matchmaker's timeout re-queues them.
 */
internal class HttpMatchReadySignal(
    private val serverName: String,
    target: String = System.getenv("MATCH_SERVICE_URL") ?: DEFAULT_TARGET,
    private val tokenFile: String? = System.getenv("GROUNDS_TOKEN_FILE") ?: DEFAULT_TOKEN_FILE,
    private val http: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
    private val attempts: Int = 3,
) : MatchReadySignal {
    private val logger = LoggerFactory.getLogger(HttpMatchReadySignal::class.java)
    private val baseUrl = if ("://" in target) target.trimEnd('/') else "http://$target"

    override fun send(matchId: String, ok: Boolean, reason: String): Boolean? {
        val body = """{"gameServerName":${json(serverName)},"ok":$ok,"reason":${json(reason)}}"""
        repeat(attempts) { attempt ->
            try {
                val request =
                    HttpRequest.newBuilder(URI.create("$baseUrl/v1/match/matches/$matchId/ready"))
                        .timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                token()?.let { request.header("Authorization", "Bearer $it") }
                val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    return ROUTED.find(response.body())?.groupValues?.get(1) == "true"
                }
                logger.warn(
                    "Ready signal for match {} answered {}: {}",
                    matchId,
                    response.statusCode(),
                    response.body().take(200),
                )
                // 4xx will not change on a retry.
                if (response.statusCode() in 400..499) return null
            } catch (e: Exception) {
                logger.warn(
                    "Ready signal for match {} failed (attempt {})",
                    matchId,
                    attempt + 1,
                    e,
                )
            }
            Thread.sleep(RETRY_DELAY_MILLIS * (attempt + 1))
        }
        return null
    }

    private fun token(): String? =
        tokenFile
            ?.let { runCatching { Files.readString(Path.of(it)).trim() }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val DEFAULT_TARGET = "service-match:9000"
        const val DEFAULT_TOKEN_FILE = "/var/run/secrets/grounds/token"
        const val RETRY_DELAY_MILLIS = 300L
        val ROUTED = Regex("\"routed\"\\s*:\\s*(true|false)")

        fun json(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
    }
}

package gg.grounds.runtime.match

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HttpMatchReadySignalTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val requests = mutableListOf<Triple<String, String, String?>>()
    private var status = 200
    private var answer = """{"routed":true}"""

    init {
        server.createContext("/") { exchange ->
            requests +=
                Triple(
                    exchange.requestURI.path,
                    String(exchange.requestBody.readAllBytes()),
                    exchange.requestHeaders.getFirst("Authorization"),
                )
            val body = answer.toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun signal(): HttpMatchReadySignal {
        val token = Files.createTempFile("token", "").also { Files.writeString(it, "tok\n") }
        return HttpMatchReadySignal(
            "survivalgames-solo-abc",
            target = "127.0.0.1:${server.address.port}",
            tokenFile = token.toString(),
            attempts = 2,
        )
    }

    @Test
    fun `posts the outcome with the pod's token and reads whether the players come`() {
        val routed = signal().send("m1", true, "")

        assertEquals(true, routed)
        val (path, body, auth) = requests.single()
        assertEquals("/v1/match/matches/m1/ready", path)
        assertEquals("""{"gameServerName":"survivalgames-solo-abc","ok":true,"reason":""}""", body)
        assertEquals("Bearer tok", auth)
    }

    @Test
    fun `a round the matchmaker gave up on is not routed`() {
        answer = """{"routed": false}"""

        assertEquals(false, signal().send("m1", true, ""))
    }

    @Test
    fun `a refusal is not retried`() {
        status = 409
        answer = """{"error":"another server"}"""

        assertNull(signal().send("m1", false, "map \"x\" unreadable"))
        assertEquals(1, requests.size)
        assertEquals(
            """{"gameServerName":"survivalgames-solo-abc","ok":false,"reason":"map \"x\" unreadable"}""",
            requests.single().second,
        )
    }
}

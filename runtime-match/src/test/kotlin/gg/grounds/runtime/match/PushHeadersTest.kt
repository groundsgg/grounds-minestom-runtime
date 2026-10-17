package gg.grounds.runtime.match

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PushHeadersTest {
    private val alice = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @Test
    fun `parses what service-match sends`() {
        val headers =
            PushHeaders.parse(
                traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                enqueuedAt = "$alice=1760600000000,$bob=1760600001000",
                formedAt = "1760600005000",
            )

        assertEquals(
            PushHeaders(
                traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                enqueuedAt = mapOf(alice to 1760600000000, bob to 1760600001000),
                formedAt = 1760600005000,
            ),
            headers,
        )
    }

    @Test
    fun `absent headers are empty, not a refusal`() {
        assertEquals(PushHeaders.NONE, PushHeaders.parse(null, null, null))
        assertEquals(PushHeaders.NONE, PushHeaders.parse(" ", "", ""))
    }

    @Test
    fun `malformed values are dropped one by one`() {
        val headers =
            PushHeaders.parse(
                traceparent = null,
                enqueuedAt = "not-a-uuid=1,$alice=soon,$bob=1760600001000,=,$alice,$alice=1=2,",
                formedAt = "yesterday",
            )

        assertEquals(mapOf(bob to 1760600001000), headers.enqueuedAt)
        assertEquals(null, headers.formedAt)
    }
}

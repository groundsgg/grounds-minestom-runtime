package gg.grounds.runtime.core

import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GroundsServerTest {
    @Test
    fun `runtime config parses environment`() {
        val config =
            RuntimeConfig.fromEnvironment(
                mapOf(
                    "GROUNDS_SERVER_TYPE" to "lobby",
                    "GROUNDS_ENV" to "test",
                    "GROUNDS_BIND_HOST" to "127.0.0.1",
                    "GROUNDS_BIND_PORT" to "25566",
                )
            )

        assertEquals(ServerType.LOBBY, config.serverType)
        assertEquals(RuntimeEnvironment.TEST, config.environment)
        assertEquals("127.0.0.1", config.host)
        assertEquals(25566, config.port)
    }
}

package gg.grounds.runtime.core

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.GroundsServerContext
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

    @Test
    fun `builder uses direct modules and module providers`() {
        val server =
            GroundsServer.builder()
                .config(testConfig())
                .use(testModule("grounds.direct"))
                .use(testProvider("grounds.provider"))
                .build()

        assertEquals(listOf("grounds.provider", "grounds.direct"), server.installedModuleIds())
    }

    @Test
    fun `builder uses only selected discovered providers`() {
        val server =
            GroundsServer.builder()
                .config(testConfig())
                .discoverProviders()
                .useProvider("grounds.selected-discovered")
                .build()

        assertEquals(listOf("grounds.selected-discovered"), server.installedModuleIds())
    }

    private fun testConfig(): RuntimeConfig =
        RuntimeConfig(serverType = ServerType.MINIGAME, environment = RuntimeEnvironment.TEST)

    private fun testModule(id: String): GroundsModule =
        object : GroundsModule {
            override val id: String = id

            override fun install(ctx: GroundsServerContext) = Unit
        }

    private fun testProvider(id: String): GroundsModuleProvider =
        object : GroundsModuleProvider {
            override val id: String = id
            override val version: String = "local"
            override val serverTypes: Set<ServerType> = setOf(ServerType.MINIGAME)

            override fun create(): GroundsModule = testModule(id)
        }

    private fun GroundsServer.installedModuleIds(): List<String> {
        val modulesField = GroundsServer::class.java.getDeclaredField("modules")
        modulesField.isAccessible = true
        val modules = modulesField.get(this) as List<*>

        return modules.map { installed ->
            val idField = checkNotNull(installed).javaClass.getDeclaredField("id")
            idField.isAccessible = true
            idField.get(installed) as String
        }
    }
}

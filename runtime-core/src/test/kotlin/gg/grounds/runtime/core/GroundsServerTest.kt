package gg.grounds.runtime.core

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import net.minestom.server.MinecraftServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GroundsServerTest {
    @Test
    fun `runtime config parses environment`() {
        val config =
            RuntimeConfig.fromEnvironment(
                RuntimeEnv.of(
                    mapOf(
                        "GROUNDS_SERVER_TYPE" to "lobby",
                        "GROUNDS_ENV" to "test",
                        "GROUNDS_BIND_HOST" to "127.0.0.1",
                        "GROUNDS_BIND_PORT" to "25566",
                        "GROUNDS_SERVER_BRAND" to "Grounds Test",
                    )
                )
            )

        assertEquals(ServerType.LOBBY, config.serverType)
        assertEquals(RuntimeEnvironment.TEST, config.environment)
        assertEquals("127.0.0.1", config.host)
        assertEquals(25566, config.port)
        assertEquals("Grounds Test", config.serverBrand)
    }

    @Test
    fun `runtime config defaults server brand to Grounds`() {
        val config = RuntimeConfig.fromEnvironment(RuntimeEnv.of(emptyMap()))

        assertEquals("Grounds", config.serverBrand)
    }

    @Test
    fun `runtime config rejects invalid bind port`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeConfig.fromEnvironment(RuntimeEnv.of(mapOf("GROUNDS_BIND_PORT" to "abc")))
            }

        assertEquals("unsupported GROUNDS_BIND_PORT: abc", error.message)
    }

    @Test
    fun `runtime applies configured server brand to Minestom`() {
        MinecraftServer.init()
        val previousBrand = MinecraftServer.getBrandName()
        try {
            applyRuntimeBrand(testConfig(serverBrand = "Grounds Test"))

            assertEquals("Grounds Test", MinecraftServer.getBrandName())
        } finally {
            MinecraftServer.setBrandName(previousBrand)
            MinecraftServer.stopCleanly()
        }
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

    private fun testConfig(serverBrand: String = "Grounds"): RuntimeConfig =
        RuntimeConfig(
            serverType = ServerType.MINIGAME,
            environment = RuntimeEnvironment.TEST,
            serverBrand = serverBrand,
        )

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

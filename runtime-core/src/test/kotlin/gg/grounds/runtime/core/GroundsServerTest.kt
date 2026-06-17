package gg.grounds.runtime.core

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import net.minestom.server.Auth
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
                        "GROUNDS_ONLINE_MODE" to "false",
                    )
                )
            )

        assertEquals(ServerType.LOBBY, config.serverType)
        assertEquals(RuntimeEnvironment.TEST, config.environment)
        assertEquals("127.0.0.1", config.host)
        assertEquals(25566, config.port)
        assertEquals("Grounds Test", config.serverBrand)
        assertEquals(false, config.onlineMode)
    }

    @Test
    fun `runtime config parses explicit velocity proxy mode`() {
        val config =
            RuntimeConfig.fromEnvironment(
                RuntimeEnv.of(
                    mapOf(
                        "GROUNDS_PROXY_MODE" to "velocity",
                        "GROUNDS_VELOCITY_FORWARDING_SECRET" to "secret-value",
                    )
                )
            )

        assertEquals(ProxyMode.VELOCITY, config.proxy.mode)
        assertEquals("secret-value", config.proxy.velocityForwardingSecret)
    }

    @Test
    fun `runtime config reads legacy velocity forwarding secret aliases`() {
        val config =
            RuntimeConfig.fromEnvironment(
                RuntimeEnv.of(mapOf("GROUNDS_LOBBY_VELOCITY_SECRET" to "legacy-secret"))
            )

        assertEquals(ProxyMode.AUTO, config.proxy.mode)
        assertEquals("legacy-secret", config.proxy.velocityForwardingSecret)
    }

    @Test
    fun `runtime config rejects forced velocity mode without forwarding secret`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeConfig.fromEnvironment(
                    RuntimeEnv.of(mapOf("GROUNDS_PROXY_MODE" to "velocity"))
                )
            }

        assertEquals(
            "GROUNDS_PROXY_MODE=velocity requires one of GROUNDS_VELOCITY_FORWARDING_SECRET, VELOCITY_FORWARDING_SECRET, GROUNDS_LOBBY_VELOCITY_SECRET, PAPER_VELOCITY_SECRET",
            error.message,
        )
    }

    @Test
    fun `runtime config defaults online mode to true`() {
        val config = RuntimeConfig.fromEnvironment(RuntimeEnv.of(emptyMap()))

        assertEquals(true, config.onlineMode)
    }

    @Test
    fun `runtime config rejects invalid online mode`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeConfig.fromEnvironment(
                    RuntimeEnv.of(mapOf("GROUNDS_ONLINE_MODE" to "sometimes"))
                )
            }

        assertEquals("unsupported GROUNDS_ONLINE_MODE: sometimes", error.message)
    }

    @Test
    fun `runtime auth uses online mode when proxy mode is auto without secret`() {
        val auth = createRuntimeAuth(testConfig(proxy = ProxyConfig(mode = ProxyMode.AUTO)))

        assertEquals(Auth.Online::class.java, auth.javaClass)
    }

    @Test
    fun `runtime auth uses offline mode when online mode is disabled`() {
        val auth =
            createRuntimeAuth(
                testConfig(onlineMode = false, proxy = ProxyConfig(mode = ProxyMode.AUTO))
            )

        assertEquals(Auth.Offline::class.java, auth.javaClass)
    }

    @Test
    fun `runtime auth uses velocity mode when proxy mode is auto with forwarding secret`() {
        val auth =
            createRuntimeAuth(
                testConfig(
                    proxy =
                        ProxyConfig(
                            mode = ProxyMode.AUTO,
                            velocityForwardingSecret = "secret-value",
                        )
                )
            )

        assertEquals(Auth.Velocity::class.java, auth.javaClass)
    }

    @Test
    fun `runtime auth uses velocity mode when proxy mode is forced`() {
        val auth =
            createRuntimeAuth(
                testConfig(
                    proxy =
                        ProxyConfig(
                            mode = ProxyMode.VELOCITY,
                            velocityForwardingSecret = "secret-value",
                        )
                )
            )

        assertEquals(Auth.Velocity::class.java, auth.javaClass)
    }

    @Test
    fun `runtime auth uses offline mode when proxy mode is forced offline`() {
        val auth =
            createRuntimeAuth(
                testConfig(
                    proxy =
                        ProxyConfig(
                            mode = ProxyMode.OFFLINE,
                            velocityForwardingSecret = "secret-value",
                        )
                )
            )

        assertEquals(Auth.Offline::class.java, auth.javaClass)
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

    private fun testConfig(
        serverBrand: String = "Grounds",
        onlineMode: Boolean = true,
        proxy: ProxyConfig = ProxyConfig(),
    ): RuntimeConfig =
        RuntimeConfig(
            serverType = ServerType.MINIGAME,
            environment = RuntimeEnvironment.TEST,
            serverBrand = serverBrand,
            onlineMode = onlineMode,
            proxy = proxy,
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

package gg.grounds.runtime.core

import gg.grounds.modules.ModuleDescriptor
import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.register
import gg.grounds.modules.require
import gg.grounds.modules.serviceKey
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GroundsModuleComposerTest {
    @Test
    fun `orders provider modules by service requirements and shares services`() {
        val events = mutableListOf<String>()
        val serviceProvider =
            provider(
                descriptor =
                    ModuleDescriptor(
                        id = "grounds.test-service",
                        version = "1.0.0",
                        provides = setOf(serviceKey<TestService>()),
                    ),
                module =
                    module("legacy.service") { ctx ->
                        ctx.services.register<TestService>(DefaultTestService("ready"))
                        events.add("service")
                    },
            )
        val consumerProvider =
            provider(
                descriptor =
                    ModuleDescriptor(
                        id = "grounds.consumer",
                        version = "1.0.0",
                        requires = setOf(serviceKey<TestService>()),
                    ),
                module =
                    module("legacy.consumer") { ctx ->
                        events.add("consumer:${ctx.services.require<TestService>().value}")
                    },
            )

        val composition =
            GroundsModuleComposer.compose(
                config = minigameConfig(),
                modules = emptyList(),
                providers = listOf(consumerProvider, serviceProvider),
            )

        assertEquals(
            listOf("grounds.test-service", "grounds.consumer"),
            composition.modules.map { it.id },
        )

        val context = testContext(composition.services)
        composition.modules.forEach { it.module.install(context) }

        assertEquals(listOf("service", "consumer:ready"), events)
    }

    @Test
    fun `filters providers by server type before graph validation`() {
        val lobbyOnlyProvider =
            provider(
                descriptor =
                    ModuleDescriptor(
                        id = "grounds.lobby-only",
                        version = "1.0.0",
                        requires = setOf(serviceKey<TestService>()),
                    ),
                serverTypes = setOf(ServerType.LOBBY),
                module = module("grounds.lobby-only"),
            )

        val composition =
            GroundsModuleComposer.compose(
                config = minigameConfig(),
                modules = emptyList(),
                providers = listOf(lobbyOnlyProvider),
            )

        assertEquals(emptyList<String>(), composition.modules.map { it.id })
    }

    @Test
    fun `fails when required services are not provided`() {
        val provider =
            provider(
                descriptor =
                    ModuleDescriptor(
                        id = "grounds.consumer",
                        version = "1.0.0",
                        requires = setOf(serviceKey<TestService>()),
                    ),
                module = module("grounds.consumer"),
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                GroundsModuleComposer.compose(
                    config = minigameConfig(),
                    modules = emptyList(),
                    providers = listOf(provider),
                )
            }

        assertEquals(
            "missing required services: grounds.consumer -> gg.grounds.runtime.core.GroundsModuleComposerTest.TestService",
            error.message,
        )
    }

    private fun minigameConfig(): RuntimeConfig =
        RuntimeConfig(serverType = ServerType.MINIGAME, environment = RuntimeEnvironment.TEST)

    private fun provider(
        descriptor: ModuleDescriptor,
        serverTypes: Set<ServerType> = setOf(ServerType.MINIGAME),
        module: GroundsModule,
    ): GroundsModuleProvider =
        object : GroundsModuleProvider {
            override val id: String = descriptor.id
            override val version: String = descriptor.version
            override val serverTypes: Set<ServerType> = serverTypes
            override val descriptor: ModuleDescriptor = descriptor

            override fun create(): GroundsModule = module
        }

    private fun module(id: String, install: (GroundsServerContext) -> Unit = {}): GroundsModule =
        object : GroundsModule {
            override val id: String = id

            override fun install(ctx: GroundsServerContext) {
                install(ctx)
            }
        }

    private fun testContext(services: ServiceRegistry): GroundsServerContext =
        object : GroundsServerContext {
            override val serverType: ServerType = ServerType.MINIGAME
            override val environment: RuntimeEnvironment = RuntimeEnvironment.TEST
            override val services: ServiceRegistry = services

            override fun eventNode(name: String): EventNode<Event> = EventNode.all(name)

            override fun onShutdown(action: () -> Unit) = Unit
        }

    private interface TestService {
        val value: String
    }

    private class DefaultTestService(override val value: String) : TestService
}

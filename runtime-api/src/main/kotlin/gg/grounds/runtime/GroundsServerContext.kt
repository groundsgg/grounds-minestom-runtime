package gg.grounds.runtime

import gg.grounds.modules.ServiceRegistry
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode

data class ActiveGroundsModuleProvider(
    val id: String,
    val version: String,
    val classLoader: ClassLoader,
)

interface GroundsServerContext {
    val serverType: ServerType
    val environment: RuntimeEnvironment
    val services: ServiceRegistry
    val activeModuleProviders: List<ActiveGroundsModuleProvider>
        get() = emptyList()

    fun eventNode(name: String): EventNode<Event>

    fun onShutdown(action: () -> Unit)
}

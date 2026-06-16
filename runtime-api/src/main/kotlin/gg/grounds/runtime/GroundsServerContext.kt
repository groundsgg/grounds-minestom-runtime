package gg.grounds.runtime

import gg.grounds.modules.ServiceRegistry
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode

interface GroundsServerContext {
    val serverType: ServerType
    val environment: RuntimeEnvironment
    val services: ServiceRegistry

    fun eventNode(name: String): EventNode<Event>

    fun onShutdown(action: () -> Unit)
}

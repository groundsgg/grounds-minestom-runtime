package gg.grounds.runtime

import net.minestom.server.event.Event
import net.minestom.server.event.EventNode

interface GroundsServerContext {
    val serverType: ServerType
    val environment: RuntimeEnvironment

    fun eventNode(name: String): EventNode<Event>

    fun onShutdown(action: () -> Unit)
}

package gg.grounds.runtime.core

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.ServerType

class SelectedDiscoveredModuleProvider : GroundsModuleProvider {
    override val id: String = "grounds.selected-discovered"
    override val version: String = "local"
    override val serverTypes: Set<ServerType> = setOf(ServerType.MINIGAME)

    override fun create(): GroundsModule = discoveredModule(id)
}

class IgnoredDiscoveredModuleProvider : GroundsModuleProvider {
    override val id: String = "grounds.ignored-discovered"
    override val version: String = "local"
    override val serverTypes: Set<ServerType> = setOf(ServerType.MINIGAME)

    override fun create(): GroundsModule = discoveredModule(id)
}

private fun discoveredModule(id: String): GroundsModule =
    object : GroundsModule {
        override val id: String = id

        override fun install(ctx: GroundsServerContext) = Unit
    }

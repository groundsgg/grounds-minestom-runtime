package gg.grounds.runtime

import gg.grounds.modules.ModuleDescriptor
import gg.grounds.modules.ModuleProvider

interface GroundsModuleProvider : ModuleProvider<GroundsModule> {
    val id: String
    val version: String
    val serverTypes: Set<ServerType>
    override val descriptor: ModuleDescriptor
        get() = ModuleDescriptor(id = id, version = version)

    override fun create(): GroundsModule
}

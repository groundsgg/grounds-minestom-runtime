package gg.grounds.runtime

interface GroundsModuleProvider {
    val id: String
    val version: String
    val serverTypes: Set<ServerType>

    fun create(): GroundsModule
}

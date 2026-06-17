package gg.grounds.runtime.testkit

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.GroundsServer
import gg.grounds.runtime.core.RuntimeConfig

object TestGroundsServer {
    fun minigame(vararg modules: GroundsModule): GroundsServer {
        val builder = minigameBuilder()
        modules.forEach(builder::use)
        return builder.build()
    }

    fun minigameWithProviders(vararg providers: GroundsModuleProvider): GroundsServer {
        val builder = minigameBuilder()
        providers.forEach(builder::use)
        return builder.build()
    }

    private fun minigameBuilder(): GroundsServer.Builder {
        val builder =
            GroundsServer.builder()
                .config(
                    RuntimeConfig(
                        serverType = ServerType.MINIGAME,
                        environment = RuntimeEnvironment.TEST,
                    )
                )
        return builder
    }
}

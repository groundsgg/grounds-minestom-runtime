package gg.grounds.runtime.testkit

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.GroundsServer
import gg.grounds.runtime.core.RuntimeConfig

object TestGroundsServer {
    fun minigame(vararg modules: GroundsModule): GroundsServer {
        val builder =
            GroundsServer.builder()
                .config(
                    RuntimeConfig(
                        serverType = ServerType.MINIGAME,
                        environment = RuntimeEnvironment.TEST,
                    )
                )
        modules.forEach(builder::install)
        return builder.build()
    }
}

package gg.grounds.runtime.example

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.core.GroundsServer
import org.slf4j.LoggerFactory

private class ExampleMinigameModule : GroundsModule {
    private val logger = LoggerFactory.getLogger(ExampleMinigameModule::class.java)

    override val id: String = "grounds.example-minigame"

    override fun install(ctx: GroundsServerContext) {
        logger.info(
            "Installed example minigame module (serverType={}, env={})",
            ctx.serverType,
            ctx.environment,
        )
    }
}

fun main() {
    GroundsServer.builder().install(ExampleMinigameModule()).start()
}

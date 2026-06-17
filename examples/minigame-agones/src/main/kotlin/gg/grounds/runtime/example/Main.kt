package gg.grounds.runtime.example

import gg.grounds.modules.ModuleDescriptor
import gg.grounds.modules.register
import gg.grounds.modules.require
import gg.grounds.modules.serviceKey
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.ServerType
import gg.grounds.runtime.core.GroundsServer
import org.slf4j.LoggerFactory

private interface ExampleStatusService {
    fun status(): String
}

private class DefaultExampleStatusService : ExampleStatusService {
    override fun status(): String = "ready"
}

private class ExampleMinigameModuleProvider : GroundsModuleProvider {
    override val id: String = "grounds.example-minigame"
    override val version: String = "local"
    override val serverTypes: Set<ServerType> = setOf(ServerType.MINIGAME)
    override val descriptor: ModuleDescriptor =
        ModuleDescriptor(
            id = id,
            version = version,
            provides = setOf(serviceKey<ExampleStatusService>()),
        )

    override fun create(): GroundsModule = ExampleMinigameModule()
}

private class ExampleMinigameModule : GroundsModule {
    private val logger = LoggerFactory.getLogger(ExampleMinigameModule::class.java)

    override val id: String = "grounds.example-minigame"

    override fun install(ctx: GroundsServerContext) {
        ctx.services.register<ExampleStatusService>(DefaultExampleStatusService())
        logger.info(
            "Installed example minigame module (serverType={}, env={}, status={})",
            ctx.serverType,
            ctx.environment,
            ctx.services.require<ExampleStatusService>().status(),
        )
    }
}

fun main() {
    buildExampleServer().start()
}

internal fun buildExampleServer(): GroundsServer =
    GroundsServer.builder()
        .discoverProviders()
        .useProvider("grounds.agones")
        .use(ExampleMinigameModuleProvider())
        .build()

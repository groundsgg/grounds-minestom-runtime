package gg.grounds.runtime.core

import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.slf4j.LoggerFactory

class GroundsServer
private constructor(private val config: RuntimeConfig, modules: List<GroundsModule>) {
    private val logger = LoggerFactory.getLogger(GroundsServer::class.java)
    private val modules = modules.toList()
    private val shutdownHooks = mutableListOf<() -> Unit>()
    private var started = false

    fun start() {
        check(!started) { "server is already started" }
        started = true

        val minecraftServer = MinecraftServer.init()
        val context = DefaultGroundsServerContext(config, shutdownHooks)

        modules.forEach { module ->
            logger.info("Installing Grounds module {}", module.id)
            module.install(context)
        }

        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        minecraftServer.start(config.host, config.port)

        modules.forEach { module ->
            logger.info("Starting Grounds module {}", module.id)
            module.start()
        }
    }

    fun stop() {
        if (!started) return
        started = false

        modules.asReversed().forEach { module ->
            logger.info("Stopping Grounds module {}", module.id)
            module.stop()
        }
        shutdownHooks.asReversed().forEach { it.invoke() }
        MinecraftServer.stopCleanly()
    }

    private class DefaultGroundsServerContext(
        private val config: RuntimeConfig,
        private val shutdownHooks: MutableList<() -> Unit>,
    ) : GroundsServerContext {
        override val serverType: ServerType = config.serverType
        override val environment: RuntimeEnvironment = config.environment

        override fun eventNode(name: String): EventNode<Event> = EventNode.all(name)

        override fun onShutdown(action: () -> Unit) {
            shutdownHooks.add(action)
        }
    }

    class Builder {
        private var config: RuntimeConfig = RuntimeConfig.fromEnvironment()
        private val modules = mutableListOf<GroundsModule>()

        fun config(config: RuntimeConfig): Builder = apply { this.config = config }

        fun install(module: GroundsModule): Builder = apply { modules.add(module) }

        fun build(): GroundsServer = GroundsServer(config, modules)

        fun start(): GroundsServer {
            return build().also { it.start() }
        }
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

package gg.grounds.runtime.core

import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.core.ServiceLoaderModuleDiscovery
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider
import gg.grounds.runtime.GroundsServerContext
import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType
import net.minestom.server.MinecraftServer
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.slf4j.LoggerFactory

class GroundsServer
private constructor(private val config: RuntimeConfig, composition: GroundsModuleComposition) {
    private val logger = LoggerFactory.getLogger(GroundsServer::class.java)
    private val modules = composition.modules
    private val services = composition.services
    private val shutdownHooks = mutableListOf<() -> Unit>()
    private var started = false

    fun start() {
        check(!started) { "server is already started" }
        started = true

        val minecraftServer = MinecraftServer.init()
        val context = DefaultGroundsServerContext(config, services, shutdownHooks)

        modules.forEach { installed ->
            logger.info("Installing Grounds module {}", installed.id)
            installed.module.install(context)
        }

        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        minecraftServer.start(config.host, config.port)

        modules.forEach { installed ->
            logger.info("Starting Grounds module {}", installed.id)
            installed.module.start()
        }
    }

    fun stop() {
        if (!started) return
        started = false

        modules.asReversed().forEach { installed ->
            logger.info("Stopping Grounds module {}", installed.id)
            installed.module.stop()
        }
        shutdownHooks.asReversed().forEach { it.invoke() }
        MinecraftServer.stopCleanly()
    }

    private class DefaultGroundsServerContext(
        private val config: RuntimeConfig,
        override val services: ServiceRegistry,
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
        private val providers = mutableListOf<GroundsModuleProvider>()

        fun config(config: RuntimeConfig): Builder = apply { this.config = config }

        fun install(module: GroundsModule): Builder = apply { modules.add(module) }

        fun install(provider: GroundsModuleProvider): Builder = apply { providers.add(provider) }

        fun discoverProviders(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader
        ): Builder = apply {
            providers.addAll(
                ServiceLoaderModuleDiscovery.discover(
                    providerType = GroundsModuleProvider::class.java,
                    classLoader = classLoader,
                )
            )
        }

        fun build(): GroundsServer =
            GroundsServer(config, GroundsModuleComposer.compose(config, modules, providers))

        fun start(): GroundsServer {
            return build().also { it.start() }
        }
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

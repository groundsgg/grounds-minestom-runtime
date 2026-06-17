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

        logger.info(
            "Starting Grounds server (serverType={}, environment={}, bind={}:{}, brand={}, moduleCount={})",
            config.serverType,
            config.environment,
            config.host,
            config.port,
            config.serverBrand,
            modules.size,
        )
        logger.info("Activated Grounds modules: {}", modules.joinToString { it.id })

        val minecraftServer = MinecraftServer.init()
        applyRuntimeBrand(config)
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
        private val logger = LoggerFactory.getLogger(Builder::class.java)
        private var config: RuntimeConfig = RuntimeConfig.fromEnvironment()
        private val modules = mutableListOf<GroundsModule>()
        private val providers = mutableListOf<GroundsModuleProvider>()
        private val discoveredProviders = mutableListOf<GroundsModuleProvider>()
        private val selectedDiscoveredProviderIds = mutableListOf<String>()

        fun config(config: RuntimeConfig): Builder = apply { this.config = config }

        fun use(module: GroundsModule): Builder = apply {
            logger.info("Using Grounds module {}", module.id)
            modules.add(module)
        }

        fun use(provider: GroundsModuleProvider): Builder = apply {
            logger.info(
                "Using Grounds module provider {} (version={}, serverTypes={})",
                provider.id,
                provider.version,
                provider.serverTypes.joinToString(),
            )
            providers.add(provider)
        }

        fun useProvider(id: String): Builder = apply {
            logger.info("Using discovered Grounds module provider {}", id)
            selectedDiscoveredProviderIds.add(id)
        }

        fun discoverProviders(
            classLoader: ClassLoader = Thread.currentThread().contextClassLoader
        ): Builder = apply {
            val discovered =
                ServiceLoaderModuleDiscovery.discover(
                    providerType = GroundsModuleProvider::class.java,
                    classLoader = classLoader,
                )
            if (discovered.isEmpty()) {
                logger.info("Discovered Grounds module providers: none")
            } else {
                logger.info(
                    "Discovered Grounds module providers: {}",
                    discovered.joinToString { provider -> "${provider.id}@${provider.version}" },
                )
            }
            discoveredProviders.addAll(discovered)
        }

        fun build(): GroundsServer {
            val selectedDiscoveredProviders = resolveSelectedDiscoveredProviders()
            val activeProviders = providers + selectedDiscoveredProviders
            logger.info(
                "Building Grounds server (serverType={}, environment={}, directModules={}, providers={}, discoveredProviderSelections={})",
                config.serverType,
                config.environment,
                modules.joinToString { it.id }.ifEmpty { "none" },
                activeProviders.joinToString { it.id }.ifEmpty { "none" },
                selectedDiscoveredProviderIds.joinToString().ifEmpty { "none" },
            )
            return GroundsServer(
                config,
                GroundsModuleComposer.compose(config, modules, activeProviders),
            )
        }

        private fun resolveSelectedDiscoveredProviders(): List<GroundsModuleProvider> =
            selectedDiscoveredProviderIds.map { id ->
                val matches = discoveredProviders.filter { provider -> provider.id == id }
                when (matches.size) {
                    0 ->
                        error(
                            "Grounds module provider $id was requested but not discovered. " +
                                "Available providers: ${discoveredProviders.joinToString { it.id }.ifEmpty { "none" }}"
                        )
                    1 -> matches.single()
                    else ->
                        error(
                            "Grounds module provider $id is ambiguous; ${matches.size} providers were discovered"
                        )
                }
            }

        fun start(): GroundsServer {
            return build().also { it.start() }
        }
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

internal fun applyRuntimeBrand(config: RuntimeConfig) {
    MinecraftServer.setBrandName(config.serverBrand)
}

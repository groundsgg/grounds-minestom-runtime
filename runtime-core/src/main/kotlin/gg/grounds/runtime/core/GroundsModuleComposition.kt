package gg.grounds.runtime.core

import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.core.DefaultServiceRegistry
import gg.grounds.modules.core.ModuleGraphValidator
import gg.grounds.runtime.ActiveGroundsModuleProvider
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider

internal data class GroundsModuleComposition(
    val modules: List<InstalledGroundsModule>,
    val services: ServiceRegistry,
    val activeModuleProviders: List<ActiveGroundsModuleProvider>,
)

internal data class InstalledGroundsModule(val id: String, val module: GroundsModule)

internal object GroundsModuleComposer {
    fun compose(
        config: RuntimeConfig,
        modules: List<GroundsModule>,
        providers: List<GroundsModuleProvider>,
        services: ServiceRegistry = DefaultServiceRegistry(),
    ): GroundsModuleComposition {
        val matchingProviders = providers.filter { config.serverType in it.serverTypes }
        val providerModules = composeProviderModules(matchingProviders, services)
        val directModules = modules.map { module -> InstalledGroundsModule(module.id, module) }
        val activeModuleProviders =
            matchingProviders.map { provider ->
                ActiveGroundsModuleProvider(
                    id = provider.id,
                    version = provider.version,
                    classLoader = requireNotNull(provider.javaClass.classLoader),
                )
            }

        return GroundsModuleComposition(
            providerModules + directModules,
            services,
            activeModuleProviders,
        )
    }

    private fun composeProviderModules(
        matchingProviders: List<GroundsModuleProvider>,
        services: ServiceRegistry,
    ): List<InstalledGroundsModule> {
        if (matchingProviders.isEmpty()) {
            return emptyList()
        }

        val descriptors = matchingProviders.map { it.descriptor }
        val serviceProviderIds =
            descriptors
                .flatMap { descriptor ->
                    descriptor.provides.map { service -> service to descriptor.id }
                }
                .toMap()
        val descriptorsWithServiceDependencies =
            descriptors.map { descriptor ->
                val serviceDependencies =
                    descriptor.requires
                        .mapNotNull(serviceProviderIds::get)
                        .filterNot { providerId -> providerId == descriptor.id }
                        .toSet()
                descriptor.copy(dependsOn = descriptor.dependsOn + serviceDependencies)
            }
        val graph =
            ModuleGraphValidator.validate(
                descriptors = descriptorsWithServiceDependencies,
                availableServices = services.keys(),
            )
        val providersById = matchingProviders.associateBy { it.descriptor.id }

        return graph.descriptors.map { descriptor ->
            val provider = providersById.getValue(descriptor.id)
            InstalledGroundsModule(id = descriptor.id, module = provider.create())
        }
    }
}

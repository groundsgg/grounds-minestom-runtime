package gg.grounds.runtime.core

import gg.grounds.modules.ServiceRegistry
import gg.grounds.modules.core.DefaultServiceRegistry
import gg.grounds.modules.core.ModuleGraphValidator
import gg.grounds.runtime.GroundsModule
import gg.grounds.runtime.GroundsModuleProvider

internal data class GroundsModuleComposition(
    val modules: List<InstalledGroundsModule>,
    val services: ServiceRegistry,
)

internal data class InstalledGroundsModule(val id: String, val module: GroundsModule)

internal object GroundsModuleComposer {
    fun compose(
        config: RuntimeConfig,
        modules: List<GroundsModule>,
        providers: List<GroundsModuleProvider>,
        services: ServiceRegistry = DefaultServiceRegistry(),
    ): GroundsModuleComposition {
        val providerModules = composeProviderModules(config, providers, services)
        val directModules = modules.map { module -> InstalledGroundsModule(module.id, module) }

        return GroundsModuleComposition(providerModules + directModules, services)
    }

    private fun composeProviderModules(
        config: RuntimeConfig,
        providers: List<GroundsModuleProvider>,
        services: ServiceRegistry,
    ): List<InstalledGroundsModule> {
        val matchingProviders = providers.filter { config.serverType in it.serverTypes }
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

# grounds-minestom-runtime

Grounds runtime for Minestom-based lobby and minigame server images.

This repository provides the process-level runtime pieces for build-time composed Minestom servers:

- module lifecycle APIs
- server context and runtime environment types
- Minestom bootstrap and shutdown orchestration
- runtime test helpers
- example server application

The runtime is not a hot plugin system. Server images are built from pinned Gradle dependencies and run as immutable artifacts.

## Modules

```text
runtime-api       Public module lifecycle and server context API
runtime-core      Runtime bootstrap, lifecycle runner, config, and health state
runtime-testkit   Helpers for module composition tests
examples          Minimal runnable server examples
```

## Build

```bash
./gradlew build -Pgithub.user="$GITHUB_ACTOR" -Pgithub.token="$GITHUB_TOKEN"
```

## Module Composition

Modules can be installed directly, or through `GroundsModuleProvider` when they need
descriptor metadata, dependency ordering, server-type filtering, or typed services.

```kotlin
class MatchmakingModuleProvider : GroundsModuleProvider {
    override val id = "grounds.matchmaking"
    override val version = "1.0.0"
    override val serverTypes = setOf(ServerType.MINIGAME)
    override val descriptor =
        ModuleDescriptor(
            id = id,
            version = version,
            requires = setOf(serviceKey<PlayerService>()),
            provides = setOf(serviceKey<MatchmakingService>()),
        )

    override fun create(): GroundsModule = MatchmakingModule()
}
```

The runtime validates provider descriptors before startup, sorts provider-backed modules
by explicit dependencies and required service providers, and passes one shared
`ServiceRegistry` through `GroundsServerContext`. Use type-first access for services:

```kotlin
ctx.services.register<MatchmakingService>(DefaultMatchmakingService())
val players = ctx.services.require<PlayerService>()
```

## License

Licensed under the Apache License, Version 2.0.

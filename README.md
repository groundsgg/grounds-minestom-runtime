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

Runtimes use modules directly, or through `GroundsModuleProvider` when they need
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

## Metrics

Every server can publish Prometheus metrics about itself. Off unless asked for,
because a scrape target nobody collects is only a cost.

| Variable | Default | Meaning |
| --- | --- | --- |
| `GROUNDS_METRICS_ENABLED` | `false` | Bind the endpoint at all |
| `GROUNDS_METRICS_HOST` | `0.0.0.0` | Bind address |
| `GROUNDS_METRICS_PORT` | `9000` | Must differ from `GROUNDS_BIND_PORT` |
| `GROUNDS_METRICS_PATH` | `/metrics` | Path served; everything else answers 404 |

What it publishes, on top of Micrometer's JVM and process binders
(`jvm_memory_used_bytes`, `jvm_gc_pause_seconds`, `process_cpu_usage`, … — the
same names the Quarkus services use, so one query covers both):

```text
minecraft_tick_duration_seconds      Timer. _count is ticks (rate = TPS),
                                     _sum/_count is mean MSPT, _max is the spike
minecraft_tick_acquisition_seconds   Timer. Time a tick waited for threads
minecraft_players_online             Players in the world
minecraft_instances                  Instances held (one per live match)
minecraft_entities                   Entities across every instance
minecraft_chunks_loaded              Chunks loaded across every instance
```

Every series carries `server_type` (`lobby` / `minigame`) and `environment`.
`cluster`, `pod` and `app` are added by the satellite's metrics agent.

In Kubernetes the port must also be a **declared `containerPort`** and the pod
must carry `prometheus.io/scrape=true` — `grounds-gamemode`'s `metrics.enabled`
does both. Failing to bind is logged and the server keeps running: a game server
without metrics is degraded, one that refuses to boot is an outage.

## License

Licensed under the Apache License, Version 2.0.

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

## Map block rendering

`MapBlockRenderingModule` in `runtime-core` preserves player-head profiles, sign text
and banner patterns when loading authored Anvil maps. Select it before modules that
load worlds:

```kotlin
GroundsServer.builder()
    .config(config)
    .use(MapBlockRenderingModule())
    .use(MyWorldModule())
    .build()
```

The module registers client-NBT handlers for `minecraft:skull`, `minecraft:sign`,
`minecraft:hanging_sign` and `minecraft:banner`. It enables no block interactions,
inventories, physics or scheduled tasks and requires no Vanilla dependency. Existing
handlers keep ownership; installation is idempotent for each Minestom process.
Handlers persist until the process stops because loaded blocks keep their handler.
Install before loading any chunks: later registration cannot repair existing dummy
handlers. Embedded texture properties are forwarded as authored; the module does
not look up missing profiles or convert old map formats.

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

The registry is registered as the `io.micrometer.core.instrument.MeterRegistry`
service, so a module can publish its own meters on the same endpoint:
`ctx.services.get<MeterRegistry>()` (absent when the endpoint is off).

## Match timeline

`runtime-match` records where the seconds between a pushed match and a running
round go. The runtime marks the push and the accept (`MatchHandler.start`
returned or threw) itself; the gamemode reports the rest through
`matchHost.timeline`:

```kotlin
val matchHost = MatchHostModule(handler)

// In MatchHandler.start, once the map is ready (survivalgames: after MapSource.load).
matchHost.timeline.mapLoaded(match.matchId, MatchTimeline.MapSource.DOWNLOAD) // or CACHE

// On a player's first spawn in the match's world, not on connect.
matchHost.timeline.playerJoined(matchId, player.uuid)

// When the countdown is over and the round is being played.
matchHost.timeline.started(matchId)
```

`finished` is called by `MatchRegistry.finish`. Calls are thread-safe and
idempotent, and an unknown match id is ignored. A match nothing finishes is
forgotten two hours after its push.

Histograms (seconds, buckets `0.1 … 300`), labelled by `mode` only:

```text
grounds_match_time_to_accept_seconds      push → handler returned   (+ result=ok|failed)
grounds_match_time_to_map_seconds         push → mapLoaded          (+ source=cache|download)
grounds_match_time_to_first_join_seconds  push → first roster player joined
grounds_match_time_to_all_joined_seconds  push → last roster player joined
grounds_match_time_to_start_seconds       push → started
grounds_match_queue_to_join_seconds       a player's queue entry → that player joined
```

`queue_to_join` needs the `grounds-enqueued-at` NATS header from service-match;
without it (and without `traceparent` / `grounds-formed-at`) those parts are
skipped. Every mark logs
`Match timeline (match=<id>, mode=<mode>, stage=<stage>, at=+<ms>ms)`, and
`started` adds a `Match timeline summary (...)` line with every stage's offset.

Traces continue the pushed `traceparent`: a `match <mode>` span from push to
finish, with `accept`, `map` and `wait for players` children and `player joined` /
`started` events. Exported over OTLP/HTTP, configured by the standard variables:

| Variable | Meaning |
| --- | --- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Collector base URL (spans go to `/v1/traces`). Unset: tracing is a no-op |
| `OTEL_SERVICE_NAME` | `service.name` |
| `OTEL_RESOURCE_ATTRIBUTES` | Extra resource attributes, `key=value,...` |
| `OTEL_SDK_DISABLED` | `true` turns tracing off |

## License

Licensed under the Apache License, Version 2.0.

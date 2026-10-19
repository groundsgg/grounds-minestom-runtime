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

## Opening worlds

Open every map through `GroundsWorld` in `runtime-core` instead of
`InstanceManager.createInstanceContainer`:

```kotlin
// An Anvil map: the directory that contains region/.
val instance = GroundsWorld.open(worldPath, WorldIdentity.of("survivalgames/breeze", version))

// An arena the game builds itself, with nothing on disk.
val arena = GroundsWorld.create(WorldIdentity.of("getdown/deathmatch"))
```

Both register the instance and use `LightingChunk`. They fix two things a plain
`createInstanceContainer` gets wrong:

- **Identity.** Minestom sends every instance as `minecraft:overworld` with hashed
  seed `0`, so behind one proxy address every map looks like the same world. LOD
  mods cache per world under exactly those values (Distant Horizons:
  `<seed>@<dimension key>` in a folder per server; Voxy: a hash of seed and key in
  a folder per server address), and then show the previous map or the lobby in the
  distance. `WorldIdentity.of(address, version)` gives each map its own key,
  `grounds:<address>[_v<version>]` with everything but `a-z0-9_-` turned into `_`,
  and a hashed seed derived from that key. The same map always gets the same
  identity, so the LOD cache is reused; a new version gets a fresh one.
- **Light.** Converted maps carry no sky or block light, and Minestom only lights
  chunks it generates. `open` relights every chunk of such a map as it loads, the
  same way a generated chunk is lit, so the map is not dark. A *lit world* (below)
  is sent with the light it stores.

### Maps for rounds: `MapStore` and `GroundsWorld.prepare`

Minestom computes a chunk's light when the chunk is first sent — on the tick, under
the instance's monitor, for every chunk around a joining player. On a converted map
that is seconds of "Loading terrain…" and a stalled server. Rounds therefore play
lit worlds, loaded in full before anyone joins:

```kotlin
val maps = MapStore() // MAPS_CDN_BASE, MAPS_ENVIRONMENT, MAPS_CACHE_DIR

// At boot: download and light every map a round may pick, one at a time, in the background.
maps.warm(listOf("survivalgames", "survivalgames-dm"))

// Per round: the map on local disk, lit if it already is.
val map = checkNotNull(maps.fetch(maps.pinned("survivalgames").random()))
val world = GroundsWorld.prepare(map.path, WorldIdentity.of(map.pinned.address, map.version)) {
    instance -> /* the round's own block changes, e.g. chests */
}
// StartMatch can answer now. A joining player's configuration waits for the world:
world.await(10_000) // then spawningInstance = world.instance
```

- `MapStore` reads the pin file from the CDN, downloads a bundle once per pod
  (checked against its pinned SHA-256) and keeps a lit copy beside it. `fetch`
  returns the lit copy when there is one; otherwise the unlit one, and lights it in
  the background for the next round.
- `MapLight.bake` makes a lit world: every chunk lit the way Minestom lights it for
  a client, `isLightOn = 1`, all-air chunks left out (a missing chunk of a lit world
  is open sky), leftover upgrade NBT dropped, and a `.grounds-lit-v1` marker. Any
  step that unpacks a map can produce it; only a world with the marker is trusted.
- `GroundsWorld.prepare` loads every chunk of the map off the tick thread, on an
  instance nobody can join yet, runs the round's edits, lights whatever still needs
  it and only then registers the instance. Nothing loads or lights while players
  play. Memory per round is the whole map (see the lit size per map in the logs).

Moving a player between worlds with different keys sends a Respawn, so the client
starts a fresh level (and a fresh LOD level). Minestom resends inventory, health
and abilities after it but not potion effects, so a game that moves players with
active effects between maps (a deathmatch arena, say) should send them again.
Between worlds with the same key nothing changes.

The key shows on the F3 screen and in commands such as `/spawnpoint`. The
dimension type, not the key, drives sky, fog and lighting, so an overworld-typed
world looks like the overworld.

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

## Idle servers go back to Ready

The matchmaker's allocation moves a server to `Allocated`. `runtime-match`
moves it back to `Ready` through the Agones SDK once it has hosted nothing for
a grace period: no live match, none being accepted, and the `matches` counter at
0. Agones can then replace it in a Fleet rolling update and scale it down. The
matchmaker still allocates it, since its allocation also takes `Ready` servers
with a free slot. A match pushed to a server that is `Ready` marks it
`Allocated` again before the gamemode's `MatchHandler` runs.

| Variable | Default | Meaning |
| --- | --- | --- |
| `GROUNDS_MATCHMAKING_REREADY` | `true` | `false` keeps a server `Allocated` after its first match |
| `GROUNDS_MATCHMAKING_REREADY_GRACE_SECONDS` | `20` | How long a server must be idle before it is readied |

## Ending a match

When a match is over, send its players back to a lobby with `MatchExit.toLobby`
instead of kicking them with a message:

```kotlin
// Once the podium delay is over, in place of `forEach { it.kick("Match over") }`.
MatchExit.toLobby(instance.players)              // fallback kick after 5 s
MatchExit.toLobby(players, Duration.ofSeconds(10))
```

For each player it sends the `Lobby` verb on the `grounds:match` plugin-message
channel (one `DataOutputStream.writeUTF("Lobby")`, bytes `00 05 4C 6F 62 62 79`).
plugin-match on the proxy moves that player to their home lobby, or the
least-loaded `lobby-*` server, as a normal server switch: no kick and no chat line.

After `kickAfter` (default 5 s) anyone still connected is kicked with an empty
reason (`Component.empty()`). plugin-match redirects that kick to a lobby without
a message as well, so the fallback also covers proxies that don't know the verb
yet. The player collection is copied at the call, so passing a live set such as
`instance.players` is fine. Keep using a reasoned kick for players who never
belonged to a match (`"No match found"`).

## License

Licensed under the Apache License, Version 2.0.

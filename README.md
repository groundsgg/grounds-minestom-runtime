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
./gradlew build
```

## License

Licensed under the Apache License, Version 2.0.

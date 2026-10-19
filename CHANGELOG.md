# Changelog

## [0.13.1](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.13.0...v0.13.1) (2026-10-19)


### Performance Improvements

* **world:** release a round world a few chunks a tick ([#47](https://github.com/groundsgg/grounds-minestom-runtime/issues/47)) ([667ed88](https://github.com/groundsgg/grounds-minestom-runtime/commit/667ed88b1c85a1d60f1a17aac52c71137902976a))

## [0.13.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.12.0...v0.13.0) (2026-10-19)


### Features

* **world:** lit maps and rounds prepared before anyone joins ([#45](https://github.com/groundsgg/grounds-minestom-runtime/issues/45)) ([bea0eee](https://github.com/groundsgg/grounds-minestom-runtime/commit/bea0eee38eb8f03ad6935ca6245865b1f5d977ee))

## [0.12.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.11.0...v0.12.0) (2026-10-17)


### Features

* **match:** hand each pushed roster to registered player prefetches ([#42](https://github.com/groundsgg/grounds-minestom-runtime/issues/42)) ([d850c0f](https://github.com/groundsgg/grounds-minestom-runtime/commit/d850c0f64d4833da7393169d3923844c62b5e5c0))

## [0.11.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.10.0...v0.11.0) (2026-10-17)


### Features

* **world:** open maps with their own world identity and relit chunks ([#41](https://github.com/groundsgg/grounds-minestom-runtime/issues/41)) ([c6fa889](https://github.com/groundsgg/grounds-minestom-runtime/commit/c6fa889d5be770d80f8e824584f809126e81787c))


### Bug Fixes

* **build:** resolve dependencies through the runner mirror in Docker builds ([#39](https://github.com/groundsgg/grounds-minestom-runtime/issues/39)) ([5a1a7a3](https://github.com/groundsgg/grounds-minestom-runtime/commit/5a1a7a34a04936dd643953a1c509fefc43af1e61))

## [0.10.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.9.1...v0.10.0) (2026-10-17)


### Features

* **match:** add MatchExit.toLobby to end a match without a kick message ([#37](https://github.com/groundsgg/grounds-minestom-runtime/issues/37)) ([7464cda](https://github.com/groundsgg/grounds-minestom-runtime/commit/7464cda4a02b8a1643429b535b83a5fe43eebc6a))

## [0.9.1](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.9.0...v0.9.1) (2026-10-17)


### Bug Fixes

* mark idle matchmade servers Ready again ([#35](https://github.com/groundsgg/grounds-minestom-runtime/issues/35)) ([13270f9](https://github.com/groundsgg/grounds-minestom-runtime/commit/13270f9d78476dc61980794c8a3bbb7bdf45b3c7))

## [0.9.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.8.0...v0.9.0) (2026-10-17)


### Features

* add match timeline metrics, logs and traces to runtime-match ([#33](https://github.com/groundsgg/grounds-minestom-runtime/issues/33)) ([23a4b6a](https://github.com/groundsgg/grounds-minestom-runtime/commit/23a4b6af4160c5c8dfec5e2e4e8ef0e63946ac58))

## [0.8.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.7.0...v0.8.0) (2026-09-02)


### Features

* add opt-in map block rendering module ([#30](https://github.com/groundsgg/grounds-minestom-runtime/issues/30)) ([384d1e1](https://github.com/groundsgg/grounds-minestom-runtime/commit/384d1e13a1d9699641b52b888a7fe01ebb1a2304))

## [0.7.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.6.1...v0.7.0) (2026-08-04)


### Features

* **runtime-match:** wait for matches on NATS instead of a gRPC port ([#27](https://github.com/groundsgg/grounds-minestom-runtime/issues/27)) ([107769e](https://github.com/groundsgg/grounds-minestom-runtime/commit/107769e5d78c118659c1ad68bda06221f90db25a))

## [0.6.1](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.6.0...v0.6.1) (2026-08-03)


### Bug Fixes

* **match:** give back the slot when a match is refused, and repair drift ([#25](https://github.com/groundsgg/grounds-minestom-runtime/issues/25)) ([686043b](https://github.com/groundsgg/grounds-minestom-runtime/commit/686043bd269f7fcdf79646734eb63bfcb40ea4a3))

## [0.6.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.5.0...v0.6.0) (2026-08-03)


### Features

* **metrics:** publish Minestom server metrics on a Prometheus endpoint ([#23](https://github.com/groundsgg/grounds-minestom-runtime/issues/23)) ([0e038b0](https://github.com/groundsgg/grounds-minestom-runtime/commit/0e038b091a376fe6590d34acb7c15d7bbb4f9a0e))

## [0.5.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.4.0...v0.5.0) (2026-08-02)


### Features

* **match:** surface the pushed map on PushedMatch ([#21](https://github.com/groundsgg/grounds-minestom-runtime/issues/21)) ([d46e472](https://github.com/groundsgg/grounds-minestom-runtime/commit/d46e4724cea4a74324400a429946184965125599))

## [0.4.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.3.0...v0.4.0) (2026-07-13)


### Features

* **match:** host the matches the matchmaker pushes ([#13](https://github.com/groundsgg/grounds-minestom-runtime/issues/13)) ([5179670](https://github.com/groundsgg/grounds-minestom-runtime/commit/51796709fd5b19fda8ed03dfd264c5a20b1e6312))


### Bug Fixes

* **ci:** give the SNAPSHOT publish its Gradle credentials ([#16](https://github.com/groundsgg/grounds-minestom-runtime/issues/16)) ([f538228](https://github.com/groundsgg/grounds-minestom-runtime/commit/f538228c00996928688471ea571c37c91798da8e))

## [0.3.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.2.0...v0.3.0) (2026-07-09)


### Features

* expose active module providers ([#11](https://github.com/groundsgg/grounds-minestom-runtime/issues/11)) ([2b574ba](https://github.com/groundsgg/grounds-minestom-runtime/commit/2b574ba2369c8e93aca34fd6314935a35899ee97))

## [0.2.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.1.0...v0.2.0) (2026-06-29)


### Features

* add velocity proxy auth ([#7](https://github.com/groundsgg/grounds-minestom-runtime/issues/7)) ([b8e59ad](https://github.com/groundsgg/grounds-minestom-runtime/commit/b8e59ada98c716899a1db58cbbf35961d289fe8b))
* configure runtime server brand ([#6](https://github.com/groundsgg/grounds-minestom-runtime/issues/6)) ([70eabcc](https://github.com/groundsgg/grounds-minestom-runtime/commit/70eabcc69b1cfd6ef7e6107325817cf18ca817c3))
* default module server type compatibility ([#3](https://github.com/groundsgg/grounds-minestom-runtime/issues/3)) ([58b25e4](https://github.com/groundsgg/grounds-minestom-runtime/commit/58b25e44a7e176dabb059f7dc34cf6a7dcf989e4))
* use explicit minestom runtime modules ([#5](https://github.com/groundsgg/grounds-minestom-runtime/issues/5)) ([7698fb3](https://github.com/groundsgg/grounds-minestom-runtime/commit/7698fb37685e7810564393484bf945d78b700cee))
* use grounds dependencies bom ([#9](https://github.com/groundsgg/grounds-minestom-runtime/issues/9)) ([b0f2f16](https://github.com/groundsgg/grounds-minestom-runtime/commit/b0f2f168e1da2de57ff59969a1536f1aa06197d4))

## [0.1.0](https://github.com/groundsgg/grounds-minestom-runtime/compare/v0.0.1...v0.1.0) (2026-06-16)


### Features

* bootstrap minestom runtime ([9cbd3a7](https://github.com/groundsgg/grounds-minestom-runtime/commit/9cbd3a78ae08e0fd2497e8137ac3ae26038464b8))
* use jvm module service registry ([#2](https://github.com/groundsgg/grounds-minestom-runtime/issues/2)) ([62f8bc4](https://github.com/groundsgg/grounds-minestom-runtime/commit/62f8bc4289ed4588144b7ce99a81f0881fae2240))

## 0.1.0

- Initial repository bootstrap.

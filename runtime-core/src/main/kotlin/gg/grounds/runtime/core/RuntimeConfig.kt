package gg.grounds.runtime.core

import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType

data class RuntimeConfig(
    val serverType: ServerType,
    val environment: RuntimeEnvironment,
    val host: String = "0.0.0.0",
    val port: Int = 25565,
    val serverBrand: String = "Grounds",
) {
    companion object {
        fun fromEnvironment(env: RuntimeEnv = RuntimeEnv.system()): RuntimeConfig {
            return RuntimeConfig(
                serverType =
                    env.choice("GROUNDS_SERVER_TYPE", ServerType.MINIGAME, ::parseServerType),
                environment = env.choice("GROUNDS_ENV", RuntimeEnvironment.DEV, ::parseEnvironment),
                host = env.string("GROUNDS_BIND_HOST", "0.0.0.0"),
                port = env.int("GROUNDS_BIND_PORT", 25565),
                serverBrand = env.string("GROUNDS_SERVER_BRAND", "Grounds"),
            )
        }

        fun fromEnvironment(env: Map<String, String>): RuntimeConfig =
            fromEnvironment(RuntimeEnv.of(env))

        private fun parseServerType(value: String): ServerType? {
            return when (value.lowercase()) {
                "lobby" -> ServerType.LOBBY
                "minigame" -> ServerType.MINIGAME
                else -> null
            }
        }

        private fun parseEnvironment(value: String): RuntimeEnvironment? {
            return when (value.lowercase()) {
                "prod" -> RuntimeEnvironment.PROD
                "test" -> RuntimeEnvironment.TEST
                "dev" -> RuntimeEnvironment.DEV
                else -> null
            }
        }
    }
}

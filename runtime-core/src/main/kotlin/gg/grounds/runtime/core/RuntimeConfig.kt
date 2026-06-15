package gg.grounds.runtime.core

import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType

data class RuntimeConfig(
    val serverType: ServerType,
    val environment: RuntimeEnvironment,
    val host: String = "0.0.0.0",
    val port: Int = 25565,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): RuntimeConfig {
            return RuntimeConfig(
                serverType = parseServerType(env["GROUNDS_SERVER_TYPE"]),
                environment = parseEnvironment(env["GROUNDS_ENV"]),
                host = env["GROUNDS_BIND_HOST"] ?: "0.0.0.0",
                port = env["GROUNDS_BIND_PORT"]?.toIntOrNull() ?: 25565,
            )
        }

        private fun parseServerType(value: String?): ServerType {
            return when (value?.lowercase()) {
                "lobby" -> ServerType.LOBBY
                "minigame",
                null,
                "" -> ServerType.MINIGAME
                else -> error("unsupported GROUNDS_SERVER_TYPE: $value")
            }
        }

        private fun parseEnvironment(value: String?): RuntimeEnvironment {
            return when (value?.lowercase()) {
                "prod" -> RuntimeEnvironment.PROD
                "test" -> RuntimeEnvironment.TEST
                "dev",
                null,
                "" -> RuntimeEnvironment.DEV
                else -> error("unsupported GROUNDS_ENV: $value")
            }
        }
    }
}

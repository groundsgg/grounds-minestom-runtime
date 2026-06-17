package gg.grounds.runtime.core

import gg.grounds.runtime.RuntimeEnvironment
import gg.grounds.runtime.ServerType

data class RuntimeConfig(
    val serverType: ServerType,
    val environment: RuntimeEnvironment,
    val host: String = "0.0.0.0",
    val port: Int = 25565,
    val serverBrand: String = "Grounds",
    val onlineMode: Boolean = true,
    val proxy: ProxyConfig = ProxyConfig(),
) {
    companion object {
        private val velocityForwardingSecretNames =
            listOf(
                "GROUNDS_VELOCITY_FORWARDING_SECRET",
                "VELOCITY_FORWARDING_SECRET",
                "GROUNDS_LOBBY_VELOCITY_SECRET",
                "PAPER_VELOCITY_SECRET",
            )

        fun fromEnvironment(env: RuntimeEnv = RuntimeEnv.system()): RuntimeConfig {
            return RuntimeConfig(
                serverType =
                    env.choice("GROUNDS_SERVER_TYPE", ServerType.MINIGAME, ::parseServerType),
                environment = env.choice("GROUNDS_ENV", RuntimeEnvironment.DEV, ::parseEnvironment),
                host = env.string("GROUNDS_BIND_HOST", "0.0.0.0"),
                port = env.int("GROUNDS_BIND_PORT", 25565),
                serverBrand = env.string("GROUNDS_SERVER_BRAND", "Grounds"),
                onlineMode = env.boolean("GROUNDS_ONLINE_MODE", true),
                proxy = parseProxyConfig(env),
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

        private fun parseProxyConfig(env: RuntimeEnv): ProxyConfig {
            val mode = env.choice("GROUNDS_PROXY_MODE", ProxyMode.AUTO, ::parseProxyMode)
            val secret = env.firstString(velocityForwardingSecretNames)
            if (mode == ProxyMode.VELOCITY && secret == null) {
                throw IllegalArgumentException(
                    "GROUNDS_PROXY_MODE=velocity requires one of " +
                        velocityForwardingSecretNames.joinToString()
                )
            }
            return ProxyConfig(mode = mode, velocityForwardingSecret = secret)
        }

        private fun parseProxyMode(value: String): ProxyMode? {
            return when (value.lowercase()) {
                "auto" -> ProxyMode.AUTO
                "velocity" -> ProxyMode.VELOCITY
                "offline" -> ProxyMode.OFFLINE
                else -> null
            }
        }
    }
}

data class ProxyConfig(
    val mode: ProxyMode = ProxyMode.AUTO,
    val velocityForwardingSecret: String? = null,
)

enum class ProxyMode {
    AUTO,
    VELOCITY,
    OFFLINE,
}

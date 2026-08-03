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
    val metrics: MetricsConfig = MetricsConfig(),
) {
    companion object {
        private const val velocityForwardingSecretName = "GROUNDS_VELOCITY_FORWARDING_SECRET"

        fun fromEnvironment(env: RuntimeEnv = RuntimeEnv.system()): RuntimeConfig {
            val minecraftPort = env.int("GROUNDS_BIND_PORT", 25565)
            return RuntimeConfig(
                serverType =
                    env.choice("GROUNDS_SERVER_TYPE", ServerType.MINIGAME, ::parseServerType),
                environment = env.choice("GROUNDS_ENV", RuntimeEnvironment.DEV, ::parseEnvironment),
                host = env.string("GROUNDS_BIND_HOST", "0.0.0.0"),
                port = minecraftPort,
                serverBrand = env.string("GROUNDS_SERVER_BRAND", "Grounds"),
                onlineMode = env.boolean("GROUNDS_ONLINE_MODE", true),
                proxy = parseProxyConfig(env),
                metrics = parseMetricsConfig(env, minecraftPort),
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
            val secret = env.string(velocityForwardingSecretName, "").takeIf { it.isNotEmpty() }
            require(!(mode == ProxyMode.VELOCITY && secret == null)) {
                "GROUNDS_PROXY_MODE=velocity requires $velocityForwardingSecretName"
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

        private fun parseMetricsConfig(env: RuntimeEnv, minecraftPort: Int): MetricsConfig {
            val config =
                MetricsConfig(
                    enabled = env.boolean("GROUNDS_METRICS_ENABLED", false),
                    host = env.string("GROUNDS_METRICS_HOST", "0.0.0.0"),
                    port = env.int("GROUNDS_METRICS_PORT", 9000),
                    path = env.string("GROUNDS_METRICS_PATH", "/metrics"),
                )
            require(config.path.startsWith("/")) {
                "GROUNDS_METRICS_PATH must start with '/': ${config.path}"
            }
            // Sharing the Minecraft port fails at bind time with "Address already in use", which
            // names neither setting. Say which two collided instead.
            require(config.port != minecraftPort) {
                "GROUNDS_METRICS_PORT must differ from GROUNDS_BIND_PORT: ${config.port}"
            }
            return config
        }
    }
}

data class ProxyConfig(
    val mode: ProxyMode = ProxyMode.AUTO,
    val velocityForwardingSecret: String? = null,
)

/**
 * The Prometheus endpoint the satellite's metrics agent scrapes.
 *
 * Off by default: a scrape target nobody asked for is a metrics bill, and the agent only collects
 * pods that carry `prometheus.io/scrape=true` anyway — so this switch and the chart's have to agree
 * before anything is published. `port` must be a **declared containerPort** on the pod as well: the
 * agent keeps only the discovered target whose port matches the annotation, so a metrics port the
 * pod spec does not name is never scraped.
 */
data class MetricsConfig(
    val enabled: Boolean = false,
    val host: String = "0.0.0.0",
    val port: Int = 9000,
    val path: String = "/metrics",
)

enum class ProxyMode {
    AUTO,
    VELOCITY,
    OFFLINE,
}

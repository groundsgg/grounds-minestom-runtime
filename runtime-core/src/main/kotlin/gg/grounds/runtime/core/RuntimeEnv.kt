package gg.grounds.runtime.core

class RuntimeEnv private constructor(private val lookup: (String) -> String?) {
    fun string(name: String, default: String): String =
        lookup(name)?.trim()?.takeIf { it.isNotEmpty() } ?: default

    fun int(name: String, default: Int): Int {
        val value = lookup(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
        return value.toIntOrNull() ?: throw IllegalArgumentException("unsupported $name: $value")
    }

    fun boolean(name: String, default: Boolean): Boolean {
        val value = lookup(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
        return when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("unsupported $name: $value")
        }
    }

    fun <T> choice(name: String, default: T, parse: (String) -> T?): T {
        val value = lookup(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
        return parse(value) ?: throw IllegalArgumentException("unsupported $name: $value")
    }

    companion object {
        fun system(): RuntimeEnv = RuntimeEnv { name -> System.getenv(name) }

        fun of(values: Map<String, String>): RuntimeEnv = RuntimeEnv { name -> values[name] }
    }
}

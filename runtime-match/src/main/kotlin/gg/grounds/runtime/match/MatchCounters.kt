package gg.grounds.runtime.match

/**
 * Advertises free match capacity on this server. Pulled out as an interface separate from
 * [AgonesCounters] so tests can fake it instead of hitting the Agones SDK sidecar's HTTP API.
 */
interface MatchCounters {
    fun decrement()
}

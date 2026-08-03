package gg.grounds.runtime.match

/**
 * Advertises free match capacity on this server. Pulled out as an interface separate from
 * [AgonesCounters] so tests can fake it instead of hitting the Agones SDK sidecar's HTTP API.
 */
interface MatchCounters {
    fun decrement()

    /**
     * Set the advertised count to [live], the number of matches actually running.
     *
     * Decrementing is a *relative* correction and only works when every path that consumes a slot
     * also releases it. Something will eventually not: a reply lost after the counter went up, a
     * process killed between accepting a match and registering it, a bug like the one that retired
     * every server on stage. This is the absolute statement that repairs whatever the relative ones
     * missed.
     */
    fun reconcile(live: Int)
}

package gg.grounds.runtime.core.metrics

import net.minestom.server.MinecraftServer

/**
 * The game-side numbers the metrics endpoint publishes, read fresh on every scrape.
 *
 * An interface with one real implementation, because the real one reads `MinecraftServer` statics
 * that only exist after `MinecraftServer.init()` — so without it the gauges could not be tested
 * without booting a server, and a gauge that silently reports NaN is exactly the failure this whole
 * module is meant to make visible.
 */
interface GameSnapshot {
    /** Players past the configuration phase and in the world. */
    fun playersOnline(): Int

    /** Instances the server holds. A minigame creates one per match and drops it afterwards. */
    fun instances(): Int

    /** Entities across every instance, players included — the tick's main workload. */
    fun entities(): Int

    /** Chunks currently loaded across every instance. */
    fun chunksLoaded(): Int

    companion object {
        /**
         * Reads Minestom directly.
         *
         * Every call walks the instance set, which is small (one per live match) and only happens
         * on scrape. Deliberately not cached: a number that is a scrape interval stale is worse
         * than the walk it saves, because it is only ever read to answer "what is happening now".
         */
        fun minestom(): GameSnapshot =
            object : GameSnapshot {
                override fun playersOnline(): Int =
                    MinecraftServer.getConnectionManager().getOnlinePlayerCount()

                override fun instances(): Int =
                    MinecraftServer.getInstanceManager().getInstances().size

                override fun entities(): Int =
                    MinecraftServer.getInstanceManager().getInstances().sumOf { instance ->
                        instance.getEntities().size
                    }

                override fun chunksLoaded(): Int =
                    MinecraftServer.getInstanceManager().getInstances().sumOf { instance ->
                        instance.getChunks().size
                    }
            }
    }
}

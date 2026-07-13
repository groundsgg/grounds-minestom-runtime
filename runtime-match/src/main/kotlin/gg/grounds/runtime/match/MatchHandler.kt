package gg.grounds.runtime.match

/**
 * Implemented by the gamemode: build the arena and take the players.
 *
 * Throwing means "I cannot host this match" — [MatchRegistry.accept] treats that as a refusal
 * rather than letting a half-built match get registered.
 */
fun interface MatchHandler {
    fun start(match: PushedMatch)
}

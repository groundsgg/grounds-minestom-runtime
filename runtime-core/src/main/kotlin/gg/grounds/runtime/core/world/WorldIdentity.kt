package gg.grounds.runtime.core.world

import java.nio.ByteBuffer
import java.security.MessageDigest
import net.kyori.adventure.key.Key

/**
 * What a client can tell a world apart by: the dimension key and the hashed seed it receives in the
 * Login and Respawn packets.
 *
 * Minestom sends `minecraft:overworld` and seed `0` for every instance unless told otherwise, so
 * every map on the network looks like the same world. LOD mods cache per world under exactly these
 * two values (Distant Horizons: `<seed>@<dimension key>`; Voxy: a hash of seed and key), and then
 * show the previous map, or the lobby, in the distance. A map gets its own key and seed here.
 *
 * The identity is derived, never random: the same map always gets the same identity, so a cache a
 * player already has for it is reused, and different maps never share one.
 */
data class WorldIdentity(val key: Key, val hashedSeed: Long) {
    companion object {
        const val NAMESPACE = "grounds"

        /**
         * The identity of the map at [address], e.g. `survivalgames/breeze`, optionally at a map
         * [version].
         *
         * Pass the version when the map can change under the same address: a new version then gets
         * a fresh cache instead of the old version's terrain. The key only uses `a-z`, `0-9`, `_`
         * and `-`, which keeps it a valid Minecraft key, a plain folder name, and a valid Distant
         * Horizons level key.
         */
        fun of(address: String, version: Int? = null): WorldIdentity {
            val slug = slug(address)
            require(slug.isNotEmpty()) { "map address must not be blank: '$address'" }
            val key = Key.key(NAMESPACE, if (version == null) slug else "${slug}_v$version")
            return WorldIdentity(key, seedOf(key))
        }

        /** Lowercases [address] and turns everything but `a-z`, `0-9`, `_` and `-` into `_`. */
        private fun slug(address: String): String =
            address
                .trim()
                .lowercase()
                .map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '_' }
                .joinToString("")
                .trim('_')

        /** The first eight bytes of the key's SHA-256, as vanilla hashes its world seed. */
        private fun seedOf(key: Key): Long =
            ByteBuffer.wrap(
                    MessageDigest.getInstance("SHA-256").digest(key.asString().toByteArray())
                )
                .long
    }
}

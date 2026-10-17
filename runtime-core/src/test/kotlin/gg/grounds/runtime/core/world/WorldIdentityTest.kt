package gg.grounds.runtime.core.world

import net.kyori.adventure.key.Key
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorldIdentityTest {

    @Test
    fun `same map gets the same identity`() {
        assertEquals(
            WorldIdentity.of("survivalgames/breeze", 3),
            WorldIdentity.of("survivalgames/breeze", 3),
        )
    }

    @Test
    fun `different maps get different keys and seeds`() {
        val breeze = WorldIdentity.of("survivalgames/breeze")
        val lobby = WorldIdentity.of("lobby/mainlobby")

        assertNotEquals(breeze.key, lobby.key)
        assertNotEquals(breeze.hashedSeed, lobby.hashedSeed)
    }

    @Test
    fun `a new map version gets a new identity`() {
        val v3 = WorldIdentity.of("survivalgames/breeze", 3)
        val v4 = WorldIdentity.of("survivalgames/breeze", 4)

        assertNotEquals(v3.key, v4.key)
        assertNotEquals(v3.hashedSeed, v4.hashedSeed)
    }

    @Test
    fun `key is the slugged address in the grounds namespace`() {
        assertEquals(
            Key.key("grounds", "survivalgames_breeze"),
            WorldIdentity.of("survivalgames/breeze").key,
        )
        assertEquals(
            Key.key("grounds", "bedwars_sky-islands_v2"),
            WorldIdentity.of(" BedWars/Sky-Islands ", 2).key,
        )
    }

    @Test
    fun `key is a valid Distant Horizons level key`() {
        // ServerKeyedClientLevelWrapper's pattern; a key outside it is rejected by the client.
        val levelKey = Regex("^(?=.{1,150}$)([a-zA-Z0-9-_]+@)?[a-zA-Z0-9-_]+(:[a-zA-Z0-9-_]+)?$")
        val key = WorldIdentity.of("game/Map Name.with:odd/chars", 12).key.asString()

        assertTrue(levelKey.matches(key), key)
    }

    @Test
    fun `seed is never the vanilla default`() {
        assertNotEquals(0L, WorldIdentity.of("lobby/mainlobby").hashedSeed)
    }

    @Test
    fun `blank address is rejected`() {
        assertThrows<IllegalArgumentException> { WorldIdentity.of("  ") }
        assertThrows<IllegalArgumentException> { WorldIdentity.of("//") }
    }
}

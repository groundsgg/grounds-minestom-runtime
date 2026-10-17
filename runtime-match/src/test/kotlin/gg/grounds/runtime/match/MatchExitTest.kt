package gg.grounds.runtime.match

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.time.Duration
import java.util.UUID
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.network.packet.server.SendablePacket
import net.minestom.server.network.player.GameProfile
import net.minestom.server.network.player.PlayerConnection
import net.minestom.server.timer.Scheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MatchExitTest {

    /** Records what the helper does to it instead of talking to a client. */
    private class FakePlayer(name: String) :
        Player(FakeConnection(), GameProfile(UUID.randomUUID(), name)) {
        var online = true
        val pluginMessages = mutableListOf<Pair<String, ByteArray>>()
        val kicks = mutableListOf<Component>()

        override fun isOnline() = online

        override fun sendPluginMessage(channel: String, data: ByteArray) {
            pluginMessages += channel to data
        }

        override fun kick(component: Component) {
            kicks += component
        }
    }

    private class FakeConnection : PlayerConnection() {
        override fun sendPacket(packet: SendablePacket) {}

        override fun getRemoteAddress(): SocketAddress = InetSocketAddress(0)
    }

    private val scheduler = Scheduler.newScheduler()

    @BeforeEach
    fun init() {
        MinecraftServer.init()
    }

    @AfterEach
    fun stop() {
        MinecraftServer.stopCleanly()
    }

    @Test
    fun `the lobby message is one length-prefixed UTF string`() {
        val message = MatchExit.lobbyMessage()

        assertArrayEquals(byteArrayOf(0, 5) + "Lobby".toByteArray(), message)
        DataInputStream(ByteArrayInputStream(message)).use {
            assertEquals("Lobby", it.readUTF())
            assertEquals(0, it.available())
        }
    }

    @Test
    fun `every player gets the lobby message on the match channel at once`() {
        val players = listOf(FakePlayer("alice"), FakePlayer("bob"))

        MatchExit.toLobby(players, Duration.ofMinutes(1), scheduler)

        for (player in players) {
            val (channel, data) = player.pluginMessages.single()
            assertEquals("grounds:match", channel)
            assertArrayEquals(MatchExit.lobbyMessage(), data)
            assertTrue(player.kicks.isEmpty(), "the kick waits for the proxy")
        }
    }

    @Test
    fun `after the delay only players still online are kicked, with an empty reason`() {
        val moved = FakePlayer("alice")
        val stuck = FakePlayer("bob")

        MatchExit.toLobby(listOf(moved, stuck), Duration.ofMillis(20), scheduler)
        moved.online = false // the proxy moved them to a lobby
        runUntil { stuck.kicks.isNotEmpty() }

        assertEquals(listOf(Component.empty()), stuck.kicks)
        assertTrue(moved.kicks.isEmpty())
    }

    @Test
    fun `the player list is copied when the helper is called`() {
        val player = FakePlayer("alice")
        val live = mutableListOf<Player>(player)

        MatchExit.toLobby(live, Duration.ofMillis(20), scheduler)
        live.clear() // an instance's player set empties as players leave
        runUntil { player.kicks.isNotEmpty() }

        assertEquals(listOf(Component.empty()), player.kicks)
    }

    /** Ticks [scheduler] until [done], as the server's tick loop would. */
    private fun runUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!done()) {
            check(System.nanoTime() < deadline) { "the scheduled kick never ran" }
            scheduler.processTick()
            Thread.sleep(5)
        }
    }
}

package me.spartacus04.jext.listeners

import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect
import me.spartacus04.jext.JextState.LANG
import me.spartacus04.jext.JextState.PLUGIN
import me.spartacus04.jext.JextState.SCHEDULER
import me.spartacus04.jext.discs.Disc
import me.spartacus04.jext.listeners.utils.JextPacketListener
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.Location
import org.bukkit.block.Jukebox
import org.bukkit.entity.Player

internal class RecordPacketEvent : JextPacketListener() {
    override fun onPacketSend(event: PacketSendEvent) {
        if(event.packetType != PacketType.Play.Server.EFFECT) return

        val packet = WrapperPlayServerEffect(event)

        // https://minecraft.wiki/w/Java_Edition_protocol/Packets#World_Event

        if(packet.type != 1010) return

        val player = event.getPlayer<Player>() ?: return

        // Multiple robust checks for plugin state to prevent scheduling when disabled
        if (!PLUGIN.isEnabled) return

        // Additional check using plugin state
        try {
            if (!PLUGIN.isEnabled || !PLUGIN.server.pluginManager.isPluginEnabled(PLUGIN)) return
        } catch (e: Exception) {
            return
        }

        // Validate player and world
        if (!player.isOnline || player.world == null) return

        val position = packet.position.toVector3d()
        val location = Location(player.world, position.x, position.y, position.z)

        // Use synchronous task with region-specific scheduling for Folia compatibility
        // Triple-check plugin state before scheduling - prevent scheduling when disabled
        if (PLUGIN.isEnabled && PLUGIN.server.pluginManager.isPluginEnabled(PLUGIN)) {
            // Add delay to allow block state to update after packet is sent
            SCHEDULER.runTaskLater({
                // Triple-check plugin state inside the task
                if (!PLUGIN.isEnabled) return@runTaskLater

                // Validate player is still online
                if (!player.isOnline) return@runTaskLater

                // Try-catch to handle world access issues in Folia
                try {
                    checkJukeboxWithRetry(player, location, 0)
                } catch (ex: Exception) {
                    // Silently ignore world access errors in Folia
                    // This handles "Thread failed main thread check" errors
                }
            }, 3L)
        }

    }

    private fun checkJukeboxWithRetry(player: Player, location: Location, attempt: Int) {
        if (!PLUGIN.isEnabled || !player.isOnline) return

        try {
            val block = location.block
            val blockState = block.state

            if (blockState is Jukebox) {
                val disc = Disc.fromItemstack(blockState.record)
                if (disc != null) {
                    actionBarDisplay(player, disc)
                    return
                }
            }
        } catch (e: Exception) {
            // Handle world access errors in Folia - just return without retrying
            return
        }

        // If jukebox not found or no record, retry up to 3 times with increasing delay
        if (attempt < 3) {
            SCHEDULER.runTaskLater({
                checkJukeboxWithRetry(player, location, attempt + 1)
            }, (attempt + 1) * 2L)
        }
    }

    private fun actionBarDisplay(player: Player, disc: Disc) {
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(LANG.getKey(player, "now-playing", mapOf(
                "name" to disc.displayName
            )))
        )
    }
}
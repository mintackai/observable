package observable

import dev.architectury.event.events.common.CommandRegistrationEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.utils.GameInstance
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import net.minecraft.world.entity.player.Player
import observable.net.BetterChannel
import observable.net.C2SPacket
import observable.net.S2CPacket
import observable.server.OBSERVABLE_COMMAND
import observable.server.Profiler
import observable.server.ServerSettings
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

object Observable {
    const val MOD_ID = "observable"

    val CHANNEL = BetterChannel(Identifier.fromNamespaceAndPath(MOD_ID, "channel/observable"))
    val LOGGER: Logger = LogManager.getLogger("Observable")
    val PROFILER: Profiler by lazy { Profiler() }

    fun hasPermission(player: Player): Boolean {
        if (ServerSettings.allPlayersAllowed) return true
        if (ServerSettings.allowedPlayers.contains(player.gameProfile.id.toString())) return true
        if (GameInstance.getServer()?.playerList?.isOp(NameAndId(player.gameProfile)) != false) return true
        return GameInstance.getServer()?.isSingleplayer ?: false
    }

    @JvmStatic
    fun init() {
        CHANNEL.register { t: C2SPacket.InitTPSProfile, ctx ->
            val player = ctx.player
            if (!hasPermission(player)) {
                LOGGER.info("${player.name.contents} lacks permissions to start profiling")
                return@register
            }
            if (PROFILER.notProcessing) {
                PROFILER.runWithDuration(player as? ServerPlayer, t.duration, t.sample)
            }
            LOGGER.info("${player.gameProfile.name} started profiler for ${t.duration} s")
        }

        CHANNEL.register { _: C2SPacket.RequestAvailability, ctx ->
            (ctx.player as? ServerPlayer)?.let {
                CHANNEL.sendToPlayer(
                    it,
                    if (hasPermission(it)) {
                        S2CPacket.Availability.Available
                    } else {
                        S2CPacket.Availability.NoPermissions
                    }
                )
            }
        }

        LifecycleEvent.SERVER_STARTED.register {
            val thread = Thread.currentThread()
            PROFILER.serverThread = thread
            LOGGER.info("Registered thread ${thread.name}")
        }

        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(OBSERVABLE_COMMAND)
        }
    }
}

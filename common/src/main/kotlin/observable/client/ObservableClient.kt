package observable.client

import com.mojang.blaze3d.platform.InputConstants
import dev.architectury.event.events.client.ClientLifecycleEvent
import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.registry.client.keymappings.KeyMappingRegistry
import net.minecraft.client.Minecraft
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import observable.Observable
import observable.net.S2CPacket
import observable.server.ProfilingData
import observable.util.MOD_URL_COMPONENT
import observable.util.Marker
import org.lwjgl.glfw.GLFW
import java.net.URI

object ObservableClient {
    private val profileKeybind by lazy {
        KeyMapping(
            "key.observable.profile",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Observable.MOD_ID, "keybinds"))
        )
    }

    private val chat get() = Minecraft.getInstance().gui.chat

    var results: ProfilingData? = null
        private set
    private val profileScreen by lazy { ProfileScreen() }

    @JvmStatic
    fun init() {
        Observable.CHANNEL.register { t: S2CPacket.ProfilingStarted, _ ->
            profileScreen.action = ProfileScreen.Action.TPSProfilerRunning(t.endMillis)
            profileScreen.startBtn?.active = false
        }

        Observable.CHANNEL.register { _: S2CPacket.ProfilingCompleted, _ ->
            profileScreen.action = ProfileScreen.Action.TPSProfilerCompleted
        }

        Observable.CHANNEL.register { _: S2CPacket.ProfilerInactive, _ ->
            profileScreen.action = ProfileScreen.Action.DEFAULT
            profileScreen.startBtn?.active = true
        }

        Observable.CHANNEL.register { t: S2CPacket.ProfilingResult, _ ->
            results = t.data
            profileScreen.apply {
                action = ProfileScreen.Action.DEFAULT
                startBtn?.active = true
            }
            Overlay.loadSync()

            if (t.link != null) {
                val linkText = Component.literal(t.link).withStyle(ChatFormatting.UNDERLINE).withStyle {
                    val uri = runCatching { URI.create(t.link) }.getOrNull()
                    if (uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
                        it.withClickEvent(ClickEvent.OpenUrl(uri))
                    } else {
                        it
                    }
                }
                val msg = Component.translatable("text.observable.profile_uploaded", linkText)
                chat.addClientSystemMessage(msg)
            } else {
                chat.addClientSystemMessage(Component.translatable("text.observable.upload_failed"))
                chat.addClientSystemMessage(
                    Component.translatable("text.observable.profile_saved", ProfileExporter.export(t.data))
                )
                chat.addClientSystemMessage(Component.translatable("text.observable.after_save", MOD_URL_COMPONENT))
            }
        }

        Observable.CHANNEL.register { t: S2CPacket.Availability, _ ->
            when (t) {
                S2CPacket.Availability.Available -> {
                    profileScreen.action = ProfileScreen.Action.DEFAULT
                    profileScreen.startBtn?.active = true
                }
                S2CPacket.Availability.NoPermissions -> {
                    profileScreen.action = ProfileScreen.Action.NO_PERMISSIONS
                    profileScreen.startBtn?.active = false
                }
            }
        }

        KeyMappingRegistry.register(profileKeybind)

        ClientTickEvent.CLIENT_POST.register {
            if (profileKeybind.consumeClick()) {
                it.setScreen(profileScreen)
            }
        }

        ClientLifecycleEvent.CLIENT_LEVEL_LOAD.register { level ->
            Overlay.loadSync(level)
            Marker("observable_announce").mark {
                chat.addClientSystemMessage(
                    Component.translatable(
                        "text.observable.announce",
                        MOD_URL_COMPONENT
                    )
                )
            }
        }

        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            results = null
            profileScreen.action = ProfileScreen.Action.UNAVAILABLE
        }
    }
}

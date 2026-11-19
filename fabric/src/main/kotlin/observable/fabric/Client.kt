package observable.fabric

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import observable.client.ObservableClient
import observable.client.Overlay

class Client : ClientModInitializer {
    override fun onInitializeClient() {
        ObservableClient.init()
        LevelRenderEvents.END_EXTRACTION.register { context ->
            context.levelRenderer().collectPerFrameGizmos().use {
                Overlay.render(context.level(), context.camera(), context.deltaTracker().getGameTimeDeltaPartialTick(true))
            }
        }
    }
}

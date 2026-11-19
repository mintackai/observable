package observable.forge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.common.NeoForge;
import observable.Observable;
import observable.client.ObservableClient;
import observable.client.Overlay;

@Mod(value = Observable.MOD_ID, dist = Dist.CLIENT)
public class ForgeClientHooks {
    public ForgeClientHooks() {
        ObservableClient.init();
        NeoForge.EVENT_BUS.addListener(ForgeClientHooks::onExtractLevelRenderState);
    }

    private static void onExtractLevelRenderState(ExtractLevelRenderStateEvent event) {
        try (var gizmos = event.getLevelRenderer().collectPerFrameGizmos()) {
            Overlay.INSTANCE.render(event.getLevel(), event.getCamera(),
                    event.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        }
    }
}

package observable.forge;

import net.neoforged.fml.common.Mod;
import observable.Observable;

@Mod(Observable.MOD_ID)
public class ObservableForge {
    public ObservableForge() {
        Observable.init();
    }
}

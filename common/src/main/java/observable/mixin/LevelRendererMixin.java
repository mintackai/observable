package observable.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import observable.client.Overlay;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Shadow @Final private LevelTargetBundle targets;

    @Inject(method = "addLateDebugPass", at = @At("HEAD"))
    private void observable$addBlockOverlayPass(FrameGraphBuilder frameGraph, CameraRenderState camera,
            GpuBufferSlice fog, Matrix4fc modelView, CallbackInfo ci) {
        if (!Overlay.INSTANCE.hasBlockGeometry()) return;

        // Draw into the main target before the late debug pass adds the timing labels.
        var pass = frameGraph.addPass("observable_block_overlay");
        var target = pass.readsAndWrites(targets.main);
        targets.main = target;
        pass.executes(() -> Overlay.INSTANCE.renderBlocks(target.get(), camera));
    }
}

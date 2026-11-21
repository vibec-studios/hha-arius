package dev.henny.hha_arius.mixin.client;

import dev.henny.hha.client.vfx.CameraFx;
import dev.henny.hha_arius.client.fx.CounterStance;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Das Kameraschütteln von HHA ruht während Konterhaltung und Bestrafung. */
@Mixin(value = CameraFx.class, remap = false)
public abstract class CounterShakeMixin {
    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, require = 0)
    private static void hhaArius$steady(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (CounterStance.steadyView()) ci.cancel();
    }
}

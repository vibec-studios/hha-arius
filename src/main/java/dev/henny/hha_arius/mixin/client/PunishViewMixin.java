package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.CounterStance;
import dev.henny.hha_arius.client.fx.PunishCam;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Kein Trefferkippen in der Konterhaltung und der Bestrafung, kein Gehwippen in der Bestrafungskamera. */
@Mixin(GameRenderer.class)
public abstract class PunishViewMixin {
    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void hhaArius$steadyHurt(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
        if (CounterStance.steadyView()) ci.cancel();
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void hhaArius$steadyBob(MatrixStack matrices, float tickProgress, CallbackInfo ci) {
        if (PunishCam.hidesHands()) ci.cancel();
    }
}

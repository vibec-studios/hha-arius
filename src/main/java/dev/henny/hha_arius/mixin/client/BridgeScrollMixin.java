package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.gear.client.art.ArtClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Yang-Brücke: Beim Zielen stellt das Mausrad Länge (Schleichen: Breite) ein, statt den Hotbar-Platz zu wechseln. */
@Mixin(Mouse.class)
public abstract class BridgeScrollMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void hha_arius$bridge(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (ArtClient.bridgeScroll(vertical)) {
            ci.cancel();
        }
    }
}

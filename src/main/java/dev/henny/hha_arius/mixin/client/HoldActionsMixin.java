package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.HoldClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inszenierter Treffer: kein Angreifen, Benutzen oder Hotbar-Wechsel, solange der Spieler gehalten wird. */
@Mixin(MinecraftClient.class)
public abstract class HoldActionsMixin {

    @Inject(method = "handleInputEvents", at = @At("HEAD"), cancellable = true)
    private void hha_arius$hold(CallbackInfo ci) {
        if (HoldClient.isHeld()) {
            ci.cancel();
        }
    }
}

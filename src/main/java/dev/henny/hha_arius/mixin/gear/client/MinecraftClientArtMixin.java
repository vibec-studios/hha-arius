package dev.henny.hha_arius.mixin.gear.client;

import dev.henny.hha_arius.gear.client.art.ArtClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Links-/Rechtsklick mit Arius' Kampfhandschuhen bzw. während Solvanes Haltung: ArtClient entscheidet, ob der Klick
 * eine eigene Aktion ist (dann kein Vanilla-Schlag/-Benutzen) oder normal durchgeht (Block abbauen, Truhe öffnen).
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientArtMixin {

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void hha_arius$attack(CallbackInfoReturnable<Boolean> cir) {
        if (ArtClient.onAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void hha_arius$use(CallbackInfo ci) {
        if (ArtClient.onUse()) ci.cancel();
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void hha_arius$breaking(boolean breaking, CallbackInfo ci) {
        if (breaking && ArtClient.blockBreakingSuppressed()) ci.cancel();
    }
}

package dev.henny.hha_arius.mixin.gear.client;

import dev.henny.hha_arius.gear.client.art.ArtPose;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ganzkörper-Haltungen für Arius' Kampfhandschuhe und Solvanes Klaren Schnitt: am Ende von setAngles setzt
 * {@link ArtPose} Rumpf, Arme, Beine und Kopf (weich überblendet mit der Vanilla-Haltung).
 */
@Mixin(PlayerEntityModel.class)
public abstract class ArtPlayerModelMixin {

    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"))
    private void hhaArius$artPose(PlayerEntityRenderState state, CallbackInfo ci) {
        ArtPose.apply((PlayerEntityModel) (Object) this, state);
    }
}

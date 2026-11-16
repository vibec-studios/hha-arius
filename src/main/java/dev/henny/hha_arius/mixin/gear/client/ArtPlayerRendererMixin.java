package dev.henny.hha_arius.mixin.gear.client;

import dev.henny.hha_arius.gear.client.art.ArtPose;
import dev.henny.hha_arius.gear.client.art.ArtRender;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.PlayerLikeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spieler-Renderer für die Kampfkunst: Drehung der ganzen Figur (Drehtritt 360°, Seitentritt) nach den
 * Vanilla-Transformationen, und Ausblenden von Haupthand-Modell/Nebenhand, solange die Handschuhe gehalten werden.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class ArtPlayerRendererMixin {

    @Inject(
        method = "setupTransforms(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;FF)V",
        at = @At("TAIL")
    )
    private void hhaArius$artTurn(PlayerEntityRenderState state, MatrixStack matrices, float bodyYaw, float baseHeight, CallbackInfo ci) {
        ArtPose.applyTurn(state, matrices);
    }

    @Inject(
        method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
        at = @At("TAIL")
    )
    private void hhaArius$gauntletItems(PlayerLikeEntity entity, PlayerEntityRenderState state, float tickProgress, CallbackInfo ci) {
        ArtRender.hideItems(state);
    }
}

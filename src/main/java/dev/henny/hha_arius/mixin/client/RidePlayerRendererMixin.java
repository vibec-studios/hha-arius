package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.RideClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ritt auf Solvane (seit 1.10.0): Reiter liegen waagerecht im Fahrtwind am Eisdorn statt zu sitzen. Ersetzt die
 * Vanilla-Drehung der Figur, solange {@link RideClient#transform} einen Griffpunkt kennt.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class RidePlayerRendererMixin {

    @Inject(
        method = "setupTransforms(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;FF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hhaArius$rideTransform(PlayerEntityRenderState state, MatrixStack matrices, float bodyYaw, float baseHeight, CallbackInfo ci) {
        if (RideClient.transform(state, matrices)) ci.cancel();
    }
}

package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.RideClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Szenen (seit 1.11.0): Fährt die Kamera zurück in die Augen des Spielers, wird seine eigene Figur samt Rüstung nicht
 * gezeichnet. Sonst blickt man kurz von innen auf Kopf und Helm.
 */
@Mixin(EntityRenderer.class)
public abstract class CineHidePlayerMixin {

    @Inject(
        method = "shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/Frustum;DDD)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hhaArius$hideOwnBody(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (RideClient.hideForCamera(entity)) cir.setReturnValue(false);
    }
}

package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.boss.AriusEntity;
import dev.henny.hha_arius.client.fx.HitStop;
import dev.henny.hha_arius.client.model.arius.AriusRenderState;
import dev.henny.hha_arius.gear.client.art.ArtAnims;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Trefferpause in Arius' Pose (nur Darstellung): Trifft einer seiner Schläge einen Spieler, steht die Pose-Zeit ein paar
 * Bilder still und holt danach auf (siehe {@link HitStop}). Greift nach dem vollständigen updateRenderState (am Ende von
 * getAndUpdateRenderState), damit Arius' Renderer und Modell (Paket V4) unberührt bleiben.
 */
@Mixin(EntityRenderer.class)
public abstract class AriusRendererHitStopMixin {

    @Inject(
        method = "getAndUpdateRenderState(Lnet/minecraft/entity/Entity;F)Lnet/minecraft/client/render/entity/state/EntityRenderState;",
        at = @At("RETURN")
    )
    private void hhaArius$hitStop(Entity entity, float tickDelta, CallbackInfoReturnable<EntityRenderState> cir) {
        if (!(entity instanceof AriusEntity) || !(cir.getReturnValue() instanceof AriusRenderState state)) return;
        float hold = HitStop.INSTANCE.offset(entity.getId(), ArtAnims.INSTANCE.getTicks() + tickDelta);
        if (hold > 0f) state.setPoseTime(state.getPoseTime() - hold);
    }
}

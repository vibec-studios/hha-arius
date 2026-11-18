package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.gear.client.GearClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seit 1.7.0: Solange die Resonanzleiste und die Abklingzeit-Icons über der Hotbar stehen, rückt die Aktionsleisten-
 * Meldung (z. B. Hinweise) darüber, statt von ihnen verdeckt zu werden (Crix).
 */
@Mixin(InGameHud.class)
public abstract class OverlayMessageMixin {

    @Inject(method = "renderOverlayMessage", at = @At("HEAD"))
    private void hha_arius$liftStart(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        context.getMatrices().pushMatrix();
        if (GearClient.getBearer()) context.getMatrices().translate(0f, -34f);
    }

    @Inject(method = "renderOverlayMessage", at = @At("RETURN"))
    private void hha_arius$liftEnd(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        context.getMatrices().popMatrix();
    }
}

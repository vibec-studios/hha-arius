package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.QteClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * QTE-Anzeige auch in Szenen: HHA bricht {@code InGameHud.render} während einer Kamerafahrt am Anfang ab (nur die
 * Kinobalken werden gezeichnet), damit liefen auch Fabric-HUD-Rückrufe nicht – der Prompt war unsichtbar. Dieser
 * Einstieg läuft mit niedrigerer Priorität vor HHAs Abbruch.
 */
@Mixin(value = InGameHud.class, priority = 500)
public abstract class QteHudMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void hha_arius$qte(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        dev.henny.hha_arius.client.fx.Subtitles.renderAlways(context);
        QteClient.render(context, tickCounter.getTickProgress(true));
    }
}

package dev.henny.hha_arius.mixin.client;

import dev.henny.hha.client.vfx.Palette;
import dev.henny.hha.logic.Vfx;
import dev.henny.hha_arius.client.fx.FrostPalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** In Eirholm nutzen die Effekte von Arius und Solvane nur die Frostfarben ({@link FrostPalette}). */
@Mixin(value = Palette.Companion.class, remap = false)
public abstract class FrostPaletteMixin {
    @Inject(method = {"of", "danger"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void hhaArius$frost(Vfx.Style style, CallbackInfoReturnable<Palette> cir) {
        Palette frost = FrostPalette.forStyle(style);
        if (frost != null) cir.setReturnValue(frost);
    }
}

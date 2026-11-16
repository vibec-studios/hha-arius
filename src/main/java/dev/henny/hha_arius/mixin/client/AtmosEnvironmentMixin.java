package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.Atmos;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeInterpolator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Eirholm: Wetter- und Lichtstimmung ({@link Atmos}) blendet Nebel, Himmel und Licht über. */
@Mixin(EnvironmentAttributeInterpolator.class)
public abstract class AtmosEnvironmentMixin {

    @SuppressWarnings("unchecked")
    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private <V> void hha_arius$atmos(EnvironmentAttribute<V> attribute, float tickProgress, CallbackInfoReturnable<V> cir) {
        V value = cir.getReturnValue();
        Object replaced = Atmos.override(attribute, value);
        if (replaced != value) {
            cir.setReturnValue((V) replaced);
        }
    }
}

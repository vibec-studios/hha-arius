package dev.henny.hha_arius.mixin.gear;

import dev.henny.hha_arius.gear.GearHooks;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Volles Eirholm-Set: immun gegen Gefrieren im Pulverschnee (Konzept 10.1, Passiv). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFreezeMixin {

    @Inject(method = "canFreeze", at = @At("HEAD"), cancellable = true)
    private void hhaArius$eirholmFreezeImmune(CallbackInfoReturnable<Boolean> cir) {
        if (GearHooks.freezeImmune((LivingEntity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}

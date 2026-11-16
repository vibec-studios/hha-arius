package dev.henny.hha_arius.mixin.gear;

import dev.henny.hha_arius.gear.GearHooks;
import net.minecraft.block.PowderSnowBlock;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Volles Eirholm-Set: Pulverschnee trägt (wie Lederstiefel). Läuft auf beiden Seiten, damit nichts ruckelt. */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {

    @Inject(method = "canWalkOnPowderSnow", at = @At("HEAD"), cancellable = true)
    private static void hhaArius$eirholmWalksOnPowderSnow(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (GearHooks.walksOnPowderSnow(entity)) {
            cir.setReturnValue(true);
        }
    }
}

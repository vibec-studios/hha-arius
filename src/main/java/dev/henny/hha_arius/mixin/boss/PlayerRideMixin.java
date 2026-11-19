package dev.henny.hha_arius.mixin.boss;

import dev.henny.hha_arius.boss.NivarisEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ritt auf Solvanes Rücken (seit 1.8.0): Schleichen wirft dich nicht ab – du hältst dich fest, bis sie landet.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerRideMixin {

    @Inject(method = "shouldDismount", at = @At("HEAD"), cancellable = true)
    private void hha_arius$holdOn(CallbackInfoReturnable<Boolean> cir) {
        if (((PlayerEntity) (Object) this).getVehicle() instanceof NivarisEntity) cir.setReturnValue(false);
    }
}

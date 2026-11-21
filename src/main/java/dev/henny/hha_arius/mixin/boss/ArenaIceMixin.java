package dev.henny.hha_arius.mixin.boss;

import dev.henny.hha_arius.arena.ArenaDimension;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.IceBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eis in Eirholm schmilzt nie (seit 1.11.0): Die Höhlen sind mit Laternen beleuchtet, und durchsichtiges Eis wird bei
 * Blocklicht über 11 sonst zu Wasser. Nur normales Eis; Frosteis (Fähigkeiten) taut weiter von selbst.
 */
@Mixin(IceBlock.class)
public abstract class ArenaIceMixin {

    @Inject(method = "melt", at = @At("HEAD"), cancellable = true)
    private void hhaArius$neverMeltInEirholm(BlockState state, World world, BlockPos pos, CallbackInfo ci) {
        if (state.isOf(Blocks.ICE) && ArenaDimension.INSTANCE.getWORLD().equals(world.getRegistryKey())) {
            ci.cancel();
        }
    }
}

package dev.henny.hha_arius.mixin.boss;

import dev.henny.hha_arius.arena.ArenaDimension;
import net.minecraft.block.BlockState;
import net.minecraft.block.SnowBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Schneeschichten bilden die Rampen des Jagdwegs: Auch sie tauen in Eirholm neben Laternen nicht weg (seit 1.11.0). */
@Mixin(SnowBlock.class)
public abstract class ArenaSnowMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void hhaArius$neverMeltInEirholm(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        if (ArenaDimension.INSTANCE.getWORLD().equals(world.getRegistryKey())) {
            ci.cancel();
        }
    }
}

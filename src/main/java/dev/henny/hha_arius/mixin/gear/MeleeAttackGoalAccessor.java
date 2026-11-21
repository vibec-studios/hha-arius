package dev.henny.hha_arius.mixin.gear;

import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Seit 1.10.0 (Paradeanzeige der Solvane): Ticks bis zum nächsten Nahkampfschlag eines Mobs. */
@Mixin(MeleeAttackGoal.class)
public interface MeleeAttackGoalAccessor {
    @Accessor("cooldown")
    int hhaArius$cooldown();
}

package dev.henny.hha_arius.mixin.gear;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Seit 1.10.0 (Paradeanzeige der Solvane): Zugriff auf die Ziele eines Mobs, um seinen Nahkampftakt zu lesen. */
@Mixin(MobEntity.class)
public interface MobGoalsAccessor {
    @Accessor("goalSelector")
    GoalSelector hhaArius$goals();
}

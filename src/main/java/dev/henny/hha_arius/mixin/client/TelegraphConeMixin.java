package dev.henny.hha_arius.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.henny.hha.client.vfx.Palette;
import dev.henny.hha.client.vfx.TelegraphConeEffect;
import dev.henny.hha.client.vfx.TelegraphEffect;
import dev.henny.hha.client.vfx.VfxDraw;
import dev.henny.hha_arius.client.fx.ClassicTelegraphs;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Renders Arius/Solvane cone telegraphs in Eirholm with the clean translucent HHA 0.8.0 frost glow style. */
@Mixin(value = TelegraphConeEffect.class, remap = false)
public abstract class TelegraphConeMixin extends TelegraphEffect {
    @Shadow @Final private Vec3d apex;
    @Shadow @Final private float radius;
    @Shadow @Final private Vec3d forward;
    @Shadow @Final private Vec3d side;
    @Shadow @Final private double half;
    @Shadow @Final private boolean full;
    @Shadow @Final private int segments;

    protected TelegraphConeMixin(Palette pal, boolean safe, int life) {
        super(pal, safe, life);
    }

    @WrapMethod(method = "render", require = 0)
    private void hhaArius$classicCone(VfxDraw d, float t, Operation<Void> original) {
        if (ClassicTelegraphs.active()) {
            ClassicTelegraphs.renderCone(d, t, getPal(), getLife(), apex, radius, forward, side, half, full, segments);
        } else {
            original.call(d, t);
        }
    }
}

package dev.henny.hha_arius.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.henny.hha.client.vfx.Palette;
import dev.henny.hha.client.vfx.TelegraphEffect;
import dev.henny.hha.client.vfx.TelegraphLineEffect;
import dev.henny.hha.client.vfx.VfxDraw;
import dev.henny.hha_arius.client.fx.ClassicTelegraphs;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Renders Arius/Solvane line telegraphs in Eirholm with the clean translucent HHA 0.8.0 frost glow style. */
@Mixin(value = TelegraphLineEffect.class, remap = false)
public abstract class TelegraphLineMixin extends TelegraphEffect {
    @Shadow @Final private Vec3d from;
    @Shadow @Final private Vec3d to;
    @Shadow @Final private float halfWidth;
    @Shadow @Final private Vec3d delta;
    @Shadow @Final private Vec3d dir;
    @Shadow @Final private Vec3d side;

    protected TelegraphLineMixin(Palette pal, boolean safe, int life) {
        super(pal, safe, life);
    }

    @WrapMethod(method = "render", require = 0)
    private void hhaArius$classicLine(VfxDraw d, float t, Operation<Void> original) {
        if (ClassicTelegraphs.active()) {
            ClassicTelegraphs.renderLine(d, t, getPal(), getLife(), from, to, halfWidth, delta, dir, side);
        } else {
            original.call(d, t);
        }
    }
}

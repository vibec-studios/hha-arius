package dev.henny.hha_arius.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.henny.hha.client.vfx.Palette;
import dev.henny.hha.client.vfx.TelegraphEffect;
import dev.henny.hha.client.vfx.TelegraphRingEffect;
import dev.henny.hha.client.vfx.VfxDraw;
import dev.henny.hha_arius.client.fx.ClassicTelegraphs;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Renders Arius/Solvane ring telegraphs in Eirholm with the clean translucent HHA 0.8.0 frost glow style. */
@Mixin(value = TelegraphRingEffect.class, remap = false)
public abstract class TelegraphRingMixin extends TelegraphEffect {
    @Shadow @Final private Vec3d center;
    @Shadow @Final private float radius;
    @Shadow @Final private float inner;
    @Shadow @Final private int segments;

    protected TelegraphRingMixin(Palette pal, boolean safe, int life) {
        super(pal, safe, life);
    }

    @WrapMethod(method = "render", require = 0)
    private void hhaArius$classicRing(VfxDraw d, float t, Operation<Void> original) {
        if (ClassicTelegraphs.active()) {
            ClassicTelegraphs.renderRing(d, t, getPal(), getSafe(), getLife(), center, radius, inner, segments);
        } else {
            original.call(d, t);
        }
    }
}

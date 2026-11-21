package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.CineClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kameras der Kampfszenen (seit 1.10.0), nach HHAs Cutscene-Kamera (höhere Priorität = später): Verfolgerkamera
 * ({@link CineClient}) und Kollisionsschutz – steht eine Szenenkamera in Fels oder Eis, rückt sie entlang der
 * Blickrichtung vor, bis sie frei ist.
 */
@Mixin(value = Camera.class, priority = 1500)
public abstract class CineCameraMixin {

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Shadow
    public abstract Vec3d getCameraPos();

    @Shadow
    public abstract float getYaw();

    @Shadow
    public abstract float getPitch();

    @Inject(method = "update", at = @At("TAIL"))
    private void hhaArius$cineCamera(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        CineClient.Pose pose = CineClient.camera(area, getCameraPos(), getYaw(), getPitch(), tickProgress);
        if (pose == null) return;
        setRotation(pose.yaw, pose.pitch);
        setPos(pose.pos);
    }
}

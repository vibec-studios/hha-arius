package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.CameraKick;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dezentes Kamerarucken bei schweren Treffern (nur Blickwinkel, abschaltbar mit /ariusc shake, ruht in Cutscenes). */
@Mixin(Camera.class)
public abstract class CameraKickMixin {

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    public abstract float getYaw();

    @Shadow
    public abstract float getPitch();

    @Inject(method = "update", at = @At("TAIL"))
    private void hhaArius$kick(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        float[] kick = CameraKick.offset(tickProgress);
        if (kick != null) setRotation(getYaw() + kick[0], getPitch() + kick[1]);
    }
}

package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.HuntCine;
import net.minecraft.client.render.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Jagd (seit 1.10.0): Kippen der Kamera (Dutch Tilt bei Einschlägen, Schwanken auf Hängebrücken) und Zittern (Grollen).
 * Ändert nur die Blickrotation, nicht Gieren/Nicken des Spielers; nach jedem setRotation (auch dem des Kamerareckens).
 */
@Mixin(Camera.class)
public abstract class HuntCameraMixin {

    @Shadow @Final private Quaternionf rotation;
    @Shadow @Final private Vector3f horizontalPlane;
    @Shadow @Final private Vector3f verticalPlane;
    @Shadow @Final private Vector3f diagonalPlane;
    @Shadow private float lastTickProgress;

    @Inject(method = "setRotation", at = @At("TAIL"))
    private void hhaArius$huntRoll(float yaw, float pitch, CallbackInfo ci) {
        float roll = HuntCine.roll(lastTickProgress);
        float[] jitter = HuntCine.jitter(lastTickProgress);
        if (Math.abs(roll) < 0.001f && jitter == null) return;
        float y = yaw + (jitter != null ? jitter[0] : 0f);
        float p = pitch + (jitter != null ? jitter[1] : 0f);
        float deg = (float) (Math.PI / 180.0);
        rotation.rotationYXZ((float) Math.PI - y * deg, -p * deg, roll * deg);
        horizontalPlane.set(0f, 0f, -1f).rotate(rotation);
        verticalPlane.set(0f, 1f, 0f).rotate(rotation);
        diagonalPlane.set(-1f, 0f, 0f).rotate(rotation);
    }
}

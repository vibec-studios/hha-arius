package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.PunishCam;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seit 1.10.0: persönliche Kampfkamera (Konter-Bestrafung). Solange [PunishCam] aktiv ist, steht die Kamera seitlich
 * und zeigt Arius und den eigenen Spieler (dritte Person); weich ein- und ausgeblendet.
 */
@Mixin(Camera.class)
public abstract class PunishCameraMixin {

    @Shadow
    private boolean thirdPerson;

    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    public abstract float getYaw();

    @Shadow
    public abstract float getPitch();

    @Shadow
    public abstract net.minecraft.util.math.Vec3d getCameraPos();

    @Inject(method = "update", at = @At("TAIL"))
    private void hhaArius$punishCam(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        double[] r = PunishCam.apply(getCameraPos(), getYaw(), getPitch(), tickProgress);
        if (r == null) return;
        setPos(r[0], r[1], r[2]);
        setRotation((float) r[3], (float) r[4]);
        this.thirdPerson = true;
    }
}

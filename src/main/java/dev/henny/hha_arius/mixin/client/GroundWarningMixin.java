package dev.henny.hha_arius.mixin.client;

import dev.henny.hha.client.vfx.VfxManager;
import dev.henny.hha.net.VfxPayload;
import dev.henny.hha_arius.client.fx.GroundWarnings;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VfxManager.class, remap = false)
public abstract class GroundWarningMixin {
    @Inject(method = "handle", at = @At("HEAD"))
    private void hhaArius$outline(VfxPayload packet, ClientWorld world, CallbackInfo ci) {
        GroundWarnings.receive(packet, world);
    }
}

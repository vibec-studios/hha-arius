package dev.henny.hha_arius.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.henny.hha.client.vfx.VfxManager;
import dev.henny.hha.net.VfxPayload;
import dev.henny.hha_arius.client.fx.FrostPalette;
import dev.henny.hha_arius.client.fx.GroundWarnings;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;

/** Use the HHA renderer and palette that drew the telegraphs in Arius 1.11. */
@Mixin(value = VfxManager.class, remap = false)
public abstract class GroundWarningMixin {
    @WrapMethod(method = "handle", require = 0)
    private void hhaArius$groundWarning(VfxPayload packet, ClientWorld world, Operation<Void> original) {
        if (GroundWarnings.observe(packet, world)) {
            FrostPalette.withStockTelegraph(() -> original.call(packet, world));
        } else {
            original.call(packet, world);
        }
    }
}

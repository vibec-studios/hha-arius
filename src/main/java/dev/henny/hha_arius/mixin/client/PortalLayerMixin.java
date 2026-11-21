package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.portal.PortalBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.BlockRenderLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Das Tor nach Eirholm ist durchscheinend (wie das Netherportal), ohne eigene Ebenen-Registrierung. */
@Mixin(BlockRenderLayers.class)
public abstract class PortalLayerMixin {
    @Inject(method = "getBlockLayer", at = @At("HEAD"), cancellable = true)
    private static void hhaArius$gateLayer(BlockState state, CallbackInfoReturnable<BlockRenderLayer> cir) {
        if (PortalBlocks.INSTANCE.isGate(state)) cir.setReturnValue(BlockRenderLayer.TRANSLUCENT);
    }
}

package dev.henny.hha_arius.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.henny.hha_arius.client.fx.Atmos;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.AtmosphericFogModifier;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.render.fog.FogModifier;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Eirholm: Sturmnebel ({@link Atmos}) auch mit NoFog/ClearViews. Die greifen innerhalb von
 * {@link AtmosphericFogModifier#applyStartEndModifier} ein; hier wird der Aufruf umschlossen und die Sturmdistanz danach
 * gesetzt. Wasser-, Lava-, Blindheits- und Pulverschnee-Nebel bleiben unberührt (anderer Modifier).
 */
@Mixin(FogRenderer.class)
public abstract class AtmosFogRendererMixin {

    @WrapOperation(
            method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/fog/FogModifier;applyStartEndModifier(Lnet/minecraft/client/render/fog/FogData;Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/world/ClientWorld;FLnet/minecraft/client/render/RenderTickCounter;)V")
    )
    private void hha_arius$stormFog(FogModifier modifier, FogData data, Camera camera, ClientWorld world, float viewDistance,
                                    RenderTickCounter tickCounter, Operation<Void> original) {
        original.call(modifier, data, camera, world, viewDistance, tickCounter);
        if (modifier instanceof AtmosphericFogModifier) {
            Atmos.enforceFog(data);
        }
    }
}

package dev.henny.hha_arius.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.henny.hha_arius.client.fx.Atmos;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.AtmosphericFogModifier;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.render.fog.FogModifier;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Eirholm: Sturmnebel ({@link Atmos}) auch mit Nebel-Abschaltern wie NoFog, ClearViews, bactromod oder Sodium Extra.
 * <p>
 * Die greifen im Atmosphären-Modifier ein (NoFog, ClearViews, Sodium Extra) oder direkt danach in
 * {@code FogRenderer.applyFog} nach dem Setzen der Render-Distanz (bactromod, Sodium Extra). Deshalb merkt sich
 * {@link #hha_arius$atmospheric} nur, ob der Atmosphären-Nebel gewählt wurde, und die Sturmdistanz wird erst unmittelbar
 * vor dem Auslesen der Nebelwerte gesetzt – nach all diesen Eingriffen und bevor Vanilla den Nebel-Puffer schreibt und
 * Sodium die Werte für den Terrain-Nebel übernimmt. Wasser-, Lava-, Blindheits- und Pulverschnee-Nebel bleiben unberührt.
 */
@Mixin(FogRenderer.class)
public abstract class AtmosFogRendererMixin {

    @Unique
    private boolean hha_arius$atmospheric;

    @WrapOperation(
            method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/fog/FogModifier;applyStartEndModifier(Lnet/minecraft/client/render/fog/FogData;Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/world/ClientWorld;FLnet/minecraft/client/render/RenderTickCounter;)V")
    )
    private void hha_arius$markAtmospheric(FogModifier modifier, FogData data, Camera camera, ClientWorld world, float viewDistance,
                                           RenderTickCounter tickCounter, Operation<Void> original) {
        original.call(modifier, data, camera, world, viewDistance, tickCounter);
        hha_arius$atmospheric = modifier instanceof AtmosphericFogModifier;
    }

    @Inject(
            method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/render/fog/FogData;environmentalStart:F", opcode = Opcodes.GETFIELD)
    )
    private void hha_arius$stormFog(Camera camera, int viewDistance, RenderTickCounter tickCounter, float skyDarkness, ClientWorld world,
                                    CallbackInfoReturnable<Vector4f> cir, @Local FogData data) {
        if (hha_arius$atmospheric) {
            Atmos.enforceFog(data);
        }
        hha_arius$atmospheric = false;
    }
}

package dev.henny.hha_arius.mixin.gear.client;

import dev.henny.hha_arius.gear.client.EirholmGlow;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.equipment.EquipmentModel;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.equipment.EquipmentAsset;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leuchtnähte der Eirholm-Rüstung: Nach den normalen Ebenen von {@code hha_arius:eirholm} wird zusätzlich
 * {@code textures/entity/equipment/<humanoid|humanoid_leggings>/eirholm_glow.png} mit der "eyes"-Ebene gezeichnet
 * (lichtunabhängig, wie Spinnenaugen), getönt mit einem sanften Puls 70–100 % in 3 s.
 *
 * <p>Absichtlich nur ein {@code @Inject} am Ende (kein {@code @Redirect}), damit es mit anderen Rüstungs-Mixins
 * (z. B. hha-blood) nicht kollidiert. Die Leuchtnähte gehören deshalb NICHT als Ebene in {@code equipment/eirholm.json}.
 */
@Mixin(EquipmentRenderer.class)
public abstract class EquipmentRendererGlowMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
        at = @At("TAIL")
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void hhaArius$eirholmGlow(
        EquipmentModel.LayerType layerType, RegistryKey<EquipmentAsset> assetKey, Model model, Object state, ItemStack stack,
        MatrixStack matrices, OrderedRenderCommandQueue queue, int light, @Nullable Identifier textureId, int outlineColor,
        int initialOrder, CallbackInfo ci
    ) {
        if (!EirholmGlow.isEirholm(assetKey.getValue())) return;
        Identifier glow = EirholmGlow.texture(layerType.asString());
        if (glow == null) return;
        // Basis, Glanz und Trim belegen höchstens initialOrder .. initialOrder + 2; die Leuchtebene kommt danach.
        queue.getBatchingQueue(initialOrder + 3).submitModel(
            model, state, matrices, RenderLayers.eyes(glow), light, OverlayTexture.DEFAULT_UV, EirholmGlow.tint(), null, outlineColor, null
        );
    }
}

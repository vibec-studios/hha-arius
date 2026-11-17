package dev.henny.hha_arius.mixin.gear.client;

import dev.henny.hha_arius.client.model.armor.ArmorRender;
import net.minecraft.client.render.entity.equipment.EquipmentModel;
import net.minecraft.client.render.entity.feature.CapeFeatureRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Umhang über der Drachenherz-Wacht: Vanilla schiebt den Umhang nur nach hinten, wenn die Brustplatte eine
 * {@code humanoid}-Ebene hat. Die Eirholm-Rüstung hat keine Ebenen mehr (eigenes 3D-Modell, ArmorFeature), steht hinten
 * aber genauso weit ab – also bekommt der Umhang denselben Abstand wie über Vanilla-Rüstung.
 */
@Mixin(CapeFeatureRenderer.class)
public abstract class CapeFeatureArmorMixin {

    @Inject(method = "hasCustomModelForLayer", at = @At("HEAD"), cancellable = true)
    private void hhaArius$eirholmCape(ItemStack stack, EquipmentModel.LayerType layerType, CallbackInfoReturnable<Boolean> cir) {
        if (layerType == EquipmentModel.LayerType.HUMANOID && ArmorRender.pushesCape(stack)) {
            cir.setReturnValue(true);
        }
    }
}

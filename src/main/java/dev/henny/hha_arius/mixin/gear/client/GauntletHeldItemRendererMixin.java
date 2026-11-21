package dev.henny.hha_arius.mixin.gear.client;

import dev.henny.hha_arius.gear.client.art.GauntletFirstPerson;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Erste Person: Mit Arius' Kampfhandschuhen zeichnet {@link GauntletFirstPerson} beide Arme samt Handschuhen und
 * unterdrückt die Nebenhand; mit Solvane nur während Haltung und Schnitt des Klaren Schnitts.
 */
@Mixin(HeldItemRenderer.class)
public abstract class GauntletHeldItemRendererMixin {

    @Inject(
        method = "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hhaArius$gauntlets(
        AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item,
        float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci
    ) {
        // Seit 1.11.0: In der Kamera der Konter-Bestrafung keine Hand und kein Gegenstand der ersten Person.
        if (dev.henny.hha_arius.client.fx.PunishCam.hidesHands()) {
            ci.cancel();
            return;
        }
        if (player.isUsingSpyglass()) return;
        if (GauntletFirstPerson.render((HeldItemRenderer) (Object) this, player, tickProgress, hand, item, equipProgress, matrices, queue, light)) {
            ci.cancel();
        }
    }

    /** Normale Schläge mit Solvane: Klinge für die Schwertspur der ersten Person mitschreiben (direkt vor renderItem). */
    @Inject(
        method = "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V"
        ),
        require = 0
    )
    private void hhaArius$solvaneTrail(
        AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item,
        float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci
    ) {
        GauntletFirstPerson.vanillaSolvane(player, hand, item, matrices, queue, tickProgress);
    }
}

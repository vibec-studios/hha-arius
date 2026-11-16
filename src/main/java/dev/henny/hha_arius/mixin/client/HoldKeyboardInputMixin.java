package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.HoldClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inszenierter Treffer: Während der Spieler gehalten wird, gibt es keine Bewegungseingabe. */
@Mixin(KeyboardInput.class)
public abstract class HoldKeyboardInputMixin extends Input {

    @Inject(method = "tick", at = @At("TAIL"))
    private void hha_arius$hold(CallbackInfo ci) {
        if (HoldClient.isHeld()) {
            this.playerInput = PlayerInput.DEFAULT;
            this.movementVector = Vec2f.ZERO;
        }
    }
}

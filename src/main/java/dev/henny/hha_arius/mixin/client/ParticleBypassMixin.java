package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.arena.ArenaDimension;
import dev.henny.hha_arius.client.fx.AriusClientConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensures Arius / Eirholm / HHA combat particles still spawn even when client optimization mods such as Sodium Extra
 * disable vanilla particles globally ("particle_settings.particles = false"). Runs at priority 500 before Sodium
 * Extra's default-priority HEAD cancellation, and works cleanly both with and without Sodium Extra installed.
 * Still respects the player's own {@code /ariusc fx 0} toggle.
 */
@Mixin(value = ParticleManager.class, priority = 500)
public abstract class ParticleBypassMixin {

    @Shadow
    @Nullable
    protected abstract <T extends ParticleEffect> Particle createParticle(
        T parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ
    );

    @Shadow
    public abstract void addParticle(Particle particle);

    @Inject(
        method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hhaArius$bypassExternalParticleFilter(
        ParticleEffect parameters,
        double x, double y, double z,
        double velocityX, double velocityY, double velocityZ,
        CallbackInfoReturnable<Particle> cir
    ) {
        if (AriusClientConfig.INSTANCE.getFx() <= 0.01f) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        boolean inEirholm = world != null && ArenaDimension.INSTANCE.getWORLD().equals(world.getRegistryKey());
        if (!inEirholm) {
            Identifier id = Registries.PARTICLE_TYPE.getId(parameters.getType());
            if (id == null || (!"hha".equals(id.getNamespace()) && !"hha_arius".equals(id.getNamespace()))) {
                return;
            }
        }
        Particle particle = this.createParticle(parameters, x, y, z, velocityX, velocityY, velocityZ);
        if (particle != null) {
            this.addParticle(particle);
        }
        cir.setReturnValue(particle);
    }
}

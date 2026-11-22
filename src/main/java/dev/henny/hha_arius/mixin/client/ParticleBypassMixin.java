package dev.henny.hha_arius.mixin.client;

import dev.henny.hha_arius.client.fx.ParticleGate;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium Extra kann alle Partikel abschalten; dann fehlen dem Kampf Funken, Schneestaub und Splitter. Nur in Eirholm und
 * nur für die Partikelarten des Kampfes läuft dieser Weg vor Sodium Extra (Priorität 500). Der Partikel entsteht genau
 * einmal, weil das Setzen des Rückgabewerts die Methode beendet. {@link ParticleGate} entscheidet.
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
    private void hhaArius$fightParticles(
        ParticleEffect parameters,
        double x, double y, double z,
        double velocityX, double velocityY, double velocityZ,
        CallbackInfoReturnable<Particle> cir
    ) {
        if (!ParticleGate.bypass(parameters)) return;
        Particle particle = this.createParticle(parameters, x, y, z, velocityX, velocityY, velocityZ);
        if (particle != null) this.addParticle(particle);
        cir.setReturnValue(particle);
    }
}

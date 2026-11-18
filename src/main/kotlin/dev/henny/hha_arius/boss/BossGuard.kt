package dev.henny.hha_arius.boss

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.damage.DamageSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
import kotlin.math.cos

/**
 * Seit 1.7.0 (Crix: „Man soll Arius' Attacken auch mit einem normalen Schild blocken können“): Wer einen Schild hebt,
 * fängt Treffer von Arius und Solvane von vorn (±70°) ab. Der Schild nimmt Haltbarkeit; schwere Treffer (ab 8 Schaden,
 * der Null-Schnitt immer) schlagen ihn wie eine Axt für 5 s aus der Deckung.
 */
object BossGuard {

    fun isBoss(source: DamageSource): Boolean =
        source.attacker is AriusEntity || source.attacker is NivarisEntity ||
            source.typeRegistryEntry.key.map { it.value.namespace == "hha_arius" }.orElse(false)

    fun init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            val p = entity as? ServerPlayerEntity ?: return@register true
            if (!isBoss(source) || !p.isBlocking) return@register true
            val from = source.position ?: source.attacker?.entityPos ?: return@register true
            val dir = from.subtract(p.eyePos).let { Vec3d(it.x, 0.0, it.z) }
            val look = p.rotationVector.let { Vec3d(it.x, 0.0, it.z) }
            if (dir.lengthSquared() < 1e-6 || look.lengthSquared() < 1e-6) return@register true
            if (dir.normalize().dotProduct(look.normalize()) < cos(Math.toRadians(70.0))) return@register true
            val shield = p.activeItem
            val slot = if (p.activeHand == Hand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND
            shield.damage(maxOf(1, amount.toInt()), p, slot)
            val world = p.entityWorld
            val heavy = amount >= 8f || source.typeRegistryEntry.key.map { it.value.path == "null_cut" }.orElse(false)
            if (heavy) {
                p.itemCooldownManager.set(shield, 100)
                p.stopUsingItem()
                world.playSound(null, p.blockPos, SoundEvents.ITEM_SHIELD_BREAK.value(), SoundCategory.PLAYERS, 1f, 0.8f)
            } else {
                world.playSound(null, p.blockPos, SoundEvents.ITEM_SHIELD_BLOCK.value(), SoundCategory.PLAYERS, 1f, 1f)
            }
            false
        }
    }
}

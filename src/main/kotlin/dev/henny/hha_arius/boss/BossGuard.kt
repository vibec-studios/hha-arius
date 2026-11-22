package dev.henny.hha_arius.boss

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import kotlin.math.cos

/**
 * Seit 1.7.0 (Crix: „Man soll Arius' Attacken auch mit einem normalen Schild blocken können“): Wer einen Schild hebt,
 * fängt Treffer von Arius und Solvane von vorn (±70° / ±90° Vanilla) ab. Vanilla-Schilde blocken genau einen Treffer
 * und werden sofort wie durch eine Axt für 5 Sekunden gesperrt (Abklingzeit + Bruchklang + Haltbarkeit).
 */
object BossGuard {

    fun isBoss(source: DamageSource): Boolean =
        source.attacker is AriusEntity || source.attacker is NivarisEntity ||
            source.typeRegistryEntry.key.map { it.value.namespace == "hha_arius" }.orElse(false)

    fun init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            val p = entity as? ServerPlayerEntity ?: return@register true
            if (amount <= 0f || !isBoss(source) || !p.isBlocking) return@register true
            val from = source.position ?: source.attacker?.entityPos ?: return@register true
            val dir = from.subtract(p.eyePos).let { Vec3d(it.x, 0.0, it.z) }
            val look = p.rotationVector.let { Vec3d(it.x, 0.0, it.z) }
            if (dir.lengthSquared() < 1e-6 || look.lengthSquared() < 1e-6) return@register true
            val shield = p.activeItem
            if (shield.isEmpty || p.itemCooldownManager.isCoolingDown(shield)) return@register true
            val arc = if (shield.isOf(Items.SHIELD)) 90.0 else 70.0
            if (dir.normalize().dotProduct(look.normalize()) < cos(Math.toRadians(arc))) return@register true
            val block = shield.get(DataComponentTypes.BLOCKS_ATTACKS) ?: return@register true
            val world = p.entityWorld as ServerWorld
            val hand = p.activeHand
            val vanilla = shield.isOf(Items.SHIELD)
            val disable = vanilla || amount >= 8f || source.isOf(Damage.Kind.NULL_CUT.key)
            // Durability & stats apply to whichever hand (mainhand or offhand) is actively blocking.
            block.onShieldHit(world, shield, p, hand, amount)
            if (disable) {
                // Immediately clears active item + sets 5s (100t) axe-style cooldown so multi-hit follow-ups penetrate.
                block.applyShieldCooldown(world, p, 5f, shield)
                if (block.disableSound().isEmpty) {
                    world.playSound(null, p.x, p.y, p.z, SoundEvents.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 0.9f, 0.85f)
                }
            } else {
                block.playBlockSound(world, p)
            }
            if (source.attacker is AriusEntity) Encounters.fightOf(p)?.onShieldBlock(p)
            false
        }
    }
}

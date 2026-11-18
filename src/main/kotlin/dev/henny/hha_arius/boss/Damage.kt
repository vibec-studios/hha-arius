package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.Entity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageType
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld

/**
 * Schaden der Bosse an Spielern (Konzept 9.3). Die eigenen Schadensarten umgehen Rüstung und Verzauberungen; dieses
 * Objekt rechnet beides gedeckelt selbst ein: Rüstung höchstens 30 %, Schutz höchstens 20 %. Resistenz wirkt normal.
 */
object Damage {

    enum class Kind(val path: String) {
        SOLVANE("solvane"), FROST_BREATH("frost_breath"), PRESSURE("pressure"), NULL_CUT("null_cut"), IMPACT("impact");

        val key: RegistryKey<DamageType> = RegistryKey.of(RegistryKeys.DAMAGE_TYPE, HhaArius.id(path))
    }

    private val ARMOR_SLOTS = arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    /** Anteil, den Rüstung und Schutz-Verzauberung abziehen (0..0,44). */
    fun reduction(player: ServerPlayerEntity): Double {
        val armor = player.armor.toDouble()
        val toughness = player.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS)
        val fromArmor = (armor * 0.012 + toughness * 0.004).coerceAtMost(0.30)
        val world = player.entityWorld as ServerWorld
        val protection = world.registryManager.getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION)
        var levels = 0
        for (slot in ARMOR_SLOTS) levels += EnchantmentHelper.getLevel(protection, player.getEquippedStack(slot))
        val fromProtection = (levels * 0.0125).coerceAtMost(0.20)
        return 1.0 - (1.0 - fromArmor) * (1.0 - fromProtection)
    }

    /** Fügt [player] den Grundschaden [base] zu (schon mit Konfig-Skalierung [scale]). Gibt den echten Schaden zurück. */
    fun hit(player: ServerPlayerEntity, attacker: Entity?, kind: Kind, base: Float, scale: Float): Float {
        val world = player.entityWorld as? ServerWorld ?: return 0f
        val amount = (base * scale * (1.0 - reduction(player))).toFloat()
        if (amount <= 0f) return 0f
        val source = if (attacker != null) world.damageSources.create(kind.key, attacker) else world.damageSources.create(kind.key)
        player.timeUntilRegen = 0
        // Abgefangen (z. B. Yang-Schild)? Dann zählt der Treffer nicht (kein Frostaufbau).
        return if (player.damage(world, source, amount)) amount else 0f
    }
}

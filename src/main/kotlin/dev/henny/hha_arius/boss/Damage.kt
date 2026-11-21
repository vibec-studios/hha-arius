package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.Entity
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageType
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.item.Items
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

/**
 * Seit 1.11.0: Woher ein Treffer eines Spielers stammt. Fähigkeiten laufen über denselben Schadenstyp wie normale Schläge,
 * deshalb markieren die Aufrufer sie hier für die Dauer des Treffers.
 */
object PlayerHit {
    enum class Kind { NONE, ABILITY, GAUNTLET }

    @JvmField
    var current = Kind.NONE

    inline fun <T> marked(kind: Kind, block: () -> T): T {
        val previous = current
        current = kind
        try {
            return block()
        } finally {
            current = previous
        }
    }
}

/**
 * Seit 1.11.0: Rechnet Spielertreffer auf die Bosse um. Zauber, Rüstungsfähigkeiten und Handschuhe zählen mehr (nur gegen
 * diese Bosse, PvP und andere Gegner bleiben unberührt). Der Streitkolben wird gedeckelt: Sein Sturzschaden wächst mit der
 * Fallhöhe unbegrenzt, ein einzelner Treffer darf aber nicht mehr als ein starker Schwerthieb machen, und je Spieler zählt
 * in [WINDOW] Ticks nur eine begrenzte Summe. Normale Treffer ohne Sturz liegen unter der Grenze.
 */
class BossBurst {
    private val maceHits = HashMap<java.util.UUID, ArrayDeque<Pair<Long, Float>>>()

    fun scale(player: ServerPlayerEntity, source: DamageSource, amount: Float, now: Long): Float = when {
        PlayerHit.current == PlayerHit.Kind.ABILITY || source.isOf(DamageTypes.INDIRECT_MAGIC) -> amount * BossConfig.abilityDamage()
        PlayerHit.current == PlayerHit.Kind.GAUNTLET -> amount * BossConfig.gauntletDamage()
        source.source === player && player.mainHandStack.isOf(Items.MACE) -> capMace(player, amount, now)
        else -> amount
    }

    private fun capMace(player: ServerPlayerEntity, amount: Float, now: Long): Float {
        val log = maceHits.getOrPut(player.uuid) { ArrayDeque() }
        while (log.isNotEmpty() && log.first().first <= now - WINDOW) log.removeFirst()
        val room = (BossConfig.maceBurstCap() - log.sumOf { it.second.toDouble() }.toFloat()).coerceAtLeast(0f)
        val dealt = minOf(amount, BossConfig.maceHitCap(), room)
        if (dealt > 0f) log.addLast(now to dealt)
        return dealt
    }

    companion object {
        const val WINDOW = 60L
    }
}

package dev.henny.hha_arius.gear

import dev.henny.hha.logic.Cooldowns
import dev.henny.hha.logic.Vfx
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.consume.UseAction
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.world.World
import java.util.UUID

/**
 * Solvane (Abbild), Konzept 10.2: Timing-Schwert. „Klare Schneide“ belohnt nur voll aufgeladene Treffer, Rechtsklick
 * hält 8 Ticks eine Konterhaltung. Funktioniert ohne Set; mit Set füllt es die Resonanz am schnellsten.
 */
class SolvaneItem(settings: Settings) : Item(settings) {

    override fun getBonusAttackDamage(target: Entity, baseAttackDamage: Float, damageSource: DamageSource): Float =
        SolvaneEdge.bonusDamage(target, damageSource)

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        if (world is ServerWorld && user is ServerPlayerEntity) {
            if (!GearConfig.on("solvane_counter")) return ActionResult.PASS
            if (!dev.henny.hha_arius.gear.art.Focus.isFree(user)) return ActionResult.FAIL
            SolvaneCounter.start(user, hand)
        }
        user.setCurrentHand(hand)
        return ActionResult.CONSUME
    }

    override fun getUseAction(stack: ItemStack): UseAction = UseAction.BLOCK

    override fun getMaxUseTime(stack: ItemStack, user: LivingEntity): Int = GearConfig.ticks("solvane_counter_ticks").coerceAtLeast(1)

    override fun finishUsing(stack: ItemStack, world: World, user: LivingEntity): ItemStack {
        if (user is ServerPlayerEntity) SolvaneCounter.finish(user, stack)
        return stack
    }

    override fun onStoppedUsing(stack: ItemStack, world: World, user: LivingEntity, remainingUseTicks: Int): Boolean {
        if (user is ServerPlayerEntity) SolvaneCounter.finish(user, stack)
        return false
    }
}

/** „Klare Schneide“ und die Resonanzgewinne aus Nahkampftreffern und perfekten Schildblöcken. */
object SolvaneEdge {

    /** Angriff dieses Ticks, gemerkt bevor Vanilla die Aufladung zurücksetzt. */
    private class Pending(val targetId: Int, val tick: Long, val full: Boolean, val solvane: Boolean, val weapon: Boolean) {
        var bonusApplied = false
        var shatter = false
        var counted = false
    }

    private class Chain(var targetId: Int, var count: Int, var lastTick: Long)

    private val pending = HashMap<UUID, Pending>()
    private val chains = HashMap<UUID, Chain>()
    private val lastPerfectRaise = HashMap<UUID, Long>()

    /** Kette verfällt, wenn so lange kein voller Treffer am selben Ziel kam. */
    private const val CHAIN_TIMEOUT = 100L

    fun init() {
        AttackEntityCallback.EVENT.register { player, world, _, entity, _ ->
            if (!world.isClient && player is ServerPlayerEntity && entity is LivingEntity) {
                val stack = player.mainHandStack
                val full = player.getAttackCooldownProgress(0.5f) >= GearConfig.float("resonance_full_charge")
                pending[player.uuid] = Pending(
                    entity.id, GearTasks.now, full,
                    solvane = stack.isOf(GearItems.SOLVANE),
                    weapon = stack.contains(DataComponentTypes.WEAPON),
                )
            }
            ActionResult.PASS
        }

        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, blocked ->
            onAttackLanded(entity, source, alive = true)
            // Nur Treffer von Wesen zählen (kein Fall-, Feuer- oder Erstickungsschaden).
            if (entity is ServerPlayerEntity && source.attacker != null && source.attacker !== entity) {
                onPlayerHurt(entity, damageTaken, blocked)
            }
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source ->
            onAttackLanded(entity, source, alive = false)
            chains.values.removeIf { it.targetId == entity.id }
            if (entity is ServerPlayerEntity) Resonance.reset(entity)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val id = handler.player.uuid
            pending.remove(id); chains.remove(id); lastPerfectRaise.remove(id)
        }
    }

    /** Vom Schwert aus [PlayerEntity.attack]: Bonus nur für volle Ladung, jeder n-te volle Treffer zerspringt. */
    fun bonusDamage(target: Entity, source: DamageSource): Float {
        val player = source.attacker as? ServerPlayerEntity ?: return 0f
        if (source.source !== player) return 0f
        val p = pending[player.uuid] ?: return 0f
        if (p.tick != GearTasks.now || p.targetId != target.id || !p.full || !p.solvane || p.bonusApplied) return 0f
        p.bonusApplied = true

        val chain = chains[player.uuid]
        val count = if (chain != null && chain.targetId == target.id && GearTasks.now - chain.lastTick <= CHAIN_TIMEOUT) {
            chain.count + 1
        } else {
            1
        }
        chains[player.uuid] = Chain(target.id, count, GearTasks.now)

        var bonus = GearConfig.float("solvane_frost_damage")
        val every = GearConfig.ticks("solvane_shatter_every")
        if (every > 0 && count % every == 0) {
            bonus += GearConfig.float("solvane_shatter_damage")
            p.shatter = true
        }
        return bonus
    }

    private fun onAttackLanded(entity: LivingEntity, source: DamageSource, alive: Boolean) {
        val player = source.attacker as? ServerPlayerEntity ?: return
        if (source.source !== player) return
        val p = pending[player.uuid] ?: return
        if (p.counted || p.tick != GearTasks.now || p.targetId != entity.id) return
        p.counted = true
        Resonance.markCombat(player)
        if (!p.full || !GearCombat.worthy(player, entity)) return
        when {
            p.solvane -> Resonance.gain(player, GearConfig.num("resonance_gain_solvane"))
            p.weapon -> Resonance.gain(player, GearConfig.num("resonance_gain_melee"))
        }
        if (p.shatter && alive) shatter(player, entity)
    }

    /** Rauhreif zerspringt: kurze Langsamkeit, ein Splitterklang, ein kleiner weißer Riss. */
    private fun shatter(player: ServerPlayerEntity, target: LivingEntity) {
        val world = player.entityWorld
        target.addStatusEffect(
            StatusEffectInstance(StatusEffects.SLOWNESS, GearConfig.ticks("solvane_shatter_slow_ticks"), 0, false, true, true),
            player,
        )
        val center = target.entityPos.add(0.0, target.height * 0.55, 0.0)
        GearVfx.flare(world, Vfx.Style.HEAVEN, center, 0.9f, 6)
        world.spawnParticles(ParticleTypes.SNOWFLAKE, center.x, center.y, center.z, 8, 0.25, 0.3, 0.25, 0.04)
        GearSounds.play(world, center, "cut.split", SoundEvents.BLOCK_GLASS_BREAK, 0.8f, 1.4f)
    }

    private fun onPlayerHurt(player: ServerPlayerEntity, damageTaken: Float, blocked: Boolean) {
        Resonance.markCombat(player)
        if (blocked) {
            val active = player.activeItem
            if (!player.isUsingItem || active.isOf(GearItems.SOLVANE) || !active.contains(DataComponentTypes.BLOCKS_ATTACKS)) return
            val useTime = player.itemUseTime
            if (useTime > GearConfig.ticks("resonance_perfect_block_ticks")) return
            // Ein Gewinn je Anheben des Schildes, auch wenn mehrere Pfeile im selben Fenster abprallen.
            val raise = GearTasks.now - useTime
            if (lastPerfectRaise[player.uuid] == raise) return
            lastPerfectRaise[player.uuid] = raise
            Resonance.gain(player, GearConfig.num("resonance_gain_counter"))
        } else if (damageTaken > 0f) {
            Resonance.gain(player, GearConfig.num("resonance_gain_hurt"))
        }
    }
}

/** Konterhaltung: 8 Ticks, wehrt einen Nahkampftreffer oder ein Geschoss ab. */
object SolvaneCounter {

    private class Window(val until: Long)

    private val windows = HashMap<UUID, Window>()

    fun init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (entity is ServerPlayerEntity) !tryCounter(entity, source) else true
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> windows.remove(handler.player.uuid) }
    }

    fun start(player: ServerPlayerEntity, hand: Hand) {
        windows[player.uuid] = Window(GearTasks.now + GearConfig.ticks("solvane_counter_ticks"))
        GearSounds.play(player.entityWorld, player.entityPos, "arius.guard", SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), 0.5f, 1.5f)
    }

    /** Haltung endet ohne Treffer (Zeit um oder losgelassen): längere Abklingzeit. */
    fun finish(player: ServerPlayerEntity, stack: ItemStack) {
        if (windows.remove(player.uuid) == null) return
        Cooldowns.set(player, stack, GearConfig.key("solvane_counter_cooldown_fail"))
    }

    private fun tryCounter(player: ServerPlayerEntity, source: DamageSource): Boolean {
        val window = windows[player.uuid] ?: return false
        if (GearTasks.now > window.until) return false
        if (!player.isUsingItem || !player.activeItem.isOf(GearItems.SOLVANE)) return false
        // Seit 1.7.0 auch gegen Bosse (deren Treffer „ignorieren Schilde“) und jeden Mob; nur Explosionen nicht.
        if (source.isIn(DamageTypeTags.IS_EXPLOSION)) return false
        val boss = dev.henny.hha_arius.boss.BossGuard.isBoss(source)
        if (!boss && source.isIn(DamageTypeTags.BYPASSES_SHIELD)) return false

        val direct = source.source
        val attacker = source.attacker
        val world = player.entityWorld
        when {
            direct is ProjectileEntity -> {
                val at = direct.entityPos
                direct.discard()
                world.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, 6, 0.15, 0.15, 0.15, 0.03)
            }
            attacker is LivingEntity -> {
                // Gegenschlag: Wer in Reichweite ist, bekommt einen vollen Hieb (Bosse über den normalen Angriffsweg).
                if (attacker.squaredDistanceTo(player) <= 36.0) {
                    if (boss) player.attack(attacker)
                    else {
                        attacker.timeUntilRegen = 0
                        attacker.damage(world, world.damageSources.playerAttack(player), (player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.ATTACK_DAMAGE) * 1.5).toFloat())
                        GearCombat.push(attacker, player.entityPos, GearConfig.num("solvane_counter_knockback"), 0.25)
                        Frostbite.add(attacker, 25f)
                    }
                }
            }
            else -> if (!boss) return false
        }

        val stack = player.activeItem
        windows.remove(player.uuid)
        Cooldowns.set(player, stack, GearConfig.key("solvane_counter_cooldown_success"))
        Resonance.gain(player, GearConfig.num("resonance_gain_counter"))
        val look = player.rotationVector
        val at = player.eyePos.add(look.multiply(0.8)).add(0.0, -0.3, 0.0)
        GearVfx.flare(world, Vfx.Style.HEAVEN, at, 0.8f, 6)
        GearSounds.play(world, at, "solvane.parry", SoundEvents.ITEM_SHIELD_BLOCK.value(), 1f, 1.2f)
        player.stopUsingItem()
        return true
    }
}

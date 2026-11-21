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

/**
 * Konterhaltung: wehrt einen Nahkampftreffer oder ein Geschoss ab.
 *
 * Seit 1.11.0 gilt das Fenster ab dem Klick volle [GearConfig] `solvane_counter_ticks`, auch wenn man die Taste sofort
 * wieder loslässt. Vorher beendete das Loslassen die Haltung: Ein kurzes Tippen schützte nur ein bis zwei Ticks, während
 * die Anzeige das ganze Fenster versprach. Kommt der Klick wegen der Leitung knapp nach einem Boss-Treffer an, zählt er
 * noch als Parade und der Schaden wird erstattet.
 */
object SolvaneCounter {

    private class Window(val until: Long, val hand: Hand)

    private class RecentHit(val tick: Long, val taken: Float)

    private val windows = HashMap<UUID, Window>()
    private val recent = HashMap<UUID, RecentHit>()

    fun init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (entity is ServerPlayerEntity) !tryCounter(entity, source) else true
        }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, taken, blocked ->
            if (entity is ServerPlayerEntity && !blocked && taken > 0f && dev.henny.hha_arius.boss.BossGuard.isBoss(source)) {
                recent[entity.uuid] = RecentHit(GearTasks.now, taken)
            }
        }
        GearTasks.everyTick { expire() }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            windows.remove(handler.player.uuid)
            recent.remove(handler.player.uuid)
        }
    }

    /** Abgelaufene Fenster schließen: Wer nichts abgefangen hat, bekommt die längere Abklingzeit. */
    private fun expire() {
        if (windows.isEmpty()) return
        val server = GearServer.current ?: return
        val it = windows.entries.iterator()
        while (it.hasNext()) {
            val (id, w) = it.next()
            if (GearTasks.now <= w.until) continue
            it.remove()
            val player = server.playerManager.getPlayer(id) ?: continue
            val stack = player.getStackInHand(w.hand)
            if (stack.isOf(GearItems.SOLVANE)) Cooldowns.set(player, stack, GearConfig.key("solvane_counter_cooldown_fail"))
        }
    }

    /** Seit 1.10.0: Ist gerade eine Konterhaltung offen (Stille-Duell auf dem Gipfel)? */
    fun active(player: ServerPlayerEntity): Boolean {
        val w = windows[player.uuid] ?: return false
        return GearTasks.now <= w.until && player.getStackInHand(w.hand).isOf(GearItems.SOLVANE)
    }

    fun start(player: ServerPlayerEntity, hand: Hand) {
        if (active(player)) return
        windows[player.uuid] = Window(GearTasks.now + GearConfig.ticks("solvane_counter_ticks"), hand)
        GearSounds.play(player.entityWorld, player.entityPos, "arius.guard", SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), 0.5f, 1.5f)
        forgiveLateClick(player, hand)
    }

    /**
     * Der Klick kam knapp nach dem Treffer an (Ping): Der Spieler hat ihn rechtzeitig gedrückt, der Server sah ihn nur zu
     * spät. Dann gilt er als Parade und der Schaden kommt zurück. Höchstens [MAX_LATE] Ticks.
     */
    private fun forgiveLateClick(player: ServerPlayerEntity, hand: Hand) {
        val hit = recent.remove(player.uuid) ?: return
        val late = (player.networkHandler.latency / 100 + 1).coerceIn(1, MAX_LATE)
        if (GearTasks.now - hit.tick > late || dev.henny.hha_arius.boss.Hold.isHeld(player) || !player.isAlive) return
        player.health = (player.health + hit.taken).coerceAtMost(player.maxHealth)
        val stack = player.getStackInHand(hand)
        windows.remove(player.uuid)
        Cooldowns.set(player, stack, GearConfig.key("solvane_counter_cooldown_success"))
        Resonance.gain(player, GearConfig.num("resonance_gain_counter"))
        val at = player.eyePos.add(player.rotationVector.multiply(0.8)).add(0.0, -0.3, 0.0)
        GearVfx.flare(player.entityWorld, Vfx.Style.HEAVEN, at, 0.8f, 6)
        GearSounds.play(player.entityWorld, at, "solvane.parry", SoundEvents.ITEM_SHIELD_BLOCK.value(), 1f, 1.2f)
        player.stopUsingItem()
    }

    private const val MAX_LATE = 3

    /** Loslassen beendet das Fenster nicht mehr; es läuft bis zu seinem Ende ([expire]). */
    @Suppress("UNUSED_PARAMETER")
    fun finish(player: ServerPlayerEntity, stack: ItemStack) = Unit

    private fun tryCounter(player: ServerPlayerEntity, source: DamageSource): Boolean {
        val window = windows[player.uuid] ?: return false
        if (GearTasks.now > window.until) return false
        if (!player.getStackInHand(window.hand).isOf(GearItems.SOLVANE)) return false
        // Inszenierte Treffer (Konter-Bestrafung, Griffe) lassen sich nicht parieren.
        if (dev.henny.hha_arius.boss.Hold.isHeld(player)) return false
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

        val stack = player.getStackInHand(window.hand)
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

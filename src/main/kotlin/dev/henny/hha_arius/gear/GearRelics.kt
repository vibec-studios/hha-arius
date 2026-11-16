package dev.henny.hha_arius.gear

import dev.henny.hha.logic.Cooldowns
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.boss.Encounters
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.component.type.TooltipDisplayComponent
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.tooltip.TooltipType
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.cos
import kotlin.math.sin

// ==================================================================== Nivaris' Schwingenschuppe (10.3)

/** Schwingenschlag: hoch und nach vorn, Sanfter Fall, bei der Landung ein Schneestoß ohne Schaden. Ohne Set nutzbar. */
class WingscaleItem(settings: Settings) : Item(settings) {
    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        if (world is ServerWorld && user is ServerPlayerEntity) {
            if (!GearConfig.on("wingscale")) return ActionResult.PASS
            Wingscale.flap(user, world, user.getStackInHand(hand))
        }
        return ActionResult.SUCCESS
    }
}

object Wingscale {

    private class Flight(val world: ServerWorld, val start: Long)

    private val flights = HashMap<UUID, Flight>()
    private const val MAX_FLIGHT = 300L

    fun init() {
        GearTasks.everyTick { if (flights.isNotEmpty()) tickFlights() }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> flights.remove(handler.player.uuid) }
    }

    fun flap(player: ServerPlayerEntity, world: ServerWorld, stack: ItemStack) {
        val up = velocityForHeight(GearConfig.num("wingscale_height"))
        // Luftreibung 0,91 je Tick: Gesamtweg ≈ v / 0,09.
        val forward = GearConfig.num("wingscale_forward") * 0.09
        val yaw = Math.toRadians(player.yaw.toDouble())
        Fx.launchPlayer(player, Vec3d(-sin(yaw) * forward, up, cos(yaw) * forward))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, GearConfig.ticks("wingscale_slow_fall_ticks"), 0, false, false, true))
        player.fallDistance = 0.0
        Cooldowns.set(player, stack, GearConfig.key("wingscale_cooldown"))
        flights[player.uuid] = Flight(world, GearTasks.now)

        GearVfx.wings(world, Vfx.Style.HEAVEN, player, 14, 1f)
        val p = player.entityPos
        world.spawnParticles(ParticleTypes.SNOWFLAKE, p.x, p.y + 0.1, p.z, 10, 0.6, 0.05, 0.6, 0.05)
        GearSounds.play(world, p, "wingscale.flap", SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 0.9f, 1.3f)
    }

    private fun tickFlights() {
        val iterator = flights.entries.iterator()
        while (iterator.hasNext()) {
            val (id, flight) = iterator.next()
            val player = flight.world.server?.playerManager?.getPlayer(id)
            if (player == null || !GearCombat.stillThere(player, flight.world) || GearTasks.now - flight.start > MAX_FLIGHT) {
                iterator.remove()
                continue
            }
            if (player.isTouchingWater || player.isGliding || player.abilities.flying) {
                iterator.remove()
                continue
            }
            if (player.isOnGround && GearTasks.now - flight.start > 5) {
                iterator.remove()
                land(player, flight.world)
            }
        }
    }

    /** Landungs-Schneestoß: schiebt Gegner zurück, kein Schaden. */
    private fun land(player: ServerPlayerEntity, world: ServerWorld) {
        val center = player.entityPos
        val radius = GearConfig.num("wingscale_landing_radius")
        val push = GearConfig.num("wingscale_landing_push") / 6.0
        for (entity in world.getOtherEntities(player, Box.of(center, radius * 2, radius * 2, radius * 2))) {
            if (entity.squaredDistanceTo(center) > radius * radius) continue
            if (!GearCombat.canHit(player, entity)) continue
            GearCombat.push(entity as net.minecraft.entity.LivingEntity, center, push, 0.25)
        }
        GearVfx.shockwave(world, Vfx.Style.HEAVEN, center, radius.toFloat(), 12, 0.6f)
        world.spawnParticles(ParticleTypes.SNOWFLAKE, center.x, center.y + 0.2, center.z, 14, radius * 0.3, 0.1, radius * 0.3, 0.06)
        GearSounds.play(world, center, "nivaris.land", SoundEvents.BLOCK_POWDER_SNOW_FALL, 0.6f, 1.4f)
    }

    /** Startgeschwindigkeit für [height] Blöcke Steighöhe (Spielerphysik: Schwerkraft 0,08, Luftwiderstand 0,98). */
    fun velocityForHeight(height: Double): Double {
        fun peak(v0: Double): Double {
            var v = v0
            var y = 0.0
            var best = 0.0
            repeat(200) {
                y += v
                v = (v - 0.08) * 0.98
                if (y > best) best = y
                if (v <= 0.0) return best
            }
            return best
        }
        var lo = 0.0
        var hi = 4.0
        repeat(40) {
            val mid = (lo + hi) / 2
            if (peak(mid) < height) lo = mid else hi = mid
        }
        return hi
    }
}

// ==================================================================== Siegel von Eirholm (2.2)

/** Nur in kaltem Biom. 5 s Schneesturm, danach [Encounters.requestStart]. Das Siegel wird NICHT verbraucht. */
class SealItem(settings: Settings) : Item(settings) {
    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        if (world is ServerWorld && user is ServerPlayerEntity) {
            if (!SealRitual.begin(user, world, hand)) return ActionResult.FAIL
        }
        return ActionResult.SUCCESS
    }
}

object SealRitual {

    private class Ritual(val world: ServerWorld, val hand: Hand, val ends: Long)

    private val rituals = HashMap<UUID, Ritual>()

    /** Radius, in dem Spieler den Szenentext sehen (wie der Mitnahme-Radius von [Encounters.requestStart]). */
    private const val GROUP_RADIUS = 12.0

    fun init() {
        GearTasks.everyTick { if (rituals.isNotEmpty()) tick() }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> rituals.remove(handler.player.uuid) }
    }

    fun isCold(player: ServerPlayerEntity, world: ServerWorld): Boolean {
        val pos = player.blockPos
        val biome = world.getBiome(pos).value()
        val max = GearConfig.float("seal_max_temperature")
        if (biome.temperature < max) return true
        // Höhenlage zählt mit (verschneite Gipfel); entspricht Vanillas Schwelle 0,15.
        return max >= 0.15f && biome.isCold(pos, world.seaLevel)
    }

    fun begin(player: ServerPlayerEntity, world: ServerWorld, hand: Hand): Boolean {
        if (player.uuid in rituals) return false
        if (!isCold(player, world)) {
            GearCombat.actionbar(player, "hha_arius.msg.seal_not_cold")
            return false
        }
        if (Encounters.isParticipant(player)) {
            GearCombat.actionbar(player, "hha_arius.msg.seal_in_fight")
            return false
        }
        val ticks = GearConfig.ticks("seal_blizzard_ticks").coerceAtLeast(1)
        rituals[player.uuid] = Ritual(world, hand, GearTasks.now + ticks)
        player.itemCooldownManager.set(player.getStackInHand(hand), ticks + 20)

        val scene = Text.translatable("hha_arius.scene.seal").formatted(Formatting.GRAY, Formatting.ITALIC)
        for (other in world.players) {
            if (other.squaredDistanceTo(player) <= GROUP_RADIUS * GROUP_RADIUS) other.sendMessage(scene, true)
        }
        GearSounds.play(world, player.entityPos, "seal.blizzard", SoundEvents.ENTITY_PLAYER_HURT_FREEZE, 1f, 0.6f)
        return true
    }

    private fun tick() {
        val iterator = rituals.entries.iterator()
        while (iterator.hasNext()) {
            val (id, ritual) = iterator.next()
            val world = ritual.world
            val player = world.server?.playerManager?.getPlayer(id)
            if (player == null || !GearCombat.stillThere(player, world)) {
                iterator.remove()
                continue
            }
            if (GearTasks.now >= ritual.ends) {
                iterator.remove()
                finish(player, ritual)
                continue
            }
            blizzard(world, player)
        }
    }

    /** Schnee treibt zu dir hin und steigt dabei auf („fällt nach oben“). */
    private fun blizzard(world: ServerWorld, player: ServerPlayerEntity) {
        val center = player.entityPos
        val random = player.random
        repeat(10) {
            val angle = random.nextDouble() * Math.PI * 2
            val r = 2.5 + random.nextDouble() * 5.0
            val x = center.x + cos(angle) * r
            val z = center.z + sin(angle) * r
            val y = center.y + random.nextDouble() * 1.5
            val inward = Vec3d(center.x - x, 0.0, center.z - z).normalize().multiply(0.10 + random.nextDouble() * 0.06)
            world.spawnParticles(ParticleTypes.SNOWFLAKE, x, y, z, 0, inward.x, 0.22 + random.nextDouble() * 0.12, inward.z, 1.0)
        }
    }

    private fun finish(player: ServerPlayerEntity, ritual: Ritual) {
        val inHand = player.getStackInHand(ritual.hand)
        val seal = if (inHand.isOf(GearItems.EIRHOLM_SEAL)) inHand else findSeal(player)
        if (seal == null) {
            GearCombat.actionbar(player, "hha_arius.msg.seal_missing")
            return
        }
        if (Encounters.requestStart(player, seal)) {
            GearAdvancements.grant(player, "seal")
        }
    }

    fun findSeal(player: ServerPlayerEntity): ItemStack? {
        val inventory = player.inventory
        for (i in 0 until inventory.size()) {
            val stack = inventory.getStack(i)
            if (stack.isOf(GearItems.EIRHOLM_SEAL)) return stack
        }
        return null
    }
}

// ==================================================================== Echo von Eirholm (2.2)

/** Schleichen + Benutzen wählt den Abschnitt (gespeichert im Item), Benutzen startet das Training. */
class EchoItem(settings: Settings) : Item(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        if (world is ServerWorld && user is ServerPlayerEntity) {
            val stack = user.getStackInHand(hand)
            if (user.isSneaking) {
                val next = stage(stack) % 4 + 1
                stack.set(GearItems.ECHO_STAGE, next)
                GearCombat.actionbar(user, "hha_arius.msg.echo_stage", Text.translatable(stageKey(next)))
                GearSounds.play(world, user.entityPos, "resonance.chime", SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 0.8f + next * 0.15f)
            } else {
                Cooldowns.set(user, stack, GearConfig.key("echo_cooldown"))
                Encounters.requestTraining(user, stage(stack))
            }
        }
        return ActionResult.SUCCESS
    }

    @Deprecated("Vanilla markiert appendTooltip als veraltet, nutzt es aber weiter.")
    override fun appendTooltip(
        stack: ItemStack, context: TooltipContext, displayComponent: TooltipDisplayComponent, textConsumer: Consumer<Text>, type: TooltipType,
    ) {
        textConsumer.accept(Text.translatable("hha_arius.echo.tooltip", Text.translatable(stageKey(stage(stack)))).formatted(Formatting.AQUA))
    }

    companion object {
        fun stage(stack: ItemStack): Int = (stack.get(GearItems.ECHO_STAGE) ?: 1).coerceIn(1, 4)
        fun stageKey(stage: Int): String = "hha_arius.echo.stage.$stage"
    }
}

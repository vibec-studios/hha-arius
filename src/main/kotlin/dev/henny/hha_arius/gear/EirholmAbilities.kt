package dev.henny.hha_arius.gear

import dev.henny.hha.api.HhaAddonContext
import dev.henny.hha.api.ability.AbilitySlot
import dev.henny.hha.api.ability.SimpleAbility
import dev.henny.hha.logic.Cooldowns
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.boss.Encounters
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.block.Blocks
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.fluid.Fluids
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Die drei Set-Fähigkeiten (Konzept 10.1). Jede hat ihren eigenen Cooldown auf einem eigenen Setteil, damit die
 * HHA-Leiste sie getrennt zeigt: Weiße Linie → Brustplatte, Frostschritt → Stiefel, Weißer Kern → Helm.
 */
object EirholmAbilities {

    /** Wer gerade lädt (Weiße Linie/Weißer Kern), kann nicht noch einmal auslösen. */
    private val charging = HashSet<UUID>()

    /** Frostschritt: bis zu diesem Tick keine Nahkampftreffer. */
    private val guardUntil = HashMap<UUID, Long>()

    fun register(context: HhaAddonContext) {
        context.registerAbility(SimpleAbility(context.id("white_line"), AbilitySlot.ABILITY_1, Gear::hasFullSet, ::castWhiteLine))
        context.registerAbility(SimpleAbility(context.id("frost_step"), AbilitySlot.ABILITY_2, Gear::hasFullSet, ::castFrostStep))
        context.registerAbility(SimpleAbility(context.id("white_core"), AbilitySlot.ABILITY_4, Gear::hasFullSet, ::castWhiteCore))
    }

    fun init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (entity !is ServerPlayerEntity) return@register true
            val until = guardUntil[entity.uuid] ?: return@register true
            if (GearTasks.now > until) {
                guardUntil.remove(entity.uuid)
                return@register true
            }
            val direct = source.source
            val melee = direct != null && direct === source.attacker && direct !is ProjectileEntity &&
                !source.isIn(DamageTypeTags.IS_EXPLOSION) && !source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)
            !melee
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            charging.remove(handler.player.uuid)
            guardUntil.remove(handler.player.uuid)
        }
        FrostIce.init()
    }

    private fun ready(player: ServerPlayerEntity, slot: EquipmentSlot, toggle: String): Boolean {
        if (!GearConfig.on(toggle)) return false
        if (player.uuid in charging) return false
        return !player.itemCooldownManager.isCoolingDown(player.getEquippedStack(slot))
    }

    private fun needResonance(player: ServerPlayerEntity, cost: Float): Boolean {
        val have = Resonance.get(player)
        if (have >= cost) return false
        GearCombat.actionbar(player, "hha_arius.msg.need_resonance", cost.toInt(), have.toInt())
        return true
    }

    // ---------------------------------------------------------------- Weiße Linie (Ability 1)

    private fun castWhiteLine(player: ServerPlayerEntity) {
        if (!ready(player, EquipmentSlot.CHEST, "white_line")) return
        val cost = GearConfig.float("white_line_cost")
        if (needResonance(player, cost)) return
        val world = player.entityWorld
        val spent = Resonance.spendAll(player)
        Cooldowns.set(player, player.getEquippedStack(EquipmentSlot.CHEST), GearConfig.key("white_line_cooldown"))

        val charge = GearConfig.ticks("white_line_charge_ticks")
        charging.add(player.uuid)
        player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, charge + 2, 2, false, false, false))
        val hand = player.eyePos.add(player.rotationVector.multiply(0.6)).add(0.0, -0.35, 0.0)
        GearVfx.flare(world, Vfx.Style.HEAVEN, hand, 0.5f, charge)
        GearSounds.play(world, player.entityPos, "white_line.charge", SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), 0.8f, 1.4f)

        GearTasks.later(charge) {
            charging.remove(player.uuid)
            if (GearCombat.stillThere(player, world)) releaseWhiteLine(player, world, spent)
        }
    }

    private fun releaseWhiteLine(player: ServerPlayerEntity, world: ServerWorld, spent: Float) {
        val start = player.eyePos.add(0.0, -0.25, 0.0)
        val dir = player.rotationVector.normalize()
        val length = GearConfig.num("white_line_length")
        var end = start.add(dir.multiply(length))
        // Die Linie endet an festen Blöcken: Sie schneidet keine Wände (und keine Eiswände in der Arena).
        val hit = world.raycast(RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
        if (hit.type == HitResult.Type.BLOCK) end = hit.pos

        val half = GearConfig.num("white_line_width") / 2.0
        val samples = segmentSamples(start, end, 0.5)
        val area = Box(start, end).expand(half + 1.0)
        val damage = (GearConfig.num("white_line_damage") + GearConfig.num("white_line_damage_per_resonance") * spent).toFloat()
        var anyHit = false

        for (entity in world.getOtherEntities(player, area)) {
            val box = entity.boundingBox.expand(half)
            if (samples.none { box.contains(it) }) continue
            when {
                entity is ProjectileEntity && GearCombat.isHostileProjectile(player, entity) -> cutProjectile(world, entity)
                GearCombat.canHit(player, entity) -> {
                    (entity as LivingEntity).damage(world, world.damageSources.playerAttack(player), damage)
                    anyHit = true
                }
            }
        }
        if (anyHit) Resonance.markCombat(player)

        GearVfx.beam(world, Vfx.Style.HEAVEN, start, end, 0.55f, 10)
        GearVfx.crescent(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.1, 0.0), dir, 2.5f, 2.2f, 7)
        GearSounds.play(world, start, "white_line.release", SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.3f)
    }

    // ---------------------------------------------------------------- Frostschritt (Ability 2)

    private fun castFrostStep(player: ServerPlayerEntity) {
        if (!ready(player, EquipmentSlot.FEET, "frost_step")) return
        val cost = GearConfig.float("frost_step_cost")
        if (needResonance(player, cost)) return
        if (!Resonance.spend(player, cost)) return
        val world = player.entityWorld
        Cooldowns.set(player, player.getEquippedStack(EquipmentSlot.FEET), GearConfig.key("frost_step_cooldown"))

        val dir = moveDirection(player)
        val distance = GearConfig.num("frost_step_distance")
        val dashTicks = 3
        val speed = distance / dashTicks
        guardUntil[player.uuid] = GearTasks.now + GearConfig.ticks("frost_step_guard_ticks")
        val placeIce = GearConfig.on("frost_step_ice") &&
            (GearConfig.on("frost_step_ice_in_arena") || !Encounters.isParticipant(player))

        GearSounds.play(world, player.entityPos, "frost_step", SoundEvents.BLOCK_POWDER_SNOW_STEP, 1f, 1.2f)
        GearVfx.trail(world, Vfx.Style.STORM, player, 0.45f, 10)

        for (t in 0 until dashTicks) {
            val step: () -> Unit = {
                if (GearCombat.stillThere(player, world)) {
                    if (placeIce) FrostIce.pathAhead(world, player.entityPos, dir, speed + 1.5)
                    Fx.launchPlayer(player, Vec3d(dir.x * speed, 0.0, dir.z * speed))
                    player.fallDistance = 0.0
                    val p = player.entityPos
                    world.spawnParticles(ParticleTypes.SNOWFLAKE, p.x, p.y + 0.2, p.z, 3, 0.2, 0.05, 0.2, 0.01)
                }
            }
            if (t == 0) step() else GearTasks.later(t, step)
        }
        GearTasks.later(dashTicks) {
            if (GearCombat.stillThere(player, world)) {
                if (placeIce) FrostIce.pathAhead(world, player.entityPos, dir, 1.5)
                Fx.launchPlayer(player, Vec3d(dir.x * 0.2, player.velocity.y.coerceAtMost(0.0), dir.z * 0.2))
            }
        }
    }

    /** Laufrichtung aus den Tasten (PlayerInput), sonst Blickrichtung; immer waagerecht. */
    private fun moveDirection(player: ServerPlayerEntity): Vec3d {
        val input = player.playerInput
        var forward = 0.0
        var strafe = 0.0
        if (input.forward()) forward += 1.0
        if (input.backward()) forward -= 1.0
        if (input.left()) strafe += 1.0
        if (input.right()) strafe -= 1.0
        if (forward == 0.0 && strafe == 0.0) forward = 1.0
        val yaw = Math.toRadians(player.yaw.toDouble())
        val fx = -sin(yaw)
        val fz = cos(yaw)
        // Links ist bei Minecraft-Yaw (+cos, +sin) gedreht.
        val lx = cos(yaw)
        val lz = sin(yaw)
        val v = Vec3d(fx * forward + lx * strafe, 0.0, fz * forward + lz * strafe)
        return if (v.lengthSquared() < 1.0e-6) Vec3d(fx, 0.0, fz) else v.normalize()
    }

    // ---------------------------------------------------------------- Weißer Kern (Ability 4)

    private fun castWhiteCore(player: ServerPlayerEntity) {
        if (!ready(player, EquipmentSlot.HEAD, "white_core")) return
        val cost = GearConfig.float("white_core_cost")
        if (needResonance(player, cost)) return
        if (!Resonance.spend(player, cost)) return
        val world = player.entityWorld
        Cooldowns.set(player, player.getEquippedStack(EquipmentSlot.HEAD), GearConfig.key("white_core_cooldown"))

        val charge = GearConfig.ticks("white_core_charge_ticks")
        charging.add(player.uuid)
        GearVfx.flare(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.2, 0.0), 0.7f, charge)
        GearSounds.play(world, player.entityPos, "core.form", SoundEvents.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.6f)

        GearTasks.later(charge) {
            charging.remove(player.uuid)
            if (GearCombat.stillThere(player, world)) releaseWhiteCore(player, world)
        }
    }

    private fun releaseWhiteCore(player: ServerPlayerEntity, world: ServerWorld) {
        val center = player.entityPos.add(0.0, 1.0, 0.0)
        val radius = GearConfig.num("white_core_radius")
        val damage = GearConfig.float("white_core_damage")
        // ~Blöcke → Startgeschwindigkeit (kurzer Flug mit Luftreibung 0,91 und Bodenrutschen).
        val push = GearConfig.num("white_core_push") / 6.0
        var anyHit = false
        for (entity in world.getOtherEntities(player, Box.of(center, radius * 2, radius * 2, radius * 2))) {
            if (entity.squaredDistanceTo(center) > radius * radius) continue
            when {
                entity is ProjectileEntity && GearCombat.isHostileProjectile(player, entity) -> cutProjectile(world, entity)
                GearCombat.canHit(player, entity) -> {
                    val target = entity as LivingEntity
                    target.damage(world, world.damageSources.playerAttack(player), damage)
                    GearCombat.push(target, center, push, 0.35)
                    anyHit = true
                }
            }
        }
        if (anyHit) Resonance.markCombat(player)
        player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, GearConfig.ticks("white_core_resistance_ticks"), 0, false, true, true))

        GearVfx.shockwave(world, Vfx.Style.HEAVEN, player.entityPos, radius.toFloat(), 16, 1.4f)
        GearVfx.nova(world, Vfx.Style.STORM, center, 2.2f, 12)
        GearSounds.play(world, center, "white_core", SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.6f)
    }

    // ---------------------------------------------------------------- Helfer

    /** Geschoss zerschneiden: verschwindet mit einem kleinen Splitterhauch. */
    fun cutProjectile(world: ServerWorld, projectile: ProjectileEntity) {
        val at = projectile.entityPos
        projectile.discard()
        world.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, 5, 0.1, 0.1, 0.1, 0.03)
    }

    private fun segmentSamples(start: Vec3d, end: Vec3d, step: Double): List<Vec3d> {
        val delta = end.subtract(start)
        val n = ceil(delta.length() / step).toInt().coerceAtLeast(1)
        return (0..n).map { start.add(delta.multiply(it.toDouble() / n)) }
    }
}

/**
 * Brüchiges Eis des Frostschritts: nur Vanilla-`frosted_ice` auf stillem Wasser mit Luft darüber. Es schmilzt von selbst;
 * zur Sicherheit (dunkle Orte schmelzen Frosted Ice nie) wird jeder gesetzte Block nach `frost_step_ice_max_ticks`
 * wieder zu Wasser und beim Server-Stopp sofort. So verändert der Frostschritt nichts dauerhaft.
 */
object FrostIce {

    private class Placed(val world: RegistryKey<World>, val pos: BlockPos, val expires: Long)

    private val placed = ArrayList<Placed>()
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        GearTasks.everyTick { if (placed.isNotEmpty()) expire(force = false, server = null) }
        ServerLifecycleEvents.SERVER_STOPPING.register { server -> expire(force = true, server = server) }
    }

    /** Legt eine 3 breite Spur vom Spieler bis [length] Blöcke in Richtung [dir]. */
    fun pathAhead(world: ServerWorld, from: Vec3d, dir: Vec3d, length: Double) {
        val side = Vec3d(-dir.z, 0.0, dir.x)
        val y = BlockPos.ofFloored(from.x, from.y - 0.5, from.z).y
        val steps = ceil(length / 0.5).toInt()
        for (i in 0..steps) {
            val p = from.add(dir.multiply(i * 0.5))
            for (s in -1..1) {
                val q = p.add(side.multiply(s.toDouble()))
                place(world, BlockPos.ofFloored(q.x, y.toDouble(), q.z))
            }
        }
    }

    private fun place(world: ServerWorld, pos: BlockPos) {
        val state = world.getBlockState(pos)
        if (!state.isOf(Blocks.WATER) || !state.fluidState.isOf(Fluids.WATER) || !state.fluidState.isStill) return
        if (!world.getBlockState(pos.up()).isAir) return
        val ice = Blocks.FROSTED_ICE.defaultState
        if (!ice.canPlaceAt(world, pos) || !world.canPlace(ice, pos, net.minecraft.block.ShapeContext.absent())) return
        if (world.setBlockState(pos, ice)) {
            placed.add(Placed(world.registryKey, pos.toImmutable(), GearTasks.now + GearConfig.ticks("frost_step_ice_max_ticks")))
        }
    }

    private fun expire(force: Boolean, server: net.minecraft.server.MinecraftServer?) {
        val iterator = placed.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!force && entry.expires > GearTasks.now) continue
            val srv = server ?: GearServer.current
            val world = srv?.getWorld(entry.world)
            if (world == null) {
                iterator.remove()
                continue
            }
            // Nicht geladen: später erneut versuchen (dort tickt auch das Eis nicht).
            if (!world.isChunkLoaded(entry.pos)) {
                if (force) iterator.remove()
                continue
            }
            iterator.remove()
            if (world.getBlockState(entry.pos).isOf(Blocks.FROSTED_ICE)) {
                world.setBlockState(entry.pos, Blocks.WATER.defaultState)
            }
        }
    }
}

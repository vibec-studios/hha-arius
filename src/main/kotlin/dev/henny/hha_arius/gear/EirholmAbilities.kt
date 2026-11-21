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
 * Rüstungsfähigkeiten nach der Konvention der anderen HHA-Sets (Brustplatte → Ability 1, Beinschienen → Ability 2,
 * volles Set → Ability 3 statt Ultra-Modus). Die Rüstung funktioniert ohne Waffen; Kosten in Frostresonanz.
 * * Brustplatte: Weißer Kern (Schutz/Raum schaffen) + passiv Frostpanzer (Nahkampf-Angreifer kurz verlangsamt).
 * * Beinschienen: Frostschritt (Bewegung, kurz unberührbar im Nahkampf).
 * * Helm: passiv kein Gefrieren. Stiefel: passiv Pulverschnee trägt, kleine Stürze (bis 2,5 Herzen) abgefangen.
 * * Volles Set: Weißes Loch ([dev.henny.hha_arius.gear.art.WhiteHole]), Resonanz-Ruhepunkt 50, +25 % Gewinn.
 */
object EirholmAbilities {

    /** Frostschritt: bis zu diesem Tick keine Nahkampftreffer. */
    private val guardUntil = HashMap<UUID, Long>()

    /** Frostpanzer: Angreifer-ID → nächster möglicher Verlangsamungs-Tick. */
    private val chillReady = HashMap<Int, Long>()

    fun register(context: HhaAddonContext) {
        // Seit 1.3.1 (Crix: „die checke ich nicht“): Brust = Drachenatem; Beine seit 1.4.2 Drachenschweif (der
        // Schwingensprung doppelte den Windsprung der Handschuhe). IDs bleiben.
        context.registerAbility(SimpleAbility(context.id("white_core"), AbilitySlot.ABILITY_1, { Gear.hasPiece(it, EquipmentSlot.CHEST) }, ::castDragonBreath, EquipmentSlot.CHEST))
        context.registerAbility(SimpleAbility(context.id("frost_step"), AbilitySlot.ABILITY_2, { Gear.hasPiece(it, EquipmentSlot.LEGS) }, ::castDragonTail, EquipmentSlot.LEGS))
        context.registerAbility(SimpleAbility(context.id("white_hole"), AbilitySlot.ABILITY_3, Gear::hasFullSet, { dev.henny.hha_arius.gear.art.WhiteHole.cast(it) }, EquipmentSlot.HEAD))
        // Die eingebaute Ultra-Ability ist mit isAvailable = { true } registriert und gewinnt den Dispatch; bei vollem
        // Arius-Set wird ihr Cast abgefangen und das Weiße Loch gewirkt (wie HHA Void).
        dev.henny.hha.api.event.HhaEvents.ABILITY_CAST.register { player, ability ->
            if (ability.id == net.minecraft.util.Identifier.of("hha", "ultra") && Gear.hasFullSet(player)) {
                dev.henny.hha_arius.gear.art.WhiteHole.cast(player)
                false
            } else {
                true
            }
        }
    }

    fun init() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            if (entity !is ServerPlayerEntity) return@register true
            // Stiefel: kleine Stürze abfangen.
            if (source.isIn(DamageTypeTags.IS_FALL) && Gear.hasPiece(entity, EquipmentSlot.FEET) && amount <= 5f) return@register false
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
        // Brustplatte, passiv „Frostpanzer“: Wer im Nahkampf trifft, wird kurz verlangsamt (je Angreifer höchstens alle 3 s).
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, taken, _ ->
            if (entity !is ServerPlayerEntity || taken <= 0f || !Gear.hasPiece(entity, EquipmentSlot.CHEST)) return@register
            val attacker = source.attacker as? LivingEntity ?: return@register
            if (source.source !== attacker || attacker === entity) return@register
            val now = GearTasks.now
            if ((chillReady[attacker.id] ?: 0L) > now) return@register
            chillReady[attacker.id] = now + 60
            attacker.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 20, 0, false, true, true), entity)
            val p = attacker.entityPos
            entity.entityWorld.spawnParticles(ParticleTypes.SNOWFLAKE, p.x, p.y + attacker.height * 0.6, p.z, 6, 0.2, 0.3, 0.2, 0.02)
            if (chillReady.size > 256) chillReady.entries.removeIf { it.value < now }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> guardUntil.remove(handler.player.uuid) }
        FrostIce.init()
    }

    private fun ready(player: ServerPlayerEntity, slot: EquipmentSlot, toggle: String): Boolean {
        if (!GearConfig.on(toggle)) return false
        if (!dev.henny.hha_arius.gear.art.Focus.isFree(player)) return false
        return !player.itemCooldownManager.isCoolingDown(player.getEquippedStack(slot))
    }

    private fun needResonance(player: ServerPlayerEntity, cost: Float): Boolean {
        val have = Resonance.get(player)
        if (have >= cost) return false
        GearCombat.actionbar(player, "hha_arius.msg.need_resonance", cost.toInt(), have.toInt())
        return true
    }

    // ---------------------------------------------------------------- Drachenatem (Brustpanzer, Ability 1, seit 1.3.1)

    /**
     * Drachenatem (seit 1.4.2 nach den Drachen-Anrufungen aus Elden Ring): Vor dem Wirker erscheint Solvanes Kopf aus
     * Geisterlicht ([dev.henny.hha_arius.client.fx.DragonBreathFx]), öffnet nach 0,3 s den Kiefer und speit 1,5 s lang
     * einen breiten, rollenden Frostodem in Blickrichtung (die Schwaden folgen dem Kopf verzögert). Alle 3 Ticks Schaden
     * und starke Verlangsamung im Kegel ab dem Maul; der Spieler ist dabei verlangsamt.
     */
    private fun castDragonBreath(player: ServerPlayerEntity) {
        if (!ready(player, EquipmentSlot.CHEST, "white_core")) return
        val cost = GearConfig.float("breath_cost")
        if (needResonance(player, cost)) return
        if (!Resonance.spend(player, cost)) return
        val world = player.entityWorld
        Cooldowns.set(player, player.getEquippedStack(EquipmentSlot.CHEST), GearConfig.key("breath_cooldown"))
        val ticks = GearConfig.ticks("breath_ticks")
        dev.henny.hha_arius.gear.art.Focus.begin(player, dev.henny.hha_arius.gear.art.Focus.Busy.ABILITY, GearTasks.now + ticks)
        val range = GearConfig.num("breath_range")
        val dmg = GearConfig.float("breath_damage")
        val windup = 6
        player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, ticks + windup, 1, false, false, false))
        dev.henny.hha_arius.gear.art.ArtFx.send(world, dev.henny.hha_arius.net.FxKind.DRAGON_BREATH, player.entityPos, a = range.toFloat(), life = windup + ticks + 8, e1 = player.id)
        GearSounds.play(world, player.entityPos, "white_core", SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 1.35f)
        for (t in 0 until ticks) {
            GearTasks.later(windup + t) {
                if (!GearCombat.stillThere(player, world)) return@later
                val look = player.rotationVector.normalize()
                // Maul des Geisterkopfs (wie im Client: 1,1 vor den Augen, 0,35 höher, plus Kopflänge)
                val mouth = player.eyePos.add(look.multiply(2.2)).add(0.0, 0.35, 0.0)
                if (t % 8 == 0) GearSounds.play(world, mouth, "white_core", SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 0.55f, 0.6f + t * 0.01f)
                if (t % 3 == 0) world.spawnParticles(ParticleTypes.SNOWFLAKE, mouth.x, mouth.y, mouth.z, 4, 0.3, 0.3, 0.3, 0.2)
                if (t % 3 != 0) return@later
                for (e in world.getOtherEntities(player, Box.of(mouth.add(look.multiply(range / 2)), range * 2, range * 2, range * 2))) {
                    if (!GearCombat.canHit(player, e)) continue
                    val to = e.entityPos.add(0.0, e.height / 2.0, 0.0).subtract(mouth)
                    val dist = to.length()
                    if (dist > range || dist < 1e-3) continue
                    if (dist > 2.0 && to.multiply(1.0 / dist).dotProduct(look) < cos(Math.toRadians(32.0))) continue
                    val target = e as LivingEntity
                    target.timeUntilRegen = 0
                    dev.henny.hha_arius.boss.PlayerHit.marked(dev.henny.hha_arius.boss.PlayerHit.Kind.ABILITY) { target.damage(world, world.damageSources.playerAttack(player), dmg) }
                    target.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, GearConfig.ticks("breath_slow_ticks"), 2, false, true, true), player)
                    target.frozenTicks = (target.frozenTicks + 12).coerceAtMost(target.minFreezeDamageTicks + 40)
                    Frostbite.add(target, 6f)
                    Resonance.markCombat(player)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Drachenschweif (Beinschienen, Ability 2, seit 1.4.2)

    /**
     * Drachenschweif: Aus dem Rücken wächst Solvanes Schwanz aus Geisterlicht und peitscht in 0,6 s einmal ganz um den
     * Spieler ([dev.henny.hha_arius.client.fx.DragonTailFx]). Wen die Spitze überstreicht (Radius `tail_radius`), der
     * bekommt Schaden, wird nach außen geschleudert, stark verlangsamt und vereist; jedes Ziel einmal. Während des
     * Peitschens keine Nahkampftreffer. Steht, statt zu springen – die Bewegung bleibt Sache der Wind-Handschuhe.
     */
    private fun castDragonTail(player: ServerPlayerEntity) {
        if (!ready(player, EquipmentSlot.LEGS, "frost_step")) return
        val cost = GearConfig.float("tail_cost")
        if (needResonance(player, cost)) return
        if (!Resonance.spend(player, cost)) return
        val world = player.entityWorld
        Cooldowns.set(player, player.getEquippedStack(EquipmentSlot.LEGS), GearConfig.key("tail_cooldown"))
        val radius = GearConfig.num("tail_radius")
        val dmg = GearConfig.float("tail_damage")
        val look = player.rotationVector
        val start = kotlin.math.atan2(look.x, look.z)
        val sweep = 12
        guardUntil[player.uuid] = GearTasks.now + sweep + 2
        dev.henny.hha_arius.gear.art.ArtFx.send(world, dev.henny.hha_arius.net.FxKind.DRAGON_TAIL, player.entityPos, a = radius.toFloat(), b = start.toFloat(), life = sweep + 10, e1 = player.id)
        GearSounds.play(world, player.entityPos, "frost_step", SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 0.9f, 0.75f)
        GearSounds.play(world, player.entityPos, "frost_step", SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.55f)
        val hit = HashSet<Int>()
        var prev = start
        for (t in 1..sweep) {
            GearTasks.later(t) {
                if (!GearCombat.stillThere(player, world)) return@later
                val k = t / sweep.toDouble()
                val cur = start + 2 * Math.PI * (1 - (1 - k) * (1 - k) * (1 - k))
                val at = player.entityPos
                for (e in world.getOtherEntities(player, Box.of(at, radius * 2 + 2, 4.0, radius * 2 + 2))) {
                    if (e.id in hit || !GearCombat.canHit(player, e)) continue
                    val dx = e.x - at.x
                    val dz = e.z - at.z
                    if (dx * dx + dz * dz > (radius + e.width / 2) * (radius + e.width / 2)) continue
                    // Wurde der Winkel des Ziels zwischen dem letzten und diesem Tick überstrichen?
                    var a = kotlin.math.atan2(dx, dz)
                    while (a < prev) a += 2 * Math.PI
                    if (a > cur && !(dx * dx + dz * dz < 2.25)) continue
                    hit.add(e.id)
                    val target = e as LivingEntity
                    dev.henny.hha_arius.boss.PlayerHit.marked(dev.henny.hha_arius.boss.PlayerHit.Kind.ABILITY) { target.damage(world, world.damageSources.playerAttack(player), dmg) }
                    if (!GearCombat.isHeavy(target)) GearCombat.push(target, at, 0.9, 0.45)
                    target.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2, false, true, true), player)
                    target.frozenTicks = (target.frozenTicks + 40).coerceAtMost(target.minFreezeDamageTicks + 60)
                    Frostbite.add(target, 22f)
                    world.spawnParticles(ParticleTypes.SNOWFLAKE, e.x, e.y + e.height * 0.6, e.z, 12, 0.3, 0.3, 0.3, 0.1)
                    GearSounds.play(world, e.entityPos, "frost_step", SoundEvents.BLOCK_GLASS_BREAK, 0.6f, 1.4f)
                    Resonance.markCombat(player)
                }
                prev = cur
                if (t == sweep) {
                    dev.henny.hha_arius.gear.art.ArtFx.send(world, dev.henny.hha_arius.net.FxKind.CLEAR_WAVE, at, a = radius.toFloat() + 1.5f, life = 14)
                    world.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.3, at.z, 24, radius * 0.4, 0.1, radius * 0.4, 0.12)
                }
            }
        }
    }

    // ---------------------------------------------------------------- Schwingensprung (frühere Beinschienen-Fähigkeit, ungenutzt)

    /**
     * Schwingensprung: Lichtschwingen öffnen sich, der Spieler springt weit nach vorn (Laufrichtung) und schlägt mit einer
     * Frost-Schockwelle auf: Schaden und Hochwerfen im Umkreis, Schockring, Eissichel. Während des Sprungs keine
     * Nahkampftreffer und kein Fallschaden.
     */
    @Suppress("unused")
    private fun castWingLeap(player: ServerPlayerEntity) {
        if (!ready(player, EquipmentSlot.LEGS, "frost_step")) return
        val cost = GearConfig.float("wing_leap_cost")
        if (needResonance(player, cost)) return
        if (!Resonance.spend(player, cost)) return
        val world = player.entityWorld
        Cooldowns.set(player, player.getEquippedStack(EquipmentSlot.LEGS), GearConfig.key("wing_leap_cooldown"))
        val dir = moveDirection(player)
        guardUntil[player.uuid] = GearTasks.now + 14
        GearVfx.wings(world, Vfx.Style.HEAVEN, player, 24, 2.2f)
        GearVfx.trail(world, Vfx.Style.STORM, player, 0.5f, 24)
        GearSounds.play(world, player.entityPos, "frost_step", SoundEvents.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.3f)
        dev.henny.hha_arius.gear.art.ArtFx.send(world, dev.henny.hha_arius.net.FxKind.FRAGMENTS, player.entityPos, a = 0.35f, life = 16, e1 = player.id)
        Fx.launchPlayer(player, Vec3d(dir.x * GearConfig.num("wing_leap_forward"), GearConfig.num("wing_leap_up"), dir.z * GearConfig.num("wing_leap_forward")))
        player.fallDistance = 0.0
        // Landung abwarten (höchstens 3 s), dann Schockwelle
        var waited = 0
        lateinit var check: () -> Unit
        check = {
            waited++
            player.fallDistance = 0.0
            if (GearCombat.stillThere(player, world)) {
                if ((player.isOnGround && waited > 4) || waited > 60) slam(player, world) else GearTasks.later(1, check)
            }
        }
        GearTasks.later(2, check)
    }

    private fun slam(player: ServerPlayerEntity, world: ServerWorld) {
        val at = player.entityPos
        val radius = GearConfig.num("wing_leap_radius")
        val dmg = GearConfig.float("wing_leap_damage")
        GearVfx.shockwave(world, Vfx.Style.HEAVEN, at, radius.toFloat(), 14, 1.2f)
        GearVfx.nova(world, Vfx.Style.STORM, at.add(0.0, 0.5, 0.0), 1.8f, 10)
        dev.henny.hha_arius.gear.art.ArtFx.send(world, dev.henny.hha_arius.net.FxKind.CLEAR_WAVE, at, a = radius.toFloat() + 1.5f, life = 16)
        world.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.2, at.z, 30, radius * 0.4, 0.1, radius * 0.4, 0.15)
        GearSounds.play(world, at, "white_core", SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.5f, 1.5f)
        for (e in world.getOtherEntities(player, Box.of(at, radius * 2, radius * 2, radius * 2))) {
            if (!GearCombat.canHit(player, e) || e.squaredDistanceTo(at) > radius * radius) continue
            val target = e as LivingEntity
            dev.henny.hha_arius.boss.PlayerHit.marked(dev.henny.hha_arius.boss.PlayerHit.Kind.ABILITY) { target.damage(world, world.damageSources.playerAttack(player), dmg) }
            if (!GearCombat.isHeavy(target)) GearCombat.push(target, at, 0.6, 0.55)
            target.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1, false, true, true), player)
            Resonance.markCombat(player)
        }
    }

    // ---------------------------------------------------------------- Frostschritt (frühere Beinschienen-Fähigkeit, ungenutzt)

    private fun castFrostStep(player: ServerPlayerEntity) {
        if (!ready(player, EquipmentSlot.LEGS, "frost_step")) return
        val cost = GearConfig.float("frost_step_cost")
        if (needResonance(player, cost)) return
        if (!Resonance.spend(player, cost)) return
        val world = player.entityWorld
        Cooldowns.set(player, player.getEquippedStack(EquipmentSlot.LEGS), GearConfig.key("frost_step_cooldown"))

        val dir = moveDirection(player)
        val distance = GearConfig.num("frost_step_distance")
        val dashTicks = 3
        val speed = distance / dashTicks
        guardUntil[player.uuid] = GearTasks.now + GearConfig.ticks("frost_step_guard_ticks")
        val placeIce = GearConfig.on("frost_step_ice") &&
            (GearConfig.on("frost_step_ice_in_arena") || !Encounters.isParticipant(player))

        GearSounds.play(world, player.entityPos, "frost_step", SoundEvents.BLOCK_POWDER_SNOW_STEP, 1f, 1.2f)
        GearVfx.trail(world, Vfx.Style.STORM, player, 0.45f, 10)
        // Nachbild: am Startpunkt zerspringt eine Frost-Silhouette in Splitter (seit 1.2.2).
        dev.henny.hha_arius.gear.art.ArtFx.send(world, dev.henny.hha_arius.net.FxKind.FRAGMENTS, player.entityPos, a = 0.35f, life = 16, e1 = player.id)

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
                // Ankunft: Eis-Aufprall mit Sichel in Laufrichtung.
                val at = player.entityPos
                GearVfx.nova(world, Vfx.Style.STORM, at.add(0.0, 0.6, 0.0), 1.4f, 8)
                GearVfx.crescent(world, Vfx.Style.STORM, at.add(0.0, 0.8, 0.0), dir, 2.4f, 1.4f, 6)
                world.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.3, at.z, 14, 0.5, 0.2, 0.5, 0.08)
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

    // ---------------------------------------------------------------- Weißer Kern (Brustplatte, Ability 1)

    private fun castWhiteCore(player: ServerPlayerEntity) {
        if (!ready(player, EquipmentSlot.CHEST, "white_core")) return
        val cost = GearConfig.float("white_core_cost")
        if (needResonance(player, cost)) return
        if (!Resonance.spend(player, cost)) return
        val world = player.entityWorld
        Cooldowns.set(player, player.getEquippedStack(EquipmentSlot.CHEST), GearConfig.key("white_core_cooldown"))

        val charge = GearConfig.ticks("white_core_charge_ticks")
        dev.henny.hha_arius.gear.art.Focus.begin(player, dev.henny.hha_arius.gear.art.Focus.Busy.ABILITY, GearTasks.now + charge)
        GearVfx.flare(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.2, 0.0), 0.7f, charge)
        GearSounds.play(world, player.entityPos, "core.form", SoundEvents.BLOCK_BEACON_POWER_SELECT, 0.7f, 1.6f)

        GearTasks.later(charge) {
            if (GearCombat.stillThere(player, world)) {
                releaseWhiteCore(player, world)
                dev.henny.hha_arius.gear.art.Focus.reverb(player)
            }
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
                    dev.henny.hha_arius.boss.PlayerHit.marked(dev.henny.hha_arius.boss.PlayerHit.Kind.ABILITY) { target.damage(world, world.damageSources.playerAttack(player), damage) }
                    GearCombat.push(target, center, push, 0.35)
                    anyHit = true
                }
            }
        }
        if (anyHit) Resonance.markCombat(player)
        player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, GearConfig.ticks("white_core_resistance_ticks"), 0, false, true, true))

        GearVfx.shockwave(world, Vfx.Style.HEAVEN, player.entityPos, radius.toFloat(), 16, 1.4f)
        GearVfx.nova(world, Vfx.Style.STORM, center, 2.2f, 12)
        // Seit 1.2.2 wuchtiger: acht Eissicheln strahlen aus, eine Frostwelle läuft über den Boden, ein Nachhall folgt.
        for (k in 0 until 8) {
            val ang = k / 8.0 * 2 * Math.PI
            GearVfx.crescent(world, Vfx.Style.STORM, center, Vec3d(cos(ang), 0.05, sin(ang)), radius.toFloat(), 1.2f, 8)
        }
        dev.henny.hha_arius.gear.art.ArtFx.send(world, dev.henny.hha_arius.net.FxKind.CLEAR_WAVE, player.entityPos, a = radius.toFloat() + 2f, life = 18)
        world.spawnParticles(ParticleTypes.SNOWFLAKE, center.x, center.y, center.z, 40, radius * 0.4, 0.4, radius * 0.4, 0.15)
        GearTasks.later(5) { GearVfx.shockwave(world, Vfx.Style.STORM, player.entityPos, (radius * 1.4).toFloat(), 12, 0.8f) }
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

    fun place(world: ServerWorld, pos: BlockPos) {
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

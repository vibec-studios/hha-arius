package dev.henny.hha_arius.gear.art

import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.gear.GearCombat
import dev.henny.hha_arius.gear.GearConfig
import dev.henny.hha_arius.gear.GearSounds
import dev.henny.hha_arius.gear.GearTasks
import dev.henny.hha_arius.gear.GearVfx
import dev.henny.hha_arius.gear.Resonance
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributeModifier
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import java.util.UUID
import kotlin.math.acos

/**
 * Nahkampfmodus der Handschuhe (Server, maßgeblich). Kompaktes Moveset:
 * * Linksklick: Jab → Cross → Körperhaken (Folge, solange man im Verbindungsfenster weiterklickt).
 * * Rechtsklick: Seitentritt; direkt nach Jab/Cross schneller; direkt nach dem Haken der Drehtritt als Abschluss.
 * * Schleichen + Rechtsklick: Handflächen-Parade (kurzes Fenster); gelingt sie gegen einen Nahkampfschlag von vorn,
 *   folgt automatisch ein Konterstoß – nur wenn der Angreifer noch in Reichweite vor einem steht (kein Heranziehen).
 *
 * Gegen Dauerklicken: Eingaben während einer Aktion werden höchstens EINMAL vorgemerkt (in den letzten Ticks der
 * Erholung), alles andere verfällt. Jede Aktion hat feste Ausholzeit und Erholung. Rückstoß der Tritte trifft
 * dasselbe Ziel höchstens alle `melee_knock_guard_ticks` (keine Rückstoßketten); schwere Gegner (Bosse, große Wesen)
 * werden nie bewegt. Keine Betäubung.
 */
object GauntletCombat {

    private class State {
        var move: Move? = null
        var start = 0L
        var windup = 0
        var hit = false
        var chain = 0
        var chainUntil = 0L
        var buffer: ArtAction? = null
    }

    private val states = HashMap<UUID, State>()
    private val knockGuard = HashMap<Int, Long>()
    private val SLOW_ID = HhaArius.id("gauntlet_move_slow")

    fun init() {
        GearTasks.everyTick { tickAll() }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (entity is ServerPlayerEntity) !tryParry(entity, source) else true
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            removeSlow(handler.player)
            states.remove(handler.player.uuid)
        }
    }

    fun holdsMelee(player: ServerPlayerEntity): Boolean {
        val stack = player.mainHandStack
        return GauntletsItem.isGauntlets(stack) && GauntletsItem.mode(stack) == GauntletMode.MELEE
    }

    fun input(player: ServerPlayerEntity, action: ArtAction) {
        if (!GearConfig.on("gauntlets") || !holdsMelee(player) || player.isSpectator) return
        val s = states.getOrPut(player.uuid) { State() }
        val current = s.move
        if (current != null) {
            // Höchstens eine Vormerkung, und nur kurz vor Ende der Erholung.
            if (action != ArtAction.PARRY && s.buffer == null && GearTasks.now >= s.start + current.total - 4) s.buffer = action
            return
        }
        if (Focus.drawing(player) || !Focus.isFree(player)) return
        start(player, s, action)
    }

    private fun start(player: ServerPlayerEntity, s: State, action: ArtAction) {
        val now = GearTasks.now
        val linked = now <= s.chainUntil
        val chain = if (linked) s.chain else 0
        var windupOverride = -1
        val airborne = !player.isOnGround && !player.isTouchingWater && player.fallDistance > 0.2
        val move = when (action) {
            ArtAction.ATTACK if airborne && chain == 0 -> Move.DIVE_KICK
            ArtAction.ATTACK if player.isSprinting && chain == 0 -> Move.FLYING_KICK
            ArtAction.KICK if !player.isOnGround && !player.isTouchingWater -> Move.WHIRL_KICK
            ArtAction.ATTACK -> when (chain) {
                1 -> Move.CROSS
                2 -> Move.HOOK
                3 -> Move.UPPERCUT
                else -> Move.JAB
            }
            ArtAction.KICK -> when (chain) {
                3 -> Move.SPIN_KICK
                4 -> Move.AXE_KICK
                1, 2 -> Move.SIDE_KICK.also { windupOverride = 4 }
                else -> Move.SIDE_KICK
            }
            ArtAction.PARRY -> Move.PARRY
            else -> return
        }
        begin(player, s, move, if (windupOverride > 0) windupOverride else move.windup)
    }

    private fun begin(player: ServerPlayerEntity, s: State, move: Move, windup: Int) {
        val now = GearTasks.now
        s.move = move
        s.start = now
        s.windup = windup
        s.hit = false
        s.chain = 0
        val total = windup + move.recover
        Focus.begin(player, Focus.Busy.MOVE, now + total) { abort(player) }
        applySlow(player, move.slow)
        player.isSprinting = false
        player.resetTicksSinceLastAttack()
        ArtNet.anim(player, move.anim, total, windup)
        val world = player.entityWorld
        val pitch = when (move) {
            Move.JAB -> 1.5f
            Move.CROSS -> 1.3f
            Move.HOOK -> 1.1f
            Move.SPIN_KICK -> 0.8f
            Move.UPPERCUT -> 1.0f
            Move.AXE_KICK -> 0.7f
            else -> 1.0f
        }
        stepIn(player, move)
        if (move == Move.AXE_KICK) {
            // Absprung: kurz hoch, die Ferse kommt zum Treffer herab.
            dev.henny.hha.logic.Fx.launchPlayer(player, player.velocity.add(0.0, 0.42, 0.0))
        }
        val look = horizontalLook(player)
        when (move) {
            Move.FLYING_KICK -> dev.henny.hha.logic.Fx.launchPlayer(player, Vec3d(look.x * 1.15, 0.3, look.z * 1.15))
            Move.DIVE_KICK -> dev.henny.hha.logic.Fx.launchPlayer(player, Vec3d(look.x * 0.7, -1.1, look.z * 0.7))
            Move.WHIRL_KICK -> dev.henny.hha.logic.Fx.launchPlayer(player, Vec3d(player.velocity.x, 0.32, player.velocity.z))
            else -> {}
        }
        if (move == Move.FLYING_KICK || move == Move.DIVE_KICK || move == Move.WHIRL_KICK) player.fallDistance = 0.0
        if (move != Move.PARRY) {
            GearSounds.play(world, player.entityPos, "gauntlet.swing", SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, 0.5f, pitch)
        } else {
            GearSounds.play(world, player.entityPos, "gauntlet.parry_ready", SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), 0.5f, 1.6f)
        }
    }

    private fun abort(player: ServerPlayerEntity) {
        val s = states[player.uuid] ?: return
        if (s.move == null) return
        s.move = null
        s.buffer = null
        s.chain = 0
        removeSlow(player)
        ArtNet.anim(player, Anim.NONE, 0)
    }

    private fun tickAll() {
        if (states.isEmpty()) return
        val now = GearTasks.now
        for ((id, s) in states.entries.toList()) {
            val move = s.move ?: continue
            val player = GearCombat.player(id) ?: continue
            if (!holdsMelee(player) || !player.isAlive) {
                Focus.cancel(player)
                abort(player)
                continue
            }
            val t = now - s.start
            if (!s.hit && t >= s.windup) {
                s.hit = true
                if (move != Move.PARRY) strike(player, move)
            }
            if (t >= s.windup + move.recover) finish(player, s, move)
        }
    }

    private fun finish(player: ServerPlayerEntity, s: State, move: Move) {
        s.move = null
        removeSlow(player)
        Focus.end(player, Focus.Busy.MOVE)
        s.chain = when (move) {
            Move.JAB -> 1
            Move.CROSS -> 2
            Move.HOOK -> 3
            Move.UPPERCUT -> 4
            else -> 0
        }
        s.chainUntil = GearTasks.now + GearConfig.ticks("melee_link_ticks")
        val buffered = s.buffer
        s.buffer = null
        if (buffered != null && holdsMelee(player) && Focus.isFree(player)) start(player, s, buffered)
    }

    // ------------------------------------------------------------------------------------------------- Treffer

    private fun strike(player: ServerPlayerEntity, move: Move) {
        val world = player.entityWorld
        val targets = targets(player, move)
        val stack = player.mainHandStack
        val base = GearConfig.num("melee_base_damage") + (player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE) - 1.0).coerceAtLeast(0.0)
        val damage = (base * move.mult).toFloat()
        var any = false
        for (target in targets) {
            target.timeUntilRegen = 0
            if (!target.damage(world, world.damageSources.playerAttack(player), damage)) continue
            any = true
            if (move.knock > 0 && !GearCombat.isHeavy(target)) {
                val last = knockGuard[target.id] ?: Long.MIN_VALUE
                val strong = move == Move.SIDE_KICK || move == Move.SPIN_KICK || move == Move.COUNTER || move == Move.FLYING_KICK || move == Move.WHIRL_KICK
                if (!strong || GearTasks.now - last >= GearConfig.ticks("melee_knock_guard_ticks")) {
                    if (strong) knockGuard[target.id] = GearTasks.now
                    GearCombat.push(target, player.entityPos, move.knock, move.lift)
                }
            }
            if (GearCombat.worthy(player, target)) Resonance.gain(player, GearConfig.num(move.gainKey))
            val at = target.entityPos.add(0.0, target.height * 0.6, 0.0)
            world.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 4, 0.15, 0.15, 0.15, 0.1)
        }
        if (any) {
            Resonance.markCombat(player)
            stack.damage(1, player, EquipmentSlot.MAINHAND)
            val (sound, pitch) = when (move) {
                Move.SIDE_KICK, Move.SPIN_KICK, Move.FLYING_KICK, Move.WHIRL_KICK -> SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK to 0.9f
                Move.DIVE_KICK -> SoundEvents.ENTITY_PLAYER_ATTACK_CRIT to 0.8f
                Move.COUNTER -> SoundEvents.ENTITY_PLAYER_ATTACK_CRIT to 1.2f
                else -> SoundEvents.ENTITY_PLAYER_ATTACK_STRONG to 1.25f
            }
            GearSounds.play(world, player.entityPos, "gauntlet.hit", sound, 0.8f, pitch)
        }
        strikeFx(player, move, any)
        if (knockGuard.size > 512) knockGuard.entries.removeIf { GearTasks.now - it.value > 200 }
    }

    /**
     * Vorrücken: Ist ein Ziel vorn in Reichweite + 2, schiebt der Schlag den Spieler ein Stück darauf zu (nur am Boden,
     * nie näher als 1,6 Blöcke). Gibt Schlägen Gewicht, statt auf der Stelle zu wedeln.
     */
    private fun stepIn(player: ServerPlayerEntity, move: Move) {
        val speed = GearConfig.num("melee_step_in")
        if (speed <= 0.0 || move == Move.PARRY || move == Move.COUNTER || !player.isOnGround) return
        val look = horizontalLook(player)
        val box = player.boundingBox.expand(move.reach + 2.0)
        val target = player.entityWorld.getOtherEntities(player, box) { GearCombat.canHit(player, it) }
            .filter { val d = it.entityPos.subtract(player.entityPos); Vec3d(d.x, 0.0, d.z).normalize().dotProduct(look) > 0.7 }
            .minByOrNull { it.squaredDistanceTo(player) } ?: return
        val dist = kotlin.math.sqrt(target.squaredDistanceTo(player))
        if (dist < 1.6) return
        val k = when (move) { Move.JAB -> 0.8; Move.CROSS -> 0.9; Move.AXE_KICK -> 0.6; else -> 1.0 }
        val v = look.multiply(minOf(speed * k * (dist - 1.6), speed * k * 2.2))
        dev.henny.hha.logic.Fx.launchPlayer(player, player.velocity.add(v.x, 0.0, v.z))
    }

    /** Treffer-Effekte je Schlag (sichtbar auch ohne Treffer: Luftstoß, damit jeder Schlag Wucht zeigt). */
    private fun strikeFx(player: ServerPlayerEntity, move: Move, hit: Boolean) {
        val world = player.entityWorld
        val look = horizontalLook(player)
        val chest = player.entityPos.add(0.0, 1.1, 0.0)
        when (move) {
            Move.JAB, Move.CROSS, Move.COUNTER ->
                GearVfx.crescent(world, Vfx.Style.STORM, chest.add(look.multiply(0.9)), player.rotationVector, 1.6f, if (hit) 1.2f else 0.8f, 4)
            Move.HOOK -> {
                GearVfx.crescent(world, Vfx.Style.STORM, chest.add(look.multiply(0.8)), look, 2.0f, 1.6f, 5)
                if (hit) world.spawnParticles(ParticleTypes.SNOWFLAKE, chest.x + look.x * 1.8, chest.y, chest.z + look.z * 1.8, 10, 0.25, 0.25, 0.25, 0.05)
            }
            Move.UPPERCUT -> {
                GearVfx.crescent(world, Vfx.Style.STORM, chest.add(look.multiply(0.7)).add(0.0, -0.4, 0.0), Vec3d(look.x * 0.3, 1.0, look.z * 0.3).normalize(), 2.6f, 1.4f, 7)
                world.spawnParticles(ParticleTypes.CLOUD, chest.x + look.x * 1.4, chest.y - 0.6, chest.z + look.z * 1.4, 8, 0.2, 0.6, 0.2, 0.08)
            }
            Move.SPIN_KICK -> GearVfx.crescent(world, Vfx.Style.STORM, player.entityPos.add(0.0, 0.7, 0.0), look, 3.2f, 2.6f, 6)
            Move.FLYING_KICK -> {
                GearVfx.crescent(world, Vfx.Style.STORM, chest.add(look.multiply(1.2)), look, 2.4f, 2.0f, 6)
                world.spawnParticles(ParticleTypes.CLOUD, chest.x, chest.y - 0.6, chest.z, 10, 0.4, 0.2, 0.4, 0.06)
            }
            Move.DIVE_KICK -> {
                val at = player.entityPos
                GearVfx.shockwave(world, Vfx.Style.HEAVEN, at, 4.0f, 14, 1.3f)
                world.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.2, at.z, 36, 1.6, 0.15, 1.6, 0.14)
                world.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 1, 0.0, 0.0, 0.0, 0.0)
                GearSounds.play(world, at, "gauntlet.slam", SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.6f, 1.5f)
            }
            Move.WHIRL_KICK -> {
                // Dryleaf-Wirbelwind: zwei gegenläufige Windsicheln rundum, Blätterwirbel aus Schnee
                GearVfx.crescent(world, Vfx.Style.STORM, player.entityPos.add(0.0, 0.9, 0.0), look, 3.6f, 3.0f, 7)
                GearVfx.crescent(world, Vfx.Style.STORM, player.entityPos.add(0.0, 0.9, 0.0), look.multiply(-1.0), 3.6f, 3.0f, 7)
                world.spawnParticles(ParticleTypes.CLOUD, player.x, player.y + 0.8, player.z, 16, 1.2, 0.3, 1.2, 0.1)
            }
            Move.AXE_KICK -> {
                val at = player.entityPos.add(look.multiply(1.8))
                GearVfx.shockwave(world, Vfx.Style.HEAVEN, at, 3.6f, 14, 1.2f)
                GearVfx.crescent(world, Vfx.Style.STORM, player.entityPos.add(0.0, 2.2, 0.0), Vec3d(look.x * 0.4, -1.0, look.z * 0.4).normalize(), 2.4f, 2.0f, 6)
                world.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.2, at.z, 30, 1.4, 0.15, 1.4, 0.12)
                world.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 1, 0.0, 0.0, 0.0, 0.0)
                GearSounds.play(world, at, "gauntlet.slam", SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.6f, 1.4f)
            }
            else -> {}
        }
    }

    private fun horizontalLook(player: ServerPlayerEntity): Vec3d {
        val v = player.rotationVector
        val h = Vec3d(v.x, 0.0, v.z)
        return if (h.lengthSquared() < 1.0e-6) Vec3d(0.0, 0.0, 1.0) else h.normalize()
    }

    /** Ziele in Reichweite und im Winkel vor dem Spieler, mit freier Sicht; nächstes zuerst. */
    private fun targets(player: ServerPlayerEntity, move: Move): List<LivingEntity> {
        val world = player.entityWorld
        val eye = player.eyePos
        val kick = move == Move.SIDE_KICK || move == Move.SPIN_KICK
        val look = if (kick) horizontalLook(player) else player.rotationVector.normalize()
        val reach = move.reach
        val box = player.boundingBox.expand(reach + 1.0)
        val out = ArrayList<Pair<LivingEntity, Double>>()
        for (e in world.getOtherEntities(player, box)) {
            if (!GearCombat.canHit(player, e)) continue
            val target = e as LivingEntity
            val tb = target.boundingBox
            val p = Vec3d(MathHelper.clamp(eye.x, tb.minX, tb.maxX), MathHelper.clamp(eye.y, tb.minY, tb.maxY), MathHelper.clamp(eye.z, tb.minZ, tb.maxZ))
            val dist = eye.distanceTo(p)
            if (dist > reach) continue
            var dir = tb.center.subtract(eye)
            if (kick) dir = Vec3d(dir.x, 0.0, dir.z)
            if (dir.lengthSquared() > 1.0e-6) {
                val angle = Math.toDegrees(acos(dir.normalize().dotProduct(look).coerceIn(-1.0, 1.0)))
                // Breite Ziele: der Winkel zählt bis zur Kante, nicht nur zur Mitte.
                val slack = Math.toDegrees(Math.atan2(tb.lengthX / 2.0, dir.length().coerceAtLeast(0.5)))
                if (angle > move.halfAngle + slack) continue
            }
            val hit = world.raycast(RaycastContext(eye, if (p == eye) tb.center else p, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
            if (hit.type == HitResult.Type.BLOCK && hit.pos.squaredDistanceTo(eye) < dist * dist - 0.05) continue
            out.add(target to dist)
        }
        // Langer Drachenkörper: Der Client hat ein Glied anvisiert (die Rumpf-Box liegt oft weit weg).
        dev.henny.hha_arius.boss.DragonAim.gauntletTarget(player)?.let { d -> if (out.none { it.first === d }) out.add(0, d to 0.0) }
        return out.sortedBy { it.second }.take(move.maxTargets).map { it.first }
    }

    // ------------------------------------------------------------------------------------------------- Parade

    private fun tryParry(player: ServerPlayerEntity, source: DamageSource): Boolean {
        val s = states[player.uuid] ?: return false
        if (s.move != Move.PARRY || GearTasks.now - s.start >= s.windup) return false
        if (source.isIn(DamageTypeTags.BYPASSES_SHIELD) || source.isIn(DamageTypeTags.IS_EXPLOSION)) return false
        val direct = source.source ?: return false
        val from = direct.entityPos.subtract(player.entityPos)
        val facing = horizontalLook(player)
        if (Vec3d(from.x, 0.0, from.z).normalize().dotProduct(facing) < 0.2) return false
        val world = player.entityWorld
        if (direct is ProjectileEntity) {
            GearCombat.discardProjectile(world, direct)
            GearSounds.play(world, player.entityPos, "gauntlet.parry", SoundEvents.ITEM_SHIELD_BLOCK.value(), 0.8f, 1.4f)
            return true
        }
        val attacker = source.attacker as? LivingEntity ?: return false
        if (direct !== attacker) return false
        // Gelungen: Treffer abgefangen, Konterstoß folgt (trifft nur, wenn der Angreifer noch vorn in Reichweite ist).
        Resonance.gain(player, GearConfig.num("melee_gain_parry"))
        GearSounds.play(world, player.entityPos, "gauntlet.parry", SoundEvents.ITEM_SHIELD_BLOCK.value(), 1f, 1.3f)
        GearVfx.flare(world, Vfx.Style.HEAVEN, player.eyePos.add(facing.multiply(0.7)).add(0.0, -0.3, 0.0), 0.7f, 5)
        s.move = null
        removeSlow(player)
        Focus.end(player, Focus.Busy.MOVE)
        begin(player, s, Move.COUNTER, Move.COUNTER.windup)
        return true
    }

    // ------------------------------------------------------------------------------------------------- Helfer

    private fun applySlow(player: ServerPlayerEntity, factor: Double) {
        val inst = player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED) ?: return
        inst.removeModifier(SLOW_ID)
        if (factor < 1.0) inst.addTemporaryModifier(EntityAttributeModifier(SLOW_ID, factor - 1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
    }

    private fun removeSlow(player: ServerPlayerEntity) {
        player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED)?.removeModifier(SLOW_ID)
    }
}

package dev.henny.hha_arius.gear.art

import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.gear.Gear
import dev.henny.hha_arius.gear.GearCombat
import dev.henny.hha_arius.gear.GearConfig
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.GearSounds
import dev.henny.hha_arius.gear.GearTasks
import dev.henny.hha_arius.gear.GearVfx
import dev.henny.hha_arius.gear.Resonance
import dev.henny.hha_arius.net.FxKind
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import java.util.UUID

/**
 * Das weiße Loch als Setfähigkeit (volles Set, Ability 3 – ersetzt bei vollem Set den HHA-Ultra-Modus wie bei den
 * anderen Addon-Sets). Begründung: Es ist Arius' Erkennungszeichen und eine seltene „letzte Karte“; als Setfähigkeit
 * gehört es zur Rüstung, die man sich im Kampf verdient, und es bleibt unabhängig von der gehaltenen Waffe. Die
 * Yang-Techniken der Handschuhe bleiben dadurch formend (Kugel, Schild, Bauwerke) und doppeln es nicht.
 *
 * Ablauf: braucht volle Resonanz (verbraucht alles), 1 s Wirken (Hände führen zusammen), dann steht ein weißer Kern bis
 * 16 Blöcke vor dem Spieler: 2 s Sog auf leichte Gegner (Bosse/schwere Wesen werden nicht bewegt, Spieler nur halb so
 * stark), danach Kollaps mit Druckwelle (Schaden im Radius, kurze Verlangsamung, zerschneidet Geschosse).
 * Abklingzeit 120 s. Keine Blockschäden.
 */
object WhiteHole {

    private val cooldownUntil = HashMap<UUID, Long>()

    fun cooldownLeft(player: ServerPlayerEntity): Int = ((cooldownUntil[player.uuid] ?: 0L) - GearTasks.now).coerceIn(0, 1000000).toInt()

    fun cast(player: ServerPlayerEntity) {
        if (!GearConfig.on("white_hole") || !Gear.hasFullSet(player)) return
        val left = cooldownLeft(player)
        if (left > 0) {
            GearCombat.actionbar(player, "hha_arius.msg.art.cooldown", net.minecraft.text.Text.translatable("hha_arius.art.white_hole"), String.format("%.0f", left / 20.0))
            return
        }
        if (!Focus.isFree(player) || Focus.inReverb(player)) return
        val cost = GearConfig.float("white_hole_cost").coerceAtMost(Resonance.max())
        if (Resonance.get(player) < cost) {
            GearCombat.actionbar(player, "hha_arius.msg.need_resonance", cost.toInt(), Resonance.get(player).toInt())
            return
        }
        Resonance.spendAll(player)
        val world = player.entityWorld
        cooldownUntil[player.uuid] = GearTasks.now + GearConfig.ticks("white_hole_cooldown")
        player.itemCooldownManager.set(GearItems.WHITE_HOLE_GROUP, GearConfig.ticks("white_hole_cooldown"))
        SpellCasting.markDirty(player)
        val castTicks = ArtTimings.WHITE_HOLE_CAST
        Focus.begin(player, Focus.Busy.ABILITY, GearTasks.now + ArtTimings.WHITE_HOLE)
        ArtNet.anim(player, Anim.WHITE_HOLE, ArtTimings.WHITE_HOLE, 0)
        player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, castTicks, 2, false, false, false))
        GearSounds.play(world, player.entityPos, "white_hole.form", SoundEvents.BLOCK_BEACON_POWER_SELECT, 0.8f, 0.6f)
        GearTasks.later(castTicks) {
            if (!GearCombat.stillThere(player, world)) return@later
            open(player, world, target(player, world))
            Focus.reverb(player)
        }
    }

    private fun target(player: ServerPlayerEntity, world: ServerWorld): Vec3d {
        val range = GearConfig.num("white_hole_range")
        val eye = player.eyePos
        val end = eye.add(player.rotationVector.multiply(range))
        val hit = world.raycast(RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
        val at = if (hit.type == HitResult.Type.BLOCK) hit.pos.subtract(player.rotationVector.multiply(1.0)) else end
        return at.add(0.0, 0.6, 0.0)
    }

    private fun open(player: ServerPlayerEntity, world: ServerWorld, center: Vec3d) {
        val radius = GearConfig.num("white_hole_radius")
        val pull = GearConfig.num("white_hole_pull")
        val pullTicks = 40
        ArtFx.send(world, FxKind.CORE, center, a = 0.9f, life = pullTicks + 6)
        ArtFx.send(world, FxKind.REVERSE_WIND, center, a = radius.toFloat(), life = pullTicks)
        GearSounds.play(world, center, "white_hole.pull", SoundEvents.BLOCK_PORTAL_AMBIENT, 0.8f, 1.6f)
        for (t in 1..pullTicks) {
            GearTasks.later(t) {
                for (e in world.getOtherEntities(player, Box.of(center, radius * 2, radius * 2, radius * 2))) {
                    if (e.squaredDistanceTo(center) > radius * radius) continue
                    if (e is ProjectileEntity && GearCombat.isHostileProjectile(player, e)) {
                        GearCombat.discardProjectile(world, e)
                        continue
                    }
                    if (!GearCombat.canHit(player, e)) continue
                    val target = e as LivingEntity
                    if (GearCombat.isHeavy(target)) continue
                    val d = center.subtract(target.entityPos.add(0.0, target.height / 2.0, 0.0))
                    if (d.lengthSquared() < 0.6) continue
                    val strength = if (target is ServerPlayerEntity) pull * 0.5 else pull
                    val v = target.velocity.multiply(0.8).add(d.normalize().multiply(strength))
                    if (target is ServerPlayerEntity) dev.henny.hha.logic.Fx.launchPlayer(target, v)
                    else {
                        target.velocity = v
                        target.velocityDirty = true
                    }
                }
            }
        }
        GearTasks.later(pullTicks) { collapse(player, world, center, radius) }
    }

    private fun collapse(player: ServerPlayerEntity, world: ServerWorld, center: Vec3d, radius: Double) {
        val damage = GearConfig.float("white_hole_damage")
        var any = false
        for (e in world.getOtherEntities(player, Box.of(center, radius * 2, radius * 2, radius * 2))) {
            if (e.squaredDistanceTo(center) > radius * radius || !GearCombat.canHit(player, e)) continue
            val target = e as LivingEntity
            if (target.damage(world, world.damageSources.indirectMagic(player, player), damage)) any = true
            target.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1, false, true, true), player)
            if (!GearCombat.isHeavy(target)) GearCombat.push(target, center, if (target is ServerPlayerEntity) 0.5 else 0.9, 0.3)
        }
        if (any) Resonance.markCombat(player)
        ArtFx.send(world, FxKind.CLEAR_WAVE, center.add(0.0, -0.5, 0.0), a = (radius + 2).toFloat(), life = 14)
        GearVfx.nova(world, Vfx.Style.HEAVEN, center, radius.toFloat() * 0.6f, 10)
        GearSounds.play(world, center, "white_hole.collapse", SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.5f)
    }
}

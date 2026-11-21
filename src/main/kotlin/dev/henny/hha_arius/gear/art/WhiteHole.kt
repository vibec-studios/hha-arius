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
 * 16 Blöcke vor dem Spieler. Seit 1.10.0 **stößt** er ab, wie es ein weißes Loch soll (vorher zog er an): 2 s wachsender
 * Druck nach außen mit drei Druckwellen (je etwas Schaden), dann die Eruption – eine Druckwelle bis 1,6 × Radius
 * (Schaden, Verlangsamung, weit weg geschleudert, zerschneidet Geschosse). Bosse/schwere Wesen werden nicht bewegt,
 * Spieler nur halb so stark.
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
        val cd = ArtTest.cd(player, GearConfig.ticks("white_hole_cooldown"))
        cooldownUntil[player.uuid] = GearTasks.now + cd
        player.itemCooldownManager.set(GearItems.WHITE_HOLE_GROUP, cd)
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
        val push = GearConfig.num("white_hole_push")
        val pushTicks = 40
        ArtFx.send(world, FxKind.WHITE_HOLE, center, a = radius.toFloat(), b = pushTicks.toFloat(), life = pushTicks + 22)
        GearSounds.play(world, center, "white_hole.pull", SoundEvents.BLOCK_PORTAL_AMBIENT, 0.8f, 1.6f)
        val wave = GearConfig.float("white_hole_damage") * 0.15f
        for (t in 1..pushTicks) {
            GearTasks.later(t) {
                val k = t / pushTicks.toDouble()
                val pulse = t % 13 == 0
                if (pulse) {
                    ArtFx.send(world, FxKind.CLEAR_WAVE, center.add(0.0, -0.5, 0.0), a = (radius * (0.6 + 0.6 * k)).toFloat(), life = 10)
                    GearSounds.play(world, center, "core.pulse", SoundEvents.BLOCK_BEACON_AMBIENT, 0.7f, 1.0f + k.toFloat() * 0.6f)
                }
                for (e in world.getOtherEntities(player, Box.of(center, radius * 3, radius * 3, radius * 3))) {
                    if (e.squaredDistanceTo(center) > radius * radius * 2.0) continue
                    if (e is ProjectileEntity && GearCombat.isHostileProjectile(player, e)) {
                        GearCombat.discardProjectile(world, e)
                        continue
                    }
                    if (!GearCombat.canHit(player, e)) continue
                    val target = e as LivingEntity
                    if (pulse && e.squaredDistanceTo(center) <= radius * radius) {
                        target.timeUntilRegen = 0
                        target.damage(world, world.damageSources.indirectMagic(player, player), wave)
                    }
                    if (GearCombat.isHeavy(target)) continue
                    val d = target.entityPos.add(0.0, target.height / 2.0, 0.0).subtract(center)
                    val flat = Vec3d(d.x, 0.0, d.z).let { if (it.lengthSquared() < 1e-4) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
                    // Wächst mit der Zeit und nahe am Kern (Druck), lässt am Rand nach.
                    val near = (1.0 - kotlin.math.sqrt(d.lengthSquared()) / (radius * 1.4)).coerceIn(0.15, 1.0)
                    val strength = push * (0.5 + k) * near * (if (target is ServerPlayerEntity) 0.5 else 1.0)
                    val v = target.velocity.multiply(0.85).add(flat.multiply(strength)).add(0.0, if (pulse) 0.12 else 0.0, 0.0)
                    if (target is ServerPlayerEntity) dev.henny.hha.logic.Fx.launchPlayer(target, v)
                    else {
                        target.velocity = v
                        target.velocityDirty = true
                    }
                }
            }
        }
        GearTasks.later(pushTicks) { erupt(player, world, center, radius) }
    }

    /** Eruption: Druckwelle bis 1,6 × Radius, Schaden mit der Nähe zum Kern (außen halb), weit weg geschleudert. */
    private fun erupt(player: ServerPlayerEntity, world: ServerWorld, center: Vec3d, radius: Double) {
        val damage = GearConfig.float("white_hole_damage")
        val reach = radius * 1.6
        var any = false
        for (e in world.getOtherEntities(player, Box.of(center, reach * 2, reach * 2, reach * 2))) {
            if (e.squaredDistanceTo(center) > reach * reach || !GearCombat.canHit(player, e)) continue
            val target = e as LivingEntity
            val near = (1.0 - kotlin.math.sqrt(e.squaredDistanceTo(center)) / reach).coerceIn(0.0, 1.0)
            target.timeUntilRegen = 0
            if (target.damage(world, world.damageSources.indirectMagic(player, player), (damage * (0.5 + 0.5 * near)).toFloat())) any = true
            target.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1, false, true, true), player)
            if (!GearCombat.isHeavy(target)) GearCombat.push(target, center, if (target is ServerPlayerEntity) 0.7 else 1.4, 0.45)
        }
        if (any) Resonance.markCombat(player)
        ArtFx.send(world, FxKind.CLEAR_WAVE, center.add(0.0, -0.5, 0.0), a = (reach + 2).toFloat(), life = 16)
        GearSounds.play(world, center, "white_hole.collapse", SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.6f, 1.5f)
    }
}

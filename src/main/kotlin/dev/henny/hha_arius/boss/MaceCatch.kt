package dev.henny.hha_arius.boss

import dev.henny.hha_arius.net.AriusNet
import dev.henny.hha_arius.net.CineCamS2C
import dev.henny.hha_arius.net.FxKind
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import java.util.UUID

/** Arius remembers each fighter's falling smashes. A warned fighter can switch to grounded attacks. */
class MaceCatch(private val f: EirholmFight) {
    private class Attempts(var count: Int = 0, var last: Long = 0, var cooldown: Long = 0)
    private val attempts = HashMap<UUID, Attempts>()
    private var runner: Runner? = null

    fun step(): Boolean {
        val active = runner ?: return false
        if (active.step()) return true
        runner = null
        return false
    }

    fun onHit(a: AriusEntity, p: ServerPlayerEntity, source: DamageSource): Boolean {
        if (source.source !== p || !p.mainHandStack.isOf(Items.MACE) ||
            !(source.isOf(DamageTypes.MACE_SMASH) || p.fallDistance > 1.5) ||
            PlayerHit.current != PlayerHit.Kind.NONE || f.chasing) return false
        val record = attempts.getOrPut(p.uuid) { Attempts() }
        if (f.now < record.cooldown) return false
        if (f.now - record.last > 200) record.count = 0
        record.last = f.now
        record.count = minOf(3, record.count + 1)
        if (record.count == 2) {
            p.sendMessage(Text.translatable("hha_arius.mace.warning").formatted(Formatting.AQUA), true)
            f.ambient("arius.guard", a.entityPos, 1.8f, 0.75f)
        }
        if (record.count < 3 || runner != null || f.moves.countering || !Hold.canGrab(p) ||
            f.now < f.ariusOpenUntil || f.players().any { f.calls.hasBossTelegraph(it) }) return false
        val start = p.entityPos
        val floor = start.subtract(Vec3d(0.0, (start.y - f.floorAt(a)).coerceIn(0.0, 5.0), 0.0))
        val ground = Hold.safeSpot(f.world, floor) ?: return false
        if (ground.squaredDistanceTo(floor) > 1.2 || start.y - ground.y !in 0.0..4.8) return false
        val top = Vec3d(start.x, ground.y + 1.7, start.z)
        val bottom = Vec3d(start.x, ground.y + 0.06, start.z)
        for (i in 0..8) {
            val sample = bottom.lerp(start.let { if (it.y > top.y) it else top }, i / 8.0)
            if (!f.world.isSpaceEmpty(p, p.boundingBox.offset(sample.subtract(start)))) return false
        }
        record.count = 0
        record.cooldown = f.now + 240
        runner = Runner(slam(a, p, start, top, bottom))
        step()
        return true
    }

    private fun slam(a: AriusEntity, p: ServerPlayerEntity, start: Vec3d, top: Vec3d, bottom: Vec3d): Script = script {
        f.face(a, p.entityPos)
        a.play(AriusPose.MACE_CATCH, 5)
        Hold.allowLong(p, 58)
        Hold.freeze(p, 58, start)
        AriusNet.send(p, CineCamS2C(true, a.id, 52, 0))
        f.fxAll(FxKind.MACE_GRIP, start, life = 50, e1 = p)
        f.ambient("arius.parry", start, 2.8f, 0.8f)
        suspend fun SequenceScope<Unit>.move(from: Vec3d, to: Vec3d, ticks: Int, down: Boolean = false) {
            for (i in 1..ticks) {
                if (!f.canSequence(p) || !Hold.isHeld(p)) break
                val u = i.toDouble() / ticks
                Hold.moveAnchor(p, from.lerp(to, if (down) u * u else u * u * (3.0 - 2.0 * u)))
                yield(Unit)
            }
        }
        try {
            move(start, top, 5)
            wait(10)
            repeat(3) { strike ->
                if (!f.canSequence(p) || !Hold.isHeld(p)) return@script
                if (strike > 0) {
                    a.play(AriusPose.MACE_CATCH, 6)
                    move(bottom, top, 6)
                }
                a.play(AriusPose.MACE_SLAM, 4)
                move(top, bottom, 4, down = true)
                // Preserve at least half a heart even with damage scaling or repeated strikes.
                val damage = (p.health - 1f).coerceIn(0f, 3f)
                if (f.canSequence(p) && Hold.isHeld(p)) Hold.hitting { Damage.hit(p, a, Damage.Kind.IMPACT, damage, 1f) }
                f.ambient("hold.impact", bottom, 2.4f, 1.08f - strike * 0.12f)
                f.fx(FxKind.CLEAR_WAVE, bottom, a = 2.8f + strike, life = 10)
                f.calls.hitShake(bottom, 1.7f, 14f)
                wait(4)
            }
        } finally {
            if (f.canSequence(p)) p.onLanding()
            Hold.release(p)
            AriusNet.send(p, CineCamS2C(false, a.id, 0, 0))
            a.play(AriusPose.IDLE)
        }
    }
}

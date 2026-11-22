package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.basisOf
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.boss.AriusEntity
import dev.henny.hha_arius.boss.AriusForm
import dev.henny.hha_arius.boss.AriusTimings
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.ArtParam
import dev.henny.hha_arius.gear.art.ArtTimings
import dev.henny.hha_arius.gear.art.Move
import dev.henny.hha_arius.gear.client.art.ArtAnims
import dev.henny.hha_arius.gear.client.art.SwordTrails
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Trefferpause (Hit-Stop) **nur in der Darstellung**: Die Animation einer Entity steht für ein paar Bilder still und holt
 * danach in wenigen Ticks auf. Serverzeit, Treffer und Ansagen bleiben unberührt. Zeiten in Client-Ticks
 * ([ArtAnims.ticks] + Teil-Tick), damit Pause-Menü und Bildrate keine Rolle spielen.
 *
 * Leser: [ArtAnims.State.age] (Spieler-Moveset, erste und dritte Person) und der Arius-Renderer über
 * `AriusRendererHitStopMixin` (Pose-Zeit).
 */
object HitStop {
    private class Stop(val at: Float, val len: Float)

    private val stops = HashMap<Int, Stop>()

    /** So lange (Ticks) läuft die Animation nach der Pause schneller, bis sie wieder synchron ist. */
    private const val CATCH_UP = 3f

    /** Pause für [entityId] ab Zeitpunkt [at] (Client-Ticks) für [ticks] (0,9 ≈ 2 Bilder bei 40 fps … 1,3 ≈ 4 Bilder bei 60 fps). */
    fun stop(entityId: Int, at: Float, ticks: Float) {
        val old = stops[entityId]
        // Mehrere Treffer desselben Schlags (Drehtritt trifft bis zu drei Ziele) verlängern nicht.
        if (old != null && at - old.at < old.len + CATCH_UP) return
        stops[entityId] = Stop(at, ticks)
    }

    /** Wie viele Ticks die Darstellung von [entityId] gerade hinter der echten Zeit [now] zurückliegt. */
    fun offset(entityId: Int, now: Float): Float {
        if (stops.isEmpty()) return 0f
        val s = stops[entityId] ?: return 0f
        val dt = now - s.at
        if (dt <= 0f) return 0f
        if (dt < s.len) return dt
        val k = (dt - s.len) / CATCH_UP
        if (k >= 1f) return 0f
        return s.len * (1f - k * k * (3f - 2f * k))
    }

    fun tick(now: Float) {
        if (stops.isNotEmpty()) stops.values.removeIf { now - it.at > it.len + CATCH_UP + 1f }
    }

    fun clear() = stops.clear()
}

/**
 * Dezentes Kamerarucken bei schweren Treffern (eigene Treffer und Arius' Schläge auf den eigenen Spieler): ein kurzer
 * Stoß im Blickwinkel, der in wenigen Ticks abklingt. Abschaltbar mit `/ariusc shake false`; folgt außerdem HHAs
 * Schalter für Kameraschütteln. Während einer Cutscene ruht es.
 */
object CameraKick {
    private var start = -100f
    private var pitchAmp = 0f
    private var yawAmp = 0f

    private const val RISE = 0.6f
    private const val DECAY = 3.4f

    fun enabled(): Boolean = AriusClientConfig.shake && runCatching { HhaClientConfig.enabled("screen_shake") }.getOrDefault(true)

    /** [strength] in Grad (Ausschlag nach oben), [side] −1..1 seitlicher Anteil. */
    fun kick(strength: Float, side: Float = 0f) {
        if (!enabled() || CounterStance.steadyView()) return
        val now = ArtAnims.ticks.toFloat()
        // Ein stärkerer Stoß ersetzt einen schwächeren, der noch läuft.
        if (now - start < RISE + DECAY && strength < pitchAmp * envelope(now - start)) return
        start = now
        pitchAmp = strength
        yawAmp = strength * 0.45f * side
    }

    private fun envelope(t: Float): Float = when {
        t < 0f -> 0f
        t < RISE -> t / RISE
        t < RISE + DECAY -> (1f - (t - RISE) / DECAY).let { it * it }
        else -> 0f
    }

    /** Ausschlag (Grad) als (Gieren, Nicken), oder null. */
    @JvmStatic
    fun offset(tickDelta: Float): FloatArray? {
        if (CounterStance.steadyView()) return null
        if (CinePlayer.active) return cineSway(tickDelta)
        val t = ArtAnims.ticks + tickDelta - start
        val e = envelope(t)
        if (e <= 0.0001f) return null
        return floatArrayOf(yawAmp * e, -pitchAmp * e)
    }

    /**
     * Handkamera in Arius' Szenen (seit 1.3.1): ganz leichtes, organisches Schwanken des Blicks (mehrere langsame
     * Sinus), damit Kamerafahrten nicht wie auf Schienen wirken. Nur im Heiligtum; folgt dem Kamerarucken-Schalter.
     */
    private fun cineSway(tickDelta: Float): FloatArray? {
        if (!AriusClientConfig.shake) return null
        val world = net.minecraft.client.MinecraftClient.getInstance().world ?: return null
        if (world.registryKey != dev.henny.hha_arius.arena.ArenaDimension.WORLD) return null
        val t = ArtAnims.ticks + tickDelta
        val yaw = 0.32f * kotlin.math.sin(t * 0.045f) + 0.14f * kotlin.math.sin(t * 0.11f + 1.7f)
        val pitch = 0.22f * kotlin.math.sin(t * 0.052f + 0.6f) + 0.09f * kotlin.math.sin(t * 0.13f + 2.9f)
        return floatArrayOf(yaw, pitch)
    }

    fun clear() {
        start = -100f
    }
}

/**
 * Trefferwirkung: erkennt Treffer am Client (die Lebensanzeige einer Entity zuckt, das ist im Netz genauso spät wie die
 * Animation des Angreifers) und ordnet sie dem Schlag zu, der gerade seinen Treffermoment hat. Dann: Trefferpause
 * ([HitStop]) beim Angreifer, Aufprall-Funken bzw. Eissplitter am Treffpunkt, Schockring bei Tritten, Kamerarucken
 * ([CameraKick]) für den eigenen Spieler, und bei Solvane die Schwertspur ([SwordTrails]). Alles nur Darstellung.
 */
object Impacts {

    private abstract class Fx(val life: Int) {
        var age = 0
        open fun tick() = Unit
        abstract fun render(d: VfxDraw, t: Float, fx: Float)
    }

    private val effects = ArrayList<Fx>()
    private val lastHurt = HashMap<Int, Int>()
    private val rnd = Random()
    private var sweep = 0

    private const val ICE = 0xFFB8E8FF.toInt()
    private const val ICE_DEEP = 0xFF7FC4F0.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val SPARK = 0xFFFFF4E0.toInt()

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.isPaused) return@register
            val world = client.world ?: return@register
            HitStop.tick(ArtAnims.ticks.toFloat())
            detect(client, world)
            if (effects.isNotEmpty()) effects.removeIf { it.tick(); ++it.age >= it.life }
        }
        WorldRenderEvents.END_MAIN.register { context ->
            if (effects.isEmpty()) return@register
            val client = MinecraftClient.getInstance()
            val consumers = context.consumers() ?: return@register
            val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
            if (fx <= 0f) return@register
            val td = client.renderTickCounter.getTickProgress(true)
            val d = VfxDraw(consumers, client.gameRenderer.camera, td)
            for (e in effects) runCatching { e.render(d, e.age + td, fx) }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear() }
    }

    fun clear() {
        effects.clear()
        lastHurt.clear()
        HitStop.clear()
        CameraKick.clear()
    }

    // --- Erkennung ----------------------------------------------------------------------------------------------------

    private fun detect(client: MinecraftClient, world: ClientWorld) {
        val cam = client.gameRenderer.camera.cameraPos
        for (e in world.entities) {
            if (e !is LivingEntity) continue
            if (e.squaredDistanceTo(cam) > 72.0 * 72.0) continue
            val h = e.hurtTime
            val prev = lastHurt.put(e.id, h) ?: 0
            if (h > prev && h >= e.maxHurtTime - 1) runCatching { onHurt(client, world, e) }
        }
        if (++sweep >= 200) {
            sweep = 0
            lastHurt.keys.removeIf { world.getEntityById(it) == null }
        }
    }

    private fun onHurt(client: MinecraftClient, world: ClientWorld, victim: LivingEntity) {
        if (victim is PlayerEntity) {
            ariusStrike(client, world, victim)
            return
        }
        // Wer hat gerade seinen Treffermoment? Der Nächste gewinnt.
        var best: PlayerEntity? = null
        var bestKind: Anim? = null
        var bestDist = Double.MAX_VALUE
        var solvane = false
        for (p in world.players) {
            if (p === victim) continue
            val dist = sqrt(p.squaredDistanceTo(victim))
            if (dist > 16.0) continue
            val anim = ArtAnims.get(p.id)
            if (anim != null) {
                val kind = hitMoment(anim, p, victim, dist)
                if (kind != null && dist < bestDist) {
                    best = p; bestKind = kind; bestDist = dist; solvane = false
                    continue
                }
            }
            if (p.mainHandStack.isOf(GearItems.SOLVANE) && p.handSwinging && p.handSwingTicks <= 3 && dist < 6.0 && dist < bestDist) {
                best = p; bestKind = null; bestDist = dist; solvane = true
            }
        }
        val attacker = best ?: return
        val contact = contactPoint(attacker, victim)
        val dir = flat(victim.entityPos.subtract(attacker.entityPos))
        val local = attacker === client.player
        val now = ArtAnims.ticks.toFloat()
        if (solvane) {
            val full = !local || SwordTrails.lastAttackStrength >= 0.9f
            if (!full) return
            SwordTrails.confirm(attacker.id)
            effects.add(Sparks(contact, dir, 7, 0.32, SPARK, ICE, rnd.nextLong()))
            return
        }
        val anim = ArtAnims.get(attacker.id) ?: return
        val hitAt = anim.start + when (bestKind) {
            Anim.ART_RELEASE -> ArtTimings.ART_HIT
            else -> anim.param.coerceAtLeast(1)
        }
        val at = max(hitAt.toFloat(), now - 1f)
        when (bestKind) {
            Anim.JAB, Anim.CROSS -> effects.add(Sparks(contact, dir, 5, 0.22, SPARK, WHITE, rnd.nextLong()))
            Anim.HOOK -> {
                HitStop.stop(attacker.id, at, 1.1f)
                effects.add(Sparks(contact, dir, 9, 0.34, SPARK, WHITE, rnd.nextLong()))
                if (local) CameraKick.kick(0.7f, -0.4f)
            }
            Anim.COUNTER -> {
                HitStop.stop(attacker.id, at, 0.9f)
                effects.add(Sparks(contact, dir, 8, 0.3, SPARK, WHITE, rnd.nextLong()))
                if (local) CameraKick.kick(0.6f)
            }
            Anim.SIDE_KICK -> {
                HitStop.stop(attacker.id, at, 0.9f)
                effects.add(Sparks(contact, dir, 8, 0.36, SPARK, WHITE, rnd.nextLong()))
                effects.add(ShockRing(contact, dir, 1.3, WHITE))
                if (local) CameraKick.kick(0.8f, 0.3f)
            }
            Anim.SPIN_KICK -> {
                HitStop.stop(attacker.id, at, 1.3f)
                effects.add(Sparks(contact, dir, 11, 0.42, SPARK, WHITE, rnd.nextLong()))
                effects.add(ShockRing(contact, dir, 1.8, WHITE))
                if (local) CameraKick.kick(1.0f, 0.5f)
            }
            Anim.ART_RELEASE -> {
                HitStop.stop(attacker.id, at, 1.2f)
                effects.add(Splinters(contact, dir, 10, ICE, rnd.nextLong()))
                effects.add(Sparks(contact, dir, 8, 0.4, WHITE, ICE, rnd.nextLong()))
                if (local) CameraKick.kick(0.9f)
            }
            else -> {}
        }
    }

    /** Ist [anim] gerade im Treffermoment, und liegt [victim] in Reichweite und Winkel? Dann die Art des Schlags. */
    private fun hitMoment(anim: ArtAnims.State, p: PlayerEntity, victim: LivingEntity, dist: Double): Anim? {
        val a = anim.rawAge(0f)
        return when (anim.anim) {
            Anim.JAB, Anim.CROSS, Anim.HOOK, Anim.SIDE_KICK, Anim.SPIN_KICK, Anim.COUNTER -> {
                val move = Move.entries.firstOrNull { it.anim == anim.anim } ?: return null
                val w = anim.param.coerceAtLeast(1)
                if (a < w - 1.5f || a > w + 3.5f) return null
                if (dist > move.reach + victim.width + 1.5) return null
                if (angleTo(p, victim) > move.halfAngle + 25.0) return null
                anim.anim
            }
            Anim.ART_RELEASE -> {
                val h = ArtTimings.ART_HIT
                if (a < h - 1.5f || a > h + 3.5f) return null
                if (dist > ArtTimings.artLength(ArtParam.charge(anim.param)) + 2.5) return null
                Anim.ART_RELEASE
            }
            else -> null
        }
    }

    private fun angleTo(p: PlayerEntity, e: LivingEntity): Double {
        val look = p.rotationVector
        val to = e.boundingBox.center.subtract(p.eyePos)
        val l = to.length()
        if (l < 1e-4) return 0.0
        return Math.toDegrees(acos((look.dotProduct(to) / l).coerceIn(-1.0, 1.0)))
    }

    /** Arius trifft einen Spieler: Pause in Arius' Pose, Eissplitter (weiße Form: weiße Funken), Rucken für den Getroffenen. */
    private fun ariusStrike(client: MinecraftClient, world: ClientWorld, victim: PlayerEntity) {
        val arius = world.entities.asSequence().filterIsInstance<AriusEntity>()
            .filter { it.squaredDistanceTo(victim) < 9.0 * 9.0 }
            .minByOrNull { it.squaredDistanceTo(victim) } ?: return
        val pose = arius.pose.coerceIn(0, AriusTimings.ARIUS_WINDUP.size - 1)
        val w = if (arius.windup > 0) arius.windup else AriusTimings.ARIUS_WINDUP[pose]
        if (w <= 0) return
        val t = arius.age - arius.poseChangedAge
        if (t < w - 3 || t > w + 6) return
        HitStop.stop(arius.id, ArtAnims.ticks.toFloat(), 1.1f)
        val contact = victim.boundingBox.center.add(0.0, 0.25, 0.0)
        val dir = flat(victim.entityPos.subtract(arius.entityPos))
        val white = arius.form == AriusForm.WHITE
        effects.add(Splinters(contact, dir, if (white) 7 else 9, if (white) WHITE else ICE, rnd.nextLong()))
        effects.add(Sparks(contact, dir, 7, 0.36, WHITE, if (white) ICE else ICE_DEEP, rnd.nextLong()))
        if (victim === client.player) CameraKick.kick(1.1f, if (rnd.nextBoolean()) 0.6f else -0.6f)
    }

    private fun contactPoint(attacker: PlayerEntity, victim: LivingEntity): Vec3d {
        val box = victim.boundingBox
        val c = box.center
        val eye = attacker.eyePos
        val y = (eye.y - 0.35).coerceIn(box.minY + 0.2, box.maxY - 0.1)
        val toward = flat(eye.subtract(c))
        val r = min(box.lengthX, box.lengthZ) * 0.5
        return Vec3d(c.x + toward.x * r, y, c.z + toward.z * r)
    }

    private fun flat(v: Vec3d): Vec3d {
        val f = Vec3d(v.x, 0.0, v.z)
        return if (f.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else f.normalize()
    }

    // --- Effekte ------------------------------------------------------------------------------------------------------

    /** Aufprall-Funken: kurze, helle Striche, die vom Treffpunkt wegspritzen (vorwiegend in Schlagrichtung) und erlöschen. */
    private class Sparks(val at: Vec3d, dir: Vec3d, n: Int, val speed: Double, val core: Int, val edge: Int, seed: Long) : Fx(7) {
        private val dirs: List<Vec3d>
        private val speeds: DoubleArray

        init {
            val r = Random(seed)
            dirs = List(n) {
                val spread = Vec3d(r.nextGaussian() * 0.55, r.nextGaussian() * 0.45 + 0.15, r.nextGaussian() * 0.55)
                dir.multiply(0.8).add(spread).normalize()
            }
            speeds = DoubleArray(n) { 0.6 + r.nextDouble() * 0.7 }
        }

        override fun render(d: VfxDraw, t: Float, fx: Float) {
            val k = 1f - t / life
            if (k <= 0f) return
            for (i in dirs.indices) {
                // Abbremsen: Weg wächst mit 1 − e^(−t)
                val s = speed * speeds[i]
                val head = at.add(dirs[i].multiply(s * 3.0 * (1.0 - Math.exp(-t * 0.55))))
                val tail = at.add(dirs[i].multiply(s * 3.0 * (1.0 - Math.exp(-max(0f, t - 1.2f) * 0.55))))
                d.ribbon(AriusLayers.VFX_GLOW, listOf(tail, head), { 0.04 * (0.5 + 0.5 * k) }, { withAlpha(if (it > 0.5f) core else edge, 0.92f * k * fx) },
                    uScale = 0f, uOffset = 0.5f)
            }
            if (t < 2.5f) d.billboard(AriusLayers.VFX_GLOW, at, 0.4 * (1.0 - t / 2.5), withAlpha(core, 0.65f * fx * (1f - t / 2.5f)))
        }
    }

    /** Schockring bei Tritten: flacher Lichtring quer zur Trittrichtung, der schnell aufgeht und verblasst. */
    private class ShockRing(val at: Vec3d, val dir: Vec3d, val radius: Double, val color: Int) : Fx(7) {
        override fun render(d: VfxDraw, t: Float, fx: Float) {
            val u = (t / life).coerceIn(0f, 1f)
            val grow = 1.0 - (1.0 - u) * (1.0 - u)
            val r = 0.15 + radius * grow
            val (a, b) = basisOf(dir)
            val k = (1f - u) * fx
            d.ring(AriusLayers.INK, at, a, b, r * 0.84, r, withAlpha(0xFF1A4B70.toInt(), 0f), withAlpha(0xFF5DC4F5.toInt(), 0.55f * k), segments = 40)
            d.ring(AriusLayers.VFX_GLOW, at, a, b, r * 0.82, r, withAlpha(color, 0f), withAlpha(color, 0.65f * k), segments = 40, uTile = 0f, uOffset = 0.5f)
            d.ring(AriusLayers.VFX_GLOW, at.add(dir.multiply(0.25 * grow)), a, b, r * 0.55, r * 0.75, withAlpha(color, 0f), withAlpha(color, 0.3f * k), segments = 32, uTile = 0f, uOffset = 0.5f)
        }
    }

    /** Eissplitter: kleine, helle Scherben, die wegspringen, sich drehen und fallen. */
    private class Splinters(val at: Vec3d, dir: Vec3d, n: Int, val color: Int, seed: Long) : Fx(12) {
        private val vel: List<Vec3d>
        private val spin: DoubleArray
        private val size: DoubleArray

        init {
            val r = Random(seed)
            vel = List(n) { dir.multiply(0.12 + r.nextDouble() * 0.12).add(r.nextGaussian() * 0.08, 0.08 + r.nextDouble() * 0.12, r.nextGaussian() * 0.08) }
            spin = DoubleArray(n) { r.nextGaussian() * 0.6 }
            size = DoubleArray(n) { 0.05 + r.nextDouble() * 0.06 }
        }

        override fun render(d: VfxDraw, t: Float, fx: Float) {
            val k = 1f - (t / life).coerceIn(0f, 1f)
            if (k <= 0f) return
            for (i in vel.indices) {
                val v = vel[i]
                val p = at.add(v.x * t, v.y * t - 0.018 * t * t, v.z * t)
                val ang = spin[i] * t
                val s = size[i] * (0.6 + 0.4 * k)
                // Scherbe als schmale Raute: zwei kurze Striche über Kreuz
                val a = Vec3d(cos(ang), sin(ang) * 0.6, sin(ang)).normalize().multiply(s * 1.6)
                d.ribbon(AriusLayers.INK, listOf(p.subtract(a), p.add(a)), { s * 0.48 }, { withAlpha(0xFF1A5882.toInt(), 0.75f * k * fx) }, uScale = 0f, uOffset = 0.5f)
                d.ribbon(AriusLayers.VFX_GLOW, listOf(p.subtract(a), p.add(a)), { s * 0.42 }, { withAlpha(color, 0.9f * k * fx) }, uScale = 0f, uOffset = 0.5f)
            }
        }
    }
}

package dev.henny.hha_arius.client.model.nivaris

import dev.henny.hha_arius.boss.AriusTimings
import dev.henny.hha_arius.boss.NivarisPose
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.util.math.MathHelper
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class NivarisRenderState : LivingEntityRenderState() {
    var bossPose = NivarisPose.IDLE
    var previousPose = NivarisPose.IDLE
    var poseTime = 0f

    /** Treffer-Zeitpunkt der aktuellen Pose (windup vom Server oder Standard aus [AriusTimings]). */
    var windup = 0f

    /** 0 = am Boden, 1 = in der Luft; weich überblendet (siehe [NivarisRenderer]). */
    var fly = 0f
    var walk = 0f
    var walkPhase = 0f
    var throatGlow = 0
    var eyeGlow = 50
    var lead = false
    var resonance = false
}

/** Eine Schlüsselpose: je Knochen (pitch, yaw, roll, dx, dy, dz), siehe [NivarisModel.Keys]. */
class NivarisKey(val v: FloatArray)

private fun key(data: String) = NivarisKey(data.split(',').map { it.toFloat() }.toFloatArray())

/**
 * Nivaris, die Alte vom Eirgletscher. Geometrie, Textur und alle Schlüsselposen stammen aus
 * tools/assets/nivaris_model.py; der Block [Keys] wird dort erzeugt (`python tools/assets/nivaris_model.py --kotlin`),
 * damit Vorschau und Spiel dieselben Winkel benutzen. Hier steht nur der Ablauf.
 *
 * Ablaufvertrag (siehe [NivarisPose]): Ausholhaltung weich erreichen, halten, **Treffer genau bei t = windup**,
 * Nachlauf [AriusTimings.NIVARIS_RECOVER], dann zurück in die Ruhe (am Boden IDLE, in der Luft HOVER). Haltungen
 * bleiben stehen, bis der Server die Pose wechselt. Sie ist schwer und alt: Ausholen ruhig, Schlag kurz und hart,
 * jeder Aufprall staucht den Körper und der Hals federt nach.
 */
class NivarisModel(root: ModelPart) : EntityModel<NivarisRenderState>(root) {

    private val parts: Array<ModelPart> = Array(Keys.COUNT) { i ->
        Keys.BONES[i].split('/').fold(root) { p, n -> p.getChild(n) }
    }
    private fun part(i: Int) = parts[i]

    // --- Hüllkurven -------------------------------------------------------------------------------------------------

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun easeIn(t: Float) = t * t
    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)

    /** Zeitleiste: hält die zuletzt erreichte Pose und blendet in den Abschnitten [t0, t1] zur nächsten über. */
    private class Track(var cur: FloatArray, private val t: Float) {
        var out: FloatArray? = null
        fun to(t0: Float, t1: Float, target: NivarisKey, ease: (Float) -> Float): Track {
            if (out != null) return this
            when {
                t >= t1 -> cur = target.v
                t > t0 -> out = FloatArray(cur.size) { cur[it] + (target.v[it] - cur[it]) * ease((t - t0) / max(t1 - t0, 0.001f)) }
                else -> out = cur
            }
            return this
        }
        fun get(): FloatArray = out ?: cur
    }

    private fun track(start: FloatArray, t: Float) = Track(start, t)

    private fun defaultWindup(pose: Int) = AriusTimings.NIVARIS_WINDUP.getOrElse(pose) { 0 }.toFloat()
    private fun recover(pose: Int) = AriusTimings.NIVARIS_RECOVER.getOrElse(pose) { 0 }.toFloat()

    /** Ruhehaltung, in die Angriffe zurückkehren. */
    private fun rest(air: Boolean) = if (air) Keys.HOVER else Keys.IDLE

    /** Der Ablauf jeder Pose ab ihrem Beginn. [from] ist die Haltung, aus der sie kommt; w = Treffer-Zeitpunkt. */
    private fun evaluate(pose: Int, t: Float, w: Float, air: Boolean, from: FloatArray): FloatArray {
        val r = recover(pose)
        val s: (Float) -> Float = ::smooth
        val i: (Float) -> Float = ::easeIn
        val o: (Float) -> Float = ::easeOut
        val back = rest(air)
        // Ausholen höchstens 8–12 Ticks, aber immer vor dem Schlag fertig.
        fun wind(maxTicks: Float, strike: Float) = MathHelper.clamp(w - strike, 1f, maxTicks)
        return when (pose) {
            NivarisPose.HOVER -> track(from, t).to(0f, 12f, Keys.HOVER, s).get()
            NivarisPose.GLIDE -> track(from, t).to(0f, 14f, Keys.GLIDE, s).get()
            NivarisPose.INHALE -> track(from, t).to(0f, MathHelper.clamp(w * 0.6f, 4f, 16f), if (air) Keys.INHALE_AIR else Keys.INHALE, s).get()
            NivarisPose.BREATH -> track(from, t).to(0f, 4f, if (air) Keys.BREATH_AIR else Keys.BREATH, o).get()
            NivarisPose.DIVE -> track(from, t).to(0f, wind(12f, 6f), Keys.DIVE_WINDUP, s).to(w - 6f, w, Keys.DIVE, i).get()
            NivarisPose.CRASH_DIVE -> track(from, t).to(0f, wind(14f, 6f), Keys.CRASH_WINDUP, s).to(w - 6f, w, Keys.CRASH, i).get()
            NivarisPose.LAND -> track(from, t).to(0f, wind(10f, 3f), Keys.LAND_WINDUP, s).to(w - 3f, w, Keys.LAND_HIT, i)
                .to(w + 3f, w + max(r, 4f), Keys.IDLE, s).get()
            NivarisPose.CLAW -> track(from, t).to(0f, wind(8f, 3f), Keys.CLAW_WINDUP, s).to(w - 3f, w, Keys.CLAW_HIT, i)
                .to(w + 2f, w + max(r, 3f), back, s).get()
            NivarisPose.TAIL -> track(from, t).to(0f, wind(10f, 4f), Keys.TAIL_WINDUP, s).to(w - 4f, w, Keys.TAIL_HIT, i)
                .to(w, w + 4f, Keys.TAIL_FOLLOW, o).to(w + 4f, w + max(r, 5f), back, s).get()
            NivarisPose.WING_GUST -> track(from, t).to(0f, wind(12f, 3f), Keys.GUST_WINDUP, s).to(w - 3f, w, Keys.GUST_HIT, i)
                .to(w + 3f, w + max(r, 4f), back, s).get()
            NivarisPose.GRAB -> track(from, t).to(0f, max(w * 0.5f, 2f), Keys.GRAB_REACH, s).to(max(w * 0.5f, 2f), max(w, 3f), Keys.GRAB_HOLD, s).get()
            NivarisPose.THROW -> track(from, t).to(0f, wind(12f, 3f), Keys.THROW_WINDUP, s).to(w - 3f, w, Keys.THROW_HIT, i)
                .to(w + 3f, w + max(r, 4f), back, s).get()
            NivarisPose.ROAR -> track(from, t).to(0f, max(w, 2f), Keys.ROAR, s).to(w + r - 8f, w + r, back, s).get()
            NivarisPose.DOWNED -> track(from, t).to(0f, 8f, Keys.DOWNED, i).get()
            NivarisPose.EXHAUSTED -> track(from, t).to(0f, 14f, Keys.EXHAUSTED, s).get()
            NivarisPose.TAKEOFF -> track(from, t).to(0f, wind(8f, 2f), Keys.TAKEOFF_CROUCH, s).to(w - 2f, w, Keys.TAKEOFF_BEAT, i)
                .to(w + 1f, w + max(r, 2f), Keys.HOVER, s).get()
            NivarisPose.RESONANCE -> track(from, t).to(0f, 16f, Keys.RESONANCE, s).get()
            NivarisPose.LIE_DOWN -> track(from, t).to(0f, 30f, Keys.LIE_DOWN, s).get()
            NivarisPose.FREEZE_FIELD -> track(from, t).to(0f, wind(14f, 4f), Keys.FREEZE_WINDUP, s).to(w - 4f, w, Keys.FREEZE, i)
                .to(w + 2f, w + max(r, 3f), back, s).get()
            else -> track(from, t).to(0f, 10f, back, s).get()
        }
    }

    /** Wann eine Pose in ihrer Endhaltung steht (für den Übergang in die nächste). */
    private fun settled(pose: Int): Float {
        val w = defaultWindup(pose)
        val r = recover(pose)
        return when (pose) {
            NivarisPose.LIE_DOWN -> 30f
            NivarisPose.GRAB, NivarisPose.DIVE, NivarisPose.CRASH_DIVE, NivarisPose.INHALE -> max(w, 16f)
            else -> if (w > 0f) w + r + 1f else 20f
        }
    }

    /** Posen, deren Schwingen und Beine nicht vom Flug-Zyklus überschrieben werden. */
    private fun ownsWings(pose: Int) = when (pose) {
        NivarisPose.GLIDE, NivarisPose.DIVE, NivarisPose.CRASH_DIVE, NivarisPose.LAND, NivarisPose.TAKEOFF,
        NivarisPose.RESONANCE, NivarisPose.WING_GUST, NivarisPose.DOWNED, NivarisPose.LIE_DOWN, NivarisPose.EXHAUSTED -> true
        else -> false
    }

    private val wingChannels = intArrayOf(Keys.WING_R, Keys.WING_FORE_R, Keys.WING_HAND_R, Keys.WING_L, Keys.WING_FORE_L, Keys.WING_HAND_L)
    private val legChannels = intArrayOf(
        Keys.ARM_R, Keys.FORE_R, Keys.HAND_R, Keys.ARM_L, Keys.FORE_L, Keys.HAND_L,
        Keys.THIGH_R, Keys.SHIN_R, Keys.FOOT_R, Keys.THIGH_L, Keys.SHIN_L, Keys.FOOT_L,
    )

    override fun setAngles(state: NivarisRenderState) {
        super.setAngles(state)
        val pose = state.bossPose
        val t = state.poseTime
        val age = state.age
        val fly = state.fly
        val air = fly > 0.5f
        val w = state.windup
        val prev = state.previousPose
        val start = evaluate(prev, settled(prev), defaultWindup(prev), air, rest(air).v)
        val v = evaluate(pose, t, w, air, start).copyOf()

        // Flug-Schicht: Flügelschlag und angezogene Beine, solange die Pose die Schwingen nicht selbst führt.
        val flap = if (ownsWings(pose)) 0f else fly
        var flapK = 0f
        if (flap > 0.001f) {
            val period = 26f
            val u = ((age % period) + period) % period / period
            // Abschlag schnell (40 %), Aufschlag langsam.
            flapK = if (u < 0.4f) smooth(u / 0.4f) else 1f - smooth((u - 0.4f) / 0.6f)
            for (c in wingChannels) {
                val o = c * 6
                for (k in 0 until 3) {
                    val target = Keys.FLY_UP.v[o + k] + (Keys.FLY_DOWN.v[o + k] - Keys.FLY_UP.v[o + k]) * flapK
                    v[o + k] += (target - v[o + k]) * flap
                }
            }
            for (c in legChannels) {
                val o = c * 6
                for (k in 0 until 3) v[o + k] += (Keys.HOVER.v[o + k] - v[o + k]) * flap
            }
            val dy = Keys.BODY * 6 + 4
            v[dy] += (Keys.FLY_DY - v[dy]) * flap
        }

        for (b in 0 until Keys.COUNT) {
            val p = parts[b]
            val o = b * 6
            p.pitch = v[o]
            p.yaw = v[o + 1]
            p.roll = v[o + 2]
            p.originX += v[o + 3]
            p.originY += v[o + 4]
            p.originZ += v[o + 5]
        }

        val body = part(Keys.BODY)
        val chest = part(Keys.CHEST)
        val head = part(Keys.HEAD)
        val jaw = part(Keys.JAW)
        val neck0 = part(Keys.NECK_0)
        val neck1 = part(Keys.NECK_1)

        // Flugbewegung: der Körper hebt sich nach jedem Abschlag, der Schwanz steuert nach.
        if (flap > 0.001f) {
            body.originY += (0.5f - flapK) * 3.2f * flap
            body.pitch += (flapK - 0.5f) * 0.04f * flap
            neck0.pitch -= (flapK - 0.5f) * 0.05f * flap
            head.pitch += (flapK - 0.5f) * 0.05f * flap
            for (n in 0..5) part(Keys.TAIL_0 + n).pitch += sin(age * 0.24f - n * 0.6f) * 0.035f * flap
        }
        if (pose == NivarisPose.GLIDE) {
            val g = sin(age * 0.07f)
            part(Keys.WING_R).roll += g * 0.05f
            part(Keys.WING_L).roll -= g * 0.05f
            part(Keys.WING_HAND_R).roll += sin(age * 0.07f - 0.8f) * 0.06f
            part(Keys.WING_HAND_L).roll -= sin(age * 0.07f - 0.8f) * 0.06f
            body.roll += sin(age * 0.03f) * 0.05f
        }

        // Atmen: langsam und tief; erschöpft schwer und schnell.
        val ground = 1f - fly
        val exhausted = pose == NivarisPose.EXHAUSTED || pose == NivarisPose.DOWNED
        val breath = if (exhausted) sin(age * 0.2f) else sin(age * 0.065f)
        val depth = if (exhausted) 2.4f else 1f
        chest.pitch -= breath * 0.014f * depth
        body.originY -= breath * 0.25f * depth * ground
        neck0.pitch -= breath * 0.012f * depth
        head.pitch += breath * 0.01f * depth
        if (exhausted) jaw.pitch += (0.5f + 0.5f * breath) * 0.12f

        // Schwanz pendelt träge (am Boden; liegend nur noch die Spitze).
        val sway = when (pose) {
            NivarisPose.LIE_DOWN, NivarisPose.DOWNED -> 0.25f
            NivarisPose.TAIL -> 0.2f
            else -> 1f
        }
        for (n in 0..5) {
            part(Keys.TAIL_0 + n).yaw += sin(age * 0.045f - n * 0.55f) * (0.02f + n * 0.012f) * sway
        }
        part(Keys.FAN).yaw += sin(age * 0.045f - 3.6f) * 0.06f * sway

        // Gehen: schwerer Kreuzgang (rechts vorn + links hinten gemeinsam). Bei jedem Auftreten sackt der Rumpf ein.
        val walkFactor = when (pose) {
            NivarisPose.IDLE, NivarisPose.ROAR, NivarisPose.INHALE -> 1f
            NivarisPose.DOWNED, NivarisPose.LIE_DOWN, NivarisPose.EXHAUSTED, NivarisPose.TAKEOFF, NivarisPose.LAND -> 0f
            else -> 0.4f
        }
        val wk = state.walk * walkFactor * ground
        if (wk > 0.01f) {
            val ph = state.walkPhase * 0.55f
            fun leg(upper: Int, lower: Int, end: Int, phase: Float, fore: Boolean) {
                val swing = sin(ph + phase)
                val lift = max(0f, cos(ph + phase))
                part(upper).pitch -= swing * (if (fore) 0.3f else 0.28f) * wk
                if (fore) {
                    part(lower).pitch -= lift * 0.55f * wk
                    part(end).pitch += lift * 0.45f * wk + swing * 0.25f * wk
                } else {
                    part(lower).pitch += lift * 0.5f * wk
                    part(end).pitch -= lift * 0.35f * wk - swing * 0.2f * wk
                }
            }
            leg(Keys.ARM_R, Keys.FORE_R, Keys.HAND_R, 0f, true)
            leg(Keys.THIGH_L, Keys.SHIN_L, Keys.FOOT_L, 0.3f, false)
            leg(Keys.ARM_L, Keys.FORE_L, Keys.HAND_L, MathHelper.PI, true)
            leg(Keys.THIGH_R, Keys.SHIN_R, Keys.FOOT_R, MathHelper.PI + 0.3f, false)
            val step = abs(sin(ph))
            body.originY += (step * step * 1.6f - 0.4f) * wk
            body.roll += sin(ph) * 0.035f * wk
            chest.yaw += sin(ph) * 0.05f * wk
            chest.roll -= sin(ph) * 0.02f * wk
            // Der Hals gleicht aus (Kopf bleibt ruhig), wie bei großen Tieren.
            neck0.pitch += cos(2f * ph) * 0.035f * wk
            head.pitch -= cos(2f * ph) * 0.03f * wk
            neck0.yaw -= sin(ph) * 0.04f * wk
            for (n in 0..5) part(Keys.TAIL_0 + n).yaw += sin(ph - 0.6f - n * 0.35f) * 0.05f * wk
            part(Keys.WING_R).roll += step * 0.03f * wk
            part(Keys.WING_L).roll -= step * 0.03f * wk
        }

        // Spannung beim Halten des Ausholens: kaum sichtbares Zittern.
        val holding = when (pose) {
            NivarisPose.CLAW, NivarisPose.TAIL, NivarisPose.WING_GUST, NivarisPose.THROW, NivarisPose.FREEZE_FIELD -> t > 8f && t < w - 3f
            NivarisPose.INHALE -> true
            else -> false
        }
        if (holding) {
            chest.roll += sin(age * 1.9f) * 0.006f
            neck0.pitch += sin(age * 2.3f) * 0.006f
        }

        when (pose) {
            NivarisPose.INHALE -> {
                // Sie zieht immer tiefer Luft: Brust hebt sich, Hals geht weiter zurück, Maul öffnet sich langsam.
                val p = MathHelper.clamp(t / max(w, 1f), 0f, 1f)
                chest.pitch -= p * 0.07f
                neck0.pitch -= p * 0.06f
                neck1.pitch -= p * 0.04f
                jaw.pitch += p * 0.12f
            }
            NivarisPose.BREATH, NivarisPose.FREEZE_FIELD -> {
                if (pose == NivarisPose.BREATH || t >= w) {
                    head.yaw += sin(age * 1.3f) * 0.015f
                    jaw.pitch += sin(age * 2.1f) * 0.03f
                    neck0.pitch += sin(age * 0.9f) * 0.01f
                }
            }
            NivarisPose.ROAR -> {
                if (t >= w) {
                    val k = MathHelper.clamp(1f - (t - w) / max(recover(pose), 1f), 0f, 1f)
                    head.yaw += sin(age * 2.6f) * 0.035f * k
                    jaw.pitch += sin(age * 3.1f) * 0.05f * k
                    chest.pitch += sin(age * 2.0f) * 0.01f * k
                }
            }
            NivarisPose.LAND -> {
                // Aufprall: der Hals schwingt nach unten nach und federt gedämpft zurück, Schwingen zittern.
                val since = t - w
                if (since >= 0f) {
                    val spring = exp(-since * 0.16f) * sin(since * 0.5f)
                    neck0.pitch += spring * 0.32f
                    neck1.pitch += spring * 0.12f
                    head.pitch -= spring * 0.3f
                    chest.pitch += spring * 0.05f
                    body.originY += max(0f, spring) * 1.5f
                    part(Keys.WING_HAND_R).roll += spring * 0.12f
                    part(Keys.WING_HAND_L).roll -= spring * 0.12f
                    for (n in 0..5) part(Keys.TAIL_0 + n).pitch -= spring * 0.05f
                }
            }
            NivarisPose.TAKEOFF -> {
                val since = t - w
                if (since >= 0f) {
                    val spring = exp(-since * 0.25f) * sin(since * 0.6f)
                    neck0.pitch -= spring * 0.2f
                    head.pitch += spring * 0.15f
                }
            }
            NivarisPose.CLAW, NivarisPose.TAIL, NivarisPose.WING_GUST, NivarisPose.THROW -> {
                // Nach dem Schlag ruckt das Gewicht nach.
                val since = t - w
                if (since in 0f..10f) {
                    val k = exp(-since * 0.3f) * sin(since * 0.9f)
                    body.originY += abs(k) * 0.8f
                    neck0.pitch += k * 0.08f
                }
            }
            NivarisPose.DOWNED -> {
                // Gerade zu Boden gebracht: ein harter Aufschlag, dann zuckt der Kopf.
                if (t < 14f) {
                    val k = exp(-t * 0.25f) * sin(t * 0.8f)
                    neck0.pitch += k * 0.15f
                    head.pitch -= k * 0.2f
                }
                head.yaw += sin(age * 0.05f) * 0.03f
            }
            NivarisPose.RESONANCE -> {
                part(Keys.WING_HAND_R).roll += sin(age * 0.11f) * 0.03f
                part(Keys.WING_HAND_L).roll -= sin(age * 0.11f) * 0.03f
                head.pitch += sin(age * 0.05f) * 0.02f
            }
            NivarisPose.LIE_DOWN -> {
                head.pitch += sin(age * 0.02f) * 0.02f
            }
        }
        // Der Fächer stellt sich beim Ausholen des Schwanzfegers auf (Warnung), sonst liegt er etwas an.
        if (pose == NivarisPose.TAIL && t < w) part(Keys.FAN).pitch += min(t / 6f, 1f) * 0.25f
    }

    // <keys> erzeugt von tools/assets/nivaris_model.py --kotlin; nicht von Hand ändern
    object Keys {
        /** Knochenpfade in Kanalreihenfolge (je 6 Werte: pitch, yaw, roll, dx, dy, dz). */
        val BONES = arrayOf("body", "body/chest", "body/chest/neck_0", "body/chest/neck_0/neck_1", "body/chest/neck_0/neck_1/neck_2", "body/chest/neck_0/neck_1/neck_2/neck_3", "body/chest/neck_0/neck_1/neck_2/neck_3/head", "body/chest/neck_0/neck_1/neck_2/neck_3/head/jaw", "body/chest/neck_0/neck_1/neck_2/neck_3/head/crown", "body/chest/wing_r", "body/chest/wing_r/wing_fore_r", "body/chest/wing_r/wing_fore_r/wing_hand_r", "body/chest/wing_l", "body/chest/wing_l/wing_fore_l", "body/chest/wing_l/wing_fore_l/wing_hand_l", "body/chest/arm_r", "body/chest/arm_r/fore_r", "body/chest/arm_r/fore_r/hand_r", "body/chest/arm_l", "body/chest/arm_l/fore_l", "body/chest/arm_l/fore_l/hand_l", "body/thigh_r", "body/thigh_r/shin_r", "body/thigh_r/shin_r/foot_r", "body/thigh_l", "body/thigh_l/shin_l", "body/thigh_l/shin_l/foot_l", "body/tail_0", "body/tail_0/tail_1", "body/tail_0/tail_1/tail_2", "body/tail_0/tail_1/tail_2/tail_3", "body/tail_0/tail_1/tail_2/tail_3/tail_4", "body/tail_0/tail_1/tail_2/tail_3/tail_4/tail_5", "body/tail_0/tail_1/tail_2/tail_3/tail_4/tail_5/fan")
        const val BODY = 0
        const val CHEST = 1
        const val NECK_0 = 2
        const val NECK_1 = 3
        const val NECK_2 = 4
        const val NECK_3 = 5
        const val HEAD = 6
        const val JAW = 7
        const val CROWN = 8
        const val WING_R = 9
        const val WING_FORE_R = 10
        const val WING_HAND_R = 11
        const val WING_L = 12
        const val WING_FORE_L = 13
        const val WING_HAND_L = 14
        const val ARM_R = 15
        const val FORE_R = 16
        const val HAND_R = 17
        const val ARM_L = 18
        const val FORE_L = 19
        const val HAND_L = 20
        const val THIGH_R = 21
        const val SHIN_R = 22
        const val FOOT_R = 23
        const val THIGH_L = 24
        const val SHIN_L = 25
        const val FOOT_L = 26
        const val TAIL_0 = 27
        const val TAIL_1 = 28
        const val TAIL_2 = 29
        const val TAIL_3 = 30
        const val TAIL_4 = 31
        const val TAIL_5 = 32
        const val FAN = 33
        const val COUNT = 34
        /** Versatz des Körpers in der Flughaltung (Einheiten, -y = hoch). */
        const val FLY_DY = -6.0f
        val IDLE = key("0,0,0,0,0,0,0,0,0,0,0,0,-0.7679,0,0,0,0,0,-0.0524,0,0,0,0,0,-0.0349,0,0,0,0,0,-0.0175,0,0,0,0,0,0.8727,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.9932,0.8011,1.0943,0,0,0,-0.2821,-0.0685,-2.7708,0,0,0,0.0686,0.5644,-3.0186,0,0,0,-0.9932,-0.8011,-1.0943,0,0,0,-0.2821,0.0685,2.7708,0,0,0,0.0686,-0.5644,3.0186,0,0,0,0.227,0,0,0,0,0,-0.9346,0,0,0,0,0,0.7076,0,0,0,0,0,0.227,0,0,0,0,0,-0.9346,0,0,0,0,0,0.7076,0,0,0,0,0,-0.4137,0,0,0,0,0,1.5807,0,0,0,0,0,-1.587,0,0,0,0,0,-0.4137,0,0,0,0,0,1.5807,0,0,0,0,0,-1.587,0,0,0,0,0,-0.2094,0,0,0,0,0,-0.1047,0.0698,0,0,0,0,-0.0349,0.1047,0,0,0,0,0.0349,0.1047,0,0,0,0,0.0698,0.0698,0,0,0,0,0.2793,0,0,0,0,0,0,0,0,0,0,0")
        val INHALE = key("-0.1047,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.8378,0,0,0,0,0,-0.3142,0,0,0,0,0,0.1396,0,0,0,0,0,0.2443,0,0,0,0,0,0.6981,0,0,0,0,0,0.1745,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.6023,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.6023,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,0.0903,0,0,0,0,0,-0.057,0,0,0,0,0,0.2459,0,0,0,0,0,0.0903,0,0,0,0,0,-0.057,0,0,0,0,0,0.2459,0,0,0,0,0,-0.3644,0,0,0,0,0,1.7789,0,0,0,0,0,-1.7298,0,0,0,0,0,-0.3644,0,0,0,0,0,1.7789,0,0,0,0,0,-1.7298,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0.2618,0,0,0,0,0,0,0,0,0,0,0")
        val BREATH = key("0.0698,0,0,0,0,0,0.1047,0,0,0,0,0,-0.1745,0,0,0,0,0,0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.1396,0,0,0,0,0,0.8029,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.6721,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.6721,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,0.2133,0,0,0,0,0,-1.3237,0,0,0,0,0,0.9359,0,0,0,0,0,0.2133,0,0,0,0,0,-1.3237,0,0,0,0,0,0.9359,0,0,0,0,0,-0.4307,0,0,0,0,0,1.435,0,0,0,0,0,-1.4941,0,0,0,0,0,-0.4307,0,0,0,0,0,1.435,0,0,0,0,0,-1.4941,0,0,0,0,0,-0.1396,0,0,0,0,0,-0.0698,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0.1047,0,0,0,0,0,0,0,0,0,0,0")
        val FREEZE_WINDUP = key("-0.0698,0,0,0,0,0,-0.1396,0,0,0,0,0,-0.7679,0,0,0,0,0,-0.2443,0,0,0,0,0,0.1047,0,0,0,0,0,0.1745,0,0,0,0,0,0.6283,0,0,0,0,0,0.1396,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.6023,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.6023,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,0.0331,0,0,0,0,0,-0.057,0,0,0,0,0,0.2333,0,0,0,0,0,0.0331,0,0,0,0,0,-0.057,0,0,0,0,0,0.2333,0,0,0,0,0,-0.3841,0,0,0,0,0,1.7153,0,0,0,0,0,-1.6813,0,0,0,0,0,-0.3841,0,0,0,0,0,1.7153,0,0,0,0,0,-1.6813,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.6981,0,0,0,0,0,0,0,0,0,0,0")
        val FREEZE = key("0.1396,0,0,0,0,0,0.1745,0,0,0,0,0,0.2793,0,0,0,0,0,0.1745,0,0,0,0,0,0.1396,0,0,0,0,0,-0.733,0,0,0,0,0,-0.6981,0,0,0,0,0,0.8378,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.707,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.707,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,0.1527,0,0,0,0,0,-1.5683,0,0,0,0,0,1.1015,0,0,0,0,0,0.1527,0,0,0,0,0,-1.5683,0,0,0,0,0,1.1015,0,0,0,0,0,-0.4352,0,0,0,0,0,1.2754,0,0,0,0,0,-1.3998,0,0,0,0,0,-0.4352,0,0,0,0,0,1.2754,0,0,0,0,0,-1.3998,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val CLAW_WINDUP = key("-0.1396,-0.1745,-0.1047,0,0,0,-0.1396,-0.2443,-0.1396,0,0,0,-0.4538,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0,0,0,0,0,0,0.4887,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.7768,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.6023,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,-2.0944,0,-0.4189,0,0,0,-0.6981,0,0,0,0,0,-0.3491,0,0,0,0,0,0.0575,0,0,0,0,0,-0.057,0,0,0,0,0,0.2504,0,0,0,0,0,-0.212,0,0,0,0,0,1.8881,0,0,0,0,0,-1.9377,0,0,0,0,0,-0.4611,0,0,0,0,0,1.7789,0,0,0,0,0,-1.5791,0,0,0,0,0,-0.1396,0.1745,0,0,0,0,-0.0698,0.1745,0,0,0,0,0,0.1396,0,0,0,0,0.0349,0.1047,0,0,0,0,0.0698,0.0698,0,0,0,0,0.2793,0,0,0,0,0,0,0,0,0,0,0")
        val CLAW_HIT = key("0.1047,0.2443,0.0698,0,0,0,0.1745,0.3142,0.1047,0,0,0,-0.2793,0,0,0,0,0,0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.384,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.6023,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.9164,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,-0.8727,0,0.5934,0,0,0,-0.2094,0,0,0,0,0,0.5236,0,0,0,0,0,0.8137,0,0,0,0,0,-1.7806,0,0,0,0,0,0.6264,0,0,0,0,0,-0.5904,0,0,0,0,0,1.3368,0,0,0,0,0,-1.2478,0,0,0,0,0,-0.3111,0,0,0,0,0,1.361,0,0,0,0,0,-1.55,0,0,0,0,0,-0.1745,-0.1745,0,0,0,0,-0.1047,-0.1745,0,0,0,0,-0.0349,-0.1396,0,0,0,0,0,-0.1047,0,0,0,0,0.0698,-0.0698,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val TAIL_WINDUP = key("0,0.3142,0,0,0,0,0,0.2443,0,0,0,0,-0.4538,-0.4189,0,0,0,0,-0.0698,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0.5236,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.6372,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.6372,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,0.0618,0,0,0,0,0,-0.8147,0,0,0,0,0,0.6715,0,0,0,0,0,0.5849,0,0,0,0,0,-0.9364,0,0,0,0,0,0.3016,0,0,0,0,0,-0.7047,0,0,0,0,0,1.6556,0,0,0,0,0,-1.347,0,0,0,0,0,-0.2517,0,0,0,0,0,1.488,0,0,0,0,0,-1.6404,0,0,0,0,0,-0.1396,-0.5934,0,0,0,0,-0.0349,-0.4189,0,0,0,0,0,-0.3142,0,0,0,0,0.0349,-0.2443,0,0,0,0,0.0698,-0.1745,0,0,0,0,0.1745,-0.1047,0,0,0,0,0,0,0,0,0,0")
        val TAIL_HIT = key("0,-0.4189,0,0,0,0,0,-0.2793,0,0,0,0,-0.384,0.384,0,0,0,0,0,0,0,0,0,0,-0.0349,0,0,0,0,0,-0.0349,0,0,0,0,0,0.4887,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.6372,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.6372,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,0.6465,0,0,0,0,0,-0.8249,0,0,0,0,0,0.1372,0,0,0,0,0,0.0807,0,0,0,0,0,-0.8311,0,0,0,0,0,0.6202,0,0,0,0,0,-0.2336,0,0,0,0,0,1.4755,0,0,0,0,0,-1.6332,0,0,0,0,0,-0.8147,0,0,0,0,0,1.6466,0,0,0,0,0,-1.2067,0,0,0,0,0,-0.0698,0.6632,0,0,0,0,-0.0349,0.4538,0,0,0,0,0,0.384,0,0,0,0,0.0349,0.3142,0,0,0,0,0.0349,0.2094,0,0,0,0,0.1047,0.1396,0,0,0,0,0,0,0,0,0,0")
        val TAIL_FOLLOW = key("0,-0.5236,0,0,0,0,0,-0.3491,0,0,0,0,-0.384,0.4538,0,0,0,0,0,0,0,0,0,0,-0.0349,0,0,0,0,0,-0.0349,0,0,0,0,0,0.4887,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.6372,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.6372,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,0.6581,0,0,0,0,0,-0.5405,0,0,0,0,0,-0.0758,0,0,0,0,0,0.1388,0,0,0,0,0,-0.8773,0,0,0,0,0,0.5294,0,0,0,0,0,-0.2344,0,0,0,0,0,1.476,0,0,0,0,0,-1.6151,0,0,0,0,0,-0.9206,0,0,0,0,0,1.6091,0,0,0,0,0,-1.0391,0,0,0,0,0,-0.1047,0.7679,0,0,0,0,-0.0698,0.5585,0,0,0,0,-0.0349,0.4538,0,0,0,0,0,0.3491,0,0,0,0,0,0.2443,0,0,0,0,0.0698,0.1396,0,0,0,0,0,0,0,0,0,0")
        val GUST_WINDUP = key("-0.2443,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.5934,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.0698,0,0,0,0,0,0,0,0,0,0,0,0.6283,0,0,0,0,0,0.2094,0,0,0,0,0,0,0,0,0,0,0,0,0.5193,1.0304,0,0,0,-0.2238,0.1247,0.1292,0,0,0,-0.0767,0.2681,0.1763,0,0,0,0,-0.5193,-1.0304,0,0,0,-0.2238,-0.1247,-0.1292,0,0,0,-0.0767,-0.2681,-0.1763,0,0,0,0.1949,0,0,0,0,0,-0.057,0,0,0,0,0,0.2809,0,0,0,0,0,0.1949,0,0,0,0,0,-0.057,0,0,0,0,0,0.2809,0,0,0,0,0,0.1368,0,0,0,0,0,1.7645,0,0,0,0,0,-2.077,0,0,0,0,0,0.1368,0,0,0,0,0,1.7645,0,0,0,0,0,-2.077,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,0.5411,0,0,0,0,0,0.0349,0,0,0,0,0,0.3316,0,0,0,0,0,0,0,0,0,0,0")
        val GUST_HIT = key("0.1396,0,0,0,0,0,0.1047,0,0,0,0,0,-0.2443,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.2793,0,0,0,0,0,0.5236,0,0,0,0,0,0,0,0,0,0,0,0.1837,-0.6492,0.3218,0,0,0,2.9228,-0.6344,-0.1187,0,0,0,-0.0767,0.4516,-0.0156,0,0,0,0.1837,0.6492,-0.3218,0,0,0,2.9228,0.6344,0.1187,0,0,0,-0.0767,-0.4516,0.0156,0,0,0,0.1905,0,0,0,0,0,-1.4455,0,0,0,0,0,1.0107,0,0,0,0,0,0.1905,0,0,0,0,0,-1.4455,0,0,0,0,0,1.0107,0,0,0,0,0,0.1087,0,0,0,0,0,0.7501,0,0,0,0,0,-1.4184,0,0,0,0,0,0.1087,0,0,0,0,0,0.7501,0,0,0,0,0,-1.4184,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val GRAB_REACH = key("0.2443,0,0,0,0,0,0.2443,0,0,0,0,0,0.1745,0,0,0,0,0,0.1745,0,0,0,0,0,-0.5236,0,0,0,0,0,-0.2443,0,0,0,0,0,-0.3142,0,0,0,0,0,0.384,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.8815,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.8815,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,-1.4087,0,0,0,0,0,-0.057,0,0,0,0,0,0.977,0,0,0,0,0,0.021,0,0,0,0,0,-1.8221,0,0,0,0,0,1.3124,0,0,0,0,0,-0.4149,0,0,0,0,0,0.9973,0,0,0,0,0,-1.2467,0,0,0,0,0,-0.4149,0,0,0,0,0,0.9973,0,0,0,0,0,-1.2467,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val GRAB_HOLD = key("-0.1745,0,0,0,0,0,-0.2793,0,0,0,0,0,-0.5236,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0349,0,0,0,0,0,0,0,0,0,0,0,0.5236,0,0,0,0,0,0.1047,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.6023,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.6023,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,-1.2217,0,0.1745,0,0,0,-1.0472,0,0,0,0,0,-0.6981,0,0,0,0,0,0.2279,0,0,0,0,0,-0.057,0,0,0,0,0,0.2828,0,0,0,0,0,-0.3143,0,0,0,0,0,1.8995,0,0,0,0,0,-1.8307,0,0,0,0,0,-0.3143,0,0,0,0,0,1.8995,0,0,0,0,0,-1.8307,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,0.1222,0,0,0,0,0,0.3491,0,0,0,0,0,0.384,0,0,0,0,0,0,0,0,0,0,0")
        val THROW_WINDUP = key("-0.384,0,0,0,0,0,-0.3491,-0.2793,0,0,0,0,-0.4189,0,0,0,0,0,-0.1047,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.4538,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.5193,1.0304,0,0,0,-0.2238,0.1247,0.1292,0,0,0,-0.0767,0.2681,0.1763,0,0,0,0,-0.5193,-1.0304,0,0,0,-0.2238,-0.1247,-0.1292,0,0,0,-0.0767,-0.2681,-0.1763,0,0,0,0.3491,0,0.3491,0,0,0,-1.2217,0,0,0,0,0,-0.5236,0,0,0,0,0,0.3542,0,0,0,0,0,-0.057,0,0,0,0,0,0.4338,0,0,0,0,0,-0.0584,0,0,0,0,0,2.2058,0,0,0,0,0,-2.1834,0,0,0,0,0,-0.0584,0,0,0,0,0,2.2058,0,0,0,0,0,-2.1834,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,0.5585,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0.3316,0,0,0,0,0,0,0,0,0,0,0")
        val THROW_HIT = key("0.1745,0,0,0,0,0,0.1745,0.2793,0,0,0,0,-0.2443,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.3142,0,0,0,0,0,0.3491,0,0,0,0,0,0,0,0,0,0,0,0.1837,-0.6492,0.3218,0,0,0,2.9228,-0.6344,-0.1187,0,0,0,-0.0767,0.4516,-0.0156,0,0,0,0.1837,0.6492,-0.3218,0,0,0,2.9228,0.6344,0.1187,0,0,0,-0.0767,-0.4516,0.0156,0,0,0,-1.9199,0,-0.1745,0,0,0,-0.1745,0,0,0,0,0,0.1745,0,0,0,0,0,0.3784,0,0,0,0,0,-1.7294,0,0,0,0,0,0.9878,0,0,0,0,0,-0.4325,0,0,0,0,0,1.189,0,0,0,0,0,-1.351,0,0,0,0,0,-0.4325,0,0,0,0,0,1.189,0,0,0,0,0,-1.351,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val ROAR = key("-0.1745,0,0,0,0,0,-0.2094,0,0,0,0,0,-0.7679,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.1745,0,0,0,0,0,0.6981,0,0,0,0,0,0,0,0,0,0,0,-0.935,0.2997,0.6023,0,0,0,0.1055,-0.0312,-1.6026,0,0,0,-0.1043,1.0616,2.4579,0,0,0,-0.935,-0.2997,-0.6023,0,0,0,0.1055,0.0312,1.6026,0,0,0,-0.1043,-1.0616,-2.4579,0,0,0,0.1718,0,0,0,0,0,-0.057,0,0,0,0,0,0.2691,0,0,0,0,0,0.1718,0,0,0,0,0,-0.057,0,0,0,0,0,0.2691,0,0,0,0,0,-0.3143,0,0,0,0,0,1.8995,0,0,0,0,0,-1.8307,0,0,0,0,0,-0.3143,0,0,0,0,0,1.8995,0,0,0,0,0,-1.8307,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.1047,0,0,0,0,0,0.1396,0,0,0,0,0,0.1745,0,0,0,0,0,0,0,0,0,0,0")
        val DOWNED = key("0.0349,0,0.0698,0,10.0382,0,0.0349,0,-0.0349,0,0,0,0.3491,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,-0.2443,0,0,0,0,0,-0.4538,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1137,0.3638,-0.1812,0,0,0,-0.0306,-0.4401,-0.211,0,0,0,-0.0493,0.4513,0.0751,0,0,0,-0.1137,-0.3638,0.0764,0,0,0,-0.0306,0.4401,0.211,0,0,0,-0.0493,-0.4513,-0.0751,0,0,0,-1.1493,0,0,0,0,0,-0.057,0,0,0,0,0,1.1367,0,0,0,0,0,-1.2135,0,0,0,0,0,-0.057,0,0,0,0,0,1.201,0,0,0,0,0,-0.1279,0,0,0,0,0,2.3515,0,0,0,0,0,-2.6773,0,0,0,0,0,0.2604,0,0,0,0,0,2.2927,0,0,0,0,0,-3.007,0,0,0,0,0,0.0698,0.1396,0,0,0,0,0,0.1396,0,0,0,0,0,0.1745,0,0,0,0,0,0.2094,0,0,0,0,0,0.1745,0,0,0,0,0,0.1047,0,0,0,0,0,0,0,0,0,0")
        val EXHAUSTED = key("0.1396,0,0,0,0,0,0.1047,0,0,0,0,0,0.2793,0,0,0,0,0,0.1396,0,0,0,0,0,0.1047,0,0,0,0,0,-0.0349,0,0,0,0,0,-0.9425,0,0,0,0,0,0.2094,0,0,0,0,0,0,0,0,0,0,0,-0.1137,0.3638,-0.3208,0,0,0,-0.0306,-0.4401,-0.211,0,0,0,-0.0493,0.4513,0.0751,0,0,0,-0.1137,-0.3638,0.3208,0,0,0,-0.0306,0.4401,0.211,0,0,0,-0.0493,-0.4513,-0.0751,0,0,0,0.1905,0,0,0,0,0,-1.4455,0,0,0,0,0,1.0107,0,0,0,0,0,0.1905,0,0,0,0,0,-1.4455,0,0,0,0,0,1.0107,0,0,0,0,0,-0.4352,0,0,0,0,0,1.2754,0,0,0,0,0,-1.3998,0,0,0,0,0,-0.4352,0,0,0,0,0,1.2754,0,0,0,0,0,-1.3998,0,0,0,0,0,-0.2443,0,0,0,0,0,-0.1396,0.1047,0,0,0,0,-0.0349,0.1047,0,0,0,0,0,0.1047,0,0,0,0,0.0349,0.0698,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0")
        val TAKEOFF_CROUCH = key("-0.0698,0,0,0,7,0,0,0,0,0,0,0,-0.3491,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0.4538,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.114,0.1209,0.98,0,0,0,-0.0931,-0.0987,0.1861,0,0,0,-0.0632,0.299,0.2575,0,0,0,0.114,-0.1209,-0.98,0,0,0,-0.0931,0.0987,-0.1861,0,0,0,-0.0632,-0.299,-0.2575,0,0,0,0.5217,0,0,0,0,0,-1.718,0,0,0,0,0,1.2661,0,0,0,0,0,0.5217,0,0,0,0,0,-1.718,0,0,0,0,0,1.2661,0,0,0,0,0,-0.5853,0,0,0,0,0,2.5771,0,0,0,0,0,-2.3419,0,0,0,0,0,-0.5853,0,0,0,0,0,2.5771,0,0,0,0,0,-2.3419,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,0.2269,0,0,0,0,0,0.1396,0,0,0,0,0,0.384,0,0,0,0,0,0,0,0,0,0,0")
        val TAKEOFF_BEAT = key("-0.1745,0,0,0,-10,0,0,0,0,0,0,0,-0.4189,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0349,0,0,0,0,0,0,0,0,0,0,0,0.4189,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1151,0,-0.6908,0,0,0,0.0161,-0.0548,-0.4155,0,0,0,-0.0022,0.3611,-0.2521,0,0,0,-0.1151,0,0.6908,0,0,0,0.0161,0.0548,0.4155,0,0,0,-0.0022,-0.3611,0.2521,0,0,0,-0.5236,0,0,0,0,0,-0.3491,0,0,0,0,0,0.6981,0,0,0,0,0,-0.5236,0,0,0,0,0,-0.3491,0,0,0,0,0,0.6981,0,0,0,0,0,-0.5236,0,0,0,0,0,0.6981,0,0,0,0,0,-0.3491,0,0,0,0,0,-0.5236,0,0,0,0,0,0.6981,0,0,0,0,0,-0.3491,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val RESONANCE = key("-0.1396,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.6981,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.1047,0,0,0,0,0,0.5236,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0485,1.3258,0,0,0,-0.007,-0.0977,0.1458,0,0,0,0.0076,0.1979,0.1509,0,0,0,0,-0.0485,-1.3258,0,0,0,-0.007,0.0977,-0.1458,0,0,0,0.0076,-0.1979,-0.1509,0,0,0,0.1169,0,0,0,0,0,-0.057,0,0,0,0,0,0.2542,0,0,0,0,0,0.1169,0,0,0,0,0,-0.057,0,0,0,0,0,0.2542,0,0,0,0,0,-0.3412,0,0,0,0,0,1.8403,0,0,0,0,0,-1.7795,0,0,0,0,0,-0.3412,0,0,0,0,0,1.8403,0,0,0,0,0,-1.7795,0,0,0,0,0,-0.1396,0,0,0,0,0,-0.0698,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0.4538,0,0,0,0,0,0,0,0,0,0,0")
        val LIE_DOWN = key("0,0,0,0,12,0,0,0,0,0,0,0,0.3142,-0.4538,0,0,0,0,0.1047,-0.5236,0,0,0,0,0,-0.5585,0,0,0,0,0,-0.4538,0,0,0,0,-0.0349,-0.3491,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6821,1.0121,1.9234,0,0,0,-0.1561,-0.0637,-2.9753,0,0,0,0.0755,0.4993,-3.0377,0,0,0,-0.6821,-1.0121,-1.9234,0,0,0,-0.1561,0.0637,2.9753,0,0,0,0.0755,-0.4993,3.0377,0,0,0,-1.1356,0,0,0,0,0,-0.057,0,0,0,0,0,1.1926,0,0,0,0,0,-0.7958,0,0,0,0,0,-0.7565,0,0,0,0,0,1.5523,0,0,0,0,0,0.4306,0,0,0,0,0,2.34,0,0,0,0,0,-3.1906,0,0,0,0,0,0.345,0,0,0,0,0,2.5572,0,0,0,0,0,-3.3222,0,0,0,0,0,0.0349,0.3491,0,0,0,0,0,0.5236,0,0,0,0,0,0.5934,0,0,0,0,0,0.6283,0,0,0,0,0,0.5236,0,0,0,0,0,0.3491,0,0,0,0,0,0,0,0,0,0")
        val HOVER = key("-0.2443,0,0,0,-6,0,0,0,0,0,0,0,-0.3142,0,0,0,0,0,-0.0698,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.4538,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.1381,0.1194,0,0,0,0.0165,-0.1171,-0.1202,0,0,0,0.0013,0.443,-0.0593,0,0,0,0,-0.1381,-0.1194,0,0,0,0.0165,0.1171,0.1202,0,0,0,0.0013,-0.443,0.0593,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val FLY_UP = key("-0.2443,0,0,0,-6,0,0,0,0,0,0,0,-0.3142,0,0,0,0,0,-0.0698,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.4538,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.114,0.1209,0.98,0,0,0,-0.0931,-0.0987,0.1861,0,0,0,-0.0632,0.299,0.2575,0,0,0,0.114,-0.1209,-0.98,0,0,0,-0.0931,0.0987,-0.1861,0,0,0,-0.0632,-0.299,-0.2575,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val FLY_DOWN = key("-0.2443,0,0,0,-6,0,0,0,0,0,0,0,-0.3142,0,0,0,0,0,-0.0698,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.4538,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1151,0,-0.6908,0,0,0,0.0161,-0.0548,-0.4155,0,0,0,-0.0022,0.3611,-0.2521,0,0,0,-0.1151,0,0.6908,0,0,0,0.0161,0.0548,0.4155,0,0,0,-0.0022,-0.3611,0.2521,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val GLIDE = key("0.0349,0,0,0,-6,0,0,0,0,0,0,0,-0.0698,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.1369,0.1781,0,0,0,0.0189,-0.0957,-0.1386,0,0,0,-0.0033,0.4385,-0.1175,0,0,0,0,-0.1369,-0.1781,0,0,0,0.0189,0.0957,0.1386,0,0,0,-0.0033,-0.4385,0.1175,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val DIVE_WINDUP = key("-0.5236,0,0,0,-6,0,0,0,0,0,0,0,-0.1745,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.3491,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.114,0.1209,0.98,0,0,0,-0.0931,-0.0987,0.1861,0,0,0,-0.0632,0.299,0.2575,0,0,0,0.114,-0.1209,-0.98,0,0,0,-0.0931,0.0987,-0.1861,0,0,0,-0.0632,-0.299,-0.2575,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val DIVE = key("0.6632,0,0,0,-6,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1047,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.8011,1.0974,0.7086,0,0,0,0.0141,-0.1736,-2.8388,0,0,0,-0.0051,0.1986,3.1406,0,0,0,-0.8011,-1.0974,-0.7086,0,0,0,0.0141,0.1736,2.8388,0,0,0,-0.0051,-0.1986,-3.1406,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0")
        val CRASH_WINDUP = key("-0.6283,0,0,0,-6,0,0,0,0,0,0,0,-0.1047,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.2793,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.114,0.1209,0.98,0,0,0,-0.0931,-0.0987,0.1861,0,0,0,-0.0632,0.299,0.2575,0,0,0,0.114,-0.1209,-0.98,0,0,0,-0.0931,0.0987,-0.1861,0,0,0,-0.0632,-0.299,-0.2575,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val CRASH = key("1.3963,0,0,0,-6,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0698,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.8011,1.0974,0.7086,0,0,0,0.0141,-0.1736,-2.8388,0,0,0,-0.0051,0.1986,3.1406,0,0,0,-0.8011,-1.0974,-0.7086,0,0,0,0.0141,0.1736,2.8388,0,0,0,-0.0051,-0.1986,-3.1406,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0")
        val INHALE_AIR = key("-0.2793,0,0,0,-6,0,-0.1396,0,0,0,0,0,-0.7679,0,0,0,0,0,-0.2793,0,0,0,0,0,0.1396,0,0,0,0,0,0.2094,0,0,0,0,0,0.6981,0,0,0,0,0,0.1745,0,0,0,0,0,0,0,0,0,0,0,0,0.1381,0.1194,0,0,0,0.0165,-0.1171,-0.1202,0,0,0,0.0013,0.443,-0.0593,0,0,0,0,-0.1381,-0.1194,0,0,0,0.0165,0.1171,0.1202,0,0,0,0.0013,-0.443,0.0593,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val BREATH_AIR = key("0.1047,0,0,0,-6,0,0.0698,0,0,0,0,0,0.1745,0,0,0,0,0,0.1047,0,0,0,0,0,0.1047,0,0,0,0,0,0.0698,0,0,0,0,0,0.2443,0,0,0,0,0,0.8029,0,0,0,0,0,0,0,0,0,0,0,0,0.1381,0.1194,0,0,0,0.0165,-0.1171,-0.1202,0,0,0,0.0013,0.443,-0.0593,0,0,0,0,-0.1381,-0.1194,0,0,0,0.0165,0.1171,0.1202,0,0,0,0.0013,-0.443,0.0593,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,0.9599,0,0,0,0,0,-1.6581,0,0,0,0,0,1.0472,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,1.2217,0,0,0,0,0,-0.3491,0,0,0,0,0,1.2217,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val LAND_WINDUP = key("-0.4189,0,0,0,-10,0,0,0,0,0,0,0,-0.384,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0349,0,0,0,0,0,0,0,0,0,0,0,0.5236,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3753,-0.5325,0.7854,0,0,0,-0.5307,-0.2116,-0.2055,0,0,0,-0.9446,-0.1258,-0.3804,0,0,0,-0.3753,0.5325,-0.7854,0,0,0,-0.5307,0.2116,0.2055,0,0,0,-0.9446,0.1258,0.3804,0,0,0,-0.5236,0,0,0,0,0,-0.3491,0,0,0,0,0,0.6981,0,0,0,0,0,-0.5236,0,0,0,0,0,-0.3491,0,0,0,0,0,0.6981,0,0,0,0,0,-0.5236,0,0,0,0,0,0.6981,0,0,0,0,0,-0.3491,0,0,0,0,0,-0.5236,0,0,0,0,0,0.6981,0,0,0,0,0,-0.3491,0,0,0,0,0,0.0698,0,0,0,0,0,0.0349,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
        val LAND_HIT = key("0.1047,0,0,0,3,0,0.1396,0,0,0,0,0,0.1047,0,0,0,0,0,0.1047,0,0,0,0,0,0.1047,0,0,0,0,0,0.0698,0,0,0,0,0,-0.4538,0,0,0,0,0,0.1745,0,0,0,0,0,0,0,0,0,0,0,-0.1151,0,0.0073,0,0,0,0.0161,-0.0548,-0.4155,0,0,0,-0.0022,0.3611,-0.2521,0,0,0,-0.1151,0,-0.0073,0,0,0,0.0161,0.0548,0.4155,0,0,0,-0.0022,-0.3611,0.2521,0,0,0,0.1374,0,0,0,0,0,-1.7244,0,0,0,0,0,1.3427,0,0,0,0,0,0.1374,0,0,0,0,0,-1.7244,0,0,0,0,0,1.3427,0,0,0,0,0,-0.6279,0,0,0,0,0,1.8088,0,0,0,0,0,-1.7056,0,0,0,0,0,-0.6279,0,0,0,0,0,1.8088,0,0,0,0,0,-1.7056,0,0,0,0,0,-0.1745,0,0,0,0,0,-0.1047,0,0,0,0,0,-0.0698,0,0,0,0,0,-0.0349,0,0,0,0,0,0.0349,0,0,0,0,0,0.0698,0,0,0,0,0,0,0,0,0,0,0")
    }
    // </keys>
}

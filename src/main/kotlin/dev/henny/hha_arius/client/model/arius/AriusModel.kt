package dev.henny.hha_arius.client.model.arius

import dev.henny.hha_arius.boss.AriusForm
import dev.henny.hha_arius.boss.AriusPose
import dev.henny.hha_arius.boss.AriusTimings
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.util.math.MathHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class AriusRenderState : LivingEntityRenderState() {
    var entityId = 0
    var bossPose = AriusPose.IDLE
    var previousPose = AriusPose.IDLE
    var poseTime = 0f
    var poseChangedAge = 0

    /** Ausholzeit dieser Pose (schon aufgelöst: 0 vom Server = Standard aus [AriusTimings]). */
    var windup = 0
    var form = AriusForm.DARK
    var sheathed = false
    var eyeGlow = 40
    var lead = false
    var open = false
    var sideSign = 1
    var walk = 0f
    var walkPhase = 0f
}

/** Eine Schlüsselpose: je Knochen (pitch, yaw, roll, dx, dy, dz), siehe [AriusModel.Keys]; hidden = ausgeblendete Knochen. */
class AriusKey(val v: FloatArray, val hidden: IntArray)

private fun key(data: String, hidden: IntArray) = AriusKey(data.split(',').map { it.toFloat() }.toFloatArray(), hidden)

/**
 * Arius. Geometrie, Texturen und alle Schlüsselposen stammen aus tools/assets/arius_model.py; der Block [Keys] wird dort
 * erzeugt (`python tools/assets/arius_model.py --kotlin`), damit Vorschau und Spiel dieselben Winkel benutzen. Hier
 * steht nur der Ablauf je [AriusPose] (Vertrag aus Poses.kt: Ausholen weich, Treffer genau bei t = windup, Nachlauf
 * [AriusTimings.ARIUS_RECOVER], dann IDLE; Haltungen werden gehalten) plus Atmen, Gehen, Nachschwingen von Haar und
 * Mantel, Kopfblick und die Formen (Mantel/Rüstung/Gewand über Knochen-Sichtbarkeit).
 *
 * Die Überblendung startet bei einem Posenwechsel aus der Haltung, die das Modell im Bild davor tatsächlich hatte
 * (je Entity gemerkt), auch mitten in einem Schlag; ohne Merkwert aus der Endhaltung von previousPose.
 */
class AriusModel(root: ModelPart) : EntityModel<AriusRenderState>(root) {

    private fun path(p: String): ModelPart = p.split('/').fold(root) { m, n -> m.getChild(n) }
    private val parts: Array<ModelPart> = Array(Keys.COUNT) { path(Keys.BONES[it]) }
    private fun part(i: Int) = parts[i]

    private val formParts: Array<Array<ModelPart>> = arrayOf(
        Keys.HIDE_DARK.map(::path).toTypedArray(),
        Keys.HIDE_SERIOUS.map(::path).toTypedArray(),
        Keys.HIDE_WHITE.map(::path).toTypedArray(),
    )
    private val formAll: Array<ModelPart> = formParts.flatten().distinct().toTypedArray()
    private val solvane = path(Keys.SOLVANE)
    private val sheathHilt = path(Keys.SHEATH_HILT)

    /** IDLE mit +2π Beckendrehung: Rückweg nach dem Drehschnitt ohne Zurückdrehen. */
    private val idleSpun = AriusKey(Keys.IDLE.v.copyOf().also { it[Keys.HIPS * 6 + 1] += (2 * PI).toFloat() }, Keys.IDLE.hidden)

    // --- Merkwerte je Entity (Überblendung aus der echten Haltung, weiche Umschaltungen) ------------------------------

    private class Memory {
        var changedAge = Int.MIN_VALUE
        var last: FloatArray? = null
        var lastHidden: IntArray = IntArray(0)
        var snapshot: FloatArray? = null
        var snapshotHidden: IntArray = IntArray(0)
        var lastSheathed = false
        var drawOnRise = false
        var time = Float.NaN
        var sheath = 0f
        var open = 0f
    }

    private val memory = HashMap<Int, Memory>()

    private fun memoryOf(id: Int): Memory {
        if (memory.size > 64) memory.clear()
        return memory.getOrPut(id) { Memory() }
    }

    // --- Hüllkurven ------------------------------------------------------------------------------------------------

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun easeIn(t: Float) = t * t
    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)
    private fun snap(t: Float) = t * t * t
    private fun linear(t: Float) = t

    private fun mix(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

    /** Zeitleiste: hält die zuletzt erreichte Haltung und blendet in den Abschnitten [t0, t1] zur nächsten über. */
    private class Track(var cur: FloatArray, var hid: IntArray, private val t: Float) {
        var out: FloatArray? = null
        fun to(t0: Float, t1: Float, target: AriusKey, ease: (Float) -> Float): Track {
            if (out != null) return this
            when {
                t >= t1 -> {
                    cur = target.v
                    hid = target.hidden
                }
                t > t0 -> {
                    val e = ease(MathHelper.clamp((t - t0) / max(t1 - t0, 0.001f), 0f, 1f))
                    out = FloatArray(cur.size) { cur[it] + (target.v[it] - cur[it]) * e }
                    if (e > 0.5f) hid = target.hidden
                }
                else -> out = cur
            }
            return this
        }

        fun get(): FloatArray = out ?: cur
    }

    private fun windupOf(pose: Int, w: Int): Float = (if (w > 0) w else AriusTimings.ARIUS_WINDUP[pose]).toFloat()
    private fun recoverOf(pose: Int): Float = AriusTimings.ARIUS_RECOVER[pose].toFloat()

    /**
     * Angriff nach Vertrag: Ausholhaltung weich erreichen (höchstens 8 Ticks), halten, Schlag beschleunigt in die
     * Trefferhaltung genau bei t = w, Nachlauf, ab w + r - 10 zurück nach IDLE (erreicht bei w + r).
     */
    private fun attack(tr: Track, w: Float, r: Float, wind: AriusKey, hit: AriusKey, follow: AriusKey?,
                       strike: Float = 3f, followFor: Float = 4f): Track {
        val s = min(strike, w * 0.5f)
        tr.to(0f, reach(w, s), wind, ::smooth).to(w - s, w, hit, ::easeIn)
        val f = if (follow != null) min(followFor, max(1f, r * 0.4f)) else 0f
        if (follow != null) tr.to(w, w + f, follow, ::easeOut)
        val end = w + r
        return tr.to(max(w + f, end - min(r, 10f)), end, Keys.IDLE, ::smooth)
    }

    /** Ausholhaltung erreicht: höchstens 8 Ticks und immer vor dem Schlag (auch bei kurzem Server-Windup). */
    private fun reach(w: Float, strike: Float): Float = MathHelper.clamp(w - min(strike, w * 0.5f), 0.5f, 8f)

    private fun evaluate(pose: Int, t: Float, w: Float, from: FloatArray, fromHidden: IntArray, m: Memory?): Track {
        val r = recoverOf(pose)
        val tr = Track(from, fromHidden, t)
        val sheath = m?.sheath ?: 0f
        return when (pose) {
            AriusPose.IDLE -> tr.to(0f, 10f, if (sheath > 0.5f) Keys.IDLE_EMPTY else Keys.IDLE, ::smooth)
            AriusPose.SEATED -> tr.to(0f, 14f, if (sheath > 0.5f) Keys.SEATED_EMPTY else Keys.SEATED, ::smooth)
            AriusPose.RISE -> {
                val s = min(3f, w * 0.15f)
                if (m?.drawOnRise == true) {
                    tr.to(0f, w * 0.3f, Keys.RISE_LEAN_EMPTY, ::smooth).to(w * 0.33f, w * 0.6f, Keys.RISE_REACH, ::smooth)
                        .to(w * 0.65f, w * 0.8f, Keys.RISE_DRAW, ::easeOut).to(w - s, w, Keys.RISE_FLICK, ::easeIn)
                } else {
                    tr.to(0f, w * 0.3f, Keys.RISE_LEAN, ::smooth).to(w * 0.33f, w - s, Keys.RISE_DRAW, ::smooth)
                        .to(w - s, w, Keys.RISE_FLICK, ::easeIn)
                }
                tr.to(w + 1f, w + max(r, 4f), Keys.IDLE, ::smooth)
            }
            AriusPose.COMBO_1 -> attack(tr, w, r, Keys.C1_WIND, Keys.C1_HIT, Keys.C1_FOLLOW)
            AriusPose.COMBO_2 -> attack(tr, w, r, Keys.C2_WIND, Keys.C2_HIT, Keys.C2_FOLLOW)
            AriusPose.COMBO_3 -> attack(tr, w, r, Keys.C3_WIND, Keys.C3_HIT, Keys.C3_HOLD, strike = 3f, followFor = 8f)
            AriusPose.COMBO_4 -> attack(tr, w, r, Keys.C4_WIND, Keys.C4_HIT, Keys.C4_FOLLOW, strike = 2.5f, followFor = 5f)
            AriusPose.LUNGE -> {
                tr.to(0f, MathHelper.clamp(w - 2f, 1f, 8f), Keys.LUNGE_CROUCH, ::smooth).to(w - 2f, w, Keys.LUNGE_SPRINT, ::easeIn)
                if (t > w && t < w + 10f) {
                    val k = 0.5f - 0.5f * cos((t - w) * (2f * PI.toFloat() / 5f))
                    tr.out = mix(Keys.LUNGE_SPRINT.v, Keys.LUNGE_SPRINT_B.v, k)
                }
                tr.to(w + 10f, w + 14f, Keys.LUNGE_SLIDE, ::easeOut).to(w + r - 10f, w + r, Keys.IDLE, ::smooth)
            }
            AriusPose.WALL_CAST -> tr.to(0f, reach(w, 3f), Keys.WALL_CROUCH, ::smooth).to(w - min(3f, w * 0.5f), w, Keys.WALL_PRESS, ::easeIn)
                .to(w + 1f, w + r * 0.7f, Keys.WALL_RISE, ::smooth).to(w + r * 0.7f, w + r, Keys.IDLE, ::smooth)
            AriusPose.STRIP_SLASH -> attack(tr, w, r, Keys.STRIP_WIND, Keys.STRIP_HIT, Keys.STRIP_FOLLOW, strike = 4f, followFor = 5f)
            AriusPose.GUARD -> tr.to(0f, 6f, Keys.GUARD, ::easeOut)
            AriusPose.PARRY_KICK -> tr.to(0f, w * 0.3f, Keys.PK_DEFLECT, ::easeOut).to(w * 0.4f, w * 0.7f, Keys.PK_TURN, ::smooth)
                .to(w * 0.7f, w, Keys.PK_KICK, ::easeIn).to(w + 2f, w + max(r, 4f), Keys.IDLE, ::smooth)
            AriusPose.GRIP_CAST -> tr.to(0f, reach(w, 2f), Keys.GRIP_AIM, ::smooth).to(w - min(2f, w * 0.5f), w, Keys.GRIP_SNAP, ::easeOut)
                .to(w + r - 8f, w + r, Keys.IDLE, ::smooth)
            AriusPose.POMMEL -> attack(tr, w, r, Keys.POMMEL_RAISE, Keys.POMMEL_HIT, null, strike = 3f)
            AriusPose.STAB_GROUND -> tr.to(0f, reach(w, 3f), Keys.STAB_RAISE, ::smooth).to(w - min(3f, w * 0.5f), w, Keys.STAB_HIT, ::snap)
                .to(w + 3f, w + 8f, Keys.STAB_STUCK, ::smooth).to(w + r - 14f, w + r - 6f, Keys.STAB_PULL, ::smooth)
                .to(w + r - 6f, w + r, Keys.IDLE, ::smooth)
            AriusPose.SIDESTEP -> {
                val left = stepLeft
                val step = if (left) Keys.STEP_L else Keys.STEP_R
                val land = if (left) Keys.STEP_L_LAND else Keys.STEP_R_LAND
                tr.to(0f, w * 0.5f, step, ::easeOut).to(w * 0.5f, w, land, ::smooth).to(w + 1f, w + max(r, 3f), Keys.IDLE, ::smooth)
            }
            AriusPose.ARROW_CUT -> tr.to(0f, w * 0.5f, Keys.ARROW_WIND, ::easeOut).to(w * 0.5f, w, Keys.ARROW_HIT, ::easeIn)
                .to(w + 1f, w + max(r, 3f), Keys.IDLE, ::smooth)
            AriusPose.CUT_CHARGE -> tr.to(0f, 10f, Keys.CUT_CHARGE, ::smooth)
            AriusPose.CUT_RELEASE -> tr.to(0f, max(1f, w * 0.4f), Keys.CUT_TOP, ::easeOut).to(max(1f, w - 2f), w, Keys.CUT_STRIKE, ::easeIn)
                .to(w, w + 5f, Keys.CUT_LOW, ::easeOut)
            AriusPose.GATE_KICK -> tr.to(0f, w * 0.3f, Keys.GK_PARRY, ::easeOut).to(w * 0.35f, w * 0.78f, Keys.GK_KNEE, ::smooth)
                .to(w * 0.78f, w, Keys.GK_KICK, ::snap).to(w + 3f, w + r, Keys.IDLE, ::smooth)
            AriusPose.STAGGER -> tr.to(0f, 8f, Keys.STAGGER, ::easeOut)
            AriusPose.TRANSFORM -> tr.to(0f, w * 0.1f, Keys.TR_KNEEL, ::easeOut).to(w * 0.25f, w * 0.7f, Keys.TR_RISE, ::smooth)
                .to(w * 0.7f, w, Keys.TR_OPEN, ::smooth)
            AriusPose.LAUNCH -> tr.to(0f, min(6f, w - 3f), Keys.LAUNCH_WIND, ::smooth).to(w - 3f, w, Keys.LAUNCH_HIT, ::easeIn)
                // Ausnahme: hält die Sprunghaltung bis w + r (danach folgt AIR_KICK) und sinkt erst dann nach IDLE.
                .to(w, w + 3f, Keys.LAUNCH_FOLLOW, ::easeOut).to(w + r, w + r + 8f, Keys.IDLE, ::smooth)
            AriusPose.AIR_KICK -> tr.to(0f, min(6f, w - 3f), Keys.AIR_TUCK, ::easeOut).to(w - 3f, w, Keys.AIR_KICK, ::snap)
                .to(w + 2f, w + 6f, Keys.AIR_LAND, ::easeIn).to(w + r - 5f, w + r, Keys.IDLE, ::smooth)
            AriusPose.NULL_CUT -> attack(tr, w, r, Keys.NULL_WIND, Keys.NULL_HIT, Keys.NULL_FOLLOW, strike = 2f, followFor = 3f)
            AriusPose.CORE_RAISE -> tr.to(0f, 12f, Keys.CORE_RAISE, ::smooth)
            AriusPose.BREAKTHROUGH -> tr.to(0f, reach(w, 4f), Keys.BT_AIR, ::easeOut).to(w - min(4f, w * 0.5f), w, Keys.BT_HIT, ::snap)
                .to(w, w + 6f, Keys.BT_LOW, ::easeOut).to(w + r - 10f, w + r, Keys.IDLE, ::smooth)
            AriusPose.IAI_STANCE -> tr.to(0f, 10f, Keys.IAI_STANCE, ::smooth)
            AriusPose.IAI_DRAW -> tr.to(0f, w * 0.5f, Keys.IAI_MID, ::easeIn).to(w * 0.5f, w, Keys.IAI_HIT, ::linear)
                .to(w, w + 5f, Keys.IAI_END, ::easeOut).to(w + r - 12f, w + r, Keys.IDLE, ::smooth)
            AriusPose.KNEEL -> tr.to(0f, 12f, Keys.KNEEL, ::smooth)
            AriusPose.THROW_CRYSTALS -> tr.to(0f, reach(w, 3f), Keys.THROW_WIND, ::smooth).to(w - min(3f, w * 0.5f), w, Keys.THROW_HIT, ::easeOut)
                .to(w + r - 8f, w + r, Keys.IDLE, ::smooth)
            AriusPose.SPLIT_FIELD -> tr.to(0f, reach(w, 3f), Keys.SPLIT_RAISE, ::smooth).to(w - min(3f, w * 0.5f), w, Keys.SPLIT_HIT, ::snap)
                .to(w, w + 5f, Keys.SPLIT_LOW, ::easeOut).to(w + r - 10f, w + r, Keys.IDLE, ::smooth)
            AriusPose.WHISTLE -> tr.to(0f, min(6f, w), Keys.WHISTLE, ::smooth).to(w + r - 6f, w + r, Keys.IDLE, ::smooth)
            AriusPose.SPIN_CUT -> tr.to(0f, reach(w, 4f), Keys.SPIN_WIND, ::smooth)
                .to(w - min(4f, w * 0.5f), w, Keys.SPIN_HIT, ::linear).to(w, w + 4f, Keys.SPIN_END, ::easeOut)
                .to(w + r - 8f, w + r, idleSpun, ::smooth)
            else -> tr.to(0f, 10f, Keys.IDLE, ::smooth)
        }
    }

    /** SIDESTEP-Richtung für [evaluate] (aus dem Zustand des gerade gezeichneten Arius). */
    private var stepLeft = false

    /** Wie lange eine Pose läuft, bis sie in ihrer Endhaltung steht (Ersatz, wenn kein Merkwert da ist). */
    private fun settled(pose: Int, w: Float): Float = w + max(recoverOf(pose), 14f) + 10f

    private fun inHand(pose: Int, t: Float, w: Float, sheathed: Boolean, m: Memory): Boolean = when (pose) {
        AriusPose.IAI_STANCE -> false
        AriusPose.IAI_DRAW -> t >= w * 0.4f
        AriusPose.KNEEL -> true
        AriusPose.RISE -> if (m.drawOnRise) t >= w * 0.62f else true
        else -> !sheathed
    }

    override fun setAngles(state: AriusRenderState) {
        super.setAngles(state)
        val pose = state.bossPose
        val t = state.poseTime
        val age = state.age
        val w = windupOf(pose, state.windup)
        val m = memoryOf(state.entityId)
        stepLeft = state.sideSign < 0

        // Weiche Umschaltungen (Scheide, offen), zeitbasiert: mehrfaches Zeichnen im selben Bild ändert nichts.
        val now = age
        val dt = if (m.time.isNaN()) 100f else MathHelper.clamp(now - m.time, 0f, 5f)
        m.time = now
        val k = 1f - exp(-dt * 0.3f)
        m.sheath += ((if (state.sheathed) 1f else 0f) - m.sheath) * k
        m.open += ((if (state.open) 1f else 0f) - m.open) * k

        // Posenwechsel: Startwert ist die zuletzt gezeigte Haltung.
        if (m.changedAge != state.poseChangedAge) {
            val last = m.last
            if (last != null && m.changedAge != Int.MIN_VALUE) {
                m.snapshot = last.copyOf().also { v ->
                    // Drehschnitt: Beckendrehung auf (-π, π] zurückholen, sonst dreht er sich zurück.
                    val i = Keys.HIPS * 6 + 1
                    v[i] = MathHelper.wrapDegrees(v[i] * MathHelper.DEGREES_PER_RADIAN) * MathHelper.RADIANS_PER_DEGREE
                }
                m.snapshotHidden = m.lastHidden
            } else {
                m.snapshot = null
            }
            m.drawOnRise = pose == AriusPose.RISE && m.lastSheathed
            m.changedAge = state.poseChangedAge
        }
        m.lastSheathed = state.sheathed
        val from: FloatArray
        val fromHidden: IntArray
        val snap = m.snapshot
        if (snap != null) {
            from = snap
            fromHidden = m.snapshotHidden
        } else {
            val prevW = windupOf(state.previousPose, 0)
            val prev = evaluate(state.previousPose, settled(state.previousPose, prevW), prevW, Keys.IDLE.v, Keys.IDLE.hidden, null)
            from = prev.get()
            fromHidden = prev.hid
        }
        val track = evaluate(pose, t, w, from, fromHidden, m)
        var v = track.get()
        // IDLE/SEATED: zwischen Haltung mit und ohne Schwert in der Hand weich überblenden.
        if (pose == AriusPose.IDLE && t > 10f) v = mix(Keys.IDLE.v, Keys.IDLE_EMPTY.v, m.sheath)
        if (pose == AriusPose.SEATED && t > 14f) v = mix(Keys.SEATED.v, Keys.SEATED_EMPTY.v, m.sheath)
        m.last = v
        m.lastHidden = track.hid

        for (b in 0 until Keys.COUNT) {
            val p = parts[b]
            val o = b * 6
            p.pitch = v[o]
            p.yaw = v[o + 1]
            p.roll = v[o + 2]
            p.originX += v[o + 3]
            p.originY += v[o + 4]
            p.originZ += v[o + 5]
            p.visible = true
        }
        for (h in track.hid) parts[h].visible = false

        // Formen: Mantel, Rüstung, Gewand.
        for (p in formAll) p.visible = true
        for (p in formParts[MathHelper.clamp(state.form, 0, 2)]) p.visible = false
        val hand = inHand(pose, t, w, state.sheathed, m)
        solvane.visible = hand
        sheathHilt.visible = !hand

        // Bewegung aus der Zeitleiste selbst (für Nachschwingen): Differenz zur Haltung 2 Ticks früher.
        val before = evaluate(pose, max(0f, t - 2f), w, from, fromHidden, m).get()
        fun vel(bone: Int, ch: Int) = (v[bone * 6 + ch] - before[bone * 6 + ch]) * 0.5f
        val spin = vel(Keys.HIPS, 1) + vel(Keys.TORSO, 1) + vel(Keys.CHEST, 1)
        val bend = vel(Keys.HIPS, 0) + vel(Keys.TORSO, 0)
        val lift = vel(Keys.HIPS, 4)
        secondary(state, age, spin, bend, lift, m)
    }

    private fun secondary(state: AriusRenderState, age: Float, spin: Float, bend: Float, lift: Float, m: Memory) {
        val pose = state.bossPose
        val hips = part(Keys.HIPS)
        val torso = part(Keys.TORSO)
        val chest = part(Keys.CHEST)
        val head = part(Keys.HEAD)
        val armR = part(Keys.ARM_R)
        val armL = part(Keys.ARM_L)
        val open = m.open

        // Atmen: Brust hebt sich, Schultern gehen mit; offen deutlich schwerer.
        val heavy = if (pose == AriusPose.STAGGER) 1f else open
        val breath = sin(age * (0.075f + 0.05f * heavy))
        chest.pitch -= breath * (0.014f + 0.03f * heavy)
        armR.roll += breath * 0.012f * (1f + heavy)
        armL.roll -= breath * 0.012f * (1f + heavy)
        head.pitch += breath * (0.008f + 0.02f * heavy)
        hips.originY += breath * 0.12f

        // Offen: Haltung lockert sich sichtbar (Schultern sinken, Kopf etwas tiefer).
        if (open > 0.01f) {
            chest.pitch += 0.1f * open
            head.pitch += 0.1f * open
            armR.roll -= 0.05f * open
            armL.roll += 0.12f * open
            armL.pitch += 0.12f * open
            hips.originY += 0.6f * open
        }

        // Gehen: echter Gang mit Gewichtsverlagerung, Beckendrehung, Gegenrotation der Schultern, Armpendel.
        val gait = when (pose) {
            AriusPose.IDLE, AriusPose.ARROW_CUT -> 1f
            AriusPose.GUARD, AriusPose.GRIP_CAST, AriusPose.IAI_STANCE, AriusPose.CUT_CHARGE -> 0.6f
            AriusPose.STAGGER, AriusPose.WHISTLE -> 0.5f
            AriusPose.LUNGE, AriusPose.SEATED, AriusPose.KNEEL, AriusPose.AIR_KICK, AriusPose.BREAKTHROUGH, AriusPose.SPIN_CUT -> 0f
            else -> 0.35f
        }
        val w = state.walk * gait
        var thighR = 0f
        var thighL = 0f
        if (w > 0.01f) {
            val ph = state.walkPhase
            val stride = sin(ph)
            val swing = cos(ph)
            thighR = -stride * 0.5f * w
            thighL = stride * 0.5f * w
            val kneeR = max(0f, swing) * 0.85f * w + 0.08f * w
            val kneeL = max(0f, -swing) * 0.85f * w + 0.08f * w
            part(Keys.THIGH_R).pitch += thighR - kneeR * 0.3f
            part(Keys.THIGH_L).pitch += thighL - kneeL * 0.3f
            part(Keys.SHIN_R).pitch += kneeR
            part(Keys.SHIN_L).pitch += kneeL
            part(Keys.FOOT_R).pitch -= (thighR - kneeR * 0.3f + kneeR) * 0.75f
            part(Keys.FOOT_L).pitch -= (thighL - kneeL * 0.3f + kneeL) * 0.75f
            hips.originY -= (1f - abs(stride)) * 0.9f * w - 0.5f * w
            hips.originX += swing * 0.55f * w
            hips.yaw -= stride * 0.12f * w
            hips.roll -= swing * 0.035f * w
            torso.yaw += stride * 0.06f * w
            chest.yaw += stride * 0.1f * w
            chest.roll += swing * 0.025f * w
            head.yaw += stride * 0.05f * w
            armL.pitch -= stride * 0.32f * w
            armR.pitch += stride * 0.12f * w
            part(Keys.FORE_L).pitch -= (0.15f + max(0f, -stride) * 0.2f) * w
            chest.pitch += 0.05f * w
        }

        // Kopfblick: ein Teil der Kopfdrehung zum Blickziel (nur in ruhigen Haltungen).
        val look = when (pose) {
            AriusPose.IDLE, AriusPose.GUARD, AriusPose.IAI_STANCE, AriusPose.CUT_CHARGE, AriusPose.STAGGER, AriusPose.GRIP_CAST -> 0.5f
            AriusPose.SEATED -> 0.3f
            else -> 0f
        }
        if (look > 0f) {
            head.yaw += MathHelper.clamp(state.relativeHeadYaw * MathHelper.RADIANS_PER_DEGREE * look, -0.6f, 0.6f)
            head.pitch += MathHelper.clamp(state.pitch * MathHelper.RADIANS_PER_DEGREE * look, -0.4f, 0.4f)
        }

        // Spannung im gehaltenen Ausholen: kaum sichtbares Zittern.
        val t = state.poseTime
        val w0 = windupOf(pose, state.windup)
        val holding = when (pose) {
            AriusPose.CUT_CHARGE, AriusPose.IAI_STANCE, AriusPose.CORE_RAISE -> t > 10f
            AriusPose.STRIP_SLASH, AriusPose.SPLIT_FIELD, AriusPose.STAB_GROUND, AriusPose.NULL_CUT, AriusPose.LUNGE -> t > 8f && t < w0 - 3f
            else -> false
        }
        if (holding) {
            val shake = sin(age * 2.3f) * 0.01f
            armR.roll += shake
            armL.roll -= shake
            chest.pitch += sin(age * 1.7f) * 0.005f
        }
        if (pose == AriusPose.CUT_CHARGE) head.yaw += sin(age * 0.05f) * 0.02f

        // Nachschwingen: Mantelschöße und Gewandrock folgen Becken und Beinen träge; Drehung bläht sie auf.
        val flare = MathHelper.clamp(abs(spin) * 1.6f, 0f, 0.9f)
        val lag = MathHelper.clamp(-spin * 1.2f, -0.5f, 0.5f)
        val drift = MathHelper.clamp(bend * 1.5f + lift * 0.08f, -0.5f, 0.5f)
        val wind = 0.025f + 0.02f * state.walk
        val clock = age
        for ((i, bone) in Keys.SKIRT.withIndex()) {
            val back = i >= 2
            val right = i % 2 == 0
            val p = part(bone)
            val p1 = part(bone + 1)
            val legAdd = if (right) thighR else thighL
            p.pitch += (if (back) max(0f, legAdd) * 0.9f + 0.12f * state.walk else min(0f, legAdd) * 0.9f) + drift
            p.roll += (if (right) flare else -flare) + lag * 0.5f
            p.pitch += sin(clock * 0.11f + i * 1.3f) * wind
            p1.pitch += sin(clock * 0.11f + i * 1.3f - 0.9f) * wind * 1.6f + drift * 0.6f + (if (back) 0.1f * state.walk else 0f)
            p1.roll += (if (right) flare else -flare) * 0.5f + lag * 0.4f
        }
        val tail = part(Keys.SASH_TAIL)
        tail.pitch += sin(clock * 0.13f) * 0.05f + drift * 0.8f
        tail.roll += lag + sin(clock * 0.09f + 1f) * 0.04f

        // Haar: Nacken-Strähnen und die kleine Antenne federn nach.
        val hair = part(Keys.HAIR_BACK)
        hair.pitch += sin(clock * 0.1f) * 0.03f + drift * 0.7f + 0.1f * state.walk * sin(state.walkPhase * 2f)
        hair.roll += lag * 0.5f
        val ant = part(Keys.ANTENNA)
        ant.pitch += sin(clock * 0.21f) * 0.06f + drift * 0.6f + 0.12f * state.walk * sin(state.walkPhase * 2f + 0.5f)
        ant.roll += sin(clock * 0.17f + 0.7f) * 0.05f + lag * 0.6f
    }

    // <keys> erzeugt von tools/assets/arius_model.py --kotlin; nicht von Hand ändern
    object Keys {
        /** Knochenpfade in Kanalreihenfolge (je 6 Werte: pitch, yaw, roll, dx, dy, dz). */
        val BONES = arrayOf("hips", "hips/torso", "hips/torso/chest", "hips/torso/chest/head", "hips/torso/chest/head/hair_back", "hips/torso/chest/head/antenna", "hips/torso/chest/arm_r", "hips/torso/chest/arm_r/fore_r", "hips/torso/chest/arm_r/fore_r/hand_r", "hips/torso/chest/arm_l", "hips/torso/chest/arm_l/fore_l", "hips/torso/chest/arm_l/fore_l/hand_l", "hips/torso/chest/arm_l/fore_l/hand_l/fist_l", "hips/torso/chest/arm_l/fore_l/hand_l/open_l", "hips/torso/chest/arm_l/fore_l/hand_l/point_l", "hips/thigh_r", "hips/thigh_r/shin_r", "hips/thigh_r/shin_r/foot_r", "hips/thigh_l", "hips/thigh_l/shin_l", "hips/thigh_l/shin_l/foot_l", "hips/coat_fr", "hips/coat_fr/coat_fr_1", "hips/coat_fl", "hips/coat_fl/coat_fl_1", "hips/coat_br", "hips/coat_br/coat_br_1", "hips/coat_bl", "hips/coat_bl/coat_bl_1", "hips/robe_sash/sash_tail")
        const val HIPS = 0
        const val TORSO = 1
        const val CHEST = 2
        const val HEAD = 3
        const val HAIR_BACK = 4
        const val ANTENNA = 5
        const val ARM_R = 6
        const val FORE_R = 7
        const val HAND_R = 8
        const val ARM_L = 9
        const val FORE_L = 10
        const val HAND_L = 11
        const val FIST_L = 12
        const val OPEN_L = 13
        const val POINT_L = 14
        const val THIGH_R = 15
        const val SHIN_R = 16
        const val FOOT_R = 17
        const val THIGH_L = 18
        const val SHIN_L = 19
        const val FOOT_L = 20
        const val COAT_FR = 21
        const val COAT_FR_1 = 22
        const val COAT_FL = 23
        const val COAT_FL_1 = 24
        const val COAT_BR = 25
        const val COAT_BR_1 = 26
        const val COAT_BL = 27
        const val COAT_BL_1 = 28
        const val SASH_TAIL = 29
        const val COUNT = 30
        /** Mantelschöße (oberes Glied; das untere folgt im Index): vorn rechts, vorn links, hinten rechts, hinten links. */
        val SKIRT = intArrayOf(COAT_FR, COAT_FL, COAT_BR, COAT_BL)
        /** In Form DARK ausgeblendet (samt Kindern). */
        val HIDE_DARK = arrayOf("hips/torso/chest/robe_collar", "hips/robe_sash")
        /** In Form SERIOUS ausgeblendet (samt Kindern). */
        val HIDE_SERIOUS = arrayOf("hips/torso/chest/robe_collar", "hips/robe_sash", "hips/torso/chest/coat_body", "hips/torso/coat_waist", "hips/torso/chest/coat_collar", "hips/torso/chest/arm_r/coat_sleeve_r", "hips/torso/chest/arm_l/coat_sleeve_l", "hips/torso/chest/arm_r/fore_r/coat_cuff_r", "hips/torso/chest/arm_l/fore_l/coat_cuff_l", "hips/coat_fr", "hips/coat_fl", "hips/coat_br", "hips/coat_bl")
        /** In Form WHITE ausgeblendet (samt Kindern). */
        val HIDE_WHITE = arrayOf("hips/torso/chest/armor_chest", "hips/torso/armor_belly", "hips/torso/chest/arm_r/armor_shoulder_r", "hips/torso/chest/arm_l/armor_shoulder_l", "hips/torso/chest/arm_r/fore_r/armor_bracer_r", "hips/torso/chest/arm_l/fore_l/armor_bracer_l", "hips/thigh_r/armor_thigh_r", "hips/thigh_l/armor_thigh_l", "hips/thigh_r/shin_r/armor_knee_r", "hips/thigh_l/shin_l/armor_knee_l", "hips/thigh_r/shin_r/armor_greave_r", "hips/thigh_l/shin_l/armor_greave_l", "hips/thigh_r/shin_r/foot_r/armor_foot_r", "hips/thigh_l/shin_l/foot_l/armor_foot_l", "hips/coat_fr/coat_fr_1", "hips/coat_fl/coat_fl_1", "hips/coat_br/coat_br_1", "hips/coat_bl/coat_bl_1", "hips/torso/chest/coat_body", "hips/torso/coat_waist", "hips/torso/chest/coat_collar", "hips/torso/chest/arm_r/coat_sleeve_r", "hips/torso/chest/arm_l/coat_sleeve_l", "hips/torso/chest/arm_r/fore_r/coat_cuff_r", "hips/torso/chest/arm_l/fore_l/coat_cuff_l")
        const val SOLVANE = "hips/torso/chest/arm_r/fore_r/hand_r/solvane"
        const val SHEATH_HILT = "hips/torso/chest/scabbard/sheath_hilt"
        /** Schlüsselposen: je Knochen 6 Werte; die Liste nennt Knochen, die in der Pose ausgeblendet sind. */
        val IDLE = key("0.04,0.22,0,0,1,0,0.03,-0.08,0,0,0,0,0.03,-0.08,0.02,0,0,0,0.02,-0.08,0,0,0,0,-0.0851,0,-0.0018,0,0,0,0,0,0,0,0,0,-0.2095,-0.29,0.1405,0,0,0,-0.1171,0,0,0,0,0,0.6955,0.4513,0.0573,0,0,0,0.1,0,-0.14,0,0,0,-0.32,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2492,0.1816,0.0852,0,0,0,0.514,0,0,0,0,0,-0.2877,0,-0.0949,0,0,0,-0.3847,-0.179,-0.0193,0,0,0,0.4999,0,0,0,0,0,-0.151,0,0.0264,0,0,0,-0.3692,0,0.1024,0,0,0,0.2796,0,-0.0756,0,0,0,-0.5047,0,-0.0464,0,0,0,0.395,0,0.0363,0,0,0,-0.04,0,0.1024,0,0,0,-0.0002,0,-0.0716,0,0,0,-0.04,0,-0.0464,0,0,0,0,0,0.0325,0,0,0,-0.0406,0,0.1745,0,0,0", intArrayOf(13, 14))
        val IDLE_EMPTY = key("0.04,0.22,0,0,1,0,0.03,-0.08,0,0,0,0,0.03,-0.08,0.02,0,0,0,0.02,-0.08,0,0,0,0,-0.0851,0,-0.0018,0,0,0,0,0,0,0,0,0,0.06,0,0.12,0,0,0,-0.28,0,0,0,0,0,0,0.2,0,0,0,0,0.1,0,-0.14,0,0,0,-0.32,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2492,0.1816,0.0852,0,0,0,0.514,0,0,0,0,0,-0.2877,0,-0.0949,0,0,0,-0.3847,-0.179,-0.0193,0,0,0,0.4999,0,0,0,0,0,-0.151,0,0.0264,0,0,0,-0.3692,0,0.1024,0,0,0,0.2796,0,-0.0756,0,0,0,-0.5047,0,-0.0464,0,0,0,0.395,0,0.0363,0,0,0,-0.04,0,0.1024,0,0,0,-0.0002,0,-0.0716,0,0,0,-0.04,0,-0.0464,0,0,0,0,0,0.0325,0,0,0,-0.0406,0,0.1745,0,0,0", intArrayOf(13, 14))
        val SEATED = key("-0.05,0,0,0,12,0,0.12,0,0,0,0,0,0.14,0.05,0,0,0,0,0.28,-0.05,0,0,0,0,-0.3429,0,0.0047,0,0,0,0,0,0,0,0,0,0.0329,-0.5158,0.4579,0,0,0,-1.758,0,0,0,0,0,0.8313,-0.9468,-0.2852,0,0,0,-0.0182,-0.0526,-0.18,0,0,0,-1.727,0,0,0,0,0,1.5419,-0.6478,-0.0175,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3807,0.1772,-0.0172,0,0,0,1.4041,0,0,0,0,0,0.0228,0,0.0258,0,0,0,-1.3812,-0.1765,0.0309,0,0,0,1.4831,0,0,0,0,0,-0.058,0,-0.0392,0,0,0,-1.5007,0,0.0153,0,0,0,1.3181,0,-0.4553,0,0,0,-1.5012,0,-0.0037,0,0,0,1.3185,0,0.1316,0,0,0,0.05,0,0.0153,0,0,0,0,0,-0.0107,0,0,0,0.05,0,-0.0037,0,0,0,0,0,0.0026,0,0,0,0.0508,0,0.1745,0,0,0", intArrayOf(12, 14))
        val SEATED_EMPTY = key("-0.05,0,0,0,12,0,0.12,0,0,0,0,0,0.14,0.05,0,0,0,0,0.28,-0.05,0,0,0,0,-0.3429,0,0.0047,0,0,0,0,0,0,0,0,0,-0.117,-0.36,0.0819,0,0,0,-1.5478,0,0,0,0,0,0.2597,-0.1507,0.3481,0,0,0,-0.1288,0.0051,0.182,0,0,0,-1.6056,0,0,0,0,0,1.5179,0.1815,-0.0259,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3807,0.1772,-0.0172,0,0,0,1.4041,0,0,0,0,0,0.0228,0,0.0258,0,0,0,-1.3812,-0.1765,0.0309,0,0,0,1.4831,0,0,0,0,0,-0.058,0,-0.0392,0,0,0,-1.5007,0,0.0153,0,0,0,1.3181,0,-0.4553,0,0,0,-1.5012,0,-0.0037,0,0,0,1.3185,0,0.1316,0,0,0,0.05,0,0.0153,0,0,0,0,0,-0.0107,0,0,0,0.05,0,-0.0037,0,0,0,0,0,0.0026,0,0,0,0.0508,0,0.1745,0,0,0", intArrayOf(12, 14))
        val RISE_LEAN = key("0.35,0.05,0,0,8,-3,0.3,0,0,0,0,0,0.25,0.05,0,0,0,0,-0.35,0,0,0,0,0,-0.35,0,-0.0213,0,0,0,0,0,0,0,0,0,-0.0108,0.0438,-0.1134,0,0,0,-1.7099,0,0,0,0,0,1.5241,0.0901,0.0296,0,0,0,0.1091,0.1904,-0.0472,0,0,0,-1.692,0,0,0,0,0,0.6875,-0.0953,-0.2714,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.4063,0.176,-0.0405,0,0,0,1.4297,0,0,0,0,0,-0.3748,0,-0.0243,0,0,0,-1.3757,-0.1774,0.0132,0,0,0,1.4619,0,0,0,0,0,-0.4328,0,0.0533,0,0,0,-1.5263,0,0,0,0,0,0.9999,0,0,0,0,0,-1.4957,0,-0.0188,0,0,0,0.9738,0,0.0299,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,-0.35,0,-0.0188,0,0,0,0,0,0.0124,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(12, 14))
        val RISE_LEAN_EMPTY = key("0.35,0.05,0,0,8,-3,0.3,0,0,0,0,0,0.25,0.05,0,0,0,0,-0.35,0,0,0,0,0,-0.35,0,-0.0213,0,0,0,0,0,0,0,0,0,0.0106,-0.3727,0.0498,0,0,0,-1.6296,0,0,0,0,0,0.5164,-0.1676,0.3655,0,0,0,0.1091,0.1904,-0.0472,0,0,0,-1.692,0,0,0,0,0,0.6875,-0.0953,-0.2714,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.4063,0.176,-0.0405,0,0,0,1.4297,0,0,0,0,0,-0.3748,0,-0.0243,0,0,0,-1.3757,-0.1774,0.0132,0,0,0,1.4619,0,0,0,0,0,-0.4328,0,0.0533,0,0,0,-1.5263,0,0,0,0,0,0.9999,0,0,0,0,0,-1.4957,0,-0.0188,0,0,0,0.9738,0,0.0299,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,-0.35,0,-0.0188,0,0,0,0,0,0.0124,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(12, 14))
        val RISE_REACH = key("0.1,0.25,0,0,2,-1,0.06,-0.1,0,0,0,0,0.05,-0.15,0.08,0,0,0,0,-0.15,0,0,0,0,-0.1557,0,-0.0071,0,0,0,0,0,0,0,0,0,2.0492,0.3422,-2.6566,0,0,0,-2.4324,0,0,0,0,0,-0.3102,1.0402,0.2962,0,0,0,0.2,0,-0.3,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3818,0.1819,0.0946,0,0,0,0.7368,0,0,0,0,0,-0.4339,0,-0.1221,0,0,0,-0.5316,-0.1786,-0.0107,0,0,0,0.7613,0,0,0,0,0,-0.3261,0,0.0298,0,0,0,-0.5018,0,0.1104,0,0,0,0.341,0,-0.0835,0,0,0,-0.6516,0,-0.0391,0,0,0,0.4688,0,0.032,0,0,0,-0.1,0,0.1104,0,0,0,-0.0005,0,-0.0769,0,0,0,-0.1,0,-0.0391,0,0,0,-0.0001,0,0.0273,0,0,0,-0.1015,0,0.1745,0,0,0", intArrayOf(13, 14))
        val RISE_DRAW = key("0.05,0.3,0,0,1.5,-0.5,-0.02,-0.05,0,0,0,0,-0.05,-0.1,0.06,0,0,0,-0.05,-0.2,0,0,0,0,0.0373,0,-0.0336,0,0,0,0,0,0,0,0,0,-2.3801,-0.4876,-0.1703,0,0,0,-1.382,0,0,0,0,0,1.4659,-0.0112,-0.003,0,0,0,0.15,0,-0.35,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3142,0.1819,0.0941,0,0,0,0.638,0,0,0,0,0,-0.3542,0,-0.1082,0,0,0,-0.437,-0.1793,-0.0256,0,0,0,0.6476,0,0,0,0,0,-0.2551,0,0.0353,0,0,0,-0.4342,0,0.1099,0,0,0,0.3263,0,-0.0828,0,0,0,-0.557,0,-0.0518,0,0,0,0.4309,0,0.0414,0,0,0,-0.05,0,0.1099,0,0,0,-0.0003,0,-0.0769,0,0,0,-0.05,0,-0.0518,0,0,0,-0.0001,0,0.0362,0,0,0,-0.0508,0,0.1745,0,0,0", intArrayOf(13, 14))
        val RISE_FLICK = key("0.08,0.32,0,0,2.5,0,0.04,-0.1,0,0,0,0,0.04,-0.12,0.03,0,0,0,0,-0.18,0,0,0,0,-0.1121,0,0.0131,0,0,0,0,0,0,0,0,0,0.2641,0.519,0.0627,0,0,0,-1.5214,0,0,0,0,0,1.7983,0.0102,0.0293,0,0,0,0.15,0,-0.25,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4475,0.1825,0.1145,0,0,0,0.8321,0,0,0,0,0,-0.4392,0,-0.1403,0,0,0,-0.5937,-0.1798,-0.0386,0,0,0,0.8277,0,0,0,0,0,-0.3055,0,0.0547,0,0,0,-0.5675,0,0.1274,0,0,0,0.4138,0,-0.1004,0,0,0,-0.7137,0,-0.0628,0,0,0,0.5385,0,0.0543,0,0,0,-0.08,0,0.1274,0,0,0,-0.0006,0,-0.0889,0,0,0,-0.08,0,-0.0628,0,0,0,-0.0001,0,0.0438,0,0,0,-0.0812,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C1_WIND = key("0.05,0.55,0,0,1.9748,1,-0.05,0.18,0,0,0,0,-0.06,0.25,-0.06,0,0,0,0,-0.85,0.05,0,0,0,0.0221,0,-0.0218,0,0,0,0,0,0,0,0,0,-0.9044,-0.9086,-0.7529,0,0,0,-2.0904,0,0,0,0,0,1.1295,-0.0652,-0.0978,0,0,0,-1.452,-0.1702,-1.0692,0,0,0,-1.9363,0,0,0,0,0,1.4597,1.5564,-0.3014,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.456,0.1825,0.1142,0,0,0,0.8339,0,0,0,0,0,-0.6534,0,-0.1319,0,0,0,-0.5841,-0.1819,-0.0947,0,0,0,0.7647,0,0,0,0,0,-0.2117,0,0.1045,0,0,0,-0.526,0,0.1271,0,0,0,0.4043,0,-0.0998,0,0,0,-0.6541,0,-0.1105,0,0,0,0.5132,0,0.0937,0,0,0,0,0,0.1271,0,0,0,-0.0428,0,-0.0889,0,0,0,0,0,-0.1105,0,0,0,-0.0428,0,0.0774,0,0,0,-0.0508,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C1_HIT = key("0.14,-0.35,0,0,2.3246,-3,0.08,-0.2,0,0,0,0,0.08,-0.18,0.05,0,0,0,-0.08,0.65,0,0,0,0,-0.119,0,-0.0998,0,0,0,0,0,0,0,0,0,0.3971,0.0638,-0.0731,0,0,0,-2.6627,0,0,0,0,0,2.2777,0.0224,0.1421,0,0,0,0.9166,0.6835,1.0928,0,0,0,-1.8912,0,0,0,0,0,0.4542,-1.2037,0.42,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2472,0.1787,0.0136,0,0,0,0.7922,0,0,0,0,0,-1.0299,0,-0.049,0,0,0,-0.7915,-0.1689,0.1502,0,0,0,0.7813,0,0,0,0,0,-0.153,0,-0.1243,0,0,0,-0.3672,0,0.0416,0,0,0,0.193,0,-0.0296,0,0,0,-0.9115,0,0,0,0,0,0.6558,0,0,0,0,0,-0.1272,0,0.0416,0,0,0,-0.011,0,-0.0288,0,0,0,-0.14,0,0,0,0,0,0,0,0,0,0,0,-0.1421,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C1_FOLLOW = key("0.18,-0.6,0,0,2.8246,-3,0.12,-0.25,0,0,0,0,0.1,-0.2,0.06,0,0,0,-0.05,0.8,0,0,0,0,-0.1831,0,-0.1517,0,0,0,0,0,0,0,0,0,0.187,-0.1913,-0.1104,0,0,0,-1.5219,0,0,0,0,0,1.9895,0.8001,0.0048,0,0,0,0.4829,0.5456,0.5901,0,0,0,-1.4706,0,0,0,0,0,0.1722,-1.4766,0.1398,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.296,0.1748,-0.0613,0,0,0,0.8491,0,0,0,0,0,-1.0907,0,0.0382,0,0,0,-0.8606,-0.1621,0.2336,0,0,0,0.8396,0,0,0,0,0,-0.1945,0,-0.201,0,0,0,-0.416,0,0.08,0,0,0,0.2001,0,-0.0566,0,0,0,-0.9806,0,-0.08,0,0,0,0.68,0,0.0789,0,0,0,-0.176,0,0.08,0,0,0,-0.0039,0,-0.0551,0,0,0,-0.18,0,-0.08,0,0,0,-0.0005,0,0.0551,0,0,0,-0.1827,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C2_WIND = key("0.1,-0.7,0,0,3,-2,0.05,-0.3,0,0,0,0,0.04,-0.3,0.05,0,0,0,0,0.95,0,0,0,0,-0.0895,0,-0.0823,0,0,0,0,0,0,0,0,0,-1.5705,0.4055,2.0295,0,0,0,-2.2657,0,0,0,0,0,2.7707,-0.3816,-0.0029,0,0,0,-0.0264,0.2423,-0.3476,0,0,0,-2.0533,0,0,0,0,0,1.7771,0.5916,0.0933,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.289,0.1755,-0.0488,0,0,0,0.8454,0,0,0,0,0,-0.6631,0,0.0386,0,0,0,-0.7229,-0.1624,0.23,0,0,0,0.7327,0,0,0,0,0,-0.1452,0,-0.2114,0,0,0,-0.409,0,0,0,0,0,0.2627,0,0,0,0,0,-0.8429,0,0,0,0,0,0.6314,0,0,0,0,0,-0.1,0,0,0,0,0,0,0,0,0,0,0,-0.1,0,0,0,0,0,0,0,0,0,0,0,-0.1015,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C2_HIT = key("0.12,0.1,0,0,2.3952,-4,0.05,0.05,0,0,0,0,0.05,0.1,-0.03,0,0,0,0,-0.15,0,0,0,0,-0.1526,0,0.0242,0,0,0,0,0,0,0,0,0,-0.6585,-0.7513,0.387,0,0,0,-1.8814,0,0,0,0,0,-2.3963,-0.8783,-1.504,0,0,0,1.7134,0.4387,0.1524,0,0,0,-1.6838,0,0,0,0,0,0.0106,-0.1617,-1.2795,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6258,0.1816,0.0869,0,0,0,0.9161,0,0,0,0,0,-0.3909,0,-0.1153,0,0,0,-0.6408,-0.179,-0.0198,0,0,0,0.9379,0,0,0,0,0,-0.7113,0,0.0444,0,0,0,-0.7458,0,0.1039,0,0,0,0.5314,0,-0.0889,0,0,0,-0.7608,0,-0.0469,0,0,0,0.5446,0,0.0406,0,0,0,-0.12,0,0.1039,0,0,0,-0.0005,0,-0.0722,0,0,0,-0.12,0,-0.0469,0,0,0,-0.0001,0,0.0326,0,0,0,-0.1218,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C2_FOLLOW = key("0.12,0.6,0,0,2.2633,-4,0.05,0.25,0,0,0,0,0.05,0.3,-0.06,0,0,0,0,-0.95,0,0,0,0,-0.1014,0,0.0996,0,0,0,0,0,0,0,0,0,-0.1241,0.049,0.5803,0,0,0,-1.8203,0,0,0,0,0,-2.3573,-0.4068,-1.4874,0,0,0,-0.9,0,0.3,0,0,0,-1.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.7146,0.1786,0.0108,0,0,0,0.8917,0,0,0,0,0,-0.2931,0,-0.0332,0,0,0,-0.5583,-0.1802,-0.048,0,0,0,0.9433,0,0,0,0,0,-0.8934,0,0.0776,0,0,0,-0.8346,0,0.1391,0,0,0,0.6065,0,-0.1273,0,0,0,-0.6783,0,-0.1708,0,0,0,0.4731,0,0.1392,0,0,0,-0.12,0,0.1391,0,0,0,-0.001,0,-0.0967,0,0,0,-0.12,0,-0.1708,0,0,0,-0.0015,0,0.1187,0,0,0,-0.1218,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C3_WIND = key("0.1,0.55,0,0,4,3,0.02,0.2,0,0,0,0,0,0.2,0,0,0,0,0.05,-0.9,0,0,0,0,-0.1072,0,0.0369,0,0,0,0,0,0,0,0,0,-1.8339,-0.3522,2.8807,0,0,0,-1.9135,0,0,0,0,0,-1.954,-0.9016,-1.2296,0,0,0,0.0792,-0.8328,-0.4466,0,0,0,-2.2466,0,0,0,0,0,1.0684,-0.1586,-0.3312,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6157,0.1828,0.1258,0,0,0,1.069,0,0,0,0,0,-0.5238,0,-0.1634,0,0,0,-0.7773,-0.1826,-0.1181,0,0,0,0.9788,0,0,0,0,0,-0.2766,0,0.1391,0,0,0,-0.7357,0,0.1369,0,0,0,0.5395,0,-0.118,0,0,0,-0.8973,0,-0.1304,0,0,0,0.677,0,0.1291,0,0,0,-0.1,0,0.1369,0,0,0,-0.0008,0,-0.0953,0,0,0,-0.1,0,-0.1304,0,0,0,-0.0007,0,0.0908,0,0,0,-0.1015,0,0.1745,0,0,0", intArrayOf(12, 14))
        val C3_HIT = key("0.3,-0.25,0,0,6.4965,-7,0.15,-0.15,0,0,0,0,0.1,-0.15,0,0,0,0,-0.45,0.4,0,0,0,0,-0.0617,0,-0.0593,0,0,0,0,0,0,0,0,0,-0.9326,0.5688,0.4898,0,0,0,-2.2249,0,0,0,0,0,2.9803,-0.1435,0.0191,0,0,0,1.1818,0.0624,-0.6787,0,0,0,-1.3226,0,0,0,0,0,0.628,-0.0858,0.1688,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.4417,0.177,-0.0226,0,0,0,0.077,0,0,0,0,0,-1.7175,0,-0.045,0,0,0,-1.3434,-0.1583,0.2749,0,0,0,1.151,0,0,0,0,0,-0.1514,0,-0.2135,0,0,0,-0.05,0,0.0108,0,0,0,-0.2125,0,-0.0075,0,0,0,-1.2134,0,0,0,0,0,0.7764,0,0,0,0,0,0.8117,0,0.0108,0,0,0,-0.945,0,-0.0163,0,0,0,-0.05,0,0,0,0,0,-0.2125,0,0,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C3_HOLD = key("0.36,-0.22,0,0,6.952,-7,0.2,-0.12,0,0,0,0,0.14,-0.12,0,0,0,0,-0.3,0.35,0,0,0,0,-0.2718,0,-0.0768,0,0,0,0,0,0,0,0,0,-0.3111,0.2924,2.0251,0,0,0,-2.4791,0,0,0,0,0,-2.9861,-0.204,0.9585,0,0,0,1.0778,0.2617,-0.3066,0,0,0,-1.2103,0,0,0,0,0,0.361,-0.2455,-0.1308,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.4078,0.1777,-0.0071,0,0,0,0.077,0,0,0,0,0,-1.739,0,-0.0835,0,0,0,-1.4824,-0.1565,0.2945,0,0,0,1.2702,0,0,0,0,0,-0.1941,0,-0.2193,0,0,0,-0.36,0,0.024,0,0,0,-0.0001,0,-0.0157,0,0,0,-1.6024,0,0,0,0,0,1.0561,0,0,0,0,0,0.5278,0,0.024,0,0,0,-0.7547,0,-0.0249,0,0,0,-0.36,0,0,0,0,0,0,0,0,0,0,0,-0.3651,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C4_WIND = key("0.25,-0.9,0,0,6,0,0.1,-0.35,0,0,0,0,0.08,-0.35,0.05,0,0,0,-0.1,1.1,0,0,0,0,-0.1509,0,-0.1629,0,0,0,0,0,0,0,0,0,0.4862,-1.0331,-0.226,0,0,0,-1.9696,0,0,0,0,0,1.7658,-1.0795,-0.015,0,0,0,0.8405,0.4081,1.5213,0,0,0,-0.3375,0,0,0,0,0,-1.2225,-0.4384,-1.7234,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6817,0.1815,0.0833,0,0,0,1.302,0,0,0,0,0,-0.8441,0,-0.1877,0,0,0,-0.9474,-0.1828,-0.1252,0,0,0,1.3152,0,0,0,0,0,-0.5849,0,0.1979,0,0,0,-0.7017,0,0.3008,0,0,0,0.3744,0,-0.224,0,0,0,-0.9674,0,-0.3364,0,0,0,0.5977,0,0.2916,0,0,0,-0.15,0,0.3008,0,0,0,-0.0946,0,-0.205,0,0,0,-0.15,0,-0.3364,0,0,0,-0.0971,0,0.2293,0,0,0,-0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C4_HIT = key("0.22,0.85,0,0,6,0,0.08,0.3,0,0,0,0,0.06,0.3,-0.08,0,0,0,-0.1,-1,0,0,0,0,-0.1061,0,0.1479,0,0,0,0,0,0,0,0,0,-1.109,-0.0908,-2.4133,0,0,0,-2.1617,0,0,0,0,0,2.565,0.8167,-0.0021,0,0,0,2.071,-0.3686,-1.5103,0,0,0,-2.1377,0,0,0,0,0,-1.7642,0.1687,0.9514,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.0828,0.1754,-0.0516,0,0,0,1.2833,0,0,0,0,0,-0.4261,0,0.0126,0,0,0,-0.5123,-0.1732,0.0872,0,0,0,1.2297,0,0,0,0,0,-0.9479,0,-0.0791,0,0,0,-1.0028,0,0.35,0,0,0,0.6537,0,-0.3213,0,0,0,-0.4323,0,-0.35,0,0,0,0.1688,0,0.2433,0,0,0,-0.02,0,0.35,0,0,0,-0.1816,0,-0.244,0,0,0,-0.02,0,-0.35,0,0,0,-0.1816,0,0.244,0,0,0,-0.2233,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C4_FOLLOW = key("0.25,1.15,0,0,7,0,0.1,0.35,0,0,0,0,0.08,0.32,-0.08,0,0,0,-0.15,-1.2,0,0,0,0,-0.0765,0,0.1941,0,0,0,0,0,0,0,0,0,-1.5173,0.1781,-2.2551,0,0,0,-2.0628,0,0,0,0,0,2.2493,0.6732,0.0061,0,0,0,2.3229,0.3959,1.5555,0,0,0,-1.6197,0,0,0,0,0,-1.2429,-0.2524,-2.3094,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.2015,0.1685,-0.1547,0,0,0,1.3757,0,0,0,0,0,-0.4454,0,0.1174,0,0,0,-0.5811,-0.1661,0.1857,0,0,0,1.3201,0,0,0,0,0,-1.004,0,-0.2551,0,0,0,-1.2215,0,0.25,0,0,0,0.8193,0,-0.286,0,0,0,-0.6011,0,-0.25,0,0,0,0.2919,0,0.1795,0,0,0,-0.15,0,0.25,0,0,0,-0.0915,0,-0.1704,0,0,0,-0.15,0,-0.25,0,0,0,-0.0915,0,0.1704,0,0,0,-0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val LUNGE_CROUCH = key("0.55,0.15,0,0,6.7475,1,0.25,0,0,0,0,0,0.15,0.05,0,0,0,0,-0.75,0,0,0,0,0,0.2604,0,-0.022,0,0,0,0,0,0,0,0,0,-2.8108,0.1298,-2.9057,0,0,0,-0.7432,0,0,0,0,0,1.6351,-0.0663,-0.0053,0,0,0,-0.2622,0.0964,-0.1332,0,0,0,-1.6145,0,0,0,0,0,0.9479,-0.1788,-0.3037,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.9895,0.182,0.0968,0,0,0,1.4834,0,0,0,0,0,-1.8005,0,-0.3313,0,0,0,-1.5834,-0.1751,0.0559,0,0,0,1.458,0,0,0,0,0,-0.4251,0,0.0485,0,0,0,-0.7595,0,0.1123,0,0,0,0.1757,0,-0.0684,0,0,0,-1.3534,0,0,0,0,0,0.6829,0,0,0,0,0,-0.2,0,0.1123,0,0,0,-0.2999,0,-0.0713,0,0,0,-0.2,0,0,0,0,0,-0.2975,0,0,0,0,0,-0.5568,0,0.1745,0,0,0", intArrayOf(12, 14))
        val LUNGE_SPRINT = key("0.75,0,0,0,1.9871,0,0.08,0,0,0,0,0,0,0.08,0,0,0,0,-0.85,-0.05,0,0,0,0,0.8142,0,-0.0133,0,0,0,0,0,0,0,0,0,2.8791,0.0012,-2.966,0,0,0,-0.397,0,0,0,0,0,1.6252,-0.177,-0.0102,0,0,0,-1.4,0,-0.1,0,0,0,-1.2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.9,0,0.05,0,0,0,1.6,0,0,0,0,0,-0.791,0,-3.1048,0,0,0,-1.25,0,-0.05,0,0,0,0.7,0,0,0,0,0,-0.2005,0,0.0373,0,0,0,0.05,0,0.1725,0,0,0,-0.3863,0,-0.1268,0,0,0,-0.57,0,-0.1725,0,0,0,0.1407,0,0.0897,0,0,0,1.82,0,0.1725,0,0,0,-1.8908,0,-2.095,0,0,0,0.05,0,-0.1725,0,0,0,-0.3863,0,0.1268,0,0,0,-0.7576,0,0.1745,0,0,0", intArrayOf(13, 14))
        val LUNGE_SPRINT_B = key("0.75,-0.08,0,0,2.1601,0,0.08,0.05,0,0,0,0,0,0.05,0,0,0,0,-0.85,0,0,0,0,0,0.8157,0,-0.0426,0,0,0,0,0,0,0,0,0,2.869,-0.0392,-3.0171,0,0,0,-0.397,0,0,0,0,0,1.6253,-0.185,-0.0119,0,0,0,-0.5,0,-0.2,0,0,0,-1.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.1,0,0.05,0,0,0,0.8,0,0,0,0,0,-0.4503,0,-0.0406,0,0,0,0.8,0,-0.05,0,0,0,1.6,0,0,0,0,0,-0.891,0,3.105,0,0,0,-0.42,0,0.1725,0,0,0,0.0132,0,-0.0934,0,0,0,0.05,0,-0.1725,0,0,0,-0.3863,0,0.1268,0,0,0,0.05,0,0.1725,0,0,0,-0.3863,0,-0.1268,0,0,0,1.72,0,-0.1725,0,0,0,-1.8058,0,2.0875,0,0,0,-0.7576,0,0.1745,0,0,0", intArrayOf(13, 14))
        val LUNGE_SLIDE = key("0.3,-0.55,0.15,0,13,0,0.3,-0.2,0.25,0,0,0,0.2,-0.1,0.15,0,0,0,-0.3,0.75,0.15,0,0,0,-0.4463,0,-0.5,0,0,0,0,0,0,0,0,0,2.9279,-1.0988,-2.739,0,0,0,-0.9648,0,0,0,0,0,1.8535,-0.0919,-0.0261,0,0,0,-0.8266,0.1096,-0.7969,0,0,0,-0.6714,0,0,0,0,0,0.5431,0.2335,0.1992,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.1601,0.1846,0.3026,0,0,0,1.864,0,0,0,0,0,-0.8698,0,-0.787,0,0,0,-1.2319,-0.1846,-0.3064,0,0,0,2.0474,0,0,0,0,0,-1.1087,0,0.5191,0,0,0,-1.5801,0,0.5872,0,0,0,0.9311,0,-0.7294,0,0,0,-1.6519,0,-0.5904,0,0,0,1.0528,0,0.6575,0,0,0,-0.6788,0,0.5872,0,0,0,0.165,0,-0.4716,0,0,0,-0.6788,0,-0.5904,0,0,0,0.2257,0,0.303,0,0,0,-0.3758,0,0.0372,0,0,0", intArrayOf(12, 14))
        val WALL_CROUCH = key("0.3,0.2,0,0,10,0,0.25,-0.05,0,0,0,0,0.2,-0.1,0,0,0,0,-0.45,0,0,0,0,0,-0.2073,0,0.042,0,0,0,0,0,0,0,0,0,3.125,0.2628,-2.8762,0,0,0,-0.4683,0,0,0,0,0,1.8627,0.4982,-0.0009,0,0,0,-0.8577,0.2411,-0.2052,0,0,0,-0.3714,0,0,0,0,0,0.4726,-0.3637,-0.1282,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.0149,0.1832,0.142,0,0,0,1.7454,0,0,0,0,0,-0.9775,0,-0.3406,0,0,0,-1.254,-0.1813,-0.078,0,0,0,1.7518,0,0,0,0,0,-0.7732,0,0.1778,0,0,0,-1.1349,0,0.4007,0,0,0,0.6892,0,-0.3694,0,0,0,-1.374,0,-0.3463,0,0,0,0.8978,0,0.4259,0,0,0,-0.3,0,0.4007,0,0,0,-0.0205,0,-0.2674,0,0,0,-0.3,0,-0.3463,0,0,0,-0.0151,0,0.2312,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(12, 14))
        val WALL_PRESS = key("0.35,0.2,0,0,11,-1,0.3,-0.05,0,0,0,0,0.25,-0.1,0,0,0,0,-0.55,0,0,0,0,0,-0.2421,0,0.0494,0,0,0,0,0,0,0,0,0,2.6302,1.1626,2.9363,0,0,0,-0.0874,0,0,0,0,0,1.0488,1.48,-0.7241,0,0,0,-1.1728,0.2289,-0.2934,0,0,0,-0.0874,0,0,0,0,0,0.3353,-0.4438,-0.074,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.0317,0.1837,0.1656,0,0,0,1.8213,0,0,0,0,0,-1.0662,0,-0.4605,0,0,0,-1.2981,-0.1813,-0.0759,0,0,0,1.8564,0,0,0,0,0,-0.881,0,0.2087,0,0,0,-1.1517,0,0.5207,0,0,0,0.6404,0,-0.439,0,0,0,-1.4181,0,-0.4445,0,0,0,0.8788,0,0.4985,0,0,0,-0.35,0,0.5207,0,0,0,-0.0411,0,-0.3408,0,0,0,-0.35,0,-0.4445,0,0,0,-0.0291,0,0.2912,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(12, 14))
        val WALL_RISE = key("0.15,0.2,0,0,4,0,0.1,-0.05,0,0,0,0,0.08,-0.08,0,0,0,0,-0.15,0,0,0,0,0,-0.1249,0,0.0166,0,0,0,0,0,0,0,0,0,-2.9114,0.5011,-2.9981,0,0,0,-0.9207,0,0,0,0,0,1.6275,0.5646,0.005,0,0,0,-0.2399,0.0174,-0.1128,0,0,0,-1.1778,0,0,0,0,0,1.0968,-0.1719,-0.0527,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.5762,0.1826,0.1173,0,0,0,1.0637,0,0,0,0,0,-0.6081,0,-0.1726,0,0,0,-0.7548,-0.1809,-0.067,0,0,0,1.0709,0,0,0,0,0,-0.4501,0,0.1024,0,0,0,-0.6962,0,0.1297,0,0,0,0.4632,0,-0.1048,0,0,0,-0.8748,0,-0.0869,0,0,0,0.6156,0,0.0802,0,0,0,-0.15,0,0.1297,0,0,0,-0.0011,0,-0.0898,0,0,0,-0.15,0,-0.0869,0,0,0,-0.0005,0,0.0602,0,0,0,-0.1523,0,0.1745,0,0,0", intArrayOf(12, 14))
        val STRIP_WIND = key("0.3,0.85,0,0,7.5631,1,0.1,0.3,0,0,0,0,0.06,0.3,-0.06,0,0,0,-0.2,-1.1,0,0,0,0,-0.0914,0,0.1822,0,0,0,0,0,0,0,0,0,-0.0859,0.7874,-0.4858,0,0,0,-1.617,0,0,0,0,0,1.407,0.7812,0.0572,0,0,0,-0.3518,1.1786,-0.0545,0,0,0,-1.0946,0,0,0,0,0,1.0765,1.5502,-0.6904,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.1593,0.1828,0.1267,0,0,0,1.5263,0,0,0,0,0,-0.6321,0,-0.2154,0,0,0,-1.0891,-0.1845,-0.2241,0,0,0,1.4945,0,0,0,0,0,-0.8416,0,0.3344,0,0,0,-1.1793,0,0.3377,0,0,0,0.7331,0,-0.3317,0,0,0,-1.1091,0,-0.4205,0,0,0,0.6651,0,0.3768,0,0,0,-0.2,0,0.3377,0,0,0,-0.0993,0,-0.2269,0,0,0,-0.2,0,-0.4205,0,0,0,-0.1077,0,0.2825,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STRIP_HIT = key("0.3,-0.25,0,0,8.8331,-4,0.15,-0.2,0,0,0,0,0.12,-0.2,0.04,0,0,0,-0.3,0.45,0,0,0,0,-0.1732,0,-0.0806,0,0,0,0,0,0,0,0,0,0.2578,-0.2867,0.1874,0,0,0,-1.7442,0,0,0,0,0,0.7346,1.1417,0.0003,0,0,0,0.387,0.327,0.6336,0,0,0,-1.3137,0,0,0,0,0,0.6499,0.6943,-0.4269,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3302,0.1818,0.0908,0,0,0,1.3723,0,0,0,0,0,-1.9878,0,-0.5194,0,0,0,-1.4828,-0.1713,0.1159,0,0,0,1.6475,0,0,0,0,0,-0.4796,0,-0.0659,0,0,0,-0.3502,0,0.3072,0,0,0,0.0309,0,-0.2053,0,0,0,-1.5028,0,-0.2,0,0,0,1.0175,0,0.3408,0,0,0,-0.1102,0,0.3072,0,0,0,-0.1731,0,-0.2092,0,0,0,-0.2,0,-0.2,0,0,0,-0.0899,0,0.1344,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STRIP_FOLLOW = key("0.3,-0.75,0,0,8.8331,-4,0.15,-0.3,0,0,0,0,0.12,-0.3,0.06,0,0,0,-0.3,0.95,0,0,0,0,-0.1087,0,-0.1944,0,0,0,0,0,0,0,0,0,-0.0801,-0.8101,-0.0312,0,0,0,-1.5968,0,0,0,0,0,1.6618,-0.7658,-0.012,0,0,0,0.4376,0.5139,0.3972,0,0,0,-1.7141,0,0,0,0,0,0.1203,-1.1507,0.1162,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3015,0.163,-0.2231,0,0,0,1.2845,0,0,0,0,0,-1.9731,0,0.5796,0,0,0,-1.4702,-0.1499,0.3583,0,0,0,1.5969,0,0,0,0,0,-0.4697,0,-0.3278,0,0,0,-0.4215,0,0.3,0,0,0,0.0921,0,-0.2015,0,0,0,-1.5902,0,-0.3,0,0,0,1.0855,0,0.5555,0,0,0,-0.1815,0,0.3,0,0,0,-0.1119,0,-0.202,0,0,0,-0.3,0,-0.3,0,0,0,-0.0112,0,0.2004,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val GUARD = key("0.08,0.3,0,0,2.1601,1.5,-0.04,-0.12,0,0,0,0,-0.03,-0.1,0,0,0,0,0.05,-0.12,0,0,0,0,-0.0396,0,0.0086,0,0,0,0,0,0,0,0,0,-0.3864,-0.4451,0.535,0,0,0,-2.092,0,0,0,0,0,0.4486,-1.5563,0.2789,0,0,0,-0.5989,-0.5436,1.0615,0,0,0,-2.0453,0,0,0,0,0,1.1259,-0.9631,-0.1307,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4422,0.1823,0.1074,0,0,0,0.8355,0,0,0,0,0,-0.4496,0,-0.1331,0,0,0,-0.642,-0.1801,-0.0459,0,0,0,0.761,0,0,0,0,0,-0.3393,0,0.0604,0,0,0,-0.5622,0,0.1213,0,0,0,0.4093,0,-0.0953,0,0,0,-0.762,0,-0.069,0,0,0,0.5795,0,0.0619,0,0,0,-0.08,0,0.1213,0,0,0,-0.0005,0,-0.0846,0,0,0,-0.08,0,-0.069,0,0,0,-0.0002,0,0.0481,0,0,0,-0.0812,0,0.1745,0,0,0", intArrayOf(12, 14))
        val PK_DEFLECT = key("0.06,0.45,0,0,2.5,1,-0.06,0.1,0,0,0,0,-0.08,0.15,-0.05,0,0,0,0,-0.6,0,0,0,0,0.0598,0,-0.0034,0,0,0,0,0,0,0,0,0,-1.293,-0.1044,0.6767,0,0,0,-1.91,0,0,0,0,0,1.5261,0.0445,-0.0024,0,0,0,0.5864,0.1151,-0.5833,0,0,0,-2.5048,0,0,0,0,0,-0.2808,-0.1333,-0.1706,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4344,0.1826,0.1206,0,0,0,0.8309,0,0,0,0,0,-0.4301,0,-0.1424,0,0,0,-0.5871,-0.1813,-0.0762,0,0,0,0.7922,0,0,0,0,0,-0.2497,0,0.0884,0,0,0,-0.5544,0,0.1325,0,0,0,0.4198,0,-0.105,0,0,0,-0.7071,0,-0.0948,0,0,0,0.5498,0,0.0828,0,0,0,-0.06,0,0.1325,0,0,0,-0.0004,0,-0.0926,0,0,0,-0.06,0,-0.0948,0,0,0,-0.0002,0,0.0662,0,0,0,-0.0609,0,0.1745,0,0,0", intArrayOf(13, 14))
        val PK_TURN = key("0,-0.95,0.12,0,0.5631,-0.5,-0.05,-0.15,0.1,0,0,0,-0.05,-0.1,0.1,0,0,0,0,1.1,-0.1,0,0,0,0.0745,0,-0.0654,0,0,0,0,0,0,0,0,0,0.8354,-0.6774,-1.2735,0,0,0,-0.0874,0,0,0,0,0,1.1891,-0.9188,-0.0095,0,0,0,0.4136,0.5009,1.1286,0,0,0,-1.3188,0,0,0,0,0,-2.3972,0.9674,-1.8162,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.5,0,0.25,0,0,0,2.1,0,0,0,0,0,-0.6609,0,-0.4078,0,0,0,-0.4321,-0.1752,0.0547,0,0,0,0.5454,0,0,0,0,0,-0.4305,0,-0.108,0,0,0,-1.62,0,0.5425,0,0,0,1.2758,0,-1.0298,0,0,0,-0.5521,0,-0.37,0,0,0,0.3825,0,0.2305,0,0,0,-0.0978,0,0.5425,0,0,0,-0.0181,0,-0.4265,0,0,0,-0.0978,0,-0.37,0,0,0,-0.0036,0,0.209,0,0,0,-0.0981,0,0.1045,0,0,0", intArrayOf(13, 14))
        val PK_KICK = key("-0.05,-1.45,0.35,0,-0.5252,-1,-0.05,-0.1,0.3,0,0,0,0,-0.05,0.18,0,0,0,0.1,1.35,-0.4,0,0,0,-0.0676,0,-0.1891,0,0,0,0,0,0,0,0,0,0.5348,-0.8719,-1.1583,0,0,0,-1.017,0,0,0,0,0,1.6214,-1.1554,-0.0491,0,0,0,0.9453,0.4838,1.2354,0,0,0,-1.2652,0,0,0,0,0,-2.6067,0.4995,-2.0397,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2,0,1.35,0,0,0,0.15,0,0,0,0,0,-0.2882,0,-1.3784,0,0,0,-0.4098,-0.1764,0.0323,0,0,0,0.1629,0,0,0,0,0,-0.309,0,-0.0196,0,0,0,-0.32,0,1.7275,0,0,0,-1.4519,0,-1.1652,0,0,0,-0.5298,0,-0.5932,0,0,0,0.257,0,0.3715,0,0,0,-0.08,0,1.7275,0,0,0,-1.6559,0,-1.2222,0,0,0,-0.2898,0,-0.5932,0,0,0,0.053,0,0.3668,0,0,0,-0.2998,0,0.1313,0,0,0", intArrayOf(13, 14))
        val GRIP_AIM = key("0.05,0.6,0,0,2,0,0,0.2,0,0,0,0,0,0.2,-0.05,0,0,0,0.05,-0.95,0,0,0,0,-0.041,0,0.0377,0,0,0,0,0,0,0,0,0,-2.1986,-1.0257,2.2974,0,0,0,-0.0874,0,0,0,0,0,0.964,1.352,-0.2596,0,0,0,-0.3344,-1.1123,-0.3937,0,0,0,-2.0738,0,0,0,0,0,1.1992,-0.2177,-0.1664,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3951,0.183,0.1346,0,0,0,0.7228,0,0,0,0,0,-0.349,0,-0.1505,0,0,0,-0.4805,-0.1822,-0.1035,0,0,0,0.7146,0,0,0,0,0,-0.2632,0,0.1147,0,0,0,-0.5151,0,0.1444,0,0,0,0.3949,0,-0.1127,0,0,0,-0.6005,0,-0.118,0,0,0,0.4676,0,0.0966,0,0,0,-0.05,0,0.1444,0,0,0,-0.0004,0,-0.101,0,0,0,-0.05,0,-0.118,0,0,0,-0.0003,0,0.0825,0,0,0,-0.0508,0,0.1745,0,0,0", intArrayOf(12, 13))
        val GRIP_SNAP = key("0.08,0.6,0,0,2.5,-0.5,0.03,0.2,0,0,0,0,0.02,0.22,-0.05,0,0,0,0.05,-0.95,0,0,0,0,-0.0841,0,0.0687,0,0,0,0,0,0,0,0,0,-2.0525,-1.0455,2.1455,0,0,0,-0.0874,0,0,0,0,0,0.9812,1.2584,-0.1934,0,0,0,-1.5733,-1.2786,0.3924,0,0,0,-2.138,0,0,0,0,0,1.7429,-0.2708,-0.0673,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4506,0.1833,0.1464,0,0,0,0.8115,0,0,0,0,0,-0.4082,0,-0.1725,0,0,0,-0.5488,-0.1818,-0.0923,0,0,0,0.8255,0,0,0,0,0,-0.3371,0,0.1113,0,0,0,-0.5706,0,0.1545,0,0,0,0.4162,0,-0.1219,0,0,0,-0.6688,0,-0.1085,0,0,0,0.5001,0,0.0908,0,0,0,-0.08,0,0.1545,0,0,0,-0.0008,0,-0.1078,0,0,0,-0.08,0,-0.1085,0,0,0,-0.0004,0,0.0757,0,0,0,-0.0812,0,0.1745,0,0,0", intArrayOf(12, 13))
        val POMMEL_RAISE = key("0.1,0.3,0,0,2.5,-3,-0.05,0.1,0,0,0,0,-0.08,0.1,0,0,0,0,-0.05,-0.4,0,0,0,0,0.0514,0,-0.0157,0,0,0,0,0,0,0,0,0,-0.7936,-1.2887,-1.0358,0,0,0,-2.0122,0,0,0,0,0,0.2281,-0.4849,-0.6088,0,0,0,0.3727,-0.4217,2.8443,0,0,0,-1.52,0,0,0,0,0,-0.2431,-1.4647,-0.2553,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3091,0.1834,0.1518,0,0,0,0.7641,0,0,0,0,0,-0.5187,0,-0.1925,0,0,0,-0.6653,-0.1796,-0.0342,0,0,0,0.7621,0,0,0,0,0,-0.1889,0,0.0523,0,0,0,-0.4291,0,0.159,0,0,0,0.2787,0,-0.1169,0,0,0,-0.7853,0,-0.0591,0,0,0,0.5824,0,0.0531,0,0,0,-0.1,0,0.159,0,0,0,-0.0011,0,-0.1107,0,0,0,-0.1,0,-0.0591,0,0,0,-0.0001,0,0.0412,0,0,0,-0.1015,0,0.1745,0,0,0", intArrayOf(13, 14))
        val POMMEL_HIT = key("0.25,0.1,0,0,3.1281,-6,0.2,0,0,0,0,0,0.15,0.05,0,0,0,0,-0.3,-0.15,0,0,0,0,-0.2079,0,0.0394,0,0,0,0,0,0,0,0,0,0.4785,-0.5051,-0.9293,0,0,0,-2.3923,0,0,0,0,0,-0.8299,-0.8653,-0.278,0,0,0,-0.2814,0.5604,0.1666,0,0,0,-2.2376,0,0,0,0,0,-0.3501,1.0001,0.233,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1851,0.1835,0.1552,0,0,0,0.7205,0,0,0,0,0,-1.2397,0,-0.2636,0,0,0,-0.9996,-0.1756,0.0472,0,0,0,0.9564,0,0,0,0,0,-0.2111,0,-0.0018,0,0,0,-0.3051,0,0.162,0,0,0,0.0441,0,-0.11,0,0,0,-1.1196,0,0,0,0,0,0.7391,0,0,0,0,0,-0.0651,0,0.162,0,0,0,-0.1599,0,-0.1117,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,-0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STAB_RAISE = key("-0.05,0.1,0,0,-0.7859,0,-0.08,0,0,0,0,0,-0.1,0,0,0,0,0,-0.2,0,0,0,0,0,0.201,0,0,0,0,0,0,0,0,0,0,0,-0.3092,0.4439,-3.0168,0,0,0,-0.5047,0,0,0,0,0,-0.7998,-0.3358,-0.5549,0,0,0,-0.4607,-0.2314,3.018,0,0,0,-0.0874,0,0,0,0,0,-0.7745,-0.9608,-0.5372,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0533,0.1812,0.0747,0,0,0,0.077,0,0,0,0,0,-0.5674,0,-0.0645,0,0,0,-0.0834,-0.179,-0.0197,0,0,0,0.077,0,0,0,0,0,-0.4409,0,0.0104,0,0,0,-0.0667,0,0.0935,0,0,0,0.0994,0,-0.0658,0,0,0,-0.2034,0,-0.0467,0,0,0,0.2155,0,0.0337,0,0,0,0.1733,0,0.0935,0,0,0,-0.1046,0,-0.0658,0,0,0,0.05,0,-0.0467,0,0,0,0,0,0.0327,0,0,0,0.0508,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STAB_HIT = key("0.15,0.1,0,0,12,-1,0.25,0,0,0,0,0,0.2,0,0,0,0,0,0.1,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,-1.4607,0.7186,2.7577,0,0,0,-1.9949,0,0,0,0,0,2.5393,0.8898,0.0051,0,0,0,0.057,-0.7208,1.9625,0,0,0,-2.5643,0,0,0,0,0,2.2007,0.0041,-0.1019,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.15,0,0.08,0,0,0,1.55,0,0,0,0,0,-1.2804,0,-2.862,0,0,0,-1.3979,-0.1787,-0.0124,0,0,0,1.866,0,0,0,0,0,-0.6131,0,0.0472,0,0,0,-0.05,0,0.348,0,0,0,-0.093,0,-0.2421,0,0,0,-1.4179,0,-0.2905,0,0,0,1.0722,0,0.5391,0,0,0,0.37,0,0.348,0,0,0,-0.45,0,-0.2753,0,0,0,-0.05,0,-0.2905,0,0,0,-0.0905,0,0.2021,0,0,0,-0.1523,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STAB_STUCK = key("0.22,0.1,0,0,12,-1,0.3,0,0,0,0,0,0.25,0,0,0,0,0,0.35,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,-1.5733,0.6699,2.6335,0,0,0,-2.0332,0,0,0,0,0,2.8318,0.8494,0.003,0,0,0,0.4549,-0.534,1.453,0,0,0,-2.6322,0,0,0,0,0,2.232,0.0056,-0.1802,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.15,0,0.08,0,0,0,1.55,0,0,0,0,0,-1.2127,0,-2.9172,0,0,0,-1.4689,-0.1781,-0.0004,0,0,0,1.866,0,0,0,0,0,-0.6132,0,0.0477,0,0,0,-0.22,0,0.098,0,0,0,-0.0009,0,-0.0669,0,0,0,-1.5889,0,-0.0303,0,0,0,1.1635,0,0.1025,0,0,0,0.27,0,0.098,0,0,0,-0.4174,0,-0.0758,0,0,0,-0.22,0,-0.0303,0,0,0,-0.0001,0,0.0207,0,0,0,-0.2233,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STAB_PULL = key("0.15,0.1,0,0,4,1,0.05,0,0,0,0,0,-0.05,0,0,0,0,0,0.1,0,0,0,0,0,-0.175,0,0,0,0,0,0,0,0,0,0,0,0.1929,0.164,-1.8752,0,0,0,-2.0475,0,0,0,0,0,0.7121,1.3589,0.2414,0,0,0,0.2741,0.1835,2.1275,0,0,0,-2.1324,0,0,0,0,0,0.1188,-1.0746,0.1375,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6026,0.1814,0.0793,0,0,0,1.0814,0,0,0,0,0,-0.609,0,-0.1271,0,0,0,-0.8941,-0.1778,0.0052,0,0,0,0.9538,0,0,0,0,0,-0.2083,0,0.0219,0,0,0,-0.7226,0,0.0974,0,0,0,0.4861,0,-0.0801,0,0,0,-1.0141,0,-0.0256,0,0,0,0.7344,0,0.0273,0,0,0,-0.15,0,0.0974,0,0,0,-0.0006,0,-0.0674,0,0,0,-0.15,0,-0.0256,0,0,0,0,0,0.0177,0,0,0,-0.1523,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STEP_R = key("0.05,0.2,0.1,-1.5,2.1281,0,0,-0.05,-0.1,0,0,0,0,-0.05,-0.06,0,0,0,0,-0.1,0.15,0,0,0,-0.0245,0,-0.0492,0,0,0,0,0,0,0,0,0,-0.1245,-0.2447,0.3422,0,0,0,-0.3339,0,0,0,0,0,0.8705,0.4659,-0.0308,0,0,0,0.1,0,-0.45,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4109,0.1832,0.143,0,0,0,0.773,0,0,0,0,0,-0.3371,0,-0.2572,0,0,0,-0.5699,-0.1833,-0.1484,0,0,0,0.9728,0,0,0,0,0,-0.9225,0,0.0602,0,0,0,-0.5309,0,0.4515,0,0,0,0.4214,0,-0.4256,0,0,0,-0.6899,0,-0.4561,0,0,0,0.5593,0,0.3091,0,0,0,-0.0301,0,0.4515,0,0,0,-0.0043,0,-0.3845,0,0,0,-0.0301,0,-0.4561,0,0,0,-0.0016,0,0.2505,0,0,0,-0.03,0,0.0765,0,0,0", intArrayOf(13, 14))
        val STEP_R_LAND = key("0.07,0.22,0,0,2.5,0,0.03,-0.06,0.03,0,0,0,0.03,-0.06,0,0,0,0,0,-0.1,0,0,0,0,-0.094,0,-0.0038,0,0,0,0,0,0,0,0,0,0.0836,-0.0529,0.0788,0,0,0,-0.7752,0,0,0,0,0,1.0264,0.1553,0.0787,0,0,0,0.1,0,-0.2,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.437,0.1823,0.108,0,0,0,0.8364,0,0,0,0,0,-0.4457,0,-0.1317,0,0,0,-0.58,-0.1797,-0.0365,0,0,0,0.8321,0,0,0,0,0,-0.3141,0,0.0509,0,0,0,-0.557,0,0.1218,0,0,0,0.4135,0,-0.0961,0,0,0,-0.7,0,-0.0611,0,0,0,0.5354,0,0.0527,0,0,0,-0.07,0,0.1218,0,0,0,-0.0004,0,-0.0851,0,0,0,-0.07,0,-0.0611,0,0,0,-0.0001,0,0.0426,0,0,0,-0.0711,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STEP_L = key("0.05,0.2,-0.1,1.5,2.1281,0,0,-0.05,0.1,0,0,0,0,-0.05,0.06,0,0,0,0,-0.1,-0.15,0,0,0,-0.044,0,0.0612,0,0,0,0,0,0,0,0,0,-0.0571,-0.0931,0.0353,0,0,0,-0.5352,0,0,0,0,0,0.9913,0.2498,0.1114,0,0,0,0.1,0,-0.2,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.5131,0.1841,0.1909,0,0,0,0.9711,0,0,0,0,0,-1.0068,0,-0.1187,0,0,0,-0.4961,-0.1821,-0.1003,0,0,0,0.8044,0,0,0,0,0,-0.3343,0,0.2199,0,0,0,-0.6331,0,-0.1077,0,0,0,0.4777,0,0.169,0,0,0,-0.6161,0,0.1848,0,0,0,0.4643,0,-0.0706,0,0,0,-0.0699,0,-0.1077,0,0,0,-0.001,0,0.1438,0,0,0,-0.0699,0,0.1848,0,0,0,0.0001,0,-0.0604,0,0,0,-0.0723,0,0.2728,0,0,0", intArrayOf(13, 14))
        val STEP_L_LAND = key("0.07,0.22,0,0,2.5,0,0.03,-0.06,-0.03,0,0,0,0.03,-0.06,0,0,0,0,0,-0.1,0,0,0,0,-0.0849,0,0.0316,0,0,0,0,0,0,0,0,0,0.126,0.0563,0.0603,0,0,0,-0.8724,0,0,0,0,0,1.0785,0.0198,0.0721,0,0,0,0.1,0,-0.2,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.437,0.1823,0.108,0,0,0,0.8364,0,0,0,0,0,-0.4457,0,-0.1317,0,0,0,-0.58,-0.1797,-0.0365,0,0,0,0.8321,0,0,0,0,0,-0.3141,0,0.0509,0,0,0,-0.557,0,0.1218,0,0,0,0.4135,0,-0.0961,0,0,0,-0.7,0,-0.0611,0,0,0,0.5354,0,0.0527,0,0,0,-0.07,0,0.1218,0,0,0,-0.0004,0,-0.0851,0,0,0,-0.07,0,-0.0611,0,0,0,-0.0001,0,0.0426,0,0,0,-0.0711,0,0.1745,0,0,0", intArrayOf(13, 14))
        val ARROW_WIND = key("0.04,0.22,0,0,1,0,0.03,-0.08,0,0,0,0,0,0.25,-0.05,0,0,0,0,-0.3,0,0,0,0,-0.0471,0,0.034,0,0,0,0,0,0,0,0,0,-0.1191,0.5488,2.764,0,0,0,-2.1529,0,0,0,0,0,2.9475,-0.0674,0.0023,0,0,0,0.1,0,-0.14,0,0,0,-0.32,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2492,0.1816,0.0852,0,0,0,0.514,0,0,0,0,0,-0.2877,0,-0.0949,0,0,0,-0.3847,-0.179,-0.0193,0,0,0,0.4999,0,0,0,0,0,-0.151,0,0.0264,0,0,0,-0.3692,0,0.1024,0,0,0,0.2796,0,-0.0756,0,0,0,-0.5047,0,-0.0464,0,0,0,0.395,0,0.0363,0,0,0,-0.04,0,0.1024,0,0,0,-0.0002,0,-0.0716,0,0,0,-0.04,0,-0.0464,0,0,0,0,0,0.0325,0,0,0,-0.0406,0,0.1745,0,0,0", intArrayOf(13, 14))
        val ARROW_HIT = key("0.04,0.22,0,0,1,0,0.03,-0.08,0,0,0,0,0.05,-0.3,0.05,0,0,0,0,0.2,0,0,0,0,-0.0863,0,-0.0298,0,0,0,0,0,0,0,0,0,-1.011,-0.7705,1.0657,0,0,0,-1.4439,0,0,0,0,0,2.1572,-0.1139,-0.0202,0,0,0,0.2,0,-0.3,0,0,0,-0.32,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2492,0.1816,0.0852,0,0,0,0.514,0,0,0,0,0,-0.2877,0,-0.0949,0,0,0,-0.3847,-0.179,-0.0193,0,0,0,0.4999,0,0,0,0,0,-0.151,0,0.0264,0,0,0,-0.3692,0,0.1024,0,0,0,0.2796,0,-0.0756,0,0,0,-0.5047,0,-0.0464,0,0,0,0.395,0,0.0363,0,0,0,-0.04,0,0.1024,0,0,0,-0.0002,0,-0.0716,0,0,0,-0.04,0,-0.0464,0,0,0,0,0,0.0325,0,0,0,-0.0406,0,0.1745,0,0,0", intArrayOf(13, 14))
        val CUT_CHARGE = key("0.06,0.65,0,0,3.3952,1,-0.05,0.25,0,0,0,0,-0.06,0.3,-0.06,0,0,0,0,-1.1,0.05,0,0,0,0.0066,0,-0.0233,0,0,0,0,0,0,0,0,0,-1.3503,-0.9378,-0.6691,0,0,0,-1.846,0,0,0,0,0,1.7484,-0.4697,-0.0188,0,0,0,-1.8179,0.3592,-0.5736,0,0,0,-1.8214,0,0,0,0,0,2.1893,-0.0001,0.0072,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.5749,0.1841,0.1875,0,0,0,1.0336,0,0,0,0,0,-0.7741,0,-0.2197,0,0,0,-0.6993,-0.184,-0.181,0,0,0,0.9603,0,0,0,0,0,-0.2823,0,0.1966,0,0,0,-0.5949,0,0.3394,0,0,0,0.4516,0,-0.2716,0,0,0,-0.7193,0,-0.3338,0,0,0,0.5574,0,0.2882,0,0,0,0.04,0,0.3394,0,0,0,-0.0881,0,-0.2383,0,0,0,0.04,0,-0.3338,0,0,0,-0.088,0,0.2344,0,0,0,-0.0609,0,0.1745,0,0,0", intArrayOf(13, 14))
        val CUT_TOP = key("0,0.2,0,0,2,0,-0.08,0.05,0,0,0,0,-0.12,0.05,0,0,0,0,-0.1,-0.2,0,0,0,0,0.2078,0,-0.0223,0,0,0,0,0,0,0,0,0,-1.1716,-1.2261,-1.6666,0,0,0,-0.0874,0,0,0,0,0,-0.2383,-1.256,0.5697,0,0,0,-1.3487,-1.0793,-1.8208,0,0,0,-0.5837,0,0,0,0,0,0.2792,-1.5578,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2181,0.1833,0.1486,0,0,0,0.6984,0,0,0,0,0,-0.4478,0,-0.1623,0,0,0,-0.5149,-0.1803,-0.051,0,0,0,0.6416,0,0,0,0,0,-0.1174,0,0.0506,0,0,0,-0.3381,0,0.1563,0,0,0,0.2874,0,-0.1159,0,0,0,-0.6349,0,-0.0734,0,0,0,0.5396,0,0.0637,0,0,0,0,0,0.1563,0,0,0,0,0,-0.1094,0,0,0,0,0,-0.0734,0,0,0,0,0,0.0514,0,0,0,0,0,0.1745,0,0,0", intArrayOf(13, 14))
        val CUT_STRIKE = key("0.2,0,0,0,4.9604,-4,0.15,0,0,0,0,0,0.12,0,0,0,0,0,-0.25,0,0,0,0,0,-0.154,0,0,0,0,0,0,0,0,0,0,0,-1.434,0.5528,1.2996,0,0,0,-2.3666,0,0,0,0,0,-2.7651,-0.4992,1.3081,0,0,0,1.0448,-0.114,1.9545,0,0,0,-2.433,0,0,0,0,0,0.5921,1.5118,-0.5672,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1836,0.1836,0.163,0,0,0,0.9784,0,0,0,0,0,-1.5385,0,-0.332,0,0,0,-1.1126,-0.1757,0.0448,0,0,0,1.2167,0,0,0,0,0,-0.3088,0,-0.0089,0,0,0,-0.1036,0,0.1685,0,0,0,-0.0843,0,-0.1162,0,0,0,-1.0326,0,0,0,0,0,0.7077,0,0,0,0,0,0.1364,0,0.1685,0,0,0,-0.2883,0,-0.1225,0,0,0,0,0,0,0,0,0,-0.17,0,0,0,0,0,-0.203,0,0.1745,0,0,0", intArrayOf(13, 14))
        val CUT_LOW = key("0.35,0,0,0,8.7475,-5,0.25,0,0,0,0,0,0.2,0,0,0,0,0,-0.1,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,0.2021,-0.2671,-0.4393,0,0,0,-1.9841,0,0,0,0,0,1.6729,-0.4847,0.164,0,0,0,0.513,0.6725,0.3179,0,0,0,-1.9773,0,0,0,0,0,0.4661,-1.338,0.4509,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2398,0.184,0.185,0,0,0,1.2166,0,0,0,0,0,-2.0115,0,-0.723,0,0,0,-1.5341,-0.1737,0.0797,0,0,0,1.6417,0,0,0,0,0,-0.4659,0,-0.0161,0,0,0,-0.1098,0,0.1872,0,0,0,-0.209,0,-0.1268,0,0,0,-1.4041,0,0,0,0,0,0.896,0,0,0,0,0,0.1302,0,0.1872,0,0,0,-0.413,0,-0.1387,0,0,0,-0.1,0,0,0,0,0,-0.2125,0,0,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(13, 14))
        val GK_PARRY = key("0.05,0.4,0,0,2.5,1,-0.05,0.1,0,0,0,0,-0.08,0.12,0,0,0,0,0,-0.5,0,0,0,0,0.048,0,-0.0259,0,0,0,0,0,0,0,0,0,-0.435,-0.6774,-0.7784,0,0,0,-1.7711,0,0,0,0,0,1.2905,0.1558,0.1477,0,0,0,0.6978,0.1741,-0.48,0,0,0,-2.5072,0,0,0,0,0,-0.3921,-0.134,-0.2114,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4124,0.1827,0.1229,0,0,0,0.8302,0,0,0,0,0,-0.4409,0,-0.1436,0,0,0,-0.5859,-0.181,-0.0685,0,0,0,0.7887,0,0,0,0,0,-0.239,0,0.0785,0,0,0,-0.5324,0,0.1345,0,0,0,0.4097,0,-0.1059,0,0,0,-0.7059,0,-0.0882,0,0,0,0.5573,0,0.0777,0,0,0,-0.05,0,0.1345,0,0,0,-0.0004,0,-0.094,0,0,0,-0.05,0,-0.0882,0,0,0,-0.0002,0,0.0617,0,0,0,-0.0508,0,0.1745,0,0,0", intArrayOf(13, 14))
        val GK_KNEE = key("-0.12,0.1,0,0,0.0489,1.5,-0.1,0,0,0,0,0,-0.06,0.05,0,0,0,0,0.15,-0.1,0,0,0,0,0.0906,0,-0.0101,0,0,0,0,0,0,0,0,0,-2.8613,0.816,-2.9056,0,0,0,-0.9662,0,0,0,0,0,1.134,0.3033,0.0588,0,0,0,-0.1156,0.3053,-0.2138,0,0,0,-1.7028,0,0,0,0,0,-0.6775,0.3515,-0.226,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.85,0,0.1,0,0,0,2.2,0,0,0,0,0,0.1717,0,-0.1019,0,0,0,0.0381,-0.179,-0.0185,0,0,0,0.077,0,0,0,0,0,0.0062,0,-0.0032,0,0,0,-1.97,0,0.115,0,0,0,1.7772,0,-2.0403,0,0,0,-0.0819,0,-0.0457,0,0,0,0.1717,0,0.0324,0,0,0,0.12,0,0.115,0,0,0,0.0007,0,-0.0799,0,0,0,0.1581,0,-0.0457,0,0,0,-0.0323,0,0.0318,0,0,0,0.1218,0,0.1745,0,0,0", intArrayOf(13, 14))
        val GK_KICK = key("-0.3,-0.4,0,0,-0.3967,0.5,-0.12,-0.05,0,0,0,0,0,0,0,0,0,0,0.35,0.35,0,0,0,0,0.0361,0,0.0758,0,0,0,0,0,0,0,0,0,2.3896,-0.3678,-2.8183,0,0,0,-0.0874,0,0,0,0,0,0.971,0.1678,-0.4017,0,0,0,-0.1312,0.371,-0.0567,0,0,0,-1.7662,0,0,0,0,0,-0.3192,0.7224,-0.1099,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.5,0,0.05,0,0,0,0.1,0,0,0,0,0,1.9328,0,-2.7872,0,0,0,0.3008,-0.1816,-0.0861,0,0,0,0.077,0,0,0,0,0,-0.2672,0,0.0275,0,0,0,-1.62,0,0.2725,0,0,0,1.6412,0,-1.7463,0,0,0,0.1808,0,-0.3032,0,0,0,0.1128,0,0.2042,0,0,0,0.3,0,0.2725,0,0,0,0.0092,0,-0.182,0,0,0,0.4208,0,-0.3032,0,0,0,-0.0912,0,0.2036,0,0,0,0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STAGGER = key("0.3,0.15,0.05,0,4,0,0.3,0,0,0,0,0,0.25,0,0.05,0,0,0,0.1,-0.15,0.1,0,0,0,-0.35,0,-0.0455,0,0,0,0,0,0,0,0,0,-1.9152,-0.1889,3.0082,0,0,0,-1.0415,0,0,0,0,0,2.6217,-0.2024,-0.0087,0,0,0,-0.3932,0.2242,-0.2368,0,0,0,-0.6007,0,0,0,0,0,0.1686,-0.4272,-0.1721,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.7502,0.1793,0.0265,0,0,0,1.0575,0,0,0,0,0,-0.5778,0,-0.1497,0,0,0,-0.9568,-0.1809,-0.0668,0,0,0,1.0729,0,0,0,0,0,-0.4004,0,0.0719,0,0,0,-0.8702,0,0.0525,0,0,0,0.49,0,-0.0831,0,0,0,-1.0768,0,-0.0868,0,0,0,0.6668,0,0.0333,0,0,0,-0.2925,0,0.0525,0,0,0,-0.001,0,-0.0698,0,0,0,-0.2925,0,-0.0868,0,0,0,0.0002,0,0.0236,0,0,0,-0.2942,0,0.1229,0,0,0", intArrayOf(12, 14))
        val TR_KNEEL = key("0.35,0.15,0,0,12,0,0.3,0,0,0,0,0,0.25,0,0,0,0,0,-0.2,0,0,0,0,0,-0.65,0,0,0,0,0,0,0,0,0,0,0,0.3517,0.0741,0.254,0,0,0,-1.0381,0,0,0,0,0,1.6978,-0.804,0.0088,0,0,0,-1.0998,0.1245,-0.4838,0,0,0,-0.2082,0,0,0,0,0,0.4094,-0.6368,-0.051,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.25,0,0.1,0,0,0,1.45,0,0,0,0,0,-1.0816,0,-2.9407,0,0,0,-1.6666,-0.178,0.0015,0,0,0,1.7946,0,0,0,0,0,-0.4722,0,0.0666,0,0,0,-0.7,0,0.615,0,0,0,-0.0622,0,-0.4137,0,0,0,-2.1366,0,-0.5287,0,0,0,1.1762,0,1.308,0,0,0,0.02,0,0.615,0,0,0,-0.6742,0,-0.4335,0,0,0,-0.7,0,-0.5287,0,0,0,-0.045,0,0.3591,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(12, 14))
        val TR_RISE = key("0.15,0.1,0,0,5,0,0.1,0,0,0,0,0,0.05,0,0,0,0,0,-0.1,0,0,0,0,0,-0.14,0,0,0,0,0,0,0,0,0,0,0,0.3102,0.3435,0.2625,0,0,0,-1.1441,0,0,0,0,0,1.7348,0.2223,0.0192,0,0,0,0.5683,0.2741,-0.2713,0,0,0,-1.2287,0,0,0,0,0,0.3264,-0.4743,0.0212,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6121,0.1819,0.0961,0,0,0,1.2038,0,0,0,0,0,-0.7164,0,-0.1602,0,0,0,-0.9506,-0.1781,0.0005,0,0,0,1.1367,0,0,0,0,0,-0.3337,0,0.0275,0,0,0,-0.7321,0,0.1117,0,0,0,0.494,0,-0.0923,0,0,0,-1.0706,0,-0.0296,0,0,0,0.7825,0,0.0338,0,0,0,-0.15,0,0.1117,0,0,0,-0.0008,0,-0.0773,0,0,0,-0.15,0,-0.0296,0,0,0,-0.0001,0,0.0205,0,0,0,-0.1523,0,0.1745,0,0,0", intArrayOf(12, 14))
        val TR_OPEN = key("-0.02,0,0,0,0.5,0,-0.05,0,0,0,0,0,-0.08,0,0,0,0,0,-0.18,0,0,0,0,0,0.231,0,0,0,0,0,0,0,0,0,0,0,-0.0408,0.816,0.2593,0,0,0,-0.2537,0,0,0,0,0,1.4952,-0.6059,0.0305,0,0,0,0.2838,0.2214,-0.3809,0,0,0,-0.718,0,0,0,0,0,0.5647,-0.2004,0.0097,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1307,0.1815,0.0825,0,0,0,0.3534,0,0,0,0,0,-0.1875,0,-0.0789,0,0,0,-0.169,-0.1812,-0.0755,0,0,0,0.3534,0,0,0,0,0,-0.1507,0,0.0715,0,0,0,-0.2507,0,0.1001,0,0,0,0.2302,0,-0.0727,0,0,0,-0.289,0,-0.0942,0,0,0,0.2627,0,0.0692,0,0,0,0.02,0,0.1001,0,0,0,0.0001,0,-0.0701,0,0,0,0.02,0,-0.0942,0,0,0,0.0001,0,0.0659,0,0,0,0.0203,0,0.1745,0,0,0", intArrayOf(12, 14))
        val LAUNCH_WIND = key("0.35,0.5,0,0,9,1,0.15,0.2,0,0,0,0,0.1,0.2,-0.04,0,0,0,-0.45,-0.7,0,0,0,0,-0.0619,0,0.154,0,0,0,0,0,0,0,0,0,-1.3194,1.2247,-1.7833,0,0,0,-1.2261,0,0,0,0,0,1.395,0.6459,0.004,0,0,0,-2.3179,0.7207,-2.1244,0,0,0,-0.8823,0,0,0,0,0,1.7482,-0.1822,0.0313,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.075,0.184,0.1856,0,0,0,1.6319,0,0,0,0,0,-0.8438,0,-0.3585,0,0,0,-1.3347,-0.1833,-0.1463,0,0,0,1.5839,0,0,0,0,0,-0.5606,0,0.235,0,0,0,-1.195,0,0.1878,0,0,0,0.7134,0,-0.1824,0,0,0,-1.4547,0,-0.1544,0,0,0,0.9357,0,0.2183,0,0,0,-0.35,0,0.1878,0,0,0,-0.0049,0,-0.1234,0,0,0,-0.35,0,-0.1544,0,0,0,-0.0033,0,0.1015,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(13, 14))
        val LAUNCH_HIT = key("-0.05,-0.1,0,0,-0.8152,-2,-0.08,-0.05,0,0,0,0,-0.12,-0.05,0,0,0,0,-0.35,0.1,0,0,0,0,0.2195,0,0.0114,0,0,0,0,0,0,0,0,0,-0.9515,-0.3152,-0.2035,0,0,0,-1.7231,0,0,0,0,0,2.0158,-0.5649,0.0044,0,0,0,-0.3982,0.5659,0.5969,0,0,0,-1.811,0,0,0,0,0,1.754,-0.3445,-0.0878,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.1687,0.1813,0.0758,0,0,0,0.077,0,0,0,0,0,-0.8823,0,-0.0666,0,0,0,-0.1036,-0.1782,-0.0016,0,0,0,0.077,0,0,0,0,0,-0.424,0,-0.0073,0,0,0,0.0487,0,0.0945,0,0,0,0.0013,0,-0.066,0,0,0,-0.2236,0,-0.0314,0,0,0,0.2326,0,0.0228,0,0,0,0.2887,0,0.0945,0,0,0,-0.2027,0,-0.068,0,0,0,0.05,0,-0.0314,0,0,0,0,0,0.0219,0,0,0,0.0508,0,0.1745,0,0,0", intArrayOf(13, 14))
        val LAUNCH_FOLLOW = key("-0.1,-0.1,0,0,-9,-1,-0.1,0,0,0,0,0,-0.12,0,0,0,0,0,-0.45,0.1,0,0,0,0,0.138,0,0.0262,0,0,0,0,0,0,0,0,0,-1.4677,-0.4692,-0.1731,0,0,0,-1.2647,0,0,0,0,0,1.6809,-0.8049,0.0007,0,0,0,-0.6744,0.7071,0.8585,0,0,0,-1.4762,0,0,0,0,0,0.7403,1.5074,-0.5995,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4,0,0.05,0,0,0,1.2,0,0,0,0,0,-1.2988,0,-0.065,0,0,0,0.2,0,-0.05,0,0,0,0.9,0,0,0,0,0,-1.598,0,0.0919,0,0,0,-0.67,0,0.1725,0,0,0,0.4558,0,-0.1661,0,0,0,-0.07,0,-0.1725,0,0,0,-0.0542,0,0.1219,0,0,0,-0.05,0,0.1725,0,0,0,-0.0712,0,-0.1215,0,0,0,0.17,0,-0.1725,0,0,0,-0.2582,0,0.1204,0,0,0,0.1015,0,0.1745,0,0,0", intArrayOf(13, 14))
        val AIR_TUCK = key("0.1,0.35,0,0,-14,0,0.15,0.1,0,0,0,0,0.1,0.1,0,0,0,0,0.2,-0.4,0,0,0,0,-0.85,0,0.0702,0,0,0,0,0,0,0,0,0,0.0347,-0.0888,1.1105,0,0,0,-0.9015,0,0,0,0,0,2.3563,-0.2047,-0.0044,0,0,0,-1.2,0,-0.9,0,0,0,-1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-2.1,0,0.2,0,0,0,2.2,0,0,0,0,0,-0.798,0,-0.203,0,0,0,-1,0,-0.1,0,0,0,2.1,0,0,0,0,0,-1.788,0,0.2692,0,0,0,-2.62,0,0.5,0,0,0,1.8302,0,-1.7838,0,0,0,-1.52,0,-0.415,0,0,0,0.8992,0,0.8553,0,0,0,-0.5,0,0.5,0,0,0,0.0282,0,-0.371,0,0,0,-0.5,0,-0.415,0,0,0,0.0322,0,0.3097,0,0,0,-0.1015,0,0.1745,0,0,0", intArrayOf(13, 14))
        val AIR_KICK = key("-0.15,-0.75,0.15,0,-10,-2,-0.15,-0.2,0.1,0,0,0,-0.05,-0.1,0.05,0,0,0,0.45,0.85,-0.1,0,0,0,-0.4398,0,0.0065,0,0,0,0,0,0,0,0,0,1.7402,-0.5952,-2.1569,0,0,0,-0.6652,0,0,0,0,0,1.6691,0.0783,-0.0141,0,0,0,-1,0,-1,0,0,0,-0.9,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.35,0,0.1,0,0,0,0.1,0,0,0,0,0,0.7286,0,-0.6682,0,0,0,0.35,0,-0.05,0,0,0,1.6,0,0,0,0,0,-1.9336,0,-2.9605,0,0,0,-1.67,0,0.415,0,0,0,1.2657,0,-1.2807,0,0,0,-0.1527,0,-0.4097,0,0,0,-0.0284,0,0.2138,0,0,0,-0.1527,0,0.415,0,0,0,-0.024,0,-0.3734,0,0,0,0.27,0,-0.4097,0,0,0,-0.3876,0,0.2147,0,0,0,0.0472,0,0.0648,0,0,0", intArrayOf(13, 14))
        val AIR_LAND = key("0.35,0.15,0,0,12,0,0.25,0.05,0,0,0,0,0.2,0.05,0,0,0,0,-0.45,-0.1,0,0,0,0,-0.2443,0,0.0168,0,0,0,0,0,0,0,0,0,-2.7702,-0.0402,-2.4229,0,0,0,-1.5437,0,0,0,0,0,2.7882,0.1085,-0.0017,0,0,0,-0.8488,0.1143,-0.4279,0,0,0,-0.3807,0,0,0,0,0,0.4757,-0.6372,-0.1192,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.1128,0.184,0.1863,0,0,0,1.9182,0,0,0,0,0,-1.0699,0,-0.5085,0,0,0,-1.4451,-0.1814,-0.0793,0,0,0,1.9246,0,0,0,0,0,-0.8028,0,0.1957,0,0,0,-1.2328,0,0.5883,0,0,0,0.6964,0,-0.511,0,0,0,-1.5651,0,-0.4974,0,0,0,0.9957,0,0.6378,0,0,0,-0.35,0,0.5883,0,0,0,-0.054,0,-0.3845,0,0,0,-0.35,0,-0.4974,0,0,0,-0.0371,0,0.3256,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(12, 14))
        val NULL_WIND = key("0.3,-0.35,0,0,7.3952,1,0.15,-0.25,0,0,0,0,0.1,-0.3,0.05,0,0,0,-0.4,0.85,0,0,0,0,-0.0484,0,-0.1614,0,0,0,0,0,0,0,0,0,-1.8281,-0.671,2.5232,0,0,0,-2.0684,0,0,0,0,0,2.0619,-0.999,-0.0252,0,0,0,-1.4361,-1.1964,2.5269,0,0,0,-1.9823,0,0,0,0,0,1.1904,-0.6051,-0.117,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6373,0.1821,0.1025,0,0,0,1.4398,0,0,0,0,0,-1.3614,0,-0.3119,0,0,0,-1.408,-0.1656,0.1923,0,0,0,1.345,0,0,0,0,0,-0.2651,0,-0.1366,0,0,0,-0.7573,0,0.1171,0,0,0,0.387,0,-0.0871,0,0,0,-1.528,0,0,0,0,0,1.0438,0,0,0,0,0,-0.3,0,0.1171,0,0,0,-0.0017,0,-0.0783,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(12, 14))
        val NULL_HIT = key("0.25,0.45,0,0,6.7475,-6,0.1,0.2,0,0,0,0,0.06,0.25,-0.05,0,0,0,-0.35,-0.75,0,0,0,0,-0.0018,0,0.1197,0,0,0,0,0,0,0,0,0,-0.7958,-0.1537,-2.0434,0,0,0,-2.1036,0,0,0,0,0,3.044,0.1919,0,0,0,0,0.9032,0.4286,0.1167,0,0,0,-1.527,0,0,0,0,0,0.6309,0.1297,-0.8071,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2905,0.1819,0.447,0,0,0,1.0637,0,0,0,0,0,-1.6185,0,-0.7319,0,0,0,-1.2335,-0.1844,-0.2184,0,0,0,1.3478,0,0,0,0,0,-0.3166,0,0.2681,0,0,0,-0.1605,0,0.7099,0,0,0,-0.1396,0,-0.4831,0,0,0,-1.1035,0,-0.5156,0,0,0,0.6953,0,0.4704,0,0,0,0.0795,0,0.7099,0,0,0,-0.3436,0,-0.5075,0,0,0,0,0,-0.5156,0,0,0,-0.2427,0,0.3609,0,0,0,-0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val NULL_FOLLOW = key("0.25,0.65,0,0,7.2475,-6,0.1,0.25,0,0,0,0,0.06,0.3,-0.06,0,0,0,-0.3,-0.95,0,0,0,0,-0.0119,0,0.1483,0,0,0,0,0,0,0,0,0,-1.2093,-0.5179,-2.1929,0,0,0,-1.8639,0,0,0,0,0,2.6813,0.0179,-0.0102,0,0,0,0.8173,0.4772,0.3101,0,0,0,-1.4354,0,0,0,0,0,0.8123,0.296,-1.0543,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4287,0.1791,0.5194,0,0,0,1.1483,0,0,0,0,0,-1.5382,0,-0.7746,0,0,0,-1.1763,-0.1846,-0.3055,0,0,0,1.4175,0,0,0,0,0,-0.417,0,0.3715,0,0,0,-0.3487,0,0.8215,0,0,0,-0.0084,0,-0.5521,0,0,0,-1.0963,0,-0.6397,0,0,0,0.6699,0,0.5517,0,0,0,-0.05,0,0.8215,0,0,0,-0.2624,0,-0.569,0,0,0,-0.05,0,-0.6397,0,0,0,-0.2194,0,0.4429,0,0,0,-0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val CORE_RAISE = key("-0.03,0,0,0,0.5,0,-0.06,0,0,0,0,0,-0.1,0,0,0,0,0,-0.55,0,0,0,0,0,0.368,0,0,0,0,0,0,0,0,0,0,0,-2.8099,-0.8462,-0.1332,0,0,0,-0.0874,0,0,0,0,0,1.6454,-1.0486,-0.0059,0,0,0,0.3161,0.238,-0.2792,0,0,0,-0.6329,0,0,0,0,0,0.5056,-0.1721,-0.0648,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0897,0.1823,0.1067,0,0,0,0.3288,0,0,0,0,0,-0.1891,0,-0.1012,0,0,0,-0.1892,-0.1811,-0.0704,0,0,0,0.3372,0,0,0,0,0,-0.1055,0,0.0641,0,0,0,-0.2097,0,0.1207,0,0,0,0.2039,0,-0.0869,0,0,0,-0.3092,0,-0.0898,0,0,0,0.2884,0,0.0666,0,0,0,0.0303,0,0.1207,0,0,0,-0.0001,0,-0.0844,0,0,0,0.03,0,-0.0898,0,0,0,0.0001,0,0.0629,0,0,0,0.0305,0,0.1745,0,0,0", intArrayOf(12, 14))
        val BT_AIR = key("-0.25,0,0,0,-14,2,-0.2,0,0,0,0,0,-0.2,0,0,0,0,0,0.2,0,0,0,0,0,-0.085,0,0,0,0,0,0,0,0,0,0,0,-0.7798,-0.4912,-0.3196,0,0,0,-1.7356,0,0,0,0,0,-0.1432,-0.5788,0.0094,0,0,0,-1.0637,0.3775,-0.1044,0,0,0,-1.2602,0,0,0,0,0,-0.3512,0.3545,0.0714,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3,0,0.1,0,0,0,1.8,0,0,0,0,0,-1.835,0,-0.298,0,0,0,-0.9,0,-0.1,0,0,0,1.6,0,0,0,0,0,-1.0465,0,0.1075,0,0,0,-0.72,0,0.265,0,0,0,0.5319,0,-0.3076,0,0,0,-1.32,0,-0.265,0,0,0,1.0419,0,1.1205,0,0,0,-0.05,0,0.265,0,0,0,-0.0376,0,-0.1881,0,0,0,-0.05,0,-0.265,0,0,0,-0.0376,0,0.1881,0,0,0,0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val BT_HIT = key("0.35,0,0,0,8.9604,-2,0.25,0,0,0,0,0,0.2,0,0,0,0,0,-0.35,0,0,0,0,0,-0.015,0,0,0,0,0,0,0,0,0,0,0,-0.2422,-0.831,-0.2189,0,0,0,-2.4362,0,0,0,0,0,2.4641,-0.8361,0.1919,0,0,0,0.8122,0.7571,0.8372,0,0,0,-2.3979,0,0,0,0,0,0.4976,-1.4135,0.481,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6651,0.1834,0.1494,0,0,0,1.5866,0,0,0,0,0,-1.7899,0,-0.5679,0,0,0,-1.5316,-0.1754,0.0516,0,0,0,1.6388,0,0,0,0,0,-0.4606,0,0.0135,0,0,0,-0.4851,0,0.457,0,0,0,0.084,0,-0.3005,0,0,0,-1.3516,0,-0.3,0,0,0,0.8387,0,0.3378,0,0,0,-0.05,0,0.457,0,0,0,-0.2859,0,-0.3147,0,0,0,-0.05,0,-0.3,0,0,0,-0.2677,0,0.2065,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(13, 14))
        val BT_LOW = key("0.45,0,0,0,10.8331,-2,0.3,0,0,0,0,0,0.25,0,0,0,0,0,-0.2,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,0.1282,-0.4137,-0.4647,0,0,0,-2.1794,0,0,0,0,0,1.894,-0.5986,0.1355,0,0,0,0.5311,0.7065,0.3979,0,0,0,-2.1587,0,0,0,0,0,0.4813,-1.3998,0.467,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.7242,0.1836,0.1592,0,0,0,1.7162,0,0,0,0,0,-2.0014,0,-0.9713,0,0,0,-1.7974,-0.173,0.0904,0,0,0,1.7748,0,0,0,0,0,-0.4357,0,-0.0057,0,0,0,-0.6942,0,0.5153,0,0,0,0.1593,0,-0.3268,0,0,0,-1.7674,0,-0.35,0,0,0,1.0986,0,0.6079,0,0,0,-0.3,0,0.5153,0,0,0,-0.1758,0,-0.3279,0,0,0,-0.3,0,-0.35,0,0,0,-0.1487,0,0.2229,0,0,0,-0.456,0,0.1745,0,0,0", intArrayOf(13, 14))
        val IAI_STANCE = key("0.3,-0.2,0,0,6.3952,1,0.15,-0.1,0,0,0,0,0.1,-0.15,0,0,0,0,-0.4,0.4,0,0,0,0,-0.0958,0,-0.0685,0,0,0,0,0,0,0,0,0,2.0492,0.3421,-2.6568,0,0,0,-2.4324,0,0,0,0,0,-0.3102,1.0403,0.2965,0,0,0,-0.1278,0.4674,0.1118,0,0,0,-1.915,0,0,0,0,0,0.0022,0.235,0.0778,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6522,0.1824,0.1129,0,0,0,1.3554,0,0,0,0,0,-1.2624,0,-0.2827,0,0,0,-1.3273,-0.1704,0.1295,0,0,0,1.2598,0,0,0,0,0,-0.2501,0,-0.0738,0,0,0,-0.7722,0,0.126,0,0,0,0.3995,0,-0.0943,0,0,0,-1.4473,0,0,0,0,0,0.9752,0,0,0,0,0,-0.3,0,0.126,0,0,0,-0.0019,0,-0.0842,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(12, 14))
        val IAI_MID = key("0.25,0,0,0,6.2633,-1,0.12,0.05,0,0,0,0,0.06,0.1,0,0,0,0,-0.35,0.2,0,0,0,0,-0.0423,0,-0.0781,0,0,0,0,0,0,0,0,0,1.9935,-1.0556,1.3727,0,0,0,-0.5873,0,0,0,0,0,0.9789,-1.1417,-0.2101,0,0,0,1.2893,0.2832,-0.2597,0,0,0,-2.2498,0,0,0,0,0,0.2876,0.0394,-0.3673,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.5364,0.1839,0.1803,0,0,0,1.3032,0,0,0,0,0,-1.3504,0,-0.3807,0,0,0,-1.2533,-0.1773,0.0159,0,0,0,1.2839,0,0,0,0,0,-0.2794,0,0.0297,0,0,0,-0.6564,0,0.1832,0,0,0,0.3419,0,-0.1347,0,0,0,-1.3733,0,-0.0165,0,0,0,0.9547,0,0.0259,0,0,0,-0.25,0,0.1832,0,0,0,-0.0035,0,-0.1242,0,0,0,-0.25,0,-0.0165,0,0,0,0,0,0.0112,0,0,0,-0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val IAI_HIT = key("0.3,-0.4,0,0,7.7475,-8,0.15,-0.2,0,0,0,0,0.1,-0.2,0.05,0,0,0,-0.35,0.6,0,0,0,0,-0.1082,0,-0.1249,0,0,0,0,0,0,0,0,0,-1.4712,-0.7151,1.2335,0,0,0,-2.6951,0,0,0,0,0,-2.1725,-0.6941,-0.6283,0,0,0,1.031,0.0283,-0.7379,0,0,0,-1.6256,0,0,0,0,0,0.5877,-0.1004,0.0988,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0727,0.1762,-0.036,0,0,0,0.9993,0,0,0,0,0,-2.0277,0,-0.0536,0,0,0,-1.4845,-0.1564,0.2946,0,0,0,1.396,0,0,0,0,0,-0.2558,0,-0.2379,0,0,0,0,0,0.25,0,0,0,-0.2627,0,-0.175,0,0,0,-1.3045,0,-0.25,0,0,0,0.8462,0,0.2943,0,0,0,0.3473,0,0.25,0,0,0,-0.5578,0,-0.2085,0,0,0,0,0,-0.25,0,0,0,-0.2627,0,0.175,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val IAI_END = key("0.3,-0.75,0,0,8.7475,-9,0.15,-0.3,0,0,0,0,0.1,-0.3,0.08,0,0,0,-0.3,0.9,0,0,0,0,-0.1018,0,-0.1816,0,0,0,0,0,0,0,0,0,-0.9817,-1.0412,0.2422,0,0,0,-1.8183,0,0,0,0,0,2.7005,-0.6572,-0.0124,0,0,0,0.5323,-0.0702,-0.9055,0,0,0,-1.4409,0,0,0,0,0,1.1099,-0.0402,0.1201,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0392,0.1542,-0.3176,0,0,0,0.9203,0,0,0,0,0,-1.9558,0,0.6669,0,0,0,-1.4807,-0.138,0.4613,0,0,0,1.5012,0,0,0,0,0,-0.3776,0,-0.4217,0,0,0,-0.1,0,0.35,0,0,0,-0.1854,0,-0.2389,0,0,0,-1.4007,0,-0.35,0,0,0,0.9202,0,0.4456,0,0,0,0.2808,0,0.35,0,0,0,-0.5091,0,-0.2782,0,0,0,-0.1,0,-0.35,0,0,0,-0.1854,0,0.2389,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val KNEEL = key("0.1,0.1,0,0,12,0,0.15,0,0,0,0,0,0.12,0,0,0,0,0,0.45,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,-0.6019,0.789,-2.7601,0,0,0,-2.0101,0,0,0,0,0,1.6495,1.0527,0.0152,0,0,0,-0.7902,-0.4755,-2.2433,0,0,0,-2.2459,0,0,0,0,0,0.2636,1.5539,-0.3346,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.15,0,0.08,0,0,0,1.55,0,0,0,0,0,-1.3281,0,-2.8044,0,0,0,-1.3886,-0.1788,-0.0155,0,0,0,1.8303,0,0,0,0,0,-0.5371,0,0.0383,0,0,0,-0.1,0,0.298,0,0,0,-0.0039,0,-0.2075,0,0,0,-1.5086,0,-0.2431,0,0,0,1.1948,0,0.688,0,0,0,0.27,0,0.298,0,0,0,-0.3184,0,-0.222,0,0,0,-0.1,0,-0.2431,0,0,0,-0.0026,0,0.1693,0,0,0,-0.1015,0,0.1745,0,0,0", intArrayOf(13, 14))
        val THROW_WIND = key("0.12,0.5,0,0,4,0,0.08,0.2,0,0,0,0,0.06,0.3,-0.06,0,0,0,0.05,-0.8,0,0,0,0,-0.1752,0,0.1037,0,0,0,0,0,0,0,0,0,-1.3735,0.9866,-1.7236,0,0,0,-1.2306,0,0,0,0,0,1.586,0.258,0.0398,0,0,0,0.4395,-0.2762,0.1438,0,0,0,-1.6853,0,0,0,0,0,-0.0982,-1.0551,0.8559,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.623,0.1827,0.1243,0,0,0,1.0689,0,0,0,0,0,-0.5362,0,-0.1669,0,0,0,-0.723,-0.1818,-0.0925,0,0,0,1.0615,0,0,0,0,0,-0.4375,0,0.1237,0,0,0,-0.743,0,0.1357,0,0,0,0.5286,0,-0.1156,0,0,0,-0.843,0,-0.1086,0,0,0,0.614,0,0.1003,0,0,0,-0.12,0,0.1357,0,0,0,-0.0009,0,-0.0943,0,0,0,-0.12,0,-0.1086,0,0,0,-0.0006,0,0.0755,0,0,0,-0.1218,0,0.1745,0,0,0", intArrayOf(12, 14))
        val THROW_HIT = key("-0.05,-0.2,0,0,-0.553,-1,-0.08,-0.1,0,0,0,0,-0.1,-0.15,0.05,0,0,0,-0.5,0.2,0,0,0,0,0.3113,0,-0.0229,0,0,0,0,0,0,0,0,0,2.3257,0.8703,2.5522,0,0,0,-0.0874,0,0,0,0,0,0.4638,1.2182,-0.603,0,0,0,-0.0913,-0.437,-3.0532,0,0,0,-0.693,0,0,0,0,0,0.5155,0.0282,-0.0435,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.157,0.1816,0.0845,0,0,0,0.077,0,0,0,0,0,-0.6689,0,-0.075,0,0,0,-0.1583,-0.1773,0.0165,0,0,0,0.077,0,0,0,0,0,-0.1724,0,-0.0252,0,0,0,0.037,0,0.1018,0,0,0,0.0113,0,-0.0712,0,0,0,-0.2783,0,-0.016,0,0,0,0.2791,0,0.0118,0,0,0,0.277,0,0.1018,0,0,0,-0.1927,0,-0.0731,0,0,0,0.05,0,-0.016,0,0,0,0,0,0.0112,0,0,0,0.0508,0,0.1745,0,0,0", intArrayOf(12, 14))
        val SPLIT_RAISE = key("-0.05,0.1,0,0,-0.5043,1,-0.1,0,0,0,0,0,-0.15,0,0,0,0,0,-0.15,0,0,0,0,0,0.315,0,0,0,0,0,0,0,0,0,0,0,-1.1974,-1.126,-1.284,0,0,0,-0.6864,0,0,0,0,0,0.5841,-1.3401,-0.285,0,0,0,-1.434,-0.9091,-1.6552,0,0,0,-0.7706,0,0,0,0,0,0.0748,-1.5569,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.1085,0.1824,0.1112,0,0,0,0.077,0,0,0,0,0,-0.5153,0,-0.1008,0,0,0,-0.1946,-0.1795,-0.0296,0,0,0,0.077,0,0,0,0,0,0.172,0,0.0205,0,0,0,0.0385,0,0.1245,0,0,0,0.0101,0,-0.0871,0,0,0,-0.2646,0,-0.0552,0,0,0,0.2675,0,0.0406,0,0,0,0.2785,0,0.1245,0,0,0,-0.1939,0,-0.0894,0,0,0,0.1,0,-0.0552,0,0,0,-0.0424,0,0.0386,0,0,0,0.0508,0,0.1745,0,0,0", intArrayOf(13, 14))
        val SPLIT_HIT = key("0.2,0,0,0,3.9604,-4,0.15,0,0,0,0,0,0.12,0,0,0,0,0,-0.25,0,0,0,0,0,-0.154,0,0,0,0,0,0,0,0,0,0,0,-0.8436,-0.811,0.0494,0,0,0,-2.3783,0,0,0,0,0,2.9813,-0.8121,-0.013,0,0,0,0.829,0.1553,1.3388,0,0,0,-2.4208,0,0,0,0,0,0.9767,-1.5164,0.6572,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1343,0.183,0.1338,0,0,0,0.8361,0,0,0,0,0,-1.4604,0,-0.2554,0,0,0,-1.0378,-0.1755,0.0492,0,0,0,1.088,0,0,0,0,0,-0.2556,0,-0.0132,0,0,0,-0.0543,0,0.1437,0,0,0,-0.1256,0,-0.0997,0,0,0,-0.9578,0,0,0,0,0,0.6441,0,0,0,0,0,0.1857,0,0.1437,0,0,0,-0.3296,0,-0.1064,0,0,0,0,0,0,0,0,0,-0.17,0,0,0,0,0,-0.203,0,0.1745,0,0,0", intArrayOf(13, 14))
        val SPLIT_LOW = key("0.35,0,0,0,7.7475,-4,0.25,0,0,0,0,0,0.2,0,0,0,0,0,-0.15,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,0.1083,-0.2727,-0.4454,0,0,0,-1.9731,0,0,0,0,0,1.8168,-0.5038,0.1234,0,0,0,0.419,0.6973,0.2726,0,0,0,-2.0154,0,0,0,0,0,0.4612,-1.5088,0.4337,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3678,0.1831,0.1396,0,0,0,1.2724,0,0,0,0,0,-1.981,0,-0.5266,0,0,0,-1.4994,-0.1725,0.0982,0,0,0,1.5048,0,0,0,0,0,-0.3669,0,-0.0342,0,0,0,-0.2378,0,0.1487,0,0,0,-0.0984,0,-0.0984,0,0,0,-1.3694,0,0,0,0,0,0.8665,0,0,0,0,0,0.0022,0,0.1487,0,0,0,-0.3024,0,-0.1041,0,0,0,-0.1,0,0,0,0,0,-0.2125,0,0,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(13, 14))
        val WHISTLE = key("0.04,0.22,0,0,1,0,0.03,-0.08,0,0,0,0,-0.04,-0.1,0,0,0,0,-0.45,-0.1,0,0,0,0,0.2954,0,0.0086,0,0,0,0,0,0,0,0,0,-0.1448,-0.2784,0.138,0,0,0,-0.1472,0,0,0,0,0,0.7426,0.4464,0.078,0,0,0,-1.0801,-0.3708,-2.5504,0,0,0,-2.3556,0,0,0,0,0,2.6597,1.2206,0.018,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2492,0.1816,0.0852,0,0,0,0.514,0,0,0,0,0,-0.2877,0,-0.0949,0,0,0,-0.3847,-0.179,-0.0193,0,0,0,0.4999,0,0,0,0,0,-0.151,0,0.0264,0,0,0,-0.3692,0,0.1024,0,0,0,0.2796,0,-0.0756,0,0,0,-0.5047,0,-0.0464,0,0,0,0.395,0,0.0363,0,0,0,-0.04,0,0.1024,0,0,0,-0.0002,0,-0.0716,0,0,0,-0.04,0,-0.0464,0,0,0,0,0,0.0325,0,0,0,-0.0406,0,0.1745,0,0,0", intArrayOf(12, 13))
        val SPIN_WIND = key("0.25,-0.95,0,0,7,0,0.1,-0.35,0,0,0,0,0.08,-0.35,0.05,0,0,0,-0.15,1.15,0,0,0,0,-0.1065,0,-0.1703,0,0,0,0,0,0,0,0,0,0.4284,-1.2416,-0.2694,0,0,0,-2.0657,0,0,0,0,0,2.0323,-1.0797,-0.0146,0,0,0,0.8154,0.394,1.5643,0,0,0,-0.0874,0,0,0,0,0,-1.1539,-0.5289,-1.9934,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.7143,0.1814,0.081,0,0,0,1.4143,0,0,0,0,0,-0.9231,0,-0.2032,0,0,0,-1.0193,-0.1828,-0.1273,0,0,0,1.429,0,0,0,0,0,-0.6254,0,0.2062,0,0,0,-0.7343,0,0.2988,0,0,0,0.4022,0,-0.2259,0,0,0,-1.0393,0,-0.3382,0,0,0,0.6587,0,0.3105,0,0,0,-0.15,0,0.2988,0,0,0,-0.0944,0,-0.2037,0,0,0,-0.15,0,-0.3382,0,0,0,-0.0972,0,0.2305,0,0,0,-0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val SPIN_HIT = key("0.2,3.1416,0,0,5,0,0.05,0.1,0,0,0,0,0,0.15,0,0,0,0,-0.1,0,0,0,0,0,-0.1004,0,-0.0342,0,0,0,0,0,0,0,0,0,-2.0601,-0.489,-2.0526,0,0,0,-1.7263,0,0,0,0,0,2.8582,0.169,-0.0056,0,0,0,1.9509,0.5174,0.576,0,0,0,-1.8157,0,0,0,0,0,-0.2677,-0.0321,-1.7489,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.7294,0.1829,0.131,0,0,0,1.199,0,0,0,0,0,-0.6349,0,-0.2026,0,0,0,-0.8305,-0.1811,-0.0709,0,0,0,1.2166,0,0,0,0,0,-0.5676,0,0.1238,0,0,0,-0.8494,0,0.8414,0,0,0,0.671,0,-0.6296,0,0,0,-0.9505,0,-0.7903,0,0,0,0.7695,0,0.6239,0,0,0,-0.2,0,0.8414,0,0,0,0.119,0,-0.5751,0,0,0,-0.2,0,-0.7903,0,0,0,0.1316,0,0.5404,0,0,0,-0.203,0,0.1745,0,0,0", intArrayOf(13, 14))
        val SPIN_END = key("0.2,5.7332,0,0,5,0,0.05,0.1,0,0,0,0,0,0.15,0,0,0,0,-0.1,0,0,0,0,0,-0.1004,0,-0.0342,0,0,0,0,0,0,0,0,0,-1.9639,-0.7831,-2.3006,0,0,0,-1.6773,0,0,0,0,0,2.874,0.0776,-0.0118,0,0,0,1.9509,0.5174,0.576,0,0,0,-1.8157,0,0,0,0,0,-0.2677,-0.0321,-1.7489,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.7294,0.1829,0.131,0,0,0,1.199,0,0,0,0,0,-0.6349,0,-0.2026,0,0,0,-0.8305,-0.1811,-0.0709,0,0,0,1.2166,0,0,0,0,0,-0.5676,0,0.1238,0,0,0,-0.8494,0,0.6414,0,0,0,0.6114,0,-0.5044,0,0,0,-0.9505,0,-0.5903,0,0,0,0.7045,0,0.4968,0,0,0,-0.2,0,0.6414,0,0,0,0.0594,0,-0.439,0,0,0,-0.2,0,-0.5903,0,0,0,0.0666,0,0.4041,0,0,0,-0.203,0,0.1745,0,0,0", intArrayOf(13, 14))
    }
    // </keys>
}

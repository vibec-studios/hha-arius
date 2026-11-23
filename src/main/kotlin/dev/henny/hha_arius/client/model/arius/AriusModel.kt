package dev.henny.hha_arius.client.model.arius

import dev.henny.hha_arius.boss.AriusBlade
import dev.henny.hha_arius.boss.AriusForm
import dev.henny.hha_arius.boss.AriusPose
import dev.henny.hha_arius.boss.AriusTimings
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.util.math.MathHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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

    /** [dev.henny.hha_arius.boss.AriusBlade]: Solvane, keine Klinge oder Eisklinge. */
    val iceItem = net.minecraft.client.render.item.ItemRenderState()
    var blade = AriusBlade.HELD
    var eyeGlow = 40
    var lead = false
    var open = false

    /** Seit 1.11.0: Konterhaltung (eigene, tiefe Haltung, siehe [AriusModel]). */
    var counter = false
    var sideSign = 1
    var walk = 0f
    var walkPhase = 0f
}

/**
 * Eine Schlüsselpose: je Knochen (pitch, yaw, roll, dx, dy, dz), siehe [AriusModel.Keys]; hidden = ausgeblendete Knochen;
 * plant = Haltung, deren Fußstellung die Fuß-IK hält (abgeleitete Haltungen stehen auf den Füßen ihrer Vorlage).
 */
class AriusKey(val v: FloatArray, val hidden: IntArray, val plant: FloatArray = v)

private fun key(data: String, hidden: IntArray) = AriusKey(data.split(',').map { it.toFloat() }.toFloatArray(), hidden)

// --- Hüllkurven ----------------------------------------------------------------------------------------------------

private fun smooth(t: Float) = t * t * (3f - 2f * t)
private fun easeIn(t: Float) = t * t
private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)
private fun snap(t: Float) = t * t * t
private fun linear(t: Float) = t

/** Langsames Anspannen: sanft hinein, sanft hinaus (für das Weiter-Ausholen vor dem Schlag). */
private fun sine(t: Float) = 0.5f - 0.5f * cos(t * PI.toFloat())

private fun wrap(a: Float): Float {
    var x = a
    while (x > PI) x -= (2 * PI).toFloat()
    while (x < -PI) x += (2 * PI).toFloat()
    return x
}

// --- Knochengruppen der Kette Hüfte → Brust → Schulter → Arm → Klinge -------------------------------------------------

private const val G_ROOT = 0      // Becken, Mantelschöße, Schärpe
private const val G_LEG_R = 1
private const val G_LEG_L = 2
private const val G_TORSO = 3
private const val G_CHEST = 4
private const val G_HEAD = 5      // Kopf, Haar, Antenne
private const val G_ARM = 6
private const val G_FORE = 7
private const val G_HAND = 8      // Hände samt Fingerhaltungen
private const val GROUPS = 9

/**
 * Zeitfenster je Knochengruppe innerhalb eines Abschnitts (Anteile 0..1) und eigene Hüllkurven (null = die des
 * Abschnitts). Alle Gruppen sind am Abschnittsende am Ziel, der Treffer bleibt also genau bei t = windup.
 */
private class Chain(val start: FloatArray, val end: FloatArray, val ease: Array<((Float) -> Float)?>)

private fun chain(start: FloatArray, end: FloatArray, vararg ease: ((Float) -> Float)?) =
    Chain(start, end, if (ease.isEmpty()) arrayOfNulls(GROUPS) else arrayOf(*ease))

//                         Becken  Bein R  Bein L  Bauch   Brust   Kopf    Arm     Unterarm Hand
private val FLAT = chain(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f))

/** Ausholen: das Gewicht verlagert sich zuerst, dann heben sich die Arme. */
private val WIND = chain(floatArrayOf(0f, 0f, 0f, 0.05f, 0.1f, 0.05f, 0.14f, 0.18f, 0.22f),
    floatArrayOf(0.8f, 0.8f, 0.8f, 0.86f, 0.92f, 0.85f, 1f, 1f, 1f))

/** Schlag: Hüfte dreht zuerst und bremst, Brust, Schulter, Arm und Klinge peitschen nacheinander nach. */
private val STRIKE = chain(floatArrayOf(0f, 0f, 0f, 0.1f, 0.2f, 0.06f, 0.3f, 0.38f, 0.46f),
    floatArrayOf(0.72f, 0.72f, 0.72f, 0.8f, 0.9f, 0.75f, 1f, 1f, 1f),
    ::smooth, ::smooth, ::smooth, ::smooth, ::easeIn, ::smooth, ::easeIn, ::snap, ::snap)

/** Tritt (rechtes Bein): Becken dreht ein, die Arme gleichen früh aus, das Bein schnellt zuletzt. */
private val KICK = chain(floatArrayOf(0f, 0.3f, 0f, 0.08f, 0.12f, 0.05f, 0f, 0f, 0f),
    floatArrayOf(0.75f, 1f, 0.75f, 0.85f, 0.9f, 0.8f, 0.8f, 0.85f, 0.85f),
    ::smooth, ::snap, ::smooth, ::smooth, ::smooth, ::smooth, ::smooth, ::smooth, ::smooth)

/** Durchziehen: die Klinge führt, der Körper wird mitgerissen. */
private val FOLLOW = chain(floatArrayOf(0.12f, 0.12f, 0.12f, 0.1f, 0.06f, 0.05f, 0f, 0f, 0f),
    floatArrayOf(1f, 1f, 1f, 0.92f, 0.85f, 0.9f, 0.75f, 0.65f, 0.58f))

/** Zurück ins Gleichgewicht: erst Becken und Beine, dann Rumpf und zuletzt die Arme. */
private val SETTLE = chain(floatArrayOf(0f, 0f, 0f, 0.05f, 0.1f, 0.1f, 0.12f, 0.15f, 0.18f),
    floatArrayOf(0.8f, 0.8f, 0.8f, 0.88f, 0.94f, 1f, 1f, 1f, 1f))

// --- Feder-Dämpfer für Haar, Mantel, Schärpe und Scheide ---------------------------------------------------------------

private const val S_COAT_YAW = 0
private const val S_COAT_PITCH = 1
private const val S_COAT_LIFT = 2
private const val S_COAT_LOW = 3
private const val S_HAIR_PITCH = 4
private const val S_HAIR_YAW = 5
private const val S_HAIR_LIFT = 6
private const val S_ANT_PITCH = 7
private const val S_ANT_YAW = 8
private const val S_SCAB_PITCH = 9
private const val S_SCAB_YAW = 10
private const val S_SASH = 11
private const val S_SASH_LIFT = 12
private const val SPRINGS = 13

/** Steifigkeit (je Tick²) und Dämpfung (je Tick): schwerer Mantel träge, Haar schneller, Antenne federnd. */
private val SPRING_K = floatArrayOf(0.45f, 0.5f, 0.55f, 0.25f, 1.0f, 1.0f, 1.1f, 2.0f, 2.0f, 0.8f, 0.8f, 0.7f, 0.8f)
private val SPRING_C = floatArrayOf(0.42f, 0.45f, 0.5f, 0.3f, 0.55f, 0.55f, 0.6f, 0.45f, 0.45f, 0.9f, 0.9f, 0.45f, 0.5f)

/**
 * Arius. Geometrie, Texturen und alle Schlüsselposen stammen aus tools/assets/arius_model.py; der Block [Keys] wird dort
 * erzeugt (`python tools/assets/arius_model.py --kotlin`), damit Vorschau und Spiel dieselben Winkel benutzen. Hier
 * steht der Ablauf je [AriusPose] (Vertrag aus Poses.kt: Ausholen weich, Treffer genau bei t = windup, Nachlauf
 * [AriusTimings.ARIUS_RECOVER], dann IDLE; Haltungen werden gehalten) plus Atmen, Gehen, Kopfblick und die Formen
 * (Mantel/Rüstung/Gewand über Knochen-Sichtbarkeit).
 *
 * Körperliche Wucht (Paket V4, Nachbau für die Vorschau: tmp/visual/V4/anim.py):
 * - Angriffe laufen in Phasen: Ausholen (Gewicht zuerst), Anspannen (weiter gegen die Schlagrichtung, Einsinken),
 *   Schlag im letzten Drittel als Kette Hüfte → Brust → Schulter → Arm → Klinge ([STRIKE]), harter Treffer mit kurzem
 *   Halt und Stoß durch den Körper ([impact]), Durchziehen mit Überschwung ([FOLLOW]), Zurückfedern ([SETTLE]).
 * - Fuß-IK ([plantFeet]): Füße, die am Anfang und Ende eines Abschnitts am Boden stehen, bleiben dort (gerade Linie,
 *   bei großen Schritten mit Anheben); Einsinken und Treffer-Stoß beugen dadurch die Knie statt die Sohlen zu versenken.
 * - Sekundärbewegung über Feder-Dämpfer ([springs]): Mantel bzw. Gewand, Schärpe, Haar, Antenne und Scheide folgen
 *   Becken, Rumpf und Kopf mit Verzögerung und schwingen nach.
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
    private val solvaneTree: List<ModelPart> = solvane.traverse()
    private val sheathHilt = path(Keys.SHEATH_HILT)
    private val scabbard = path(Keys.SHEATH_HILT.substringBeforeLast('/'))

    /** IDLE mit +2π Beckendrehung: Rückweg nach dem Drehschnitt ohne Zurückdrehen. */
    private val idleSpun = AriusKey(Keys.IDLE.v.copyOf().also { it[Keys.HIPS * 6 + 1] += (2 * PI).toFloat() }, Keys.IDLE.hidden)

    /** Kugelgelenke, die per Quaternion überblendet werden. */
    private val BALL = intArrayOf(Keys.ARM_R, Keys.HAND_R, Keys.ARM_L, Keys.HAND_L)

    /** Gruppe je Knochen (siehe [Chain]). */
    private val group = IntArray(Keys.COUNT) {
        when (it) {
            Keys.TORSO -> G_TORSO
            Keys.CHEST -> G_CHEST
            Keys.HEAD, Keys.HAIR_BACK, Keys.ANTENNA -> G_HEAD
            Keys.ARM_R, Keys.ARM_L -> G_ARM
            Keys.FORE_R, Keys.FORE_L -> G_FORE
            Keys.HAND_R, Keys.HAND_L, Keys.FIST_L, Keys.OPEN_L, Keys.POINT_L -> G_HAND
            Keys.THIGH_R, Keys.SHIN_R, Keys.FOOT_R -> G_LEG_R
            Keys.THIGH_L, Keys.SHIN_L, Keys.FOOT_L -> G_LEG_L
            else -> G_ROOT
        }
    }

    // Beingeometrie (Grundlage der Knochen im Elternraum) für die Fuß-IK.
    private fun origin(b: Int) = floatArrayOf(parts[b].originX, parts[b].originY, parts[b].originZ)
    private val hipsPivot = origin(Keys.HIPS)
    private val thighPivot = arrayOf(origin(Keys.THIGH_R), origin(Keys.THIGH_L))
    private val shinPivot = arrayOf(origin(Keys.SHIN_R), origin(Keys.SHIN_L))
    private val footPivot = arrayOf(origin(Keys.FOOT_R), origin(Keys.FOOT_L))
    private val thighLen = len(shinPivot[0])
    private val shinLen = len(footPivot[0])

    // --- Angespannte Ausholhaltungen (Ausholhaltung weiter gegen die Schlagrichtung, eingesunken) -------------------------

    private val c1Load by lazy { coil(Keys.C1_WIND, Keys.C1_HIT, 0.16f, 1.4f) }
    private val c2Load by lazy { coil(Keys.C2_WIND, Keys.C2_HIT, 0.14f, 1.2f) }
    private val c3Load by lazy { coil(Keys.C3_WIND, Keys.C3_HIT, 0.15f, 1.6f) }
    private val c4Load by lazy { coil(Keys.C4_WIND, Keys.C4_HIT, 0.14f, 1.2f) }
    private val lungeLoad by lazy { coil(Keys.LUNGE_CROUCH, Keys.LUNGE_SPRINT, 0.1f, 1.5f) }
    private val wallLoad by lazy { coil(Keys.WALL_CROUCH, Keys.WALL_PRESS, 0.6f, 0.8f) }
    private val stripLoad by lazy { coil(Keys.STRIP_WIND, Keys.STRIP_HIT, 0.18f, 1.8f) }
    private fun handPose(base: AriusKey, arm: Int, pitch: Float, yaw: Float, roll: Float, elbow: Float, lean: Float): AriusKey {
        val v = base.v.copyOf()
        val at = arm * 6
        v[at] = pitch
        v[at + 1] = yaw
        v[at + 2] = roll
        v[at + 6] = elbow
        v[Keys.HIPS * 6] = lean
        return AriusKey(v, intArrayOf())
    }

    private val maceCatch by lazy { handPose(Keys.IDLE, Keys.ARM_L, -2.45f, -0.12f, -0.24f, -0.45f, -0.10f) }
    private val maceSlam by lazy { handPose(Keys.IDLE, Keys.ARM_L, -0.42f, -0.05f, -0.19f, -0.16f, 0.37f) }
    private val forgeIce by lazy { handPose(Keys.IDLE_EMPTY, Keys.ARM_R, -1.36f, -0.38f, 0.25f, -0.53f, 0.04f) }

    private val gripLoad by lazy { coil(Keys.GRIP_AIM, Keys.GRIP_SNAP, 0.6f, 0.8f) }
    private val pommelLoad by lazy { coil(Keys.POMMEL_RAISE, Keys.POMMEL_HIT, 0.15f, 1.0f) }
    private val stabLoad by lazy { coil(Keys.STAB_RAISE, Keys.STAB_HIT, 0.1f, -0.8f) }
    private val cutLoad by lazy { coil(Keys.CUT_CHARGE, Keys.CUT_STRIKE, 0.22f, 2.6f) }
    private val launchLoad by lazy { coil(Keys.LAUNCH_WIND, Keys.LAUNCH_HIT, 0.12f, 1.5f) }
    private val nullLoad by lazy { coil(Keys.NULL_WIND, Keys.NULL_HIT, 0.16f, 1.5f) }
    private val btLoad by lazy { coil(Keys.BT_AIR, Keys.BT_HIT, 0.12f, 0f) }
    private val iaiDeep by lazy { coil(Keys.IAI_STANCE, Keys.IAI_HIT, 0.08f, 1.2f, arms = false) }
    private val throwLoad by lazy { coil(Keys.THROW_WIND, Keys.THROW_HIT, 0.15f, 1.0f) }
    private val splitLoad by lazy { coil(Keys.SPLIT_RAISE, Keys.SPLIT_HIT, 0.1f, -0.6f) }
    private val spinLoad by lazy { coil(Keys.SPIN_WIND, Keys.SPIN_HIT, 0.12f, 1.2f, extraYaw = -0.25f) }

    /**
     * Ausholhaltung [wind], um den Anteil [k] weiter von [hit] weg: Becken (halb so stark), Bauch und Brust drehen weiter
     * ein, die Arme reiten mit (Griff und Klingenlage bleiben exakt; [arms] extrapoliert auch sie). Becken um [sink]
     * Einheiten tiefer (die Fuß-IK beugt die Knie). Der Kopf gleicht die Zusatzdrehung aus, der Blick bleibt am
     * Ziel. Mehrdeutige Unterschiede über π (z. B. die Drehung im Drehschnitt) bleiben weg; dafür gibt es [extraYaw].
     */
    private fun coil(wind: AriusKey, hit: AriusKey, k: Float, sink: Float, arms: Boolean = false, extraYaw: Float = 0f): AriusKey {
        val v = wind.v.copyOf()
        fun add(b: Int, ch: Int, f: Float) {
            val i = b * 6 + ch
            val d = wind.v[i] - hit.v[i]
            if (ch < 3 && abs(d) > PI) return
            v[i] += d * f
        }
        for (ch in 0..2) {
            add(Keys.HIPS, ch, k * 0.5f)
            add(Keys.TORSO, ch, k)
            add(Keys.CHEST, ch, k)
            if (arms) for (b in intArrayOf(Keys.ARM_R, Keys.FORE_R, Keys.HAND_R, Keys.ARM_L, Keys.FORE_L, Keys.HAND_L)) add(b, ch, k)
        }
        add(Keys.HIPS, 3, k * 0.5f)
        add(Keys.HIPS, 5, k * 0.5f)
        v[Keys.HIPS * 6 + 1] += extraYaw
        for (ch in 0..1) {
            var extra = 0f
            for (b in intArrayOf(Keys.HIPS, Keys.TORSO, Keys.CHEST)) extra += v[b * 6 + ch] - wind.v[b * 6 + ch]
            v[Keys.HEAD * 6 + ch] -= extra * 0.8f
        }
        v[Keys.HIPS * 6 + 4] += sink
        return AriusKey(v, wind.hidden, wind.plant)
    }

    // --- Merkwerte je Entity (Überblendung aus der echten Haltung, weiche Umschaltungen, Federn) -------------------------

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
        var counter = 0f

        /** Federn: je Feder Lage, Geschwindigkeit; dazu der Antrieb im letzten Bild. */
        val spring = FloatArray(SPRINGS * 2)
        val driver = FloatArray(SPRINGS)
        var springReady = false
    }

    /** Gemeinsam für alle Instanzen (Hauptmodell und Eisklingen-Ebene zeichnen dieselbe Haltung). */
    private fun memoryOf(id: Int): Memory {
        if (MEMORY.size > 64) MEMORY.clear()
        return MEMORY.getOrPut(id) { Memory() }
    }

    private fun mix(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

    /**
     * Zeitleiste: hält die zuletzt erreichte Haltung und blendet in den Abschnitten [t0, t1] zur nächsten über, je
     * Knochengruppe im Fenster der [Chain]. Merkt sich Start und Ziel des laufenden Abschnitts für die Fuß-IK.
     */
    private inner class Track(var cur: FloatArray, var hid: IntArray, private val t: Float) {
        var out: FloatArray? = null
        private var curPlant: FloatArray = cur
        var segFrom: FloatArray = cur
        var segTo: FloatArray = cur
        var legR = 0f
        var legL = 0f

        /** Ohne Fuß-IK (Laufzyklus, freie Beine). */
        var free = false

        fun to(t0: Float, t1: Float, target: AriusKey, ease: (Float) -> Float, chain: Chain = FLAT): Track {
            if (out != null) return this
            when {
                t >= t1 -> {
                    cur = target.v
                    curPlant = target.plant
                    hid = target.hidden
                    segFrom = curPlant
                    segTo = curPlant
                    legR = 0f
                    legL = 0f
                }
                t > t0 -> {
                    val d = max(t1 - t0, 0.001f)
                    val e = FloatArray(GROUPS) { g ->
                        val a = t0 + chain.start[g] * d
                        val b = t0 + chain.end[g] * d
                        (chain.ease[g] ?: ease)(MathHelper.clamp((t - a) / max(b - a, 0.001f), 0f, 1f))
                    }
                    val from = cur
                    val tv = target.v
                    val o = FloatArray(from.size) { from[it] + (tv[it] - from[it]) * e[group[it / 6]] }
                    // Kugelgelenke (Schulter, Handgelenk) auf dem kürzesten Drehweg, sonst dreht die Klinge über Umwege.
                    for (bone in BALL) {
                        val f = e[group[bone]]
                        if (f <= 0f || f >= 1f) continue
                        val i = bone * 6
                        val q = slerp(quat(rotOf(floatArrayOf(from[i], from[i + 1], from[i + 2]))),
                            quat(rotOf(floatArrayOf(tv[i], tv[i + 1], tv[i + 2]))), f)
                        val eu = euler(matOf(q))
                        o[i] = eu[0]
                        o[i + 1] = eu[1]
                        o[i + 2] = eu[2]
                    }
                    out = o
                    if (e[G_HAND] > 0.5f) hid = target.hidden
                    segFrom = curPlant
                    segTo = target.plant
                    legR = e[G_LEG_R]
                    legL = e[G_LEG_L]
                }
                else -> {
                    out = cur
                    segFrom = curPlant
                    segTo = curPlant
                    legR = 0f
                    legL = 0f
                }
            }
            return this
        }

        fun get(): FloatArray = out ?: cur
    }

    private fun windupOf(pose: Int, w: Int): Float = (if (w > 0) w else AriusTimings.ARIUS_WINDUP[pose]).toFloat()
    private fun recoverOf(pose: Int): Float = AriusTimings.ARIUS_RECOVER[pose].toFloat()

    /** Kettenfenster des Schlags: im letzten Drittel der Ausholzeit, die Hüfte beginnt vor der Klinge. */
    private fun strikeSpan(w: Float, strike: Float): Float = min(min(max(strike * 1.5f, w / 3f), 6f), w * 0.6f)

    /** Ausholhaltung erreicht: höchstens 8 Ticks und immer vor dem Schlag (auch bei kurzem Server-Windup). */
    private fun reach(w: Float, span: Float): Float = MathHelper.clamp(w - span, 0.5f, 8f)

    /**
     * Angriff nach Vertrag: Ausholhaltung (Gewicht zuerst) → Anspannen bis zum Schlag ([load]) → Schlag als Kette genau
     * bis t = w → kurzer Halt ([stop]) → Durchziehen über die Endhaltung hinaus ([follow]) → zurück nach IDLE bei w + r.
     */
    private fun attack(tr: Track, w: Float, r: Float, wind: AriusKey, hit: AriusKey, follow: AriusKey?, load: AriusKey?,
                       strike: Float = 3f, followFor: Float = 4f, stop: Float = 1f, chain: Chain = STRIKE): Track {
        val s = strikeSpan(w, strike)
        val a = reach(w, s)
        tr.to(0f, a, wind, ::smooth, WIND)
        if (load != null && w - s - a > 1f) tr.to(a, w - s, load, ::sine)
        tr.to(w - s, w, hit, ::easeIn, chain)
        val hs = min(stop, r * 0.2f)
        val f = if (follow != null) min(followFor, max(1f, r * 0.4f)) else 0f
        if (follow != null) tr.to(w + hs, w + hs + f, follow, ::easeOut, FOLLOW)
        val end = w + r
        return tr.to(max(w + hs + f, end - min(r, 10f)), end, Keys.IDLE, ::smooth, SETTLE)
    }

    /** Herabsausender Hieb mit Nachhaltung am Boden (Gletscherstoß, Spalten, Durchbruch). */
    private fun slam(tr: Track, w: Float, raise: AriusKey, load: AriusKey, hit: AriusKey, strike: Float, raiseEase: (Float) -> Float): Track {
        val s = strikeSpan(w, strike)
        val a = reach(w, s)
        tr.to(0f, a, raise, raiseEase, WIND)
        if (w - s - a > 1f) tr.to(a, w - s, load, ::sine)
        return tr.to(w - s, w, hit, ::easeIn, STRIKE)
    }

    private fun evaluate(pose: Int, t: Float, w: Float, from: FloatArray, fromHidden: IntArray, m: Memory?): Track {
        val r = recoverOf(pose)
        val tr = Track(from, fromHidden, t)
        val sheath = m?.sheath ?: 0f
        return when (pose) {
            AriusPose.IDLE -> tr.to(0f, 10f, if (sheath > 0.5f) Keys.IDLE_EMPTY else Keys.IDLE, ::smooth, SETTLE)
            AriusPose.SEATED -> tr.to(0f, 14f, if (sheath > 0.5f) Keys.SEATED_EMPTY else Keys.SEATED, ::smooth)
            AriusPose.RISE -> {
                val s = min(3f, w * 0.15f)
                if (m?.drawOnRise == true) {
                    tr.to(0f, w * 0.3f, Keys.RISE_LEAN_EMPTY, ::smooth).to(w * 0.33f, w * 0.6f, Keys.RISE_REACH, ::smooth, WIND)
                        .to(w * 0.65f, w * 0.8f, Keys.RISE_DRAW, ::easeOut).to(w - s, w, Keys.RISE_FLICK, ::easeIn, STRIKE)
                } else {
                    tr.to(0f, w * 0.3f, Keys.RISE_LEAN, ::smooth).to(w * 0.33f, w - s, Keys.RISE_DRAW, ::smooth, WIND)
                        .to(w - s, w, Keys.RISE_FLICK, ::easeIn, STRIKE)
                }
                tr.to(w + 1f, w + max(r, 4f), Keys.IDLE, ::smooth, SETTLE)
            }
            AriusPose.COMBO_1 -> attack(tr, w, r, Keys.C1_WIND, Keys.C1_HIT, Keys.C1_FOLLOW, c1Load, stop = 0.8f)
            AriusPose.COMBO_2 -> attack(tr, w, r, Keys.C2_WIND, Keys.C2_HIT, Keys.C2_FOLLOW, c2Load, stop = 0.7f)
            AriusPose.COMBO_3 -> attack(tr, w, r, Keys.C3_WIND, Keys.C3_HIT, Keys.C3_HOLD, c3Load, strike = 3f, followFor = 8f, stop = 1.2f)
            AriusPose.COMBO_4 -> attack(tr, w, r, Keys.C4_WIND, Keys.C4_HIT, Keys.C4_FOLLOW, c4Load, strike = 2.5f, followFor = 5f, stop = 0.8f)
            AriusPose.LUNGE -> {
                val a = MathHelper.clamp(w * 0.4f, 1f, 8f)
                tr.to(0f, a, Keys.LUNGE_CROUCH, ::smooth, WIND)
                if (w - 2f - a > 1f) tr.to(a, w - 2f, lungeLoad, ::sine)
                tr.to(w - 2f, w, Keys.LUNGE_SPRINT, ::easeIn, STRIKE)
                if (t > w && t < w + 10f) {
                    val k = 0.5f - 0.5f * cos((t - w) * (2f * PI.toFloat() / 5f))
                    tr.out = mix(Keys.LUNGE_SPRINT.v, Keys.LUNGE_SPRINT_B.v, k)
                    tr.free = true
                }
                tr.to(w + 10f, w + 14f, Keys.LUNGE_SLIDE, ::easeOut, FOLLOW).to(w + r - 10f, w + r, Keys.IDLE, ::smooth, SETTLE)
            }
            AriusPose.WALL_CAST -> {
                val s = strikeSpan(w, 2f)
                val a = reach(w, s)
                tr.to(0f, a, Keys.WALL_CROUCH, ::smooth, WIND)
                if (w - s - a > 1f) tr.to(a, w - s, wallLoad, ::sine)
                tr.to(w - s, w, Keys.WALL_PRESS, ::easeIn, STRIKE)
                    .to(w + 1.5f, w + r * 0.7f, Keys.WALL_RISE, ::smooth, SETTLE).to(w + r * 0.7f, w + r, Keys.IDLE, ::smooth, SETTLE)
            }
            AriusPose.STRIP_SLASH -> attack(tr, w, r, Keys.STRIP_WIND, Keys.STRIP_HIT, Keys.STRIP_FOLLOW, stripLoad, strike = 4f, followFor = 5f, stop = 1f)
            AriusPose.GUARD -> tr.to(0f, 6f, Keys.GUARD, ::easeOut)
            AriusPose.PARRY_KICK -> tr.to(0f, w * 0.25f, Keys.PK_DEFLECT, ::easeOut).to(w * 0.3f, w * 0.6f, Keys.PK_TURN, ::smooth, WIND)
                // Nach dem Treffer: Bein zurückziehen (Knie anlegen), dann absetzen.
                .to(w * 0.6f, w, Keys.PK_KICK, ::easeIn, KICK).to(w + min(0.8f, r * 0.2f), w + r * 0.4f, Keys.PK_TURN, ::easeOut, FOLLOW)
                .to(w + r * 0.4f, w + max(r, 4f), Keys.IDLE, ::smooth, SETTLE)
            AriusPose.MACE_CATCH -> tr.to(0f, w, maceCatch, ::easeOut, WIND)
            AriusPose.MACE_SLAM -> tr.to(0f, w, maceSlam, ::easeIn, STRIKE)
            AriusPose.FORGE_ICE -> tr.to(0f, w, forgeIce, ::smooth, WIND)
            AriusPose.GRIP_CAST -> attack(tr, w, r, Keys.GRIP_AIM, Keys.GRIP_SNAP, null, gripLoad, strike = 2f, stop = 1.5f)
            AriusPose.POMMEL -> attack(tr, w, r, Keys.POMMEL_RAISE, Keys.POMMEL_HIT, null, pommelLoad, strike = 3f, stop = 1f)
            AriusPose.STAB_GROUND -> slam(tr, w, Keys.STAB_RAISE, stabLoad, Keys.STAB_HIT, 3f, ::smooth)
                .to(w + 2f, w + 8f, Keys.STAB_STUCK, ::smooth).to(w + r - 14f, w + r - 6f, Keys.STAB_PULL, ::smooth, WIND)
                .to(w + r - 6f, w + r, Keys.IDLE, ::smooth, SETTLE)
            AriusPose.SIDESTEP -> {
                val left = stepLeft
                val step = if (left) Keys.STEP_L else Keys.STEP_R
                val land = if (left) Keys.STEP_L_LAND else Keys.STEP_R_LAND
                tr.to(0f, w * 0.5f, step, ::easeOut).to(w * 0.5f, w, land, ::smooth).to(w + 1f, w + max(r, 3f), Keys.IDLE, ::smooth, SETTLE)
            }
            AriusPose.ARROW_CUT -> tr.to(0f, w * 0.5f, Keys.ARROW_WIND, ::easeOut, WIND).to(w * 0.5f, w, Keys.ARROW_HIT, ::easeIn, STRIKE)
                .to(w + 0.5f, w + max(r, 3f), Keys.IDLE, ::smooth, SETTLE)
            // Laden: in 10 Ticks in die Haltung, dann über 30 Ticks immer tiefer gespannt (Server lädt CUT_WIND = 40).
            AriusPose.CUT_CHARGE -> tr.to(0f, 10f, Keys.CUT_CHARGE, ::smooth, WIND).to(10f, 40f, cutLoad, ::sine)
            // Senkrechtes Aufladen (Weißer Horizont): Klinge zweihändig gerade über dem Kopf in der Schnittebene.
            AriusPose.HORIZON_RAISE -> tr.to(0f, 12f, Keys.SPLIT_RAISE, ::smooth, WIND).to(12f, 52f, splitLoad, ::sine)
            // Senkrechter Niederschlag (Weißer Horizont): zieht exakt aus der erhobenen Ebene gerade nach unten durch.
            AriusPose.CUT_RELEASE -> tr.to(0f, max(1f, w * 0.25f), splitLoad, ::easeOut, WIND)
                .to(max(1f, w * 0.25f), w, Keys.SPLIT_HIT, ::easeIn, STRIKE)
                .to(w + 1.5f, w + 8f, Keys.SPLIT_LOW, ::easeOut, FOLLOW)
            AriusPose.GATE_KICK -> tr.to(0f, w * 0.22f, Keys.GK_PARRY, ::easeOut).to(w * 0.25f, w * 0.45f, Keys.GK_LOAD, ::sine, WIND)
                .to(w * 0.45f, w * 0.72f, Keys.GK_KNEE, ::easeOut, WIND).to(w * 0.72f, w, Keys.GK_KICK, ::easeIn, KICK)
                .to(w + min(1f, r * 0.2f), w + r * 0.3f, Keys.GK_KNEE, ::easeOut, FOLLOW).to(w + r * 0.3f, w + r, Keys.IDLE, ::smooth, SETTLE)
            AriusPose.STAGGER -> tr.to(0f, 8f, Keys.STAGGER, ::easeOut)
            AriusPose.TRANSFORM -> tr.to(0f, w * 0.1f, Keys.TR_KNEEL, ::easeOut).to(w * 0.25f, w * 0.7f, Keys.TR_RISE, ::smooth, SETTLE)
                .to(w * 0.7f, w, Keys.TR_OPEN, ::smooth, SETTLE)
            AriusPose.LAUNCH -> {
                val s = strikeSpan(w, 3f)
                val a = reach(w, s)
                tr.to(0f, a, Keys.LAUNCH_WIND, ::smooth, WIND)
                if (w - s - a > 1f) tr.to(a, w - s, launchLoad, ::sine)
                tr.to(w - s, w, Keys.LAUNCH_HIT, ::easeIn, STRIKE)
                    // Ausnahme: hält die Sprunghaltung bis w + r (danach folgt AIR_KICK) und sinkt erst dann nach IDLE.
                    .to(w, w + 3f, Keys.LAUNCH_FOLLOW, ::easeOut, FOLLOW).to(w + r, w + r + 8f, Keys.IDLE, ::smooth, SETTLE)
            }
            AriusPose.AIR_KICK -> tr.to(0f, min(6f, w - 3f), Keys.AIR_TUCK, ::easeOut, WIND).to(w - 3f, w, Keys.AIR_KICK, ::easeIn, KICK)
                .to(w + 2f, w + 6f, Keys.AIR_LAND, ::easeIn).to(w + r - 5f, w + r, Keys.IDLE, ::smooth, SETTLE)
            AriusPose.NULL_CUT -> attack(tr, w, r, Keys.NULL_WIND, Keys.NULL_HIT, Keys.NULL_FOLLOW, nullLoad, strike = 2f, followFor = 3f, stop = 1.2f)
            AriusPose.CORE_RAISE -> tr.to(0f, 12f, Keys.CORE_RAISE, ::smooth, WIND)
            AriusPose.BREAKTHROUGH -> slam(tr, w, Keys.BT_AIR, btLoad, Keys.BT_HIT, 4f, ::easeOut)
                .to(w + 1.5f, w + 7f, Keys.BT_LOW, ::easeOut, FOLLOW).to(w + r - 10f, w + r, Keys.IDLE, ::smooth, SETTLE)
            // Iai-Haltung: in 10 Ticks tief, danach sinkt er langsam weiter (Spannung), Hand bleibt am Heft.
            AriusPose.IAI_STANCE -> tr.to(0f, 10f, Keys.IAI_STANCE, ::smooth, WIND).to(10f, 40f, iaiDeep, ::sine)
            AriusPose.IAI_DRAW -> tr.to(0f, w * 0.45f, Keys.IAI_MID, ::easeIn, WIND).to(w * 0.45f, w, Keys.IAI_HIT, ::easeIn, STRIKE)
                .to(w + 0.8f, w + 6f, Keys.IAI_END, ::easeOut, FOLLOW).to(w + r - 12f, w + r, Keys.IDLE, ::smooth, SETTLE)
            AriusPose.KNEEL -> tr.to(0f, 12f, Keys.KNEEL, ::smooth)
            AriusPose.THROW_CRYSTALS -> attack(tr, w, r, Keys.THROW_WIND, Keys.THROW_HIT, null, throwLoad, strike = 3f, stop = 0.6f)
            AriusPose.SPLIT_FIELD -> slam(tr, w, Keys.SPLIT_RAISE, splitLoad, Keys.SPLIT_HIT, 3f, ::smooth)
                .to(w + 1.5f, w + 6.5f, Keys.SPLIT_LOW, ::easeOut, FOLLOW).to(w + r - 10f, w + r, Keys.IDLE, ::smooth, SETTLE)
            AriusPose.WHISTLE -> tr.to(0f, min(6f, w), Keys.WHISTLE, ::smooth).to(w + r - 6f, w + r, Keys.IDLE, ::smooth)
            AriusPose.SPIN_CUT -> {
                val s = strikeSpan(w, 4f)
                val a = reach(w, s)
                tr.to(0f, a, Keys.SPIN_WIND, ::smooth, WIND)
                if (w - s - a > 1f) tr.to(a, w - s, spinLoad, ::sine)
                tr.to(w - s, w, Keys.SPIN_HIT, ::easeIn, STRIKE).to(w + 0.5f, w + 4.5f, Keys.SPIN_END, ::easeOut, FOLLOW)
                    .to(w + r - 8f, w + r, idleSpun, ::smooth, SETTLE)
            }
            else -> tr.to(0f, 10f, Keys.IDLE, ::smooth)
        }
    }

    /** Wucht je Pose für den Treffer-Stoß (0 = keiner), dazu Landungen als zweiter Stoß. */
    private fun heavy(pose: Int): Float = when (pose) {
        AriusPose.COMBO_1 -> 0.5f
        AriusPose.COMBO_2 -> 0.45f
        AriusPose.COMBO_3 -> 0.65f
        AriusPose.COMBO_4 -> 0.55f
        AriusPose.WALL_CAST -> 0.6f
        AriusPose.STRIP_SLASH -> 0.6f
        AriusPose.PARRY_KICK -> 0.55f
        AriusPose.GRIP_CAST -> 0.3f
        AriusPose.POMMEL -> 0.55f
        AriusPose.STAB_GROUND -> 1f
        AriusPose.ARROW_CUT -> 0.25f
        AriusPose.CUT_RELEASE -> 1f
        AriusPose.GATE_KICK -> 0.7f
        AriusPose.LAUNCH -> 0.4f
        AriusPose.AIR_KICK -> 0.5f
        AriusPose.NULL_CUT -> 0.7f
        AriusPose.BREAKTHROUGH -> 1f
        AriusPose.IAI_DRAW -> 0.8f
        AriusPose.THROW_CRYSTALS -> 0.3f
        AriusPose.SPLIT_FIELD -> 1f
        AriusPose.SPIN_CUT -> 0.5f
        AriusPose.RISE -> 0.2f
        else -> 0f
    }

    /** Treffer-Stoß(e) dieser Pose zur Zeit t auf die Haltung legen. */
    private fun impactLayer(pose: Int, t: Float, w: Float, v: FloatArray) {
        impact(v, t - w, heavy(pose))
        when (pose) {
            AriusPose.AIR_KICK -> impact(v, t - (w + 5.5f), 0.7f)             // Landung in der Hocke
            AriusPose.LUNGE -> impact(v, t - (w + 12f), 0.45f)               // Schlittern endet
            AriusPose.TRANSFORM -> impact(v, t - w * 0.1f, 0.6f)             // landet auf dem Knie
        }
    }

    /**
     * Treffer: harter, kurzer Stoß durch den Körper (Becken sackt, Rumpf staucht, kurzes Zittern), danach federt er in
     * einer gedämpften Schwingung ins Gleichgewicht zurück. Das Einsacken beugt über die Fuß-IK die Knie.
     */
    private fun impact(v: FloatArray, tau: Float, heavy: Float) {
        if (tau < 0f || heavy <= 0f || tau > 16f) return
        val two = 2f * PI.toFloat()
        val hit = heavy * (1f - exp(-tau / 0.25f)) * exp(-tau / 1.3f)
        val bob = heavy * exp(-tau / 3.2f) * sin(tau * two / 7f)
        val jolt = heavy * exp(-tau / 0.6f) * sin(tau * two / 1.6f)
        v[Keys.HIPS * 6 + 4] += 1.8f * hit + 0.9f * bob
        v[Keys.TORSO * 6] += 0.05f * hit - 0.03f * bob
        v[Keys.CHEST * 6] += 0.09f * hit - 0.04f * bob
        v[Keys.CHEST * 6 + 2] += 0.03f * jolt
        v[Keys.HEAD * 6] += 0.06f * hit
        v[Keys.HEAD * 6 + 2] -= 0.02f * jolt
    }

    // --- Fuß-IK ---------------------------------------------------------------------------------------------------------

    /** Weltlage (Modellraum) von Hüftgelenk, Knie, Knöchel und Fußdrehung eines Beins (s = 0 rechts, 1 links). */
    private inner class Leg(v: FloatArray, s: Int) {
        val rHips = rot(v, Keys.HIPS)
        val hip: FloatArray
        val knee: FloatArray
        val ankle: FloatArray
        val foot: FloatArray

        /** Tiefster Punkt der Sohle (Ferse oder Spitze), +y unten. */
        val sole: Float

        init {
            val h = Keys.HIPS * 6
            val thigh = if (s == 0) Keys.THIGH_R else Keys.THIGH_L
            val shin = thigh + 1
            val foot0 = thigh + 2
            val hp = floatArrayOf(hipsPivot[0] + v[h + 3], hipsPivot[1] + v[h + 4], hipsPivot[2] + v[h + 5])
            hip = add(hp, apply(rHips, offset(thighPivot[s], v, thigh)))
            val rt = mul(rHips, rot(v, thigh))
            knee = add(hip, apply(rt, offset(shinPivot[s], v, shin)))
            val rs = mul(rt, rot(v, shin))
            ankle = add(knee, apply(rs, offset(footPivot[s], v, foot0)))
            foot = mul(rs, rot(v, foot0))
            sole = ankle[1] + soleDrop(foot)
        }
    }

    private fun offset(p: FloatArray, v: FloatArray, b: Int) = floatArrayOf(p[0] + v[b * 6 + 3], p[1] + v[b * 6 + 4], p[2] + v[b * 6 + 5])

    /**
     * Hält Füße am Boden: steht ein Fuß am Anfang und am Ende des laufenden Abschnitts (Sohle am Boden, auch auf den Zehen),
     * läuft der Knöchel auf gerader Linie dorthin (bei großen Schritten mit Anheben) und das Bein wird per Zwei-Knochen-IK
     * gelöst; die Sohle behält ihre Ausrichtung. Sehr große Beckendrehungen (Drehschnitt) bleiben bei der
     * Winkel-Überblendung, damit der Fuß mitschwenkt.
     */
    private fun plantFeet(v: FloatArray, tr: Track) {
        val a = tr.segFrom
        val b = tr.segTo
        val plant = !tr.free && abs(wrap(b[Keys.HIPS * 6 + 1] - a[Keys.HIPS * 6 + 1])) <= 1.7f
        for (s in 0..1) {
            val la = Leg(a, s)
            val lb = if (b === a) la else Leg(b, s)
            if (!plant || la.sole < GROUNDED || lb.sole < GROUNDED) {
                // Ohne Pflanzen (freies Bein, Drehschnitt): nur verhindern, dass Einsinken oder Stoß die Sohle versenkt.
                val now = Leg(v, s)
                if (now.sole > 24.05f) solveLeg(v, s, floatArrayOf(now.ankle[0], now.ankle[1] - (now.sole - 24f), now.ankle[2]), now.foot)
                continue
            }
            val k = if (s == 0) tr.legR else tr.legL
            val target = FloatArray(3) { la.ankle[it] + (lb.ankle[it] - la.ankle[it]) * k }
            val dx = lb.ankle[0] - la.ankle[0]
            val dz = lb.ankle[2] - la.ankle[2]
            val dist = sqrt(dx * dx + dz * dz)
            if (dist > 2.5f) target[1] -= sin(k * PI.toFloat()) * min(3.5f, 0.45f * dist)
            val ea = euler(la.foot)
            val eb = euler(lb.foot)
            val footWorld = rotOf(FloatArray(3) { ea[it] + wrap(eb[it] - ea[it]) * k })
            // Nie unter den Boden: tiefster Sohlenpunkt höchstens auf 24.
            target[1] = min(target[1], 24f - soleDrop(footWorld))
            solveLeg(v, s, target, footWorld)
        }
    }

    private fun solveLeg(v: FloatArray, s: Int, target: FloatArray, footWorld: FloatArray) {
        val now = Leg(v, s)
        val side = if (s == 0) -1f else 1f
        // Kniezielrichtung aus der überblendeten Haltung, leicht nach vorn gezogen (gestrecktes Bein bleibt eindeutig).
        val mid = FloatArray(3) { (now.hip[it] + now.ankle[it]) * 0.5f }
        val fwd = apply(now.rHips, floatArrayOf(side * 0.18f, -0.05f, -1f))
        val pole = FloatArray(3) { now.knee[it] - mid[it] + fwd[it] * 0.6f }
        val d = sub(target, now.hip)
        val dl = max(len(d), 1e-4f)
        val u = FloatArray(3) { d[it] / dl }
        val a = thighLen
        val bLen = shinLen
        val dc = max(abs(a - bLen) + 0.05f, min(a + bLen - 0.02f, dl))
        val cosA = (a * a + dc * dc - bLen * bLen) / (2f * a * dc)
        val sinA = sqrt(max(0f, 1f - cosA * cosA))
        var n = sub(pole, scale(u, dot(pole, u)))
        if (dot(n, n) < 1e-8f) n = sub(floatArrayOf(0f, 0f, 1f), scale(u, u[2]))
        n = norm(n)
        val knee = FloatArray(3) { now.hip[it] + (u[it] * cosA + n[it] * sinA) * a }
        val y = norm(sub(knee, now.hip))
        val z = norm(scale(sub(n, scale(y, dot(n, y))), -1f))
        val x = cross(y, z)
        val rUp = floatArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])
        val tip = FloatArray(3) { now.hip[it] + u[it] * dc }
        val lv = applyT(rUp, scale(sub(tip, knee), 1f / bLen))
        val shinPitch = max(0f, atan2(lv[2], lv[1]))
        val thigh = if (s == 0) Keys.THIGH_R else Keys.THIGH_L
        val te = euler(mulT(now.rHips, rUp))
        for (i in 0..2) v[thigh * 6 + i] = te[i]
        v[(thigh + 1) * 6] = shinPitch
        v[(thigh + 1) * 6 + 1] = 0f
        v[(thigh + 1) * 6 + 2] = 0f
        val rShin = mul(rUp, rotOf(floatArrayOf(shinPitch, 0f, 0f)))
        val fe = euler(mulT(rShin, footWorld))
        for (i in 0..2) v[(thigh + 2) * 6 + i] = fe[i]
    }

    // --- Feder-Dämpfer ----------------------------------------------------------------------------------------------------

    /**
     * Schreitet die Federn um dt Ticks voran (Unterschritte ≤ 0,2). Jede Feder folgt ihrem Antrieb (Winkel bzw. Höhe
     * aus der fertigen Haltung) wie ein gedämpftes Pendel; die Abweichung ist die träge Nachbewegung.
     */
    private fun springs(m: Memory, v: FloatArray, dt: Float) {
        val h = Keys.HIPS * 6
        val pitchHead = v[h] + v[Keys.TORSO * 6] + v[Keys.CHEST * 6] + v[Keys.HEAD * 6]
        val yawHead = v[h + 1] + v[Keys.TORSO * 6 + 1] + v[Keys.CHEST * 6 + 1] + v[Keys.HEAD * 6 + 1]
        val drive = FloatArray(SPRINGS)
        drive[S_COAT_YAW] = v[h + 1]
        drive[S_COAT_PITCH] = v[h] + v[Keys.TORSO * 6] * 0.3f
        drive[S_COAT_LIFT] = v[h + 4]
        drive[S_COAT_LOW] = v[h + 1]
        drive[S_HAIR_PITCH] = pitchHead
        drive[S_HAIR_YAW] = yawHead
        drive[S_HAIR_LIFT] = v[h + 4]
        drive[S_ANT_PITCH] = pitchHead
        drive[S_ANT_YAW] = yawHead
        drive[S_SCAB_PITCH] = v[h] + v[Keys.TORSO * 6] + v[Keys.CHEST * 6]
        drive[S_SCAB_YAW] = v[h + 1] + v[Keys.TORSO * 6 + 1] + v[Keys.CHEST * 6 + 1]
        drive[S_SASH] = v[h + 1]
        drive[S_SASH_LIFT] = v[h + 4]
        val st = m.spring
        if (!m.springReady || dt > 5f) {
            for (i in 0 until SPRINGS) {
                st[i * 2] = drive[i]
                st[i * 2 + 1] = 0f
                m.driver[i] = drive[i]
            }
            m.springReady = true
            return
        }
        if (dt <= 0f) return
        val steps = max(1, ceil(dt / 0.2f).toInt())
        val hStep = dt / steps
        for (i in 0 until SPRINGS) {
            var d = drive[i] - m.driver[i]
            // Sprung im Antrieb (Beckendrehung nach dem Drehschnitt zurückgeholt): Feder mitversetzen.
            if (abs(d) > PI) {
                st[i * 2] += d
                m.driver[i] = drive[i]
                d = 0f
            }
            val vel = d / dt
            var x = st[i * 2]
            var xv = st[i * 2 + 1]
            for (j in 1..steps) {
                val s = m.driver[i] + d * (j.toFloat() / steps)
                xv += hStep * (SPRING_K[i] * (s - x) + SPRING_C[i] * (vel - xv))
                x += hStep * xv
            }
            st[i * 2] = x
            st[i * 2 + 1] = xv
            m.driver[i] = drive[i]
        }
    }

    /** Träge Abweichung einer Feder (Lage − Antrieb), begrenzt. */
    private fun lag(m: Memory, i: Int, limit: Float) = MathHelper.clamp(m.spring[i * 2] - m.driver[i], -limit, limit)

    // --- Ablauf je Bild -----------------------------------------------------------------------------------------------

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
        val dt = if (m.time.isNaN()) 100f else MathHelper.clamp(now - m.time, 0f, 100f)
        m.time = now
        val k = 1f - exp(-min(dt, 5f) * 0.3f)
        // Ohne Klinge (Solvane ist der Drache) hält er die Hände wie mit leerer Hand.
        val emptyHanded = state.sheathed || state.blade == AriusBlade.NONE || state.blade == AriusBlade.SHEATH_ONLY
        m.sheath += ((if (emptyHanded) 1f else 0f) - m.sheath) * k
        m.open += ((if (state.open) 1f else 0f) - m.open) * k
        m.counter += ((if (state.counter) 1f else 0f) - m.counter) * (1f - exp(-min(dt, 5f) * 0.55f))

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
        var v = track.get().copyOf()
        // IDLE/SEATED: zwischen Haltung mit und ohne Schwert in der Hand weich überblenden.
        if (pose == AriusPose.IDLE && t > 10f) v = mix(Keys.IDLE.v, Keys.IDLE_EMPTY.v, m.sheath)
        if (pose == AriusPose.SEATED && t > 14f) v = mix(Keys.SEATED.v, Keys.SEATED_EMPTY.v, m.sheath)
        impactLayer(pose, t, w, v)
        plantFeet(v, track)
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
        val ice = AriusBlade.hasIce(state.blade)
        solvane.visible = hand && (state.blade == AriusBlade.HELD || ice)
        sheathHilt.visible = (!hand && state.blade == AriusBlade.HELD) || AriusBlade.hasSheathedSolvane(state.blade)
        for (part in solvaneTree) part.hidden = ice

        springs(m, v, dt)
        // Hand am Heft der Scheide: die Scheide darf dann nicht schwingen.
        val atHilt = pose == AriusPose.IAI_STANCE || (pose == AriusPose.IAI_DRAW && t < w * 0.45f) ||
            (pose == AriusPose.RISE && m.drawOnRise && t < w * 0.7f)
        secondary(state, age, m, if (atHilt) 0f else 1f)
    }

    private fun secondary(state: AriusRenderState, age: Float, m: Memory, scabbardFree: Float) {
        val pose = state.bossPose
        val hips = part(Keys.HIPS)
        val torso = part(Keys.TORSO)
        val chest = part(Keys.CHEST)
        val head = part(Keys.HEAD)
        // Seit 1.8.0 größerer, kantiger Kopf (näher am Minecraft-Maß, Crix: „der runde Kopf passt nicht“).
        head.xScale = HEAD_SCALE; head.yScale = HEAD_SCALE; head.zScale = HEAD_SCALE
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

        // Seit 1.11.0, Konterhaltung: tief geduckt, Oberkörper vorgebeugt, Kopf hoch, die Klinge steil vor dem Gesicht.
        // Klar anders als Ruhe- und Angriffshaltungen, und sie zittert leicht vor Spannung.
        val guardK = m.counter
        if (guardK > 0.01f) {
            val tense = sin(age * 2.1f) * 0.012f * guardK
            hips.originY += 2.2f * guardK
            chest.pitch += 0.2f * guardK
            head.pitch -= 0.22f * guardK
            part(Keys.THIGH_R).pitch -= 0.34f * guardK
            part(Keys.THIGH_L).pitch -= 0.34f * guardK
            part(Keys.SHIN_R).pitch += 0.58f * guardK
            part(Keys.SHIN_L).pitch += 0.58f * guardK
            part(Keys.FOOT_R).pitch -= 0.24f * guardK
            part(Keys.FOOT_L).pitch -= 0.24f * guardK
            if (pose != AriusPose.IAI_STANCE) {
                armR.pitch -= 0.62f * guardK
                armR.roll += 0.14f * guardK + tense
                armL.pitch -= 0.34f * guardK
            } else {
                armR.roll += tense
            }
        }

        // Gehen: echter Gang mit Gewichtsverlagerung, Beckendrehung, Gegenrotation der Schultern, Armpendel.
        val gait = when (pose) {
            AriusPose.IDLE, AriusPose.ARROW_CUT -> 1f
            AriusPose.GUARD, AriusPose.GRIP_CAST, AriusPose.IAI_STANCE, AriusPose.CUT_CHARGE -> 0.6f
            AriusPose.STAGGER, AriusPose.WHISTLE -> 0.5f
            AriusPose.LUNGE, AriusPose.SEATED, AriusPose.KNEEL, AriusPose.AIR_KICK, AriusPose.BREAKTHROUGH, AriusPose.SPIN_CUT,
            AriusPose.HORIZON_RAISE, AriusPose.CUT_RELEASE -> 0f
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

        // Spannung im gehaltenen Ausholen: Zittern, beim Laden des Schnitts mit der Zeit stärker.
        val t = state.poseTime
        val w0 = windupOf(pose, state.windup)
        val holding = when (pose) {
            AriusPose.CUT_CHARGE, AriusPose.HORIZON_RAISE, AriusPose.IAI_STANCE, AriusPose.CORE_RAISE -> t > 10f
            AriusPose.STRIP_SLASH, AriusPose.SPLIT_FIELD, AriusPose.STAB_GROUND, AriusPose.NULL_CUT, AriusPose.LUNGE,
            AriusPose.COMBO_3, AriusPose.BREAKTHROUGH, AriusPose.SPIN_CUT -> t > 6f && t < w0 - 3f
            else -> false
        }
        if (holding) {
            val build = if (pose == AriusPose.CUT_CHARGE || pose == AriusPose.HORIZON_RAISE || pose == AriusPose.IAI_STANCE) MathHelper.clamp((t - 10f) / 30f, 0f, 1f) else 0f
            val shake = sin(age * 2.3f) * (0.01f + 0.016f * build)
            armR.roll += shake
            armL.roll -= shake
            chest.pitch += sin(age * 1.7f) * (0.005f + 0.008f * build)
            hips.originY += sin(age * 1.9f) * 0.15f * build
        }
        if (pose == AriusPose.CUT_CHARGE) head.yaw += sin(age * 0.05f) * 0.02f

        // Mantelschöße und Gewandrock: Federn hinter Beckendrehung, -neigung und -höhe; Beine schieben die Schöße.
        val yawLag = lag(m, S_COAT_YAW, 0.8f)
        val lowLag = lag(m, S_COAT_LOW, 1.0f) - yawLag
        val spinSpeed = abs(m.spring[S_COAT_YAW * 2 + 1])
        val lift = lag(m, S_COAT_LIFT, 6f)
        val flare = MathHelper.clamp((spinSpeed - 0.15f) * 0.8f, 0f, 0.6f) + MathHelper.clamp(-lift * 0.08f, -0.15f, 0.4f)
        val pitchLag = lag(m, S_COAT_PITCH, 0.6f)
        val wind = 0.025f + 0.02f * state.walk
        val clock = age
        for ((i, bone) in Keys.SKIRT.withIndex()) {
            val back = i >= 2
            val right = i % 2 == 0
            val sx = if (right) -1f else 1f
            val sz = if (back) 1f else -1f
            val p = part(bone)
            val p1 = part(bone + 1)
            val legAdd = if (right) thighR else thighL
            p.pitch += (if (back) max(0f, legAdd) * 0.9f + 0.12f * state.walk else min(0f, legAdd) * 0.9f) +
                pitchLag * 0.8f - sx * yawLag * 0.5f + sz * flare * 0.6f
            p.roll += -sx * flare - sz * yawLag * 0.6f
            p.pitch += sin(clock * 0.11f + i * 1.3f) * wind
            p1.pitch += sin(clock * 0.11f + i * 1.3f - 0.9f) * wind * 1.6f + (if (back) 0.1f * state.walk else 0f) +
                pitchLag * 0.5f - sx * lowLag * 0.4f + sz * flare * 0.3f
            p1.roll += -sx * flare * 0.5f - sz * lowLag * 0.5f
        }
        val tail = part(Keys.SASH_TAIL)
        val sashLag = lag(m, S_SASH, 0.8f)
        tail.pitch += sin(clock * 0.13f) * 0.05f - sashLag * 0.7f + MathHelper.clamp(-lag(m, S_SASH_LIFT, 6f) * 0.1f, -0.2f, 0.6f)
        tail.roll += sashLag * 0.9f + sin(clock * 0.09f + 1f) * 0.04f

        // Haar und Antenne: folgen Kopfneigung, -drehung und Höhe mit Verzögerung.
        val hair = part(Keys.HAIR_BACK)
        hair.pitch += sin(clock * 0.1f) * 0.03f + lag(m, S_HAIR_PITCH, 0.7f) * 0.9f +
            MathHelper.clamp(-lag(m, S_HAIR_LIFT, 6f) * 0.06f, -0.15f, 0.4f) + 0.1f * state.walk * sin(state.walkPhase * 2f)
        hair.roll -= lag(m, S_HAIR_YAW, 0.7f) * 0.8f
        val ant = part(Keys.ANTENNA)
        ant.pitch += sin(clock * 0.21f) * 0.06f + lag(m, S_ANT_PITCH, 0.8f) * 1.1f + 0.12f * state.walk * sin(state.walkPhase * 2f + 0.5f)
        ant.roll += sin(clock * 0.17f + 0.7f) * 0.05f + lag(m, S_ANT_YAW, 0.8f) * 0.7f

        // Scheide auf dem Rücken: schwer, kurz nachpendelnd.
        scabbard.pitch += lag(m, S_SCAB_PITCH, 0.5f) * 0.35f * scabbardFree
        scabbard.roll += lag(m, S_SCAB_YAW, 0.5f) * 0.3f * scabbardFree
    }

    private companion object {
        const val HEAD_SCALE = 1.18f
        private val MEMORY = HashMap<Int, Memory>()

        /** Sohle tiefer als das (Modell: +y unten, Boden bei 24) gilt als am Boden, auch auf den Zehen. */
        const val GROUNDED = 23f

        /** Sohlenecken im Fußraum (Stiefelquader in leg_part, arius_model.py). */
        val SOLE = arrayOf(floatArrayOf(-2.5f, 3f, -5.5f), floatArrayOf(2.5f, 3f, -5.5f), floatArrayOf(-2.5f, 3f, 2.5f), floatArrayOf(2.5f, 3f, 2.5f))

        /** Wie weit die tiefste Sohlenecke unter dem Knöchel liegt (bei Fußdrehung [foot]). */
        fun soleDrop(foot: FloatArray): Float = SOLE.maxOf { foot[3] * it[0] + foot[4] * it[1] + foot[5] * it[2] }

        fun len(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        fun add(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
        fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        fun scale(a: FloatArray, k: Float) = floatArrayOf(a[0] * k, a[1] * k, a[2] * k)
        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun norm(a: FloatArray): FloatArray = scale(a, 1f / max(len(a), 1e-6f))

        /** R = Rz(roll) · Ry(yaw) · Rx(pitch) wie ModelPart, zeilenweise. */
        fun rotOf(e: FloatArray): FloatArray {
            val cx = cos(e[0]); val sx = sin(e[0])
            val cy = cos(e[1]); val sy = sin(e[1])
            val cz = cos(e[2]); val sz = sin(e[2])
            return floatArrayOf(
                cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
                sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
                -sy, cy * sx, cy * cx,
            )
        }

        fun rot(v: FloatArray, b: Int) = rotOf(floatArrayOf(v[b * 6], v[b * 6 + 1], v[b * 6 + 2]))

        /** Zerlegt R in (pitch, yaw, roll), Umkehrung von [rotOf]. */
        fun euler(m: FloatArray): FloatArray {
            val sy = MathHelper.clamp(-m[6], -1f, 1f)
            val yaw = asin(sy)
            return if (abs(sy) < 0.9999f) floatArrayOf(atan2(m[7], m[8]), yaw, atan2(m[3], m[0]))
            else floatArrayOf(atan2(-m[5], m[4]), yaw, 0f)
        }

        /** Einheitsquaternion (w, x, y, z) einer Drehmatrix. */
        fun quat(m: FloatArray): FloatArray {
            val tr = m[0] + m[4] + m[8]
            val q = if (tr > 0f) {
                val s = sqrt(tr + 1f) * 2f
                floatArrayOf(0.25f * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
            } else if (m[0] > m[4] && m[0] > m[8]) {
                val s = sqrt(1f + m[0] - m[4] - m[8]) * 2f
                floatArrayOf((m[7] - m[5]) / s, 0.25f * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
            } else if (m[4] > m[8]) {
                val s = sqrt(1f + m[4] - m[0] - m[8]) * 2f
                floatArrayOf((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25f * s, (m[5] + m[7]) / s)
            } else {
                val s = sqrt(1f + m[8] - m[0] - m[4]) * 2f
                floatArrayOf((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25f * s)
            }
            val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            return FloatArray(4) { q[it] / n }
        }

        fun slerp(a: FloatArray, b0: FloatArray, t: Float): FloatArray {
            var d = a[0] * b0[0] + a[1] * b0[1] + a[2] * b0[2] + a[3] * b0[3]
            val b = if (d < 0f) FloatArray(4) { -b0[it] } else b0
            d = abs(d)
            val (ka, kb) = if (d > 0.9995f) (1f - t) to t else {
                val th = kotlin.math.acos(min(d, 1f))
                val s = sin(th)
                sin((1f - t) * th) / s to sin(t * th) / s
            }
            val q = FloatArray(4) { a[it] * ka + b[it] * kb }
            val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            return FloatArray(4) { q[it] / n }
        }

        fun matOf(q: FloatArray): FloatArray {
            val (w, x, y, z) = q
            return floatArrayOf(
                1f - 2f * (y * y + z * z), 2f * (x * y - w * z), 2f * (x * z + w * y),
                2f * (x * y + w * z), 1f - 2f * (x * x + z * z), 2f * (y * z - w * x),
                2f * (x * z - w * y), 2f * (y * z + w * x), 1f - 2f * (x * x + y * y),
            )
        }

        fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { val i = it / 3; val j = it % 3; a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j] }

        /** aᵀ · b */
        fun mulT(a: FloatArray, b: FloatArray) = FloatArray(9) { val i = it / 3; val j = it % 3; a[i] * b[j] + a[3 + i] * b[3 + j] + a[6 + i] * b[6 + j] }

        fun apply(m: FloatArray, v: FloatArray) = floatArrayOf(
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2])

        fun applyT(m: FloatArray, v: FloatArray) = floatArrayOf(
            m[0] * v[0] + m[3] * v[1] + m[6] * v[2], m[1] * v[0] + m[4] * v[1] + m[7] * v[2], m[2] * v[0] + m[5] * v[1] + m[8] * v[2])
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
        val C1_WIND = key("0.06,0.72,0,0,3.9748,2.5,-0.06,0.24,0,0,0,0,-0.12,0.3,-0.08,0,0,0,0.06,-1.15,0.05,0,0,0,-0.0041,0,-0.0525,0,0,0,0,0,0,0,0,0,-1.0104,-0.8349,-0.9123,0,0,0,-1.8503,0,0,0,0,0,1.2592,0.1702,-0.04,0,0,0,-1.6156,-0.2981,-0.9479,0,0,0,-1.8448,0,0,0,0,0,1.8847,0.0024,-0.0085,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6784,0.1826,0.1178,0,0,0,1.1328,0,0,0,0,0,-0.7382,0,-0.1434,0,0,0,-0.7462,-0.1842,-0.2013,0,0,0,1.0004,0,0,0,0,0,-0.2709,0,0.2167,0,0,0,-0.7484,0,0.1301,0,0,0,0.5847,0,-0.1172,0,0,0,-0.8162,0,-0.2011,0,0,0,0.6417,0,0.1907,0,0,0,-0.01,0,0.1301,0,0,0,-0.0429,0,-0.091,0,0,0,-0.01,0,-0.2011,0,0,0,-0.0435,0,0.1407,0,0,0,-0.0609,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C1_HIT = key("0.28,-0.45,0,0,5.1281,-4.5,0.16,-0.24,0,0,0,0,0.18,-0.22,0.06,0,0,0,-0.42,0.78,0,0,0,0,-0.0707,0,-0.1979,0,0,0,0,0,0,0,0,0,1.1293,-0.3047,0.0064,0,0,0,-2.7457,0,0,0,0,0,1.3278,0.229,0.6022,0,0,0,1.1265,0.7194,1.1336,0,0,0,-1.8573,0,0,0,0,0,0.6074,-0.8075,0.4304,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3199,0.1754,-0.0516,0,0,0,1.0367,0,0,0,0,0,-1.5014,0,0.0011,0,0,0,-1.1955,-0.1594,0.263,0,0,0,1.1691,0,0,0,0,0,-0.2921,0,-0.2134,0,0,0,-0.4399,0,0,0,0,0,0.1359,0,0,0,0,0,-1.3155,0,0,0,0,0,0.8802,0,0,0,0,0,-0.1999,0,0,0,0,0,-0.0681,0,0,0,0,0,-0.28,0,0,0,0,0,0,0,0,0,0,0,-0.2841,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C1_FOLLOW = key("0.34,-0.78,0,0,6.1281,-5,0.2,-0.3,0,0,0,0,0.2,-0.26,0.08,0,0,0,-0.45,1.05,0,0,0,0,-0.0639,0,-0.2981,0,0,0,0,0,0,0,0,0,0.2738,-0.2304,-0.2398,0,0,0,-1.6548,0,0,0,0,0,1.5618,0.7033,0.0097,0,0,0,0.6105,0.5979,0.4213,0,0,0,-1.6853,0,0,0,0,0,0.3179,-1.2599,0.3033,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3849,0.1628,-0.2258,0,0,0,1.0782,0,0,0,0,0,-1.5484,0,0.3144,0,0,0,-1.2709,-0.1459,0.3942,0,0,0,1.2775,0,0,0,0,0,-0.3988,0,-0.3428,0,0,0,-0.5049,0,0.08,0,0,0,0.1393,0,-0.0535,0,0,0,-1.3909,0,-0.08,0,0,0,0.8924,0,0.1055,0,0,0,-0.2649,0,0.08,0,0,0,-0.0647,0,-0.0529,0,0,0,-0.34,0,-0.08,0,0,0,-0.0009,0,0.0528,0,0,0,-0.3448,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C2_WIND = key("0.16,-0.85,0,0,5,-0.5,0.08,-0.36,0,0,0,0,0.1,-0.38,0.06,0,0,0,-0.2,1.3,0,0,0,0,0.0217,0,-0.1673,0,0,0,0,0,0,0,0,0,-0.1507,1.2132,-2.9543,0,0,0,-2.5903,0,0,0,0,0,-3.0674,0.2086,0.7881,0,0,0,-0.2637,0.4086,0.055,0,0,0,-2.1327,0,0,0,0,0,2.5181,0.807,0.6977,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.5293,0.1752,-0.0547,0,0,0,1.1773,0,0,0,0,0,-0.8147,0,0.037,0,0,0,-0.9313,-0.1496,0.361,0,0,0,0.958,0,0,0,0,0,-0.2363,0,-0.3365,0,0,0,-0.6493,0,0,0,0,0,0.4159,0,0,0,0,0,-1.0513,0,0,0,0,0,0.7576,0,0,0,0,0,-0.16,0,0,0,0,0,0,0,0,0,0,0,-0.16,0,0,0,0,0,0,0,0,0,0,0,-0.1624,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C2_HIT = key("0.26,0.22,0,0,5.8952,-5.5,0.12,0.12,0,0,0,0,0.16,0.18,-0.05,0,0,0,-0.38,-0.42,0,0,0,0,-0.0942,0,0.0982,0,0,0,0,0,0,0,0,0,-0.255,1.3625,-2.9112,0,0,0,-2.2519,0,0,0,0,0,-2.2294,0.3648,1.0382,0,0,0,1.5764,0.5111,1.0543,0,0,0,-0.6752,0,0,0,0,0,0.5022,-0.5087,-2.1534,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.9618,0.1819,0.0953,0,0,0,1.3923,0,0,0,0,0,-0.6638,0,-0.1747,0,0,0,-0.9248,-0.1786,-0.0112,0,0,0,1.4147,0,0,0,0,0,-1.0426,0,0.0765,0,0,0,-1.0818,0,0.111,0,0,0,0.6973,0,-0.1096,0,0,0,-1.0448,0,-0.0395,0,0,0,0.6669,0,0.0378,0,0,0,-0.26,0,0.111,0,0,0,-0.0013,0,-0.0751,0,0,0,-0.26,0,-0.0395,0,0,0,-0.0002,0,0.0267,0,0,0,-0.2638,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C2_FOLLOW = key("0.26,0.78,0,0,6.2633,-5.5,0.12,0.32,0,0,0,0,0.16,0.4,-0.08,0,0,0,-0.38,-1.35,0,0,0,0,0.0738,0,0.2671,0,0,0,0,0,0,0,0,0,-0.3519,0.2525,0.9072,0,0,0,-2.2028,0,0,0,0,0,-2.7979,-0.1498,-1.3222,0,0,0,-0.9,0,0.3,0,0,0,-1.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.1233,0.1787,0.0119,0,0,0,1.4318,0,0,0,0,0,-0.5615,0,-0.0674,0,0,0,-0.8513,-0.1778,0.0068,0,0,0,1.4609,0,0,0,0,0,-1.2659,0,0.0602,0,0,0,-1.2433,0,0.1402,0,0,0,0.8337,0,-0.1681,0,0,0,-0.9713,0,-0.1242,0,0,0,0.603,0,0.1104,0,0,0,-0.26,0,0.1402,0,0,0,-0.0021,0,-0.0948,0,0,0,-0.26,0,-0.1242,0,0,0,-0.0016,0,0.084,0,0,0,-0.2638,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C3_WIND = key("0.14,0.72,0,0,6.5,4,0.04,0.26,0,0,0,0,0.02,0.28,0,0,0,0,-0.1,-1.2,0,0,0,0,-0.0302,0,0.0815,0,0,0,0,0,0,0,0,0,0.3702,0.4396,-0.3183,0,0,0,-1.957,0,0,0,0,0,-1.7312,0.037,1.37,0,0,0,1.7121,-1.4483,-2.2676,0,0,0,-2.2861,0,0,0,0,0,1.3747,-0.4483,-0.1187,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.8939,0.1816,0.0857,0,0,0,1.391,0,0,0,0,0,-0.6158,0,-0.1334,0,0,0,-0.9604,-0.1842,-0.1998,0,0,0,1.2826,0,0,0,0,0,-0.4152,0,0.2414,0,0,0,-1.0139,0,0.1029,0,0,0,0.7422,0,-0.1105,0,0,0,-1.0804,0,-0.1998,0,0,0,0.7969,0,0.2286,0,0,0,-0.14,0,0.1029,0,0,0,-0.0006,0,-0.0713,0,0,0,-0.14,0,-0.1998,0,0,0,-0.0024,0,0.1385,0,0,0,-0.1421,0,0.1745,0,0,0", intArrayOf(12, 14))
        val C3_HIT = key("0.4,-0.3,0,0,9.1326,-8,0.2,-0.16,0,0,0,0,0.16,-0.16,0,0,0,0,-0.7,0.45,0,0,0,0,-0.0283,0,-0.0934,0,0,0,0,0,0,0,0,0,0.4012,0.9006,2.1191,0,0,0,-2.425,0,0,0,0,0,-2.9484,0.3161,1.0324,0,0,0,1.0028,0.0251,-0.7423,0,0,0,-0.897,0,0,0,0,0,0.3912,0.0011,0.2436,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.4634,0.1732,-0.0877,0,0,0,0.077,0,0,0,0,0,-1.8492,0,0.0213,0,0,0,-1.6597,-0.1422,0.4268,0,0,0,1.4303,0,0,0,0,0,-0.2396,0,-0.3382,0,0,0,-0.15,0,0,0,0,0,-0.2125,0,0,0,0,0,-1.5297,0,0,0,0,0,0.9603,0,0,0,0,0,0.8334,0,0,0,0,0,-1.0484,0,0,0,0,0,-0.15,0,0,0,0,0,-0.2125,0,0,0,0,0,-0.4055,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C3_HOLD = key("0.45,-0.24,0,0,9.7043,-8,0.24,-0.12,0,0,0,0,0.2,-0.12,0,0,0,0,-0.55,0.38,0,0,0,0,-0.2269,0,-0.1047,0,0,0,0,0,0,0,0,0,-0.6451,0.6753,1.2392,0,0,0,-2.7312,0,0,0,0,0,-2.7328,-0.2146,0.1768,0,0,0,0.9459,0.257,-0.3176,0,0,0,-0.7926,0,0,0,0,0,0.0633,-0.266,-0.0442,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.4297,0.1761,-0.0386,0,0,0,0.1148,0,0,0,0,0,-1.8928,0,-0.0769,0,0,0,-1.7895,-0.1408,0.4386,0,0,0,1.5305,0,0,0,0,0,-0.2624,0,-0.3351,0,0,0,-0.45,0,0,0,0,0,0,0,0,0,0,0,-1.9095,0,0,0,0,0,1.2406,0,0,0,0,0,0.5497,0,0,0,0,0,-0.8497,0,0,0,0,0,-0.45,0,0,0,0,0,0,0,0,0,0,0,-0.456,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C4_WIND = key("0.28,-1.05,0,0,8,1,0.1,-0.35,0,0,0,0,0.08,-0.35,0.05,0,0,0,-0.1,1.1,0,0,0,0,-0.1715,0,-0.17,0,0,0,0,0,0,0,0,0,0.8224,-0.9978,-0.4486,0,0,0,-2.2376,0,0,0,0,0,1.8731,-1.2417,-0.0107,0,0,0,0.8588,0.3721,1.6224,0,0,0,-0.0874,0,0,0,0,0,-1.1322,-0.6564,-1.868,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.7872,0.1821,0.1025,0,0,0,1.5195,0,0,0,0,0,-0.9756,0,-0.2648,0,0,0,-1.1666,-0.1808,-0.0644,0,0,0,1.5411,0,0,0,0,0,-0.6354,0,0.1378,0,0,0,-0.8072,0,0.3171,0,0,0,0.4363,0,-0.2422,0,0,0,-1.1866,0,-0.2848,0,0,0,0.7612,0,0.2952,0,0,0,-0.18,0,0.3171,0,0,0,-0.0968,0,-0.2143,0,0,0,-0.18,0,-0.2848,0,0,0,-0.0944,0,0.1925,0,0,0,-0.2841,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C4_HIT = key("0.28,0.85,0,0,8.5,-1,0.08,0.3,0,0,0,0,0.06,0.3,-0.08,0,0,0,-0.1,-1,0,0,0,0,-0.1469,0,0.1618,0,0,0,0,0,0,0,0,0,-1.0348,-0.5055,-2.4852,0,0,0,-2.2689,0,0,0,0,0,2.6745,0.4355,-0.0184,0,0,0,2.7693,-0.1613,2.6395,0,0,0,-2.08,0,0,0,0,0,-1.3974,-0.0612,2.9632,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.2752,0.1774,-0.0141,0,0,0,1.5716,0,0,0,0,0,-0.5742,0,-0.0422,0,0,0,-0.6179,-0.17,0.1344,0,0,0,1.4723,0,0,0,0,0,-1.1468,0,-0.196,0,0,0,-1.1952,0,0.368,0,0,0,0.7618,0,-0.3731,0,0,0,-0.5379,0,-0.35,0,0,0,0.2047,0,0.2415,0,0,0,-0.08,0,0.368,0,0,0,-0.1861,0,-0.2527,0,0,0,-0.08,0,-0.35,0,0,0,-0.1845,0,0.2403,0,0,0,-0.2841,0,0.1745,0,0,0", intArrayOf(13, 14))
        val C4_FOLLOW = key("0.3,1.25,0,0,9,-1,0.1,0.35,0,0,0,0,0.08,0.32,-0.08,0,0,0,-0.15,-1.2,0,0,0,0,-0.1097,0,0.2087,0,0,0,0,0,0,0,0,0,-1.4292,-0.0641,-2.1964,0,0,0,-2.1462,0,0,0,0,0,2.3744,0.418,-0.0004,0,0,0,2.1392,0.3165,1.7598,0,0,0,-1.4187,0,0,0,0,0,-1.2393,-0.1679,-2.5069,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3748,0.1683,-0.1572,0,0,0,1.5934,0,0,0,0,0,-0.5392,0,0.1144,0,0,0,-0.672,-0.1575,0.2833,0,0,0,1.4853,0,0,0,0,0,-1.1101,0,-0.5108,0,0,0,-1.3948,0,0.25,0,0,0,0.9229,0,-0.3366,0,0,0,-0.692,0,-0.25,0,0,0,0.3255,0,0.1795,0,0,0,-0.2,0,0.25,0,0,0,-0.0927,0,-0.168,0,0,0,-0.2,0,-0.25,0,0,0,-0.0927,0,0.168,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val LUNGE_CROUCH = key("0.6,0.2,0,0,9.2475,2,0.3,0,0,0,0,0,0.24,0.08,0,0,0,0,-0.95,0,0,0,0,0,0.2681,0,-0.0383,0,0,0,0,0,0,0,0,0,-2.6089,0.1349,-2.836,0,0,0,-0.7432,0,0,0,0,0,1.6351,-0.0638,-0.0042,0,0,0,-0.1608,-0.1337,0.0702,0,0,0,-2.1727,0,0,0,0,0,1.1946,-0.0761,-0.1102,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.1977,0.1824,0.1121,0,0,0,1.7595,0,0,0,0,0,-1.9032,0,-0.4431,0,0,0,-1.8116,-0.177,0.0212,0,0,0,1.6681,0,0,0,0,0,-0.4502,0,0.0915,0,0,0,-0.9677,0,0.1253,0,0,0,0.3094,0,-0.0774,0,0,0,-1.5816,0,-0.012,0,0,0,0.8343,0,0.0125,0,0,0,-0.25,0,0.1253,0,0,0,-0.3006,0,-0.0771,0,0,0,-0.25,0,-0.012,0,0,0,-0.2975,0,0.0074,0,0,0,-0.6072,0,0.1745,0,0,0", intArrayOf(12, 14))
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
        val PK_TURN = key("0.06,-0.95,0.12,0,3.5631,-0.5,-0.05,-0.15,0.1,0,0,0,-0.05,-0.1,0.1,0,0,0,0,1.1,-0.1,0,0,0,0.0464,0,-0.092,0,0,0,0,0,0,0,0,0,0.7237,-0.2599,-0.9701,0,0,0,-0.0874,0,0,0,0,0,0.7204,-1.4094,0.5445,0,0,0,0.5307,0.4488,1.3804,0,0,0,-1.3898,0,0,0,0,0,-2.7707,0.9398,-2.2246,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.5,0,0.25,0,0,0,2.1,0,0,0,0,0,-0.719,0,-0.4264,0,0,0,-0.7668,-0.1737,0.08,0,0,0,1.0996,0,0,0,0,0,-0.7111,0,-0.1366,0,0,0,-1.62,0,0.5425,0,0,0,1.2141,0,-0.9576,0,0,0,-0.8868,0,-0.3706,0,0,0,0.6139,0,0.2704,0,0,0,-0.1578,0,0.5425,0,0,0,-0.0288,0,-0.4233,0,0,0,-0.1578,0,-0.3706,0,0,0,-0.0058,0,0.2073,0,0,0,-0.1582,0,0.104,0,0,0", intArrayOf(13, 14))
        val PK_KICK = key("-0.08,-1.6,0.48,0,0.4748,-1,-0.08,-0.14,0.34,0,0,0,-0.04,-0.08,0.24,0,0,0,0.15,1.5,-0.55,0,0,0,-0.186,0,-0.2216,0,0,0,0,0,0,0,0,0,0.2444,-0.7833,-1.0111,0,0,0,-1.017,0,0,0,0,0,1.6172,-1.0874,-0.005,0,0,0,1.1312,0.4608,1.3349,0,0,0,-1.2589,0,0,0,0,0,-2.5697,0.3506,-2.1059,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.25,0,1.62,0,0,0,0.05,0,0,0,0,0,-0.3982,0,-1.5207,0,0,0,-0.7017,-0.1711,0.1187,0,0,0,0.5296,0,0,0,0,0,-0.4893,0,-0.0288,0,0,0,-0.3998,0,1.957,0,0,0,-1.4987,0,-1.2259,0,0,0,-0.8217,0,-0.5354,0,0,0,0.4073,0,0.3717,0,0,0,-0.13,0,1.957,0,0,0,-1.728,0,-1.3212,0,0,0,-0.3998,0,-0.5354,0,0,0,0.0487,0,0.3522,0,0,0,-0.4063,0,0.1892,0,0,0", intArrayOf(13, 14))
        val GRIP_AIM = key("0.08,0.8,0,0,4.5,1,0.02,0.3,0,0,0,0,-0.04,0.32,-0.06,0,0,0,0.02,-1.35,0,0,0,0,-0.0209,0,0.0374,0,0,0,0,0,0,0,0,0,-2.1478,-0.9885,2.1488,0,0,0,-0.0874,0,0,0,0,0,0.9937,0.9202,-0.294,0,0,0,0.3311,-0.7761,-1.4518,0,0,0,-0.9802,0,0,0,0,0,0.6449,-0.7463,-0.4551,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6678,0.1837,0.1643,0,0,0,1.1126,0,0,0,0,0,-0.4861,0,-0.1991,0,0,0,-0.7042,-0.1843,-0.2018,0,0,0,1.0621,0,0,0,0,0,-0.3914,0,0.2303,0,0,0,-0.7878,0,0.1696,0,0,0,0.6007,0,-0.1546,0,0,0,-0.8242,0,-0.2015,0,0,0,0.6311,0,0.1887,0,0,0,-0.08,0,0.1696,0,0,0,-0.001,0,-0.1184,0,0,0,-0.08,0,-0.2015,0,0,0,-0.0014,0,0.1406,0,0,0,-0.0812,0,0.1745,0,0,0", intArrayOf(12, 13))
        val GRIP_SNAP = key("0.14,0.8,0,0,5,-1.5,0.06,0.3,0,0,0,0,0.06,0.34,-0.06,0,0,0,-0.06,-1.38,0,0,0,0,-0.0253,0,0.1408,0,0,0,0,0,0,0,0,0,-1.2326,-1.0019,1.2709,0,0,0,-0.2185,0,0,0,0,0,1.0456,0.4359,-0.1832,0,0,0,0.4109,-0.9511,-1.8314,0,0,0,-0.8913,0,0,0,0,0,0.8467,-0.7958,-0.4468,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.655,0.1846,0.2495,0,0,0,1.1278,0,0,0,0,0,-0.5458,0,-0.3165,0,0,0,-0.7551,-0.1829,-0.1311,0,0,0,1.1975,0,0,0,0,0,-0.5504,0,0.1801,0,0,0,-0.775,0,0.2421,0,0,0,0.5362,0,-0.2057,0,0,0,-0.8751,0,-0.1414,0,0,0,0.6237,0,0.1313,0,0,0,-0.14,0,0.2421,0,0,0,-0.0035,0,-0.1678,0,0,0,-0.14,0,-0.1414,0,0,0,-0.0012,0,0.098,0,0,0,-0.1421,0,0.1745,0,0,0", intArrayOf(12, 13))
        val POMMEL_RAISE = key("0.1,0.3,0,0,2.5,-3,-0.05,0.1,0,0,0,0,-0.08,0.1,0,0,0,0,-0.05,-0.4,0,0,0,0,0.0514,0,-0.0157,0,0,0,0,0,0,0,0,0,-0.7936,-1.2887,-1.0358,0,0,0,-2.0122,0,0,0,0,0,0.2281,-0.4849,-0.6088,0,0,0,0.3727,-0.4217,2.8443,0,0,0,-1.52,0,0,0,0,0,-0.2431,-1.4647,-0.2553,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3091,0.1834,0.1518,0,0,0,0.7641,0,0,0,0,0,-0.5187,0,-0.1925,0,0,0,-0.6653,-0.1796,-0.0342,0,0,0,0.7621,0,0,0,0,0,-0.1889,0,0.0523,0,0,0,-0.4291,0,0.159,0,0,0,0.2787,0,-0.1169,0,0,0,-0.7853,0,-0.0591,0,0,0,0.5824,0,0.0531,0,0,0,-0.1,0,0.159,0,0,0,-0.0011,0,-0.1107,0,0,0,-0.1,0,-0.0591,0,0,0,-0.0001,0,0.0412,0,0,0,-0.1015,0,0.1745,0,0,0", intArrayOf(13, 14))
        val POMMEL_HIT = key("0.25,0.1,0,0,3.1281,-6,0.2,0,0,0,0,0,0.15,0.05,0,0,0,0,-0.3,-0.15,0,0,0,0,-0.2079,0,0.0394,0,0,0,0,0,0,0,0,0,0.4785,-0.5051,-0.9293,0,0,0,-2.3923,0,0,0,0,0,-0.8299,-0.8653,-0.278,0,0,0,-0.2814,0.5604,0.1666,0,0,0,-2.2376,0,0,0,0,0,-0.3501,1.0001,0.233,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1851,0.1835,0.1552,0,0,0,0.7205,0,0,0,0,0,-1.2397,0,-0.2636,0,0,0,-0.9996,-0.1756,0.0472,0,0,0,0.9564,0,0,0,0,0,-0.2111,0,-0.0018,0,0,0,-0.3051,0,0.162,0,0,0,0.0441,0,-0.11,0,0,0,-1.1196,0,0,0,0,0,0.7391,0,0,0,0,0,-0.0651,0,0.162,0,0,0,-0.1599,0,-0.1117,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,-0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STAB_RAISE = key("-0.08,0.1,0,0,-0.8116,1,-0.14,0,0,0,0,0,-0.2,0,0,0,0,0,0.12,0,0,0,0,0,0.11,0,0,0,0,0,0,0,0,0,0,0,-0.9758,0.57,2.881,0,0,0,-0.0874,0,0,0,0,0,-0.7381,-0.6264,-0.5504,0,0,0,-0.7611,-0.2482,3.07,0,0,0,-0.0874,0,0,0,0,0,-0.7949,-0.7729,-0.4384,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0477,0.1811,0.0701,0,0,0,0.077,0,0,0,0,0,-0.5332,0,-0.0543,0,0,0,-0.0886,-0.1791,-0.0222,0,0,0,0.077,0,0,0,0,0,-0.4058,0,0.0075,0,0,0,-0.0723,0,0.0896,0,0,0,0.1297,0,-0.0632,0,0,0,-0.2086,0,-0.0488,0,0,0,0.2454,0,0.0355,0,0,0,0.1677,0,0.0896,0,0,0,-0.0743,0,-0.0627,0,0,0,0.08,0,-0.0488,0,0,0,0.0001,0,0.0341,0,0,0,0.0812,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STAB_HIT = key("0.2,0.1,0,0,12.5,-1.5,0.3,0,0,0,0,0,0.3,0,0,0,0,0,0.1,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,-1.655,0.67,2.4603,0,0,0,-2.1518,0,0,0,0,0,3.0082,0.9161,0.0026,0,0,0,0.7987,-0.077,1.2834,0,0,0,-2.7237,0,0,0,0,0,2.0276,0.0165,-0.6131,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.15,0,0.08,0,0,0,1.55,0,0,0,0,0,-1.2321,0,-2.9036,0,0,0,-1.4556,-0.1785,-0.008,0,0,0,1.9247,0,0,0,0,0,-0.664,0,0.0547,0,0,0,-0.1,0,0.348,0,0,0,-0.0955,0,-0.2399,0,0,0,-1.4756,0,-0.2868,0,0,0,1.0772,0,0.5375,0,0,0,0.37,0,0.348,0,0,0,-0.495,0,-0.2811,0,0,0,-0.1,0,-0.2868,0,0,0,-0.092,0,0.1978,0,0,0,-0.203,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STAB_STUCK = key("0.22,0.1,0,0,12,-1,0.3,0,0,0,0,0,0.25,0,0,0,0,0,0.35,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,-1.5733,0.6699,2.6335,0,0,0,-2.0332,0,0,0,0,0,2.8318,0.8494,0.003,0,0,0,0.4549,-0.534,1.453,0,0,0,-2.6322,0,0,0,0,0,2.232,0.0056,-0.1802,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.15,0,0.08,0,0,0,1.55,0,0,0,0,0,-1.2127,0,-2.9172,0,0,0,-1.4689,-0.1781,-0.0004,0,0,0,1.866,0,0,0,0,0,-0.6132,0,0.0477,0,0,0,-0.22,0,0.098,0,0,0,-0.0009,0,-0.0669,0,0,0,-1.5889,0,-0.0303,0,0,0,1.1635,0,0.1025,0,0,0,0.27,0,0.098,0,0,0,-0.4174,0,-0.0758,0,0,0,-0.22,0,-0.0303,0,0,0,-0.0001,0,0.0207,0,0,0,-0.2233,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STAB_PULL = key("0.15,0.1,0,0,4,1,0.05,0,0,0,0,0,-0.05,0,0,0,0,0,0.1,0,0,0,0,0,-0.175,0,0,0,0,0,0,0,0,0,0,0,0.1929,0.164,-1.8752,0,0,0,-2.0475,0,0,0,0,0,0.7121,1.3589,0.2414,0,0,0,0.2741,0.1835,2.1275,0,0,0,-2.1324,0,0,0,0,0,0.1188,-1.0746,0.1375,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6026,0.1814,0.0793,0,0,0,1.0814,0,0,0,0,0,-0.609,0,-0.1271,0,0,0,-0.8941,-0.1778,0.0052,0,0,0,0.9538,0,0,0,0,0,-0.2083,0,0.0219,0,0,0,-0.7226,0,0.0974,0,0,0,0.4861,0,-0.0801,0,0,0,-1.0141,0,-0.0256,0,0,0,0.7344,0,0.0273,0,0,0,-0.15,0,0.0974,0,0,0,-0.0006,0,-0.0674,0,0,0,-0.15,0,-0.0256,0,0,0,0,0,0.0177,0,0,0,-0.1523,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STEP_R = key("0.05,0.2,0.1,-1.5,2.1281,0,0,-0.05,-0.1,0,0,0,0,-0.05,-0.06,0,0,0,0,-0.1,0.15,0,0,0,-0.0245,0,-0.0492,0,0,0,0,0,0,0,0,0,-0.1245,-0.2447,0.3422,0,0,0,-0.3339,0,0,0,0,0,0.8705,0.4659,-0.0308,0,0,0,0.1,0,-0.45,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4109,0.1832,0.143,0,0,0,0.773,0,0,0,0,0,-0.3371,0,-0.2572,0,0,0,-0.5699,-0.1833,-0.1484,0,0,0,0.9728,0,0,0,0,0,-0.9225,0,0.0602,0,0,0,-0.5309,0,0.4515,0,0,0,0.4214,0,-0.4256,0,0,0,-0.6899,0,-0.4561,0,0,0,0.5593,0,0.3091,0,0,0,-0.0301,0,0.4515,0,0,0,-0.0043,0,-0.3845,0,0,0,-0.0301,0,-0.4561,0,0,0,-0.0016,0,0.2505,0,0,0,-0.03,0,0.0765,0,0,0", intArrayOf(13, 14))
        val STEP_R_LAND = key("0.07,0.22,0,0,2.5,0,0.03,-0.06,0.03,0,0,0,0.03,-0.06,0,0,0,0,0,-0.1,0,0,0,0,-0.094,0,-0.0038,0,0,0,0,0,0,0,0,0,0.0836,-0.0529,0.0788,0,0,0,-0.7752,0,0,0,0,0,1.0264,0.1553,0.0787,0,0,0,0.1,0,-0.2,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.437,0.1823,0.108,0,0,0,0.8364,0,0,0,0,0,-0.4457,0,-0.1317,0,0,0,-0.58,-0.1797,-0.0365,0,0,0,0.8321,0,0,0,0,0,-0.3141,0,0.0509,0,0,0,-0.557,0,0.1218,0,0,0,0.4135,0,-0.0961,0,0,0,-0.7,0,-0.0611,0,0,0,0.5354,0,0.0527,0,0,0,-0.07,0,0.1218,0,0,0,-0.0004,0,-0.0851,0,0,0,-0.07,0,-0.0611,0,0,0,-0.0001,0,0.0426,0,0,0,-0.0711,0,0.1745,0,0,0", intArrayOf(13, 14))
        val STEP_L = key("0.05,0.2,-0.1,1.5,2.1281,0,0,-0.05,0.1,0,0,0,0,-0.05,0.06,0,0,0,0,-0.1,-0.15,0,0,0,-0.044,0,0.0612,0,0,0,0,0,0,0,0,0,-0.0571,-0.0931,0.0353,0,0,0,-0.5352,0,0,0,0,0,0.9913,0.2498,0.1114,0,0,0,0.1,0,-0.2,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.5131,0.1841,0.1909,0,0,0,0.9711,0,0,0,0,0,-1.0068,0,-0.1187,0,0,0,-0.4961,-0.1821,-0.1003,0,0,0,0.8044,0,0,0,0,0,-0.3343,0,0.2199,0,0,0,-0.6331,0,-0.1077,0,0,0,0.4777,0,0.169,0,0,0,-0.6161,0,0.1848,0,0,0,0.4643,0,-0.0706,0,0,0,-0.0699,0,-0.1077,0,0,0,-0.001,0,0.1438,0,0,0,-0.0699,0,0.1848,0,0,0,0.0001,0,-0.0604,0,0,0,-0.0723,0,0.2728,0,0,0", intArrayOf(13, 14))
        val STEP_L_LAND = key("0.07,0.22,0,0,2.5,0,0.03,-0.06,-0.03,0,0,0,0.03,-0.06,0,0,0,0,0,-0.1,0,0,0,0,-0.0849,0,0.0316,0,0,0,0,0,0,0,0,0,0.126,0.0563,0.0603,0,0,0,-0.8724,0,0,0,0,0,1.0785,0.0198,0.0721,0,0,0,0.1,0,-0.2,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.437,0.1823,0.108,0,0,0,0.8364,0,0,0,0,0,-0.4457,0,-0.1317,0,0,0,-0.58,-0.1797,-0.0365,0,0,0,0.8321,0,0,0,0,0,-0.3141,0,0.0509,0,0,0,-0.557,0,0.1218,0,0,0,0.4135,0,-0.0961,0,0,0,-0.7,0,-0.0611,0,0,0,0.5354,0,0.0527,0,0,0,-0.07,0,0.1218,0,0,0,-0.0004,0,-0.0851,0,0,0,-0.07,0,-0.0611,0,0,0,-0.0001,0,0.0426,0,0,0,-0.0711,0,0.1745,0,0,0", intArrayOf(13, 14))
        val ARROW_WIND = key("0.04,0.22,0,0,1,0,0.03,-0.08,0,0,0,0,0,0.25,-0.05,0,0,0,0,-0.3,0,0,0,0,-0.0471,0,0.034,0,0,0,0,0,0,0,0,0,-0.1191,0.5488,2.764,0,0,0,-2.1529,0,0,0,0,0,2.9475,-0.0674,0.0023,0,0,0,0.1,0,-0.14,0,0,0,-0.32,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2492,0.1816,0.0852,0,0,0,0.514,0,0,0,0,0,-0.2877,0,-0.0949,0,0,0,-0.3847,-0.179,-0.0193,0,0,0,0.4999,0,0,0,0,0,-0.151,0,0.0264,0,0,0,-0.3692,0,0.1024,0,0,0,0.2796,0,-0.0756,0,0,0,-0.5047,0,-0.0464,0,0,0,0.395,0,0.0363,0,0,0,-0.04,0,0.1024,0,0,0,-0.0002,0,-0.0716,0,0,0,-0.04,0,-0.0464,0,0,0,0,0,0.0325,0,0,0,-0.0406,0,0.1745,0,0,0", intArrayOf(13, 14))
        val ARROW_HIT = key("0.04,0.22,0,0,1,0,0.03,-0.08,0,0,0,0,0.05,-0.3,0.05,0,0,0,0,0.2,0,0,0,0,-0.0863,0,-0.0298,0,0,0,0,0,0,0,0,0,-1.011,-0.7705,1.0657,0,0,0,-1.4439,0,0,0,0,0,2.1572,-0.1139,-0.0202,0,0,0,0.2,0,-0.3,0,0,0,-0.32,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2492,0.1816,0.0852,0,0,0,0.514,0,0,0,0,0,-0.2877,0,-0.0949,0,0,0,-0.3847,-0.179,-0.0193,0,0,0,0.4999,0,0,0,0,0,-0.151,0,0.0264,0,0,0,-0.3692,0,0.1024,0,0,0,0.2796,0,-0.0756,0,0,0,-0.5047,0,-0.0464,0,0,0,0.395,0,0.0363,0,0,0,-0.04,0,0.1024,0,0,0,-0.0002,0,-0.0716,0,0,0,-0.04,0,-0.0464,0,0,0,0,0,0.0325,0,0,0,-0.0406,0,0.1745,0,0,0", intArrayOf(13, 14))
        val CUT_CHARGE = key("0.12,0.85,0,0,7.8952,3,0,0.34,0,0,0,0,-0.1,0.4,-0.1,0,0,0,-0.1,-1.5,0.08,0,0,0,0.0203,0,0.0135,0,0,0,0,0,0,0,0,0,-0.7949,-1.0377,-1.4981,0,0,0,-1.6306,0,0,0,0,0,1.6884,0.1064,-0.028,0,0,0,-1.9851,-0.4837,-0.9241,0,0,0,-1.6005,0,0,0,0,0,2.4143,0.0021,-0.0082,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.986,0.1845,0.2231,0,0,0,1.5481,0,0,0,0,0,-0.9205,0,-0.2968,0,0,0,-0.9853,-0.1833,-0.3946,0,0,0,1.3623,0,0,0,0,0,-0.3921,0,0.4442,0,0,0,-1.006,0,0.4696,0,0,0,0.7409,0,-0.4643,0,0,0,-1.0053,0,-0.6154,0,0,0,0.7298,0,0.5736,0,0,0,-0.02,0,0.4696,0,0,0,-0.0972,0,-0.328,0,0,0,-0.02,0,-0.6154,0,0,0,-0.1076,0,0.4299,0,0,0,-0.1218,0,0.1745,0,0,0", intArrayOf(13, 14))
        val CUT_TOP = key("-0.02,0.25,0,0,4,1.5,-0.14,0.06,0,0,0,0,-0.2,0.06,0,0,0,0,-0.1,-0.2,0,0,0,0,0.3185,0,-0.0397,0,0,0,0,0,0,0,0,0,-1.1023,-0.9868,-1.7588,0,0,0,-0.0874,0,0,0,0,0,-0.207,-1.2836,0.4715,0,0,0,-1.3242,-0.881,-1.8856,0,0,0,-0.564,0,0,0,0,0,0.2515,-1.557,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4418,0.1832,0.1414,0,0,0,1.0718,0,0,0,0,0,-0.5782,0,-0.1618,0,0,0,-0.7072,-0.1813,-0.0758,0,0,0,0.9466,0,0,0,0,0,-0.2055,0,0.0725,0,0,0,-0.5618,0,0.1502,0,0,0,0.4947,0,-0.1255,0,0,0,-0.8272,0,-0.0944,0,0,0,0.7202,0,0.0995,0,0,0,0.02,0,0.1502,0,0,0,0.0002,0,-0.1051,0,0,0,0.02,0,-0.0944,0,0,0,0.0001,0,0.0661,0,0,0,0.0203,0,0.1745,0,0,0", intArrayOf(13, 14))
        val CUT_STRIKE = key("0.3,0,0,0,7.8331,-5,0.2,0,0,0,0,0,0.22,0,0,0,0,0,-0.45,0,0,0,0,0,-0.189,0,0,0,0,0,0,0,0,0,0,0,-2.1509,-1.0836,1.0116,0,0,0,-2.4734,0,0,0,0,0,-2.2307,-1.0879,-1.0065,0,0,0,1.57,0.0434,1.8424,0,0,0,-2.429,0,0,0,0,0,0.5069,1.0248,-0.4206,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2042,0.1841,0.1929,0,0,0,1.1504,0,0,0,0,0,-1.8455,0,-0.6035,0,0,0,-1.4467,-0.1745,0.0666,0,0,0,1.5029,0,0,0,0,0,-0.3631,0,-0.0121,0,0,0,-0.1242,0,0.194,0,0,0,-0.154,0,-0.1317,0,0,0,-1.3667,0,0,0,0,0,0.9067,0,0,0,0,0,0.1158,0,0.194,0,0,0,-0.358,0,-0.1417,0,0,0,-0.1,0,0,0,0,0,-0.17,0,0,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val CUT_LOW = key("0.42,0,0,0,11.7206,-6,0.3,0,0,0,0,0,0.3,0,0,0,0,0,-0.1,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,0.427,-0.2209,-0.6534,0,0,0,-2.2439,0,0,0,0,0,1.5343,-0.6841,0.0265,0,0,0,0.7388,0.7077,0.4759,0,0,0,-2.1128,0,0,0,0,0,0.4995,-1.0232,0.4157,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1523,0.1845,0.2263,0,0,0,1.2622,0,0,0,0,0,-2.1318,0,-1.3212,0,0,0,-1.8351,-0.1722,0.1021,0,0,0,1.8586,0,0,0,0,0,-0.4543,0,-0.0243,0,0,0,-0.17,0,0.2223,0,0,0,-0.2205,0,-0.1467,0,0,0,-1.7051,0,0,0,0,0,1.0923,0,0,0,0,0,0.2177,0,0.2223,0,0,0,-0.55,0,-0.1765,0,0,0,-0.17,0,0,0,0,0,-0.2125,0,0,0,0,0,-0.4257,0,0.1745,0,0,0", intArrayOf(13, 14))
        val GK_PARRY = key("0.05,0.4,0,0,2.5,1,-0.05,0.1,0,0,0,0,-0.08,0.12,0,0,0,0,0,-0.5,0,0,0,0,0.048,0,-0.0259,0,0,0,0,0,0,0,0,0,-0.435,-0.6774,-0.7784,0,0,0,-1.7711,0,0,0,0,0,1.2905,0.1558,0.1477,0,0,0,0.6978,0.1741,-0.48,0,0,0,-2.5072,0,0,0,0,0,-0.3921,-0.134,-0.2114,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.4124,0.1827,0.1229,0,0,0,0.8302,0,0,0,0,0,-0.4409,0,-0.1436,0,0,0,-0.5859,-0.181,-0.0685,0,0,0,0.7887,0,0,0,0,0,-0.239,0,0.0785,0,0,0,-0.5324,0,0.1345,0,0,0,0.4097,0,-0.1059,0,0,0,-0.7059,0,-0.0882,0,0,0,0.5573,0,0.0777,0,0,0,-0.05,0,0.1345,0,0,0,-0.0004,0,-0.094,0,0,0,-0.05,0,-0.0882,0,0,0,-0.0002,0,0.0617,0,0,0,-0.0508,0,0.1745,0,0,0", intArrayOf(13, 14))
        val GK_LOAD = key("0.1,0.3,-0.06,0,4.8246,2,0.02,0.08,0,0,0,0,-0.02,0.1,0,0,0,0,-0.12,-0.38,0,0,0,0,0.0107,0,0.0466,0,0,0,0,0,0,0,0,0,-0.5757,-0.9214,-0.5501,0,0,0,-2.5196,0,0,0,0,0,1.8106,-0.1727,0.2107,0,0,0,0.2605,0.1493,-0.522,0,0,0,-2.2652,0,0,0,0,0,-0.196,-0.1392,-0.2284,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.7149,0.1837,0.1656,0,0,0,1.2967,0,0,0,0,0,-1.022,0,-0.1624,0,0,0,-0.9176,-0.1771,0.0198,0,0,0,1.2012,0,0,0,0,0,-0.3922,0,0.0625,0,0,0,-0.8349,0,0.1708,0,0,0,0.6091,0,-0.1039,0,0,0,-1.0376,0,-0.0132,0,0,0,0.7818,0,0.0811,0,0,0,-0.1178,0,0.1708,0,0,0,-0.0005,0,-0.0786,0,0,0,-0.1178,0,-0.0132,0,0,0,-0.0001,0,0.0493,0,0,0,-0.1208,0,0.2322,0,0,0", intArrayOf(13, 14))
        val GK_KNEE = key("-0.2,0.1,0,0,0.5,1.5,-0.1,0,0,0,0,0,-0.06,0.05,0,0,0,0,0.15,-0.1,0,0,0,0,0.1466,0,-0.0125,0,0,0,0,0,0,0,0,0,-2.7847,0.7439,-2.8686,0,0,0,-1.0777,0,0,0,0,0,1.13,0.1825,0.0574,0,0,0,-0.1887,0.2992,-0.2284,0,0,0,-1.5613,0,0,0,0,0,-0.7488,0.32,-0.2464,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-2.1,0,0.1,0,0,0,2.4,0,0,0,0,0,0.3015,0,-0.0985,0,0,0,-0.0244,-0.1796,-0.0333,0,0,0,0.3742,0,0,0,0,0,-0.1471,0,-0.0035,0,0,0,-2.22,0,0.115,0,0,0,2.0581,0,-2.0945,0,0,0,-0.1444,0,-0.0583,0,0,0,0.293,0,0.0425,0,0,0,0.2,0,0.115,0,0,0,0.0011,0,-0.0789,0,0,0,0.2,0,-0.0583,0,0,0,0.0003,0,0.04,0,0,0,0.203,0,0.1745,0,0,0", intArrayOf(13, 14))
        val GK_KICK = key("-0.36,-0.45,0,0,0.0631,0,-0.16,-0.05,0,0,0,0,0,0,0,0,0,0,0.35,0.35,0,0,0,0,0.1039,0,0.0936,0,0,0,0,0,0,0,0,0,2.5522,0.4338,2.5403,0,0,0,-0.6943,0,0,0,0,0,0.541,1.4128,-0.5162,0,0,0,-0.1945,0.3653,-0.0695,0,0,0,-1.6311,0,0,0,0,0,-0.4166,0.6988,-0.1714,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.5,0,0.05,0,0,0,0.1,0,0,0,0,0,1.8756,0,-2.8981,0,0,0,0.2326,-0.1824,-0.1133,0,0,0,0.3705,0,0,0,0,0,-0.4283,0,0.0412,0,0,0,-1.62,0,0.2725,0,0,0,1.6937,0,-1.8027,0,0,0,0.1126,0,-0.3263,0,0,0,0.2258,0,0.2205,0,0,0,0.36,0,0.2725,0,0,0,0.0107,0,-0.1783,0,0,0,0.36,0,-0.3263,0,0,0,0.0155,0,0.2133,0,0,0,0.3651,0,0.1745,0,0,0", intArrayOf(13, 14))
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
        val BT_HIT = key("0.42,0,0,0,11.8331,-3,0.3,0,0,0,0,0,0.3,0,0,0,0,0,-0.35,0,0,0,0,0,-0.05,0,0,0,0,0,0,0,0,0,0,0,-0.1577,-1.1904,-0.2793,0,0,0,-2.581,0,0,0,0,0,2.3033,-1.1921,0.2637,0,0,0,1.1792,0.7524,1.0674,0,0,0,-2.4242,0,0,0,0,0,0.5967,-0.9362,0.4681,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.5874,0.1844,0.2158,0,0,0,1.7079,0,0,0,0,0,-1.9937,0,-1.3513,0,0,0,-1.827,-0.1764,0.0336,0,0,0,1.852,0,0,0,0,0,-0.4439,0,0.0457,0,0,0,-0.4074,0,0.5134,0,0,0,-0.0564,0,-0.3262,0,0,0,-1.647,0,-0.3014,0,0,0,1.0281,0,0.4709,0,0,0,-0.12,0,0.5134,0,0,0,-0.3007,0,-0.3438,0,0,0,-0.12,0,-0.3014,0,0,0,-0.2698,0,0.2017,0,0,0,-0.4257,0,0.1745,0,0,0", intArrayOf(13, 14))
        val BT_LOW = key("0.5,0,0,0,13.7475,-3,0.34,0,0,0,0,0,0.34,0,0,0,0,0,-0.2,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,0.2216,-0.5353,-0.4998,0,0,0,-2.3225,0,0,0,0,0,1.7777,-0.6943,0.1884,0,0,0,0.6639,0.733,0.4903,0,0,0,-2.2167,0,0,0,0,0,0.5231,-1.1503,0.4645,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.5852,0.1846,0.2345,0,0,0,1.7917,0,0,0,0,0,-2.0601,0,-1.9085,0,0,0,-2.0801,-0.1745,0.0662,0,0,0,1.9537,0,0,0,0,0,-0.3768,0,0.0281,0,0,0,-0.5552,0,0.5793,0,0,0,-0.0197,0,-0.3509,0,0,0,-2.0501,0,-0.35,0,0,0,1.2948,0,0.9951,0,0,0,-0.3152,0,0.5793,0,0,0,-0.2237,0,-0.3612,0,0,0,-0.35,0,-0.35,0,0,0,-0.1502,0,0.2171,0,0,0,-0.5065,0,0.1745,0,0,0", intArrayOf(13, 14))
        val IAI_STANCE = key("0.4,-0.32,0,0,9.2633,2.5,0.2,-0.14,0,0,0,0,0.16,-0.2,0,0,0,0,-0.62,0.62,0,0,0,0,-0.0677,0,-0.1533,0,0,0,0,0,0,0,0,0,2.049,0.3418,-2.6572,0,0,0,-2.4324,0,0,0,0,0,-0.3101,1.0404,0.297,0,0,0,-0.3929,0.5422,0.1676,0,0,0,-1.5333,0,0,0,0,0,-0.2207,0.3822,0.219,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.7612,0.1827,0.1211,0,0,0,1.6172,0,0,0,0,0,-1.5898,0,-0.5057,0,0,0,-1.6894,-0.1503,0.355,0,0,0,1.3778,0,0,0,0,0,-0.1478,0,-0.2641,0,0,0,-0.8812,0,0.1329,0,0,0,0.4063,0,-0.0964,0,0,0,-1.8094,0,0,0,0,0,1.198,0,0,0,0,0,-0.4,0,0.1329,0,0,0,-0.0027,0,-0.0857,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,-0.4055,0,0.1745,0,0,0", intArrayOf(12, 14))
        val IAI_MID = key("0.25,0,0,0,6.2633,-1,0.12,0.05,0,0,0,0,0.06,0.1,0,0,0,0,-0.35,0.2,0,0,0,0,-0.0423,0,-0.0781,0,0,0,0,0,0,0,0,0,1.9935,-1.0556,1.3727,0,0,0,-0.5873,0,0,0,0,0,0.9789,-1.1417,-0.2101,0,0,0,1.2893,0.2832,-0.2597,0,0,0,-2.2498,0,0,0,0,0,0.2876,0.0394,-0.3673,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.5364,0.1839,0.1803,0,0,0,1.3032,0,0,0,0,0,-1.3504,0,-0.3807,0,0,0,-1.2533,-0.1773,0.0159,0,0,0,1.2839,0,0,0,0,0,-0.2794,0,0.0297,0,0,0,-0.6564,0,0.1832,0,0,0,0.3419,0,-0.1347,0,0,0,-1.3733,0,-0.0165,0,0,0,0.9547,0,0.0259,0,0,0,-0.25,0,0.1832,0,0,0,-0.0035,0,-0.1242,0,0,0,-0.25,0,-0.0165,0,0,0,0,0,0.0112,0,0,0,-0.2537,0,0.1745,0,0,0", intArrayOf(13, 14))
        val IAI_HIT = key("0.4,-0.5,0,0,10.2206,-9,0.2,-0.24,0,0,0,0,0.16,-0.26,0.06,0,0,0,-0.6,0.72,0,0,0,0,-0.0636,0,-0.1775,0,0,0,0,0,0,0,0,0,3.0493,1.2174,0.5607,0,0,0,-2.7356,0,0,0,0,0,-0.9257,-0.5788,0.6119,0,0,0,0.9492,-0.074,-0.9133,0,0,0,-1.5726,0,0,0,0,0,0.6166,-0.1231,0.2979,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0715,0.1685,-0.1551,0,0,0,1.036,0,0,0,0,0,-2.2213,0,0.3878,0,0,0,-1.7442,-0.1265,0.5509,0,0,0,1.5229,0,0,0,0,0,-0.2672,0,-0.4611,0,0,0,-0.1,0,0.25,0,0,0,-0.2647,0,-0.1688,0,0,0,-1.5642,0,-0.25,0,0,0,0.9798,0,0.3661,0,0,0,0.3485,0,0.25,0,0,0,-0.646,0,-0.2186,0,0,0,-0.1,0,-0.25,0,0,0,-0.2647,0,0.1688,0,0,0,-0.4055,0,0.1745,0,0,0", intArrayOf(13, 14))
        val IAI_END = key("0.42,-0.85,0,0,11.2206,-10,0.22,-0.34,0,0,0,0,0.16,-0.36,0.1,0,0,0,-0.58,1.12,0,0,0,0,-0.0223,0,-0.2918,0,0,0,0,0,0,0,0,0,-1.3497,-1.0976,0.5699,0,0,0,-1.8788,0,0,0,0,0,2.8003,-0.9949,-0.0089,0,0,0,0.4231,-0.1482,-1.0467,0,0,0,-1.4221,0,0,0,0,0,1.2007,-0.1282,0.2574,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0814,0.1369,-0.4709,0,0,0,0.9199,0,0,0,0,0,-2.0004,0,1.0539,0,0,0,-1.6863,-0.0976,0.7481,0,0,0,1.618,0,0,0,0,0,-0.4329,0,-0.6908,0,0,0,-0.22,0,0.35,0,0,0,-0.1902,0,-0.2282,0,0,0,-1.6063,0,-0.35,0,0,0,0.9882,0,0.4851,0,0,0,0.2386,0,0.35,0,0,0,-0.58,0,-0.2812,0,0,0,-0.22,0,-0.35,0,0,0,-0.1902,0,0.2282,0,0,0,-0.4257,0,0.1745,0,0,0", intArrayOf(13, 14))
        val KNEEL = key("0.1,0.1,0,0,12,0,0.15,0,0,0,0,0,0.12,0,0,0,0,0,0.45,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,-0.6019,0.789,-2.7601,0,0,0,-2.0101,0,0,0,0,0,1.6495,1.0527,0.0152,0,0,0,-0.7902,-0.4755,-2.2433,0,0,0,-2.2459,0,0,0,0,0,0.2636,1.5539,-0.3346,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.15,0,0.08,0,0,0,1.55,0,0,0,0,0,-1.3281,0,-2.8044,0,0,0,-1.3886,-0.1788,-0.0155,0,0,0,1.8303,0,0,0,0,0,-0.5371,0,0.0383,0,0,0,-0.1,0,0.298,0,0,0,-0.0039,0,-0.2075,0,0,0,-1.5086,0,-0.2431,0,0,0,1.1948,0,0.688,0,0,0,0.27,0,0.298,0,0,0,-0.3184,0,-0.222,0,0,0,-0.1,0,-0.2431,0,0,0,-0.0026,0,0.1693,0,0,0,-0.1015,0,0.1745,0,0,0", intArrayOf(13, 14))
        val THROW_WIND = key("0.12,0.5,0,0,4,0,0.08,0.2,0,0,0,0,0.06,0.3,-0.06,0,0,0,0.05,-0.8,0,0,0,0,-0.1752,0,0.1037,0,0,0,0,0,0,0,0,0,-1.3735,0.9866,-1.7236,0,0,0,-1.2306,0,0,0,0,0,1.586,0.258,0.0398,0,0,0,0.4395,-0.2762,0.1438,0,0,0,-1.6853,0,0,0,0,0,-0.0982,-1.0551,0.8559,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.623,0.1827,0.1243,0,0,0,1.0689,0,0,0,0,0,-0.5362,0,-0.1669,0,0,0,-0.723,-0.1818,-0.0925,0,0,0,1.0615,0,0,0,0,0,-0.4375,0,0.1237,0,0,0,-0.743,0,0.1357,0,0,0,0.5286,0,-0.1156,0,0,0,-0.843,0,-0.1086,0,0,0,0.614,0,0.1003,0,0,0,-0.12,0,0.1357,0,0,0,-0.0009,0,-0.0943,0,0,0,-0.12,0,-0.1086,0,0,0,-0.0006,0,0.0755,0,0,0,-0.1218,0,0.1745,0,0,0", intArrayOf(12, 14))
        val THROW_HIT = key("-0.05,-0.2,0,0,-0.553,-1,-0.08,-0.1,0,0,0,0,-0.1,-0.15,0.05,0,0,0,-0.5,0.2,0,0,0,0,0.3113,0,-0.0229,0,0,0,0,0,0,0,0,0,2.3257,0.8703,2.5522,0,0,0,-0.0874,0,0,0,0,0,0.4638,1.2182,-0.603,0,0,0,-0.0913,-0.437,-3.0532,0,0,0,-0.693,0,0,0,0,0,0.5155,0.0282,-0.0435,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.157,0.1816,0.0845,0,0,0,0.077,0,0,0,0,0,-0.6689,0,-0.075,0,0,0,-0.1583,-0.1773,0.0165,0,0,0,0.077,0,0,0,0,0,-0.1724,0,-0.0252,0,0,0,0.037,0,0.1018,0,0,0,0.0113,0,-0.0712,0,0,0,-0.2783,0,-0.016,0,0,0,0.2791,0,0.0118,0,0,0,0.277,0,0.1018,0,0,0,-0.1927,0,-0.0731,0,0,0,0.05,0,-0.016,0,0,0,0,0,0.0112,0,0,0,0.0508,0,0.1745,0,0,0", intArrayOf(12, 14))
        val SPLIT_RAISE = key("-0.05,0.1,0,0,-0.5043,1,-0.1,0,0,0,0,0,-0.15,0,0,0,0,0,-0.15,0,0,0,0,0,0.315,0,0,0,0,0,0,0,0,0,0,0,-1.1974,-1.126,-1.284,0,0,0,-0.6864,0,0,0,0,0,0.5841,-1.3401,-0.285,0,0,0,-1.434,-0.9091,-1.6552,0,0,0,-0.7706,0,0,0,0,0,0.0748,-1.5569,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.1085,0.1824,0.1112,0,0,0,0.077,0,0,0,0,0,-0.5153,0,-0.1008,0,0,0,-0.1946,-0.1795,-0.0296,0,0,0,0.077,0,0,0,0,0,0.172,0,0.0205,0,0,0,0.0385,0,0.1245,0,0,0,0.0101,0,-0.0871,0,0,0,-0.2646,0,-0.0552,0,0,0,0.2675,0,0.0406,0,0,0,0.2785,0,0.1245,0,0,0,-0.1939,0,-0.0894,0,0,0,0.1,0,-0.0552,0,0,0,-0.0424,0,0.0386,0,0,0,0.0508,0,0.1745,0,0,0", intArrayOf(13, 14))
        val SPLIT_HIT = key("0.2,0,0,0,3.9604,-4,0.15,0,0,0,0,0,0.12,0,0,0,0,0,-0.25,0,0,0,0,0,-0.154,0,0,0,0,0,0,0,0,0,0,0,-0.8436,-0.811,0.0494,0,0,0,-2.3783,0,0,0,0,0,2.9813,-0.8121,-0.013,0,0,0,0.829,0.1553,1.3388,0,0,0,-2.4208,0,0,0,0,0,0.9767,-1.5164,0.6572,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1343,0.183,0.1338,0,0,0,0.8361,0,0,0,0,0,-1.4604,0,-0.2554,0,0,0,-1.0378,-0.1755,0.0492,0,0,0,1.088,0,0,0,0,0,-0.2556,0,-0.0132,0,0,0,-0.0543,0,0.1437,0,0,0,-0.1256,0,-0.0997,0,0,0,-0.9578,0,0,0,0,0,0.6441,0,0,0,0,0,0.1857,0,0.1437,0,0,0,-0.3296,0,-0.1064,0,0,0,0,0,0,0,0,0,-0.17,0,0,0,0,0,-0.203,0,0.1745,0,0,0", intArrayOf(13, 14))
        val SPLIT_LOW = key("0.35,0,0,0,7.7475,-4,0.25,0,0,0,0,0,0.2,0,0,0,0,0,-0.15,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,0.1083,-0.2727,-0.4454,0,0,0,-1.9731,0,0,0,0,0,1.8168,-0.5038,0.1234,0,0,0,0.419,0.6973,0.2726,0,0,0,-2.0154,0,0,0,0,0,0.4612,-1.5088,0.4337,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.3678,0.1831,0.1396,0,0,0,1.2724,0,0,0,0,0,-1.981,0,-0.5266,0,0,0,-1.4994,-0.1725,0.0982,0,0,0,1.5048,0,0,0,0,0,-0.3669,0,-0.0342,0,0,0,-0.2378,0,0.1487,0,0,0,-0.0984,0,-0.0984,0,0,0,-1.3694,0,0,0,0,0,0.8665,0,0,0,0,0,0.0022,0,0.1487,0,0,0,-0.3024,0,-0.1041,0,0,0,-0.1,0,0,0,0,0,-0.2125,0,0,0,0,0,-0.355,0,0.1745,0,0,0", intArrayOf(13, 14))
        val WHISTLE = key("0.04,0.22,0,0,1,0,0.03,-0.08,0,0,0,0,-0.04,-0.1,0,0,0,0,-0.45,-0.1,0,0,0,0,0.2954,0,0.0086,0,0,0,0,0,0,0,0,0,-0.1448,-0.2784,0.138,0,0,0,-0.1472,0,0,0,0,0,0.7426,0.4464,0.078,0,0,0,-1.0801,-0.3708,-2.5504,0,0,0,-2.3556,0,0,0,0,0,2.6597,1.2206,0.018,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2492,0.1816,0.0852,0,0,0,0.514,0,0,0,0,0,-0.2877,0,-0.0949,0,0,0,-0.3847,-0.179,-0.0193,0,0,0,0.4999,0,0,0,0,0,-0.151,0,0.0264,0,0,0,-0.3692,0,0.1024,0,0,0,0.2796,0,-0.0756,0,0,0,-0.5047,0,-0.0464,0,0,0,0.395,0,0.0363,0,0,0,-0.04,0,0.1024,0,0,0,-0.0002,0,-0.0716,0,0,0,-0.04,0,-0.0464,0,0,0,0,0,0.0325,0,0,0,-0.0406,0,0.1745,0,0,0", intArrayOf(12, 13))
        val SPIN_WIND = key("0.3,-1.12,0,0,9,1,0.12,-0.44,0,0,0,0,0.12,-0.44,0.06,0,0,0,-0.3,1.5,0,0,0,0,0.0122,0,-0.25,0,0,0,0,0,0,0,0,0,1.1554,-1.2363,-0.9351,0,0,0,-2.3047,0,0,0,0,0,1.2399,-1.4773,1.0494,0,0,0,1.1162,0.3676,1.6339,0,0,0,-0.0874,0,0,0,0,0,-0.9495,-0.7527,-2.1788,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.8238,0.1818,0.0931,0,0,0,1.617,0,0,0,0,0,-1.0561,0,-0.2901,0,0,0,-1.26,-0.1804,-0.0523,0,0,0,1.6399,0,0,0,0,0,-0.663,0,0.1299,0,0,0,-0.8438,0,0.3092,0,0,0,0.4504,0,-0.237,0,0,0,-1.28,0,-0.2744,0,0,0,0.8237,0,0.3095,0,0,0,-0.2,0,0.3092,0,0,0,-0.0969,0,-0.2077,0,0,0,-0.2,0,-0.2744,0,0,0,-0.0943,0,0.1844,0,0,0,-0.3043,0,0.1745,0,0,0", intArrayOf(13, 14))
        val SPIN_HIT = key("0.24,3.1416,0,0,7.5,0,0.08,0.12,0,0,0,0,0.08,0.18,0,0,0,0,-0.28,0,0,0,0,0,-0.0761,0,-0.0508,0,0,0,0,0,0,0,0,0,-2.056,-0.8418,-1.9139,0,0,0,-1.8059,0,0,0,0,0,2.9256,-0.1304,-0.0101,0,0,0,2.1875,0.5058,1.0995,0,0,0,-1.6983,0,0,0,0,0,-0.1141,-0.0659,-2.1942,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.9009,0.1833,0.1457,0,0,0,1.4924,0,0,0,0,0,-0.7874,0,-0.2608,0,0,0,-1.0134,-0.1813,-0.0784,0,0,0,1.5089,0,0,0,0,0,-0.7129,0,0.1562,0,0,0,-1.0209,0,0.8538,0,0,0,0.7648,0,-0.6571,0,0,0,-1.1334,0,-0.7966,0,0,0,0.8772,0,0.6608,0,0,0,-0.24,0,0.8538,0,0,0,0.101,0,-0.5774,0,0,0,-0.24,0,-0.7966,0,0,0,0.1178,0,0.539,0,0,0,-0.2436,0,0.1745,0,0,0", intArrayOf(13, 14))
        val SPIN_END = key("0.24,5.7332,0,0,7.5,0,0.08,0.12,0,0,0,0,0.08,0.18,0,0,0,0,-0.28,0,0,0,0,0,-0.0761,0,-0.0508,0,0,0,0,0,0,0,0,0,-1.7997,-1.1126,-2.2849,0,0,0,-1.7967,0,0,0,0,0,2.8899,-0.2038,-0.0151,0,0,0,2.1875,0.5058,1.0995,0,0,0,-1.6983,0,0,0,0,0,-0.1141,-0.0659,-2.1942,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.9009,0.1833,0.1457,0,0,0,1.4924,0,0,0,0,0,-0.7874,0,-0.2608,0,0,0,-1.0134,-0.1813,-0.0784,0,0,0,1.5089,0,0,0,0,0,-0.7129,0,0.1562,0,0,0,-1.0209,0,0.6538,0,0,0,0.7136,0,-0.5411,0,0,0,-1.1334,0,-0.5966,0,0,0,0.8189,0,0.5435,0,0,0,-0.24,0,0.6538,0,0,0,0.0498,0,-0.443,0,0,0,-0.24,0,-0.5966,0,0,0,0.0595,0,0.4045,0,0,0,-0.2436,0,0.1745,0,0,0", intArrayOf(13, 14))
    }
    // </keys>
}

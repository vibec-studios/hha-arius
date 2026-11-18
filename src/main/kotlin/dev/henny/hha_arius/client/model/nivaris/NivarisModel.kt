package dev.henny.hha_arius.client.model.nivaris

import dev.henny.hha_arius.boss.AriusTimings
import dev.henny.hha_arius.boss.NivarisPose
import dev.henny.hha_arius.boss.NivarisShape
import net.minecraft.client.model.Dilation
import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPart
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Zustand für einen Frame. Neben Pose und Leuchten trägt er den fertig berechneten Körper: [NivarisShape.SEGMENTS] + 1
 * Punkte (Kopfgelenk, Gliedgrenzen, Schwanzansatz) relativ zur Render-Position, je Glied ein Rahmen und der Querschnitt.
 * Berechnet wird er in [NivarisRenderer.updateRenderState] ([NivarisBody]), gezeichnet in [NivarisRenderer.render].
 */
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

    /** [dev.henny.hha_arius.boss.NivarisMode]. */
    var mode = 0

    /** Posen-Kanäle, siehe [NivarisModel.FWD] … */
    val p = FloatArray(NivarisModel.COUNT)

    // --- Körper (relativ zur Render-Position der Entity) ---
    val px = FloatArray(POINTS)
    val py = FloatArray(POINTS)
    val pz = FloatArray(POINTS)

    /** Je Glied 9 Werte: Modell-x (links), Modell-y (Bauch, = −oben), Modell-z (nach hinten). */
    val frame = FloatArray(NivarisShape.SEGMENTS * 9)

    /** Kopf: gleiche Spalten wie [frame]. */
    val headFrame = FloatArray(9)

    /** Querschnitt je Glied (Verjüngung × Atmen) und Längenfaktor (Abstand der Punkte / 2,4). */
    val girth = FloatArray(NivarisShape.SEGMENTS)
    val stretch = FloatArray(NivarisShape.SEGMENTS)

    /** Licht je Punkt (Körper reicht weit über den Entity-Block hinaus). */
    val lights = IntArray(POINTS)
    var bodyReady = false

    /** Schleier (Auflösen/Entstehen, siehe [dev.henny.hha_arius.client.fx.DragonVeil]): 0 = ganz da. */
    var veil = 0f

    /** Je Teil (0 = Kopf, 1..40 = Glieder, 41 = Schwanzfächer): Maßstab 0..1 und Helligkeit des Leuchtüberzugs. */
    val shrink = FloatArray(dev.henny.hha_arius.client.fx.DragonVeil.PARTS) { 1f }
    val sheen = FloatArray(dev.henny.hha_arius.client.fx.DragonVeil.PARTS)

    companion object {
        const val POINTS = NivarisShape.SEGMENTS + 1
    }
}

/** Eine Schlüsselhaltung: ein Wert je Kanal (siehe [NivarisModel.FWD] …). */
class NivarisKey(val v: FloatArray)

/**
 * Nivaris als Schlangendrache. Die Teile des Modells (tools/assets/nivaris_model.py → [NivarisModelData]) hängen alle
 * unverdreht unter root und werden einzeln auf den Körper gesetzt (siehe [NivarisRenderer]): `head` (mit `jaw`),
 * `neck`, `segment`, `segment_dorsal`, `segment_heart`, Läufe, Schwingen, `tail_fan`. Fehlt ein Teil (altes Modell,
 * Bau in Arbeit), springt ein schlichter Platzhalter ein.
 *
 * Hier steht der **Ablauf der Posen** als Kanäle (Kopfgelenk vor/hoch, Neigung, Kiefer, Schwingen, Läufe, Windung …).
 * Ablaufvertrag wie bisher (siehe [NivarisPose]): Ausholhaltung weich erreichen, halten, **Treffer genau bei
 * t = windup**, Nachlauf [AriusTimings.NIVARIS_RECOVER], dann zurück in die Ruhe. Die gehaltenen Kopfgelenke stammen aus
 * [NivarisShape.head] – dieselben Werte benutzt der Server für Anker und Kopf-Trefferbox.
 */
class NivarisModel(root: ModelPart) : EntityModel<NivarisRenderState>(root) {

    private val placeholder: ModelPart by lazy { placeholderData().createModel() }

    private fun part(name: String): ModelPart = if (root.hasChild(name)) root.getChild(name) else placeholder.getChild(name)

    val head = part("head")
    val jaw: ModelPart? = if (head.hasChild("jaw")) head.getChild("jaw") else null
    private val jawPitch = jaw?.pitch ?: 0f
    val neck = part("neck")
    val segment = part("segment")
    val segmentDorsal = part("segment_dorsal")
    val segmentHeart = part("segment_heart")
    val foreL = part("foreleg_l")
    val foreR = part("foreleg_r")
    val hindL = part("hindleg_l")
    val hindR = part("hindleg_r")
    val wingL = part("wing_l")
    val wingR = part("wing_r")
    val tailFan = part("tail_fan")

    /** Bewegliches Kind mit seinen Grundwinkeln aus dem Modell. */
    class Joint(val part: ModelPart) {
        private val pitch0 = part.pitch
        private val yaw0 = part.yaw
        private val roll0 = part.roll
        fun set(pitch: Float = 0f, yaw: Float = 0f, roll: Float = 0f) {
            part.pitch = pitch0 + pitch
            part.yaw = yaw0 + yaw
            part.roll = roll0 + roll
        }
    }

    /** Kette parent → name_1 → name_2 … (Barteln, Laufglieder); fehlende Glieder entfallen. */
    private fun chain(parent: ModelPart, names: List<String>): List<Joint> {
        val out = ArrayList<Joint>()
        var cur = parent
        for (n in names) {
            if (!cur.hasChild(n)) break
            cur = cur.getChild(n)
            out.add(Joint(cur))
        }
        return out
    }

    private val whiskers = listOf("r", "l").map { s -> chain(head, listOf("whisker_$s", "whisker_${s}_1", "whisker_${s}_2", "whisker_${s}_3")) }
    private val foreJoints = listOf(foreL to "l", foreR to "r").map { (p, s) -> chain(p, listOf("foreleg_${s}_lower", "foreleg_${s}_foot")) }
    private val hindJoints = listOf(hindL to "l", hindR to "r").map { (p, s) -> chain(p, listOf("hindleg_${s}_lower", "hindleg_${s}_foot")) }

    /**
     * Kinder für diesen Frame: Läufe im Flug angelegt (Unterarm −20°, Fuß +50°, docs/NIVARIS_SERPENT.md), Barteln
     * schwingen sanft und phasenversetzt nach. Alle Layer eines Frames zeichnen dieselben Werte.
     */
    fun animateChildren(state: NivarisRenderState) {
        val p = state.p
        val deg = MathHelper.RADIANS_PER_DEGREE
        fun tuck(v: Float) = MathHelper.clamp(v / 1.1f, 0f, 1f)
        for ((legs, k) in listOf(foreJoints to tuck(p[FORE]), hindJoints to tuck(p[HIND]))) {
            for (leg in legs) {
                leg.getOrNull(0)?.set(pitch = -20f * deg * k)
                leg.getOrNull(1)?.set(pitch = 50f * deg * k)
            }
        }
        val age = state.age
        val speed = 1f + state.fly * 1.5f
        for ((side, joints) in whiskers.withIndex()) {
            val sgn = if (side == 0) 1f else -1f
            for ((k, j) in joints.withIndex()) {
                val ph = age * 0.06f * speed - k * 0.7f + side * 0.4f
                j.set(pitch = sin(ph * 0.8f) * 6f * deg, yaw = sgn * sin(ph) * 8f * deg)
            }
        }
    }

    /** Teil auf Glied [i] ([NivarisShape.gliedPart]). */
    fun glied(i: Int): ModelPart = when (NivarisShape.gliedPart(i)) {
        "neck" -> neck
        "segment_heart" -> segmentHeart
        "segment_dorsal" -> segmentDorsal
        else -> segment
    }

    /** Kiefer setzen (einziger Teil mit eigener Drehung; alle Layer eines Frames zeichnen denselben Wert). */
    fun openJaw(amount: Float) {
        jaw?.pitch = jawPitch + amount
    }

    // --- Hüllkurven ---------------------------------------------------------------------------------------------------

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun easeIn(t: Float) = t * t
    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)

    /** Zeitleiste: hält die zuletzt erreichte Haltung und blendet in den Abschnitten [t0, t1] zur nächsten über. */
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

    private fun rest(air: Boolean) = if (air) Keys.HOVER else Keys.IDLE

    /** Der Ablauf jeder Pose ab ihrem Beginn. [from] ist die Haltung, aus der sie kommt; w = Treffer-Zeitpunkt. */
    private fun evaluate(pose: Int, t: Float, w: Float, air: Boolean, from: FloatArray): FloatArray {
        val r = recover(pose)
        val s: (Float) -> Float = ::smooth
        val i: (Float) -> Float = ::easeIn
        val o: (Float) -> Float = ::easeOut
        val back = rest(air)
        fun wind(maxTicks: Float, strike: Float) = MathHelper.clamp(w - strike, 1f, maxTicks)
        return when (pose) {
            NivarisPose.IDLE -> track(from, t).to(0f, 14f, if (air) Keys.HOVER else Keys.IDLE, s).get()
            NivarisPose.HOVER -> track(from, t).to(0f, 12f, if (air) Keys.HOVER else Keys.IDLE, s).get()
            NivarisPose.GLIDE -> track(from, t).to(0f, 14f, if (air) Keys.GLIDE else Keys.IDLE, s).get()
            NivarisPose.INHALE -> track(from, t).to(0f, MathHelper.clamp(w * 0.6f, 4f, 16f), if (air) Keys.INHALE_AIR else Keys.INHALE, s).get()
            NivarisPose.BREATH -> track(from, t).to(0f, 4f, if (air) Keys.BREATH_AIR else Keys.BREATH, o).get()
            NivarisPose.DIVE -> track(from, t).to(0f, wind(12f, 6f), Keys.DIVE_WINDUP, s).to(w - 6f, w, Keys.DIVE, i).get()
            NivarisPose.CRASH_DIVE -> track(from, t).to(0f, wind(14f, 6f), Keys.CRASH_WINDUP, s).to(w - 6f, w, Keys.CRASH, i).get()
            NivarisPose.LAND -> track(from, t).to(0f, wind(10f, 3f), Keys.LAND_WINDUP, s).to(w - 3f, w, Keys.LAND_HIT, i)
                .to(w + 3f, w + max(r, 4f), Keys.IDLE, s).get()
            NivarisPose.CLAW -> track(from, t).to(0f, wind(8f, 3f), Keys.CLAW_WINDUP, s).to(w - 3f, w, Keys.CLAW_HIT, i)
                .to(w + 2f, w + max(r, 3f), back, s).get()
            NivarisPose.TAIL -> track(from, t).to(0f, wind(10f, 4f), Keys.TAIL_WINDUP, s).to(w - 4f, w, Keys.TAIL_HIT, i)
                .to(w, w + 4f, Keys.TAIL_FOLLOW, o).to(w + 4f, w + max(r, 5f), Keys.TAIL_END, s).get()
            NivarisPose.WING_GUST -> track(from, t).to(0f, wind(12f, 3f), Keys.GUST_WINDUP, s).to(w - 3f, w, Keys.GUST_HIT, i)
                .to(w + 3f, w + max(r, 4f), back, s).get()
            NivarisPose.GRAB -> track(from, t).to(0f, max(w * 0.5f, 2f), Keys.GRAB_REACH, s).to(max(w * 0.5f, 2f), max(w, 3f), Keys.GRAB_HOLD, s).get()
            NivarisPose.THROW -> track(from, t).to(0f, wind(12f, 3f), Keys.THROW_WINDUP, s).to(w - 3f, w, Keys.THROW_HIT, i)
                .to(w + 3f, w + max(r, 4f), back, s).get()
            NivarisPose.ROAR -> track(from, t).to(0f, max(w, 2f), if (air) Keys.ROAR_AIR else Keys.ROAR, s).to(w + r - 8f, w + r, back, s).get()
            NivarisPose.DOWNED -> track(from, t).to(0f, 8f, if (air) Keys.DOWNED_AIR else Keys.DOWNED, i).get()
            NivarisPose.EXHAUSTED -> track(from, t).to(0f, 14f, Keys.EXHAUSTED, s).get()
            NivarisPose.TAKEOFF -> track(from, t).to(0f, wind(8f, 2f), Keys.TAKEOFF_CROUCH, s).to(w - 2f, w, Keys.TAKEOFF_BEAT, i)
                .to(w + 1f, w + max(r, 2f), Keys.HOVER, s).get()
            NivarisPose.RESONANCE -> track(from, t).to(0f, 16f, if (air) Keys.RESONANCE_AIR else Keys.RESONANCE, s).get()
            NivarisPose.LIE_DOWN -> track(from, t).to(0f, 30f, Keys.LIE_DOWN, s).get()
            NivarisPose.FREEZE_FIELD -> track(from, t).to(0f, wind(14f, 4f), Keys.FREEZE_WINDUP, s).to(w - 4f, w, if (air) Keys.FREEZE_AIR else Keys.FREEZE, i)
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

    /** Posen, deren Schwingen nicht vom Flug-Zyklus überschrieben werden. */
    private fun ownsWings(pose: Int) = when (pose) {
        NivarisPose.DIVE, NivarisPose.CRASH_DIVE, NivarisPose.LAND, NivarisPose.TAKEOFF, NivarisPose.RESONANCE,
        NivarisPose.WING_GUST, NivarisPose.DOWNED, NivarisPose.LIE_DOWN, NivarisPose.EXHAUSTED -> true
        else -> false
    }

    override fun setAngles(state: NivarisRenderState) {
        // Nichts: Die Teile werden einzeln mit eigener Matrix gezeichnet (NivarisRenderer); siehe [pose].
    }

    /** Berechnet die Kanäle der laufenden Pose samt Überlagerungen (Flügelschlag, Atmen, Zittern …) nach [NivarisRenderState.p]. */
    fun pose(state: NivarisRenderState) {
        val pose = state.bossPose
        val t = state.poseTime
        val age = state.age
        val fly = state.fly
        val air = fly > 0.5f
        val w = state.windup
        val prev = state.previousPose
        val start = evaluate(prev, settled(prev), defaultWindup(prev), air, rest(air).v).copyOf()
        // Eine volle Umdrehung der Windungen (Schwanzfeger) ist dieselbe Lage: nicht zurückdrehen.
        start[SWEEP] -= Math.round(start[SWEEP]).toFloat()
        val v = evaluate(pose, t, w, air, start)
        System.arraycopy(v, 0, state.p, 0, COUNT)
        val p = state.p

        // Flug: Schwingen gespannt, ein langsamer, schwerer Schlag (Abschlag schnell), Läufe angezogen.
        if (!ownsWings(pose) && fly > 0.001f) {
            val period = if (pose == NivarisPose.GLIDE) 70f else 34f
            val u = ((age % period) + period) % period / period
            val beat = if (u < 0.35f) 1f - 2f * smooth(u / 0.35f) else -1f + 2f * smooth((u - 0.35f) / 0.65f)
            val amp = if (pose == NivarisPose.GLIDE) 0.35f else 0.85f
            p[WING] += (1f - p[WING]) * fly
            p[FLAP] += (beat * amp - p[FLAP]) * fly
            p[FORE] += (max(p[FORE], 1.0f) - p[FORE]) * fly
            p[HIND] += (max(p[HIND], 1.1f) - p[HIND]) * fly
            // Nach jedem Abschlag hebt sich der Kopf leicht.
            p[UP] += -beat * 0.15f * fly
        }

        // Atmen: langsam und tief; erschöpft schwer und schnell.
        val exhausted = pose == NivarisPose.EXHAUSTED || pose == NivarisPose.DOWNED
        val breath = if (exhausted) sin(age * 0.2f) else sin(age * 0.065f)
        val depth = if (exhausted) 2.2f else 1f
        p[BREATHE] = breath * depth
        p[UP] += breath * 0.08f * depth * (1f - fly)
        p[PITCH] += breath * 0.02f * depth
        if (exhausted) p[JAW] += (0.5f + 0.5f * breath) * 0.12f

        // Spannung beim Halten des Ausholens: kaum sichtbares Zittern.
        val holding = when (pose) {
            NivarisPose.CLAW, NivarisPose.TAIL, NivarisPose.WING_GUST, NivarisPose.THROW, NivarisPose.FREEZE_FIELD -> t > 8f && t < w - 3f
            NivarisPose.INHALE -> true
            else -> false
        }
        if (holding) {
            p[PITCH] += sin(age * 2.3f) * 0.012f
            p[YAW] += sin(age * 1.9f) * 0.01f
        }

        when (pose) {
            NivarisPose.INHALE -> {
                // Sie zieht immer tiefer Luft: Kopf geht weiter zurück und hoch, Maul öffnet sich langsam.
                val k = MathHelper.clamp(t / max(w, 1f), 0f, 1f)
                p[UP] += k * 0.5f
                p[FWD] -= k * 0.4f
                p[PITCH] -= k * 0.12f
                p[JAW] += k * 0.18f
                p[REAR] += k * 0.4f
            }
            NivarisPose.BREATH, NivarisPose.FREEZE_FIELD -> if (pose == NivarisPose.BREATH || t >= w) {
                p[YAW] += sin(age * 1.3f) * 0.03f
                p[JAW] += sin(age * 2.1f) * 0.05f
            }
            NivarisPose.ROAR -> if (t >= w) {
                val k = MathHelper.clamp(1f - (t - w) / max(recover(pose), 1f), 0f, 1f)
                p[YAW] += sin(age * 2.6f) * 0.06f * k
                p[JAW] += sin(age * 3.1f) * 0.08f * k
                p[PITCH] += sin(age * 2.0f) * 0.03f * k
            }
            NivarisPose.LAND -> {
                // Aufprall: der Kopf schwingt nach unten nach und federt gedämpft zurück, der Körper staucht.
                val since = t - w
                if (since >= 0f) {
                    val spring = exp(-since * 0.16f) * sin(since * 0.5f)
                    p[UP] -= spring * 0.9f
                    p[PITCH] += spring * 0.3f
                    p[REAR] -= max(0f, spring) * 0.6f
                    p[FLAP] -= spring * 0.4f
                }
            }
            NivarisPose.TAKEOFF -> {
                val since = t - w
                if (since >= 0f) p[UP] += exp(-since * 0.25f) * sin(since * 0.6f) * 0.5f
            }
            NivarisPose.CLAW, NivarisPose.TAIL, NivarisPose.WING_GUST, NivarisPose.THROW -> {
                // Nach dem Schlag ruckt das Gewicht nach.
                val since = t - w
                if (since >= 0f) {
                    val spring = exp(-since * 0.3f) * sin(since * 0.8f)
                    p[UP] -= spring * 0.25f
                    p[PITCH] += spring * 0.1f
                }
            }
        }
    }

    /**
     * Kanäle einer Haltung. Das Kopfgelenk (vor, hoch, Neigung) kommt für die gehaltenen Posen aus [NivarisShape.head],
     * damit Server-Anker und Modell übereinstimmen; Zwischenhaltungen (Ausholen, Schlag) stehen hier.
     */
    object Keys {
        private fun k(h: NivarisShape.Head, jaw: Float = 0.04f, wing: Float = 0f, flap: Float = 0f, fore: Float = 0.15f, swipe: Float = 0f,
                      sweep: Float = 0f, tight: Float = 0f, hind: Float = 0.2f, rear: Float = 0f, slack: Float = 0f): NivarisKey {
            val v = FloatArray(COUNT)
            v[FWD] = h.fwd.toFloat(); v[UP] = h.up.toFloat(); v[PITCH] = h.pitch.toFloat(); v[YAW] = 0f; v[JAW] = jaw
            v[WING] = wing; v[FLAP] = flap; v[FORE] = fore; v[SWIPE] = swipe; v[SWEEP] = sweep; v[TIGHT] = tight
            v[HIND] = hind; v[REAR] = rear; v[SLACK] = slack
            return NivarisKey(v)
        }
        private fun g(pose: Int) = NivarisShape.head(pose, false)
        private fun a(pose: Int) = NivarisShape.head(pose, true)
        private fun h(fwd: Double, up: Double, pitch: Double) = NivarisShape.Head(fwd, up, pitch)

        // Boden
        val IDLE = k(g(NivarisPose.IDLE))
        val INHALE = k(g(NivarisPose.INHALE), jaw = 0.3f, wing = 0.3f, flap = 0.5f, rear = 1.0f, tight = 0.3f)
        val BREATH = k(g(NivarisPose.BREATH), jaw = 0.85f, wing = 0.35f, flap = -0.2f, rear = 0.4f)
        val LAND_WINDUP = k(a(NivarisPose.LAND), wing = 1f, flap = 0.9f, fore = -0.6f, hind = -0.3f)
        val LAND_HIT = k(h(3.6, 3.4, 0.45), jaw = 0.3f, wing = 0.9f, flap = -1f, fore = 0.6f, rear = -0.4f)
        val CLAW_WINDUP = k(h(1.8, 6.0, -0.1), jaw = 0.2f, fore = -1.2f, rear = 1.4f)
        val CLAW_HIT = k(h(5.2, 3.0, 0.6), jaw = 0.5f, fore = 0.9f, swipe = 1f, rear = 0.3f)
        val TAIL_WINDUP = k(h(2.4, 5.0, 0.2), sweep = -0.22f, tight = 0.6f)
        val TAIL_HIT = k(h(3.0, 4.6, 0.25), sweep = 0.8f, tight = 0.15f)
        val TAIL_FOLLOW = k(h(2.8, 5.0, 0.2), sweep = 0.95f, tight = 0.2f)

        /** Eine volle Umdrehung = gleiche Lage wie vorher; [NivarisBody] rechnet die Windung modulo 1. */
        val TAIL_END = k(g(NivarisPose.TAIL), sweep = 1f)
        val GUST_WINDUP = k(h(1.8, 6.6, -0.3), wing = 1f, flap = 1f, rear = 2.2f)
        val GUST_HIT = k(h(3.4, 5.6, 0.1), wing = 1f, flap = -1f, rear = 1.2f)
        val GRAB_REACH = k(h(4.6, 1.8, 1.0), jaw = 0.7f, fore = 0.8f)
        val GRAB_HOLD = k(g(NivarisPose.GRAB), jaw = 0.1f, fore = 0.6f)
        val THROW_WINDUP = k(h(1.2, 6.4, -0.6), jaw = 0.1f, rear = 1.5f)
        val THROW_HIT = k(h(4.4, 5.0, 0.5), jaw = 0.6f, rear = 0.5f)
        val ROAR = k(g(NivarisPose.ROAR), jaw = 1f, wing = 0.8f, flap = 0.6f, rear = 1.6f)
        val DOWNED = k(g(NivarisPose.DOWNED), jaw = 0.15f, wing = 0.25f, flap = -0.7f, fore = 0.9f, rear = -0.7f, slack = 1f)
        val EXHAUSTED = k(g(NivarisPose.EXHAUSTED), jaw = 0.25f, wing = 0.1f, flap = -0.4f, rear = -0.4f, slack = 0.6f)
        val TAKEOFF_CROUCH = k(h(3.0, 4.0, 0.2), wing = 1f, flap = 1f, fore = 0.4f, rear = -0.5f)
        val TAKEOFF_BEAT = k(h(3.6, 6.0, 0.0), wing = 1f, flap = -1f, rear = 1.5f)
        val RESONANCE = k(g(NivarisPose.RESONANCE), jaw = 0.3f, wing = 1f, flap = 1.2f, rear = 2.0f)
        val LIE_DOWN = k(g(NivarisPose.LIE_DOWN), jaw = 0f, tight = 1f, rear = -0.6f, slack = 0.3f)
        val FREEZE_WINDUP = k(h(1.8, 6.6, -0.4), jaw = 0.3f, wing = 0.5f, rear = 1.4f)
        val FREEZE = k(g(NivarisPose.FREEZE_FIELD), jaw = 0.9f, wing = 0.5f, rear = 0.6f)

        // Luft
        val HOVER = k(a(NivarisPose.HOVER), wing = 1f, fore = 1f, hind = 1.1f)
        val GLIDE = k(a(NivarisPose.GLIDE), wing = 1f, flap = 0.1f, fore = 1.1f, hind = 1.2f)
        val INHALE_AIR = k(a(NivarisPose.INHALE), jaw = 0.3f, wing = 1f, flap = 0.6f, fore = 0.6f, hind = 1f)
        val BREATH_AIR = k(a(NivarisPose.BREATH), jaw = 0.85f, wing = 1f, flap = -0.2f, fore = 1f, hind = 1f)
        val DIVE_WINDUP = k(h(4.6, 3.4, -0.2), wing = 1f, flap = 1f, fore = 0.6f, hind = 1f)
        val DIVE = k(a(NivarisPose.DIVE), jaw = 0.4f, wing = 0.15f, flap = -0.3f, fore = 1.2f, hind = 1.2f)
        val CRASH_WINDUP = k(h(4.0, 3.0, -0.3), wing = 1f, flap = 1f, fore = 0.8f, hind = 1f)
        val CRASH = k(a(NivarisPose.CRASH_DIVE), wing = 0.1f, fore = 1.2f, hind = 1.2f)
        val ROAR_AIR = k(a(NivarisPose.ROAR), jaw = 1f, wing = 1f, flap = 0.8f, fore = 0.8f, hind = 1f)
        val DOWNED_AIR = k(a(NivarisPose.DOWNED), jaw = 0.3f, wing = 0.5f, flap = 1f, fore = 0.4f, hind = 0.6f, slack = 1f)
        val RESONANCE_AIR = k(a(NivarisPose.RESONANCE), jaw = 0.3f, wing = 1f, flap = 1.2f, fore = 0.8f, hind = 1f)
        val FREEZE_AIR = k(a(NivarisPose.FREEZE_FIELD), jaw = 0.9f, wing = 1f, flap = 0.2f, fore = 1f, hind = 1f)
    }

    companion object {
        /** Kopfgelenk vor dem Entity-Punkt (Blöcke, entlang der Blickrichtung). */
        const val FWD = 0

        /** Kopfgelenk über dem Entity-Punkt (Blöcke). */
        const val UP = 1

        /** Kopfneigung (+ = Nase runter). */
        const val PITCH = 2

        /** Kopfdrehung gegen die Blickrichtung der Entity. */
        const val YAW = 3

        /** Kiefer offen (rad). */
        const val JAW = 4

        /** Schwingen: 0 = angelegt, 1 = gespannt. */
        const val WING = 5

        /** Schwingen: −1 = Abschlag unten … +1 = hoch. */
        const val FLAP = 6

        /** Vorderläufe: − = nach vorn gereckt, + = nach hinten angezogen (rad). */
        const val FORE = 7

        /** Rechte Klaue: Hieb quer (0..1). */
        const val SWIPE = 8

        /** Windungen am Boden: Drehung um sie herum (Umdrehungen, Schwanzfeger). */
        const val SWEEP = 9

        /** Windungen am Boden: 0 = weit, 1 = eng zusammengerollt. */
        const val TIGHT = 10

        /** Hinterläufe angezogen (rad). */
        const val HIND = 11

        /** Rumpf vorn angehoben (Blöcke, Aufbäumen; − = flach an den Boden gedrückt). */
        const val REAR = 12

        /** Schlaff (zu Boden gebracht, erschöpft): Windungen locker, Körper flach. */
        const val SLACK = 13

        /** Atmen −1..1 (Brust weitet sich). */
        const val BREATHE = 14

        const val COUNT = 15

        /** Platzhalter mit denselben Namen, solange das Modell (noch) nicht alle Teile hat. */
        fun placeholderData(): TexturedModelData {
            val data = ModelData()
            val root = data.root
            val d = Dilation.NONE
            fun box(x: Float, y: Float, z: Float, w: Float, h: Float, l: Float) = ModelPartBuilder.create().uv(0, 0).cuboid(x, y, z, w, h, l, d)
            val head = root.addChild("head", box(-6f, -6f, -12f, 12f, 10f, 15f), ModelTransform.NONE)
            head.addChild("jaw", box(-4f, 0f, -16f, 8f, 2.5f, 12f), ModelTransform.origin(0f, 1f, -4f))
            root.addChild("neck", box(-5f, -5f, -0.5f, 10f, 10f, 17f), ModelTransform.NONE)
            root.addChild("segment", box(-6f, -6f, -0.5f, 12f, 12f, 17f), ModelTransform.NONE)
            root.addChild("segment_dorsal", box(-6f, -6f, -0.5f, 12f, 12f, 17f).uv(0, 0).cuboid(-1f, -10f, 4f, 2f, 4f, 8f, d), ModelTransform.NONE)
            root.addChild("segment_heart", box(-6f, -6f, -0.5f, 12f, 12f, 17f), ModelTransform.NONE)
            root.addChild("foreleg_l", box(-1.5f, -3f, -3f, 6f, 11f, 6f), ModelTransform.NONE)
            root.addChild("foreleg_r", box(-4.5f, -3f, -3f, 6f, 11f, 6f), ModelTransform.NONE)
            root.addChild("hindleg_l", box(-2f, -4f, -3.5f, 7f, 12f, 7f), ModelTransform.NONE)
            root.addChild("hindleg_r", box(-5f, -4f, -3.5f, 7f, 12f, 7f), ModelTransform.NONE)
            root.addChild("wing_l", box(0f, -1f, -1f, 60f, 1f, 24f), ModelTransform.NONE)
            root.addChild("wing_r", box(-60f, -1f, -1f, 60f, 1f, 24f), ModelTransform.NONE)
            root.addChild("tail_fan", box(-1f, -6f, 0f, 2f, 12f, 24f), ModelTransform.NONE)
            return TexturedModelData.of(data, 64, 64)
        }
    }
}

/**
 * Der Körper eines Nivaris-Exemplars auf dem Client: rechnet je Frame die 41 Punkte des Rückgrats und die Gliedrahmen.
 *
 * * **Flug:** Die Rumpfmitte (Entity-Punkt + [NivarisShape.SPINE_UP]) wird alle 0,5 Blöcke Weg in eine Spur
 *   geschrieben; das Kopfgelenk sitzt je Pose vor dem Rumpf ([NivarisModel.FWD]/[NivarisModel.UP]), ein weicher Bogen
 *   verbindet beide, dahinter folgen die Glieder der Spur in festem Bogenabstand (2,4). Darüber eine Welle, die nach
 *   hinten läuft, Rollen und Schräglage in Kurven.
 * * **Boden (seit 1.2.2):** Der Körper bleibt die zurückgelegte Spur (wie die Drachen in Black Myth: Wukong) und sinkt
 *   am Boden sanft auf die Lauffläche; so liegt er in den Bögen, die er geflogen bzw. gekrochen ist. Die frühere feste
 *   Spirale und das Überblenden zwischen Spur und Spirale (eckige Brücken, senkrechte Säulen) sind entfallen.
 * * **Glätten:** Die abgetasteten Punkte werden mehrfach geglättet und neu abgetastet, damit gerade Sehnen und Knicke
 *   der Spur als weiche Kurve erscheinen; kein Glied taucht unter die Lauffläche.
 * * **An der Nadel ([NivarisModeC.COIL]):** Hals vom Kopf (ruhend oder über dem Gipfel) zur Helix
 *   [ArenaLayout.coil], der Körper liegt auf ihrem oberen Teil.
 * * **Lösen ([NivarisModeC.UNCOIL]):** Der Kopf folgt [EntrancePathC]; der Körper ist der zurückgelegte Weg, dahinter
 *   Hals und Helix – so gleitet er Glied für Glied vom Fels in die Flugbahn.
 * * **Keine Sprünge:** Wechselt die Darstellung oder springt die Entity (Teleport), blendet der Körper in 10 Ticks vom
 *   alten zum neuen Verlauf über; beim Übergang in den Kampf wird die Spur aus dem letzten Körper gesät.
 */
class NivarisBody {

    private class Poly {
        val pts = ArrayList<Vec>(320)
        val ups = ArrayList<Vec>(320)
        fun add(p: Vec, up: Vec) {
            if (pts.isNotEmpty() && pts.last().squaredDistanceTo(p) < 1e-6) return
            pts.add(p)
            ups.add(up)
        }
    }

    private val trail = ArrayDeque<Vec>()
    private var last: Array<Vec>? = null
    private var fadeFrom: Array<Vec>? = null
    private var fadeAt = 0f
    private var lastMode = -1
    private var lastE: Vec? = null
    private var lastAge = Float.NaN
    private var groundY = Double.NaN
    private var ground = -1f
    private var raise = -1f
    private var smoothHead: Vec? = null

    fun update(e: dev.henny.hha_arius.boss.NivarisEntity, s: NivarisRenderState, tickDelta: Float) {
        val now = s.age
        val dt = if (lastAge.isNaN()) 1f else MathHelper.clamp(now - lastAge, 0f, 5f)
        lastAge = now
        val p = s.p
        val mode = e.mode
        val world = e.entityWorld
        val base = Vec(s.x, s.y, s.z)
        val slot = Math.floorDiv(Math.round(s.x).toInt() + ArenaLayout.SLOT_SPACING / 2, ArenaLayout.SLOT_SPACING)
        val origin = ArenaLayout.origin(slot)
        val o = Vec(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
        val eirholm = world.registryKey == dev.henny.hha_arius.arena.ArenaDimension.WORLD

        // Wechsel der Darstellung: weich überblenden, beim Übergang in den Kampf die Spur aus dem letzten Körper säen.
        if (lastMode != -1 && mode != lastMode) {
            startFade(now)
            lastE = null
            if (mode == NivarisModeC.NORMAL) last?.let { l ->
                trail.clear()
                for (k in 3 until l.size) trail.addLast(l[k])
            }
        }
        lastMode = mode

        val pts = Array(POINTS) { Vec.ZERO }
        val ups = Array(POINTS) { UP_V }
        val air = FloatArray(POINTS)
        val headFwd: Vec
        when (mode) {
            NivarisModeC.COIL, NivarisModeC.UNCOIL -> {
                lastE = null
                val target = if (mode == NivarisModeC.UNCOIL) 1f else MathHelper.clamp((p[NivarisModel.UP] - 1.3f) / 3.8f, 0f, 1f)
                raise = if (raise < 0f) target else approach(raise, target, dt / 45f)
                val r = smooth(raise)
                val poly = Poly()
                var u = 0.0
                if (mode == NivarisModeC.UNCOIL) {
                    u = MathHelper.lerp(tickDelta, e.lastUncoil, e.uncoil).toDouble() * EntrancePathC.LENGTH
                    var d = u
                    while (d > 0.0) {
                        poly.add(o.add(EntrancePathC.at(d)), UP_V)
                        d -= 0.5
                    }
                }
                coilPoly(poly, o, r.toDouble(), now)
                sample(poly, pts, ups)
                headFwd = if (mode == NivarisModeC.UNCOIL && u > 0.5) {
                    EntrancePathC.tangent(u).add(0.0, -0.12, 0.0).normalize()
                } else {
                    // Blick nach Norden auf das Plateau, die Neigung aus der Pose (ruhend: auf der Windung).
                    yawPitch(Math.PI.toFloat() + p[NivarisModel.YAW], p[NivarisModel.PITCH] + 0.15f * (1f - r))
                }
                for (i in 0 until POINTS) air[i] = if (mode == NivarisModeC.UNCOIL && i * NivarisShape.SEGMENT < u) {
                    MathHelper.clamp(((u - i * NivarisShape.SEGMENT) / 20.0).toFloat(), 0f, 1f)
                } else 0f
            }
            else -> {
                val yaw = Math.toRadians(s.bodyYaw.toDouble())
                val f = Vec(-kotlin.math.sin(yaw), 0.0, kotlin.math.cos(yaw))
                val spine = base.add(0.0, NivarisShape.SPINE_UP + p[NivarisModel.REAR] * 0.5, 0.0)
                val h = base.add(f.multiply(p[NivarisModel.FWD].toDouble())).add(0.0, p[NivarisModel.UP].toDouble(), 0.0)
                // Blickrichtung weich nachführen: dreht die Entity schlagartig (Wende, Teleport), schwenkt der Kopf statt zu springen.
                val wantHead = yawPitch(s.bodyYaw * MathHelper.RADIANS_PER_DEGREE + p[NivarisModel.YAW], p[NivarisModel.PITCH])
                val prevHead = smoothHead
                headFwd = if (prevHead == null) wantHead else {
                    val k = 1.0 - kotlin.math.exp(-dt * 0.3)
                    prevHead.lerp(wantHead, k).normalizeOr(wantHead)
                }
                smoothHead = headFwd

                // Sprung der Entity (Teleport, Neu-Spawn an anderer Stelle): Körper mitnehmen und überblenden.
                lastE?.let { prev ->
                    if (prev.squaredDistanceTo(spine) > 12.0 * 12.0) {
                        val delta = spine.subtract(prev)
                        val moved = trail.map { it.add(delta) }
                        trail.clear()
                        trail.addAll(moved)
                        startFade(now)
                    }
                }
                lastE = spine
                if (!e.flying) groundY = s.y
                if (groundY.isNaN()) groundY = s.y - (if (e.flying) 8.0 else 0.0)
                val target = if (e.flying) 0f else 1f
                ground = if (ground < 0f) target else approach(ground, target, dt / 34f)

                // Spur: Rumpfmitte alle 0,5 Blöcke. Neu gespawnt im Flug: eine sanfte S-Kurve nach hinten.
                if (trail.isEmpty()) {
                    for (k in 1..60) trail.addLast(spine.subtract(f.multiply(k * 2.0)).add(f.crossUp().multiply(kotlin.math.sin(k * 0.15) * 2.0)))
                }
                if (spine.squaredDistanceTo(trail.first()) >= 0.25) trail.addFirst(spine)
                trimTrail()

                // Am Boden sinkt die liegende Spur langsam auf die Lauffläche (Rumpfmitte über dem Boden), Glied für Glied
                // von hinten nach vorn weniger, damit der Hals aufgerichtet bleibt.
                if (ground > 0.01f) {
                    val rest = groundY + NivarisShape.SPINE_UP * 0.55
                    val step = dt * 0.35 * ground
                    var k = 0
                    val it = trail.listIterator()
                    while (it.hasNext()) {
                        val q = it.next()
                        if (k++ > 6 && q.y > rest) it.set(Vec(q.x, maxOf(rest, q.y - step), q.z))
                    }
                }

                // Flugbahn bzw. liegende Spur: vom Kopfgelenk weich zur Rumpfmitte, dann die Spur.
                val a = Poly()
                run {
                    val d = h.distanceTo(spine).coerceAtLeast(1.0)
                    val motion = trail.firstOrNull { it.squaredDistanceTo(spine) > 0.04 }?.let { spine.subtract(it).normalize() } ?: f
                    bezier(a, h, h.subtract(headFwd.multiply(0.35 * d)), spine.add(motion.multiply(0.35 * d)), spine, UP_V, 10)
                    for (q in trail) a.add(q, UP_V)
                }
                sample(a, pts, ups)
                smoothBody(pts, ups, groundY)
                for (i in 0 until POINTS) {
                    val h0 = pts[i].y - (groundY + radius(i))
                    air[i] = MathHelper.clamp((h0 / 2.5).toFloat(), 0f, 1f) * (1f - ground * 0.6f) + (1f - ground) * 0.4f
                }
            }
        }

        // Schräglage in Kurven (Flug) und Rückenseite am Hals nach hinten (aufgerichteter Hals zeigt den Kamm).
        for (i in 0 until POINTS) {
            var up = ups[i]
            if (air[i] > 0.01f && i in 1 until POINTS - 1) {
                val k = pts[i - 1].add(pts[i + 1]).subtract(pts[i].multiply(2.0))
                // Schräglage begrenzt (höchstens ~30°): enge Kurven kippen den Körper nicht mehr um 90°.
                var bank = Vec(k.x, 0.0, k.z).multiply(2.0 * air[i])
                if (bank.length() > 0.55) bank = bank.normalize().multiply(0.55)
                up = up.add(bank)
            }
            val back = max(0f, 1f - i / 6f) * 0.5
            ups[i] = up.subtract(headFwd.multiply(back)).normalizeOr(UP_V)
        }

        // Welle und Rollen: erst Rahmen, dann seitlich/aufwärts versetzen.
        val frames0 = frames(pts, ups, now, air)
        val walk = s.walk
        for (i in 1 until POINTS) {
            val k = i / POINTS.toDouble()
            val front = smooth(MathHelper.clamp(i / 5f, 0f, 1f)).toDouble()
            val amp = (0.2 + 0.9 * k) * front
            var lat = air[i] * amp * kotlin.math.sin(now * 0.16 - i * 0.42)
            var ver = air[i] * amp * 0.35 * kotlin.math.sin(now * 0.11 - i * 0.31 + 1.3)
            if (mode == NivarisModeC.NORMAL) {
                lat += (1f - air[i]) * walk * 0.5 * front * kotlin.math.sin(s.walkPhase * 0.6 - i * 0.5)
                // Schwanzfeger: der hintere Körper schwingt in einem weiten Bogen aus und zurück (statt der alten Spirale).
                val sw = p[NivarisModel.SWEEP].toDouble()
                if (sw > 0.0) lat += kotlin.math.sin(sw * 2.0 * Math.PI) * 7.0 * smooth(MathHelper.clamp((i - 10) / 18f, 0f, 1f))
            }
            else if (air[i] < 0.01f) ver += 0.1 * kotlin.math.sin(now * 0.05 - i * 0.35)
            val j = (i - 1) * 9
            val l = Vec(frames0[j].toDouble(), frames0[j + 1].toDouble(), frames0[j + 2].toDouble())
            val u = Vec(-frames0[j + 3].toDouble(), -frames0[j + 4].toDouble(), -frames0[j + 5].toDouble())
            pts[i] = pts[i].add(l.multiply(lat)).add(u.multiply(ver))
        }

        // Kein Glied durchdringt ein anderes (enge Wenden, Schwanzfeger, Kriechen im Kreis).
        if (mode == NivarisModeC.NORMAL) separate(pts, groundY)

        // Überblendung nach Wechsel/Sprung.
        fadeFrom?.let { from ->
            val k = (now - fadeAt) / 10f
            if (k >= 1f || from.size != POINTS) {
                fadeFrom = null
            } else {
                val far = (0 until POINTS).maxOf { from[it].squaredDistanceTo(pts[it]) }
                if (far > 60.0 * 60.0) {
                    fadeFrom = null // Überspringen/Neustart: kein Geisterstreifen quer durchs Bild
                } else {
                    val t = smooth(k).toDouble()
                    for (i in 0 until POINTS) pts[i] = from[i].lerp(pts[i], t)
                }
            }
        }
        last = pts

        // Endgültige Rahmen und Werte für den Renderer.
        val fr = frames(pts, ups, now, air)
        System.arraycopy(fr, 0, s.frame, 0, fr.size)
        for (i in 0 until POINTS) {
            s.px[i] = (pts[i].x - s.x).toFloat()
            s.py[i] = (pts[i].y - s.y).toFloat()
            s.pz[i] = (pts[i].z - s.z).toFloat()
            val light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(pts[i]))
            val sky = max(LightmapTextureManager.getSkyLightCoordinates(light), 9)
            s.lights[i] = LightmapTextureManager.pack(LightmapTextureManager.getBlockLightCoordinates(light), sky)
        }
        for (i in 0 until NivarisShape.SEGMENTS) {
            val breathe = if (i in 1..6) 1f + 0.025f * p[NivarisModel.BREATHE] else 1f
            s.girth[i] = NivarisShape.taper(i) * breathe
            s.stretch[i] = MathHelper.clamp((pts[i].distanceTo(pts[i + 1]) / NivarisShape.SEGMENT).toFloat(), 0.5f, 1.8f)
        }
        // Kopf: Blick headFwd, oben = Weltoben ohne Blickanteil.
        val hu = UP_V.subtract(headFwd.multiply(headFwd.dotProduct(UP_V))).normalizeOr(Vec(0.0, 0.0, 1.0))
        val hl = headFwd.negate().crossProduct(hu)
        put(s.headFrame, 0, hl, hu.negate(), headFwd.negate())
        s.bodyReady = true
    }

    private fun startFade(now: Float) {
        val l = last ?: return
        fadeFrom = l.copyOf()
        fadeAt = now
    }

    private fun trimTrail() {
        var len = 0.0
        var prev: Vec? = null
        var keep = 0
        for (q in trail) {
            if (prev != null) len += prev.distanceTo(q)
            prev = q
            keep++
            if (len > NivarisShape.SEGMENT * POINTS + 40.0) break
        }
        while (trail.size > keep) trail.removeLast()
    }

    /**
     * Glättet die abgetasteten Punkte (Kopf und Hals bleiben, ab Glied 3 dreimal ein Mittelwert der Nachbarn), tastet
     * die geglättete Linie neu in Gliedabständen ab und hält jedes Glied über der Lauffläche [groundY].
     */
    private fun smoothBody(pts: Array<Vec>, ups: Array<Vec>, groundY: Double) {
        val tmp = Array(POINTS) { pts[it] }
        repeat(3) {
            for (i in 3 until POINTS - 1) tmp[i] = tmp[i].multiply(0.5).add(tmp[i - 1].add(tmp[i + 1]).multiply(0.25))
        }
        val poly = Poly()
        for (i in 0 until POINTS) poly.add(tmp[i], ups[i])
        sample(poly, pts, ups)
        limitBend(pts)
        if (groundY.isNaN()) return
        for (i in 0 until POINTS) {
            val floor = groundY + radius(i) * 0.9
            if (pts[i].y < floor) pts[i] = Vec(pts[i].x, floor, pts[i].z)
        }
    }

    /**
     * Biegegrenze (seit 1.4.2): Zwischen zwei Gliedern höchstens 22° (am Hals 40°). Macht die Spur einen Haken oder eine
     * Kehrtwende, folgt der Körper in einem Bogen (Wenderadius ≈ 6 Blöcke) statt zu knicken oder sich zu falten.
     */
    private fun limitBend(pts: Array<Vec>) {
        for (i in 2 until POINTS) {
            val maxBend = if (i <= 4) 0.70 else 0.38
            val prev = pts[i - 1].subtract(pts[i - 2]).normalizeOr(Vec(0.0, 0.0, 1.0))
            var cur = pts[i].subtract(pts[i - 1]).normalizeOr(prev)
            val dot = prev.dotProduct(cur).coerceIn(-1.0, 1.0)
            val ang = kotlin.math.acos(dot)
            if (ang > maxBend) {
                // Drehachse; bei fast genauer Umkehr waagerecht zur Seite drehen.
                var axis = prev.crossProduct(cur)
                if (axis.lengthSquared() < 1e-6) axis = prev.crossProduct(UP_V).let { if (it.lengthSquared() < 1e-6) Vec(1.0, 0.0, 0.0) else it }
                cur = rotate(prev, axis.normalize(), maxBend)
            }
            pts[i] = pts[i - 1].add(cur.multiply(NivarisShape.SEGMENT))
        }
    }

    /** Rodrigues-Drehung von [v] um die Einheitsachse [k] um [a] (Bogenmaß). */
    private fun rotate(v: Vec, k: Vec, a: Double): Vec {
        val c = kotlin.math.cos(a)
        val sn = kotlin.math.sin(a)
        return v.multiply(c).add(k.crossProduct(v).multiply(sn)).add(k.multiply(k.dotProduct(v) * (1 - c)))
    }

    /** Glieder, die sich zu nah kommen (nicht Nachbarn), auseinanderschieben; Kopf und Hals bleiben stehen. */
    private fun separate(pts: Array<Vec>, groundY: Double) {
        repeat(3) {
            for (i in 0 until POINTS) {
                for (j in i + 6 until POINTS) {
                    val d = pts[j].subtract(pts[i])
                    val min = (radius(i) + radius(j)) * 1.05
                    val l2 = d.lengthSquared()
                    if (l2 >= min * min) continue
                    val l = kotlin.math.sqrt(l2)
                    // Waagerecht auseinander (bei Deckung übereinander: das hintere Glied darüber legen).
                    val dir = if (l < 1e-4) UP_V else Vec(d.x, d.y * 0.5, d.z).normalizeOr(UP_V)
                    val push = min - l
                    val wi = if (i < 4) 0.0 else 0.35
                    pts[j] = pts[j].add(dir.multiply(push * (1.0 - wi)))
                    if (wi > 0.0) pts[i] = pts[i].subtract(dir.multiply(push * wi))
                }
            }
        }
        if (groundY.isNaN()) return
        for (i in 0 until POINTS) {
            val floor = groundY + radius(i) * 0.9
            if (pts[i].y < floor) pts[i] = Vec(pts[i].x, floor, pts[i].z)
        }
    }

    /** Hals vom Kopfgelenk (ruhend → aufgerichtet nach [raise]) zur Spitze der Helix, dann die Helix hinab. */
    private fun coilPoly(poly: Poly, o: Vec, raise: Double, now: Float) {
        val axis = o.add(ArenaLayout.SPIRE.x + 0.5, 0.0, ArenaLayout.SPIRE.z + 0.5)
        val h = o.add(EntrancePathC.HEAD_REST.lerp(EntrancePathC.HEAD_RAISED, raise)).add(0.0, kotlin.math.sin(now * 0.05) * 0.25, 0.0)
        val c1 = o.add(ArenaLayout.coil(1.0))
        val t1 = c1.subtract(o.add(ArenaLayout.coil(0.995))).normalize()
        val d = h.distanceTo(c1).coerceAtLeast(2.0)
        val neckUp = Vec(0.0, 1.0, 0.6).normalize()
        bezier(poly, h, h.add(0.0, -0.45 * d, 0.12 * d), c1.add(t1.multiply(0.4 * d)), c1, neckUp, 14)
        var sCoil = 1.0
        while (sCoil >= 0.0) {
            val q = o.add(ArenaLayout.coil(sCoil))
            val radial = Vec(q.x - axis.x, 0.0, q.z - axis.z).normalizeOr(UP_V)
            val pos = q.add(radial.multiply(0.15 * kotlin.math.sin(now * 0.05 + sCoil * 9.0)))
            poly.add(pos, radial.multiply(0.9).add(0.0, 0.45, 0.0).normalize())
            sCoil -= 0.002
        }
    }

    /** Punkte im Bogenabstand 2,4 ab dem Anfang der Linie; zu kurze Linien laufen geradeaus weiter. */
    private fun sample(poly: Poly, outP: Array<Vec>, outU: Array<Vec>) {
        val pts = poly.pts
        if (pts.isEmpty()) return
        outP[0] = pts[0]
        outU[0] = poly.ups[0]
        var seg = 0
        var acc = 0.0
        for (i in 1 until outP.size) {
            val target = i * NivarisShape.SEGMENT
            var done = false
            while (seg < pts.size - 1) {
                val len = pts[seg].distanceTo(pts[seg + 1])
                if (acc + len >= target && len > 1e-9) {
                    val k = (target - acc) / len
                    outP[i] = pts[seg].lerp(pts[seg + 1], k)
                    outU[i] = poly.ups[seg].lerp(poly.ups[seg + 1], k)
                    done = true
                    break
                }
                acc += len
                seg++
            }
            if (!done) {
                val lastP = pts.last()
                val dir = if (pts.size >= 2) lastP.subtract(pts[pts.size - 2]).normalizeOr(Vec(0.0, 0.0, 1.0)) else Vec(0.0, 0.0, 1.0)
                outP[i] = lastP.add(dir.multiply(target - acc))
                outU[i] = poly.ups.last()
            }
        }
    }

    /** Rahmen je Glied (9 Werte: Modell-x = links, Modell-y = −oben, Modell-z = nach hinten) mit leichtem Rollen. */
    private fun frames(pts: Array<Vec>, ups: Array<Vec>, now: Float, air: FloatArray): FloatArray {
        val out = FloatArray(NivarisShape.SEGMENTS * 9)
        var prevU = ups[0]
        var prevB = Vec(0.0, 0.0, 1.0)
        for (i in 0 until NivarisShape.SEGMENTS) {
            val b = pts[i + 1].subtract(pts[i]).normalizeOr(prevB)
            val hint = ups[i].add(ups[i + 1])
            var u = hint.subtract(b.multiply(hint.dotProduct(b)))
            if (u.lengthSquared() < 0.04) u = prevU.subtract(b.multiply(prevU.dotProduct(b)))
            u = u.normalizeOr(Vec(1.0, 0.0, 0.0))
            var l = b.crossProduct(u)
            val roll = (0.02 + 0.05 * air[i]) * kotlin.math.sin(now * 0.05 - i * 0.2)
            val cr = kotlin.math.cos(roll)
            val sr = kotlin.math.sin(roll)
            val u2 = u.multiply(cr).add(l.multiply(sr))
            l = l.multiply(cr).subtract(u.multiply(sr))
            u = u2
            put(out, i * 9, l, u.negate(), b)
            prevU = u
            prevB = b
        }
        return out
    }

    private fun put(out: FloatArray, at: Int, x: Vec, y: Vec, z: Vec) {
        out[at] = x.x.toFloat(); out[at + 1] = x.y.toFloat(); out[at + 2] = x.z.toFloat()
        out[at + 3] = y.x.toFloat(); out[at + 4] = y.y.toFloat(); out[at + 5] = y.z.toFloat()
        out[at + 6] = z.x.toFloat(); out[at + 7] = z.y.toFloat(); out[at + 8] = z.z.toFloat()
    }

    private fun bezier(poly: Poly, p0: Vec, c1: Vec, c2: Vec, p3: Vec, up: Vec, steps: Int) {
        for (k in 0..steps) {
            val t = k / steps.toDouble()
            val m = 1 - t
            val q = p0.multiply(m * m * m).add(c1.multiply(3 * m * m * t)).add(c2.multiply(3 * m * t * t)).add(p3.multiply(t * t * t))
            poly.add(q, up)
        }
    }

    private fun radius(i: Int): Double = NivarisShape.taper(i.coerceAtMost(NivarisShape.SEGMENTS - 1)) * 6.0 * NivarisShape.SCALE / 16.0

    /** Blickrichtung aus Gier (rad, Minecraft-Konvention) und Neigung (+ = nach unten). */
    private fun yawPitch(yawRad: Float, pitch: Float): Vec {
        val cp = kotlin.math.cos(pitch.toDouble())
        return Vec(-kotlin.math.sin(yawRad.toDouble()) * cp, -kotlin.math.sin(pitch.toDouble()), kotlin.math.cos(yawRad.toDouble()) * cp)
    }

    private fun rotY(v: Vec, a: Double): Vec {
        val c = kotlin.math.cos(a)
        val s = kotlin.math.sin(a)
        return Vec(v.x * c - v.z * s, v.y, v.x * s + v.z * c)
    }

    private fun approach(v: Float, target: Float, step: Float) = if (v < target) minOf(target, v + step) else maxOf(target, v - step)

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun Vec.normalizeOr(fallback: Vec): Vec = if (lengthSquared() < 1e-10) fallback else normalize()

    private fun Vec.crossUp(): Vec = Vec(-z, 0.0, x)

    private companion object {
        const val POINTS = NivarisRenderState.POINTS
        val UP_V = Vec(0.0, 1.0, 0.0)
    }
}

private typealias Vec = net.minecraft.util.math.Vec3d
private typealias ArenaLayout = dev.henny.hha_arius.arena.ArenaLayout
private typealias EntrancePathC = dev.henny.hha_arius.boss.EntrancePath
private typealias NivarisModeC = dev.henny.hha_arius.boss.NivarisMode

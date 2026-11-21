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

    /** Flügel angelegt 0..1 (Ritt durch enge Felsen). */
    var wingTuck = 0f

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
 * Seit 1.11.0 (Crix: „sein Körper macht teilweise komische Sachen“, Haken, enge Schlaufen, flache Ringe, Selbstkreuzung):
 * * **Flug:** Der Körper liegt auf der Wegspur der Rumpfmitte. Alle Flüge laufen über [dev.henny.hha_arius.boss.DragonRail]
 *   (Radius mindestens 22 Blöcke); hier begrenzt zusätzlich ein Knickwinkel je Gelenk die Krümmung.
 * * **Boden und Szenen:** vorgegebene Formen (Schlangenlinie, enge Schlangenlinie, Spirale beim Liegen), nach dem
 *   Gelände gewählt. Der Körper gleitet von seinem aktuellen Verlauf hinein, simuliert wird nichts.
 * * **Überblendung in Winkeln:** Die Form ist eine Richtung je Glied, aufsummiert ab der Rumpfmitte. Gliedabstände bleiben
 *   exakt, und zwei Formen gehen ohne Falten ineinander über.
 * * **Hals:** Glieder 0–2 spannen den Bogen vom Kopfgelenk (je Pose) zur Rumpfmitte. Bewegt sich nur der Kopf, bleibt der
 *   Körper liegen.
 * * **An der Nadel ([NivarisModeC.COIL]) und beim Lösen ([NivarisModeC.UNCOIL]):** Helix bzw. Weg des Auftritts
 *   ([EntrancePathC]).
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

    /** Ein Punkt der Wegspur: Lage der Rumpfmitte und der Wegstand, an dem er entstand. */
    private class Node(var p: Vec, val odo: Double)

    private val trail = ArrayDeque<Node>()
    private var odo = 0.0
    private var lastSpine: Vec? = null
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
    private var smYaw = Float.NaN
    private var speed = 0.0

    /** Aktueller Bodenhub je Punkt (siehe [normal]). */
    private val lift = DoubleArray(POINTS)

    fun update(e: dev.henny.hha_arius.boss.NivarisEntity, s: NivarisRenderState, tickDelta: Float) {
        val now = s.age
        val dt = if (lastAge.isNaN()) 1f else MathHelper.clamp(now - lastAge, 0f, 5f)
        lastAge = now
        val p = s.p
        val mode = e.mode
        val world = e.entityWorld
        // Seit 1.10.0 direkt die Render-Position: sie ist flüssig (Bewegungspuffer, MotionClient). Die frühere
        // Vorhersage hier schoss bei ungleichmäßigen Paketen über das Ziel hinaus und zog dann zurück (Ruckeln).
        val base = Vec(s.x, s.y, s.z)
        val slot = Math.floorDiv(Math.round(s.x).toInt() + ArenaLayout.SLOT_SPACING / 2, ArenaLayout.SLOT_SPACING)
        val origin = ArenaLayout.origin(slot)
        val o = Vec(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())

        // Wechsel der Darstellung: weich überblenden, beim Übergang in den Kampf die Spur aus dem letzten Körper säen.
        if (lastMode != -1 && mode != lastMode) {
            startFade(now)
            lastE = null
            lastSpine = null
            if (mode == NivarisModeC.NORMAL) last?.let { l ->
                trail.clear()
                var od = odo
                var prevY = l[NECK].y
                for (k in NECK until l.size) {
                    // Beim Übergang vom steilen Nadel-Anflug (UNCOIL) in den Landeanflug: steil nach oben ragende
                    // Schwanzpunkte abflachen, damit der Leib hinter ihr waagerecht ausschwebt statt senkrecht hochzustehen.
                    val y = if (k == NECK) prevY else minOf(l[k].y, prevY + 0.42)
                    prevY = y
                    trail.addLast(Node(Vec(l[k].x, y, l[k].z), od))
                    od -= NivarisShape.SEGMENT
                }
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
                    u = e.uncoilAt(tickDelta).toDouble() * EntrancePathC.LENGTH
                    var d = u
                    while (d > 0.0) {
                        poly.add(o.add(EntrancePathC.at(d)), UP_V)
                        d -= 0.5
                    }
                }
                coilPoly(poly, o, r.toDouble(), now)
                sample(poly, pts, ups, 0, 0.0)
                headFwd = if (mode == NivarisModeC.UNCOIL && u > 0.5) {
                    EntrancePathC.tangent(u).add(0.0, -0.12, 0.0).normalize()
                } else {
                    // Blick nach Norden auf das Plateau, die Neigung aus der Pose (ruhend: auf der Windung).
                    yawPitch(Math.PI.toFloat() + p[NivarisModel.YAW], p[NivarisModel.PITCH] + 0.15f * (1f - r))
                }
                smYaw = s.bodyYaw
                for (i in 0 until POINTS) air[i] = if (mode == NivarisModeC.UNCOIL && i * NivarisShape.SEGMENT < u) {
                    MathHelper.clamp(((u - i * NivarisShape.SEGMENT) / 20.0).toFloat(), 0f, 1f)
                } else 0f
            }
            else -> headFwd = normal(e, s, p, base, dt, now, pts, ups, air)
        }

        // Schräglage in Kurven (Flug) und Rückenseite am Hals nach hinten (aufgerichteter Hals zeigt den Kamm).
        for (i in 0 until POINTS) {
            var up = ups[i]
            if (air[i] > 0.01f && i in 1 until POINTS - 1) {
                val k = pts[i - 1].add(pts[i + 1]).subtract(pts[i].multiply(2.0))
                // Schräglage begrenzt (höchstens ~30°): enge Kurven kippen den Körper nicht um 90°.
                var bank = Vec(k.x, 0.0, k.z).multiply(2.0 * air[i])
                if (bank.length() > 0.55) bank = bank.normalize().multiply(0.55)
                up = up.add(bank)
            }
            val back = max(0f, 1f - i / 6f) * 0.5
            ups[i] = up.subtract(headFwd.multiply(back)).normalizeOr(UP_V)
        }

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

        // Endgültige Rahmen und Werte für den Renderer (mit parallelem Transport + Fassrolle).
        val bodyRoll = e.rollAt(tickDelta).toDouble()
        val fr = frames(pts, ups, now, air, bodyRoll)
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
        // Kopf: Blick headFwd, oben = Weltoben ohne Blickanteil, dreht bei der Fassrolle mit.
        var hu = UP_V.subtract(headFwd.multiply(headFwd.dotProduct(UP_V))).normalizeOr(Vec(0.0, 0.0, 1.0))
        var hl = headFwd.negate().crossProduct(hu).normalizeOr(Vec(1.0, 0.0, 0.0))
        hu = hl.crossProduct(headFwd.negate()).normalizeOr(UP_V)
        if (kotlin.math.abs(bodyRoll) > 1e-4) {
            val cr = kotlin.math.cos(bodyRoll)
            val sr = kotlin.math.sin(bodyRoll)
            val hu2 = hu.multiply(cr).add(hl.multiply(sr))
            hl = hl.multiply(cr).subtract(hu.multiply(sr)).normalizeOr(hl)
            hu = hu2.normalizeOr(hu)
        }
        put(s.headFrame, 0, hl, hu.negate(), headFwd.negate())
        s.bodyReady = true
        // Reiter: Griffpunkte an den Kristallplatten der Rückenglieder (client/fx/RideClient).
        if (e.hasPassengers()) dev.henny.hha_arius.client.fx.RideClient.anchor(e, s, pts, hu, headFwd)
    }

    /**
     * Kampf, Flug und Szenen (seit 1.11.0 neu aufgebaut): Die Form entsteht als **Richtung je Glied** (Gier, Neigung) und
     * wird von der Rumpfmitte aus aufsummiert. Dadurch bleiben alle Gliedabstände exakt, und jede Überblendung läuft über
     * Winkel statt über Punkte: nichts faltet, knickt oder fällt durch sich selbst.
     * * **Flug:** Richtungen aus der Wegspur, je Gelenk höchstens [MAX_BEND] (Radius 16).
     * * **Boden:** vorgegebene Form ([restTarget]), in die der Körper vom aktuellen Verlauf hineingleitet.
     * Liefert die Blickrichtung des Kopfes.
     */
    private fun normal(
        e: dev.henny.hha_arius.boss.NivarisEntity, s: NivarisRenderState, p: FloatArray, base: Vec, dt: Float, now: Float,
        pts: Array<Vec>, ups: Array<Vec>, air: FloatArray,
    ): Vec {
        val world = e.entityWorld
        // Gier mit begrenzter Drehrate: Der Server dreht oft schlagartig (Wende, neues Ziel); hier wird daraus ein Bogen.
        smYaw = if (smYaw.isNaN()) s.bodyYaw else {
            val diff = MathHelper.wrapDegrees(s.bodyYaw - smYaw)
            val k = 1f - kotlin.math.exp(-dt * 0.35f)
            smYaw + MathHelper.clamp(diff * k, -12f * dt, 12f * dt)
        }
        val yawRad = smYaw * MathHelper.RADIANS_PER_DEGREE
        val f = Vec(-kotlin.math.sin(yawRad.toDouble()), 0.0, kotlin.math.cos(yawRad.toDouble()))
        val spine = base.add(0.0, NivarisShape.SPINE_UP + p[NivarisModel.REAR] * 0.5, 0.0)
        val h = base.add(f.multiply(p[NivarisModel.FWD].toDouble())).add(0.0, p[NivarisModel.UP].toDouble(), 0.0)
        val wantHead = yawPitch(yawRad + p[NivarisModel.YAW], p[NivarisModel.PITCH])
        val headFwd = smoothHead?.let { it.lerp(wantHead, 1.0 - kotlin.math.exp(-dt * 0.3)).normalizeOr(wantHead) } ?: wantHead
        smoothHead = headFwd

        // Sprung der Entity (Teleport, Neu-Spawn an anderer Stelle): Körper mitnehmen und überblenden.
        lastE?.let { prev ->
            if (prev.squaredDistanceTo(spine) > 12.0 * 12.0) {
                val delta = spine.subtract(prev)
                for (n in trail) n.p = n.p.add(delta)
                lastSpine = spine
                last = last?.map { it.add(delta) }?.toTypedArray()
                restAnchor = null
                startFade(now)
            }
        }
        lastE = spine
        // Seit 1.11.0 auch im Flug die echte Bodenhöhe unter ihr. Vorher galt nach dem Auftritt „Entity minus 8“ (die Entity
        // war noch nie gelandet): Beim Einschweben vor dem Tempel lag diese Scheinhöhe weit über dem Boden, der Kopf sank
        // mit der Entity herab, und der Körper blieb an der Scheinhöhe in der Luft hängen.
        if (!e.flying) groundY = s.y else {
            val top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(s.x), MathHelper.floor(s.z)).toDouble()
            if (top > world.bottomY + 1) groundY = minOf(top, s.y) else if (groundY.isNaN()) groundY = s.y - 40.0
        }
        val landingPose = e.pose == NivarisPose.LAND || e.pose == NivarisPose.LIE_DOWN || e.pose == NivarisPose.DOWNED || e.pose == NivarisPose.EXHAUSTED
        val alt = (s.y - groundY).coerceAtLeast(0.0)
        val wantGround = when {
            !e.flying -> 1f
            e.pose == NivarisPose.LAND && alt < 8.0 -> MathHelper.clamp(((8.0 - alt) / 8.0).toFloat() * 0.65f, 0f, 0.65f)
            else -> 0f
        }
        val groundRate = if (!e.flying || e.pose == NivarisPose.LAND) dt / 10f else dt / 16f
        ground = if (ground < 0f) wantGround else approach(ground, wantGround, groundRate)

        // Wegstand und Tempo.
        val step = lastSpine?.distanceTo(spine) ?: 0.0
        lastSpine = spine
        odo += step
        if (dt > 1e-3f) speed += (step / dt - speed) * (1.0 - kotlin.math.exp(-dt * 0.2))

        // Spur: neu gespawnt gerade nach hinten.
        if (trail.isEmpty()) {
            for (k in 0..60) trail.addLast(Node(spine.subtract(f.multiply(k * 2.0)), odo - k * 2.0))
        }
        // Rückwärts über die eigene Spur: verzehren statt falten.
        while (trail.size >= 3) {
            val d1 = spine.squaredDistanceTo(trail[1].p)
            if (d1 < spine.squaredDistanceTo(trail[0].p) && d1 < 2.25) trail.removeFirst() else break
        }
        // Fließend: der vorderste Punkt folgt der Rumpfmitte; erst 0,5 Blöcke weiter entsteht ein neuer.
        if (trail.size >= 2 && spine.squaredDistanceTo(trail[1].p) < 0.25) trail[0] = Node(spine, odo)
        else if (spine.squaredDistanceTo(trail.first().p) > 1e-6) trail.addFirst(Node(spine, odo))
        trimTrail()

        // Schlängeln im Flug: Welle je Spurpunkt nach seinem Wegstand (steht im Raum), hinter dem Hals eingeblendet.
        val poly = Poly()
        poly.add(spine, UP_V)
        run {
            var arc = 0.0
            val n = trail.size
            for (k in 0 until n) {
                val q = trail[k].p
                if (k > 0) arc += trail[k - 1].p.distanceTo(q)
                val tan = trail[max(0, k - 2)].p.subtract(trail[minOf(n - 1, k + 2)].p).normalizeOr(f)
                val side = Vec(tan.z, 0.0, -tan.x).normalizeOr(Vec(1.0, 0.0, 0.0))
                val ramp = smooth(MathHelper.clamp(((arc - 5.0) / 16.0).toFloat(), 0f, 1f)).toDouble()
                val ph = trail[k].odo
                val lat = ramp * LAT_AIR * kotlin.math.sin(ph * WAVE_LAT)
                val ver = ramp * VER_AIR * kotlin.math.sin(ph * WAVE_VER + 1.1)
                poly.add(q.add(side.multiply(lat)).add(0.0, ver, 0.0), UP_V)
            }
        }
        val tmpP = Array(BODY + 1) { Vec.ZERO }
        val tmpU = Array(BODY + 1) { UP_V }
        sample(poly, tmpP, tmpU, 0, NivarisShape.SEGMENT)
        repeat(2) { for (k in 1 until BODY) tmpP[k] = tmpP[k].multiply(0.5).add(tmpP[k - 1].add(tmpP[k + 1]).multiply(0.25)) }

        // Flugform: Richtung je Glied, Knick je Gelenk begrenzt (Gier fortlaufend, ohne Sprung bei ±180°).
        val backYaw = kotlin.math.atan2(-f.z, -f.x)
        val nearFloor = 1.0 - (alt / 11.0).coerceIn(0.0, 1.0)
        val landFlatten = maxOf(if (landingPose || !e.flying) 0.88 else 0.0, nearFloor * 0.78)
        val fYaw = DoubleArray(BODY)
        val fPitch = DoubleArray(BODY)
        for (k in 0 until BODY) {
            val d = tmpP[k + 1].subtract(tmpP[k])
            val flat = kotlin.math.sqrt(d.x * d.x + d.z * d.z)
            val yaw = if (flat > 1e-5) kotlin.math.atan2(d.z, d.x) else if (k > 0) fYaw[k - 1] else backYaw
            var pitch = kotlin.math.atan2(d.y, flat.coerceAtLeast(1e-5)).coerceIn(-MAX_PITCH, MAX_PITCH)
            // Bei Landung oder knapp über dem Boden: nach oben zeigende Spur abflachen, damit der Schwanz nicht in den Himmel steht.
            if (pitch > 0.0 && landFlatten > 0.01) pitch *= (1.0 - landFlatten * 0.92)
            fPitch[k] = if (k == 0) pitch else fPitch[k - 1] + (pitch - fPitch[k - 1]).coerceIn(-MAX_BEND, MAX_BEND)
            fYaw[k] = if (k == 0) backYaw + wrapRad(yaw - backYaw).coerceIn(-0.72, 0.72)
            else fYaw[k - 1] + wrapRad(yaw - fYaw[k - 1]).coerceIn(-MAX_BEND, MAX_BEND)
        }

        // Bodenform: gleitet vom aktuellen Verlauf in die vorgegebene Form.
        updateRest(world, e, spine, f, fYaw, dt)
        val w = smooth(ground).toDouble()

        // Endgültige Richtungen und Aufsummieren ab der Rumpfmitte (mit hartem Gelenk-Knicklimit gegen Falten).
        val idle = MathHelper.clamp((1.0 - speed / 0.25).toFloat(), 0f, 1f).toDouble()
        val sw = p[NivarisModel.SWEEP].toDouble()
        val maxJoint = MathHelper.lerp(w.toFloat(), MAX_BEND.toFloat(), MAX_BEND_GROUND.toFloat()).toDouble()
        var q = spine
        pts[NECK] = spine
        var carry = 0.0
        var prevYaw = backYaw
        for (k in 0 until BODY) {
            val i = NECK + 1 + k
            // Durchgehend abgewickelt, damit benachbarte Glieder nie in verschiedene Richtungen überblenden.
            var diff = wrapRad(restYaw[k] - fYaw[k])
            if (k > 0) diff = carry + wrapRad(diff - carry)
            carry = diff
            var yaw = fYaw[k] + diff * w
            var pitch = fPitch[k] * (1.0 - w)
            val u = i / POINTS.toDouble()
            yaw += (1.0 - w) * idle * (0.012 + 0.03 * u) * kotlin.math.sin(now * 0.12 - i * 0.38)
            pitch += (1.0 - w) * idle * (0.01 + 0.02 * u) * kotlin.math.sin(now * 0.09 - i * 0.3 + 1.3)
            yaw += w * 0.014 * kotlin.math.sin(now * 0.05 - i * 0.35)
            if (sw > 0.0) yaw += kotlin.math.sin(sw * 2.0 * Math.PI) * 0.45 * smooth(MathHelper.clamp((i - 10) / 18f, 0f, 1f))
            // Harte Winkelbegrenzung je Gelenk: Körper knickt nie zurück durch Hals oder Kopf.
            yaw = if (k == 0) backYaw + wrapRad(yaw - backYaw).coerceIn(-0.75, 0.75)
            else prevYaw + wrapRad(yaw - prevYaw).coerceIn(-maxJoint, maxJoint)
            prevYaw = yaw
            val cp = kotlin.math.cos(pitch)
            q = q.add(Vec(kotlin.math.cos(yaw) * cp, kotlin.math.sin(pitch), kotlin.math.sin(yaw) * cp).multiply(NivarisShape.SEGMENT))
            pts[i] = q
            ups[i] = UP_V
        }

        // Hals: Glieder 0..NECK-1 gleichmäßig auf dem Bogen vom Kopfgelenk zur Rumpfmitte.
        val neck = Poly()
        run {
            val d = h.distanceTo(spine).coerceAtLeast(1.0)
            var motion = spine.subtract(pts[NECK + 1]).normalizeOr(f)
            if (motion.dotProduct(f) < 0.25) motion = motion.add(f.multiply(0.85)).normalizeOr(f)
            bezier(neck, h, h.subtract(headFwd.multiply(0.35 * d)), spine.add(motion.multiply(0.35 * d)), spine, UP_V, 16)
        }
        sample(neck, pts, ups, 0, polyLength(neck) / NECK, NECK + 1)
        pts[NECK] = spine
        ups[NECK] = UP_V

        // Höhe: am Boden liegt jedes Glied auf der (geglätteten) Lauffläche, vorn steigt der Körper zur Rumpfmitte an.
        val floors = DoubleArray(POINTS) { floorAt(world, pts[it].x, pts[it].z) }
        repeat(3) {
            val c = floors.copyOf()
            for (k in 1 until POINTS - 1) floors[k] = c[k] * 0.5 + (c[k - 1] + c[k + 1]) * 0.25
        }
        for (k in 0 until BODY) {
            val i = NECK + 1 + k
            val arc = (k + 1) * NivarisShape.SEGMENT
            val rest = MathHelper.lerp(smooth(MathHelper.clamp((arc / 8.0).toFloat(), 0f, 1f)).toDouble(), spine.y, floors[i] + REST_UP)
            pts[i] = Vec(pts[i].x, MathHelper.lerp(w, pts[i].y, rest), pts[i].z)
        }
        for (i in 0 until POINTS) {
            val h0 = pts[i].y - (floors[i] + radius(i))
            air[i] = MathHelper.clamp((h0 / 2.5).toFloat(), 0f, 1f) * (1f - ground * 0.6f) + (1f - ground) * 0.4f
        }
        // Seit 1.12.0 (Crix: „kleiner Knick beim Hochziehen knapp über dem Boden“): Vorher wurde jedes Glied einzeln auf
        // die Bodenhöhe geschoben, das ergab eine Stufe an jeder Berührstelle. Jetzt eine weiche Hülle: Der nötige Hub läuft
        // mit begrenzter Steigung zu den Nachbarn aus, wird geglättet (nie unter den nötigen Wert) und ändert sich je Tick
        // nur begrenzt.
        val need = DoubleArray(POINTS)
        for (i in NECK + 1 until POINTS) {
            var floor = floors[i] + radius(i) * 0.9
            if (air[i] > 0.35f) {
                // Im Flug auch echtes Gelände unter dem langen Leib respektieren (Überhänge/Bögen > 8 Blöcke drüber ignorieren).
                val realTop = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(pts[i].x), MathHelper.floor(pts[i].z)).toDouble()
                if (realTop > world.bottomY + 1 && realTop <= pts[i].y + 8.0) floor = maxOf(floor, realTop + radius(i) + 0.5)
            }
            need[i] = max(0.0, floor - pts[i].y)
        }
        val raw = need.copyOf()
        for (i in NECK + 2 until POINTS) need[i] = max(need[i], need[i - 1] - LIFT_SLOPE)
        for (i in POINTS - 2 downTo NECK + 1) need[i] = max(need[i], need[i + 1] - LIFT_SLOPE)
        repeat(3) {
            val c = need.copyOf()
            for (k in NECK + 1 until POINTS - 1) need[k] = max(raw[k], c[k] * 0.5 + (c[k - 1] + c[k + 1]) * 0.25)
        }
        for (i in NECK + 1 until POINTS) {
            val rate = if (need[i] > lift[i]) LIFT_UP else LIFT_DOWN
            lift[i] += (need[i] - lift[i]).coerceIn(-rate * dt, rate * dt)
            // Nie mehr als einen halben Block im Boden, auch wenn die Begrenzung noch nachzieht.
            lift[i] = max(lift[i], raw[i] - 0.5)
            // Am Hals eingeblendet, damit die Rumpfmitte (Entity) keinen Absatz bekommt.
            val fade = smooth(MathHelper.clamp((i - NECK) / 4f, 0f, 1f)).toDouble()
            pts[i] = Vec(pts[i].x, pts[i].y + lift[i] * fade, pts[i].z)
        }
        return headFwd
    }

    // --- Vorgegebene Bodenformen (seit 1.11.0) ------------------------------------------------------------------------

    /** Aktuelle Bodenform: Gier je Glied, gleitet zu [restTarget]. */
    private val restYaw = DoubleArray(BODY)
    private val restTarget = DoubleArray(BODY)
    private var restLive = false
    private var restAnchor: Vec? = null
    private var restBase = 0.0
    private var restKind = SERPENT
    private var restSign = 1.0
    private var restCoiled = false

    /**
     * Am Boden: Solange sie sich schnell bewegt (Gletscherwalze, Würgegriff), liegt der Körper auf ihrer Spur; sonst
     * gleitet er in die gewählte Form. Dreht sich ihr Blick weit weg, schwenkt die Form langsam mit.
     */
    private fun updateRest(world: net.minecraft.world.World, e: dev.henny.hha_arius.boss.NivarisEntity, spine: Vec, f: Vec, fYaw: DoubleArray, dt: Float) {
        if (e.flying) {
            // In der Luft folgt die Bodenform dem Flug, damit die Landung von der echten Lage aus beginnt.
            if (ground <= 0.01f) {
                System.arraycopy(fYaw, 0, restYaw, 0, BODY)
                restLive = false
                restAnchor = null
            }
            return
        }
        if (!restLive) {
            System.arraycopy(fYaw, 0, restYaw, 0, BODY)
            restBase = fYaw[0]
            restLive = true
            restAnchor = null
        }
        val moving = speed > 0.55
        if (moving) {
            System.arraycopy(fYaw, 0, restTarget, 0, BODY)
            restAnchor = null
        } else {
            val coiled = e.pose == dev.henny.hha_arius.boss.NivarisPose.LIE_DOWN
            val back = kotlin.math.atan2(-f.z, -f.x)
            val anchor = restAnchor
            if (anchor == null || anchor.squaredDistanceTo(spine) > 12.0 * 12.0 || coiled != restCoiled) {
                restCoiled = coiled
                restAnchor = spine
                restBase = back + wrapRad(restYaw[0] - back).coerceIn(-0.45, 0.45)
                pickRest(world, spine, back, coiled)
            }
            // Körper-Basis immer nah hinter der Blickrichtung halten, damit der Leib nie nach vorn klappt.
            val off = wrapRad(back - restBase)
            if (kotlin.math.abs(off) > 0.65) restBase += off.coerceIn(-0.04 * dt, 0.04 * dt)
            for (k in 0 until BODY) restTarget[k] = restBase + restHeading(restKind, (k + 0.5) * NivarisShape.SEGMENT) * restSign
        }
        val rate = 1.0 - kotlin.math.exp(-dt * (if (moving) 0.4 else 0.11))
        for (k in 0 until BODY) restYaw[k] += wrapRad(restTarget[k] - restYaw[k]) * rate
        for (k in 1 until BODY) restYaw[k] = restYaw[k - 1] + wrapRad(restYaw[k] - restYaw[k - 1]).coerceIn(-MAX_BEND_GROUND, MAX_BEND_GROUND)
    }

    /** Gierabweichung der Form nach [s] Blöcken ab der Rumpfmitte (0 = gerade nach hinten). */
    private fun restHeading(kind: Int, s: Double): Double = when (kind) {
        // Ruhige, weite Schlangenlinie ohne Selbstkreuzung.
        SERPENT -> 0.68 * kotlin.math.sin(2.0 * Math.PI * s / 64.0)
        // Etwas kompaktere S-Kurve für engeren Platz.
        COMPACT -> 0.95 * kotlin.math.sin(2.0 * Math.PI * s / 58.0)
        // Weiter, ruhiger Ruhe-Bogen (Sichel, max. ~135°): kein enges Einrollen durch den eigenen Kopf.
        else -> {
            val len = BODY * NivarisShape.SEGMENT
            val u = (s / len).coerceIn(0.0, 1.0)
            2.35 * (u * u * (3.0 - 2.0 * u))
        }
    }

    /** Wählt Form, Drehsinn und Ausrichtung so, dass möglichst kein Glied in Fels oder über einem Abgrund liegt. */
    private fun pickRest(world: net.minecraft.world.World, spine: Vec, back: Double, coiled: Boolean) {
        val kinds = if (coiled) intArrayOf(COILED, SERPENT) else intArrayOf(SERPENT, COMPACT, COILED)
        var best = Double.MAX_VALUE
        var bestKind = restKind
        var bestSign = restSign
        var bestBase = back
        for ((ki, kind) in kinds.withIndex()) for (off in REST_OFFSETS) for (sign in doubleArrayOf(1.0, -1.0)) {
            var x = spine.x
            var z = spine.z
            var bad = 0
            for (k in 0 until BODY) {
                val yaw = back + off + restHeading(kind, (k + 0.5) * NivarisShape.SEGMENT) * sign
                x += kotlin.math.cos(yaw) * NivarisShape.SEGMENT
                z += kotlin.math.sin(yaw) * NivarisShape.SEGMENT
                val top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(x), MathHelper.floor(z)).toDouble()
                if (top > groundY + 3.5 || top < groundY - 6.0) bad++
            }
            val score = bad * 10.0 + kotlin.math.abs(off) * 2.0 + ki * 3.0 + (if (sign == restSign) 0.0 else 0.5)
            if (score < best) {
                best = score
                bestKind = kind
                bestSign = sign
                bestBase = back + off
            }
        }
        restKind = bestKind
        restSign = bestSign
        restBase = bestBase
    }

    private fun wrapRad(a: Double): Double {
        var v = a % (2.0 * Math.PI)
        if (v > Math.PI) v -= 2.0 * Math.PI
        if (v < -Math.PI) v += 2.0 * Math.PI
        return v
    }

    /** Lauffläche unter (x, z): Höhenkarte der Welt, Säulen und Wände (deutlich über dem Boden) zählen nicht. */
    private fun floorAt(world: net.minecraft.world.World, x: Double, z: Double): Double {
        val gy = groundY
        if (gy.isNaN()) return -1.0e9
        val top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(x), MathHelper.floor(z)).toDouble()
        return if (top > gy + 3.5 || top < gy - 6.0) gy else top
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
            if (prev != null) len += prev.distanceTo(q.p)
            prev = q.p
            keep++
            if (len > NivarisShape.SEGMENT * POINTS + 30.0) break
        }
        while (trail.size > keep) trail.removeLast()
    }

    /** Hals vom Kopfgelenk (ruhend → aufgerichtet nach [raise]) zur Spitze der Helix, dann die Helix hinab. */
    private fun coilPoly(poly: Poly, o: Vec, raise: Double, now: Float) {
        val axis = o.add(ArenaLayout.SPIRE.x + 0.5, 0.0, ArenaLayout.SPIRE.z + 0.5)
        val h = o.add(EntrancePathC.HEAD_REST.lerp(EntrancePathC.HEAD_RAISED, raise)).add(0.0, kotlin.math.sin(now * 0.05) * 0.25, 0.0)
        val c1 = o.add(EntrancePathC.coil(1.0))
        val t1 = c1.subtract(o.add(EntrancePathC.coil(0.995))).normalize()
        val d = h.distanceTo(c1).coerceAtLeast(2.0)
        val neckUp = Vec(0.0, 1.0, 0.6).normalize()
        bezier(poly, h, h.add(0.0, -0.45 * d, 0.12 * d), c1.add(t1.multiply(0.4 * d)), c1, neckUp, 14)
        var sCoil = 1.0
        while (sCoil >= 0.0) {
            val q = o.add(EntrancePathC.coil(sCoil))
            val radial = Vec(q.x - axis.x, 0.0, q.z - axis.z).normalizeOr(UP_V)
            val pos = q.add(radial.multiply(0.15 * kotlin.math.sin(now * 0.05 + sCoil * 9.0)))
            poly.add(pos, radial.multiply(0.9).add(0.0, 0.45, 0.0).normalize())
            sCoil -= 0.002
        }
    }

    private fun polyLength(poly: Poly): Double {
        var len = 0.0
        for (k in 1 until poly.pts.size) len += poly.pts[k - 1].distanceTo(poly.pts[k])
        return len
    }

    /**
     * Punkte im Bogenabstand [spacing] (Standard: Gliedlänge) ab dem Anfang der Linie, geschrieben ab [from] bis vor
     * [until]; zu kurze Linien laufen geradeaus weiter.
     */
    private fun sample(poly: Poly, outP: Array<Vec>, outU: Array<Vec>, from: Int, spacing: Double, until: Int = outP.size) {
        val pts = poly.pts
        if (pts.isEmpty()) return
        val sp = if (spacing > 0.0) spacing else NivarisShape.SEGMENT
        outP[from] = pts[0]
        outU[from] = poly.ups[0]
        var seg = 0
        var acc = 0.0
        for (i in from + 1 until until) {
            val target = (i - from) * sp
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

    /**
     * Rahmen je Glied (9 Werte: Modell-x = links, Modell-y = −oben, Modell-z = nach hinten).
     * Paralleler Transport (Bishop-Rahmen) verhindert 180°-Umsprünge (Frenet-Flips) und NaN-Matrizen in engen
     * Kurven oder beim Steigen/Sinken; [bodyRoll] dreht den ganzen Leib wellenförmig (Fassrolle beim Abwurf).
     */
    private fun frames(pts: Array<Vec>, ups: Array<Vec>, now: Float, air: FloatArray, bodyRoll: Double): FloatArray {
        val out = FloatArray(NivarisShape.SEGMENTS * 9)
        var prevB = pts[1].subtract(pts[0]).normalizeOr(Vec(0.0, 0.0, 1.0))
        var prevU = ups[0].subtract(prevB.multiply(ups[0].dotProduct(prevB))).normalizeOr(orthoFallback(prevB))
        for (i in 0 until NivarisShape.SEGMENTS) {
            val b = pts[i + 1].subtract(pts[i]).normalizeOr(prevB)
            // Paralleler Transport von prevU entlang prevB -> b (stetig, kein Frenet-Flip).
            var u = if (i == 0) prevU else transport(prevU, prevB, b)
            u = u.subtract(b.multiply(u.dotProduct(b))).normalizeOr(orthoFallback(b))
            // Sanftes Aufrichten zum Ziel-Up, solange eindeutig und in derselben Halbkugel.
            val hint = ups[i].add(ups[i + 1])
            val proj = hint.subtract(b.multiply(hint.dotProduct(b)))
            if (proj.lengthSquared() > 0.09) {
                val targetU = proj.normalize()
                if (targetU.dotProduct(u) > -0.15) {
                    u = u.lerp(targetU, if (i == 0) 0.75 else 0.28).normalizeOr(u)
                    u = u.subtract(b.multiply(u.dotProduct(b))).normalizeOr(orthoFallback(b))
                }
            }
            var l = b.crossProduct(u).normalizeOr(orthoFallback(b))
            u = l.crossProduct(b).normalizeOr(u)
            // Grundrollen + explizite Fassrolle (läuft wellenförmig vom Bug zum Schwanz).
            val waveRoll = bodyRoll * (1.0 - 0.008 * i)
            val roll = waveRoll + (0.02 + 0.05 * air[i]) * kotlin.math.sin(now * 0.05 - i * 0.2)
            val cr = kotlin.math.cos(roll)
            val sr = kotlin.math.sin(roll)
            val u2 = u.multiply(cr).add(l.multiply(sr)).normalizeOr(u)
            val l2 = b.crossProduct(u2).normalizeOr(l)
            val u3 = l2.crossProduct(b).normalizeOr(u2)
            put(out, i * 9, l2, u3.negate(), b)
            // Für den nächsten Transport den ungerollten Rahmen merken, damit sich die Welle nicht aufsummiert.
            prevU = u
            prevB = b
        }
        return out
    }

    /** Minimaler paralleler Transport von [u] bei Richtungswechsel [from] -> [to]. */
    private fun transport(u: Vec, from: Vec, to: Vec): Vec {
        val axis = from.crossProduct(to)
        val sinA = axis.length()
        if (sinA < 1e-6) return u
        val cosA = from.dotProduct(to).coerceIn(-1.0, 1.0)
        val k = axis.multiply(1.0 / sinA)
        // Rodrigues: u*cos + (k × u)*sin + k*(k·u)*(1 - cos)
        return u.multiply(cosA)
            .add(k.crossProduct(u).multiply(sinA))
            .add(k.multiply(k.dotProduct(u) * (1.0 - cosA)))
    }

    private fun orthoFallback(b: Vec): Vec {
        val ref = if (kotlin.math.abs(b.y) < 0.9) UP_V else Vec(1.0, 0.0, 0.0)
        return ref.subtract(b.multiply(ref.dotProduct(b))).normalizeOr(Vec(0.0, 1.0, 0.0))
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

    private fun approach(v: Float, target: Float, step: Float) = if (v < target) minOf(target, v + step) else maxOf(target, v - step)

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun Vec.normalizeOr(fallback: Vec): Vec = if (lengthSquared() < 1e-10) fallback else normalize()

    private companion object {
        const val POINTS = NivarisRenderState.POINTS

        /** Bodenhub: höchste Steigung je Glied (Blöcke) und Änderung je Tick nach oben/unten. */
        const val LIFT_SLOPE = 0.42
        const val LIFT_UP = 0.55
        const val LIFT_DOWN = 0.22

        /** Glieder des Halses (0..NECK-1) zwischen Kopfgelenk und Rumpfmitte; Punkt NECK = Rumpfmitte. */
        const val NECK = 3

        /** Rumpfmitte über der Lauffläche, wenn der Körper liegt. */
        const val REST_UP = 1.0

        /** Glieder von der Rumpfmitte bis zur Schwanzspitze. */
        const val BODY = POINTS - NECK - 1

        /** Schlängeln im Flug: Ausschlag quer, auf und ab (Blöcke), Wellenlängen 34 bzw. 52 Blöcke. */
        const val LAT_AIR = 1.5
        const val VER_AIR = 0.9
        const val WAVE_LAT = 2.0 * Math.PI / 34.0
        const val WAVE_VER = 2.0 * Math.PI / 52.0
        val UP_V = Vec(0.0, 1.0, 0.0)

        /** Größter Knick je Gelenk (Bogenmaß): in der Luft Radius 18 Blöcke, am Boden Radius 11 (keine Selbstkreuzung). */
        const val MAX_BEND = NivarisShape.SEGMENT / 18.0
        const val MAX_BEND_GROUND = NivarisShape.SEGMENT / 11.0
        const val MAX_PITCH = 0.58

        /** Vorgegebene Bodenformen. */
        const val SERPENT = 0
        const val COMPACT = 1
        const val COILED = 2
        val REST_OFFSETS = doubleArrayOf(0.0, 0.25, -0.25, 0.5, -0.5)
    }
}

private typealias Vec = net.minecraft.util.math.Vec3d
private typealias ArenaLayout = dev.henny.hha_arius.arena.ArenaLayout
private typealias EntrancePathC = dev.henny.hha_arius.boss.EntrancePath
private typealias NivarisModeC = dev.henny.hha_arius.boss.NivarisMode

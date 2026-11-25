package dev.henny.hha_arius.client.model.nivaris

import dev.henny.hha_arius.boss.AriusTimings
import dev.henny.hha_arius.boss.NivarisPose
import dev.henny.hha_arius.boss.NivarisShape
import dev.henny.hha_arius.boss.ReturnCoil
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
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.FarPlan
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.block.Blocks
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.world.Heightmap
import net.minecraft.world.World
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
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
    /** Geglättete Flügelöffnung 0..1 (verhindert Umschnappen bei Posenwechseln). */
    var wingOpen = 0f
    var spireGrip = 0f

    /** Läufe am Boden angelegt 0..1 (vorn, hinten): so weit, dass die Füße auf dem Boden statt darin stehen ([NivarisLegs]). */
    val legFolds = FloatArray(4) // fore left/right, hind left/right

    /** Sturm-Silhouette 0..1: Körper als dunkle marineblaue Form (Auftritt im Schneesturm). */
    var shade = 0f

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
 * Ground fold of the legs. The authored leg poses stand almost straight down (about 2.7 blocks below the spine at the
 * forelegs, 2.9 at the hind legs), but a lying or landed body rests only about one block above the snow. The fold
 * swings the upper leg back along the flank, straightens the knee and keeps the sole flat, by exactly as much as the
 * ground under the leg requires ([NivarisBody] solves the amount, model and renderer apply it).
 * Lengths in model units of the leg parts (NivarisModelData), angles of the joints as offsets to the model's own.
 */
internal object NivarisLegs {
    /** Tuck angle (rad, + = back) used only when the body actually lies low on the snow (LIE_DOWN/DOWNED) or for hind legs. Standing poses hold the chest high enough that forelegs stand straight (fold ≈ 0). */
    const val FOLD_UPPER = 1.32f
    private val FOLD_UPPER_DEG = Math.toDegrees(FOLD_UPPER.toDouble()).toFloat()

    /** Built-in pitch of the lower leg (degrees); the foot carries the opposite angle. */
    private fun knee(fore: Boolean) = if (fore) -40f else 30f

    fun upper(authored: Float, fold: Float): Float = MathHelper.lerp(fold, authored, FOLD_UPPER)

    /** Lower leg offset: keeps a bent knee when crouched/lying without pushing the body up. */
    fun lower(fore: Boolean, authoredDeg: Float, fold: Float): Float =
        MathHelper.lerp(fold, authoredDeg, if (fore) 24f else -28f)

    /** Foot offset: keeps the sole level with the ground across the fold range. */
    fun foot(fore: Boolean, authoredDeg: Float, fold: Float): Float =
        MathHelper.lerp(fold, authoredDeg, (if (fore) -24f else 28f) - FOLD_UPPER_DEG)

    fun rotation(state: NivarisRenderState, fore: Boolean, right: Boolean, fold: Float): Quaternionf {
        val cling = state.mode == NivarisModeC.RETURN_COIL
        val base = if (cling) 1.48f else state.p[if (fore) NivarisModel.FORE else NivarisModel.HIND]
        val swipe = if (fore && right && !cling) state.p[NivarisModel.SWIPE] else 0f
        val rot = Quaternionf().rotationZYX(-0.6f * swipe, 0.3f * swipe, upper(base - 0.9f * swipe, fold))
        if (state.mode == NivarisModeC.COIL) {
            // Reach firmly inward onto the blue-ice spire so paws visibly clamp onto the ice instead of dangling in air.
            val side = if (right) 1f else -1f
            rot.slerp(Quaternionf().rotationZYX(side * (if (fore) 0.44f else 0.42f), side * 0.14f, 0.18f), 0.9f)
        }
        return rot
    }
}

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
        for (index in state.legFolds.indices) poseLeg(state, index, state.legFolds[index])
        val deg = MathHelper.RADIANS_PER_DEGREE
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

    fun poseLeg(state: NivarisRenderState, index: Int, fold: Float): ModelPart {
        val fore = index < 2
        val joints = (if (fore) foreJoints else hindJoints)[index % 2]
        val authored = state.p[if (fore) FORE else HIND]
        val k = if (state.mode == NivarisModeC.RETURN_COIL) 1f else ((authored - 0.2f) / 0.9f).coerceIn(0f, 1f)
        val elbow = MathHelper.lerp(state.spireGrip, -20f * k, if (fore) 16f else -14f)
        val toe = MathHelper.lerp(state.spireGrip, 50f * k, if (fore) -14f else 12f)
        joints.getOrNull(0)?.set(pitch = NivarisLegs.lower(fore, elbow, fold) * MathHelper.RADIANS_PER_DEGREE)
        joints.getOrNull(1)?.set(pitch = NivarisLegs.foot(fore, toe, fold) * MathHelper.RADIANS_PER_DEGREE)
        return if (fore) (if (index == 0) foreL else foreR) else (if (index == 2) hindL else hindR)
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

        // Auf der Eisnadel beim Aufrichten/Brüllen: Schwingen majestätisch öffnen statt schlaff senkrecht hängen lassen.
        if (state.mode == NivarisModeC.COIL && pose != NivarisPose.LIE_DOWN) {
            val spread = smooth((t / 28f).coerceIn(0f, 1f))
            val wantWing = if (pose == NivarisPose.ROAR) 0.85f else 0.58f
            p[WING] = maxOf(p[WING], wantWing * spread)
            p[FLAP] = maxOf(p[FLAP], 0.35f * spread)
        }
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
                val since = t - w
                if (since < 0f) {
                    // Brems-Flügelschlag vor dem Aufsetzen: weit gespreizt, starker Gegenschlag; kurz vor Bodenkontakt Läufe zum Aufsetzen zurückführen.
                    val k = MathHelper.clamp(t / max(w, 1f), 0f, 1f)
                    val brake = sin(k * Math.PI.toFloat() * 2.5f)
                    val plant = smooth(((k - 0.65f) / 0.35f).coerceIn(0f, 1f))
                    p[WING] = 1f
                    p[FLAP] = MathHelper.lerp(plant, 0.25f + brake * 0.78f, 0.12f)
                    p[FORE] = MathHelper.lerp(plant, MathHelper.lerp(k, -0.45f, -0.68f), 0.15f)
                    p[HIND] = MathHelper.lerp(plant, MathHelper.lerp(k, -0.25f, -0.42f), 0.20f)
                    p[REAR] += (1f - k) * 0.65f
                } else {
                    // Aufprall: der Kopf schwingt nach unten nach und federt gedämpft zurück, der Körper staucht.
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
        private fun k(h: NivarisShape.Head, jaw: Float = 0.04f, wing: Float = 0f, flap: Float = 0f, fore: Float = 0.02f, swipe: Float = 0f,
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
class NivarisBody(private val model: NivarisModel) {

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
    private var wasFlying = false
    private var takeoffOdo = -100.0
    private var smSlope = Double.NaN

    /** Aktueller Bodenhub je Punkt (siehe [normal]). */
    private val lift = DoubleArray(POINTS)

    /** Geglättete Bein-Beugung je Lauf (verhindert Zittern an Schneestufen und beim Aufsetzen). */
    private val smLegFolds = FloatArray(4)

    private var surfaceCache: java.lang.ref.WeakReference<DragonSurface>? = null
    private var hadCarryFrame = false
    private var entranceLandingAge = Float.NEGATIVE_INFINITY

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
        val terrain = surfaceCache?.get()?.takeIf { it.matches(world, origin) }
            ?: DragonSurface(world, origin).also { surfaceCache = java.lang.ref.WeakReference(it) }
        terrain.beginFrame()
        val o = Vec(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())

        // Wechsel der Darstellung: weich überblenden, beim Übergang in den Kampf die Spur exakt aus dem letzten Körper säen.
        if (lastMode != -1 && mode != lastMode) {
            if (lastMode == NivarisModeC.UNCOIL && mode == NivarisModeC.NORMAL) entranceLandingAge = now
            startFade(now)
            lastE = null
            lastSpine = null
            if (mode == NivarisModeC.NORMAL) last?.let { l ->
                trail.clear()
                var od = odo
                ground = 0f
                restLive = false
                wasFlying = e.flying
                speed = 0.75
                lift.fill(0.0)
                takeoffOdo = odo - 40.0
                for (k in NECK until l.size) {
                    trail.addLast(Node(l[k], od))
                    od -= NivarisShape.SEGMENT
                }
                lastSpine = l[NECK]
                if (l.size > 1) smoothHead = l[0].subtract(l[1]).normalizeOr(Vec(0.0, 0.0, -1.0))
                if (l.size > NECK + 1) {
                    val fwd = l[NECK].subtract(l[NECK + 1])
                    if (fwd.x * fwd.x + fwd.z * fwd.z > 1e-4) {
                        smYaw = (Math.toDegrees(kotlin.math.atan2(fwd.z, fwd.x)) - 90.0).toFloat()
                    }
                }
            }
        }
        lastMode = mode

        val pts = Array(POINTS) { Vec.ZERO }
        val ups = Array(POINTS) { UP_V }
        val air = FloatArray(POINTS)
        var headFwd: Vec
        val carry = dev.henny.hha_arius.client.fx.RideClient.carrySpine(e.id, tickDelta)
        if (carry != null) {
            if (!hadCarryFrame) startFade(now)
            for (i in pts.indices) {
                pts[i] = carry.first[i]
                air[i] = s.fly
            }
            val tangent = pts[0].subtract(pts[1]).normalize()
            headFwd = tangent.add(0.0, -0.22 * (1.0 - s.fly), 0.0).normalize()
            smYaw = s.bodyYaw
        } else {
            if (hadCarryFrame) {
                startFade(now)
                trail.clear()
                lastE = null
                last?.let { points ->
                    for (i in NECK until points.size) {
                        trail.addLast(Node(points[i], odo - (i - NECK) * NivarisShape.SEGMENT))
                    }
                    lastSpine = points[NECK]
                }
                restLive = false
            }
            when (mode) {
                NivarisModeC.RETURN_COIL -> {
                    val poly = Poly()
                    for (point in ReturnCoil.points) {
                        poly.add(o.add(point), ReturnCoil.outward(point))
                    }
                    sample(poly, pts, ups, 0, 0.0)
                    headFwd = ReturnCoil.facing
                }
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
                    // Uniform segment spacing (as in 1.11.0): keeps sculpted dragon neck proportions instead of 1.75x stretched cylinders.
                    sample(poly, pts, ups, 0, 0.0)
                    headFwd = if (mode == NivarisModeC.UNCOIL && u > 0.5) {
                        EntrancePathC.tangent(u).add(0.0, -0.08, 0.0).normalize()
                    } else {
                        val neckDir = pts[0].subtract(pts[1]).normalizeOr(Vec(0.0, 0.0, -1.0))
                        val arenaLook = yawPitch(Math.PI.toFloat() + p[NivarisModel.YAW], p[NivarisModel.PITCH] + 0.15f * (1f - r))
                        neckDir.lerp(arenaLook, 0.45 + 0.55 * r).normalizeOr(arenaLook)
                    }
                    smYaw = s.bodyYaw
                    for (i in 0 until POINTS) air[i] = if (mode == NivarisModeC.UNCOIL && i * NivarisShape.SEGMENT < u) {
                        MathHelper.clamp(((u - i * NivarisShape.SEGMENT) / 20.0).toFloat(), 0f, 1f)
                    } else 0f
                }
                else -> headFwd = normal(e, s, p, base, dt, now, pts, ups, air, terrain)
            }
        }
        hadCarryFrame = carry != null

        // Schräglage in Kurven (Flug) und Rückenseite am Hals nach hinten; an der Nadel (COIL / RETURN_COIL) bleibt der Kamm exakt nach außen gerichtet.
        val onSpire = mode == NivarisModeC.COIL || mode == NivarisModeC.RETURN_COIL
        for (i in 0 until POINTS) {
            var up = ups[i]
            if (!onSpire) {
                if (air[i] > 0.01f && i in 1 until POINTS - 1) {
                    val k = pts[i - 1].add(pts[i + 1]).subtract(pts[i].multiply(2.0))
                    // Schräglage begrenzt (höchstens ~30°): enge Kurven kippen den Körper nicht um 90°.
                    var bank = Vec(k.x, 0.0, k.z).multiply(2.0 * air[i])
                    if (bank.length() > 0.55) bank = bank.normalize().multiply(0.55)
                    up = up.add(bank)
                }
                val back = max(0f, 1f - i / 6f) * 0.5
                ups[i] = up.subtract(headFwd.multiply(back)).normalizeOr(UP_V)
            } else {
                ups[i] = up.normalizeOr(UP_V)
            }
        }

        // Überblendung nach Wechsel/Sprung (Prüfung an der Schulter, damit lange Schwanzschwenks die Blende nicht abbrechen).
        fadeFrom?.let { from ->
            val duration = if (carry != null) 54f else 22f
            val k = (now - fadeAt) / duration
            if (k >= 1f || from.size != POINTS) {
                fadeFrom = null
            } else {
                val headJump = from[NECK].squaredDistanceTo(pts[NECK])
                if (headJump > 45.0 * 45.0) {
                    fadeFrom = null // Überspringen/Neustart: kein Geisterstreifen quer durchs Bild
                } else {
                    for (i in 0 until POINTS) {
                        val elapsed = if (carry != null) {
                            val local = 6f + (duration - 6f) * (i.toFloat() / (POINTS - 1))
                            ((now - fadeAt) / local).coerceIn(0f, 1f)
                        } else k
                        val blendFrom = if (carry != null && i <= 3) {
                            // The planted starting pose translates with the charge during the short head blend.
                            from[i].add(pts[3].subtract(from[3]))
                        } else from[i]
                        pts[i] = blendFrom.lerp(pts[i], smooth(elapsed).toDouble())
                    }
                }
            }
        }
        // The rear half is still sinking from the descent when the front feet stop.
        // Feed the settled shape in continuously even if the terrain under the tail steps down.
        if (carry == null && mode == NivarisModeC.NORMAL && now - entranceLandingAge in 0f..74f) {
            // The rendered muzzle extends ahead of the sampled head joint. Keep the jaw
            // above the snow while she lowers it toward the people on the plateau.
            val jawClearance = (base.y + 2.7 - pts[0].y).coerceAtLeast(0.0)
            for (i in 0 until NECK) pts[i] = pts[i].add(0.0, jawClearance * (1.0 - i.toDouble() / NECK), 0.0)
            if (headFwd.y < -0.22) {
                val flat = Vec(headFwd.x, 0.0, headFwd.z).normalizeOr(Vec(0.0, 0.0, -1.0))
                headFwd = flat.add(0.0, -0.22, 0.0).normalize()
            }
            val step = 2.30 * dt
            last?.let { before ->
                for (i in NivarisShape.HIND_GLIED + 1 until POINTS) {
                    val move = pts[i].subtract(before[i])
                    if (move.lengthSquared() > step * step) pts[i] = before[i].add(move.normalize().multiply(step))
                }
            }
        }

        // Endgültige Rahmen und Werte für den Renderer (mit parallelem Transport + Fassrolle).
        val bodyRoll = carry?.second?.toDouble() ?: e.rollAt(tickDelta).toDouble()
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
        model.openJaw(s.p[NivarisModel.JAW])
        model.animateChildren(s)
        fun updateFrames() {
            frames(pts, ups, now, air, bodyRoll, onSpire, carry != null).copyInto(s.frame)
            for (i in s.girth.indices) {
                val breath = if (carry == null && i in 1..6) 1f + 0.025f * p[NivarisModel.BREATHE] else 1f
                s.girth[i] = NivarisShape.taper(i) * breath
                s.stretch[i] = (pts[i].distanceTo(pts[i + 1]) / NivarisShape.SEGMENT).toFloat().coerceIn(0.5f, 1.8f)
            }
        }
        updateFrames()
        if (carry == null && mode != NivarisModeC.COIL && mode != NivarisModeC.UNCOIL && mode != NivarisModeC.RETURN_COIL) {
            for (pass in 0..2) {
                val need = contacts(terrain, s, pts, solveLegs = false)
                val peak = need.max()
                if (pass == 0) {
                    for (i in lift.indices) need[i] = maxOf(need[i], lift[i] - LIFT_DOWN * dt)
                }
                if (need.max() < 0.001) {
                    if (pass == 0) lift.fill(0.0)
                    break
                }
                for (i in 1 until POINTS) need[i] = max(need[i], need[i - 1] - LIFT_SLOPE)
                for (i in POINTS - 2 downTo 0) need[i] = max(need[i], need[i + 1] - LIFT_SLOPE)
                for (i in pts.indices) {
                    // A common final residual preserves the measured orientations.
                    val dy = if (pass == 2) peak else need[i]
                    pts[i] = pts[i].add(0.0, dy, 0.0)
                    if (pass == 0) lift[i] = dy else lift[i] += dy
                }
                if (pass < 2) updateFrames()
            }
            // Solve leg folds ONCE against the final settled body (using solid snow collision shapes so thin snow-layer
            // corners never lift a paw into mid-air), couple left/right pairs softly, and smooth temporally to stop jitter.
            val targetFolds = solveLegFolds(terrain, s, pts)
            val rate = 1f - kotlin.math.exp(-dt * 0.35f)
            val maxStep = dt * 0.14f
            for (k in smLegFolds.indices) {
                val delta = MathHelper.clamp((targetFolds[k] - smLegFolds[k]) * rate, -maxStep, maxStep)
                smLegFolds[k] = MathHelper.clamp(smLegFolds[k] + delta, 0f, 1f)
            }
            smLegFolds.copyInto(s.legFolds)
        } else {
            lift.fill(0.0)
            smLegFolds.fill(0f)
            s.legFolds.fill(0f)
        }
        if (carry != null) dev.henny.hha_arius.client.fx.RideClient.recordCarrySurface(carry.first, pts)
        last = pts
        for (i in 0 until POINTS) {
            s.px[i] = (pts[i].x - s.x).toFloat()
            s.py[i] = (pts[i].y - s.y).toFloat()
            s.pz[i] = (pts[i].z - s.z).toFloat()
            val light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(pts[i]))
            val sky = max(LightmapTextureManager.getSkyLightCoordinates(light), 9)
            s.lights[i] = LightmapTextureManager.pack(LightmapTextureManager.getBlockLightCoordinates(light), sky)
        }
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
        pts: Array<Vec>, ups: Array<Vec>, air: FloatArray, terrain: DragonSurface,
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
        // On the ground in standing poses (IDLE, ROAR, combat — not LIE_DOWN/DOWNED/EXHAUSTED), hold the chest at true
        // foreleg height (~2.82 blocks above snow) so both forelegs stand straight and flat-footed instead of folding back.
        val stand = smooth(ground.coerceIn(0f, 1f)) *
            (1f - MathHelper.clamp(p[NivarisModel.SLACK] / 0.25f, 0f, 1f)) *
            smooth(MathHelper.clamp((p[NivarisModel.REAR] + 0.45f) / 0.45f, 0f, 1f))
        val rearUp = if (p[NivarisModel.REAR] > 0f) p[NivarisModel.REAR] * MathHelper.lerp(stand, 0.5f, 0.14f) else p[NivarisModel.REAR] * 0.5f
        val spine = base.add(0.0, NivarisShape.SPINE_UP + stand * 0.95 + rearUp, 0.0)
        // Follow the slope of the same trail that drives the shoulders.
        val previous = trail.firstOrNull { it.p.squaredDistanceTo(spine) >= 4.0 }?.p
        val wantSlope = if (e.flying && previous != null) {
            val delta = spine.subtract(previous)
            kotlin.math.atan2(delta.y, kotlin.math.hypot(delta.x, delta.z)).coerceIn(-0.48, 0.48) * (1.0 - ground.coerceAtLeast(0f))
        } else 0.0
        // Begrenzte Änderung je Tick: Der Kopf sitzt ~7 Blöcke vor der Rumpfmitte, ein Sprung der Spurneigung (Knick in der
        // Bahn, Spurpunkt wechselt) warf ihn sonst in einem Tick um bis zu 5 Blöcke herum.
        smSlope = if (smSlope.isNaN()) wantSlope else smSlope + (wantSlope - smSlope).coerceIn(-0.035 * dt, 0.035 * dt)
        val flightSlope = smSlope
        val neckWave = (1.0 - ground.coerceIn(0f, 1f)) * kotlin.math.sin(odo * WAVE_LAT + 0.6)
        val forward = p[NivarisModel.FWD].toDouble()
        val h = base.add(f.multiply(forward * kotlin.math.cos(flightSlope)))
            .add(0.0, p[NivarisModel.UP] + forward * kotlin.math.sin(flightSlope) + 0.23 * neckWave, 0.0)
            .add(Vec(f.z, 0.0, -f.x).multiply(0.45 * neckWave))
        val wantHead = yawPitch(yawRad + p[NivarisModel.YAW] + (0.06 * neckWave).toFloat(),
            p[NivarisModel.PITCH] - flightSlope.toFloat())
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
        val baseFloor = terrain.floor(base.x, base.z, base.y + 2.0, base.y - 40.0)
        groundY = if (baseFloor != null) minOf(baseFloor, base.y) else if (e.flying) base.y - 40.0 else base.y
        val alt = (s.y - groundY).coerceAtLeast(0.0)
        val wantGround = if (!e.flying) 1f else 0f
        // Aufsetzen über 16 Ticks: Der hoch nachgezogene Schwanz senkt sich sonst fast 3 Blöcke je Tick ab.
        val groundRate = dt / 16f
        ground = if (ground < 0f) wantGround else approach(ground, wantGround, groundRate)

        // Wegstand und Tempo.
        val step = lastSpine?.distanceTo(spine) ?: 0.0
        lastSpine = spine
        odo += step
        if (dt > 1e-3f) speed += (step / dt - speed) * (1.0 - kotlin.math.exp(-dt * 0.2))

        // Beim Abheben vom Boden die Flugspur exakt aus dem tatsächlich liegenden Körper säen (keine Buckel/Sprünge!).
        if (e.flying && !wasFlying) {
            startFade(now)
            takeoffOdo = odo
            last?.let { l ->
                trail.clear()
                var od = odo
                for (k in NECK until l.size) {
                    trail.addLast(Node(l[k], od))
                    od -= NivarisShape.SEGMENT
                }
            }
        }
        wasFlying = e.flying

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

        // Schlängeln im Flug: Welle je Spurpunkt nach seinem Wegstand, beim Abheben erst sanft einblenden.
        val flightWave = smooth(MathHelper.clamp(((odo - takeoffOdo) / 28.0).toFloat(), 0f, 1f)).toDouble()
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
                val ramp = smooth(MathHelper.clamp(((arc - 5.0) / 16.0).toFloat(), 0f, 1f)).toDouble() * flightWave
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
        val landFlatten = nearFloor * smooth(ground).toDouble()
        val fYaw = DoubleArray(BODY)
        val rawYaw = DoubleArray(BODY)
        val fPitch = DoubleArray(BODY)
        val bendLimit = MathHelper.lerp(ground.coerceIn(0f, 1f), MAX_BEND.toFloat(), MAX_BEND_GROUND.toFloat()).toDouble()
        for (k in 0 until BODY) {
            val d = tmpP[k + 1].subtract(tmpP[k])
            val flat = kotlin.math.sqrt(d.x * d.x + d.z * d.z)
            val yaw = if (flat > 1e-5) kotlin.math.atan2(d.z, d.x) else if (k > 0) fYaw[k - 1] else backYaw
            var pitch = kotlin.math.atan2(d.y, flat.coerceAtLeast(1e-5)).coerceIn(-MAX_PITCH, MAX_PITCH)
            // Bei Landung oder knapp über dem Boden: nach oben zeigende Spur abflachen, damit der Schwanz nicht in den Himmel steht.
            if (pitch > 0.0 && landFlatten > 0.01) pitch *= (1.0 - landFlatten * 0.92)
            fPitch[k] = if (k == 0) pitch else fPitch[k - 1] + (pitch - fPitch[k - 1]).coerceIn(-MAX_BEND, MAX_BEND)
            fYaw[k] = if (k == 0) backYaw + wrapRad(yaw - backYaw).coerceIn(-0.95, 0.95)
            else fYaw[k - 1] + wrapRad(yaw - fYaw[k - 1]).coerceIn(-bendLimit, bendLimit)
            rawYaw[k] = if (k == 0) backYaw + wrapRad(yaw - backYaw).coerceIn(-1.15, 1.15)
            else rawYaw[k - 1] + wrapRad(yaw - rawYaw[k - 1]).coerceIn(-MAX_BEND_GROUND, MAX_BEND_GROUND)
        }

        // Bodenform: gleitet vom aktuellen Verlauf in die vorgegebene Form (beim Aufsetzen exakt die echte Anflugkurve übernehmen).
        updateRest(world, e, spine, f, rawYaw, dt)
        val w = smooth(ground).toDouble()

        // Endgültige Richtungen und Aufsummieren ab der Rumpfmitte (mit hartem Gelenk-Knicklimit gegen Falten).
        val idle = MathHelper.clamp((1.0 - speed / 0.25).toFloat(), 0f, 1f).toDouble()
        val sw = p[NivarisModel.SWEEP].toDouble()
        val maxJoint = MathHelper.lerp(w.toFloat(), MAX_BEND.toFloat(), MAX_BEND_GROUND.toFloat()).toDouble()
        var q = spine
        pts[NECK] = spine
        var carry = 0.0
        var prevYaw = backYaw
        // Nach dem Aufsetzen stehen Vorder- und Hinterläufe genau dort, wo sie den Boden berührt haben, bis sie sich wieder
        // bewegt: der Leib bis zu den Hinterläufen bleibt waagerecht auf dem gelandeten Verlauf, nur die Höhe senkt sich.
        // Läuft sie wieder los (oder hebt ab), löst sich der Halt weich über 10 Ticks statt in einem Bild umzuspringen.
        val holdOk = landedPts != null && !e.flying && speed < 0.55 && landedSpine?.let { (it.x - spine.x) * (it.x - spine.x) + (it.z - spine.z) * (it.z - spine.z) < 0.36 } == true
        holdW = approach(holdW, if (holdOk) 1f else 0f, dt / 10f)
        if (!holdOk && holdW <= 0f) landedPts = null
        val held = landedPts?.takeIf { holdW > 0f }
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
            // Am Boden bleiben Vorder- und Hinterläufe fest stehen; nur der Schwanz hinter den Hinterläufen wiegt sanft.
            val tailSway = smooth(MathHelper.clamp((i - NivarisShape.HIND_GLIED) / 7f, 0f, 1f)).toDouble()
            yaw += w * tailSway * 0.014 * kotlin.math.sin(now * 0.05 - i * 0.35)
            if (sw > 0.0) yaw += kotlin.math.sin(sw * 2.0 * Math.PI) * 0.45 * smooth(MathHelper.clamp((i - 10) / 18f, 0f, 1f))
            // Harte Winkelbegrenzung je Gelenk: Körper knickt nie zurück durch Hals oder Kopf.
            val neckClamp = MathHelper.lerp(w.toFloat(), 0.95f, 1.15f).toDouble()
            yaw = if (k == 0) backYaw + wrapRad(yaw - backYaw).coerceIn(-neckClamp, neckClamp)
            else prevYaw + wrapRad(yaw - prevYaw).coerceIn(-maxJoint, maxJoint)
            prevYaw = yaw
            // Waagerecht: im Flug die Länge aus der Neigung, am Boden die beim Aufsetzen gemessene (kein Strecken beim Abflachen).
            val flat = MathHelper.lerp(w, NivarisShape.SEGMENT * kotlin.math.cos(pitch), restFlat[k])
            q = q.add(Vec(kotlin.math.cos(yaw) * flat, kotlin.math.sin(pitch) * NivarisShape.SEGMENT, kotlin.math.sin(yaw) * flat))
            if (held != null && i <= NivarisShape.HIND_GLIED) {
                val l = held[i]
                val hw = holdW.toDouble()
                q = q.lerp(Vec(l.x, MathHelper.lerp(w, l.y, q.y), l.z), hw)
                prevYaw += wrapRad(kotlin.math.atan2(l.z - held[i - 1].z, l.x - held[i - 1].x) - prevYaw) * hw
                pts[i] = q.lerp(tmpP[k + 1], (1.0 - w) * (1.0 - idle * 0.65) * (1.0 - hw))
                ups[i] = UP_V
                continue
            }
            // In flight stay on the sampled rail. Rebuilding the centerline from
            // clamped angles cuts across bends and swings the long tail through rock.
            val railWeight = (1.0 - w) * (1.0 - idle * 0.65)
            pts[i] = q.lerp(tmpP[k + 1], railWeight)
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
        // Im Flug zählt der Boden unter jedem Glied selbst. Die Bodenhöhe unter der Entity ([groundY]) sprang beim Überflug
        // eines Bauwerks um bis zu 9 Blöcke, und mit ihr wurde der ganze nachgezogene Schwanz in einem Tick hochgeklemmt.
        val floors = DoubleArray(POINTS) { i ->
            val ref = if (e.flying) pts[i].y else groundY
            terrain.floor(pts[i].x, pts[i].z, ref + 3.5, ref - 6.0) ?: if (e.flying) ref - 6.0 else groundY
        }
        repeat(3) {
            val c = floors.copyOf()
            for (k in 1 until POINTS - 1) floors[k] = c[k] * 0.5 + (c[k - 1] + c[k + 1]) * 0.25
        }
        val slopeLen = MathHelper.lerp(stand, 8.0f, 14.0f).toDouble()
        val flatLead = MathHelper.lerp(stand, 0.0f, 2.4f).toDouble()
        for (k in 0 until BODY) {
            val i = NECK + 1 + k
            val arc = (k + 1) * NivarisShape.SEGMENT
            val rest = MathHelper.lerp(smooth(MathHelper.clamp(((arc - flatLead) / slopeLen).toFloat(), 0f, 1f)).toDouble(), spine.y, floors[i] + REST_UP)
            pts[i] = Vec(pts[i].x, MathHelper.lerp(w, pts[i].y, rest), pts[i].z)
        }
        for (i in 0 until POINTS) {
            val h0 = pts[i].y - (floors[i] + radius(i))
            air[i] = MathHelper.clamp((h0 / 2.5).toFloat(), 0f, 1f) * (1f - ground * 0.6f) + (1f - ground) * 0.4f
        }
        return headFwd
    }

    // --- Vorgegebene Bodenformen (seit 1.11.0) ------------------------------------------------------------------------

    /** Aktuelle Bodenform: Gier je Glied, gleitet zu [restTarget]. */
    private val restYaw = DoubleArray(BODY)
    private val restTarget = DoubleArray(BODY)
    /** Waagerechte Länge je Glied am Boden (beim Aufsetzen aus dem sichtbaren Körper, sonst volle Gliedlänge). */
    private val restFlat = DoubleArray(BODY) { NivarisShape.SEGMENT }
    /** Gier je Glied im Moment des Aufsetzens. */
    private val landedYaw = DoubleArray(BODY)
    /** Sichtbarer Körper im Moment des Aufsetzens und die Rumpfmitte dazu (Läufe bleiben dort stehen). */
    private var landedPts: Array<Vec>? = null
    private var landedSpine: Vec? = null
    private var holdW = 0f
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
            // In der Luft folgt die Bodenform immer dem echten Anflug, damit die Landung ohne Seitwärtssprung ansetzt.
            System.arraycopy(fYaw, 0, restYaw, 0, BODY)
            System.arraycopy(fYaw, 0, restTarget, 0, BODY)
            restFlat.fill(NivarisShape.SEGMENT)
            restLive = false
            restAnchor = null
            return
        }
        if (!restLive) {
            System.arraycopy(fYaw, 0, restYaw, 0, BODY)
            restFlat.fill(NivarisShape.SEGMENT)
            // Beim Aufsetzen genau den sichtbaren Körper übernehmen: Gier und waagerechte Länge je Glied aus dem letzten
            // Bild. Vorher wurde die Bodenform aus der Flugspur gebaut und beim Abflachen auf volle Gliedlänge gestreckt,
            // dadurch rutschten die Hinterläufe nach dem Aufsetzen fast 3 Blöcke nach hinten.
            last?.takeIf { it.size == POINTS }?.let { l ->
                for (k in 0 until BODY) {
                    val a = l[NECK + k]
                    val b = l[NECK + 1 + k]
                    val dx = b.x - a.x
                    val dz = b.z - a.z
                    val flat = kotlin.math.sqrt(dx * dx + dz * dz)
                    if (flat > 0.2) {
                        restYaw[k] = kotlin.math.atan2(dz, dx)
                        restFlat[k] = flat.coerceAtMost(NivarisShape.SEGMENT)
                    }
                }
            }
            System.arraycopy(restYaw, 0, restTarget, 0, BODY)
            System.arraycopy(restYaw, 0, landedYaw, 0, BODY)
            landedPts = last?.takeIf { it.size == POINTS }?.copyOf()
            landedSpine = spine
            holdW = if (landedPts != null) 1f else 0f
            restBase = restYaw[0]
            restLive = true
            // Prüfen, ob die gelandete Lage bereits ganz auf freiem Boden liegt: dann fest verankern (0 Fußrutschen!).
            var bad = 0
            var x = spine.x
            var z = spine.z
            for (k in 0 until BODY) {
                x += kotlin.math.cos(restYaw[k]) * restFlat[k]
                z += kotlin.math.sin(restYaw[k]) * restFlat[k]
                val top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, MathHelper.floor(x), MathHelper.floor(z)).toDouble()
                if (top > groundY + 3.5 || top < groundY - 6.0) bad++
            }
            restAnchor = spine
            if (bad > 0) {
                // Liegt der Schwanz auf Fels oder über einer Kante, legt sich nur der Teil hinter den Hinterläufen neu.
                // Vorder- und Hinterläufe bleiben genau dort stehen, wo sie aufgesetzt haben.
                val back = kotlin.math.atan2(-f.z, -f.x)
                restCoiled = false
                restBase = back + wrapRad(restYaw[0] - back).coerceIn(-0.45, 0.45)
                pickRest(world, spine, back, false)
                for (k in 0 until BODY) {
                    val shape = restBase + restHeading(restKind, (k + 0.5) * NivarisShape.SEGMENT) * restSign
                    val hold = 1.0 - smooth(MathHelper.clamp((k - LANDED_HOLD) / 5f, 0f, 1f)).toDouble()
                    restTarget[k] = shape + wrapRad(landedYaw[k] - shape) * hold
                }
            }
        }
        val moving = speed > 0.55
        if (moving) {
            System.arraycopy(fYaw, 0, restTarget, 0, BODY)
            for (k in 0 until BODY) restFlat[k] += (NivarisShape.SEGMENT - restFlat[k]) * (1.0 - kotlin.math.exp(-dt * 0.2))
            restAnchor = null
        } else {
            val coiled = false
            val back = kotlin.math.atan2(-f.z, -f.x)
            val anchor = restAnchor
            if (anchor == null || anchor.squaredDistanceTo(spine) > 12.0 * 12.0 || coiled != restCoiled) {
                restCoiled = coiled
                restAnchor = spine
                restBase = back + wrapRad(restYaw[0] - back).coerceIn(-0.45, 0.45)
                pickRest(world, spine, back, coiled)
                for (k in 0 until BODY) restTarget[k] = restBase + restHeading(restKind, (k + 0.5) * NivarisShape.SEGMENT) * restSign
            } else {
                // Steht der Körper schon sauber auf festem Boden: nur nachziehen, wenn sie sich sehr weit wegdreht.
                val off = wrapRad(back - restBase)
                if (kotlin.math.abs(off) > 0.95) {
                    restBase += off.coerceIn(-0.04 * dt, 0.04 * dt)
                    for (k in 0 until BODY) restTarget[k] = restBase + restHeading(restKind, (k + 0.5) * NivarisShape.SEGMENT) * restSign
                }
            }
        }
        val rate = 1.0 - kotlin.math.exp(-dt * (if (moving) 0.4 else 0.11))
        // Im Stand höchstens 0,03 rad je Glied und Tick: Der Schwanz legt sich ruhig um (Schwanzspitze < 2 Blöcke je Tick).
        val maxTurn = if (moving) 1.0 else 0.03 * dt
        for (k in 0 until BODY) restYaw[k] += (wrapRad(restTarget[k] - restYaw[k]) * rate).coerceIn(-maxTurn, maxTurn)
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

    private fun contacts(terrain: DragonSurface, s: NivarisRenderState, pts: Array<Vec>, solveLegs: Boolean = true): DoubleArray {
        val need = DoubleArray(POINTS)
        val matrices = MatrixStack()
        val axes = Matrix3f()
        val rotation = Quaternionf()
        val scale = NivarisShape.SCALE
        fun orient(frame: FloatArray, at: Int) {
            axes.set(frame[at], frame[at + 1], frame[at + 2], frame[at + 3], frame[at + 4], frame[at + 5],
                frame[at + 6], frame[at + 7], frame[at + 8])
            matrices.multiply(rotation.setFromNormalized(axes))
        }
        fun measure(i: Int, part: ModelPart, includeSnowOutline: Boolean = true): Double =
            terrain.lift(pts[i], matrices, part, includeSnowOutline)
        for (i in pts.indices) {
            matrices.push()
            val j = i.coerceAtMost(s.girth.lastIndex)
            orient(s.frame, j * 9)
            matrices.push()
            if (i == pts.lastIndex) {
                matrices.scale(scale, scale, scale)
                need[i] = measure(i, model.tailFan)
            } else {
                val across = scale * s.girth[i]
                matrices.scale(across, across, scale * s.stretch[i] * 1.03f)
                need[i] = measure(i, model.glied(i))
            }
            matrices.pop()
            if (solveLegs && (i == NivarisShape.FORE_GLIED || i == NivarisShape.HIND_GLIED)) {
                val fore = i == NivarisShape.FORE_GLIED
                for (side in 0..1) {
                    val index = (if (fore) 0 else 2) + side
                    val sign = if (side == 0) 1 else -1
                    matrices.push()
                    val across = scale * s.girth[i] / 16f
                    matrices.translate(sign * (if (fore) 7 else 6) * across, 3 * across, 8 * scale * s.stretch[i] * 1.03f / 16f)
                    matrices.scale(scale, scale, scale)
                    fun at(fold: Float): Double {
                        matrices.push()
                        matrices.multiply(NivarisLegs.rotation(s, fore, side == 1, fold))
                        val depth = measure(i, model.poseLeg(s, index, fold), includeSnowOutline = false)
                        matrices.pop()
                        return depth
                    }
                    var fold = 0f
                    var depth = at(fold)
                    if (depth > 0 && s.spireGrip == 0f && s.mode != NivarisModeC.RETURN_COIL) {
                        for (step in 1..8) {
                            val trial = step / 8f
                            val next = at(trial)
                            if (next < depth) {
                                fold = trial
                                depth = next
                            }
                            if (next <= 0) {
                                var lo = trial - 0.125f
                                repeat(5) {
                                    val mid = (lo + fold) / 2
                                    if (at(mid) > 0) lo = mid else fold = mid
                                }
                                break
                            }
                        }
                    }
                    s.legFolds[index] = fold
                    need[i] = max(need[i], depth)
                    matrices.pop()
                }
            }
            matrices.pop()
        }
        matrices.push()
        orient(s.headFrame, 0)
        matrices.scale(scale, scale, scale)
        need[0] = max(need[0], measure(0, model.head))
        matrices.pop()
        return need
    }

    /**
     * Solves per-leg ground fold against the final settled body using solid snow collision shapes
     * (so a thin powder-snow edge never yanks a paw up into mid-air) and softly couples left/right pairs.
     */
    private fun solveLegFolds(terrain: DragonSurface, s: NivarisRenderState, pts: Array<Vec>): FloatArray {
        val out = FloatArray(4)
        if (s.spireGrip > 0f || s.mode == NivarisModeC.RETURN_COIL) return out
        val matrices = MatrixStack()
        val axes = Matrix3f()
        val rotation = Quaternionf()
        val scale = NivarisShape.SCALE
        for (i in intArrayOf(NivarisShape.FORE_GLIED, NivarisShape.HIND_GLIED)) {
            val fore = i == NivarisShape.FORE_GLIED
            val atFrame = i * 9
            matrices.push()
            axes.set(s.frame[atFrame], s.frame[atFrame + 1], s.frame[atFrame + 2],
                s.frame[atFrame + 3], s.frame[atFrame + 4], s.frame[atFrame + 5],
                s.frame[atFrame + 6], s.frame[atFrame + 7], s.frame[atFrame + 8])
            matrices.multiply(rotation.setFromNormalized(axes))
            for (side in 0..1) {
                val index = (if (fore) 0 else 2) + side
                val sign = if (side == 0) 1 else -1
                matrices.push()
                val across = scale * s.girth[i] / 16f
                matrices.translate(sign * (if (fore) 7 else 6) * across, 3 * across, 8 * scale * s.stretch[i] * 1.03f / 16f)
                matrices.scale(scale, scale, scale)
                fun at(fold: Float): Double {
                    matrices.push()
                    matrices.multiply(NivarisLegs.rotation(s, fore, side == 1, fold))
                    val depth = terrain.lift(pts[i], matrices, model.poseLeg(s, index, fold), includeSnowOutline = false)
                    matrices.pop()
                    return depth
                }
                var fold = 0f
                var depth = at(fold)
                val deadzone = if (fore) 0.12 else 0.02
                if (depth > deadzone) {
                    for (step in 1..8) {
                        val trial = step / 8f
                        val next = at(trial)
                        if (next < depth) {
                            fold = trial
                            depth = next
                        }
                        if (next <= deadzone) {
                            var lo = trial - 0.125f
                            repeat(5) {
                                val mid = (lo + fold) / 2
                                if (at(mid) > deadzone) lo = mid else fold = mid
                            }
                            break
                        }
                    }
                }
                out[index] = fold
                matrices.pop()
            }
            matrices.pop()
        }
        // Keep left/right paws on the same plane when standing symmetrically on the arena floor (no single-leg hover).
        if (s.p[NivarisModel.SWIPE] < 0.05f) {
            val fMin = minOf(out[0], out[1])
            out[0] = minOf(out[0], fMin + 0.08f)
            out[1] = minOf(out[1], fMin + 0.08f)
        }
        val hMin = minOf(out[2], out[3])
        out[2] = minOf(out[2], hMin + 0.08f)
        out[3] = minOf(out[3], hMin + 0.08f)
        return out
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

    /** Hals vom Kopfgelenk (ruhend → aufgerichtet nach [raise]) drehungsfrei zur Ost-Schulter der Helix, dann die Helix hinab. */
    private fun coilPoly(poly: Poly, o: Vec, raise: Double, now: Float): Double {
        val axis = o.add(ArenaLayout.SPIRE.x + 0.5, 0.0, ArenaLayout.SPIRE.z + 0.5)
        val h = o.add(EntrancePathC.HEAD_REST.lerp(EntrancePathC.HEAD_RAISED, raise)).add(0.0, kotlin.math.sin(now * 0.05) * 0.20, 0.0)
        val c1 = o.add(EntrancePathC.coil(1.0))
        val t1 = c1.subtract(o.add(EntrancePathC.coil(0.995))).normalize()
        val d = h.distanceTo(c1).coerceAtLeast(2.0)
        val c1Rad = Vec(c1.x - axis.x, 0.0, c1.z - axis.z).normalizeOr(Vec(1.0, 0.0, 0.0))
        val c1Up = c1Rad.multiply(0.85).add(0.0, 0.52, 0.0).normalize()
        // Both rest and raised head back-vectors lie in the same eastern-upper-south hemisphere as c1Up (zero 180° flip!).
        val restHeadUp = c1Up.lerp(Vec(0.55, 0.72, 0.42).normalize(), 0.45).normalize()
        val raisedHeadUp = Vec(0.26, 0.72, 0.64).normalize()
        val headUp = restHeadUp.lerp(raisedHeadUp, raise).normalizeOr(UP_V)
        val restBack = t1.negate()
        val raisedBack = Vec(0.18, -0.62, 0.76).normalize()
        val headBack = restBack.lerp(raisedBack, raise).normalizeOr(restBack)
        val p1 = h.add(headBack.multiply(0.38 * d))
        val p2 = c1.add(t1.multiply(0.40 * d))
        val steps = 20
        var neckLength = 0.0
        var prev = h
        for (k in 0..steps) {
            val t = k / steps.toDouble()
            val m = 1.0 - t
            val q = h.multiply(m * m * m).add(p1.multiply(3 * m * m * t)).add(p2.multiply(3 * m * t * t)).add(c1.multiply(t * t * t))
            val smoothT = t * t * (3.0 - 2.0 * t)
            poly.add(q, headUp.lerp(c1Up, smoothT).normalizeOr(UP_V))
            neckLength += prev.distanceTo(q)
            prev = q
        }
        var sCoil = 1.0
        while (sCoil >= 0.0) {
            val q = o.add(EntrancePathC.coil(sCoil))
            val radial = Vec(q.x - axis.x, 0.0, q.z - axis.z).normalizeOr(UP_V)
            val pos = q.add(radial.multiply(0.10 * kotlin.math.sin(now * 0.05 + sCoil * 9.0)))
            poly.add(pos, radial.multiply(0.85).add(0.0, 0.52, 0.0).normalize())
            sCoil -= 0.002
        }
        return neckLength
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
    private fun sample(
        poly: Poly, outP: Array<Vec>, outU: Array<Vec>, from: Int, spacing: Double,
        until: Int = outP.size, neckSpacing: Double = spacing, neckCount: Int = NECK,
    ) {
        val pts = poly.pts
        if (pts.isEmpty()) return
        val sp = if (spacing > 0.0) spacing else NivarisShape.SEGMENT
        outP[from] = pts[0]
        outU[from] = poly.ups[0]
        var seg = 0
        var acc = 0.0
        for (i in from + 1 until until) {
            val count = i - from
            val target = if (neckSpacing > 0.0) minOf(count, neckCount) * neckSpacing + maxOf(0, count - neckCount) * sp else count * sp
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
    private fun frames(pts: Array<Vec>, ups: Array<Vec>, now: Float, air: FloatArray, bodyRoll: Double, clinging: Boolean, scripted: Boolean): FloatArray {
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
                if (clinging) {
                    u = targetU
                } else if (targetU.dotProduct(u) > -0.15) {
                    u = u.lerp(targetU, if (i == 0) 0.75 else 0.28).normalizeOr(u)
                    u = u.subtract(b.multiply(u.dotProduct(b))).normalizeOr(orthoFallback(b))
                }
            }
            if (scripted) u = UP_V.subtract(b.multiply(UP_V.dotProduct(b))).normalizeOr(u)
            var l = b.crossProduct(u).normalizeOr(orthoFallback(b))
            u = l.crossProduct(b).normalizeOr(u)
            // Grundrollen + explizite Fassrolle (läuft wellenförmig vom Bug zum Schwanz).
            // The delay fades to zero at both ends, so every segment completes the full turn.
            val waveRoll = bodyRoll - kotlin.math.sin(bodyRoll * 0.5) * (0.019 * i)
            val roll = if (clinging) 0.0 else if (scripted) bodyRoll else waveRoll + (0.02 + 0.05 * air[i]) * kotlin.math.sin(now * 0.05 - i * 0.2)
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
        const val LIFT_DOWN = 0.22

        /** Glieder des Halses (0..NECK-1) zwischen Kopfgelenk und Rumpfmitte; Punkt NECK = Rumpfmitte. */
        const val NECK = 3

        /** Glieder vom Kopfgelenk bis zur verankerten Nadel-Windung (nur diese heben sich beim Aufrichten). */
        const val COIL_NECK = 6

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

        /** Glieder ab der Rumpfmitte, die nach dem Aufsetzen genau liegen bleiben (bis knapp hinter die Hinterläufe). */
        const val LANDED_HOLD = NivarisShape.HIND_GLIED - NECK

        const val MAX_PITCH = 0.58

        /** Vorgegebene Bodenformen. */
        const val SERPENT = 0
        const val COMPACT = 1
        const val COILED = 2
        val REST_OFFSETS = doubleArrayOf(0.0, 0.25, -0.25, 0.5, -0.5)
    }
}

/** Cuboid footprints and the individual collision boxes under them, cached for one world tick. */
private class DragonSurface(private val world: World, private val origin: BlockPos) {
    private val heights = Long2IntOpenHashMap().apply { defaultReturnValue(Int.MIN_VALUE) }
    private val distant = Long2IntLinkedOpenHashMap().apply { defaultReturnValue(Int.MIN_VALUE) }
    private var sampledTick = Long.MIN_VALUE

    fun matches(other: World, at: BlockPos) = world === other && origin == at

    private val shapes = HashMap<BlockPos, List<net.minecraft.util.math.Box>>()
    private val solidShapes = HashMap<BlockPos, List<net.minecraft.util.math.Box>>()
    private val low = Vector3f()
    private val high = Vector3f()

    fun beginFrame() {
        if (world.time == sampledTick) return
        sampledTick = world.time
        heights.clear()
        shapes.clear()
        solidShapes.clear()
    }

    private fun height(x: Int, z: Int): Int {
        val key = BlockPos.asLong(x, 0, z)
        val cached = heights.get(key)
        if (cached != Int.MIN_VALUE) return cached
        val h = if (world.chunkManager.isChunkLoaded(x shr 4, z shr 4)) world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z)
            else distantHeight(x, z, key)
        heights.put(key, h)
        return h
    }

    private fun distantHeight(x: Int, z: Int, key: Long): Int {
        val rx = x - origin.x
        val rz = z - origin.z
        if (world.registryKey != ArenaDimension.WORLD || rx !in FarPlan.X_MIN..FarPlan.X_MAX || rz !in FarPlan.Z_MIN..FarPlan.Z_MAX)
            return world.bottomY
        val cached = distant.getAndMoveToLast(key)
        if (cached != Int.MIN_VALUE) return cached
        val h = origin.y + FarPlan.top(rx, rz) + 1
        if (distant.size >= 16384) distant.removeFirstInt()
        distant.put(key, h)
        return h
    }

    private fun surfaces(x: Int, y: Int, z: Int, includeSnowOutline: Boolean): List<net.minecraft.util.math.Box> {
        val pos = BlockPos(x, y, z)
        val cache = if (includeSnowOutline) shapes else solidShapes
        return cache.getOrPut(pos) {
            val state = world.getBlockState(pos)
            val shape = if (includeSnowOutline && state.isOf(Blocks.SNOW)) state.getOutlineShape(world, pos) else state.getCollisionShape(world, pos)
            shape.boundingBoxes
        }
    }

    fun floor(x: Double, z: Double, ceiling: Double, bottom: Double): Double? {
        val h = support(MathHelper.floor(x), MathHelper.floor(z), x, x, z, z, ceiling, bottom, true)
        return h.takeIf { it > bottom }
    }

    private fun support(x: Int, z: Int, x0: Double, x1: Double, z0: Double, z1: Double,
                        ceiling: Double, bottom: Double, includeSnowOutline: Boolean): Double {
        val top = height(x, z)
        if (top <= bottom || top <= world.bottomY) return bottom
        if (!world.chunkManager.isChunkLoaded(x shr 4, z shr 4)) return if (top <= ceiling) top.toDouble() else bottom
        for (y in minOf(top - 1, MathHelper.floor(ceiling)) downTo maxOf(world.bottomY, MathHelper.floor(bottom) - 1)) {
            var highest = bottom
            for (box in surfaces(x, y, z, includeSnowOutline)) {
                val h = y + box.maxY
                if (h > ceiling || h <= highest || x + box.maxX < x0 || x + box.minX > x1 ||
                    z + box.maxZ < z0 || z + box.minZ > z1) continue
                if (box.maxY >= 1.0 && y + 1 < top && world.getBlockState(BlockPos(x, y + 1, z)).isOpaqueFullCube) continue
                highest = h
            }
            if (highest > bottom) return highest
        }
        return bottom
    }

    fun lift(p: Vec, matrices: MatrixStack, part: ModelPart, includeSnowOutline: Boolean = true): Double {
        var lift = 0.0
        part.forEachCuboid(matrices) { entry, _, _, box ->
            entry.positionMatrix.transformAab(box.minX / 16f, box.minY / 16f, box.minZ / 16f,
                box.maxX / 16f, box.maxY / 16f, box.maxZ / 16f, low, high)
            val bottom = p.y + low.y - 0.02
            val ceiling = p.y + 2.0
            if (bottom >= ceiling) return@forEachCuboid
            val x0 = p.x + low.x
            val x1 = p.x + high.x
            val z0 = p.z + low.z
            val z1 = p.z + high.z
            for (x in MathHelper.floor(x0)..MathHelper.floor(x1)) for (z in MathHelper.floor(z0)..MathHelper.floor(z1)) {
                val surface = support(x, z, x0, x1, z0, z1, ceiling, bottom + lift, includeSnowOutline)
                lift = max(lift, surface - bottom)
            }
        }
        return lift
    }
}

private typealias Vec = net.minecraft.util.math.Vec3d
private typealias ArenaLayout = dev.henny.hha_arius.arena.ArenaLayout
private typealias EntrancePathC = dev.henny.hha_arius.boss.EntrancePath
private typealias NivarisModeC = dev.henny.hha_arius.boss.NivarisMode

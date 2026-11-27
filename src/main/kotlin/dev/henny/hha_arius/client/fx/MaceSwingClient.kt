package dev.henny.hha_arius.client.fx

import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Arm
import net.minecraft.util.math.Vec3d
import org.joml.Matrix3f
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mace-Griff (seit 1.15.13), für alle Zuschauer gleich: Arius hält den gefangenen Spieler am Stiel seines Streitkolbens
 * und schmettert ihn über den Kopf von einer Seite auf die andere. Die Figur hängt dabei an Arius' tatsächlich gezeichneter
 * linker Faust ([recordHand], vom Arius-Renderer) und streckt sich von der Schulter weg nach außen; wo sie den Boden
 * erreichen würde, legt sie sich flach darauf. Nach dem letzten Aufprall bleibt sie liegen und steht wieder auf.
 *
 * Zeitleiste in Ticks ab dem Griff: bis [Swing.release] hängt die Figur an der Hand, danach steht sie bis [Swing.total] auf.
 */
object MaceSwingClient {

    private class Hand(val shoulder: Vec3d, val grip: Vec3d)

    private class Swing(
        val arius: Int, val axis: Vec3d, val floor: Double, val release: Int, val total: Int,
    ) {
        var startAge = Float.NaN

        /** Letzte Lage an der Hand (Füße, Achsen) für den Übergang ins Aufstehen. */
        var lastFeet: Vec3d? = null
        var lastRot: Quaternionf? = null
        var lying = 0f
    }

    private val swings = HashMap<Int, Swing>()
    private val hands = HashMap<Int, Hand>()

    private const val BLEND_IN = 3f
    /** Von Arius' Faust (kurz unter dem Mace-Kopf) den Stiel entlang bis zu den Fäusten des Spielers. */
    private const val SHAFT = 0.5

    /** Faust der Haupthand, Arme gerade nach vorn gestreckt, im Figurraum (x rechts, y oben, z hinten); Füße im Ursprung. */
    private const val FIST_X = 0.2
    private const val FIST_Y = 1.38
    private const val FIST_Z = -0.66
    private const val LIE_Y = 0.22

    fun start(victim: Int, arius: Int, axis: Vec3d, floor: Double, release: Int, total: Int) {
        if (victim < 0 || arius < 0 || total <= 0) return
        val flat = Vec3d(axis.x, 0.0, axis.z)
        val a = if (flat.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else flat.normalize()
        swings[victim] = Swing(arius, a, floor, release.coerceIn(1, total), total)
    }

    fun reset() {
        swings.clear()
        hands.clear()
    }

    /** Die eigene Figur ausblenden, solange die Szenenkamera noch in ihrem Kopf oder dicht an ihr steckt (erste Ticks des Griffs). */
    @JvmStatic
    fun hideOwn(e: net.minecraft.entity.Entity): Boolean {
        val client = MinecraftClient.getInstance()
        if (e !== client.player) return false
        val s = swings[e.id] ?: return false
        val cam = client.gameRenderer.camera.cameraPos
        val body = s.lastFeet?.add(0.0, 0.9, 0.0)
        return cam.squaredDistanceTo(e.eyePos) < 2.2 * 2.2 || (body != null && cam.squaredDistanceTo(body) < 2.0 * 2.0)
    }

    @JvmStatic
    fun watches(ariusId: Int): Boolean = swings.values.any { it.arius == ariusId }

    fun recordHand(ariusId: Int, shoulder: Vec3d, grip: Vec3d) {
        hands[ariusId] = Hand(shoulder, grip)
    }

    /** Für die Szenenkamera des Opfers: Mitte der Schwungebene (Arius' Füße) und ihre waagerechte Achse. */
    fun cameraFrame(victim: Int): Pair<Vec3d, Vec3d>? {
        val s = swings[victim] ?: return null
        val world = MinecraftClient.getInstance().world ?: return null
        val a = world.getEntityById(s.arius) ?: return null
        return Vec3d(a.x, s.floor, a.z) to s.axis
    }

    private fun smooth(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    private fun time(state: PlayerEntityRenderState, s: Swing): Float {
        if (s.startAge.isNaN()) s.startAge = state.age
        return state.age - s.startAge
    }

    private fun active(state: PlayerEntityRenderState): Pair<Swing, Float>? {
        val s = swings[state.id] ?: return null
        val t = time(state, s)
        if (t >= s.total || t < -20f) {
            swings.remove(state.id)
            if (swings.values.none { it.arius == s.arius }) hands.remove(s.arius)
            return null
        }
        return s to t.coerceAtLeast(0f)
    }

    private class Figure(val feet: Vec3d, val rot: Quaternionf, val lying: Float)

    private fun quatOf(right: Vec3d, head: Vec3d, back: Vec3d): Quaternionf {
        val m = Matrix3f(
            right.x.toFloat(), right.y.toFloat(), right.z.toFloat(),
            head.x.toFloat(), head.y.toFloat(), head.z.toFloat(),
            back.x.toFloat(), back.y.toFloat(), back.z.toFloat(),
        )
        return Quaternionf().setFromNormalized(m)
    }

    /**
     * Lage der Figur an Arius' Faust: Er hält den Mace-Stiel kurz unter dem Kopf, der Spieler klammert sich mit beiden
     * Händen vor der Brust an den Griff; der Körper streckt sich von Arius weg nach außen. Wo er den Boden erreichen würde,
     * kippt er nach oben, bis er flach auf dem Rücken liegt (die Arme halten den Mace dann nach oben).
     */
    private fun attached(s: Swing, state: PlayerEntityRenderState): Figure? {
        val hand = hands[s.arius] ?: return null
        val up = Vec3d(0.0, 1.0, 0.0)
        var r = hand.grip.subtract(hand.shoulder).let { if (it.lengthSquared() < 1e-6) up else it.normalize() }
        // In der Schwungebene halten: Arius' Arm weicht beim Überblenden nach vorn oder hinten aus, die Figur nicht.
        val normal = s.axis.crossProduct(up).normalize()
        r = r.subtract(normal.multiply(r.dotProduct(normal) * 0.9)).let { if (it.lengthSquared() < 1e-4) up else it.normalize() }
        val minY = s.floor + LIE_Y
        val fx = if (state.mainArm == Arm.LEFT) -FIST_X else FIST_X
        fun build(dir: Vec3d): Figure {
            val head = dir.multiply(-1.0)
            // Am Boden zeigt das Gesicht nach oben; über dem Kopf dreht sich der Körper durch die Ebenen-Normale.
            val most = up.subtract(dir.multiply(dir.y))
            val w = smooth((abs(dir.dotProduct(s.axis)) / 0.55).toFloat()).toDouble()
            var face = (if (most.lengthSquared() > 1e-4) most.normalize() else normal).multiply(w).add(normal.multiply(1.0 - w))
            face = face.subtract(dir.multiply(face.dotProduct(dir)))
            face = if (face.lengthSquared() < 1e-6) normal else face.normalize()
            val back = face.multiply(-1.0)
            val right = head.crossProduct(back).normalize()
            val fist = hand.grip.add(dir.multiply(SHAFT))
            val feet = fist.subtract(right.multiply(fx)).subtract(head.multiply(FIST_Y)).subtract(back.multiply(FIST_Z))
            return Figure(feet, quatOf(right, head, back), (1.0 - abs(dir.y)).toFloat().coerceIn(0f, 1f))
        }
        fun lowest(fig: Figure, dir: Vec3d): Double {
            val headTop = fig.feet.add(dir.multiply(-1.8))
            return minOf(fig.feet.y, headTop.y)
        }
        val first = build(r)
        if (lowest(first, r) >= minY) return Figure(first.feet, first.rot, 0f)
        // Nach oben kippen (in der Ebene), bis der ganze Körper über dem Boden liegt.
        var flat = Vec3d(r.x, 0.0, r.z)
        if (flat.lengthSquared() < 1e-4) {
            val a = MinecraftClient.getInstance().world?.getEntityById(s.arius)
            val side = if (a != null && hand.grip.subtract(a.entityPos).dotProduct(s.axis) < 0.0) -1.0 else 1.0
            flat = s.axis.multiply(side)
        }
        flat = flat.normalize()
        fun dirAt(phi: Double) = flat.multiply(kotlin.math.cos(phi)).add(0.0, kotlin.math.sin(phi), 0.0)
        var lo = kotlin.math.asin(r.y.coerceIn(-1.0, 1.0))
        var hi = 0.6
        if (lowest(build(dirAt(hi)), dirAt(hi)) < minY) lo = hi
        repeat(12) {
            val mid = (lo + hi) * 0.5
            if (lowest(build(dirAt(mid)), dirAt(mid)) < minY) lo = mid else hi = mid
        }
        return build(dirAt(hi))
    }

    // --- Aufstehen (Rücken → Sitz → Knie → Stand), Körper kippt um die Hüfthöhe ---------------------------------------

    private class Key(
        val at: Float, val pitch: Float, val drop: Float, val head: Float,
        val rArm: Float, val rArmZ: Float, val lArm: Float, val lArmZ: Float,
        val rLeg: Float, val rLegZ: Float, val lLeg: Float, val lLegZ: Float,
    )

    private val GET_UP = listOf(
        Key(0.00f, 90f, -0.68f, 0.3f, 0.18f, 0.92f, 0.35f, -1.13f, 0.1f, 0.3f, 0.1f, -0.3f),
        Key(0.30f, 90f, -0.68f, 0.3f, 0.32f, 0.74f, 0.49f, -0.8f, -0.25f, 0.2f, 0.0f, -0.2f),
        Key(0.55f, 36f, -0.66f, 0.55f, 0.75f, 0.35f, 0.8f, -0.35f, -1.5f, 0.2f, -1.2f, -0.2f),
        Key(0.78f, -12f, -0.46f, 0.3f, -0.5f, 0.15f, 0.3f, -0.2f, -1.55f, 0.1f, 0.9f, -0.05f),
        Key(0.92f, -6f, -0.16f, 0.1f, -0.2f, 0.08f, 0.15f, -0.08f, -0.6f, 0.04f, 0.35f, 0f),
        Key(1.00f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    )

    private fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k

    private fun getUp(u: Float): Triple<Key, Key, Float> {
        val x = u.coerceIn(0f, 1f)
        var i = 0
        while (i < GET_UP.size - 2 && GET_UP[i + 1].at <= x) i++
        val a = GET_UP[i]
        val b = GET_UP[i + 1]
        return Triple(a, b, smooth((x - a.at) / (b.at - a.at).coerceAtLeast(0.001f)))
    }

    /** Ersetzt die Vanilla-Drehung der Figur (Kopf von PlayerEntityRenderer.setupTransforms). */
    @JvmStatic
    fun transform(state: PlayerEntityRenderState, matrices: MatrixStack): Boolean {
        val (s, t) = active(state) ?: return false
        val upright = Quaternionf().rotationY((PI - Math.toRadians(state.bodyYaw.toDouble())).toFloat())
        val here = Vec3d(state.x, state.y, state.z)
        if (t < s.release) {
            val fig = attached(s, state) ?: return false
            s.lastFeet = fig.feet
            s.lastRot = Quaternionf(fig.rot)
            s.lying = fig.lying
            val k = smooth(t / BLEND_IN)
            val d = fig.feet.subtract(here).multiply(k.toDouble())
            matrices.translate(d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
            matrices.multiply(Quaternionf(upright).slerp(fig.rot, k))
            return true
        }
        // Aufstehen an der eigenen Position: liegend mit dem Kopf zu Arius, das Gesicht nach oben.
        val world = MinecraftClient.getInstance().world
        val arius = world?.getEntityById(s.arius)
        val toArius = arius?.let { Vec3d(it.x - state.x, 0.0, it.z - state.z) }?.takeIf { it.lengthSquared() > 1e-4 }?.normalize()
            ?: s.axis
        val span = (s.total - s.release).toFloat().coerceAtLeast(1f)
        val u = ((t - s.release) / span).coerceIn(0f, 1f)
        val (a, b, k) = getUp(u)
        val pitch = Math.toRadians(mix(a.pitch, b.pitch, k).toDouble())
        val drop = mix(a.drop, b.drop, k).toDouble()
        // Stehend mit dem Rücken zu Arius; pitch kippt um die Querachse nach hinten (Kopf zu Arius).
        val up = Vec3d(0.0, 1.0, 0.0)
        val back0 = toArius
        val right = up.crossProduct(back0).normalize()
        val stand = quatOf(right, up, back0)
        val tilt = Quaternionf().rotationAxis(pitch.toFloat(), right.x.toFloat(), right.y.toFloat(), right.z.toFloat())
        val pose = Quaternionf(tilt).mul(stand)
        // Drehpunkt in Hüfthöhe (0,9), dazu das Absenken wie beim Tempel-Tritt.
        val pivot = Vec3d(0.0, 0.9, 0.0)
        val rotated = Vec3d(0.0, -0.9, 0.0).let {
            val v = org.joml.Vector3f(it.x.toFloat(), it.y.toFloat(), it.z.toFloat())
            tilt.transform(v)
            Vec3d(v.x.toDouble(), v.y.toDouble(), v.z.toDouble())
        }
        var feet = here.add(pivot).add(rotated).add(0.0, drop, 0.0)
        var rot = pose
        // Die ersten Ticks: aus der letzten Lage an der Hand hinübergleiten (beide liegen flach).
        val from = s.lastFeet
        val fromRot = s.lastRot
        val handoff = smooth((t - s.release) / 4f)
        if (from != null && fromRot != null && handoff < 1f) {
            feet = from.lerp(feet, handoff.toDouble())
            rot = Quaternionf(fromRot).slerp(pose, handoff)
        }
        // Zuletzt in die Vanilla-Drehung, damit das Ende ohne Sprung in die normale Figur übergeht.
        val end = smooth((u - 0.9f) / 0.1f)
        if (end > 0f) {
            feet = feet.lerp(here, end.toDouble())
            rot = Quaternionf(rot).slerp(upright, end)
        }
        val d = feet.subtract(here)
        matrices.translate(d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
        matrices.multiply(rot)
        return true
    }

    /** Gliedmaßen: Haupthand gestreckt am Stiel, der Rest schlenkert; am Boden ausgestreckt; beim Aufstehen nach Schlüsselbild. */
    @JvmStatic
    fun applyPose(model: PlayerEntityModel, state: PlayerEntityRenderState) {
        val (s, t) = active(state) ?: return
        val left = state.mainArm == Arm.LEFT
        val main = if (left) model.leftArm else model.rightArm
        val off = if (left) model.rightArm else model.leftArm
        val sign = if (left) -1f else 1f
        if (t < s.release) {
            val k = smooth(t / BLEND_IN)
            val ground = s.lying
            val flail = sin(t * 1.3f)
            // Beide Hände klammern sich vor der Brust an den Griff; der Stiel zeigt über den Kopf zu Arius.
            main.pitch = mix(main.pitch, -PI.toFloat() / 2f, k)
            main.yaw = mix(main.yaw, -0.12f * sign, k)
            main.roll = mix(main.roll, 0f, k)
            off.pitch = mix(off.pitch, -PI.toFloat() / 2f + 0.06f, k)
            off.yaw = mix(off.yaw, 0.55f * sign, k)
            off.roll = mix(off.roll, 0f, k)
            model.rightLeg.pitch = mix(model.rightLeg.pitch, mix(0.25f * flail, 0.05f, ground), k)
            model.rightLeg.roll = mix(model.rightLeg.roll, mix(0.18f, 0.12f, ground), k)
            model.leftLeg.pitch = mix(model.leftLeg.pitch, mix(-0.25f * flail, -0.05f, ground), k)
            model.leftLeg.roll = mix(model.leftLeg.roll, mix(-0.18f, -0.12f, ground), k)
            model.head.pitch = mix(model.head.pitch, mix(-0.35f, 0.2f, ground), k)
            model.head.yaw = mix(model.head.yaw, 0f, k)
            return
        }
        val span = (s.total - s.release).toFloat().coerceAtLeast(1f)
        val u = ((t - s.release) / span).coerceIn(0f, 1f)
        val (a, b, k) = getUp(u)
        val end = smooth((u - 0.9f) / 0.1f)
        fun set(part: net.minecraft.client.model.ModelPart, pitch: Float, roll: Float) {
            part.pitch = mix(pitch, part.pitch, end)
            part.roll = mix(roll, part.roll, end)
            part.yaw = mix(0f, part.yaw, end)
        }
        set(model.head, mix(a.head, b.head, k), 0f)
        set(model.rightArm, mix(a.rArm, b.rArm, k), mix(a.rArmZ, b.rArmZ, k))
        set(model.leftArm, mix(a.lArm, b.lArm, k), mix(a.lArmZ, b.lArmZ, k))
        set(model.rightLeg, mix(a.rLeg, b.rLeg, k), mix(a.rLegZ, b.rLegZ, k))
        set(model.leftLeg, mix(a.lLeg, b.lLeg, k), mix(a.lLegZ, b.lLegZ, k))
    }
}

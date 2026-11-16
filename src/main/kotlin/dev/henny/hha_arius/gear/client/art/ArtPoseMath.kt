package dev.henny.hha_arius.gear.client.art

import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Kleine Mathematik für Posen. Modellraum wie Minecraft: Einheiten = Modellpixel (1/16 Block), +y nach unten,
 * -z vorn, +x zur linken Seite des Spielers. Erste Person: Blickraum in Blöcken (+x rechts, +y oben, -z vorn).
 */
internal object ArtPoseMath {

    const val TAU = (2 * PI).toFloat()

    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    /** Anteil von [a] zwischen [t0] und [t1], 0..1. */
    fun seg(a: Float, t0: Float, t1: Float): Float = if (t1 <= t0) (if (a >= t1) 1f else 0f) else clamp01((a - t0) / (t1 - t0))

    fun smooth(t: Float): Float = clamp01(t).let { it * it * (3f - 2f * it) }
    fun easeOut(t: Float): Float = 1f - (1f - clamp01(t)).let { it * it }
    fun easeOutCubic(t: Float): Float = 1f - (1f - clamp01(t)).let { it * it * it }
    fun easeIn(t: Float): Float = clamp01(t).let { it * it }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Winkel auf -π..π. */
    fun wrap(a: Float): Float {
        var x = a % TAU
        if (x > PI) x -= TAU
        if (x < -PI) x += TAU
        return x
    }

    /** Normierte lineare Mischung zweier Richtungen (fällt bei Gegenrichtungen auf [b] zurück). */
    fun nlerp(a: Vector3f, b: Vector3f, t: Float, out: Vector3f): Vector3f {
        out.set(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)
        val l = out.length()
        return if (l < 1e-4f) out.set(b) else out.div(l)
    }

    fun lerpV(a: Vector3f, b: Vector3f, t: Float, out: Vector3f): Vector3f =
        out.set(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

    /** Quadratische Bézierkurve (für Bögen wie den Haken). */
    fun bezier(a: Vector3f, c: Vector3f, b: Vector3f, t: Float, out: Vector3f): Vector3f {
        val u = 1f - t
        return out.set(
            u * u * a.x + 2f * u * t * c.x + t * t * b.x,
            u * u * a.y + 2f * u * t * c.y + t * t * b.y,
            u * u * a.z + 2f * u * t * c.z + t * t * b.z,
        )
    }

    private val tmpM = Matrix3f()
    private val tx = Vector3f()
    private val ty = Vector3f()
    private val tz = Vector3f()

    /**
     * Drehung eines Armknochens: +y (Schulter → Hand) zeigt nach [dir], die Handfläche möglichst nach [palm].
     * Handfläche im Armraum: rechter Arm +x, linker Arm -x (siehe gauntlets_model.py).
     */
    fun armFrame(dir: Vector3f, palm: Vector3f, right: Boolean, out: Quaternionf): Quaternionf {
        ty.set(dir).normalize()
        perp(palm, ty, tx)
        if (!right) tx.negate()
        ty.cross(tx, tz).negate()   // z = x × y
        return fromColumns(out)
    }

    /**
     * Drehung eines Armknochens mit Schwert: +y nach [dir], Armraum -z (Richtung der Klinge, siehe [ArtPose.BLADE])
     * möglichst nach [blade].
     */
    fun bladeFrame(dir: Vector3f, blade: Vector3f, out: Quaternionf): Quaternionf {
        ty.set(dir).normalize()
        perp(blade, ty, tz)
        tz.negate()
        ty.cross(tz, tx)            // x = y × z
        return fromColumns(out)
    }

    private fun fromColumns(out: Quaternionf): Quaternionf {
        tmpM.setColumn(0, tx)
        tmpM.setColumn(1, ty)
        tmpM.setColumn(2, tz)
        return out.setFromNormalized(tmpM)
    }

    /** Anteil von [v] senkrecht zu [axis] (normiert); bei fast paralleler Lage eine stabile Ersatzrichtung. */
    private fun perp(v: Vector3f, axis: Vector3f, out: Vector3f) {
        out.set(v).fma(-v.dot(axis), axis)
        if (out.lengthSquared() < 1e-3f) {
            // Ersatz: „unten“ bzw. „vorn“, je nachdem was nicht parallel ist.
            if (abs(axis.y) < 0.9f) out.set(0f, 1f, 0f) else out.set(0f, 0f, -1f)
            out.fma(-out.dot(axis), axis)
        }
        out.normalize()
    }

    /** Eulerwinkel in Minecrafts Reihenfolge (ModelPart: rotationZYX(roll, yaw, pitch)) → (pitch, yaw, roll). */
    fun euler(q: Quaternionf, out: Vector3f): Vector3f = q.getEulerAnglesZYX(out)

    fun quat(pitch: Float, yaw: Float, roll: Float, out: Quaternionf): Quaternionf = out.rotationZYX(roll, yaw, pitch)

    /** Sanftes, langsames Atmen (kein Zittern). */
    fun breathe(t: Float, speed: Float = 0.085f, phase: Float = 0f): Float = sin(t * speed + phase)
}

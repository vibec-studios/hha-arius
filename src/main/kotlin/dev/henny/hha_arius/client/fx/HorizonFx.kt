package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.sin

/** A compressed ice edge gathers at the hand and opens into one sweeping horizontal blade. */
class HorizonFx(
    private val origin: Vec3d, direction: Vec3d, private val width: Double,
    private val released: Boolean, private val owner: Entity?, life: Int,
) : AriusFx(life) {
    private val forward = Vec3d(direction.x, 0.0, direction.z).normalize()
    private val across = Vec3d(-forward.z, 0.0, forward.x)

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val progress = (t / life).coerceIn(0f, 1f)
        if (!released && owner?.isRemoved == true) return
        val chargeAt = if (released) null else BladeAnchors.of(owner)?.base
        val center = if (released) origin.add(forward.multiply(t.coerceAtMost(6f).toDouble()))
            else (chargeAt ?: origin).add(across.multiply(1.15)).add(forward.multiply(0.25))
        val open = if (released) (t / 4f).coerceIn(0f, 1f) else progress * progress
        val fade = if (released) (1f - progress) * (1f - progress) else (t / 6f).coerceIn(0f, 1f)
        val span = width * (if (released) 0.46 + 0.54 * open else 0.10 + 0.29 * open)
        val depth = if (released) 1.35 + open * 0.7 else 0.14 + open * 0.25
        val edge = withAlpha(0xFF153756.toInt(), fade * 0.85f)
        val frost = withAlpha(0xFF80C8E9.toInt(), fade * 0.93f)
        val white = withAlpha(0xFFF4FCFF.toInt(), fade)
        val vc = d.buffer(AriusLayers.INK)
        fun point(u: Double, back: Double, lift: Double): Vec3d {
            val arch = sin(u * PI)
            return center.add(across.multiply((2.0 * u - 1.0) * span))
                .add(forward.multiply(arch * (depth * 0.48 - back * depth)))
                .add(0.0, lift * arch, 0.0)
        }
        fun strip(from: Double, to: Double, lift: Double, outer: Int, inner: Int) {
            with(d) {
                for (i in 0 until 32) {
                    val a = i / 32.0
                    val b = (i + 1) / 32.0
                    vc.put(point(a, from, 0.0), 0f, 0f, outer)
                    vc.put(point(a, to, lift), 0f, 1f, inner)
                    vc.put(point(b, to, lift), 1f, 1f, inner)
                    vc.put(point(b, from, 0.0), 1f, 0f, outer)
                }
            }
        }
        strip(0.0, 0.14, 0.09, edge, white)
        strip(0.14, 0.38, 0.04, white, frost)
        strip(0.38, 1.0, 0.0, frost, withAlpha(0xFF3875A1.toInt(), 0f))
        if (released) {
            // An icy reflection below the cut makes the impact legible from ground level.
            d.band(AriusLayers.INK, point(0.0, 0.2, 0.0).add(0.0, -0.24, 0.0),
                point(1.0, 0.2, 0.0).add(0.0, -0.24, 0.0), Vec3d(0.0, 1.0, 0.0),
                0.23 * (1f - progress), frost, withAlpha(0xFFC8EEFF.toInt(), fade * 0.38f))
        }
    }
}

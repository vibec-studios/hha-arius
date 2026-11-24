package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.arena.ArenaDimension
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Clean translucent frost-glow ground telegraph renderer (exact HHA 0.8.0 / Arius 1.11.0 look):
 * broad glowing `FLOW_ADD` fill, crisp leading edge, soft `GLOW_ADD`/`ENERGY` rims, and gentle `RAYS_ADD` halo
 * without heavy solid slabs, dark outlines, or scrolling arrows from newer HHA versions.
 * Applies exclusively inside Eirholm ([ArenaDimension.WORLD]).
 */
object ClassicTelegraphs {

    private val X = Vec3d(1.0, 0.0, 0.0)
    private val UP = Vec3d(0.0, 1.0, 0.0)
    private val Z = Vec3d(0.0, 0.0, 1.0)

    @JvmStatic
    fun active(): Boolean = MinecraftClient.getInstance().world?.registryKey == ArenaDimension.WORLD

    private fun fill(t: Float, life: Int): Float = (t / life.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    private fun flash(t: Float, life: Int): Float = (t - (life - 4).toFloat()).coerceIn(0f, 1f)
    private fun rim(pal: Palette, flash: Float, alpha: Float = 1f): Int =
        withAlpha(lerpColor(lerpColor(pal.main, pal.core, 0.35f), -1, flash), alpha)
    private fun body(pal: Palette, flash: Float, alpha: Float): Int =
        withAlpha(lerpColor(pal.main, -1, flash * 0.85f), alpha)
    private fun front(pal: Palette, flash: Float, alpha: Float): Int =
        withAlpha(lerpColor(pal.core, -1, flash), alpha)
    private fun fillAlpha(fill: Float, flash: Float): Float =
        (0.16f + 0.24f * fill) * (1f - flash) + 0.62f * flash
    private fun rimWidth(scale: Double): Double = (0.07 + scale * 0.008).coerceIn(0.07, 0.2)

    private fun rimLine(d: VfxDraw, pal: Palette, a: Vec3d, b: Vec3d, width: Double, flash: Float) {
        val delta = b.subtract(a)
        if (delta.lengthSquared() < 1e-6) return
        val side = delta.normalize().crossProduct(UP).let { if (it.lengthSquared() < 1e-6) X else it.normalize() }
        val glow = rim(pal, flash, 0.5f)
        val edge = rim(pal, flash, 1f)
        d.band(VfxLayers.GLOW_ADD, a, b, side, width * 2.6, glow, glow, 0f, 0f)
        d.band(VfxLayers.ENERGY, a.add(0.0, 0.005, 0.0), b.add(0.0, 0.005, 0.0), side, width, edge, edge, 0.5f, 0f)
    }

    private fun rimRing(d: VfxDraw, pal: Palette, center: Vec3d, r: Double, width: Double, flash: Float, segments: Int) {
        val glow = rim(pal, flash, 0.5f)
        val edge = rim(pal, flash, 1f)
        d.ring(VfxLayers.GLOW_ADD, center, X, Z, max(0.0, r - width * 2.6), r + width * 2.6, glow, glow, segments, 0f, 0.5f)
        d.ring(VfxLayers.ENERGY, center.add(0.0, 0.005, 0.0), X, Z, max(0.0, r - width), r + width, edge, edge, segments, (r * 0.9).toFloat(), 0f)
    }

    @JvmStatic
    fun renderRing(
        d: VfxDraw, t: Float, pal: Palette, safe: Boolean, life: Int,
        center: Vec3d, radius: Float, inner: Float, segments: Int,
    ) {
        val f = fill(t, life)
        val fl = flash(t, life)
        val c = center.add(0.0, 0.09, 0.0)
        val r = radius.toDouble()
        val inR = inner.toDouble().coerceIn(0.0, r)
        val rw = rimWidth(r)
        val cur = inR + (r - inR) * f
        if (cur > inR + 0.02) {
            val fa = fillAlpha(f, fl)
            val b = body(pal, fl, fa)
            val fr = front(pal, fl, 0.8f)
            d.ring(VfxLayers.FLOW_ADD, c, X, Z, inR, cur, b, b, segments, (r * 0.6).toFloat(), 0f)
            d.ring(VfxLayers.GLOW_ADD, c.add(0.0, 0.01, 0.0), X, Z, max(inR, cur - rw * 3.0), cur + rw * 1.5, fr, fr, segments, 0f, 0.5f)
        }
        rimRing(d, pal, c, r, rw, fl, segments)
        if (inR > 0.05) rimRing(d, pal, c, inR, rw, fl, segments)
        val wallH = if (safe) 1.4 else 0.45
        d.wall(
            VfxLayers.RAYS_ADD, c, X, Z, UP, r, r, wallH,
            rim(pal, fl, if (safe) 0.55f else 0.38f), rim(pal, fl, 0f),
            segments, (r * 0.5).toFloat(), t * 0.02f,
        )
    }

    @JvmStatic
    fun renderLine(
        d: VfxDraw, t: Float, pal: Palette, life: Int,
        from: Vec3d, to: Vec3d, halfWidth: Float, delta: Vec3d, dir: Vec3d, side: Vec3d,
    ) {
        val f = fill(t, life)
        val fl = flash(t, life)
        val hw = halfWidth.toDouble()
        val rw = rimWidth(hw * 4.0)
        val a = from.add(0.0, 0.1, 0.0)
        val b = to.add(0.0, 0.1, 0.0)
        val cur = a.add(delta.multiply(f.toDouble()))
        if (f > 0.01f) {
            val fa = fillAlpha(f, fl)
            val bodyCol = body(pal, fl, fa)
            val frontCol = front(pal, fl, 0.8f)
            d.band(VfxLayers.FLOW_ADD, a, cur, side, hw, bodyCol, bodyCol, 0.35f, 0f)
            val lift = Vec3d(0.0, 0.01, 0.0)
            val s = side.multiply(hw)
            d.band(VfxLayers.GLOW_ADD, cur.subtract(s).add(lift), cur.add(s).add(lift), dir, rw * 3.0, frontCol, frontCol, 0f, 0f)
        }
        val off = side.multiply(hw)
        rimLine(d, pal, a.add(off), b.add(off), rw, fl)
        rimLine(d, pal, a.subtract(off), b.subtract(off), rw, fl)
        rimLine(d, pal, a.subtract(off), a.add(off), rw, fl)
        rimLine(d, pal, b.subtract(off), b.add(off), rw, fl)
    }

    @JvmStatic
    fun renderCone(
        d: VfxDraw, t: Float, pal: Palette, life: Int,
        apex: Vec3d, radius: Float, forward: Vec3d, side: Vec3d, half: Double, full: Boolean, segments: Int,
    ) {
        val f = fill(t, life)
        val fl = flash(t, life)
        val c = apex.add(0.0, 0.09, 0.0)
        val r = radius.toDouble()
        val rw = rimWidth(r)
        val cur = r * f
        val arcLen = (r * half).toFloat()
        if (cur > 0.02) {
            val fa = fillAlpha(f, fl)
            val bodyCol = body(pal, fl, fa)
            val frontCol = front(pal, fl, 0.8f)
            d.arc(VfxLayers.FLOW_ADD, c, forward, side, 0.0, cur, -half, half, bodyCol, bodyCol, segments, arcLen * 0.6f, 0f)
            d.arc(VfxLayers.GLOW_ADD, c.add(0.0, 0.01, 0.0), forward, side, max(0.0, cur - rw * 3.0), cur + rw * 1.5, -half, half, frontCol, frontCol, segments, 0f, 0.5f)
        }
        val glow = rim(pal, fl, 0.5f)
        val edge = rim(pal, fl, 1f)
        d.arc(VfxLayers.GLOW_ADD, c, forward, side, max(0.0, r - rw * 2.6), r + rw * 2.6, -half, half, glow, glow, segments, 0f, 0.5f)
        d.arc(VfxLayers.ENERGY, c.add(0.0, 0.005, 0.0), forward, side, max(0.0, r - rw), r + rw, -half, half, edge, edge, segments, arcLen * 0.9f, 0f)
        if (!full) {
            for (ang in doubleArrayOf(-half, half)) {
                val ray = forward.multiply(cos(ang)).add(side.multiply(sin(ang)))
                rimLine(d, pal, c, c.add(ray.multiply(r)), rw, fl)
            }
        }
    }
}

package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha.logic.Vfx
import dev.henny.hha.net.VfxPayload
import dev.henny.hha_arius.arena.ArenaDimension
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Tracks nearby HHA warnings so the blizzard leaves their original frost surface visible. */
object GroundWarnings {
    private class Mark(val origin: Vec3d, val end: Vec3d, val radius: Double, val expires: Long)

    private class Burst(
        val kind: Vfx.Kind,
        val style: Int,
        val pos: Vec3d,
        val dir: Vec3d,
        val a: Double,
        val b: Double,
        val born: Long,
        val life: Int,
    )

    private val Z = Vec3d(0.0, 0.0, 1.0)

    private var level: ClientWorld? = null
    private val marks = ArrayList<Mark>()
    private val bursts = ArrayList<Burst>()

    /** Liegt ein Marker nahe der Kamera? Dann nimmt der Sturm sein Schneetreiben und die Weißblende zurück. */
    var nearWarning = false
        private set

    fun reset() {
        level = null
        nearWarning = false
        marks.clear()
        bursts.clear()
    }

    /** HHA still handles this packet. A true result selects its original telegraph palette. */
    @JvmStatic
    fun observe(p: VfxPayload, world: ClientWorld): Boolean {
        if (world.registryKey != ArenaDimension.WORLD) return false
        if (world !== level) {
            reset()
            level = world
        }
        val kind = Vfx.Kind.entries.getOrNull(p.kind) ?: return false
        val origin = Vec3d(p.x, p.y, p.z)
        val vector = Vec3d(p.dx, p.dy, p.dz)
        if (kind.name == "TELEGRAPH_CLEAR") {
            marks.removeIf { it.origin.squaredDistanceTo(origin) < 1e-4 }
            return false
        }
        if (kind == Vfx.Kind.CRESCENT || kind == Vfx.Kind.SHOCKWAVE || kind == Vfx.Kind.NOVA) {
            if (bursts.size >= 32) bursts.removeAt(0)
            bursts += Burst(kind, p.style, origin, vector,
                p.a.toDouble().coerceIn(0.4, 48.0), p.b.toDouble().coerceIn(0.2, 16.0),
                world.time, p.life.coerceIn(5, 26))
            return false
        }
        when (kind) {
            Vfx.Kind.TELEGRAPH_LINE, Vfx.Kind.TELEGRAPH_RING, Vfx.Kind.TELEGRAPH_CONE -> {
                if (marks.size >= 96) marks.removeAt(0)
                val end = if (kind == Vfx.Kind.TELEGRAPH_LINE) vector else origin
                marks += Mark(origin, end, p.a.toDouble().coerceAtLeast(0.0), world.time + p.life)
                return true
            }
            else -> return false
        }
    }

    fun render(world: ClientWorld, draw: VfxDraw) {
        if (level !== world) {
            if (level != null) reset()
            return
        }
        val time = world.time
        marks.removeIf { time >= it.expires }
        bursts.removeIf { time - it.born >= it.life }
        nearWarning = marks.any {
            val offset = draw.cam.subtract(it.origin)
            val axis = it.end.subtract(it.origin)
            val at = (offset.dotProduct(axis) / axis.lengthSquared().coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            offset.subtract(axis.multiply(at)).length() < it.radius + 30.0
        }
        if (bursts.isNotEmpty() && AriusClientConfig.fx > 0.01f) renderBursts(draw, time)
    }

    // --- Trefferbilder ---------------------------------------------------------------------------------------------------

    /** Kalte, klare Einschläge: dunkle Kante, heller Kern. Sie liegen über den Lichteffekten von HHA. */
    private fun renderBursts(draw: VfxDraw, time: Long) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        val vc = draw.buffer(AriusLayers.INK)
        for (b in bursts) {
            if (b.pos.squaredDistanceTo(draw.cam) > 90.0 * 90.0) continue
            val t = (time - b.born + draw.tickDelta).coerceAtLeast(0f)
            val u = (t / b.life).coerceIn(0f, 1f)
            val fade = (1f - u) * (1f - u) * fx
            if (fade <= 0.01f) continue
            val gold = b.style == Vfx.Style.GOLD.ordinal
            val deep = if (gold) 0xFF6B4708.toInt() else 0xFF103C66.toInt()
            val mid = if (gold) 0xFFFFC23A.toInt() else 0xFF57C6F2.toInt()
            val hot = if (gold) 0xFFFFF8E0.toInt() else 0xFFF2FCFF.toInt()
            when (b.kind) {
                Vfx.Kind.CRESCENT -> crescent(draw, vc, b, u, fade, deep, mid, hot)
                Vfx.Kind.SHOCKWAVE -> {
                    val grow = 1.0 - (1.0 - u) * (1.0 - u) * (1.0 - u)
                    val r = max(0.3, b.a * grow)
                    val thick = max(0.14, min(0.4, b.b * 0.22)) * (1.0 - u * 0.6)
                    flatRing(draw, vc, b.pos.add(0.0, 0.12, 0.0), r - thick * 2.2, r - thick * 0.5, withAlpha(mid, 0f), withAlpha(mid, 0.42f * fade))
                    flatRing(draw, vc, b.pos.add(0.0, 0.13, 0.0), r - thick * 0.5, r, withAlpha(deep, 0.9f * fade), withAlpha(deep, 0.9f * fade))
                    flatRing(draw, vc, b.pos.add(0.0, 0.14, 0.0), r - thick * 0.34, r - thick * 0.12, withAlpha(hot, fade), withAlpha(hot, fade))
                }
                Vfx.Kind.NOVA -> {
                    val grow = 1.0 - (1.0 - u) * (1.0 - u)
                    val r = max(0.3, b.a * (0.3 + 0.8 * grow))
                    val thick = max(0.07, r * 0.06)
                    camRing(draw, vc, b.pos, r - thick, r, withAlpha(deep, 0.8f * fade), withAlpha(mid, 0.95f * fade))
                    camRing(draw, vc, b.pos, r - thick * 0.45, r - thick * 0.12, withAlpha(hot, fade), withAlpha(hot, fade))
                }
                else -> Unit
            }
        }
    }

    /** Sichelklinge: an den Enden spitz, außen dunkle Kante, innen weißer Schnitt. */
    private fun crescent(d: VfxDraw, vc: VertexConsumer, b: Burst, u: Float, fade: Float, deep: Int, mid: Int, hot: Int) {
        val fwd = Vec3d(b.dir.x, 0.0, b.dir.z).let { if (it.lengthSquared() < 1e-6) Z else it.normalize() }
        val side = Vec3d(-fwd.z, 0.0, fwd.x)
        val span = b.b.coerceIn(0.8, 2.6)
        val reach = b.a * (0.82 + 0.26 * u)
        val thick = min(b.a * 0.14, 0.7) * (1.0 - u * 0.45)
        val n = 26
        fun point(s: Double, off: Double): Vec3d {
            val a = (s - 0.5) * span
            val r = reach - off
            return b.pos.add(fwd.multiply(cos(a) * r)).add(side.multiply(sin(a) * r))
        }
        fun taper(s: Double) = sin(s * PI).coerceAtLeast(0.0)
        fun strip(o0: Double, o1: Double, c0: Int, c1: Int, y: Double) {
            with(d) {
                for (i in 0 until n) {
                    val s0 = i.toDouble() / n
                    val s1 = (i + 1.0) / n
                    val a0 = point(s0, o0 * taper(s0))
                    val a1 = point(s0, o1 * taper(s0))
                    val b1 = point(s1, o1 * taper(s1))
                    val b0 = point(s1, o0 * taper(s1))
                    vc.put(a0.x, a0.y + y, a0.z, 0f, 0f, c0)
                    vc.put(a1.x, a1.y + y, a1.z, 1f, 0f, c1)
                    vc.put(b1.x, b1.y + y, b1.z, 1f, 1f, c1)
                    vc.put(b0.x, b0.y + y, b0.z, 0f, 1f, c0)
                }
            }
        }
        strip(0.0, thick, withAlpha(deep, 0.95f * fade), withAlpha(deep, 0.4f * fade), 0.0)
        strip(thick * 0.08, thick * 0.72, withAlpha(mid, 0.9f * fade), withAlpha(mid, 0f), 0.004)
        strip(thick * 0.12, thick * 0.3, withAlpha(hot, fade), withAlpha(hot, 0.3f * fade), 0.008)
    }

    private fun flatRing(d: VfxDraw, vc: VertexConsumer, c: Vec3d, r0: Double, r1: Double, c0: Int, c1: Int) {
        if (r1 <= 0.0) return
        val inner = max(0.0, r0)
        val n = ceil(2 * PI * r1 / 0.9).toInt().coerceIn(24, 120)
        with(d) {
            for (i in 0 until n) {
                val a0 = 2 * PI * i / n
                val a1 = 2 * PI * (i + 1) / n
                vc.put(c.x + inner * cos(a0), c.y, c.z + inner * sin(a0), 0f, 0f, c0)
                vc.put(c.x + r1 * cos(a0), c.y, c.z + r1 * sin(a0), 1f, 0f, c1)
                vc.put(c.x + r1 * cos(a1), c.y, c.z + r1 * sin(a1), 1f, 1f, c1)
                vc.put(c.x + inner * cos(a1), c.y, c.z + inner * sin(a1), 0f, 1f, c0)
            }
        }
    }

    private fun camRing(d: VfxDraw, vc: VertexConsumer, c: Vec3d, r0: Double, r1: Double, c0: Int, c1: Int) {
        if (r1 <= 0.0) return
        val inner = max(0.0, r0)
        val right = d.camRight
        val up = d.camUp
        val n = ceil(2 * PI * r1 / 0.5).toInt().coerceIn(20, 72)
        fun at(a: Double, r: Double) = c.add(right.multiply(cos(a) * r)).add(up.multiply(sin(a) * r))
        with(d) {
            for (i in 0 until n) {
                val a0 = 2 * PI * i / n
                val a1 = 2 * PI * (i + 1) / n
                vc.put(at(a0, inner), 0f, 0f, c0)
                vc.put(at(a0, r1), 1f, 0f, c1)
                vc.put(at(a1, r1), 1f, 1f, c1)
                vc.put(at(a1, inner), 0f, 1f, c0)
            }
        }
    }
}

package dev.henny.hha_arius.arena

import dev.henny.hha_arius.boss.RideRoute
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Feste Hindernisse entlang des Drachenritts ([RideRoute]), gebaut mit der Fernlandschaft ([FarColumns]) und danach für
 * immer Teil der Dimension. Seit 1.12.0 ist jedes so geformt, dass die Taste für jemanden Sinn ergibt, der **hinten am
 * Eisdorn hängt** (Crix: „es macht keinen Unterschied, ob ich mich ducke“):
 * * [Kind.ROCK_ARCH], [Kind.ICE_ARCH]: ein Sturz knapp über ihrem Rücken, man presst sich flach an sie.
 * * [Kind.FANGS]: zwei Reihen Eiszähne links und rechts ihres Leibs auf Höhe der herabhängenden Beine, man zieht sie an.
 * * [Kind.NEEDLE_LEFT], [Kind.NEEDLE_RIGHT]: eine Felsnadel dicht an einer Seite, man schwingt den Körper zur anderen.
 * * [Kind.ICEFALL]: ein gefrorener Wasserfall, dessen Gischt die Hände überfriert, man hackt sie frei.
 *
 * Solvane legt beim Durchflug die Flügel an ([dev.henny.hha_arius.boss.NivarisEntity.wingTuck]), darum passen die Lücken.
 */
internal object RideLandmarks {

    enum class Kind { ROCK_ARCH, ICE_ARCH, FANGS, NEEDLE_LEFT, NEEDLE_RIGHT, ICEFALL }

    class Landmark(
        val kind: Kind,
        val s: Double,
        val at: Vec3d,
        val fwd: Vec3d,
        val right: Vec3d,
        val minX: Int,
        val maxX: Int,
        val minZ: Int,
        val maxZ: Int,
    )

    /** Unterkante des Bogensturzes über dem Entity-Punkt: frei für Dornen, Arius und flach liegende Reiter. */
    const val LINTEL = 6.3

    /** Oberkante der Eiszähne über dem Entity-Punkt (Höhe der hängenden Beine). */
    private const val FANG_TOP = 4.3

    /** Abstand der Felsnadel von der Flugachse. */
    private const val NEEDLE_OFF = 6.0

    private class Spec(val u: Double, val kind: Kind)

    private val SPECS = listOf(
        Spec(0.205, Kind.ROCK_ARCH),
        Spec(0.325, Kind.FANGS),
        Spec(0.430, Kind.NEEDLE_RIGHT),
        Spec(0.535, Kind.ICE_ARCH),
        Spec(0.615, Kind.ICEFALL),
        Spec(0.695, Kind.NEEDLE_LEFT),
        Spec(0.825, Kind.FANGS),
    )

    /** Alle Hindernisse entlang der fertigen [RideRoute], nach Bogenlänge sortiert. */
    val all: List<Landmark> by lazy { build() }

    private fun build(): List<Landmark> {
        val len = RideRoute.length
        val out = ArrayList<Landmark>(SPECS.size)
        for (spec in SPECS) {
            val s = spec.u * len
            val at = RideRoute.at(s)
            val next = RideRoute.at(min(len, s + 5.0))
            val prev = RideRoute.at(max(0.0, s - 5.0))
            val rawF = Vec3d(next.x - prev.x, 0.0, next.z - prev.z)
            val fwd = if (rawF.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else rawF.normalize()
            val right = Vec3d(-fwd.z, 0.0, fwd.x)
            val (halfSpan, halfThick) = when (spec.kind) {
                Kind.ROCK_ARCH, Kind.ICE_ARCH -> 21.0 to 7.0
                Kind.FANGS -> 22.0 to 6.0
                Kind.NEEDLE_LEFT, Kind.NEEDLE_RIGHT -> 15.0 to 9.0
                Kind.ICEFALL -> 31.0 to 9.0
            }
            val rx = abs(right.x) * halfSpan + abs(fwd.x) * halfThick + 2.0
            val rz = abs(right.z) * halfSpan + abs(fwd.z) * halfThick + 2.0
            out += Landmark(
                spec.kind, s, at, fwd, right,
                floor(at.x - rx).toInt(), ceil(at.x + rx).toInt(), floor(at.z - rz).toInt(), ceil(at.z + rz).toInt(),
            )
        }
        return out
    }

    /** Schreibt die Hindernisse in die Spalte (x, z). */
    fun write(x: Int, z: Int, surface: Int, p: ColumnPlan) {
        if (HuntRoute.hit(x, z) >= 0) return
        for (lm in all) {
            if (x < lm.minX || x > lm.maxX || z < lm.minZ || z > lm.maxZ) continue
            val dx = x + 0.5 - lm.at.x
            val dz = z + 0.5 - lm.at.z
            val along = dx * lm.fwd.x + dz * lm.fwd.z
            val across = dx * lm.right.x + dz * lm.right.z
            when (lm.kind) {
                Kind.ROCK_ARCH -> arch(lm, x, z, along, across, surface, p, true)
                Kind.ICE_ARCH -> arch(lm, x, z, along, across, surface, p, false)
                Kind.FANGS -> fangs(lm, x, z, along, across, surface, p)
                Kind.NEEDLE_LEFT -> needle(lm, x, z, along, across + NEEDLE_OFF, surface, p)
                Kind.NEEDLE_RIGHT -> needle(lm, x, z, along, across - NEEDLE_OFF, surface, p)
                Kind.ICEFALL -> icefall(lm, x, z, along, across, surface, p)
            }
        }
    }

    /** Bogen: zwei Pfeiler weit außen, gewölbter Sturz knapp über ihrem Rücken, seitlich hängende Zapfen. */
    private fun arch(lm: Landmark, x: Int, z: Int, along: Double, across: Double, surface: Int, p: ColumnPlan, rock: Boolean) {
        val a = abs(across)
        val l = abs(along)
        if (a > 20.5 || l > 6.5) return
        val n = FarPlan.vnoise(x, z, 5, 1711)
        val base = lm.at.y
        val maxY = ColumnPlan.MAX_Y - 1
        val crown = base + 13.0 + n * 2.6 - a * 0.1
        if (a in 12.0 - n..19.5 + n) {
            val top = min(maxY, floor(crown).toInt())
            for (y in max(-1, surface - 2)..top) {
                val spread = ((top - y) * 0.05).coerceAtMost(2.4)
                if (l > 3.8 + spread + (n - 0.5) * 1.2) continue
                p.set(y, material(rock, x, y, z, y >= top - 1))
            }
        }
        if (a <= 19.0 && l <= 3.8) {
            var soffit = base + LINTEL + a * a * 0.011
            val hash = FarPlan.h32(x, 0, z, 1733)
            val tooth = a in 2.4..10.5 && l <= 2.2 && hash > 0.66
            if (tooth) soffit -= 0.7 + (hash - 0.66) * 2.4
            val hi = min(maxY, floor(crown).toInt())
            for (y in max(0, floor(soffit).toInt())..hi) {
                if (l > 3.0 && y <= floor(soffit).toInt() + 1) continue
                p.set(y, if (tooth && y <= floor(base + LINTEL).toInt()) FarPlan.BLUE else material(rock, x, y, z, y == hi))
            }
        }
    }

    private fun material(rock: Boolean, x: Int, y: Int, z: Int, cap: Boolean): Int {
        if (cap) return if (rock) FarPlan.SNOW else FarPlan.BLUE
        val r = FarPlan.h32(x, y, z, 1759)
        return if (rock) when {
            r < 0.35 -> FarPlan.DEEP
            r < 0.65 -> FarPlan.STONE
            r < 0.85 -> FarPlan.ANDESITE
            else -> FarPlan.TUFF
        } else when {
            r < 0.32 -> FarPlan.BLUE
            r < 0.85 -> FarPlan.PACKED
            else -> FarPlan.CALCITE
        }
    }

    /** Zwei Reihen Eiszähne: In der Mitte bleibt ein Schlitz für ihren Leib, die Spitzen stehen auf Beinhöhe. */
    private fun fangs(lm: Landmark, x: Int, z: Int, along: Double, across: Double, surface: Int, p: ColumnPlan) {
        val a = abs(across)
        val l = abs(along)
        if (a > 21.5 || l > 5.2) return
        val base = lm.at.y
        val jag = FarPlan.h32(floor(across * 0.8).toInt(), 0, floor(along * 0.8).toInt(), 1789)
        val crest = when {
            a < 3.3 -> base - 6.0
            a < 11.0 -> base + FANG_TOP - (a - 3.3) * 0.22 - jag * 1.4
            else -> base + 1.6 - (a - 11.0) * 0.9 + jag * 2.0
        }
        val top = min(ColumnPlan.MAX_Y - 1, floor(crest).toInt())
        val from = max(-1, surface - 2)
        if (top < from) return
        for (y in from..top) {
            val depth = (top - y).toDouble()
            if (l > (0.9 + depth * 0.20).coerceAtMost(4.6)) continue
            val id = when {
                depth <= 1.0 -> FarPlan.BLUE
                depth <= 5.0 -> if (FarPlan.h32(x, y, z, 1793) < 0.4) FarPlan.BLUE else FarPlan.PACKED
                y <= surface + 4 -> FarPlan.DEEP
                else -> if (FarPlan.h32(x, y, z, 1799) < 0.25) FarPlan.STONE else FarPlan.PACKED
            }
            p.set(y, id)
        }
    }

    /** Felsnadel: [along] und [dx] relativ zu ihrer Achse, nach unten breiter werdend, mit Eis an der Spitze. */
    private fun needle(lm: Landmark, x: Int, z: Int, along: Double, dx: Double, surface: Int, p: ColumnPlan) {
        val r = hypot(along, dx)
        if (r > 8.5) return
        val base = lm.at.y
        val n = FarPlan.vnoise(x, z, 3, 1801)
        val top = min(ColumnPlan.MAX_Y - 1, floor(base + 14.0 + n * 3.0).toInt())
        for (y in max(-1, surface - 2)..top) {
            val width = (1.2 + (top - y) * 0.085 + (n - 0.5) * 0.7).coerceAtMost(8.0)
            if (r > width) continue
            val id = when {
                y >= top - 3 -> FarPlan.BLUE
                y >= top - 8 -> if (FarPlan.h32(x, y, z, 1811) < 0.5) FarPlan.PACKED else FarPlan.ANDESITE
                else -> material(true, x, y, z, false)
            }
            p.set(y, id)
        }
    }

    /** Gefrorener Wasserfall von einem Felsdach, die Mitte bleibt als Durchflug frei. */
    private fun icefall(lm: Landmark, x: Int, z: Int, along: Double, across: Double, surface: Int, p: ColumnPlan) {
        val a = abs(across)
        val l = abs(along)
        if (a > 30.0 || l > 8.0) return
        val base = lm.at.y
        val maxY = ColumnPlan.MAX_Y - 1
        val n = FarPlan.vnoise(x, z, 4, 1823)
        val roof = base + 18.0
        if (a <= 26.0 && l <= 6.5 + n) {
            for (y in floor(roof).toInt()..min(maxY, floor(roof + 4.0 + n * 3.0 - a * 0.06).toInt())) {
                p.set(y, material(true, x, y, z, false))
            }
        }
        if (a in 23.0..29.5 && l <= 5.0 + n) {
            for (y in max(-1, surface - 2)..min(maxY, floor(roof + 2.0).toInt())) p.set(y, material(true, x, y, z, false))
        }
        if (a in 6.0..24.0 && l <= 2.6) {
            val lip = base + 1.0 + 7.0 * FarPlan.vnoise(x, z, 2, 1831) + (24.0 - a) * 0.1
            for (y in max(0, floor(lip).toInt())..floor(roof).toInt()) {
                p.set(y, if (FarPlan.h32(x, y, z, 1847) < 0.35) FarPlan.BLUE else FarPlan.PACKED)
            }
        }
    }
}

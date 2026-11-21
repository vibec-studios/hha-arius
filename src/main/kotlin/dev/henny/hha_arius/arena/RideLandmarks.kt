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
 * Dauerhafte Bauwerke entlang der Drachenritt-Route ([RideRoute]): feste Felsbögen, Gletschertore und
 * gezackte Eisgrate als Teil der Eirholm-Dimension (werden beim Bau der Fernlandschaft in [FarColumns] gesetzt
 * und verschwinden nie wieder). Die Quick-Time-Events in [dev.henny.hha_arius.boss.DragonRide] richten sich
 * exakt nach der Bogenlänge [Landmark.s] dieser festen Bauwerke.
 */
internal object RideLandmarks {

    enum class Kind { ROCK_ARCH, ICE_ARCH, ICE_RIDGE }

    class Landmark(
        val kind: Kind,
        val u: Double,
        val s: Double,
        val at: Vec3d,
        val fwd: Vec3d,
        val side: Vec3d,
        val minX: Int,
        val maxX: Int,
        val minZ: Int,
        val maxZ: Int,
        val qteKey: String,
    )

    private data class Spec(val u: Double, val kind: Kind, val qteKey: String)

    private val SPECS = listOf(
        Spec(0.20, Kind.ROCK_ARCH, "hha_arius.qte.ride_arch"),
        Spec(0.36, Kind.ICE_RIDGE, "hha_arius.qte.ride_ridge"),
        Spec(0.56, Kind.ICE_ARCH, "hha_arius.qte.ride_arch_ice"),
        Spec(0.72, Kind.ICE_RIDGE, "hha_arius.qte.ride_ridge"),
    )

    /** Alle dauerhaften Hindernisse entlang der fertigen [RideRoute], einmalig berechnet. */
    val all: List<Landmark> by lazy { build() }

    private fun build(): List<Landmark> {
        val len = RideRoute.length
        val out = ArrayList<Landmark>(SPECS.size)
        for (spec in SPECS) {
            val s = (spec.u * len).coerceIn(60.0, len - 80.0)
            val at = RideRoute.at(s)
            val next = RideRoute.at(min(len, s + 4.0))
            val prev = RideRoute.at(max(0.0, s - 4.0))
            val rawF = Vec3d(next.x - prev.x, 0.0, next.z - prev.z)
            val fwd = if (rawF.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else rawF.normalize()
            val side = Vec3d(fwd.z, 0.0, -fwd.x)
            // Sicher innerhalb der Fernlandschaft und außerhalb der Arenen.
            if (at.x < FarPlan.X_MIN + 24 || at.x > FarPlan.X_MAX - 24) continue
            if (at.z < FarPlan.Z_MIN + 24 || at.z > FarPlan.Z_MAX - 24) continue
            val halfSpan = if (spec.kind == Kind.ICE_RIDGE) 18.0 else 21.0
            val halfThick = 6.5
            val rx = abs(side.x) * halfSpan + abs(fwd.x) * halfThick + 2.0
            val rz = abs(side.z) * halfSpan + abs(fwd.z) * halfThick + 2.0
            out += Landmark(
                kind = spec.kind,
                u = spec.u,
                s = s,
                at = at,
                fwd = fwd,
                side = side,
                minX = floor(at.x - rx).toInt(),
                maxX = ceil(at.x + rx).toInt(),
                minZ = floor(at.z - rz).toInt(),
                maxZ = ceil(at.z + rz).toInt(),
                qteKey = spec.qteKey,
            )
        }
        return out
    }

    /** Schreibt dauerhafte QTE-Felsbögen und Eisgrate in die Spalte (x, z), falls sie ein Bauwerk berührt. */
    fun write(x: Int, z: Int, surface: Int, p: ColumnPlan) {
        if (HuntRoute.hit(x, z) >= 0) return
        for (lm in all) {
            if (x < lm.minX || x > lm.maxX || z < lm.minZ || z > lm.maxZ) continue
            when (lm.kind) {
                Kind.ROCK_ARCH, Kind.ICE_ARCH -> writeArch(lm, x, z, surface, p)
                Kind.ICE_RIDGE -> writeRidge(lm, x, z, surface, p)
            }
        }
    }

    /**
     * Massiver Fels- oder Eisbogen über der Fluglinie: Pfeiler tief im Gelände verankert, tiefer Sturz und
     * herabhängende Eiszapfen genau auf Kopfhöhe eines aufrecht hängenden Reiters (Ducken = darunter durch).
     */
    private fun writeArch(lm: Landmark, x: Int, z: Int, surface: Int, p: ColumnPlan) {
        val dx = (x + 0.5) - lm.at.x
        val dz = (z + 0.5) - lm.at.z
        val along = dx * lm.fwd.x + dz * lm.fwd.z
        val across = dx * lm.side.x + dz * lm.side.z
        val absAcross = abs(across)
        val absAlong = abs(along)
        if (absAcross > 19.5 || absAlong > 5.5) return

        val rock = lm.kind == Kind.ROCK_ARCH
        val n = FarPlan.vnoise(x, z, 5, 1711)
        val baseY = lm.at.y
        val maxY = ColumnPlan.MAX_Y - 1

        // Seitliche Pfeiler (außerhalb von Solvanes Schwingen), fest bis auf den Boden verankert.
        val pillarInner = 11.8 - n * 1.4
        val pillarOuter = 18.2 + n * 1.5
        if (absAcross in pillarInner..pillarOuter) {
            val topY = min(maxY, floor(baseY + 10.2 - (absAcross - pillarInner) * 0.28 + n * 2.4).toInt())
            val startY = max(-1, surface - 2)
            for (y in startY..topY) {
                val spread = ((topY - y) * 0.06).coerceAtMost(2.2)
                if (absAlong > 3.6 + spread + (n - 0.5) * 1.2) continue
                val id = archBlock(rock, x, y, z, y >= topY - 1)
                p.set(y, id)
            }
        }

        // Bogensturz quer über die Flugbahn: Unterkante knapp über Solvanes Rücken (y ≈ baseY + 5.1),
        // herabhängende Zähne bis baseY + 4.5 (auf Kopfhöhe eines nicht geduckten Reiters).
        if (absAcross <= 14.5 && absAlong <= 4.2) {
            val curve = (absAcross * absAcross) * 0.012
            var soffit = baseY + 5.2 + curve
            // Hängende Eiszapfen / Felszähne im mittleren Durchflugbereich.
            val toothNoise = FarPlan.h32(x, 0, z, 1733)
            val isTooth = absAcross <= 7.5 && absAlong <= 2.4 && toothNoise > 0.62
            if (isTooth) soffit -= 0.8 + (toothNoise - 0.62) * 1.6
            val yLo = max(0, floor(soffit).toInt())
            val yHi = min(maxY, floor(baseY + 9.4 + n * 2.2 - absAcross * 0.07).toInt())
            if (yLo <= yHi) {
                for (y in yLo..yHi) {
                    if (absAlong > 3.2 && y <= yLo + 1) continue
                    val id = if (isTooth && y <= floor(baseY + 5.1).toInt()) {
                        if (toothNoise > 0.82) FarPlan.BLUE else FarPlan.PACKED
                    } else {
                        archBlock(rock, x, y, z, y == yHi)
                    }
                    p.set(y, id)
                }
            }
        }
    }

    private fun archBlock(rock: Boolean, x: Int, y: Int, z: Int, cap: Boolean): Int {
        if (cap) return if (rock) FarPlan.SNOW else FarPlan.BLUE
        val r = FarPlan.h32(x, y, z, 1759)
        return if (rock) {
            when {
                r < 0.35 -> FarPlan.DEEP
                r < 0.65 -> FarPlan.STONE
                r < 0.85 -> FarPlan.ANDESITE
                else -> FarPlan.TUFF
            }
        } else {
            when {
                r < 0.32 -> FarPlan.BLUE
                r < 0.85 -> FarPlan.PACKED
                else -> FarPlan.CALCITE
            }
        }
    }

    /**
     * Gezackter Eisgrat quer unter der Flugbahn: in der Mitte eine Kerbe für Solvanes Bauch, links und rechts
     * rasiermesserscharfe Eiszacken bis auf Höhe der herunterhängenden Reiterbeine (Hochziehen = drüber weg).
     */
    private fun writeRidge(lm: Landmark, x: Int, z: Int, surface: Int, p: ColumnPlan) {
        val dx = (x + 0.5) - lm.at.x
        val dz = (z + 0.5) - lm.at.z
        val along = dx * lm.fwd.x + dz * lm.fwd.z
        val across = dx * lm.side.x + dz * lm.side.z
        val absAcross = abs(across)
        val absAlong = abs(along)
        if (absAcross > 16.5 || absAlong > 4.6) return

        val n = FarPlan.vnoise(x, z, 4, 1777)
        val jagged = FarPlan.h32(floor(across * 0.7).toInt(), 0, floor(along * 0.7).toInt(), 1789)
        val baseY = lm.at.y
        val maxY = ColumnPlan.MAX_Y - 1

        // Profil quer zur Bahn: Mitte tief (baseY - 0.8), Flanken (2..6 Blöcke) hoch auf Beinhöhe (baseY + 2.8),
        // außen markante Eistürme (baseY + 6.5) als weithin sichtbare Wegmarke.
        val crestY = when {
            absAcross < 1.8 -> baseY - 0.8 + n * 0.6
            absAcross < 6.8 -> {
                val t = (absAcross - 1.8) / 5.0
                baseY + 1.4 + t * 2.2 + jagged * 1.1
            }
            else -> {
                val t = ((absAcross - 6.8) / 9.7).coerceIn(0.0, 1.0)
                baseY + 6.4 * (1.0 - t * 0.65) + n * 2.0
            }
        }
        val topY = min(maxY, floor(crestY).toInt())
        val startY = max(-1, surface - 2)
        if (topY < startY) return

        for (y in startY..topY) {
            val distFromTop = (topY - y).toDouble()
            // Schmaler, scharfer Grat oben, breiter Sockel nach unten ins Gebirge.
            val halfW = (1.1 + distFromTop * 0.14).coerceAtMost(4.4)
            if (absAlong > halfW) continue
            val id = when {
                y >= topY - 1 -> FarPlan.BLUE
                distFromTop <= 3.0 -> if (FarPlan.h32(x, y, z, 1793) < 0.35) FarPlan.BLUE else FarPlan.PACKED
                y <= surface + 4 -> FarPlan.DEEP
                else -> if (FarPlan.h32(x, y, z, 1799) < 0.25) FarPlan.STONE else FarPlan.PACKED
            }
            p.set(y, id)
        }
    }
}

package dev.henny.hha_arius.arena

import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.floor

/**
 * Selbsttest des Jagdwegs (seit 1.10.0, Crix: „manche Wege sind komisch abgeschnitten“). Läuft die dichte Route
 * ([HuntRoute], alle 1 Block) in der Mitte und an beiden Rändern der sicher begehbaren Breite ab, in Höhlen auch den
 * Nebengang, und prüft je Stelle:
 * * **Boden:** eine Oberkante höchstens 0,6 über und 0,6 unter der erwarteten Lauffläche (kein Loch, keine Wand),
 * * **Kopffreiheit:** 1,8 Blöcke frei darüber,
 * * **Stufen:** entlang der Mitte nie mehr als 0,6 von Punkt zu Punkt (laufen ohne Springen),
 * * **Reste:** keine Lücke im Bauplan (KEEP) in der Kopffreiheit – dort bliebe stehen, was eine ältere Fassung baute.
 * [plan] prüft den Bauplan ([FarColumns], genau das, was gebaut wird), [world] die echten Blöcke einer Instanz.
 * Aufrufbar über `/ariusroute` (Op) und im Client-Spieltest.
 */
object RouteCheck {

    class Issue(val index: Int, val arc: Double, val kind: Int, val lateral: Double, val what: String) {
        override fun toString(): String = "#$index (Bogen %.1f, Art %d, seitlich %.1f): %s".format(arc, kind, lateral, what)
    }

    /** Abfrage der Kollision einer Spalte (relativ zum Instanz-Ursprung). */
    interface Probe {
        /** Höchste Oberkante einer Kollision in [yMin, yMax] oder NaN. */
        fun top(x: Int, z: Int, yMin: Double, yMax: Double): Double

        /** Ist (y0, y1) frei von Kollision? null = unbekannt (Lücke im Plan). */
        fun clear(x: Int, z: Int, y0: Double, y1: Double): Boolean?
    }

    /** Kollisionshöhe (0..1,5) eines Plan-Blocks oder 0 für keine Kollision. */
    private fun height(id: Int): Double = when (id) {
        FarPlan.AIR, in FarPlan.LIGHT..FarPlan.LIGHT + 3 -> 0.0
        in FarPlan.SNOW_LAYER..FarPlan.SNOW_LAYER + 7 -> (id - FarPlan.SNOW_LAYER) / 8.0
        in FarPlan.DECK..FarPlan.DECK + 7 -> (id - FarPlan.DECK + 1) / 8.0
        FarPlan.WALL, FarPlan.BRICKWALL, FarPlan.FENCE -> 1.5
        else -> 1.0
    }

    private class PlanProbe : Probe {
        private val cache = HashMap<Long, IntArray>()
        private val plan = ColumnPlan()

        /** ids[0] = lo, ids[1] = hi, danach y −1..MAX_Y. */
        private fun col(x: Int, z: Int): IntArray = cache.getOrPut((x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)) {
            plan.reset()
            FarColumns.plan(x, z, plan)
            val out = IntArray(ColumnPlan.MAX_Y + 4)
            out[0] = plan.lo
            out[1] = plan.hi
            for (y in -1..ColumnPlan.MAX_Y) out[y + 3] = plan.get(y)
            out
        }

        private fun id(c: IntArray, y: Int): Int = when {
            y > c[1] -> FarPlan.AIR
            y < c[0] || y < -1 -> FarPlan.STONE // Boden des Weltgenerators
            y > ColumnPlan.MAX_Y -> FarPlan.AIR
            else -> c[y + 3]
        }

        override fun top(x: Int, z: Int, yMin: Double, yMax: Double): Double {
            val c = col(x, z)
            for (by in floor(yMax).toInt() downTo floor(yMin).toInt() - 2) {
                val id = id(c, by)
                if (id == ColumnPlan.KEEP) continue
                val h = height(id)
                if (h <= 0.0) continue
                val t = by + h
                if (t <= yMax + 1e-6 && t >= yMin - 1e-6) return t
                if (t < yMin) return Double.NaN
            }
            return Double.NaN
        }

        override fun clear(x: Int, z: Int, y0: Double, y1: Double): Boolean? {
            val c = col(x, z)
            for (by in floor(y0).toInt() - 1..floor(y1).toInt()) {
                val id = id(c, by)
                if (id == ColumnPlan.KEEP) {
                    if (by + 1 > y0 + 1e-6 && by < y1) return null
                    continue
                }
                val h = height(id)
                if (h > 0.0 && by + h > y0 + 1e-6 && by < y1 - 1e-6) return false
            }
            return true
        }
    }

    private class WorldProbe(val world: ServerWorld, val origin: BlockPos) : Probe {
        private fun shapeTop(x: Int, y: Int, z: Int): Double {
            val pos = origin.add(x, y, z)
            val shape = world.getBlockState(pos).getCollisionShape(world, pos)
            return if (shape.isEmpty) 0.0 else shape.getMax(Direction.Axis.Y)
        }

        override fun top(x: Int, z: Int, yMin: Double, yMax: Double): Double {
            for (by in floor(yMax).toInt() downTo floor(yMin).toInt() - 2) {
                val h = shapeTop(x, by, z)
                if (h <= 0.0) continue
                val t = by + h
                if (t <= yMax + 1e-6 && t >= yMin - 1e-6) return t
                if (t < yMin) return Double.NaN
            }
            return Double.NaN
        }

        override fun clear(x: Int, z: Int, y0: Double, y1: Double): Boolean {
            for (by in floor(y0).toInt() - 1..floor(y1).toInt()) {
                val pos = origin.add(x, by, z)
                val shape = world.getBlockState(pos).getCollisionShape(world, pos)
                if (shape.isEmpty) continue
                if (by + shape.getMax(Direction.Axis.Y) > y0 + 1e-6 && by + shape.getMin(Direction.Axis.Y) < y1 - 1e-6) return false
            }
            return true
        }
    }

    /** `/ariusroute`: Bauplan prüfen; steht man in einer Instanz, auch die echten Blöcke dort (OP 2). */
    fun registerCommand() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("ariusroute")
                    .requires(net.minecraft.server.command.CommandManager.requirePermissionLevel<net.minecraft.server.command.ServerCommandSource>(net.minecraft.server.command.CommandManager.GAMEMASTERS_CHECK))
                    .executes { ctx ->
                        val src = ctx.source
                        val issues = plan(50)
                        src.sendFeedback({ net.minecraft.text.Text.translatable("hha_arius.route.plan", issues.size) }, false)
                        for (i in issues.take(8)) src.sendFeedback({ net.minecraft.text.Text.literal(" • $i") }, false)
                        val p = src.player
                        if (p != null && p.entityWorld.registryKey == ArenaDimension.WORLD) {
                            val w = p.entityWorld as ServerWorld
                            val o = ArenaLayout.origin(dev.henny.hha_arius.boss.Encounters.slotOf(p.x))
                            val live = world(w, o, 50)
                            src.sendFeedback({ net.minecraft.text.Text.translatable("hha_arius.route.world", live.size) }, false)
                            for (i in live.take(8)) src.sendFeedback({ net.minecraft.text.Text.literal(" • $i") }, false)
                        }
                        if (issues.isEmpty()) 1 else 0
                    },
            )
        }
    }

    fun plan(limit: Int = 200): List<Issue> = run(PlanProbe(), limit)

    fun world(world: ServerWorld, origin: BlockPos, limit: Int = 200): List<Issue> = run(WorldProbe(world, origin), limit)

    private fun run(probe: Probe, limit: Int): List<Issue> {
        val out = ArrayList<Issue>()
        var lastTop = Double.NaN
        var i = 0
        // Bis zum Tor (danach liegt die Gipfelschulter, die nicht zum Weg gehört).
        val end = (0 until HuntRoute.size).lastOrNull { HuntRoute.kind(it) == HuntRoute.GATE }?.let { it - 6 } ?: (HuntRoute.size - 1)
        while (i <= end && out.size < limit) {
            val stand = HuntRoute.standY(i)
            val (tx, tz) = HuntRoute.tangent(i)
            val w = HuntRoute.walkHalf(i)
            val offsets = doubleArrayOf(0.0, -w, w)
            for ((k, off) in offsets.withIndex()) {
                val px = HuntRoute.x(i) - tz * off
                val pz = HuntRoute.z(i) + tx * off
                val cx = floor(px).toInt()
                val cz = floor(pz).toInt()
                val t = checkAt(probe, cx, cz, stand, i, off, out) ?: continue
                // Stufen zu den Nachbarspalten derselben Lauffläche (vier Richtungen): höchstens 0,6 (ohne Springen).
                for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    if (HuntRoute.postAt(cx + dx, cz + dz) >= 0) continue
                    val j = HuntRoute.indexAtHeight(cx + dx, cz + dz, stand, 0.0, 1.2)
                    if (j < 0 || kotlin.math.abs(j - i) > 12) continue
                    val (jx, jz) = HuntRoute.tangent(j)
                    val lj = kotlin.math.abs((cx + dx + 0.5 - HuntRoute.x(j)) * -jz + (cz + dz + 0.5 - HuntRoute.z(j)) * jx)
                    if (lj > HuntRoute.walkHalf(j) + 0.3) continue
                    val tn = probe.top(cx + dx, cz + dz, HuntRoute.standY(j) - 0.7, HuntRoute.standY(j) + 0.7)
                    if (!tn.isNaN() && kotlin.math.abs(tn - t) > 0.6 + 1e-6) {
                        out += Issue(i, HuntRoute.arc(i), HuntRoute.kind(i), off, "Stufe %.3f zur Nachbarspalte %d,%d (#%d)".format(tn - t, cx + dx, cz + dz, j))
                        break
                    }
                }
                @Suppress("UNUSED_VALUE")
                if (k == 0) lastTop = t
            }
            // Nebengang der Höhlen (Bergseite, 5,5 neben der Mitte)
            val cave = HuntRoute.caveOf(i)
            if (cave != null && cave.u(i) > 1.0 && cave.u(i) < cave.length - 1.0) {
                for (sgn in intArrayOf(-1, 1)) {
                    val px = HuntRoute.x(i) - tz * 5.5 * sgn
                    val pz = HuntRoute.z(i) + tx * 5.5 * sgn
                    if (HuntRoute.outward(i, floor(px).toInt(), floor(pz).toInt())) continue
                    checkAt(probe, floor(px).toInt(), floor(pz).toInt(), stand, i, 5.5 * sgn, out, "Nebengang: ")
                }
            }
            i += 2
        }
        return out
    }

    private fun checkAt(probe: Probe, x: Int, z: Int, stand: Double, i: Int, off: Double, out: MutableList<Issue>, prefix: String = ""): Double? {
        // Laternenpfosten am Rand sind gewollt (sie stehen außerhalb der Mitte).
        if (off != 0.0 && HuntRoute.postAt(x, z) >= 0) return null
        val t = probe.top(x, z, stand - 0.62, stand + 0.62)
        if (t.isNaN()) {
            val higher = probe.top(x, z, stand + 0.62, stand + 6.0)
            out += Issue(i, HuntRoute.arc(i), HuntRoute.kind(i), off, prefix + if (!higher.isNaN()) "Wand/Hindernis (Oberkante %.1f über der Lauffläche)".format(higher - stand) else "kein Boden")
            return null
        }
        when (probe.clear(x, z, t, t + 1.8)) {
            false -> out += Issue(i, HuntRoute.arc(i), HuntRoute.kind(i), off, prefix + "Kopffreiheit < 1,8")
            null -> out += Issue(i, HuntRoute.arc(i), HuntRoute.kind(i), off, prefix + "Lücke im Bauplan (alte Blöcke blieben stehen)")
            else -> Unit
        }
        return t
    }
}

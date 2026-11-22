package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.arena.HuntRoute
import dev.henny.hha_arius.portal.PortalBlocks
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hängebrücken (seit 1.10.0): Die Kollision ist ein stabiles, unsichtbares Deck (Schichtblöcke in der belasteten Lage,
 * [PortalBlocks.DECK]) mit Barrieren als Geländer. Gezeichnet wird hier, nur am Client:
 * * Planken (Fichte, je 0,5 Blöcke, leicht unregelmäßig), Fußseile und Handläufe aus Wolle, Hänger alle Block,
 * * **Durchhängen und Nachgeben:** ohne Last hängt die Brücke etwas höher; unter jedem Spieler senkt sie sich genau auf
 *   die Kollision (die Planke unter den Füßen liegt immer da, wo man steht – kein Schweben, kein Durchfallen),
 * * **Schwingen** im Wind (Sturmstärke aus [SnowStorm]), nahe der Spieler gedämpft,
 * * angekündigte Brüche zittern ([shake]); fehlt das Deck (gebrochen, eingestürzt), fehlen die Planken, über längeren
 *   Lücken hängen die gerissenen Seile herab.
 * Wer selbst auf der Brücke steht, spürt ein leichtes Kippen ([localRoll]) und hört Knarren.
 *
 * Seit 1.11.0: Jede Planke liegt in einer festen Basis aus Laufrichtung und Querachse. Vorher drehte `rotationTo` von +X
 * auf die Laufrichtung: Auf B3 (läuft fast genau nach −X) ist diese Drehung mehrdeutig, die Planken wirbelten wie ein
 * Fächer. Morsche Planken ([HuntRoute.weakPlanks]) sind sichtbar gesplittert und brechen unter dem Fuß. Gekappte Seile
 * lassen das Deck als Kette am nahen Anker herabschwingen ([Drop]). Am fernen Kopf markiert ein Leuchten die Seile.
 *
 * Seit 1.12.0 schwingt die Brücke als gespanntes Seil ([Sway]): Böen laufen als Welle über die Spannweite, Schritte und
 * Plankenbrüche federn nach, die Enden bleiben fest. Tragseile und Handläufe laufen immer durch, auch über Lücken im Deck;
 * erst gekappte Seile fallen.
 */
object RopeBridges {

    private const val PLANK = 0.5
    private const val LIFT = 0.55

    private class Shake(val at: Vec3d, val r: Double, var left: Int)

    private val shakes = ArrayList<Shake>()
    private var time = 0L
    private var creak = 40
    private var localSway = 0f
    private var onBridge = false

    fun shake(at: Vec3d, r: Double, life: Int) {
        shakes += Shake(at, r, life)
    }

    fun reset() {
        shakes.clear()
        drops.clear()
        sims.clear()
        lastAt.clear()
        localSway = 0f
        onBridge = false
    }

    fun clearDrops() = drops.clear()

    // --- Gekappte Brücke: Kette aus Punkten (Verlet), am nahen Kopf aufgehängt --------------------------------------------

    private class Drop(val span: HuntRoute.Span, val o: BlockPos, val side: Vec3d, start: List<Vec3d>) {
        val cur = Array(start.size) { start[it] }
        val old = Array(start.size) { start[it] }
        val prev = Array(start.size) { start[it] }
        private val tops = HashMap<Long, Double>()

        private fun floorAt(p: Vec3d): Double {
            val x = floor(p.x).toInt() - o.x
            val z = floor(p.z).toInt() - o.z
            return tops.getOrPut((x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)) {
                o.y + runCatching { FarPlan.top(x, z) }.getOrDefault(-64) + 1.15
            }
        }

        fun tick() {
            for (i in cur.indices) prev[i] = cur[i]
            for (i in 1 until cur.size) {
                val v = cur[i].subtract(old[i]).multiply(0.985)
                old[i] = cur[i]
                cur[i] = cur[i].add(v).add(0.0, -0.055, 0.0)
            }
            repeat(10) {
                cur[0] = start0
                for (i in 0 until cur.size - 1) {
                    val d = cur[i + 1].subtract(cur[i])
                    val len = d.length().coerceAtLeast(1e-6)
                    val fix = d.multiply((len - LINK) / len)
                    if (i == 0) {
                        cur[1] = cur[1].subtract(fix)
                    } else {
                        cur[i] = cur[i].add(fix.multiply(0.5))
                        cur[i + 1] = cur[i + 1].subtract(fix.multiply(0.5))
                    }
                }
                for (i in 1 until cur.size) {
                    val fl = floorAt(cur[i])
                    if (cur[i].y < fl) {
                        cur[i] = Vec3d(cur[i].x, fl, cur[i].z)
                        old[i] = Vec3d(old[i].x + (cur[i].x - old[i].x) * 0.4, fl, old[i].z + (cur[i].z - old[i].z) * 0.4)
                    }
                }
            }
        }

        private val start0 = start[0]

        fun at(i: Int, td: Float): Vec3d = prev[i].lerp(cur[i], td.toDouble())
    }

    private const val LINK = 1.0
    private val drops = HashMap<Int, Drop>()

    /** Die Seile von Brücke [id] sind gekappt: das Deck fällt (am nahen Kopf hängend). */
    fun drop(id: Int, at: Vec3d) {
        val span = HuntRoute.ropeSpans.getOrNull(id) ?: return
        if (id in drops) return
        val o = origin(at)
        val n = span.length.toInt().coerceAtLeast(2)
        val pts = (0..n).map { k ->
            val i = HuntRoute.indexOfArc(span.a0 + k * span.length / n)
            Vec3d(o.x + HuntRoute.x(i), o.y + HuntRoute.standY(i), o.z + HuntRoute.z(i))
        }
        val (tx, tz) = HuntRoute.tangent(span.i0)
        drops[id] = Drop(span, o, Vec3d(-tz, 0.0, tx), pts)
        HuntCine.playAt(SoundEvents.ITEM_LEAD_BREAK, at, 2.5f, 0.6f)
        HuntCine.playAt(SoundEvents.BLOCK_BAMBOO_WOOD_BREAK, at, 2.5f, 0.5f)
    }

    private fun renderDrop(dr: Drop, d: VfxDraw) {
        val td = d.tickDelta
        val n = dr.cur.size - 1
        var s = 0.0
        while (s <= dr.span.length) {
            if (s < 3.0 || s > dr.span.length - 3.0) {
                val i = HuntRoute.indexOfArc(dr.span.a0 + s)
                val c = Vec3d(dr.o.x + HuntRoute.x(i), dr.o.y + HuntRoute.standY(i) - 0.06, dr.o.z + HuntRoute.z(i))
                val (tx, tz) = HuntRoute.tangent(i)
                box(d, planks, c, Vec3d(tx, 0.0, tz), Vec3d(-tz, 0.0, tx), 0.44, 0.1, 3.3, 0.0)
            }
            s += PLANK
        }
        for (k in 0 until n) {
            val a = dr.at(k, td)
            val b = dr.at(k + 1, td)
            if (a.squaredDistanceTo(d.cam) > 130.0 * 130.0) continue
            val along = b.subtract(a).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, -1.0, 0.0) else it.normalize() }
            val normal = dr.side.crossProduct(along).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 1.0, 0.0) else it.normalize() }
            for (sgn in intArrayOf(-1, 1)) {
                val fa = a.add(dr.side.multiply(sgn * 1.72))
                val fb = b.add(dr.side.multiply(sgn * 1.72))
                seg(d, rope, fa, fb, 0.08)
                val ha = a.add(dr.side.multiply(sgn * 1.88)).add(normal.multiply(0.95))
                val hb = b.add(dr.side.multiply(sgn * 1.88)).add(normal.multiply(0.95))
                seg(d, rope, ha, hb, 0.075)
                if (k % 2 == 0) seg(d, rope, fa, ha, 0.045)
            }
            for (h in 0..1) {
                val key = k * 2 + h
                if (((key * 7919) % 11) < 3) continue // ein Teil der Planken hat der Sturz abgerissen
                val c = a.lerp(b, h * 0.5 + 0.25)
                val twist = (((key * 3571) % 17) / 17.0 - 0.5) * 0.5
                box(d, if (key % 7 == 3) planksDark else planks, c, along, dr.side, 0.44, 0.1, 3.1, twist)
            }
        }
    }

    // --- Schwingen: gespanntes Seil mit festen Enden -----------------------------------------------------------------------

    private class Sway(val n: Int) {
        val lat = DoubleArray(n + 1)
        val latV = DoubleArray(n + 1)
        val latOld = DoubleArray(n + 1)
        val dip = DoubleArray(n + 1)
        val dipV = DoubleArray(n + 1)
        val dipOld = DoubleArray(n + 1)

        fun latAt(k: Int, td: Float) = latOld[k] + (lat[k] - latOld[k]) * td
        fun dipAt(k: Int, td: Float) = dipOld[k] + (dip[k] - dipOld[k]) * td

        fun kick(k: Int, amount: Double) {
            for (j in maxOf(1, k - 2)..minOf(n - 1, k + 2)) dipV[j] += amount * (1.0 - abs(j - k) / 3.0)
        }

        fun step(t: Double, id: Int, wind: Double) {
            System.arraycopy(lat, 0, latOld, 0, n + 1)
            System.arraycopy(dip, 0, dipOld, 0, n + 1)
            repeat(2) {
                for (k in 1 until n) {
                    val u = k / n.toDouble()
                    val taper = 4 * u * (1 - u)
                    // Die Böe läuft über die Spannweite; ein zweiter, schnellerer Anteil macht sie unregelmäßig.
                    val gust = sin(t * 0.045 + id * 1.7 - u * 2.2) + 0.45 * sin(t * 0.118 + id + u * 5.0)
                    latV[k] += 0.23 * (lat[k - 1] - 2 * lat[k] + lat[k + 1]) - 0.012 * lat[k] - 0.055 * latV[k] + gust * wind * taper * 0.0075
                    dipV[k] += 0.2 * (dip[k - 1] - 2 * dip[k] + dip[k + 1]) - 0.03 * dip[k] - 0.085 * dipV[k]
                }
                for (k in 1 until n) {
                    lat[k] = (lat[k] + latV[k]).coerceIn(-0.9, 0.9)
                    dip[k] = (dip[k] + dipV[k]).coerceIn(-0.45, 0.3)
                }
            }
        }
    }

    private val sims = HashMap<Int, Sway>()
    private val lastAt = HashMap<Int, Vec3d>()

    /** Eine Brücke in der Nähe rechnet weiter: Wind, Schritte der Spieler, Erschütterungen. */
    private fun tickSway(world: net.minecraft.client.world.ClientWorld, cam: Vec3d) {
        val o = origin(cam)
        for (span in HuntRoute.ropeSpans) {
            val mid = HuntRoute.indexOfArc((span.a0 + span.a1) / 2)
            val dx = o.x + HuntRoute.x(mid) - cam.x
            val dz = o.z + HuntRoute.z(mid) - cam.z
            if (dx * dx + dz * dz > 200.0 * 200.0 || span.id in drops) continue
            val n = (span.length / PLANK).toInt()
            val sim = sims.getOrPut(span.id) { Sway(n) }
            for (pl in world.players) {
                val h = HuntRoute.bridgeEntry(floor(pl.x).toInt() - o.x, floor(pl.z).toInt() - o.z)
                if (h < 0) continue
                val j = HuntRoute.hitIndex(h)
                if (j !in span.i0..span.i1 || abs(pl.y - o.y - HuntRoute.standY(j)) > 2.0) continue
                val now = pl.entityPos
                val before = lastAt.put(pl.id, now) ?: continue
                val moved = sqrt((now.x - before.x) * (now.x - before.x) + (now.z - before.z) * (now.z - before.z))
                if (moved > 0.04 && pl.age % 5 == 0) sim.kick(((HuntRoute.arc(j) - span.a0) / PLANK).toInt().coerceIn(1, n - 1), -0.018 * moved.coerceAtMost(0.5) / 0.25)
            }
            for (sh in shakes) {
                val k = ((HuntRoute.arc(HuntRoute.indexAtHeight(floor(sh.at.x).toInt() - o.x, floor(sh.at.z).toInt() - o.z, sh.at.y - o.y, 2.0, 3.0).coerceAtLeast(span.i0)) - span.a0) / PLANK).toInt()
                if (k in 1 until n && time % 3 == 0L) sim.kick(k, 0.03)
            }
            sim.step(time.toDouble(), span.id, wind())
        }
    }

    private fun origin(cam: Vec3d): BlockPos {
        val slot = Math.floorDiv(floor(cam.x).toInt() + ArenaLayout.SLOT_SPACING / 2, ArenaLayout.SLOT_SPACING)
        return ArenaLayout.origin(slot)
    }

    private fun wind(): Double = (0.12 + 0.12 * SnowStorm.strength.coerceIn(0f, 2.5f)).toDouble()

    /** Bauch der Brücke 0..1 (die ebenen Köpfe ausgenommen), [s] = Bogenlänge ab Anfang. */
    private fun belly(span: HuntRoute.Span, s: Double): Double {
        val u = ((s - HuntRoute.HEAD) / (span.length - 2 * HuntRoute.HEAD)).coerceIn(0.0, 1.0)
        return 4 * u * (1 - u)
    }

    fun tick() {
        time++
        shakes.removeIf { --it.left <= 0 }
        for (dr in drops.values) dr.tick()
        val mc = MinecraftClient.getInstance()
        val p = mc.player ?: return
        val world = mc.world ?: return
        onBridge = false
        if (world.registryKey != ArenaDimension.WORLD) return
        tickSway(world, mc.gameRenderer.camera.cameraPos)
        val o = origin(p.entityPos)
        val rx = floor(p.x).toInt() - o.x
        val rz = floor(p.z).toInt() - o.z
        val h = HuntRoute.bridgeEntry(rx, rz)
        if (h >= 0 && HuntRoute.kind(HuntRoute.hitIndex(h)) == HuntRoute.ROPE && HuntRoute.hitLateral(h) <= HuntRoute.ROPE_RAIL &&
            abs(p.y - o.y - HuntRoute.standY(HuntRoute.hitIndex(h))) < 1.5
        ) {
            onBridge = true
            if (--creak <= 0) {
                creak = 30 + p.random.nextInt(50)
                val ev = if (p.random.nextBoolean()) SoundEvents.BLOCK_LADDER_STEP else SoundEvents.BLOCK_SCAFFOLDING_STEP
                HuntCine.playAt(ev, p.entityPos.add(0.0, -0.5, 0.0), 0.7f, 0.45f + p.random.nextFloat() * 0.2f)
                if (p.random.nextInt(4) == 0) HuntCine.playAt(SoundEvents.ITEM_LEAD_TIED, p.entityPos, 0.5f, 0.6f)
            }
        }
        localSway += ((if (onBridge) 1f else 0f) - localSway) * 0.08f
    }

    @JvmStatic
    fun localRoll(tickDelta: Float): Float {
        if (localSway < 0.01f) return 0f
        val t = time + tickDelta
        return localSway * (1.1f * sin(t * 0.045f) + 0.4f * sin(t * 0.13f)) * (0.6f + SnowStorm.strength.coerceIn(0f, 2f) * 0.3f)
    }

    private val weakCache = HashMap<Int, DoubleArray>()

    private val planks: BlockState get() = Blocks.SPRUCE_PLANKS.defaultState
    private val planksDark: BlockState get() = Blocks.DARK_OAK_PLANKS.defaultState
    private val rope: BlockState get() = Blocks.BROWN_WOOL.defaultState

    fun render(d: VfxDraw) {
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        if (world.registryKey != ArenaDimension.WORLD) return
        val cam = d.cam
        val o = origin(cam)
        val t = time + d.tickDelta.toDouble()
        val others = world.players
        for (span in HuntRoute.ropeSpans) {
            val mid = HuntRoute.indexOfArc((span.a0 + span.a1) / 2)
            val mx = o.x + HuntRoute.x(mid)
            val mz = o.z + HuntRoute.z(mid)
            if ((mx - cam.x) * (mx - cam.x) + (mz - cam.z) * (mz - cam.z) > 190.0 * 190.0) continue
            drops[span.id]?.let { dr ->
                renderDrop(dr, d)
                continue
            }
            val weak = weakCache.getOrPut(span.id) { HuntRoute.weakPlanks(span) }
            // Lasten (Spieler auf der Brücke) als Bogenlängen
            val loads = ArrayList<Double>()
            for (pl in others) {
                val h = HuntRoute.bridgeEntry(floor(pl.x).toInt() - o.x, floor(pl.z).toInt() - o.z)
                if (h < 0) continue
                val j = HuntRoute.hitIndex(h)
                if (j !in span.i0..span.i1 || abs(pl.y - o.y - HuntRoute.standY(j)) > 2.0) continue
                loads += HuntRoute.arc(j) - span.a0
            }
            val n = (span.length / PLANK).toInt()
            val sim = sims[span.id]?.takeIf { it.n == n }
            val present = BooleanArray(n + 1)
            val pts = arrayOfNulls<Vec3d>(n + 1)
            val sides = arrayOfNulls<Vec3d>(n + 1)
            for (k in 0..n) {
                val s = k * PLANK
                val i = HuntRoute.indexOfArc(span.a0 + s)
                val deckY = o.y + HuntRoute.standY(i)
                var w = 0.0
                for (l in loads) w = maxOf(w, exp(-((s - l) / 5.0).let { it * it }))
                val belly = belly(span, s)
                // Unter einem Spieler liegt das Deck genau auf der Kollision; das Schwingen klingt dort aus.
                var y = deckY + LIFT * belly * (1 - w) + (sim?.dipAt(k, d.tickDelta) ?: 0.0) * (1 - 0.8 * w)
                val (tx, tz) = HuntRoute.tangent(i)
                val side = Vec3d(-tz, 0.0, tx)
                var lat = (sim?.latAt(k, d.tickDelta) ?: 0.0) * (1 - 0.85 * w)
                for (sh in shakes) {
                    val dx = o.x + HuntRoute.x(i) - sh.at.x
                    val dz = o.z + HuntRoute.z(i) - sh.at.z
                    if (dx * dx + dz * dz < sh.r * sh.r * 4) {
                        y += 0.05 * sin(t * 2.3 + k)
                        lat += 0.04 * sin(t * 3.1 + k * 0.7)
                    }
                }
                pts[k] = Vec3d(o.x + HuntRoute.x(i) + side.x * lat, y, o.z + HuntRoute.z(i) + side.z * lat)
                sides[k] = side
                val bp = BlockPos(o.x + floor(HuntRoute.x(i)).toInt(), o.y + HuntRoute.deckBlock(i), o.z + floor(HuntRoute.z(i)).toInt())
                present[k] = k < 6 || k > n - 6 || PortalBlocks.isDeck(world.getBlockState(bp))
            }
            // Lücken: wie lang ist die Lücke um k herum?
            val gapLen = IntArray(n + 1)
            run {
                var k = 0
                while (k <= n) {
                    if (present[k]) { k++; continue }
                    var e = k
                    while (e <= n && !present[e]) e++
                    for (q in k until e) gapLen[q] = e - k
                    k = e
                }
            }
            // Nur wenn der Großteil der Brücke eingestürzt ist (ohne laufenden Drop), gelten die Seile als gerissen.
            val missingMid = (6..(n - 6).coerceAtLeast(6)).count { !present[it] }
            val bridgeCollapsed = n > 14 && missingMid > (n - 12) * 0.65
            for (k in 0..n) {
                val p = pts[k] ?: continue
                if (p.squaredDistanceTo(cam) > 110.0 * 110.0) continue
                val side = sides[k]!!
                val next = pts[minOf(k + 1, n)]!!
                val prev = pts[maxOf(k - 1, 0)]!!
                val along = next.subtract(prev).let { if (it.lengthSquared() < 1e-6) Vec3d(side.z, 0.0, -side.x) else it.normalize() }
                // Die Brücke neigt sich beim Pendeln leicht in die Schwingung.
                val bank = (p.subtract(Vec3d(o.x + HuntRoute.x(HuntRoute.indexOfArc(span.a0 + k * PLANK)), p.y, o.z + HuntRoute.z(HuntRoute.indexOfArc(span.a0 + k * PLANK)))).dotProduct(side) * 0.16).coerceIn(-0.12, 0.12)
                if (present[k]) {
                    val jitter = ((k * 7919) % 13) / 13.0 - 0.5
                    val tilt = (if (k % 5 == 0) 0.05 else 0.0) + jitter * 0.03 + bank
                    val cracked = weak.any { abs(it - k * PLANK) < 0.75 }
                    if (cracked) {
                        // Morsch: dunkel, gesplittert, in zwei ungleiche Stücke gebrochen.
                        val off = 0.55 + jitter * 0.3
                        box(d, planksDark, p.add(side.multiply(off + 0.75)).add(0.0, -0.1, 0.0), along, side, 0.4, 0.09, 1.55, tilt + 0.14)
                        box(d, planksDark, p.add(side.multiply(off - 1.25)).add(0.0, -0.12, 0.0), along, side, 0.4, 0.09, 1.1, tilt - 0.16)
                    } else {
                        box(d, if (k % 7 == 3) planksDark else planks, p.add(0.0, -0.06, 0.0), along, side, 0.44, 0.1, 3.3 + jitter * 0.15, tilt)
                    }
                } else if (!bridgeCollapsed && gapLen[k] < 12 && (k > 0 && present[k - 1] || k < n && present[k + 1])) {
                    // Rand verlorener Bretter: gesplitterte Brettstummel hängen noch an den intakten Tragseilen.
                    for (sgn in intArrayOf(-1, 1)) box(d, planksDark, p.add(side.multiply(sgn * 1.38)).add(0.0, -0.12, 0.0), along, side, 0.38, 0.08, 0.58, sgn * 0.25)
                }
                // Verlorene Bretter trennen die Tragseile nicht: Fußseile, Handläufe und Hänger bleiben durchgehend straff.
                val intactRope = !bridgeCollapsed || present[k]
                if (k < n) {
                    val q = pts[k + 1]!!
                    val qs = sides[k + 1]!!
                    val nextIntact = !bridgeCollapsed || present[minOf(k + 1, n)]
                    if (intactRope && nextIntact) {
                        for (sgn in intArrayOf(-1, 1)) {
                            val a = p.add(side.multiply(sgn * 1.72)).add(0.0, -0.05, 0.0)
                            val b = q.add(qs.multiply(sgn * 1.72)).add(0.0, -0.05, 0.0)
                            seg(d, rope, a, b, 0.075)
                            val ha = p.add(side.multiply(sgn * 1.9)).add(0.0, 1.05 + 0.08 * (4 * (k * PLANK / span.length) * (1 - k * PLANK / span.length)), 0.0)
                            val hb = q.add(qs.multiply(sgn * 1.9)).add(0.0, 1.05 + 0.08 * (4 * ((k + 1) * PLANK / span.length) * (1 - (k + 1) * PLANK / span.length)), 0.0)
                            seg(d, rope, ha, hb, 0.085)
                            if (k % 2 == 0) seg(d, rope, a, ha, 0.045)
                        }
                    } else if (intactRope != nextIntact) {
                        // Nur bei ganz eingestürzter Brücke hängen die gerissenen Seile am Kopf herab.
                        val edge = if (intactRope) p else q
                        val es = if (intactRope) side else qs
                        for (sgn in intArrayOf(-1, 1)) {
                            val top = edge.add(es.multiply(sgn * 1.8)).add(0.0, 0.5, 0.0)
                            val sw = 0.3 * sin(t * 0.05 + sgn)
                            seg(d, rope, top, top.add(along.multiply(sw)).add(0.0, -5.5, 0.0), 0.07)
                        }
                    }
                }
            }
            // Ankerpfosten an beiden Köpfen (Fichtenstämme stehen als Blöcke, hier nur die Seilknoten)
            for (end in intArrayOf(0, n)) {
                val p = pts[end] ?: continue
                val side = sides[end]!!
                for (sgn in intArrayOf(-1, 1)) seg(d, rope, p.add(side.multiply(sgn * 1.9)).add(0.0, 1.05, 0.0), p.add(side.multiply(sgn * 2.1)).add(0.0, 2.6, 0.0), 0.1)
            }
            // Während der Jagd: die Seile am fernen Kopf leuchten, hier kann man sie kappen.
            if (HuntCine.active && !bridgeCollapsed) {
                val p = pts[n]!!
                val side = sides[n]!!
                val pulse = (0.6 + 0.4 * sin(t * 0.25)).toFloat()
                for (sgn in intArrayOf(-1, 1)) {
                    val knot = p.add(side.multiply(sgn * 2.0)).add(0.0, 1.9, 0.0)
                    d.billboard(dev.henny.hha.client.vfx.VfxLayers.GLOW_ADD, knot, 1.5, dev.henny.hha.client.vfx.withAlpha(0xFFFFC766.toInt(), 0.5f * pulse), 0.0)
                    d.billboard(dev.henny.hha.client.vfx.VfxLayers.FLARE_ADD, knot, 0.55 + 0.2 * pulse, dev.henny.hha.client.vfx.withAlpha(0xFFFFF1CC.toInt(), 0.9f * pulse), t * 0.03)
                    d.band(dev.henny.hha.client.vfx.VfxLayers.GLOW_ADD, knot, knot.add(0.0, 3.2, 0.0), d.camRight, 0.16, dev.henny.hha.client.vfx.withAlpha(0xFFFFC766.toInt(), 0.55f * pulse), dev.henny.hha.client.vfx.withAlpha(0xFFFFC766.toInt(), 0f))
                }
            }
        }
    }

    private val ms = MatrixStack()

    /** Quader aus einem Blockmodell: Mitte [c], Achsen [along] (Länge [lx]), oben (Dicke [ly]), [side] (Breite [lz]). */
    private fun box(d: VfxDraw, state: BlockState, c: Vec3d, along: Vec3d, side: Vec3d, lx: Double, ly: Double, lz: Double, roll: Double) {
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        ms.push()
        ms.translate(c.x - d.cam.x, c.y - d.cam.y, c.z - d.cam.z)
        // Feste Basis: x = Laufrichtung, z = Querachse (senkrecht dazu), y = oben. Eindeutig für jede Richtung.
        val x = Vector3f(along.x.toFloat(), along.y.toFloat(), along.z.toFloat()).normalize()
        val z = Vector3f(side.x.toFloat(), side.y.toFloat(), side.z.toFloat())
        z.sub(Vector3f(x).mul(z.dot(x)))
        if (z.lengthSquared() < 1e-5f) z.set(-x.z, 0f, x.x)
        z.normalize()
        val y = Vector3f(z).cross(x).normalize()
        val q = Quaternionf().setFromNormalized(Matrix3f(x, y, z))
        q.rotateX(roll.toFloat())
        ms.multiply(q)
        ms.scale(lx.toFloat(), ly.toFloat(), lz.toFloat())
        ms.translate(-0.5f, -0.5f, -0.5f)
        val light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(c.x, c.y + 0.5, c.z))
        mc.blockRenderManager.renderBlockAsEntity(state, ms, d.consumers, light, OverlayTexture.DEFAULT_UV)
        ms.pop()
    }

    /**
     * Seilstück von [a] nach [b] mit Dicke [th]: ein zur Kamera gedrehtes Band (Kettentextur, hanfbraun) statt eines
     * Blockmodells – Seile sind die meisten Teile der Brücke.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun seg(d: VfxDraw, state: BlockState, a: Vec3d, b: Vec3d, th: Double) {
        val v = b.subtract(a)
        if (v.lengthSquared() < 1e-8) return
        var w = v.crossProduct(d.cam.subtract(a))
        if (w.lengthSquared() < 1e-8) w = Vec3d(0.0, 1.0, 0.0)
        val col = dev.henny.hha.client.vfx.withAlpha(0xFF6B4E33.toInt(), 0.95f)
        d.band(dev.henny.hha.client.vfx.VfxLayers.CHAIN_ALPHA, a, b, w.normalize(), th * 1.6, col, col, uScale = 0.25f)
    }
}

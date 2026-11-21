package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.ArenaLayout
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
        localSway = 0f
        onBridge = false
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

    /** Seitliches Schwingen (Blöcke) an Bogenlänge u (0..1) zur Zeit t. */
    private fun sway(span: HuntRoute.Span, u: Double, t: Double): Double {
        val belly = belly(span, u * span.length)
        return belly * wind() * (sin(t * 0.045 + span.id * 1.7) + 0.35 * sin(t * 0.13 + u * 6.0))
    }

    private fun bob(span: HuntRoute.Span, u: Double, t: Double): Double {
        val belly = belly(span, u * span.length)
        return belly * 0.08 * sin(t * 0.07 + span.id + u * 3.0)
    }

    fun tick() {
        time++
        shakes.removeIf { --it.left <= 0 }
        val mc = MinecraftClient.getInstance()
        val p = mc.player ?: return
        val world = mc.world ?: return
        onBridge = false
        if (world.registryKey != ArenaDimension.WORLD) return
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
            val present = BooleanArray(n + 1)
            val pts = arrayOfNulls<Vec3d>(n + 1)
            val sides = arrayOfNulls<Vec3d>(n + 1)
            for (k in 0..n) {
                val s = k * PLANK
                val u = s / span.length
                val i = HuntRoute.indexOfArc(span.a0 + s)
                val deckY = o.y + HuntRoute.standY(i)
                var w = 0.0
                for (l in loads) w = maxOf(w, exp(-((s - l) / 5.0).let { it * it }))
                val belly = belly(span, s)
                var y = deckY + LIFT * belly * (1 - w) + bob(span, u, t) * (1 - w)
                val (tx, tz) = HuntRoute.tangent(i)
                val side = Vec3d(-tz, 0.0, tx)
                var lat = sway(span, u, t) * (1 - 0.85 * w)
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
            for (k in 0..n) {
                val p = pts[k] ?: continue
                if (p.squaredDistanceTo(cam) > 110.0 * 110.0) continue
                val side = sides[k]!!
                val next = pts[minOf(k + 1, n)]!!
                val prev = pts[maxOf(k - 1, 0)]!!
                val along = next.subtract(prev).let { if (it.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
                if (present[k]) {
                    val jitter = ((k * 7919) % 13) / 13.0 - 0.5
                    val tilt = (if (k % 5 == 0) 0.06 else 0.0) + jitter * 0.03
                    box(d, if (k % 7 == 3) planksDark else planks, p.add(0.0, -0.06, 0.0), along, side, 0.44, 0.1, 3.3 + jitter * 0.15, tilt)
                }
                // Seile nur, wo die Brücke nicht auf langer Strecke weg ist
                val intactRope = gapLen[k] < 4
                if (k < n) {
                    val q = pts[k + 1]!!
                    val qs = sides[k + 1]!!
                    if (intactRope && gapLen[minOf(k + 1, n)] < 4) {
                        for (sgn in intArrayOf(-1, 1)) {
                            val a = p.add(side.multiply(sgn * 1.72)).add(0.0, -0.05, 0.0)
                            val b = q.add(qs.multiply(sgn * 1.72)).add(0.0, -0.05, 0.0)
                            seg(d, rope, a, b, 0.07)
                            val ha = p.add(side.multiply(sgn * 1.9)).add(0.0, 1.05 + 0.08 * (4 * (k * PLANK / span.length) * (1 - k * PLANK / span.length)), 0.0)
                            val hb = q.add(qs.multiply(sgn * 1.9)).add(0.0, 1.05 + 0.08 * (4 * ((k + 1) * PLANK / span.length) * (1 - (k + 1) * PLANK / span.length)), 0.0)
                            seg(d, rope, ha, hb, 0.085)
                            if (k % 2 == 0) seg(d, rope, a, ha, 0.045)
                        }
                    } else if (intactRope != (gapLen[minOf(k + 1, n)] < 4)) {
                        // Rand einer Einsturzlücke: gerissene Seile hängen herab
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
        }
    }

    private val ms = MatrixStack()

    /** Quader aus einem Blockmodell: Mitte [c], Achsen [along] (Länge [lx]), oben (Dicke [ly]), [side] (Breite [lz]). */
    private fun box(d: VfxDraw, state: BlockState, c: Vec3d, along: Vec3d, side: Vec3d, lx: Double, ly: Double, lz: Double, roll: Double) {
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        ms.push()
        ms.translate(c.x - d.cam.x, c.y - d.cam.y, c.z - d.cam.z)
        val q = Quaternionf().rotationTo(Vector3f(1f, 0f, 0f), Vector3f(along.x.toFloat(), along.y.toFloat(), along.z.toFloat()))
        q.rotateX(roll.toFloat())
        ms.multiply(q)
        ms.scale(lx.toFloat(), ly.toFloat(), lz.toFloat())
        ms.translate(-0.5f, -0.5f, -0.5f)
        val light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(c.x, c.y + 0.5, c.z))
        mc.blockRenderManager.renderBlockAsEntity(state, ms, d.consumers, light, OverlayTexture.DEFAULT_UV)
        ms.pop()
        @Suppress("UNUSED_VARIABLE") val s = side
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

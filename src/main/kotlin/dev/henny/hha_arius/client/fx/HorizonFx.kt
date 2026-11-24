package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * White Horizon signature effect:
 * 1) Anticipation (`released = false`): authored frost-white & ice-blue light ribbons pull inward from a wide
 *    horizontal horizon arc into Arius' blade, while two opposing horizon edges compress onto the cut plane.
 * 2) Release (`released = true`): one massive clean horizontal frost slash with both a broad swept horizontal
 *    crescent and a sculpted vertical/lenticular rim profile (so it reads unmistakably in first person, third person,
 *    on white snow, and in a blizzard), paired with a brief frost flash & cool deep-navy world-tint ([HorizonPulse]).
 * 3) Aftermath: crisp geometric frost shards and thin wake filaments drift and dissolve cleanly without particle spam.
 *
 * Strict palette: frost white, ice blue, deep navy only. All layers fog-free ([AriusLayers]).
 */
class HorizonFx(
    private val origin: Vec3d, direction: Vec3d, private val width: Double,
    private val released: Boolean, private val owner: Entity?, life: Int,
    private val temple: Boolean = false,
) : AriusFx(life) {
    private val forward = Vec3d(direction.x, 0.0, direction.z).let {
        if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize()
    }
    private val across = Vec3d(-forward.z, 0.0, forward.x)

    private class DriftShard(
        val u: Double, val along: Double, val yOff: Double,
        val driftOut: Double, val driftUp: Double, val size: Double, val spin: Double, val dark: Boolean,
    )

    private val shards: List<DriftShard> = if (released) {
        val r = Random(origin.hashCode().toLong() xor (width.toLong() * 31L))
        List(26) { i ->
            val u = (i + 0.25 + r.nextDouble() * 0.5) / 26.0
            DriftShard(
                u = u,
                along = 0.08 + r.nextDouble() * 0.88,
                yOff = (r.nextDouble() - 0.5) * 0.85,
                driftOut = 0.45 + r.nextDouble() * 1.1,
                driftUp = 0.22 + r.nextDouble() * 0.72,
                size = 0.055 + r.nextDouble() * 0.065,
                spin = (if (i % 2 == 0) 1.0 else -1.0) * (0.12 + r.nextDouble() * 0.22),
                dark = i % 3 == 0,
            )
        }
    } else emptyList()

    init {
        if (released) {
            val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
            val dist = cam.distanceTo(origin)
            if (dist < 84.0) {
                val near = (1.0 - (dist / 84.0).coerceIn(0.0, 1.0)).toFloat()
                val strength = (0.45f + 0.55f * near) * if (temple) 1.0f else 0.82f
                HorizonPulse.trigger(strength)
            }
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        if (fx <= 0.01f) return
        if (!released) renderAnticipation(d, t, fx)
        else renderSlash(d, t, fx)
    }

    // Puffer immer erst direkt vor dem Zeichnen holen: Das Holen eines zweiten Layers schließt den vorigen Puffer, und
    // weiteres Schreiben darauf brach mit „Not building!“ ab (Absturz beim Aufladen des Weißen Horizonts).
    private fun renderAnticipation(d: VfxDraw, t: Float, fx: Float) {
        if (owner?.isRemoved == true) return
        val progress = (t / life.coerceAtLeast(1)).coerceIn(0f, 1f)
        val build = progress * progress
        val intro = (t / 4f).coerceIn(0f, 1f)
        val aScale = intro * fx

        val anchor = BladeAnchors.of(owner)
        val base = anchor?.base ?: owner?.getLerpedPos(d.tickDelta)?.add(0.0, 1.25, 0.0)?.add(across.multiply(0.45)) ?: origin
        val tip = anchor?.tip ?: base.add(across.multiply(1.05)).add(0.0, 0.55, 0.0)
        val bladeMid = base.lerp(tip, 0.55)
        val bladeAxis = tip.subtract(base)
        val viewSide = d.camForward.crossProduct(bladeAxis).let {
            if (it.lengthSquared() < 1e-5) d.camRight else it.normalize()
        }

        // 1) Converging frost-light ribbons drawing inward from the horizon into the blade.
        val pullRadius = (width * 0.95).coerceIn(4.8, 10.5) * (1.0 - 0.58 * build)
        for (i in 0 until 10) {
            val sideSign = if (i % 2 == 0) 1.0 else -1.0
            val band = i / 2
            val phase = ((t * 0.065f + band * 0.2f + (if (sideSign > 0) 0f else 0.1f)) % 1f)
            val tailP = (phase - 0.34f).coerceAtLeast(0f)
            val headP = phase
            val yArch = (band - 2) * 0.34 * (1.0 - 0.65 * build)
            val zBow = (0.35 + band * 0.16) * (1.0 - 0.4 * build)
            val startPt = bladeMid
                .add(across.multiply(sideSign * pullRadius * (0.72 + band * 0.09)))
                .add(forward.multiply(zBow))
                .add(0.0, yArch, 0.0)
            val ctrlPt = bladeMid
                .add(across.multiply(sideSign * pullRadius * 0.35))
                .add(forward.multiply(-zBow * 0.45))
                .add(0.0, yArch * 0.35, 0.0)
            val targetPt = base.lerp(tip, 0.25 + 0.65 * ((i * 0.37) % 1.0))

            fun bezier(s: Double): Vec3d {
                val inv = 1.0 - s
                return startPt.multiply(inv * inv).add(ctrlPt.multiply(2.0 * inv * s)).add(targetPt.multiply(s * s))
            }

            val pts = ArrayList<Vec3d>(7)
            for (k in 0..6) {
                val s = tailP + (headP - tailP) * (k / 6.0)
                pts.add(bezier(s))
            }
            val ribA = aScale * (0.35f + 0.65f * progress) * sin(phase * PI).toFloat().coerceIn(0f, 1f)
            if (ribA > 0.02f) {
                d.ribbon(
                    AriusLayers.INK, pts, { q -> 0.09 + 0.13 * q * (0.5 + 0.5 * build) },
                    { q -> withAlpha(NAVY_DARK, ribA * 0.72f * q) }, uScale = 0f, uOffset = 0.5f,
                )
                d.ribbon(
                    AriusLayers.VFX_GLOW, pts, { q -> 0.055 + 0.10 * q * (0.5 + 0.5 * build) },
                    { q -> withAlpha(if (q > 0.65f) FROST_WHITE else ICE_BLUE, ribA * (0.45f + 0.55f * q)) },
                    uScale = 0f, uOffset = 0.5f,
                )
            }
        }

        // 2) Compressing horizontal horizon tension arcs (tapered smoothly to razor tips, never blunt bars).
        val planeCenter = (owner?.getLerpedPos(d.tickDelta)?.add(0.0, 1.15, 0.0) ?: origin).add(forward.multiply(0.35))
        val span = (width * (0.38 + 0.62 * progress)).coerceAtLeast(3.4)
        val gap = 1.05 * (1.0 - build) + 0.06
        val bandA = aScale * (0.28f + 0.68f * progress)
        for (sg in intArrayOf(-1, 1)) {
            val yOff = sg * gap
            fun drawTapered(vc: net.minecraft.client.render.VertexConsumer, halfTh: Double, col: Int) {
                with(d) {
                    for (i in 0 until 24) {
                        val u0 = i / 24.0
                        val u1 = (i + 1) / 24.0
                        val s0 = sin(u0 * PI)
                        val s1 = sin(u1 * PI)
                        val p0 = planeCenter.add(across.multiply((2.0 * u0 - 1.0) * span)).add(forward.multiply(s0 * 0.45)).add(0.0, yOff, 0.0)
                        val p1 = planeCenter.add(across.multiply((2.0 * u1 - 1.0) * span)).add(forward.multiply(s1 * 0.45)).add(0.0, yOff, 0.0)
                        val c0 = withAlpha(col, ((col ushr 24) / 255f) * s0.toFloat())
                        val c1 = withAlpha(col, ((col ushr 24) / 255f) * s1.toFloat())
                        val h0 = halfTh * s0
                        val h1 = halfTh * s1
                        vc.put(p0.add(0.0, -h0, 0.0), 0f, 0f, c0)
                        vc.put(p0.add(0.0, h0, 0.0), 0f, 1f, c0)
                        vc.put(p1.add(0.0, h1, 0.0), 1f, 1f, c1)
                        vc.put(p1.add(0.0, -h1, 0.0), 1f, 0f, c1)
                    }
                }
            }
            drawTapered(d.buffer(AriusLayers.INK), 0.11 + 0.06 * build, withAlpha(NAVY_DARK, bandA * 0.80f))
            drawTapered(d.buffer(AriusLayers.VFX_GLOW), 0.055 + 0.05 * build, withAlpha(FROST_WHITE, bandA * 0.92f))
        }

        // 3) Blade core charge + intensifying horizontal gleam at the edge.
        val edgeA = aScale * (0.55f + 0.45f * progress)
        d.band(AriusLayers.INK, base, tip, viewSide, 0.16 + 0.14 * build, withAlpha(NAVY_DARK, edgeA * 0.88f), withAlpha(NAVY_MID, edgeA * 0.88f))
        d.band(AriusLayers.VFX_GLOW, base, tip, viewSide, 0.11 + 0.13 * build, withAlpha(ICE_BLUE, edgeA * 0.9f), withAlpha(FROST_WHITE, edgeA))
        d.band(AriusLayers.VFX_GLOW, base, tip, viewSide, 0.05 + 0.06 * build, withAlpha(PURE_WHITE, edgeA), withAlpha(PURE_WHITE, edgeA))

        val gleamSpan = 0.8 + 4.8 * build
        val g0 = bladeMid.subtract(across.multiply(gleamSpan))
        val g1 = bladeMid.add(across.multiply(gleamSpan))
        d.band(AriusLayers.INK, g0, g1, UP, 0.14 * (0.4 + 0.6 * build), withAlpha(NAVY_DARK, 0f), withAlpha(NAVY_DARK, edgeA * 0.7f))
        d.band(AriusLayers.VFX_GLOW, g0, g1, UP, 0.08 * (0.4 + 0.6 * build), withAlpha(ICE_BLUE, 0f), withAlpha(PURE_WHITE, edgeA * 0.95f))
        d.billboard(AriusLayers.VFX_FLARE, bladeMid, 0.75 + 2.4 * build, withAlpha(FROST_WHITE, edgeA * (0.5f + 0.5f * build)))
        d.billboard(AriusLayers.VFX_GLOW, bladeMid, 1.3 + 3.2 * build, withAlpha(ICE_BLUE, edgeA * 0.45f))
    }

    private fun renderSlash(d: VfxDraw, t: Float, fx: Float) {
        val progress = (t / life.coerceAtLeast(1)).coerceIn(0f, 1f)
        // Opens instantaneously across the full view on tick 0 so peak brilliance hits on the exact release frame.
        val open = ((t + 0.75f) / 1.9f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }

        val maxTravel = if (temple) 15.0 else 7.0
        val travelFrac = 1.0 - kotlin.math.exp(-t * (if (temple) 0.16 else 0.22))
        val travel = maxTravel * travelFrac
        val center = origin.add(forward.multiply(travel))

        // Peak brilliance across ticks 0..2, crisp smooth decay across ticks 2..14 while slivers linger.
        val peakBoost = (1f - (t / 3.8f).coerceIn(0f, 1f)).let { it * it }
        val bladeFade = (1f - ((t - 2.0f) / 14.0f).coerceIn(0f, 1f)).let { it * it } * fx
        val span = width * (0.86 + 0.28 * open)
        val depth = (if (temple) 2.4 + 1.3 * open else 1.7 + 1.1 * open) * (0.85 + 0.25 * (1f - progress))
        val bow = (if (temple) 2.1 else 1.45) * (0.7 + 0.3 * open)

        // Needle-sharp pointed taper at both tips: sin(u * PI)^1.65 reaches 0 smoothly with zero boxy ends.
        fun tipTaper(u: Double): Double {
            val s = sin(u * PI).coerceAtLeast(0.0)
            return s * sqrt(s)
        }

        fun arcPoint(u: Double, back: Double, yOff: Double = 0.0): Vec3d {
            val arch = sin(u * PI)
            val taper = tipTaper(u)
            return center
                .add(across.multiply((2.0 * u - 1.0) * span))
                .add(forward.multiply(arch * bow - back * depth * (0.12 + 0.88 * taper)))
                .add(0.0, yOff, 0.0)
        }

        if (bladeFade > 0.015f) {

            fun hStrip(
                vc: net.minecraft.client.render.VertexConsumer,
                from: Double, to: Double,
                outerCol: Int, innerCol: Int,
                yShift: Double = 0.0,
            ) {
                val aOut = (outerCol ushr 24) / 255f
                val aIn = (innerCol ushr 24) / 255f
                with(d) {
                    for (i in 0 until 44) {
                        val u0 = i / 44.0
                        val u1 = (i + 1) / 44.0
                        val e0 = sin(u0 * PI).toFloat()
                        val e1 = sin(u1 * PI).toFloat()
                        val cO0 = withAlpha(outerCol, aOut * e0)
                        val cI0 = withAlpha(innerCol, aIn * e0)
                        val cI1 = withAlpha(innerCol, aIn * e1)
                        val cO1 = withAlpha(outerCol, aOut * e1)
                        vc.put(arcPoint(u0, from, yShift), 0f, 0f, cO0)
                        vc.put(arcPoint(u0, to, yShift), 0f, 1f, cI0)
                        vc.put(arcPoint(u1, to, yShift), 1f, 1f, cI1)
                        vc.put(arcPoint(u1, from, yShift), 1f, 0f, cO1)
                    }
                }
            }

            // 1) Horizontal swept crescent: ONLY a thin deep-navy contrast edge right along the leading core,
            //    followed by a brilliant near-white core and soft luminous ice-blue trailing falloff.
            hStrip(d.buffer(AriusLayers.INK), 0.0, 0.14, withAlpha(NAVY_DARK, bladeFade * 0.58f), withAlpha(NAVY_MID, 0f), -0.02)
            // Soft ice-blue falloff wake
            hStrip(d.buffer(AriusLayers.VFX_GLOW), 0.04, 0.34, withAlpha(ICE_LIGHT, bladeFade * 0.88f), withAlpha(ICE_BLUE, bladeFade * 0.55f))
            hStrip(d.buffer(AriusLayers.VFX_GLOW), 0.34, 0.95, withAlpha(ICE_BLUE, bladeFade * 0.55f), withAlpha(ICE_BLUE, 0f))
            // Brilliant additive near-white leading blade core
            hStrip(d.buffer(AriusLayers.VFX_ADD), 0.0, 0.16, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * (0.75f + 0.25f * peakBoost)))
            hStrip(d.buffer(AriusLayers.VFX_ADD), 0.16, 0.52, withAlpha(ICE_LIGHT, bladeFade * (0.55f + 0.35f * peakBoost)), withAlpha(ICE_BLUE, 0f))

            // 2) Razor-sharp, needle-tapered vertical slash profile across the entire view (1st person, 3rd person, snow/storm).
            //    Height narrows gracefully after peak ticks so ticks 0..2 are huge & blinding and ticks 3..10 stay razor-crisp.
            val coreH = (if (temple) 0.26 else 0.22) * (0.55 + 0.45 * (1f - progress * 0.6f)) + 0.10 * peakBoost
            val falloffH = coreH * 2.55
            val contrastH = coreH * 1.32

            fun vRazor(vc: net.minecraft.client.render.VertexConsumer, back: Double, h0: Double, h1: Double, c0: Int, c1: Int) {
                val a0 = (c0 ushr 24) / 255f
                val a1 = (c1 ushr 24) / 255f
                with(d) {
                    for (i in 0 until 44) {
                        val u0 = i / 44.0
                        val u1 = (i + 1) / 44.0
                        val t0 = tipTaper(u0)
                        val t1 = tipTaper(u1)
                        val e0 = sin(u0 * PI).toFloat()
                        val e1 = sin(u1 * PI).toFloat()
                        val col0 = withAlpha(c0, a0 * e0)
                        val col1 = withAlpha(c1, a1 * e0)
                        val col1b = withAlpha(c1, a1 * e1)
                        val col0b = withAlpha(c0, a0 * e1)
                        vc.put(arcPoint(u0, back, h0 * t0), 0f, 0f, col0)
                        vc.put(arcPoint(u0, back, h1 * t0), 0f, 1f, col1)
                        vc.put(arcPoint(u1, back, h1 * t1), 1f, 1f, col1b)
                        vc.put(arcPoint(u1, back, h0 * t1), 1f, 0f, col0b)
                    }
                }
            }

            // Thin deep-navy hairline contour hugging the bright core (readable on pure white snow, NEVER a wide dark band).
            vRazor(d.buffer(AriusLayers.INK), 0.07, coreH * 0.72, contrastH * 1.08, withAlpha(NAVY_DARK, bladeFade * 0.84f), withAlpha(NAVY_DARK, 0f))
            vRazor(d.buffer(AriusLayers.INK), 0.07, -coreH * 0.72, -contrastH * 1.08, withAlpha(NAVY_DARK, bladeFade * 0.84f), withAlpha(NAVY_DARK, 0f))

            // Soft ice-blue falloff halo (alpha-glow in rich ice blue for white-snow readability + additive light bloom)
            vRazor(d.buffer(AriusLayers.VFX_GLOW), 0.055, coreH * 0.45, falloffH, withAlpha(ICE_BLUE, bladeFade * 0.82f), withAlpha(ICE_BLUE, 0f))
            vRazor(d.buffer(AriusLayers.VFX_GLOW), 0.055, -coreH * 0.45, -falloffH, withAlpha(ICE_BLUE, bladeFade * 0.82f), withAlpha(ICE_BLUE, 0f))
            vRazor(d.buffer(AriusLayers.VFX_GLOW), 0.045, 0.0, falloffH * 0.78, withAlpha(ICE_LIGHT, bladeFade * 0.78f), withAlpha(ICE_BLUE, 0f))
            vRazor(d.buffer(AriusLayers.VFX_GLOW), 0.045, 0.0, -falloffH * 0.78, withAlpha(ICE_LIGHT, bladeFade * 0.78f), withAlpha(ICE_BLUE, 0f))
            vRazor(d.buffer(AriusLayers.VFX_ADD), 0.035, 0.0, falloffH * 0.80, withAlpha(ICE_LIGHT, bladeFade * (0.55f + 0.35f * peakBoost)), withAlpha(ICE_BLUE, 0f))
            vRazor(d.buffer(AriusLayers.VFX_ADD), 0.035, 0.0, -falloffH * 0.80, withAlpha(ICE_LIGHT, bladeFade * (0.55f + 0.35f * peakBoost)), withAlpha(ICE_BLUE, 0f))

            // Blinding near-white razor core (double-layered additive + glow so it is the brightest thing on screen!)
            vRazor(d.buffer(AriusLayers.VFX_GLOW), 0.02, 0.0, coreH, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, 0f))
            vRazor(d.buffer(AriusLayers.VFX_GLOW), 0.02, 0.0, -coreH, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, 0f))
            vRazor(d.buffer(AriusLayers.VFX_ADD), 0.01, 0.0, coreH * 0.92, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * 0.25f))
            vRazor(d.buffer(AriusLayers.VFX_ADD), 0.01, 0.0, -coreH * 0.92, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * 0.25f))
            // Peak central incandescence on ticks 0..4
            if (peakBoost > 0.02f) {
                vRazor(d.buffer(AriusLayers.VFX_ADD), 0.0, 0.0, coreH * 0.52, withAlpha(PURE_WHITE, peakBoost * fx), withAlpha(PURE_WHITE, 0f))
                vRazor(d.buffer(AriusLayers.VFX_ADD), 0.0, 0.0, -coreH * 0.52, withAlpha(PURE_WHITE, peakBoost * fx), withAlpha(PURE_WHITE, 0f))
            }
        }

        // 3) Peak razor horizon flare across the cleaved axis (ticks 0..5): pure white-ice brilliance at the center.
        if (t < 5.5f) {
            val flareF = (1f - t / 5.5f).let { it * it } * fx
            val flareCenter = arcPoint(0.5, 0.02, 0.0)
            d.billboard(AriusLayers.VFX_ADD_FLARE, flareCenter, (4.8 + 3.2 * open) * flareF, withAlpha(PURE_WHITE, flareF))
            d.billboard(AriusLayers.VFX_GLOW, flareCenter, (3.6 + 2.4 * open) * flareF, withAlpha(ICE_LIGHT, flareF * 0.72f))
        }

        // 4) Crisp dissipating frost aftermath along the swept corridor (ticks 3..life):
        //    bright ice-white slivers and delicate tapered frost-wake filaments (zero dark rectangles or particle spam).
        val wakeIn = ((t - 2.5f) / 3.5f).coerceIn(0f, 1f)
        val wakeOut = (1f - ((t - 8f) / (life - 8f).coerceAtLeast(1f)).coerceIn(0f, 1f))
        val wakeA = wakeIn * wakeOut * wakeOut * fx
        if (wakeA > 0.015f) {
            val corridorSpan = maxTravel * travelFrac
            fun wakePoint(u: Double, alongFrac: Double, yOff: Double): Vec3d {
                val arch = sin(u * PI)
                val zDist = alongFrac * corridorSpan
                return origin
                    .add(across.multiply((2.0 * u - 1.0) * span * (0.82 + 0.18 * alongFrac)))
                    .add(forward.multiply(zDist + arch * bow * 0.45))
                    .add(0.0, yOff, 0.0)
            }

            // Delicate tapered frost-light filaments drifting out of the cleaved air.
            for (rIdx in 0 until 7) {
                val frac = (rIdx + 0.5) / 7.0
                val alongFrac = 0.14 + 0.74 * ((rIdx * 0.37 + 0.12) % 1.0)
                val yOff = ((rIdx % 4) - 1.5) * 0.22 * (0.4 + 0.7 * progress)
                val segSpan = width * (0.14 + 0.12 * sin(frac * PI))
                val mid = wakePoint(0.14 + 0.72 * frac, alongFrac, yOff)
                val p0 = mid.subtract(across.multiply(segSpan))
                val p1 = mid.add(across.multiply(segSpan))
                d.band(AriusLayers.INK, p0, p1, UP, 0.025 * wakeOut + 0.008, withAlpha(NAVY_MID, 0f), withAlpha(NAVY_DARK, wakeA * 0.38f))
                d.band(AriusLayers.VFX_ADD, p0, p1, UP, 0.035 * wakeOut + 0.008, withAlpha(ICE_BLUE, 0f), withAlpha(FROST_WHITE, wakeA * 0.85f))
            }

            // Slender diamond-like ice slivers sparkling and dissolving cleanly.
            for (s in shards) {
                val drift = ((t - 2.5f) / (life - 2.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val basePos = wakePoint(s.u, s.along, s.yOff * (0.35 + 0.8 * drift))
                    .add(forward.multiply(s.driftOut * drift * 1.25))
                    .add(0.0, s.driftUp * drift * 1.05, 0.0)
                val ang = t * s.spin + s.u * 9.0
                val sz = s.size * (0.5 + 0.5 * wakeOut)
                val ax = across.multiply(cos(ang) * sz * 2.6).add(forward.multiply(sin(ang) * sz * 0.95))
                val ay = UP.multiply(sz * 0.26).add(across.multiply(-sin(ang) * sz * 0.14))
                val az = forward.multiply(cos(ang) * sz * 0.22).add(across.multiply(-sin(ang) * sz * 0.12))
                val bodyCol = if (s.dark) ICE_BLUE else FROST_WHITE
                d.box(AriusLayers.VFX_SHARD, basePos, ax, ay, az, withAlpha(bodyCol, wakeA * 0.88f))
                d.billboard(AriusLayers.VFX_ADD_FLARE, basePos, sz * 3.8, withAlpha(PURE_WHITE, wakeA * 0.65f))
            }
        }
    }

    private companion object {
        const val PURE_WHITE = 0xFFFFFFFF.toInt()
        const val FROST_WHITE = 0xFFF2FBFF.toInt()
        const val ICE_LIGHT = 0xFF9BE4FF.toInt()
        const val ICE_BLUE = 0xFF42A8EA.toInt()
        const val NAVY_MID = 0xFF143C66.toInt()
        const val NAVY_DARK = 0xFF0A2342.toInt()
    }
}

/**
 * Clean, fast white-blue flash on White Horizon release: brightens the cut beat for 2–3 ticks and falls off fast
 * with zero muddy grey wash or dark borders. Respects [AriusClientConfig.flash] and [AriusClientConfig.fx].
 */
object HorizonPulse {
    private var age = -1
    private var peak = 0f
    private const val TOTAL = 6

    fun trigger(strength: Float) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        if (fx <= 0.01f) return
        age = 0
        peak = (strength * fx).coerceIn(0f, 1f)
    }

    fun tick() {
        if (age >= 0 && ++age >= TOTAL) {
            age = -1
            peak = 0f
        }
    }

    fun reset() {
        age = -1
        peak = 0f
    }

    fun render(ctx: DrawContext) {
        if (age < 0 || peak <= 0.01f) return
        val client = MinecraftClient.getInstance()
        val td = client.renderTickCounter.getTickProgress(true)
        val t = age + td
        if (t >= TOTAL) return
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight

        // Short, clean white-blue flash that peaks immediately and falls off fast (zero grey wash, zero 2D HUD bar).
        val f = (1f - t / 4.0f).coerceIn(0f, 1f).let { it * it }
        if (f > 0.01f) {
            val cap = if (AriusClientConfig.flash) 0.28f else 0.08f
            ctx.fill(0, 0, w, h, withAlpha(0xFFEAF8FF.toInt(), cap * peak * f))
        }
    }
}

/**
 * Senkrechter Weißer Horizont (Tempel & Bergspaltung):
 * - `phase == 0` (Aufladen): Zwei schlanke, vertikal auf die erhobene Klinge ausgerichtete Frost-Spannungsbögen ziehen
 *   sich von links und rechts auf die exakte Klingenebene zusammen, während geformte Lichtbänder aus Frostweiß und Eisblau
 *   geordnet in Solvanes Schneide strömen und ein kompakter Bodenring auf der Altarstufe den Stand verankert. Im Stille-Beat
 *   (`silenceStart..swingStart`) verdichtet sich alles zu einer messerscharfen vertikalen Schneide mit Spitzen-Stern; im
 *   Niederschlag (`swingStart..chargeEnd`) zieht die Lichtspur nahtlos mit der fallenden Klinge nach unten.
 * - `phase == 1` (Niederschlag): Ein gewaltiger, messerscharf zugespitzter senkrechter Abwärts-Schnittbogen exakt in der
 *   Schnittebene, der aus Arius' Schlag durch den Tempel und im Folgeshot nahtlos bis an den Bergfuß rast.
 * - `phase == 2` (Bergspaltung): Die kolossale senkrechte Frostklinge fährt 1:1 synchron zu den linearen Z-Scheiben von
 *   `splitFull` über das Massiv und hinterlässt eine entlang des echten Bergprofils nachglühende Frostnaht.
 */
class VerticalHorizonFx(
    private val origin: Vec3d,
    direction: Vec3d,
    private val reach: Double,
    val phase: Int,
    private val owner: Entity?,
    life: Int,
) : AriusFx(life) {
    private val forward = Vec3d(direction.x, 0.0, direction.z).let {
        if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize()
    }
    private val across = Vec3d(-forward.z, 0.0, forward.x)

    private class PartShard(
        val u: Double, val along: Double, val side: Double,
        val driftOut: Double, val driftUp: Double, val size: Double, val spin: Double, val dark: Boolean,
    )

    private val shards: List<PartShard> = if (phase == 1) {
        val r = Random(origin.hashCode().toLong() xor 0x51A7L)
        List(24) { i ->
            PartShard(
                u = (i + 0.3 + r.nextDouble() * 0.4) / 24.0,
                along = 0.08 + r.nextDouble() * 0.85,
                side = if (i % 2 == 0) 1.0 else -1.0,
                driftOut = 0.55 + r.nextDouble() * 1.4,
                driftUp = (r.nextDouble() - 0.35) * 0.9,
                size = 0.06 + r.nextDouble() * 0.07,
                spin = (if (i % 2 == 0) 1.0 else -1.0) * (0.14 + r.nextDouble() * 0.2),
                dark = i % 3 == 0,
            )
        }
    } else emptyList()

    /** Geformtes Einzugsband: gleitet aus der linken oder rechten Flanke sauber gebogen in die erhobene Klinge. */
    private class GatherStream(
        val side: Double,
        val radius: Double,
        val yOffset: Double,
        val zBow: Double,
        val along: Double,
        val birthFrac: Float,
        val travelTicks: Float,
        val width: Double,
    )

    private val streams: List<GatherStream> = if (phase == 0) {
        val r = Random(origin.hashCode().toLong() xor 0x7E1AL)
        List(16) { i ->
            val side = if (i % 2 == 0) 1.0 else -1.0
            val pair = i / 2
            GatherStream(
                side = side,
                radius = 2.4 + (pair % 4) * 0.65 + r.nextDouble() * 0.45,
                yOffset = -0.35 + ((pair % 4) / 3.0) * 2.15 + (r.nextDouble() - 0.5) * 0.25,
                zBow = (if (pair % 2 == 0) 1.0 else -1.0) * (0.35 + r.nextDouble() * 0.45),
                along = 0.18 + (pair / 7.0) * 0.74,
                birthFrac = (pair * 0.105f + (if (side > 0) 0f else 0.045f)).coerceIn(0f, 0.78f),
                travelTicks = 17f + (pair % 3) * 3.5f,
                width = 0.045 + (pair % 3) * 0.015,
            )
        }
    } else emptyList()

    // Alle drei Phasen des Weißen Horizonts sind Schlüsselmomente und dürfen nie vorzeitig verdrängt werden.
    override val persistent: Boolean get() = true

    init {
        if (phase == 1) {
            val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
            val dist = cam.distanceTo(origin)
            if (dist < 96.0) {
                val near = (1.0 - (dist / 96.0).coerceIn(0.0, 1.0)).toFloat()
                HorizonPulse.trigger(0.55f + 0.45f * near)
            }
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        if (fx <= 0.01f) return
        when (phase) {
            0 -> renderVerticalCharge(d, t, fx)
            1 -> renderDownwardSlash(d, t, fx)
            else -> renderMountainCleave(d, t, fx)
        }
    }

    private fun camBillSide(d: VfxDraw, axis: Vec3d): Vec3d =
        d.camForward.crossProduct(axis).let { if (it.lengthSquared() < 1e-5) across else it.normalize() }

    private fun tipTaper(u: Double): Double {
        val s = sin(u * PI).coerceAtLeast(0.0)
        return s * sqrt(s)
    }

    /**
     * Zeichnet eine zu beiden Enden weich zugespitzte Linie mit nahtloser Querdämpfung.
     * Wichtig: [AriusLayers.VFX_GLOW] nutzt die radiale Textur `glow.png`, daher wird `u = 0.5f` gehalten und `v = 0..1`
     * quer über die Breite gelegt (keine Perlenkette). Auf `white.png` ([AriusLayers.INK], [AriusLayers.VFX_ADD]) sorgen
     * zwei Halbstreifen (Mitte voll → Ränder Alpha 0) für weiche, messerscharfe Kanten ohne harte Balken.
     */
    private fun drawTaperedLine(
        d: VfxDraw,
        from: Vec3d,
        to: Vec3d,
        side: Vec3d,
        halfWidth: Double,
        layer: net.minecraft.client.render.RenderLayer,
        color: Int,
        segments: Int = 28,
        endFade: Boolean = true,
    ) {
        val baseA = (color ushr 24) / 255f
        if (baseA <= 0.01f || halfWidth <= 1e-4) return
        val radialGlow = layer === AriusLayers.VFX_GLOW || layer === AriusLayers.MOON_GLOW
        val vc = d.buffer(layer)
        with(d) {
            for (i in 0 until segments) {
                val u0 = i / segments.toDouble()
                val u1 = (i + 1) / segments.toDouble()
                val w0 = if (endFade) sin(u0 * PI).coerceAtLeast(0.0) else 1.0
                val w1 = if (endFade) sin(u1 * PI).coerceAtLeast(0.0) else 1.0
                val p0 = from.lerp(to, u0)
                val p1 = from.lerp(to, u1)
                val h0 = halfWidth * (0.14 + 0.86 * w0)
                val h1 = halfWidth * (0.14 + 0.86 * w1)
                val c0 = withAlpha(color, baseA * (if (endFade) w0.toFloat() else 1f))
                val c1 = withAlpha(color, baseA * (if (endFade) w1.toFloat() else 1f))
                if (radialGlow) {
                    vc.put(p0.add(side.multiply(-h0)), 0.5f, 0f, c0)
                    vc.put(p0.add(side.multiply(h0)), 0.5f, 1f, c0)
                    vc.put(p1.add(side.multiply(h1)), 0.5f, 1f, c1)
                    vc.put(p1.add(side.multiply(-h1)), 0.5f, 0f, c1)
                } else {
                    val e0 = withAlpha(color, 0f)
                    val e1 = withAlpha(color, 0f)
                    vc.put(p0.add(side.multiply(-h0)), 0f, 0f, e0)
                    vc.put(p0, 0f, 0.5f, c0)
                    vc.put(p1, 1f, 0.5f, c1)
                    vc.put(p1.add(side.multiply(-h1)), 1f, 0f, e1)
                    vc.put(p0, 0f, 0.5f, c0)
                    vc.put(p0.add(side.multiply(h0)), 0f, 1f, e0)
                    vc.put(p1.add(side.multiply(h1)), 1f, 1f, e1)
                    vc.put(p1, 1f, 0.5f, c1)
                }
            }
        }
    }

    /**
     * Zeichnet einen geschlossenen, 360° gleichmäßig weichen Ring ohne Nahtlücke.
     * Vermeidet den `VfxDraw.ring`-Fehler mit `glow.png` (`u = 0..1` über den Umfang ließ den Ring bei 0° verschwinden).
     */
    private fun drawSmoothRing(
        d: VfxDraw,
        center: Vec3d,
        axisA: Vec3d,
        axisB: Vec3d,
        radius: Double,
        halfWidth: Double,
        layer: net.minecraft.client.render.RenderLayer,
        color: Int,
        segments: Int = 48,
    ) {
        val baseA = (color ushr 24) / 255f
        if (baseA <= 0.01f || halfWidth <= 1e-4) return
        val rIn = (radius - halfWidth).coerceAtLeast(0.02)
        val rOut = radius + halfWidth
        val radialGlow = layer === AriusLayers.VFX_GLOW || layer === AriusLayers.MOON_GLOW
        val vc = d.buffer(layer)
        with(d) {
            for (i in 0 until segments) {
                val a0 = i * (2.0 * PI / segments)
                val a1 = (i + 1) * (2.0 * PI / segments)
                val dir0 = axisA.multiply(cos(a0)).add(axisB.multiply(sin(a0)))
                val dir1 = axisA.multiply(cos(a1)).add(axisB.multiply(sin(a1)))
                if (radialGlow) {
                    vc.put(center.add(dir0.multiply(rIn)), 0.5f, 0f, color)
                    vc.put(center.add(dir0.multiply(rOut)), 0.5f, 1f, color)
                    vc.put(center.add(dir1.multiply(rOut)), 0.5f, 1f, color)
                    vc.put(center.add(dir1.multiply(rIn)), 0.5f, 0f, color)
                } else {
                    val edge = withAlpha(color, 0f)
                    val pIn0 = center.add(dir0.multiply(rIn))
                    val pMid0 = center.add(dir0.multiply(radius))
                    val pOut0 = center.add(dir0.multiply(rOut))
                    val pIn1 = center.add(dir1.multiply(rIn))
                    val pMid1 = center.add(dir1.multiply(radius))
                    val pOut1 = center.add(dir1.multiply(rOut))
                    vc.put(pIn0, 0f, 0f, edge)
                    vc.put(pMid0, 0f, 0.5f, color)
                    vc.put(pMid1, 1f, 0.5f, color)
                    vc.put(pIn1, 1f, 0f, edge)
                    vc.put(pMid0, 0f, 0.5f, color)
                    vc.put(pOut0, 0f, 1f, edge)
                    vc.put(pOut1, 1f, 1f, edge)
                    vc.put(pMid1, 1f, 0.5f, color)
                }
            }
        }
    }

    /**
     * Senkrechtes Aufladen (Phase 0), taktgenau gekoppelt an `Scenes.theCut()` und `AriusModel.HORIZON_RAISE` / `CUT_RELEASE`:
     * - `0 .. silenceStart`: Geordnete Frost-Lichtbänder ziehen aus beiden Flanken in die erhobene Klinge; zwei feine,
     *   spitz zulaufende senkrechte Spannungsbögen schließen sich beidseits auf die Klingenebene; ein kompakter, auf der
     *   Altarstufe verankerter Druckring pulsiert um Arius' Füße (nie in der Luft über den Stufen, kein kegeliger Lichtmantel).
     * - `silenceStart .. swingStart` (6 Ticks Stille-Beat): Bänder sind in der Klinge aufgegangen, die Spannungsbögen
     *   verschmelzen zu einer messerscharfen senkrechten Frostschneide, an der Spitze blüht ein klarer Stern auf.
     * - `swingStart .. chargeEnd` (6 Ticks `CUT_RELEASE`-Niederschlag): Bodenring und Spannungsbögen ziehen sofort weg,
     *   die aufgeladene Klinge zieht eine leuchtende senkrechte Lichtspur mit dem Abwärtshieb bis zum Einschlag.
     */
    private fun renderVerticalCharge(d: VfxDraw, t: Float, fx: Float) {
        if (owner?.isRemoved == true) return
        // Server gibt +2 Ticks Taktpuffer mit; die eigentliche Ladezeit endet exakt beim Einschlag.
        val chargeEnd = (life - 2f).coerceAtLeast(12f)
        val swingStart = (chargeEnd - 6f).coerceAtLeast(chargeEnd * 0.78f)
        val silenceStart = (swingStart - 6f).coerceAtLeast(chargeEnd * 0.64f)

        val chargeP = (t / silenceStart.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val build = chargeP * chargeP
        val inSilence = ((t - silenceStart) / (swingStart - silenceStart).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val inSwing = ((t - swingStart) / (chargeEnd - swingStart).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val intro = (t / 6f).coerceIn(0f, 1f)
        val aScale = intro * fx
        val preSwingFade = (1f - (inSwing / 0.42f).coerceIn(0f, 1f)).let { it * it }

        val anchor = BladeAnchors.of(owner)
        val ownerPos = owner?.getLerpedPos(d.tickDelta) ?: origin
        val base = anchor?.base ?: ownerPos.add(0.0, 2.35, 0.0)
        val tip = anchor?.tip ?: ownerPos.add(0.0, 4.10, 0.0)
        val bladeAxis = tip.subtract(base)
        val bladeDir = if (bladeAxis.lengthSquared() < 1e-6) UP else bladeAxis.normalize()
        val bladeMid = base.lerp(tip, 0.55)
        val bladeSide = camBillSide(d, bladeDir)
        val feet = Vec3d(ownerPos.x, ownerPos.y + 0.05, ownerPos.z)

        // 1) Geordnete Frost-Einzugsbänder aus linker und rechter Flanke in die erhobene Klinge.
        //    Jedes Band besitzt ein festes Zeitfenster [birth, birth + travelTicks] <= silenceStart + 2f: kein Aufploppen,
        //    und vor dem Stille-Höhepunkt sind alle Bänder sauber in der Schneide aufgegangen.
        if (inSwing <= 0.15f) {
            val streamFade = aScale * (1f - (inSwing / 0.15f).coerceIn(0f, 1f))
            val latestStart = (silenceStart - 10f).coerceAtLeast(4f)
            for (st in streams) {
                val birth = st.birthFrac * latestStart
                val travel = st.travelTicks.coerceAtMost((silenceStart + 2f - birth).coerceAtLeast(8f))
                val local = (t - birth) / travel
                if (local <= 0f || local >= 1.05f) continue
                val headP = local.coerceIn(0f, 1f)
                val tailP = (local - 0.34f).coerceIn(0f, 1f)
                if (headP - tailP <= 0.01f) continue

                val startPt = bladeMid
                    .add(across.multiply(st.side * st.radius * (1.0 - 0.30 * build)))
                    .add(forward.multiply(st.zBow))
                    .add(0.0, st.yOffset * (1.0 - 0.45 * build), 0.0)
                val ctrlPt = bladeMid
                    .add(across.multiply(st.side * st.radius * 0.38))
                    .add(forward.multiply(-st.zBow * 0.35))
                    .add(0.0, st.yOffset * 0.25 + 0.35, 0.0)
                val targetPt = base.lerp(tip, st.along)

                fun bezier(s: Double): Vec3d {
                    val e = s * s * (1.35 - 0.35 * s)
                    val inv = 1.0 - e
                    return startPt.multiply(inv * inv).add(ctrlPt.multiply(2.0 * inv * e)).add(targetPt.multiply(e * e))
                }

                val pts = ArrayList<Vec3d>(7)
                for (k in 0..6) {
                    val s = tailP + (headP - tailP) * (k / 6.0)
                    pts.add(bezier(s))
                }
                val env = sin(headP * PI).toFloat().coerceIn(0f, 1f) * (1f - ((local - 0.82f) / 0.23f).coerceIn(0f, 1f))
                val ribA = streamFade * (0.42f + 0.58f * chargeP) * env
                if (ribA > 0.02f) {
                    d.ribbon(
                        AriusLayers.INK, pts, { q -> st.width * 1.45 * (0.25 + 0.75 * q) },
                        { q -> withAlpha(NAVY_DARK, ribA * 0.62f * q) }, uScale = 0f, uOffset = 0.5f,
                    )
                    d.ribbon(
                        AriusLayers.VFX_GLOW, pts, { q -> st.width * 1.65 * (0.30 + 0.70 * q) },
                        { q -> withAlpha(if (q > 0.65f) FROST_WHITE else ICE_BLUE, ribA * (0.45f + 0.55f * q)) },
                        uScale = 0f, uOffset = 0.5f,
                    )
                }
            }
        }

        // 2) Zwei schlanke, senkrecht auf die Klingenebene ausgerichtete Spannungsbögen (Gegenstück zum waagerechten
        //    Weißen Horizont): schließen sich links & rechts spiegelbildlich auf Solvanes Schneide zusammen.
        if (preSwingFade > 0.015f) {
            val arcAnchor = Vec3d(ownerPos.x, ownerPos.y + 2.75, ownerPos.z).add(forward.multiply(0.18))
            val arcHalfH = 1.45 + 1.45 * chargeP + 0.25 * inSilence
            val gap = 1.45 * (1.0 - build) * (1.0 - 0.85 * inSilence) + 0.03
            val bowOut = (0.35 + 0.25 * (1.0 - build)) * (1.0 - 0.75 * inSilence)
            val arcA = aScale * preSwingFade * (0.34f + 0.66f * chargeP)
            val tightenArc = 1.0 - 0.42 * inSilence

            fun drawTensionArc(layer: net.minecraft.client.render.RenderLayer, sg: Double, halfW: Double, col: Int) {
                val baseA = (col ushr 24) / 255f
                if (baseA <= 0.01f) return
                val radialGlow = layer === AriusLayers.VFX_GLOW
                val vc = d.buffer(layer)
                val segs = 20
                with(d) {
                    for (i in 0 until segs) {
                        val u0 = i / segs.toDouble()
                        val u1 = (i + 1) / segs.toDouble()
                        val s0 = sin(u0 * PI).coerceAtLeast(0.0)
                        val s1 = sin(u1 * PI).coerceAtLeast(0.0)
                        val p0 = arcAnchor.add(0.0, (2.0 * u0 - 1.0) * arcHalfH, 0.0)
                            .add(across.multiply(sg * (gap + bowOut * s0)))
                            .add(forward.multiply(s0 * 0.22))
                        val p1 = arcAnchor.add(0.0, (2.0 * u1 - 1.0) * arcHalfH, 0.0)
                            .add(across.multiply(sg * (gap + bowOut * s1)))
                            .add(forward.multiply(s1 * 0.22))
                        val sideVec = camBillSide(d, p1.subtract(p0).let { if (it.lengthSquared() < 1e-6) UP else it.normalize() })
                        val h0 = halfW * (0.12 + 0.88 * s0)
                        val h1 = halfW * (0.12 + 0.88 * s1)
                        val c0 = withAlpha(col, baseA * s0.toFloat())
                        val c1 = withAlpha(col, baseA * s1.toFloat())
                        if (radialGlow) {
                            vc.put(p0.add(sideVec.multiply(-h0)), 0.5f, 0f, c0)
                            vc.put(p0.add(sideVec.multiply(h0)), 0.5f, 1f, c0)
                            vc.put(p1.add(sideVec.multiply(h1)), 0.5f, 1f, c1)
                            vc.put(p1.add(sideVec.multiply(-h1)), 0.5f, 0f, c1)
                        } else {
                            val e0 = withAlpha(col, 0f)
                            val e1 = withAlpha(col, 0f)
                            vc.put(p0.add(sideVec.multiply(-h0)), 0f, 0f, e0)
                            vc.put(p0, 0f, 0.5f, c0)
                            vc.put(p1, 1f, 0.5f, c1)
                            vc.put(p1.add(sideVec.multiply(-h1)), 1f, 0f, e1)
                            vc.put(p0, 0f, 0.5f, c0)
                            vc.put(p0.add(sideVec.multiply(h0)), 0f, 1f, e0)
                            vc.put(p1.add(sideVec.multiply(h1)), 1f, 1f, e1)
                            vc.put(p1, 1f, 0.5f, c1)
                        }
                    }
                }
            }

            for (sg in doubleArrayOf(-1.0, 1.0)) {
                drawTensionArc(AriusLayers.INK, sg, (0.085 + 0.04 * build) * tightenArc, withAlpha(NAVY_DARK, arcA * 0.72f))
                drawTensionArc(AriusLayers.VFX_GLOW, sg, (0.13 + 0.08 * build) * tightenArc, withAlpha(ICE_BLUE, arcA * 0.88f))
                drawTensionArc(AriusLayers.VFX_ADD, sg, (0.032 + 0.025 * build) * tightenArc, withAlpha(FROST_WHITE, arcA * 0.92f))
            }
        }

        // 3) Kompakter, fest auf der Altarstufe verankerter Druckring (Radius <= 1,45: ragt nie über die Stufenkante).
        if (preSwingFade > 0.015f) {
            val ringScale = aScale * preSwingFade
            val gatherFade = (1f - inSilence).coerceIn(0f, 1f)
            if (gatherFade > 0.02f) {
                for (k in 0 until 2) {
                    val ph = ((t * 0.042f + k * 0.5f) % 1f)
                    val r = 0.65 + 1.35 * (1.0 - ph) * (1.0 - ph)
                    val pulseA = ringScale * gatherFade * (0.28f + 0.72f * build) * sin(ph * PI).toFloat().coerceAtLeast(0f)
                    if (pulseA > 0.02f) {
                        drawSmoothRing(d, feet, across, forward, r, 0.14 + 0.10 * (1.0 - ph), AriusLayers.INK, withAlpha(NAVY_DARK, pulseA * 0.42f), segments = 44)
                        drawSmoothRing(d, feet, across, forward, r, 0.22 + 0.14 * (1.0 - ph), AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, pulseA * 0.78f), segments = 44)
                    }
                }
            }
            val anchorR = 1.32 - 0.34 * inSilence
            val anchorA = ringScale * (0.35f + 0.65f * build)
            drawSmoothRing(d, feet, across, forward, anchorR, 0.16, AriusLayers.INK, withAlpha(NAVY_DARK, anchorA * 0.52f), segments = 44)
            drawSmoothRing(d, feet, across, forward, anchorR, 0.26, AriusLayers.VFX_GLOW, withAlpha(ICE_LIGHT, anchorA * 0.88f), segments = 44)
            drawSmoothRing(d, feet, across, forward, anchorR, 0.055, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, anchorA * 0.85f), segments = 44)
        }

        // 4) Klingen-Aufladung: glatter, eng an Solvane anliegender Frostmantel + messerscharfe Spitzenverlängerung.
        //    Im Stille-Beat zieht sie sich haarscharf zusammen; im Niederschlag (inSwing > 0) folgt ein heller Klingen-Schweif.
        val tighten = (1.0 - 0.40 * inSilence) * (1.0 - 0.25 * inSwing)
        val edgeA = aScale * (0.68f + 0.32f * chargeP)
        drawTaperedLine(d, base, tip, bladeSide, (0.14 + 0.08 * build) * tighten, AriusLayers.INK, withAlpha(NAVY_DARK, edgeA * 0.82f), segments = 12, endFade = false)
        drawTaperedLine(d, base, tip, bladeSide, (0.20 + 0.18 * build) * tighten, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, edgeA * 0.94f), segments = 12, endFade = false)
        drawTaperedLine(d, base, tip, bladeSide, (0.065 + 0.055 * build) * tighten, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, edgeA), segments = 12, endFade = false)

        val gleamLen = if (inSwing > 0f) {
            (1.65 + 1.8 * inSwing)
        } else {
            (0.45 + 1.75 * build) * (1.0 + 0.22 * sin(inSilence * PI))
        }
        val gleamEnd = tip.add(bladeDir.multiply(gleamLen))
        drawTaperedLine(d, base.lerp(tip, 0.15), gleamEnd, bladeSide, (0.12 + 0.10 * build) * tighten, AriusLayers.INK, withAlpha(NAVY_DARK, edgeA * 0.65f), segments = 14)
        drawTaperedLine(d, base.lerp(tip, 0.15), gleamEnd, bladeSide, (0.20 + 0.16 * build) * tighten, AriusLayers.VFX_GLOW, withAlpha(ICE_LIGHT, edgeA * 0.92f), segments = 14)
        drawTaperedLine(d, base.lerp(tip, 0.15), gleamEnd, bladeSide, (0.06 + 0.05 * build) * tighten, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, edgeA), segments = 14)

        // Im Niederschlag (`CUT_RELEASE`, T = 84..90): leuchtender vertikaler Schwung-Fächer von der Überkopf-Spitze zur
        // herabsausenden Klinge, damit der Übergang in den Abwärtsschnitt lückenlos ist.
        if (inSwing > 0.02f) {
            val swingA = aScale * (0.55f + 0.45f * inSwing)
            val highRef = ownerPos.add(0.0, 4.35, 0.0)
            val swingMid = highRef.lerp(tip, 0.5).add(forward.multiply(0.95 * inSwing))
            val swingPts = listOf(highRef, swingMid, tip, gleamEnd)
            d.ribbon(AriusLayers.INK, swingPts, { q -> 0.16 * q }, { q -> withAlpha(NAVY_DARK, swingA * 0.55f * q) }, uScale = 0f, uOffset = 0.5f)
            d.ribbon(AriusLayers.VFX_GLOW, swingPts, { q -> 0.34 * q }, { q -> withAlpha(ICE_LIGHT, swingA * 0.90f * q) }, uScale = 0f, uOffset = 0.5f)
            d.ribbon(AriusLayers.VFX_ADD, swingPts, { q -> 0.12 * q }, { q -> withAlpha(PURE_WHITE, swingA * q) }, uScale = 0f, uOffset = 0.5f)
        }

        // Spitzen-Stern: wächst ruhig mit dem Aufladen, blüht im Stille-Beat mit feinem Fadenkreuz auf und verdichtet sich.
        val silBloom = if (inSwing > 0f) (1f - inSwing) * 0.4f else sin(inSilence * PI).toFloat()
        val starSz = (0.55 + 1.05 * build) * (1.0 + 0.85 * silBloom) * (1.0 - 0.30 * inSwing)
        d.billboard(AriusLayers.VFX_GLOW, bladeMid, (0.95 + 1.45 * build) * tighten, withAlpha(ICE_LIGHT, edgeA * 0.48f))
        d.billboard(AriusLayers.VFX_ADD_FLARE, tip, starSz, withAlpha(PURE_WHITE, edgeA))
        if (silBloom > 0.04f) {
            val crossW = (0.75 + 1.85 * silBloom)
            val crossH = (0.95 + 2.45 * silBloom)
            val cSide = d.camRight
            val cUp = d.camUp
            drawTaperedLine(d, tip.subtract(cSide.multiply(crossW)), tip.add(cSide.multiply(crossW)), cUp, 0.045 * silBloom + 0.012, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, aScale * silBloom), segments = 10)
            drawTaperedLine(d, tip.subtract(cUp.multiply(crossH)), tip.add(cUp.multiply(crossH)), cSide, 0.045 * silBloom + 0.012, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, aScale * silBloom), segments = 10)
        }

        // 5) Feine Boden-Schnittnaht vor der Altarstufe (ab +2,2 Blöcke vor Arius, nie in den Altarblöcken versenkt).
        if (preSwingFade > 0.02f) {
            val lineA = aScale * preSwingFade * (0.28f + 0.52f * build)
            val pathLen = (reach * (0.32 + 0.58 * chargeP)).coerceAtLeast(18.0)
            val pathFrom = origin.add(forward.multiply(2.2)).add(0.0, 0.14, 0.0)
            val pathTo = pathFrom.add(forward.multiply(pathLen))
            drawTaperedLine(d, pathFrom, pathTo, across, 0.24 * tighten, AriusLayers.INK, withAlpha(NAVY_DARK, lineA * 0.52f), segments = 18)
            drawTaperedLine(d, pathFrom, pathTo, across, 0.40 * tighten, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, lineA * 0.72f), segments = 18)
            drawTaperedLine(d, pathFrom, pathTo, across, 0.09 * tighten, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, lineA * 0.88f), segments = 18)
        }
    }

    private fun renderDownwardSlash(d: VfxDraw, t: Float, fx: Float) {
        val progress = (t / life.coerceAtLeast(1)).coerceIn(0f, 1f)
        val plateauShot = reach > 90.0
        val maxTravel = reach.coerceAtLeast(36.0)

        // Tempel-Schlag: öffnet sofort ab Tick 0 direkt aus Arius' Niederschlag und jagt durch die Halle.
        // Plateau-Folgeshot (`reach > 90`): läuft gleichmäßig und voll leuchtend bis exakt zum Schnitt auf den Bergfuß.
        val open = if (plateauShot) 1f else ((t + 0.9f) / 2.2f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
        val travel = if (plateauShot) {
            val runTicks = (life - 2f).coerceAtLeast(1f)
            val u = (t / runTicks).coerceIn(0f, 1f)
            // Leicht beschleunigender Anlauf, landet exakt bei t = runTicks (26) am Bergfuß.
            maxTravel * (0.12 * u + 0.88 * u * (0.65 + 0.35 * u))
        } else {
            val runTicks = (life - 3f).coerceAtLeast(1f)
            val u = (t / runTicks).coerceIn(0f, 1f)
            maxTravel * (1.0 - (1.0 - u) * (1.0 - u) * (1.0 - 0.35 * u))
        }

        val peakBoost = if (plateauShot) 0.45f else (1f - (t / 4.5f).coerceIn(0f, 1f)).let { it * it }
        val bladeFade = if (plateauShot) {
            (1f - ((t - (life - 2.0f)) / 2.0f).coerceIn(0f, 1f)).let { it * it } * fx
        } else {
            (1f - ((t - 4.0f) / (life - 4.0f).coerceAtLeast(1f)).coerceIn(0f, 1f)).let { it * it } * fx
        }

        val halfHeight = if (plateauShot) {
            val u = (t / (life - 2f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            19.0 + 19.0 * u
        } else {
            14.5 * (0.78 + 0.30 * open)
        }
        val baseY = if (plateauShot) origin.y + 4.5 + 14.0 * (t / (life - 2f).coerceAtLeast(1f)).coerceIn(0f, 1f) else origin.y + 4.8
        val center = Vec3d(origin.x, baseY, origin.z).add(forward.multiply(travel))
        val depth = (if (plateauShot) 6.5 + 4.5 * progress else (4.0 + 2.5 * open) * (0.88 + 0.22 * (1f - progress)))
        val bow = if (plateauShot) 5.5 + 3.0 * progress else 3.6 * (0.72 + 0.28 * open)

        // Senkrechter Sichelbogen in der Schnittebene (forward × UP): u = 0 unten, u = 0.5 vorn, u = 1 oben.
        fun arcPt(u: Double, back: Double, sideOff: Double = 0.0): Vec3d {
            val arch = sin(u * PI)
            val taper = tipTaper(u)
            val yCoord = ((2.0 * u - 1.0) * halfHeight).coerceAtLeast(-(baseY - origin.y) + 0.15)
            return center
                .add(across.multiply(sideOff))
                .add(0.0, yCoord, 0.0)
                .add(forward.multiply(arch * bow - back * depth * (0.10 + 0.90 * taper)))
        }

        if (bladeFade > 0.015f) {
            // 1) Breiter senkrechter Sichelkörper in der Schnittebene (glatte UVs ohne Perlenkette auf VFX_GLOW).
            fun vSheet(layer: net.minecraft.client.render.RenderLayer, from: Double, to: Double, outerCol: Int, innerCol: Int, sideShift: Double = 0.0) {
                val aOut = (outerCol ushr 24) / 255f
                val aIn = (innerCol ushr 24) / 255f
                val radialGlow = layer === AriusLayers.VFX_GLOW
                val vc = d.buffer(layer)
                with(d) {
                    for (i in 0 until 44) {
                        val u0 = i / 44.0
                        val u1 = (i + 1) / 44.0
                        val e0 = sin(u0 * PI).toFloat()
                        val e1 = sin(u1 * PI).toFloat()
                        val cO0 = withAlpha(outerCol, aOut * e0)
                        val cI0 = withAlpha(innerCol, aIn * e0)
                        val cI1 = withAlpha(innerCol, aIn * e1)
                        val cO1 = withAlpha(outerCol, aOut * e1)
                        val uTex = if (radialGlow) 0.5f else 0f
                        val uTex1 = if (radialGlow) 0.5f else 1f
                        val v0 = if (radialGlow) 0.5f else 0f
                        val v1 = 1f
                        vc.put(arcPt(u0, from, sideShift), uTex, v0, cO0)
                        vc.put(arcPt(u0, to, sideShift), uTex, v1, cI0)
                        vc.put(arcPt(u1, to, sideShift), uTex1, v1, cI1)
                        vc.put(arcPt(u1, from, sideShift), uTex1, v0, cO1)
                    }
                }
            }
            vSheet(AriusLayers.INK, 0.0, 0.16, withAlpha(NAVY_DARK, bladeFade * 0.62f), withAlpha(NAVY_MID, 0f), 0.02)
            vSheet(AriusLayers.VFX_GLOW, 0.02, 0.40, withAlpha(ICE_LIGHT, bladeFade * 0.92f), withAlpha(ICE_BLUE, bladeFade * 0.45f))
            vSheet(AriusLayers.VFX_GLOW, 0.36, 0.96, withAlpha(ICE_BLUE, bladeFade * 0.55f), withAlpha(ICE_BLUE, 0f))
            vSheet(AriusLayers.VFX_ADD, 0.0, 0.18, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * (0.75f + 0.25f * peakBoost)))
            vSheet(AriusLayers.VFX_ADD, 0.18, 0.56, withAlpha(ICE_LIGHT, bladeFade * (0.55f + 0.35f * peakBoost)), withAlpha(ICE_BLUE, 0f))

            // 2) Scharfe linsenförmige Quer-Dicke an der führenden Schneide (frontal aus 1st & 3rd Person klar lesbar).
            val coreX = (if (plateauShot) 0.42 else 0.22 * (0.55 + 0.45 * (1f - progress * 0.5f))) + 0.12 * peakBoost
            val haloX = coreX * 2.6
            val inkX = coreX * 1.35

            fun xRazor(layer: net.minecraft.client.render.RenderLayer, back: Double, x0: Double, x1: Double, c0: Int, c1: Int) {
                val a0 = (c0 ushr 24) / 255f
                val a1 = (c1 ushr 24) / 255f
                val radialGlow = layer === AriusLayers.VFX_GLOW
                val vc = d.buffer(layer)
                with(d) {
                    for (i in 0 until 44) {
                        val u0 = i / 44.0
                        val u1 = (i + 1) / 44.0
                        val t0 = tipTaper(u0)
                        val t1 = tipTaper(u1)
                        val e0 = sin(u0 * PI).toFloat()
                        val e1 = sin(u1 * PI).toFloat()
                        val uTex = if (radialGlow) 0.5f else 0f
                        val uTex1 = if (radialGlow) 0.5f else 1f
                        val v0 = if (radialGlow) 0.5f else 0f
                        vc.put(arcPt(u0, back, x0 * t0), uTex, v0, withAlpha(c0, a0 * e0))
                        vc.put(arcPt(u0, back, x1 * t0), uTex, 1f, withAlpha(c1, a1 * e0))
                        vc.put(arcPt(u1, back, x1 * t1), uTex1, 1f, withAlpha(c1, a1 * e1))
                        vc.put(arcPt(u1, back, x0 * t1), uTex1, v0, withAlpha(c0, a0 * e1))
                    }
                }
            }
            for (sg in intArrayOf(-1, 1)) {
                xRazor(AriusLayers.INK, 0.06, sg * coreX * 0.72, sg * inkX * 1.08, withAlpha(NAVY_DARK, bladeFade * 0.85f), withAlpha(NAVY_DARK, 0f))
                xRazor(AriusLayers.VFX_GLOW, 0.045, sg * coreX * 0.35, sg * haloX, withAlpha(ICE_BLUE, bladeFade * 0.86f), withAlpha(ICE_BLUE, 0f))
                xRazor(AriusLayers.VFX_ADD, 0.025, 0.0, sg * haloX * 0.78, withAlpha(ICE_LIGHT, bladeFade * (0.58f + 0.34f * peakBoost)), withAlpha(ICE_BLUE, 0f))
                xRazor(AriusLayers.VFX_GLOW, 0.015, 0.0, sg * coreX, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, 0f))
                xRazor(AriusLayers.VFX_ADD, 0.005, 0.0, sg * coreX * 0.9, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * 0.25f))
            }
        }

        // 3) Senkrechte Restspur entlang der aufgeschnittenen Luft + gleißender Kernblitz.
        if (t < 6f) {
            val flareF = (1f - t / 6f).let { it * it } * fx
            val mid = arcPt(0.5, 0.0)
            d.billboard(AriusLayers.VFX_ADD_FLARE, mid, (5.5 + 3.5 * open) * flareF, withAlpha(PURE_WHITE, flareF))
            d.billboard(AriusLayers.VFX_GLOW, mid, (4.0 + 2.8 * open) * flareF, withAlpha(ICE_LIGHT, flareF * 0.72f))
        }
        val wakeIn = ((t - 1.0f) / 2.5f).coerceIn(0f, 1f)
        val wakeOut = (1f - ((t - (life * 0.42f)) / (life * 0.58f).coerceAtLeast(1f)).coerceIn(0f, 1f))
        val wakeA = wakeIn * wakeOut * wakeOut * fx
        if (wakeA > 0.015f) {
            val seamFrom = origin.add(0.0, 0.18, 0.0)
            val seamTo = origin.add(forward.multiply(travel + 2.5)).add(0.0, 0.18, 0.0)
            val pSide = camBillSide(d, forward)
            drawTaperedLine(d, seamFrom, seamTo, pSide, (if (plateauShot) 0.42 else 0.14) * wakeOut + 0.02, AriusLayers.INK, withAlpha(NAVY_DARK, wakeA * 0.62f), segments = 18)
            drawTaperedLine(d, seamFrom, seamTo, pSide, (if (plateauShot) 0.85 else 0.28) * wakeOut + 0.04, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, wakeA * 0.82f), segments = 18)
            drawTaperedLine(d, seamFrom, seamTo, pSide, (if (plateauShot) 0.24 else 0.09) * wakeOut + 0.015, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, wakeA), segments = 18)

            // Schlanke senkrechte Lichtnaht über dem Ursprung, die noch kurz nachglüht und ausklingt.
            if (!plateauShot) {
                val vSide = camBillSide(d, UP)
                val vFrom = origin.add(forward.multiply((travel * 0.45).coerceAtMost(14.0))).add(0.0, 0.2, 0.0)
                val vTo = vFrom.add(0.0, 22.0 * (0.4 + 0.6 * wakeOut), 0.0)
                drawTaperedLine(d, vFrom, vTo, vSide, 0.20 * wakeOut, AriusLayers.VFX_GLOW, withAlpha(ICE_LIGHT, wakeA * 0.68f), segments = 14)
                drawTaperedLine(d, vFrom, vTo, vSide, 0.06 * wakeOut, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, wakeA * 0.90f), segments = 14)
            }

            // Schlanke Eissplitter, die sich sauber nach links und rechts von der Schnittlinie lösen.
            val drift = ((t - 1.5f) / (life - 1.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            for (s in shards) {
                val basePos = origin
                    .add(across.multiply(s.side * (0.18 + s.driftOut * drift * 1.6)))
                    .add(0.0, 0.8 + s.u * (if (plateauShot) 24.0 else 11.0) + s.driftUp * drift * 1.1, 0.0)
                    .add(forward.multiply(s.along * travel))
                val ang = t * s.spin + s.u * 9.0
                val sz = s.size * (if (plateauShot) 1.7 else 1.0) * (0.5 + 0.5 * wakeOut)
                val ax = UP.multiply(cos(ang) * sz * 2.5).add(forward.multiply(sin(ang) * sz * 0.8))
                val ay = across.multiply(sz * 0.28)
                val az = forward.multiply(cos(ang) * sz * 0.25).add(UP.multiply(-sin(ang) * sz * 0.15))
                d.box(AriusLayers.VFX_SHARD, basePos, ax, ay, az, withAlpha(if (s.dark) ICE_BLUE else FROST_WHITE, wakeA * 0.9f))
                d.billboard(AriusLayers.VFX_ADD_FLARE, basePos, sz * 3.5, withAlpha(PURE_WHITE, wakeA * 0.6f))
            }
        }
    }

    /**
     * Bergspaltung entlang x = CUT_X (`origin` = Bergfuß auf CUT_X, `reach` = Gesamtlänge bis hinter das Massiv):
     * Eine kolossale, glatt zugespitzte senkrechte Frostklinge fährt 1:1 synchron zu den linearen Z-Scheiben von
     * `splitFull` (`cleaveTicks = life - 38`, standardmäßig 24 Ticks) von vorn nach hinten durch den Berg.
     * Einschlagspunkt und nachglühende Schnittnaht folgen dem echten Bergprofil (Fuß → Gipfelschulter → Horn → Südfuß).
     */
    private fun renderMountainCleave(d: VfxDraw, t: Float, fx: Float) {
        val cleaveTicks = (life - 38f).coerceAtLeast(1f)
        // +0,8 Tick Vorlauf, damit die vordere Schneide den physisch geöffneten Block-Scheiben exakt vorausläuft.
        val sweep = ((t + 0.8f) / cleaveTicks).coerceIn(0f, 1f)
        val totalZ = reach.coerceAtLeast(180.0)
        val headDist = totalZ * sweep
        val vSide = camBillSide(d, UP)

        // Echtes Bergprofil entlang sweep 0..1 (symmetrisch um den Gipfel bei sweep = 0,5).
        fun ridgeHeight(u: Double): Double {
            val dNorm = kotlin.math.abs(u - 0.5) * 2.0
            val slope = (1.0 - dNorm).coerceIn(0.0, 1.0)
            return origin.y + 8.0 + 206.0 * Math.pow(slope, 0.85)
        }

        // 1) Fortschreitende senkrechte Lichtklinge, die den Berg vom Himmel bis zum Fuß durchtrennt.
        val bladeFade = (1f - ((t - cleaveTicks + 1.5f) / 14f).coerceIn(0f, 1f)).let { it * it } * fx
        if (bladeFade > 0.015f) {
            val topY = origin.y + 295.0
            val botY = origin.y - 4.0
            val headBase = origin.add(forward.multiply(headDist))
            val headTop = Vec3d(headBase.x, topY, headBase.z).add(forward.multiply(12.0))
            val headBot = Vec3d(headBase.x, botY, headBase.z)

            // Glatte, messerscharfe Hauptschneide vom Himmel bis zum Bergfuß (auch aus 280 Blöcken klar lesbar).
            drawTaperedLine(d, headBot, headTop, vSide, 4.4, AriusLayers.INK, withAlpha(NAVY_DARK, bladeFade * 0.78f), segments = 28)
            drawTaperedLine(d, headBot, headTop, vSide, 11.5, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, bladeFade * 0.92f), segments = 28)
            drawTaperedLine(d, headBot, headTop, vSide, 5.2, AriusLayers.VFX_GLOW, withAlpha(FROST_WHITE, bladeFade), segments = 28)
            drawTaperedLine(d, headBot, headTop, vSide, 2.6, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, bladeFade), segments = 28)

            // Geformter senkrechter Sichelbogen hinter der vorderen Schneide (glatte UVs ohne Perlenkette).
            val arcBack = 74.0
            fun mtnPt(u: Double, back: Double): Vec3d {
                val tp = tipTaper(u)
                val y = botY + (topY - botY) * u
                val bow = sin(u * PI) * 22.0
                return Vec3d(headBase.x, y, headBase.z).add(forward.multiply(bow - back * arcBack * (0.08 + 0.92 * tp)))
            }
            fun mtnSheet(layer: net.minecraft.client.render.RenderLayer, f0: Double, f1: Double, c0: Int, c1: Int) {
                val a0 = (c0 ushr 24) / 255f
                val a1 = (c1 ushr 24) / 255f
                val radialGlow = layer === AriusLayers.VFX_GLOW
                val vc = d.buffer(layer)
                with(d) {
                    for (i in 0 until 32) {
                        val u0 = i / 32.0
                        val u1 = (i + 1) / 32.0
                        val e0 = sin(u0 * PI).toFloat()
                        val e1 = sin(u1 * PI).toFloat()
                        val uTex = if (radialGlow) 0.5f else 0f
                        val uTex1 = if (radialGlow) 0.5f else 1f
                        val v0 = if (radialGlow) 0.5f else 0f
                        vc.put(mtnPt(u0, f0), uTex, v0, withAlpha(c0, a0 * e0))
                        vc.put(mtnPt(u0, f1), uTex, 1f, withAlpha(c1, a1 * e0))
                        vc.put(mtnPt(u1, f1), uTex1, 1f, withAlpha(c1, a1 * e1))
                        vc.put(mtnPt(u1, f0), uTex1, v0, withAlpha(c0, a0 * e1))
                    }
                }
            }
            mtnSheet(AriusLayers.INK, 0.0, 0.14, withAlpha(NAVY_DARK, bladeFade * 0.55f), withAlpha(NAVY_MID, 0f))
            mtnSheet(AriusLayers.VFX_GLOW, 0.0, 0.44, withAlpha(ICE_LIGHT, bladeFade * 0.88f), withAlpha(ICE_BLUE, bladeFade * 0.42f))
            mtnSheet(AriusLayers.VFX_GLOW, 0.40, 1.0, withAlpha(ICE_BLUE, bladeFade * 0.44f), withAlpha(ICE_BLUE, 0f))
            mtnSheet(AriusLayers.VFX_ADD, 0.0, 0.24, withAlpha(PURE_WHITE, bladeFade * 0.96f), withAlpha(FROST_WHITE, 0f))

            // Heller Einschlagspunkt exakt dort, wo die Klinge auf den Bergrücken trifft.
            val impactPt = Vec3d(headBase.x, ridgeHeight(sweep.toDouble()), headBase.z)
            d.billboard(AriusLayers.VFX_ADD_FLARE, impactPt, 30.0 * bladeFade, withAlpha(PURE_WHITE, bladeFade))
            d.billboard(AriusLayers.VFX_GLOW, impactPt, 48.0 * bladeFade, withAlpha(ICE_LIGHT, bladeFade * 0.65f))
        }

        // 2) Sauberes Nachglühen exakt entlang der frisch gespaltenen Bergkontur (folgt dem Profil, nie quer in den Himmel).
        val glowOut = (1f - ((t - cleaveTicks) / (life - cleaveTicks).coerceAtLeast(1f)).coerceIn(0f, 1f)).let { it * it } * fx
        if (glowOut > 0.015f && sweep > 0.03f) {
            val pts = ArrayList<Vec3d>(25)
            val steps = 24
            for (i in 0..steps) {
                val u = (i.toDouble() / steps) * sweep
                val p = origin.add(forward.multiply(totalZ * u))
                pts.add(Vec3d(p.x, ridgeHeight(u), p.z))
            }
            d.ribbon(AriusLayers.INK, pts, { q -> (1.6 * glowOut + 0.25) * sin(q * PI).coerceAtLeast(0.15) },
                { q -> withAlpha(NAVY_DARK, glowOut * 0.58f * sin(q * PI).toFloat().coerceAtLeast(0.1f)) }, uScale = 0f, uOffset = 0.5f)
            d.ribbon(AriusLayers.VFX_GLOW, pts, { q -> (4.4 * glowOut + 0.6) * sin(q * PI).coerceAtLeast(0.15) },
                { q -> withAlpha(ICE_BLUE, glowOut * 0.82f * sin(q * PI).toFloat().coerceAtLeast(0.1f)) }, uScale = 0f, uOffset = 0.5f)
            d.ribbon(AriusLayers.VFX_ADD, pts, { q -> (1.25 * glowOut + 0.18) * sin(q * PI).coerceAtLeast(0.15) },
                { q -> withAlpha(PURE_WHITE, glowOut * 0.95f * sin(q * PI).toFloat().coerceAtLeast(0.1f)) }, uScale = 0f, uOffset = 0.5f)
        }
    }

    private companion object {
        const val PURE_WHITE = 0xFFFFFFFF.toInt()
        const val FROST_WHITE = 0xFFF2FBFF.toInt()
        const val ICE_LIGHT = 0xFF9BE4FF.toInt()
        const val ICE_BLUE = 0xFF42A8EA.toInt()
        const val NAVY_MID = 0xFF143C66.toInt()
        const val NAVY_DARK = 0xFF0A2342.toInt()
    }
}

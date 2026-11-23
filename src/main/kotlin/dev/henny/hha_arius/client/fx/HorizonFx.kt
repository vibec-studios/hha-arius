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
 * - `phase == 0` (Aufladen): Licht zieht von links und rechts in EINE schlanke senkrechte Frostlinie zusammen, die vom
 *   Himmel durch Arius' erhobene Klinge und entlang der exakten Schnittebene (`x = CUT_X`) bis an den Berg läuft; in
 *   den letzten Ticks (Stille-Beat) zieht sich die Linie messerscharf zusammen.
 * - `phase == 1` (Niederschlag): Ein gewaltiger, messerscharf zugespitzter senkrechter Abwärts-Schnittbogen exakt in
 *   der Ebene `x = CUT_X`, der von oben herabfährt und nach Süden durch den Tempel auf den Berg zurast.
 * - `phase == 2` (Bergspaltung): Die senkrechte Lichtklinge fährt vom Himmel herab durch das Massiv und öffnet den
 *   Berg sichtbar entlang derselben Ebene — kein Kastenband, kein Grauschleier, kein Partikelrauschen.
 */
class VerticalHorizonFx(
    private val origin: Vec3d,
    direction: Vec3d,
    private val reach: Double,
    private val phase: Int,
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

    override val persistent: Boolean get() = phase == 2

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

    /** Zeichnet eine weich zu beiden Enden zugespitzte Linie (nie stumpfe Balken). */
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
        if (baseA <= 0.01f) return
        val vc = d.buffer(layer)
        with(d) {
            for (i in 0 until segments) {
                val u0 = i / segments.toDouble()
                val u1 = (i + 1) / segments.toDouble()
                val w0 = if (endFade) sin(u0 * PI).coerceAtLeast(0.0) else 1.0
                val w1 = if (endFade) sin(u1 * PI).coerceAtLeast(0.0) else 1.0
                val p0 = from.lerp(to, u0)
                val p1 = from.lerp(to, u1)
                val h0 = halfWidth * (0.18 + 0.82 * w0)
                val h1 = halfWidth * (0.18 + 0.82 * w1)
                val c0 = withAlpha(color, baseA * (if (endFade) w0.toFloat() else 1f))
                val c1 = withAlpha(color, baseA * (if (endFade) w1.toFloat() else 1f))
                vc.put(p0.add(side.multiply(-h0)), 0f, 0f, c0)
                vc.put(p0.add(side.multiply(h0)), 0f, 1f, c0)
                vc.put(p1.add(side.multiply(h1)), 1f, 1f, c1)
                vc.put(p1.add(side.multiply(-h1)), 1f, 0f, c1)
            }
        }
    }

    private fun renderVerticalCharge(d: VfxDraw, t: Float, fx: Float) {
        if (owner?.isRemoved == true) return
        val progress = (t / life.coerceAtLeast(1)).coerceIn(0f, 1f)
        val silenceStart = (life - 12f).coerceAtLeast(life * 0.72f)
        val inSilence = ((t - silenceStart) / (life - silenceStart).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val build = progress * progress
        val intro = (t / 6f).coerceIn(0f, 1f)
        val aScale = intro * fx

        val anchor = BladeAnchors.of(owner)
        val ownerPos = owner?.getLerpedPos(d.tickDelta) ?: origin
        val base = anchor?.base ?: ownerPos.add(0.0, 2.35, 0.0)
        val tip = anchor?.tip ?: ownerPos.add(0.0, 4.05, 0.0)
        val bladeMid = base.lerp(tip, 0.55)
        // Alle Elemente liegen strikt auf der exakten Schnittebene x = origin.x.
        val planeX = origin.x
        val lineBase = Vec3d(planeX, origin.y - 0.5, origin.z)
        val skyTop = Vec3d(planeX, origin.y + 170.0, origin.z + 6.0)
        val skyMid = Vec3d(planeX, origin.y + 38.0, origin.z + 1.5)
        val vSide = camBillSide(d, UP)

        // 1) Einziehende Frostfäden von links (-x) und rechts (+x) direkt in die EINE senkrechte Linie (im Stille-Beat verhallend).
        val ribFade = (1f - inSilence * 0.92f).coerceIn(0f, 1f)
        if (ribFade > 0.03f) {
            val pullX = (9.0 * (1.0 - 0.62 * build)).coerceAtLeast(2.0)
            for (i in 0 until 8) {
                val sideSign = if (i % 2 == 0) 1.0 else -1.0
                val tier = i / 2
                val ph = ((t * 0.055f + tier * 0.25f + (if (sideSign > 0) 0f else 0.12f)) % 1f)
                val tailP = (ph - 0.36f).coerceAtLeast(0f)
                val yLevel = origin.y + 2.2 + tier * 3.8
                val zOff = (tier - 1) * 1.4
                val startPt = Vec3d(planeX + sideSign * pullX * (0.8 + tier * 0.12), yLevel + 1.8 * (1.0 - build), origin.z + zOff)
                val ctrlPt = Vec3d(planeX + sideSign * pullX * 0.35, yLevel + 0.5, origin.z + zOff * 0.4)
                val targetPt = Vec3d(planeX, yLevel, origin.z + 0.3)
                fun bez(s: Double): Vec3d {
                    val inv = 1.0 - s
                    return startPt.multiply(inv * inv).add(ctrlPt.multiply(2.0 * inv * s)).add(targetPt.multiply(s * s))
                }
                val pts = List(7) { k -> bez(tailP + (ph - tailP) * (k / 6.0)) }
                val ribA = aScale * ribFade * (0.35f + 0.65f * progress) * sin(ph * PI).toFloat().coerceIn(0f, 1f)
                if (ribA > 0.02f) {
                    d.ribbon(AriusLayers.INK, pts, { q -> 0.075 + 0.09 * q }, { q -> withAlpha(NAVY_DARK, ribA * 0.65f * q) }, uScale = 0f, uOffset = 0.5f)
                    d.ribbon(AriusLayers.VFX_GLOW, pts, { q -> 0.045 + 0.075 * q }, { q -> withAlpha(if (q > 0.65f) FROST_WHITE else ICE_BLUE, ribA * (0.45f + 0.55f * q)) }, uScale = 0f, uOffset = 0.5f)
                }
            }
        }

        // 2) DIE EINE dünne, messerscharfe SENKRECHTE Frostlinie vom Himmel durch Arius' erhobene Klinge bis auf den Boden.
        //    Wichtig: NICHT mit sin(u*PI) über 170 Blöcke ausblenden (sonst wäre u ≈ 0.02 auf Augenhöhe fast unsichtbar!).
        //    Stattdessen volle Schärfe vom Boden (origin.y + 0.05) über Klinge & Halle bis hoch in den Himmel, erst ganz oben weich auslaufend.
        fun drawSkyBeam(
            from: Vec3d,
            to: Vec3d,
            side: Vec3d,
            halfWidth: Double,
            layer: net.minecraft.client.render.RenderLayer,
            color: Int,
            segments: Int = 28,
        ) {
            val baseA = (color ushr 24) / 255f
            if (baseA <= 0.01f) return
            val vc = d.buffer(layer)
            fun profile(u: Double): Double = when {
                u < 0.008 -> (u / 0.008).coerceIn(0.25, 1.0)
                u > 0.78 -> ((1.0 - u) / 0.22).coerceIn(0.0, 1.0)
                else -> 1.0
            }
            with(d) {
                for (i in 0 until segments) {
                    val u0 = i / segments.toDouble()
                    val u1 = (i + 1) / segments.toDouble()
                    val w0 = profile(u0)
                    val w1 = profile(u1)
                    val p0 = from.lerp(to, u0)
                    val p1 = from.lerp(to, u1)
                    val h0 = halfWidth * (0.20 + 0.80 * w0)
                    val h1 = halfWidth * (0.20 + 0.80 * w1)
                    val c0 = withAlpha(color, baseA * w0.toFloat())
                    val c1 = withAlpha(color, baseA * w1.toFloat())
                    vc.put(p0.add(side.multiply(-h0)), 0f, 0f, c0)
                    vc.put(p0.add(side.multiply(h0)), 0f, 1f, c0)
                    vc.put(p1.add(side.multiply(h1)), 1f, 1f, c1)
                    vc.put(p1.add(side.multiply(-h1)), 1f, 0f, c1)
                }
            }
        }

        val tighten = 1.0 - 0.40 * inSilence
        val lineA = aScale * (0.68f + 0.32f * progress)
        val coreW = (0.11 + 0.06 * build) * tighten
        val haloW = (0.34 + 0.14 * build) * tighten
        val inkW = (0.23 + 0.09 * build) * tighten
        // Leicht vor Arius' Klingenebene auf x = CUT_X vom Hallenboden gerade durch die Klinge hoch in den Himmel.
        val floorLineBase = Vec3d(planeX, origin.y + 0.05, origin.z + 0.15)
        val skyLineTop = Vec3d(planeX, origin.y + 170.0, origin.z + 0.15)
        // Tief-Marineblau-Kontrastkante macht die helle Frostlinie auch vor weißem Quarz/Eis und Schnee glasklar lesbar.
        drawSkyBeam(floorLineBase, skyLineTop, vSide, inkW, AriusLayers.INK, withAlpha(NAVY_DARK, lineA * 0.90f), segments = 28)
        drawSkyBeam(floorLineBase, skyLineTop, vSide, haloW, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, lineA * 0.92f), segments = 28)
        drawSkyBeam(floorLineBase, skyLineTop, vSide, coreW * 1.55, AriusLayers.VFX_GLOW, withAlpha(FROST_WHITE, lineA), segments = 28)
        drawSkyBeam(floorLineBase, skyLineTop, vSide, coreW, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, lineA), segments = 28)

        // Feine Boden-Leitlinie exakt in der Schnittebene (x = CUT_X) nach Süden Richtung Berg (volle Sichtbarkeit im Gang).
        val pathLen = (reach * (0.45 + 0.55 * progress)).coerceAtLeast(22.0)
        val pathFrom = Vec3d(planeX, origin.y + 0.12, origin.z)
        val pathTo = Vec3d(planeX, origin.y + 0.12, origin.z + pathLen)
        val pathSide = camBillSide(d, forward)
        drawSkyBeam(pathFrom, pathTo, pathSide, 0.22 * tighten, AriusLayers.INK, withAlpha(NAVY_DARK, lineA * 0.85f), segments = 20)
        drawSkyBeam(pathFrom, pathTo, pathSide, 0.32 * tighten, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, lineA * 0.88f), segments = 20)
        drawSkyBeam(pathFrom, pathTo, pathSide, 0.11 * tighten, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, lineA), segments = 20)

        // Senkrechte Himmelslinie über dem fernen Berg (exakt auf x = CUT_X), die das Ziel bereits markiert.
        val mtnFoot = Vec3d(planeX, origin.y + 18.0, origin.z + 235.0)
        val mtnSky = Vec3d(planeX, origin.y + 270.0, origin.z + 315.0)
        val mtnA = lineA * (0.45f + 0.55f * build)
        drawSkyBeam(mtnFoot, mtnSky, vSide, 0.95 * tighten, AriusLayers.INK, withAlpha(NAVY_DARK, mtnA * 0.72f), segments = 16)
        drawSkyBeam(mtnFoot, mtnSky, vSide, 1.85 * tighten, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, mtnA * 0.85f), segments = 16)
        drawSkyBeam(mtnFoot, mtnSky, vSide, 0.60 * tighten, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, mtnA), segments = 16)

        // 3) Klingen-Kern und hochgezogener vertikaler Lichtstern an der gerade nach oben gerichteten Klinge.
        val bladeSide = camBillSide(d, tip.subtract(base))
        val edgeA = aScale * (0.72f + 0.28f * progress)
        d.band(AriusLayers.INK, base, tip, bladeSide, 0.15 + 0.08 * build, withAlpha(NAVY_DARK, edgeA * 0.88f), withAlpha(NAVY_MID, edgeA * 0.88f))
        d.band(AriusLayers.VFX_GLOW, base, tip, bladeSide, 0.12 + 0.10 * build, withAlpha(ICE_BLUE, edgeA * 0.94f), withAlpha(FROST_WHITE, edgeA))
        d.band(AriusLayers.VFX_ADD, base, tip, bladeSide, 0.06 + 0.05 * build, withAlpha(PURE_WHITE, edgeA), withAlpha(PURE_WHITE, edgeA))

        val gleamH = 2.4 + 7.5 * build * tighten
        drawTaperedLine(d, bladeMid.add(0.0, -gleamH * 0.45, 0.0), bladeMid.add(0.0, gleamH, 0.0), vSide, 0.12 * tighten, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, edgeA), segments = 12)
        d.billboard(AriusLayers.VFX_ADD_FLARE, tip, (1.1 + 2.6 * build) * (1.0 + 0.50 * inSilence), withAlpha(PURE_WHITE, edgeA))
        d.billboard(AriusLayers.VFX_GLOW, bladeMid, (1.6 + 2.8 * build) * tighten, withAlpha(ICE_LIGHT, edgeA * 0.58f))
        // Hoher Himmels-Scheitelpunkt der Linie
        if (build > 0.25f) {
            d.billboard(AriusLayers.VFX_GLOW, skyMid, 3.5 * build * tighten, withAlpha(ICE_LIGHT, aScale * 0.35f * build))
        }
    }

    private fun renderDownwardSlash(d: VfxDraw, t: Float, fx: Float) {
        val progress = (t / life.coerceAtLeast(1)).coerceIn(0f, 1f)
        // Öffnet sofort in den ersten Ticks von oben nach unten und fährt lesbar entlang x = CUT_X durch Halle & Plateau.
        val open = ((t + 0.85f) / 2.6f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
        val maxTravel = reach.coerceAtLeast(36.0)
        // Sanfter Anlauf (t=0..2 direkt an der Klinge, t=3..8 mitten durch den Tempel, danach rasch hinaus zum Berg).
        val travel = if (maxTravel > 90.0) {
            maxTravel * (1.0 - kotlin.math.exp(-t * 0.095))
        } else {
            val accel = (t * t / (t + 5.0)).toDouble()
            (accel * (maxTravel / 18.0)).coerceAtMost(maxTravel)
        }
        val planeX = origin.x
        // Senkt sich in den ersten 4 Ticks sichtbar von über dem Kopf bis auf Bodenhöhe ab (echter Abwärtsschnitt).
        val descend = (1.0 - (t / 4.0).coerceIn(0.0, 1.0)).let { it * it }
        val center = Vec3d(planeX, origin.y + 5.2 + 5.5 * descend, origin.z).add(forward.multiply(travel))

        val peakBoost = (1f - (t / 5.0f).coerceIn(0f, 1f)).let { it * it }
        val bladeFade = (1f - ((t - 5.0f) / 22.0f).coerceIn(0f, 1f)).let { it * it } * fx
        val halfHeight = 15.5 * (0.76 + 0.34 * open)
        val depth = (4.2 + 2.8 * open) * (0.88 + 0.22 * (1f - progress))
        val bow = 3.8 * (0.72 + 0.28 * open)

        // Senkrechter Bogen in der YZ-Ebene (x = planeX): u = 0 unten (-y), u = 0.5 vorn (+z), u = 1 oben (+y).
        fun arcPt(u: Double, back: Double, xOff: Double = 0.0): Vec3d {
            val arch = sin(u * PI)
            val taper = tipTaper(u)
            val yCoord = ((2.0 * u - 1.0) * halfHeight).coerceAtLeast(-5.0 - 5.5 * descend)
            return Vec3d(
                center.x + xOff,
                center.y + yCoord,
                center.z + arch * bow - back * depth * (0.10 + 0.90 * taper),
            )
        }

        if (bladeFade > 0.015f) {
            // 1) Breiter senkrechter Sichelkörper in der YZ-Schnittebene (sichtbar von Seite, Schrägansicht & 3rd Person).
            fun vSheet(vc: net.minecraft.client.render.VertexConsumer, from: Double, to: Double, outerCol: Int, innerCol: Int, xShift: Double = 0.0) {
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
                        vc.put(arcPt(u0, from, xShift), 0f, 0f, cO0)
                        vc.put(arcPt(u0, to, xShift), 0f, 1f, cI0)
                        vc.put(arcPt(u1, to, xShift), 1f, 1f, cI1)
                        vc.put(arcPt(u1, from, xShift), 1f, 0f, cO1)
                    }
                }
            }
            vSheet(d.buffer(AriusLayers.INK), 0.0, 0.16, withAlpha(NAVY_DARK, bladeFade * 0.62f), withAlpha(NAVY_MID, 0f), 0.02)
            vSheet(d.buffer(AriusLayers.VFX_GLOW), 0.03, 0.36, withAlpha(ICE_LIGHT, bladeFade * 0.90f), withAlpha(ICE_BLUE, bladeFade * 0.56f))
            vSheet(d.buffer(AriusLayers.VFX_GLOW), 0.36, 0.96, withAlpha(ICE_BLUE, bladeFade * 0.56f), withAlpha(ICE_BLUE, 0f))
            vSheet(d.buffer(AriusLayers.VFX_ADD), 0.0, 0.18, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * (0.75f + 0.25f * peakBoost)))
            vSheet(d.buffer(AriusLayers.VFX_ADD), 0.18, 0.56, withAlpha(ICE_LIGHT, bladeFade * (0.55f + 0.35f * peakBoost)), withAlpha(ICE_BLUE, 0f))

            // 2) Scharfe linsenförmige X-Dicke an der führenden Schneide (unmissverständlich auch frontal aus 1st Person!).
            val coreX = 0.22 * (0.55 + 0.45 * (1f - progress * 0.5f)) + 0.12 * peakBoost
            val haloX = coreX * 2.6
            val inkX = coreX * 1.35

            fun xRazor(vc: net.minecraft.client.render.VertexConsumer, back: Double, x0: Double, x1: Double, c0: Int, c1: Int) {
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
                        vc.put(arcPt(u0, back, x0 * t0), 0f, 0f, withAlpha(c0, a0 * e0))
                        vc.put(arcPt(u0, back, x1 * t0), 0f, 1f, withAlpha(c1, a1 * e0))
                        vc.put(arcPt(u1, back, x1 * t1), 1f, 1f, withAlpha(c1, a1 * e1))
                        vc.put(arcPt(u1, back, x0 * t1), 1f, 0f, withAlpha(c0, a0 * e1))
                    }
                }
            }
            for (sg in intArrayOf(-1, 1)) {
                xRazor(d.buffer(AriusLayers.INK), 0.06, sg * coreX * 0.72, sg * inkX * 1.08, withAlpha(NAVY_DARK, bladeFade * 0.85f), withAlpha(NAVY_DARK, 0f))
                xRazor(d.buffer(AriusLayers.VFX_GLOW), 0.045, sg * coreX * 0.4, sg * haloX, withAlpha(ICE_BLUE, bladeFade * 0.84f), withAlpha(ICE_BLUE, 0f))
                xRazor(d.buffer(AriusLayers.VFX_ADD), 0.025, 0.0, sg * haloX * 0.78, withAlpha(ICE_LIGHT, bladeFade * (0.58f + 0.34f * peakBoost)), withAlpha(ICE_BLUE, 0f))
                xRazor(d.buffer(AriusLayers.VFX_GLOW), 0.015, 0.0, sg * coreX, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, 0f))
                xRazor(d.buffer(AriusLayers.VFX_ADD), 0.005, 0.0, sg * coreX * 0.9, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * 0.25f))
            }
        }

        // 3) Senkrechte Restspur entlang der aufgeschnittenen Luft (x = CUT_X) + gleißender Kernblitz.
        if (t < 6f) {
            val flareF = (1f - t / 6f).let { it * it } * fx
            val mid = arcPt(0.5, 0.0)
            d.billboard(AriusLayers.VFX_ADD_FLARE, mid, (5.5 + 3.5 * open) * flareF, withAlpha(PURE_WHITE, flareF))
            d.billboard(AriusLayers.VFX_GLOW, mid, (4.0 + 2.8 * open) * flareF, withAlpha(ICE_LIGHT, flareF * 0.72f))
        }
        val wakeIn = ((t - 1.0f) / 2.5f).coerceIn(0f, 1f)
        val wakeOut = (1f - ((t - 10f) / (life - 10f).coerceAtLeast(1f)).coerceIn(0f, 1f))
        val wakeA = wakeIn * wakeOut * wakeOut * fx
        if (wakeA > 0.015f) {
            val seamFrom = Vec3d(planeX, origin.y + 0.18, origin.z)
            val seamTo = Vec3d(planeX, origin.y + 0.18, origin.z + travel + 3.0)
            val pSide = camBillSide(d, forward)
            drawTaperedLine(d, seamFrom, seamTo, pSide, 0.14 * wakeOut + 0.02, AriusLayers.INK, withAlpha(NAVY_DARK, wakeA * 0.62f), segments = 18)
            drawTaperedLine(d, seamFrom, seamTo, pSide, 0.24 * wakeOut + 0.03, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, wakeA * 0.80f), segments = 18)
            drawTaperedLine(d, seamFrom, seamTo, pSide, 0.09 * wakeOut + 0.015, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, wakeA), segments = 18)

            // Schlanke senkrechte Lichtnaht über dem Ursprung, die noch kurz nachglüht und ausklingt.
            val vSide = camBillSide(d, UP)
            val vFrom = Vec3d(planeX, origin.y + 0.2, origin.z + (travel * 0.45).coerceAtMost(14.0))
            val vTo = vFrom.add(0.0, 26.0 * (0.4 + 0.6 * wakeOut), 0.0)
            drawTaperedLine(d, vFrom, vTo, vSide, 0.16 * wakeOut, AriusLayers.VFX_GLOW, withAlpha(ICE_LIGHT, wakeA * 0.65f), segments = 14)
            drawTaperedLine(d, vFrom, vTo, vSide, 0.06 * wakeOut, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, wakeA * 0.90f), segments = 14)

            // Schlanke Eissplitter, die sich sauber nach links (-x) und rechts (+x) von der Schnittlinie lösen.
            val drift = ((t - 1.5f) / (life - 1.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
            for (s in shards) {
                val basePos = Vec3d(
                    planeX + s.side * (0.18 + s.driftOut * drift * 1.6),
                    origin.y + 0.8 + s.u * 11.0 + s.driftUp * drift * 1.1,
                    origin.z + s.along * travel,
                )
                val ang = t * s.spin + s.u * 9.0
                val sz = s.size * (0.5 + 0.5 * wakeOut)
                val ax = UP.multiply(cos(ang) * sz * 2.5).add(forward.multiply(sin(ang) * sz * 0.8))
                val ay = across.multiply(sz * 0.28)
                val az = forward.multiply(cos(ang) * sz * 0.25).add(UP.multiply(-sin(ang) * sz * 0.15))
                d.box(AriusLayers.VFX_SHARD, basePos, ax, ay, az, withAlpha(if (s.dark) ICE_BLUE else FROST_WHITE, wakeA * 0.9f))
                d.billboard(AriusLayers.VFX_ADD_FLARE, basePos, sz * 3.5, withAlpha(PURE_WHITE, wakeA * 0.6f))
            }
        }
    }

    /**
     * Bergspaltung entlang x = CUT_X (`origin` = Bergfuß auf CUT_X, `reach` = Gesamtlänge bis hinter das Horn):
     * Eine kolossale, sauber zugespitzte senkrechte Frostklinge fährt synchron zu `splitFull` (24 Ticks) von vorn nach
     * hinten durch den gesamten Berg und hinterlässt eine reine, ausblendende Frost-Schnittnaht (ohne Kastenwände/Dunst).
     */
    private fun renderMountainCleave(d: VfxDraw, t: Float, fx: Float) {
        val cleaveTicks = 26f
        val sweep = (t / cleaveTicks).coerceIn(0f, 1f)
        val planeX = origin.x
        val totalZ = reach.coerceAtLeast(180.0)
        val headZ = origin.z + totalZ * sweep
        val vSide = camBillSide(d, UP)

        // 1) Fortschreitende senkrechte Lichtklinge, die den Berg vom Himmel bis zum Fuß durchtrennt.
        val bladeFade = (1f - ((t - cleaveTicks + 2f) / 16f).coerceIn(0f, 1f)).let { it * it } * fx
        if (bladeFade > 0.015f) {
            val topY = origin.y + 300.0
            val botY = origin.y - 4.0
            val headTop = Vec3d(planeX, topY, headZ + 14.0)
            val headBot = Vec3d(planeX, botY, headZ)

            // Scharfe, zugespitzte Hauptschneide vom Himmel bis zum Bergfuß (auch aus 280 Blöcken Entfernung klar lesbar).
            drawTaperedLine(d, headBot, headTop, vSide, 4.2, AriusLayers.INK, withAlpha(NAVY_DARK, bladeFade * 0.78f), segments = 28)
            drawTaperedLine(d, headBot, headTop, vSide, 9.5, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, bladeFade * 0.90f), segments = 28)
            drawTaperedLine(d, headBot, headTop, vSide, 4.0, AriusLayers.VFX_GLOW, withAlpha(FROST_WHITE, bladeFade), segments = 28)
            drawTaperedLine(d, headBot, headTop, vSide, 2.4, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, bladeFade), segments = 28)

            // Geformter senkrechter Sichelbogen in der YZ-Ebene hinter der vorderen Schneide (keine Rechteckwand!).
            val arcBack = 78.0
            fun mtnPt(u: Double, back: Double): Vec3d {
                val tp = tipTaper(u)
                val y = botY + (topY - botY) * u
                val bow = sin(u * PI) * 20.0
                return Vec3d(planeX, y, headZ + bow - back * arcBack * (0.08 + 0.92 * tp))
            }
            fun mtnSheet(vc: net.minecraft.client.render.VertexConsumer, f0: Double, f1: Double, c0: Int, c1: Int) {
                val a0 = (c0 ushr 24) / 255f
                val a1 = (c1 ushr 24) / 255f
                with(d) {
                    for (i in 0 until 32) {
                        val u0 = i / 32.0
                        val u1 = (i + 1) / 32.0
                        val e0 = sin(u0 * PI).toFloat()
                        val e1 = sin(u1 * PI).toFloat()
                        vc.put(mtnPt(u0, f0), 0f, 0f, withAlpha(c0, a0 * e0))
                        vc.put(mtnPt(u0, f1), 0f, 1f, withAlpha(c1, a1 * e0))
                        vc.put(mtnPt(u1, f1), 1f, 1f, withAlpha(c1, a1 * e1))
                        vc.put(mtnPt(u1, f0), 1f, 0f, withAlpha(c0, a0 * e1))
                    }
                }
            }
            mtnSheet(d.buffer(AriusLayers.INK), 0.0, 0.14, withAlpha(NAVY_DARK, bladeFade * 0.55f), withAlpha(NAVY_MID, 0f))
            mtnSheet(d.buffer(AriusLayers.VFX_GLOW), 0.0, 0.42, withAlpha(ICE_LIGHT, bladeFade * 0.84f), withAlpha(ICE_BLUE, bladeFade * 0.42f))
            mtnSheet(d.buffer(AriusLayers.VFX_GLOW), 0.42, 1.0, withAlpha(ICE_BLUE, bladeFade * 0.42f), withAlpha(ICE_BLUE, 0f))
            mtnSheet(d.buffer(AriusLayers.VFX_ADD), 0.0, 0.24, withAlpha(PURE_WHITE, bladeFade * 0.96f), withAlpha(FROST_WHITE, 0f))

            // Heller Einschlagspunkt, wo die Klinge auf den Bergrücken trifft.
            val ridgeY = origin.y + (20.0 + 185.0 * sweep).coerceAtMost(220.0)
            val impactPt = Vec3d(planeX, ridgeY, headZ)
            d.billboard(AriusLayers.VFX_ADD_FLARE, impactPt, 28.0 * bladeFade, withAlpha(PURE_WHITE, bladeFade))
            d.billboard(AriusLayers.VFX_GLOW, impactPt, 44.0 * bladeFade, withAlpha(ICE_LIGHT, bladeFade * 0.62f))
        }

        // 2) Sauberes Nachglühen entlang des frisch geöffneten Spalts (klingt weich aus).
        val glowOut = (1f - ((t - cleaveTicks) / (life - cleaveTicks).coerceAtLeast(1f)).coerceIn(0f, 1f)).let { it * it } * fx
        if (glowOut > 0.015f && sweep > 0.03f) {
            val ridgeStart = Vec3d(planeX, origin.y + 10.0, origin.z)
            val ridgeEnd = Vec3d(planeX, origin.y + (20.0 + 205.0 * sweep).coerceAtMost(225.0), headZ)
            val rSide = camBillSide(d, ridgeEnd.subtract(ridgeStart).normalize())
            drawTaperedLine(d, ridgeStart, ridgeEnd, rSide, 1.4 * glowOut + 0.2, AriusLayers.INK, withAlpha(NAVY_DARK, glowOut * 0.60f), segments = 24)
            drawTaperedLine(d, ridgeStart, ridgeEnd, rSide, 3.4 * glowOut + 0.4, AriusLayers.VFX_GLOW, withAlpha(ICE_BLUE, glowOut * 0.78f), segments = 24)
            drawTaperedLine(d, ridgeStart, ridgeEnd, rSide, 1.1 * glowOut + 0.15, AriusLayers.VFX_ADD, withAlpha(PURE_WHITE, glowOut * 0.95f), segments = 24)
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

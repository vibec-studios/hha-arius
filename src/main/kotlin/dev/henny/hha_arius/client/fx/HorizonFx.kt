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
        val inkVc = d.buffer(AriusLayers.INK)
        val glowVc = d.buffer(AriusLayers.VFX_GLOW)
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
            drawTapered(inkVc, 0.11 + 0.06 * build, withAlpha(NAVY_DARK, bandA * 0.80f))
            drawTapered(glowVc, 0.055 + 0.05 * build, withAlpha(FROST_WHITE, bandA * 0.92f))
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
            val inkVc = d.buffer(AriusLayers.INK)
            val glowVc = d.buffer(AriusLayers.VFX_GLOW)
            val addVc = d.buffer(AriusLayers.VFX_ADD)

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
            hStrip(inkVc, 0.0, 0.14, withAlpha(NAVY_DARK, bladeFade * 0.58f), withAlpha(NAVY_MID, 0f), -0.02)
            // Soft ice-blue falloff wake
            hStrip(glowVc, 0.04, 0.34, withAlpha(ICE_LIGHT, bladeFade * 0.88f), withAlpha(ICE_BLUE, bladeFade * 0.55f))
            hStrip(glowVc, 0.34, 0.95, withAlpha(ICE_BLUE, bladeFade * 0.55f), withAlpha(ICE_BLUE, 0f))
            // Brilliant additive near-white leading blade core
            hStrip(addVc, 0.0, 0.16, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * (0.75f + 0.25f * peakBoost)))
            hStrip(addVc, 0.16, 0.52, withAlpha(ICE_LIGHT, bladeFade * (0.55f + 0.35f * peakBoost)), withAlpha(ICE_BLUE, 0f))

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
            vRazor(inkVc, 0.07, coreH * 0.72, contrastH * 1.08, withAlpha(NAVY_DARK, bladeFade * 0.84f), withAlpha(NAVY_DARK, 0f))
            vRazor(inkVc, 0.07, -coreH * 0.72, -contrastH * 1.08, withAlpha(NAVY_DARK, bladeFade * 0.84f), withAlpha(NAVY_DARK, 0f))

            // Soft ice-blue falloff halo (alpha-glow in rich ice blue for white-snow readability + additive light bloom)
            vRazor(glowVc, 0.055, coreH * 0.45, falloffH, withAlpha(ICE_BLUE, bladeFade * 0.82f), withAlpha(ICE_BLUE, 0f))
            vRazor(glowVc, 0.055, -coreH * 0.45, -falloffH, withAlpha(ICE_BLUE, bladeFade * 0.82f), withAlpha(ICE_BLUE, 0f))
            vRazor(glowVc, 0.045, 0.0, falloffH * 0.78, withAlpha(ICE_LIGHT, bladeFade * 0.78f), withAlpha(ICE_BLUE, 0f))
            vRazor(glowVc, 0.045, 0.0, -falloffH * 0.78, withAlpha(ICE_LIGHT, bladeFade * 0.78f), withAlpha(ICE_BLUE, 0f))
            vRazor(addVc, 0.035, 0.0, falloffH * 0.80, withAlpha(ICE_LIGHT, bladeFade * (0.55f + 0.35f * peakBoost)), withAlpha(ICE_BLUE, 0f))
            vRazor(addVc, 0.035, 0.0, -falloffH * 0.80, withAlpha(ICE_LIGHT, bladeFade * (0.55f + 0.35f * peakBoost)), withAlpha(ICE_BLUE, 0f))

            // Blinding near-white razor core (double-layered additive + glow so it is the brightest thing on screen!)
            vRazor(glowVc, 0.02, 0.0, coreH, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, 0f))
            vRazor(glowVc, 0.02, 0.0, -coreH, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, 0f))
            vRazor(addVc, 0.01, 0.0, coreH * 0.92, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * 0.25f))
            vRazor(addVc, 0.01, 0.0, -coreH * 0.92, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * 0.25f))
            // Peak central incandescence on ticks 0..4
            if (peakBoost > 0.02f) {
                vRazor(addVc, 0.0, 0.0, coreH * 0.52, withAlpha(PURE_WHITE, peakBoost * fx), withAlpha(PURE_WHITE, 0f))
                vRazor(addVc, 0.0, 0.0, -coreH * 0.52, withAlpha(PURE_WHITE, peakBoost * fx), withAlpha(PURE_WHITE, 0f))
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

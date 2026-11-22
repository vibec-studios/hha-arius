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
        val open = (t / 3.5f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }

        // Smooth sweep across the room/arena, lingering clearly across the view before dissipating.
        val maxTravel = if (temple) 15.5 else 7.2
        val travelFrac = (1.0 - kotlin.math.exp(-t * (if (temple) 0.16 else 0.24)))
        val travel = maxTravel * travelFrac
        val center = origin.add(forward.multiply(travel))

        // Main slash blade burns bright for ~8 ticks, then fades cleanly over ticks 8..22 while shards linger to `life`.
        val bladeFade = (1f - ((t - 5f) / (life * 0.58f)).coerceIn(0f, 1f)).let { it * it } * fx
        val span = width * (0.58 + 0.54 * open)
        val depth = (if (temple) 3.4 + 1.8 * open else 2.2 + 1.4 * open) * (0.85 + 0.25 * (1f - progress))
        val bow = (if (temple) 2.2 else 1.55) * (0.65 + 0.35 * open)

        fun arcPoint(u: Double, back: Double, yOff: Double = 0.0): Vec3d {
            val arch = sin(u * PI)
            return center
                .add(across.multiply((2.0 * u - 1.0) * span))
                .add(forward.multiply(arch * bow - back * depth * (0.45 + 0.55 * arch)))
                .add(0.0, yOff + 0.14 * sin(back * PI) * arch, 0.0)
        }

        if (bladeFade > 0.015f) {
            // 1) Broad Horizontal Swept Crescent (deep-navy contrast backing + bright frost-white core + soft ice-blue wake).
            val inkVc = d.buffer(AriusLayers.INK)
            val glowVc = d.buffer(AriusLayers.VFX_GLOW)
            val flowVc = d.buffer(AriusLayers.VFX_FLOW)

            fun hStrip(vc: net.minecraft.client.render.VertexConsumer, from: Double, to: Double, outer: Int, inner: Int, yShift: Double = 0.0, uScroll: Float = 0f) {
                with(d) {
                    for (i in 0 until 40) {
                        val a = i / 40.0
                        val b = (i + 1) / 40.0
                        vc.put(arcPoint(a, from, yShift), a.toFloat() * 2f + uScroll, 0f, outer)
                        vc.put(arcPoint(a, to, yShift), a.toFloat() * 2f + uScroll, 1f, inner)
                        vc.put(arcPoint(b, to, yShift), b.toFloat() * 2f + uScroll, 1f, inner)
                        vc.put(arcPoint(b, from, yShift), b.toFloat() * 2f + uScroll, 0f, outer)
                    }
                }
            }

            // Deep navy contrast edge & under-backing so the slash pops on pure white snow and in storms.
            hStrip(inkVc, -0.05, 0.12, withAlpha(NAVY_DARK, 0f), withAlpha(NAVY_DARK, bladeFade * 0.92f), -0.04)
            hStrip(inkVc, 0.12, 0.62, withAlpha(NAVY_DARK, bladeFade * 0.88f), withAlpha(NAVY_MID, bladeFade * 0.34f), -0.04)
            hStrip(inkVc, 0.62, 1.08, withAlpha(NAVY_MID, bladeFade * 0.34f), withAlpha(NAVY_DARK, 0f), -0.04)

            // Frost-white core & soft ice-blue falloff across the crescent.
            hStrip(glowVc, 0.0, 0.10, withAlpha(ICE_LIGHT, bladeFade * 0.65f), withAlpha(PURE_WHITE, bladeFade))
            hStrip(glowVc, 0.10, 0.28, withAlpha(PURE_WHITE, bladeFade), withAlpha(ICE_LIGHT, bladeFade * 0.92f))
            hStrip(glowVc, 0.28, 0.72, withAlpha(ICE_LIGHT, bladeFade * 0.85f), withAlpha(ICE_BLUE, bladeFade * 0.38f))
            hStrip(glowVc, 0.72, 1.05, withAlpha(ICE_BLUE, bladeFade * 0.38f), withAlpha(NAVY_MID, 0f))

            // Subtle flowing frost sheen along the wake (fog-free, monochrome frost tint).
            hStrip(flowVc, 0.08, 0.85, withAlpha(FROST_WHITE, bladeFade * 0.42f), withAlpha(ICE_BLUE, 0f), 0.05, uScroll = -t * 0.09f)

            // 2) Sculpted Vertical / Lenticular Slash Wall along the leading arc (makes it HUGE in 1st & 3rd person!).
            val halfH = (if (temple) 1.08 else 0.88) * (0.7 + 0.3 * open) * (1f - progress * 0.35f)
            fun vCurtain(vc: net.minecraft.client.render.VertexConsumer, back: Double, h0: Double, h1: Double, c0: Int, c1: Int) {
                with(d) {
                    for (i in 0 until 40) {
                        val a = i / 40.0
                        val b = (i + 1) / 40.0
                        val envA = sin(a * PI).let { sqrt(it.coerceAtLeast(0.0)) }
                        val envB = sin(b * PI).let { sqrt(it.coerceAtLeast(0.0)) }
                        val pA0 = arcPoint(a, back, h0 * envA)
                        val pA1 = arcPoint(a, back, h1 * envA)
                        val pB1 = arcPoint(b, back, h1 * envB)
                        val pB0 = arcPoint(b, back, h0 * envB)
                        vc.put(pA0, 0f, 0f, c0)
                        vc.put(pA1, 0f, 1f, c1)
                        vc.put(pB1, 1f, 1f, c1)
                        vc.put(pB0, 1f, 0f, c0)
                    }
                }
            }

            // Deep navy outer vertical halo (top & bottom) -> guaranteed silhouette against blinding white snow.
            vCurtain(inkVc, 0.14, 0.0, halfH * 1.15, withAlpha(NAVY_DARK, bladeFade * 0.82f), withAlpha(NAVY_DARK, 0f))
            vCurtain(inkVc, 0.14, 0.0, -halfH * 1.15, withAlpha(NAVY_DARK, bladeFade * 0.82f), withAlpha(NAVY_DARK, 0f))
            // Soft ice-blue mid vertical falloff.
            vCurtain(glowVc, 0.08, 0.0, halfH * 0.72, withAlpha(ICE_LIGHT, bladeFade * 0.90f), withAlpha(ICE_BLUE, 0f))
            vCurtain(glowVc, 0.08, 0.0, -halfH * 0.72, withAlpha(ICE_LIGHT, bladeFade * 0.90f), withAlpha(ICE_BLUE, 0f))
            // Razor-bright frost-white core along the horizontal horizon line.
            vCurtain(glowVc, 0.04, 0.0, halfH * 0.24, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * 0.35f))
            vCurtain(glowVc, 0.04, 0.0, -halfH * 0.24, withAlpha(PURE_WHITE, bladeFade), withAlpha(FROST_WHITE, bladeFade * 0.35f))
        }

        // 3) Crisp initial horizon cleave seam across the full view (ticks 0..11).
        if (t < 11f) {
            val seamFade = (1f - t / 11f).let { it * it } * fx
            val seamCenter = origin.add(forward.multiply(travel * 0.55 + 1.2))
            val seamSpan = width * (0.95 + 0.28 * open)
            val s0 = seamCenter.subtract(across.multiply(seamSpan))
            val s1 = seamCenter.add(across.multiply(seamSpan))
            d.band(AriusLayers.INK, s0, s1, UP, 0.22 * seamFade + 0.06, withAlpha(NAVY_DARK, seamFade * 0.9f), withAlpha(NAVY_DARK, seamFade * 0.9f))
            d.band(AriusLayers.VFX_GLOW, s0, s1, UP, 0.14 * seamFade + 0.03, withAlpha(PURE_WHITE, seamFade), withAlpha(FROST_WHITE, seamFade))
            d.billboard(AriusLayers.VFX_FLARE, seamCenter, (3.2 + 2.6 * open) * seamFade, withAlpha(FROST_WHITE, seamFade * 0.85f))
        }

        // 4) Crisp dissipating frost aftermath along the entire swept corridor (origin -> center):
        //    slender geometric ice splinters + thin fading horizontal frost filaments, zero blocky bricks or particle spam.
        val wakeIn = ((t - 3.5f) / 4.5f).coerceIn(0f, 1f)
        val wakeOut = (1f - ((t - 10f) / (life - 10f).coerceAtLeast(1f)).coerceIn(0f, 1f))
        val wakeA = wakeIn * wakeOut * fx
        if (wakeA > 0.015f) {
            val corridorSpan = maxTravel * travelFrac
            fun wakePoint(u: Double, alongFrac: Double, yOff: Double): Vec3d {
                val arch = sin(u * PI)
                val zDist = alongFrac * corridorSpan
                return origin
                    .add(across.multiply((2.0 * u - 1.0) * span * (0.85 + 0.15 * alongFrac)))
                    .add(forward.multiply(zDist + arch * bow * 0.5))
                    .add(0.0, yOff, 0.0)
            }

            // Thin dissipating horizontal frost-wake filaments suspended cleanly in the cleaved air.
            for (rIdx in 0 until 8) {
                val frac = (rIdx + 0.5) / 8.0
                val alongFrac = 0.16 + 0.72 * ((rIdx * 0.37 + 0.15) % 1.0)
                val yOff = ((rIdx % 4) - 1.5) * 0.24 * (0.5 + 0.7 * progress)
                val segSpan = width * (0.16 + 0.14 * sin(frac * PI))
                val mid = wakePoint(0.14 + 0.72 * frac, alongFrac, yOff)
                val p0 = mid.subtract(across.multiply(segSpan))
                val p1 = mid.add(across.multiply(segSpan))
                d.band(AriusLayers.INK, p0, p1, UP, 0.065 * wakeOut + 0.015, withAlpha(NAVY_MID, 0f), withAlpha(NAVY_DARK, wakeA * 0.72f))
                d.band(AriusLayers.VFX_GLOW, p0, p1, UP, 0.04 * wakeOut + 0.01, withAlpha(ICE_BLUE, 0f), withAlpha(FROST_WHITE, wakeA * 0.88f))
            }

            // Sleek diamond-like frost slivers drifting gently upward/outward along the cleaved air.
            for (s in shards) {
                val drift = ((t - 3.5f) / (life - 3.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val basePos = wakePoint(s.u, s.along, s.yOff * (0.35 + 0.85 * drift))
                    .add(forward.multiply(s.driftOut * drift * 1.4))
                    .add(0.0, s.driftUp * drift * 1.15, 0.0)
                val ang = t * s.spin + s.u * 9.0
                val sz = s.size * (0.55 + 0.45 * wakeOut)
                // Elongated razor ice sliver (length 2.8x, slim 0.28x cross-section -> crisp crystal needle, never a brick).
                val ax = across.multiply(cos(ang) * sz * 2.8).add(forward.multiply(sin(ang) * sz * 1.1))
                val ay = UP.multiply(sz * 0.34).add(across.multiply(-sin(ang) * sz * 0.18))
                val az = forward.multiply(cos(ang) * sz * 0.26).add(across.multiply(-sin(ang) * sz * 0.16))
                val bodyCol = if (s.dark) NAVY_MID else ICE_LIGHT
                d.box(AriusLayers.VFX_SHARD, basePos, ax, ay, az, withAlpha(bodyCol, wakeA * 0.92f))
                d.billboard(AriusLayers.VFX_GLOW, basePos, sz * 3.4, withAlpha(FROST_WHITE, wakeA * 0.62f))
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
 * Brief HUD frost light flash + cool deep-navy world-tint pulse triggered on White Horizon release.
 * Strict frost-white / ice-blue / deep-navy palette; respects [AriusClientConfig.flash] and [AriusClientConfig.fx].
 */
object HorizonPulse {
    private var age = -1
    private var peak = 0f
    private const val TOTAL = 16

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
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight

        // 1) Brief crisp frost-white light flash (first 3.5 ticks, capped and flash-config aware).
        if (t < 3.5f) {
            val f = (1f - t / 3.5f).let { it * it }
            val flashCap = if (AriusClientConfig.flash) 0.38f else 0.12f
            ctx.fill(0, 0, w, h, withAlpha(0xFFEAF7FF.toInt(), flashCap * peak * f))
        }

        // 2) Cool deep-navy / ice-blue world-tint & subtle top/bottom frost framing (fades cleanly over 16 ticks).
        val env = ((t / 1.5f).coerceIn(0f, 1f)) * (1f - ((t - 2f) / (TOTAL - 2f)).coerceIn(0f, 1f)).let { it * it }
        if (env > 0.01f) {
            // Subtle cool frost air tint across view.
            ctx.fill(0, 0, w, h, withAlpha(0xFF2B6EA8.toInt(), 0.16f * peak * env))
            // Deep-navy horizon framing at top and bottom so the horizontal cleave reads starkly even on white snow.
            val bands = 8
            val edgeH = (h * 0.24f).toInt().coerceAtLeast(8)
            for (i in 0 until bands) {
                val frac = 1f - i / bands.toFloat()
                val a = 0.34f * peak * env * frac * frac
                val y0 = edgeH * i / bands
                val y1 = edgeH * (i + 1) / bands
                val col = withAlpha(0xFF071B36.toInt(), a)
                ctx.fill(0, y0, w, y1, col)
                ctx.fill(0, h - y1, w, h - y0, col)
            }
        }

        // 3) Brief razor-clean horizontal frost gleam across the horizon (ticks 0..5.5).
        if (t < 5.5f) {
            val g = (1f - t / 5.5f).let { it * it } * peak
            val cy = h / 2
            val coreH = (1 + (2f * g).toInt())
            val glowH = (4 + (10f * g).toInt())
            ctx.fill(0, cy - glowH, w, cy + glowH, withAlpha(0xFF42A8EA.toInt(), 0.22f * g))
            ctx.fill(0, cy - coreH, w, cy + coreH, withAlpha(0xFFF6FCFF.toInt(), 0.55f * g))
        }
    }
}

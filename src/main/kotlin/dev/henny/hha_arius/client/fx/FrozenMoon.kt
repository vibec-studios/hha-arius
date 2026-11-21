package dev.henny.hha_arius.client.fx

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.basisOf
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.HhaArius
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Ebenen und Texturen des gefrorenen Sterns (seit 1.10.0, Crix: „der Mond sieht immer noch schlecht aus“). Texturen
 * prozedural aus tools/assets/frozen_moon.py: Eiskruste 1024 × 512 (äquirektangulär), leuchtende Risse, weicher Hof,
 * Atmosphärensaum. Alle Ebenen ohne Vanilla-Nebel (im Whiteout sichtbar); die Nebelmischung rechnet [FrozenMoonFx].
 */
object FrozenMoon {

    private val base: RenderPipeline.Snippet? = try {
        val f = VfxLayers::class.java.getDeclaredField("BASE")
        f.isAccessible = true
        f.get(null) as RenderPipeline.Snippet
    } catch (t: Throwable) {
        null
    }

    private fun pipeline(name: String, blend: BlendFunction, depth: Boolean, glow: Float?): RenderPipeline? {
        val b = base ?: return null
        return try {
            var builder = RenderPipeline.builder(b)
                .withLocation(HhaArius.id("pipeline/$name"))
                .withBlend(blend)
                .withDepthWrite(depth)
                .withShaderDefine("VFX_NO_FOG")
            if (glow != null) builder = builder.withShaderDefine("VFX_GLOW", glow)
            RenderPipelines.register(builder.build())
        } catch (t: Throwable) {
            HhaArius.LOGGER.warn("Pipeline {} nicht angelegt: {}", name, t.toString())
            null
        }
    }

    private val bodyPipe = pipeline("frozen_moon", BlendFunction.TRANSLUCENT, true, null)
    private val glowPipe = pipeline("frozen_moon_glow", BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA, false, 0.6f)

    private fun layer(name: String, pipe: RenderPipeline?, tex: Identifier, fallback: () -> RenderLayer): RenderLayer {
        if (pipe == null) return fallback()
        return try {
            RenderLayer.of("hha_arius_$name", RenderSetup.builder(pipe).texture("Sampler0", tex).expectedBufferSize(262144).build())
        } catch (t: Throwable) {
            fallback()
        }
    }

    val SURFACE: RenderLayer by lazy { layer("frozen_moon", bodyPipe, HhaArius.id("textures/fx/frozen_moon.png")) { AriusLayers.MOON } }
    val CRACKS: RenderLayer by lazy { layer("frozen_moon_cracks", glowPipe, HhaArius.id("textures/fx/frozen_moon_glow.png")) { AriusLayers.MOON_GLOW } }
    val HALO: RenderLayer by lazy { layer("frozen_moon_halo", glowPipe, HhaArius.id("textures/fx/moon_halo.png")) { AriusLayers.MOON_GLOW } }
    val LIMB: RenderLayer by lazy { layer("frozen_moon_limb", glowPipe, HhaArius.id("textures/fx/moon_limb.png")) { AriusLayers.MOON_GLOW } }

    /** Registriert die Pipelines vor dem ersten Shader-Reload (aus der Client-Initialisierung). */
    fun init() {
        AriusLayers.init()
        bodyPipe
        glowPipe
    }
}

/**
 * „Der gefrorene Stern“ (seit 1.10.0 neu gezeichnet; Ablauf wie bisher, Treffer rechnet der Server):
 * 1. **Geburt:** ein gleißender Punkt hoch über dem Ziel, Lichtfunken spiralen hinein.
 * 2. **Der Mond:** eine fein unterteilte Kugel mit echter Eiskruste (Textur: Eisebenen, Krater mit Strahlen, Frostkorn),
 *    weich beleuchtet: das Licht wandert beim Gefrieren von schräg hinten nach vorn (Sichel → Vollmond), die Nachtseite
 *    trägt blaues Erdlicht und einen Hauch Unterlicht aus dem Eis. Leuchtende Risse glimmen auf, ein weicher Lichthof
 *    und ein Atmosphärensaum (keine Ringe, keine harten Kanten) umgeben ihn; in Nebel und Sturm mischt er sich mit der
 *    Luft. Er dreht sich langsam um eine geneigte Achse, Frostdunst zieht um ihn.
 * 3. **Sturz:** er beschleunigt, ein Schweif aus Licht und Frostrauch, der Schatten wächst am Boden.
 * 4. **Einschlag:** Blitz, Splitter, Druckwelle, Frostkrater.
 */
class FrozenMoonFx(private val ground: Vec3d, private val radius: Double, private val impactAt: Int, life: Int) : AriusFx(life) {

    private val axisTilt = 0.41
    // Reusable per-instance buffers eliminate ~15k per-frame allocations on the 48x96 relief mesh.
    private val vertColors = IntArray(Crust.points.size)
    private val vertVisible = BooleanArray(Crust.points.size)
    private val vertPoints = Array(Crust.points.size) { Vec3d.ZERO }

    private object Crust {
        const val LAT = 48
        const val LON = 96
        const val STRIDE = LON + 1

        val relief = requireNotNull(FrozenMoonFx::class.java.getResourceAsStream("/assets/hha_arius/textures/fx/frozen_moon_relief.bin")).use {
            java.nio.ByteBuffer.wrap(it.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
        val points = Array((LAT + 1) * STRIDE) { index ->
            val lat = PI * (index / STRIDE) / LAT
            val lon = 2 * PI * (index % STRIDE) / LON
            val radius = 1.0 + relief[index].toDouble() / 32767.0
            Vec3d(sin(lat) * cos(lon), cos(lat), sin(lat) * sin(lon)).multiply(radius)
        }
        val normals = Array(points.size) { index ->
            val i = index / STRIDE
            val j = index % STRIDE
            if (i == 0 || i == LAT) points[index].normalize() else {
                val along = points[i * STRIDE + (j + 1) % LON].subtract(points[i * STRIDE + (j + LON - 1) % LON])
                val down = points[(i + 1) * STRIDE + j].subtract(points[(i - 1) * STRIDE + j])
                along.crossProduct(down).normalize()
            }
        }
    }

    override fun tick(world: ClientWorld) {
        val r = random()
        if (age < impactAt) {
            val c = center(age.toFloat())
            repeat((3 * particlesAllowed()).toInt()) {
                val a = r.nextDouble() * PI * 2
                val dd = 6.0 + r.nextDouble() * 10.0
                val p = c.add(cos(a) * dd, (r.nextDouble() - 0.5) * 8.0, sin(a) * dd)
                val v = c.subtract(p).multiply(0.06)
                world.addParticleClient(ParticleTypes.END_ROD, p.x, p.y, p.z, v.x, v.y, v.z)
            }
            if (age > impactAt * 0.4 && r.nextFloat() < 0.5f * particlesAllowed()) {
                val a = r.nextDouble() * PI * 2
                val rr = radius * 0.7 * (1.0 + r.nextDouble())
                world.addParticleClient(ParticleTypes.SNOWFLAKE, c.x + cos(a) * rr, c.y + (r.nextDouble() - 0.5) * rr, c.z + sin(a) * rr, 0.0, -0.05, 0.0)
            }
        } else if (age < impactAt + 3) {
            repeat((60 * particlesAllowed()).toInt()) {
                val a = r.nextDouble() * PI * 2
                val v = Vec3d(cos(a), 0.4 + r.nextDouble() * 0.8, sin(a)).multiply(0.4 + r.nextDouble() * 0.6)
                world.addParticleClient(if (r.nextInt(2) == 0) ParticleTypes.SNOWFLAKE else ParticleTypes.END_ROD, ground.x, ground.y + 0.5, ground.z, v.x, v.y, v.z)
            }
        }
    }

    private fun fall(t: Float) = ((t - impactAt * 0.75f) / (impactAt * 0.25f)).coerceIn(0f, 1f)

    private fun center(t: Float): Vec3d {
        val f = fall(t)
        return ground.add(0.0, 40.0 * (1.0 - f * f) + 1.0, 0.0)
    }

    /** Oberflächenrichtung → Weltrichtung: Drehung um die Hochachse, dann Neigung der Achse. */
    private fun spin(n: Vec3d, ang: Double): Vec3d {
        val x = n.x * cos(ang) - n.z * sin(ang)
        val z = n.x * sin(ang) + n.z * cos(ang)
        return Vec3d(x, n.y * cos(axisTilt) - z * sin(axisTilt), n.y * sin(axisTilt) + z * cos(axisTilt))
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        if (t >= impactAt) {
            impact(d, t - impactAt)
            return
        }
        val form = (t / (impactAt * 0.35f)).coerceIn(0f, 1f)
        val grow = ((t - impactAt * 0.2f) / (impactAt * 0.45f)).coerceIn(0f, 1f)
        val freeze = ((t - impactAt * 0.35f) / (impactAt * 0.4f)).coerceIn(0f, 1f)
        val f = fall(t)
        val c = center(t)
        val g = grow * grow * (3 - 2 * grow)
        val r = radius * (0.12 + 0.55 * g)
        val cold = 0xFFBFE8FF.toInt()
        val warm = 0xFFFFF0C8.toInt()
        val view = d.cam.subtract(c).normalize()
        // Luft zwischen Kamera und Mond: im Sturm/Nebel blasser und kühler (statt hart vor dem Weiß zu stehen).
        val dist = d.cam.distanceTo(c)
        val vis = runCatching { Atmos.visibility().toDouble() }.getOrDefault(200.0).coerceAtLeast(12.0)
        val haze = (1.0 - exp(-dist / (vis * 1.4))).toFloat().coerceIn(0f, 0.43f)
        val hazeCol = 0xFFDCE7F2.toInt()

        // 1) Lichtpunkt der Geburt: weicher Hof statt harter Strahlen.
        d.billboard(FrozenMoon.HALO, c, 4.0 + 10.0 * form, withAlpha(0xFFFFFFFF.toInt(), 0.85f * form * (1f - g * 0.7f)))
        d.billboard(VfxLayers.RAYS_ADD, c, r * 4.0 + 8.0 * form, withAlpha(warm, 0.25f * form * (1f - freeze)), t * 0.6)
        if (grow <= 0f) return

        val side = view.crossProduct(AriusFx.UP).let { if (it.lengthSquared() < 1e-6) AriusFx.X else it.normalize() }
        val phi = (1.0 - freeze) * 1.75 + 0.65
        val light = view.multiply(cos(phi)).add(side.multiply(sin(phi))).add(0.0, 0.35, 0.0).normalize()
        val spinA = t * 0.01

        // 2a) Lichthof hinter dem Körper (weich, ohne Kante); innen verdeckt ihn die Kugel.
        val haloCol = lerpColor(lerpColor(warm, cold, freeze), hazeCol, haze * 0.5f)
        d.billboard(FrozenMoon.HALO, c, r * 7.5, withAlpha(lerpColor(haloCol, 0xFF7FB2EA.toInt(), 0.35f), 0.38f * g))
        d.billboard(FrozenMoon.HALO, c, r * 3.6, withAlpha(haloCol, 0.55f * g))
        // Craters and the emissive fissures share exactly the same moving 3D surface.
        val crackAlpha = g * freeze * (0.78f + 0.22f * sin(t * 0.21f)) * (1f - haze * 0.55f)
        sphere(d, c, r, light, view, spinA, g, freeze, haze, hazeCol, crackAlpha)
        // 2b) Eisiger Atmosphärensaum exakt auf dem Mondrand (moon_limb.png hat den Saum bei r = 0.5).
        d.billboard(FrozenMoon.LIMB, c.add(view.multiply(r * 0.08)), r * 2.08,
            withAlpha(lerpColor(warm, 0xFFB8ECFF.toInt(), freeze), (0.52f + 0.28f * freeze) * g * (1f - haze * 0.45f)))
        // 2e) Frostdunst und schwebende Eiskristalle kreisen um den gefrierenden Mond.
        val (ra, rb) = basisOf(view)
        for (i in 0 until 6) {
            val a = i / 6.0 * PI * 2 + t * 0.015
            val p = c.add(ra.multiply(cos(a) * r * 1.35)).add(rb.multiply(sin(a) * r * 1.35))
            d.billboard(AriusLayers.VEIL, p, r * 1.3, withAlpha(0xFFE2ECF6.toInt(), 0.16f * g * freeze), a * 2.0)
        }
        if (freeze > 0.1f) {
            for (i in 0 until 10) {
                val a = i * 2.39996 + t * (0.032 + (i % 3) * 0.008)
                val orbit = r * (1.18 + 0.24 * sin(i * 1.7 + t * 0.04))
                val elev = sin(i * 2.1 + t * 0.025) * r * 0.45
                val p = c.add(ra.multiply(cos(a) * orbit)).add(rb.multiply(sin(a) * orbit)).add(view.multiply(elev))
                val sz = r * (0.032 + (i % 3) * 0.012)
                d.box(AriusLayers.VFX_SHARD, p, ra.multiply(sz * 1.8), rb.multiply(sz * 0.7), view.multiply(sz * 0.6),
                    withAlpha(if (i % 2 == 0) 0xFFDDF7FF.toInt() else 0xFF7CD4FF.toInt(), 0.85f * g * freeze))
            }
        }

        // 3) Sturz: Schweif aus Licht und Frostrauch (weiche Kugeln, keine Bänder mit Kanten).
        if (f > 0f) {
            for (i in 1..10) {
                val k = i / 10.0
                val p = c.add(0.0, k * 24.0 * f, 0.0)
                d.billboard(FrozenMoon.HALO, p, r * (2.6 - 1.2 * k), withAlpha(cold, 0.45f * (1f - k.toFloat()) * f))
                d.billboard(AriusLayers.VEIL, p.add(0.0, 2.0, 0.0), r * (1.4 + k * 1.2), withAlpha(0xFFE6EEF6.toInt(), 0.28f * (1f - k.toFloat())))
            }
        }
        val shadow = (t / impactAt).coerceIn(0f, 1f)
        d.disc(VfxLayers.SMOKE_ALPHA, ground.add(0.0, 0.07, 0.0), AriusFx.X, AriusFx.Z, radius * (0.3 + 0.7 * shadow), withAlpha(0xFF1B2A44.toInt(), 0.35f * shadow))
        d.ring(AriusLayers.VFX_GLOW, ground.add(0.0, 0.08, 0.0), AriusFx.X, AriusFx.Z, radius - 0.35, radius, withAlpha(cold, 0f), withAlpha(cold, 0.7f * shadow), segments = 64)
    }

    /**
     * Kugel mit Textur-UVs (Länge/Breite) und Beleuchtung je Ecke: scharfe Schattengrenze, tiefes Erdlicht auf der
     * Nachtseite, Unterlicht aus dem Eis am Rand (Fresnel), spiegelndes Eisglitzern und Nebelmischung.
     */
    private fun sphere(d: VfxDraw, center: Vec3d, radius: Double, light: Vec3d, view: Vec3d, spinA: Double, g: Float, freeze: Float, haze: Float, hazeCol: Int, crackAlpha: Float) {
        val litIce = lerpColor(0xFFFFF2DA.toInt(), 0xFFF5FAFF.toInt(), freeze)
        val darkIce = 0xFF0E1D36.toInt()
        val glint = light.add(view).normalize()
        val colors = vertColors
        val visible = vertVisible
        val points = vertPoints
        for (i in Crust.points.indices) {
            val v = spin(Crust.points[i], spinA)
            val n = spin(Crust.normals[i], spinA)
            val ndl = n.dotProduct(light)
            val rim = (1.0 - n.dotProduct(view).coerceIn(0.0, 1.0)).let { it * it * it }.toFloat()
            val diffuse = ((ndl + 0.14) / 1.10).coerceIn(0.0, 1.0).toFloat()
            var tint = lerpColor(darkIce, litIce, diffuse)
            tint = lerpColor(tint, 0xFF9FE6FF.toInt(), rim * 0.42f * (0.3f + 0.7f * freeze))
            val highlight = if (ndl > 0.0) Math.pow(n.dotProduct(glint).coerceAtLeast(0.0), 18.0) else 0.0
            tint = lerpColor(tint, 0xFFFFFFFF.toInt(), (highlight * 0.52 * freeze).toFloat())
            colors[i] = withAlpha(lerpColor(tint, hazeCol, haze), g)
            visible[i] = v.dotProduct(view) > -0.14
            points[i] = center.add(v.multiply(radius))
        }
        // Submit each layer as a batch; the depth-tested emissive cracks hover 0.6% above the sculpted surface to avoid z-fighting.
        fun drawLayer(layer: RenderLayer, glow: Boolean) {
            val vc = d.buffer(layer)
            val emission = withAlpha(0xFF9FE4FF.toInt(), crackAlpha)
            fun vertex(i: Int, j: Int) {
                val n = i * Crust.STRIDE + j
                val p = points[n]
                val at = if (glow) p.add(p.subtract(center).multiply(0.006)) else p
                with(d) { vc.put(at, j / Crust.LON.toFloat(), i / Crust.LAT.toFloat(), if (glow) emission else colors[n]) }
            }
            for (i in 0 until Crust.LAT) for (j in 0 until Crust.LON) {
                val a = i * Crust.STRIDE + j
                val b = a + Crust.STRIDE
                if (!visible[a] && !visible[a + 1] && !visible[b] && !visible[b + 1]) continue
                vertex(i, j)
                vertex(i, j + 1)
                vertex(i + 1, j + 1)
                vertex(i + 1, j)
            }
        }
        drawLayer(FrozenMoon.SURFACE, false)
        if (crackAlpha > 0.01f) drawLayer(FrozenMoon.CRACKS, true)
    }

    private fun impact(d: VfxDraw, s: Float) {
        val k = (1f - s / (life - impactAt).coerceAtLeast(1)).coerceIn(0f, 1f)
        if (k <= 0f) return
        val burst = (s / 6f).coerceIn(0f, 1f)
        d.billboard(FrozenMoon.HALO, ground.add(0.0, 1.5, 0.0), 22.0 * (1f - burst) + 4.0, withAlpha(0xFFFFFFFF.toInt(), (1f - burst) * 0.95f))
        d.ring(AriusLayers.INK, ground.add(0.0, 0.18, 0.0), AriusFx.X, AriusFx.Z, radius * (0.4 + 1.4 * burst), radius * (0.62 + 1.62 * burst), withAlpha(0xFF144B7A.toInt(), 0.72f * k), withAlpha(0xFF6CD4FF.toInt(), 0f), segments = 64)
        d.ring(AriusLayers.VFX_GLOW, ground.add(0.0, 0.22, 0.0), AriusFx.X, AriusFx.Z, radius * (0.4 + 1.4 * burst), radius * (0.6 + 1.6 * burst), withAlpha(0xFFFFFFFF.toInt(), 0.9f * k), withAlpha(0xFFBFE8FF.toInt(), 0f), segments = 64)
        for (i in 0 until 18) {
            val a = i / 18.0 * PI * 2 + i
            val sp = 0.5 + (i % 5) * 0.12
            val p = ground.add(cos(a) * s * sp, 0.6 + s * 0.5 - s * s * 0.02, sin(a) * s * sp)
            if (p.y < ground.y) continue
            d.box(AriusLayers.VFX_SHARD, p, AriusFx.X.multiply(0.3), AriusFx.UP.multiply(0.5), AriusFx.Z.multiply(0.3), withAlpha(0xFFDDF2FF.toInt(), 0.9f * k))
        }
        d.disc(AriusLayers.VFX_GLOW, ground.add(0.0, 0.06, 0.0), AriusFx.X, AriusFx.Z, radius * 0.9, withAlpha(0xFF8FC8FF.toInt(), 0.42f * k))
    }
}

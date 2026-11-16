package dev.henny.hha_arius.client.fx

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.HhaArius.LOGGER
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.Framebuffer
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gl.UniformType
import net.minecraft.client.render.VertexFormats
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.OptionalInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Das weiße Loch (Setfähigkeit des Spielers und Arius' Signatur im Bosskampf). Eigener starker Effekt, entwickelt aus
 * dem schwarzen Loch von HHA Void: eine Vollbild-Linse ([WhiteLens], Shader `white_lens.fsh`) biegt Licht vom Kern
 * WEG, der Kern gleißt weiß, eine Scheibe aus Eis und goldenem Licht strömt spiralförmig nach außen; dazu Schnee, der
 * eingesogen wird, zwei Lichtsäulen entlang der Achse und beim Kollaps eine Brechungs-Schockwelle durch das Bild.
 *
 * Zeitachse (Ticks): 0–12 Öffnen, bis [pullTicks] Sog, danach Kollaps (Kern fällt in 5 Ticks zusammen, Blitz) und
 * Schockwelle (18 Ticks).
 */
class WhiteHoleFx(val center: Vec3d, private val radius: Double, private var pullTicks: Int, life: Int) : AriusFx(life) {

    /** Der Server meldet den Kollaps (Bosskampf): ab jetzt fällt der Kern zusammen. */
    fun collapseNow() {
        if (pullTicks > age) pullTicks = age
        lifeLimit = age + 24
    }

    /** Frühzeitiges Ende nach dem Kollaps (Bosskampf). */
    var lifeLimit = Int.MAX_VALUE
        private set

    val finished: Boolean get() = age >= lifeLimit

    private val coreMax = (0.55 + radius * 0.07).coerceIn(0.5, 1.4)

    fun coreRadius(td: Float): Float {
        val t = age + td
        val open = easeOutBack((t / 12f).coerceIn(0f, 1f))
        val breath = 1f + 0.04f * sin(t * 0.35f)
        val collapse = if (t > pullTicks) (1f - (t - pullTicks) / 5f).coerceIn(0f, 1f).let { it * it } else 1f
        return (coreMax * open * breath * collapse).toFloat()
    }

    fun intensity(td: Float): Float {
        val t = age + td
        val end = minOf(life, lifeLimit).toFloat()
        return minOf((t / 10f).coerceIn(0f, 1f), ((end - t) / 10f).coerceIn(0f, 1f))
    }

    fun pull(td: Float): Float = ((age + td - 12f) / (pullTicks - 12f).coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun flash(td: Float): Float {
        val t = age + td
        val open = (1f - t / 6f).coerceIn(0f, 1f)
        val c = t - pullTicks
        val close = if (c in 0f..8f) (1f - c / 8f) else 0f
        val k = if (AriusClientConfig.flash) 1f else 0.25f
        return (open * open * 0.08f + close * close * 0.45f) * k
    }

    fun shock(td: Float): Triple<Float, Float, Float> {
        val c = age + td - pullTicks
        if (c < 0f) return Triple(0f, 1f, 0f)
        val s = (c / 18f).coerceIn(0f, 1f)
        return Triple((radius * 2.4 * (1 - (1 - s) * (1 - s))).toFloat(), 1.2f + 1.5f * s, (1f - s) * 1.4f)
    }

    fun diskNormal(td: Float): Vector3f {
        val t = (age + td) * 0.015f
        val tilt = 0.32f
        return Vector3f(sin(tilt) * cos(t), cos(tilt), sin(tilt) * sin(t)).normalize()
    }

    override fun tick(world: ClientWorld) {
        if (age > pullTicks) return
        val r = random()
        // Schnee und Licht werden eingesogen.
        repeat((10 * particlesAllowed()).toInt()) {
            val a = r.nextDouble() * 2 * PI
            val e = (r.nextDouble() - 0.5) * 1.2
            val d = radius * (0.8 + 0.6 * r.nextDouble())
            val p = center.add(cos(a) * cos(e) * d, sin(e) * d * 0.6, sin(a) * cos(e) * d)
            val v = center.subtract(p).multiply(0.07)
            world.addParticleClient(if (r.nextFloat() < 0.7f) ParticleTypes.SNOWFLAKE else ParticleTypes.END_ROD, p.x, p.y, p.z, v.x, v.y, v.z)
        }
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = intensity(d.tickDelta)
        val core = coreRadius(d.tickDelta).toDouble()
        if (core > 0.05) {
            // Lichtsäulen entlang der Scheibenachse
            val n = diskNormal(d.tickDelta)
            val axis = Vec3d(n.x.toDouble(), n.y.toDouble(), n.z.toDouble())
            val len = radius * 1.8 * pull(d.tickDelta)
            for (s in intArrayOf(-1, 1)) {
                val end = center.add(axis.multiply(len * s))
                d.band(VfxLayers.GLOW_ADD, center, end, d.camRight, core * 0.35, withAlpha(0xFFFFFFFF.toInt(), 0.8f * k), withAlpha(AriusFx.ICE, 0f))
            }
            d.billboard(VfxLayers.FLARE_ADD, center, core * 7.0, withAlpha(0xFFFFFFFF.toInt(), 0.35f * k))
        }
        // Nach dem Kollaps: flacher heller Ring am Boden und Eisfunken nach außen
        val (sr, _, ss) = shock(d.tickDelta)
        if (ss > 0.02f) {
            d.ring(VfxLayers.GLOW_ADD, center.add(0.0, -0.4, 0.0), X, Z, (sr - 1.4).toDouble().coerceAtLeast(0.0), sr.toDouble(), withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(AriusFx.ICE, 0.6f * ss), segments = 72)
        }
    }

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val y = x - 1f
        return 1f + c3 * y * y * y + c1 * y * y
    }
}

/** Eigene Render-Pipelines der Ausrüstung/des Kampfes. */
object ArtPipelines {
    val WHITE_LENS: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(HhaArius.id("pipeline/white_lens"))
            .withVertexShader(HhaArius.id("core/white_lens"))
            .withFragmentShader(HhaArius.id("core/white_lens"))
            .withSampler("SceneSampler")
            .withSampler("DepthSampler")
            .withUniform("WhiteHole", UniformType.UNIFORM_BUFFER)
            .withoutBlend()
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withColorWrite(true, false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build(),
    )

    /** Erzwingt die Registrierung vor dem ersten Shader-Reload. */
    fun init() = Unit
}

/** Vollbild-Pass: kopiert das Weltbild und rechnet darüber die Linse des weißen Lochs (siehe `white_lens.fsh`). */
object WhiteLens {

    private val UNIFORM_SIZE = Std140SizeCalculator().putVec4().putVec4().putVec4().putVec4().get()
    private var copy: GpuTexture? = null
    private var copyView: GpuTextureView? = null
    private var uniforms: GpuBuffer? = null
    private var warned = false

    private fun ensure(width: Int, height: Int) {
        val device = RenderSystem.getDevice()
        val existing = copy
        if (existing == null || existing.getWidth(0) != width || existing.getHeight(0) != height) {
            copyView?.close()
            existing?.close()
            val usage = GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING
            val texture = device.createTexture({ "hha_arius white lens scene" }, usage, TextureFormat.RGBA8, width, height, 1, 1)
            copy = texture
            copyView = device.createTextureView(texture)
        }
        if (uniforms == null) {
            uniforms = device.createBuffer({ "hha_arius white lens uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, UNIFORM_SIZE.toLong())
        }
    }

    fun render(hole: WhiteHoleFx, tickDelta: Float) {
        try {
            draw(MinecraftClient.getInstance().framebuffer, hole, tickDelta)
        } catch (e: Exception) {
            if (!warned) {
                warned = true
                LOGGER.error("Linse des weißen Lochs fehlgeschlagen; weitere Meldungen werden unterdrückt", e)
            }
        }
    }

    private fun draw(framebuffer: Framebuffer, hole: WhiteHoleFx, td: Float) {
        val colorTexture = framebuffer.colorAttachment ?: return
        val colorView = framebuffer.colorAttachmentView ?: return
        val depthView = framebuffer.depthAttachmentView ?: return
        val intensity = hole.intensity(td)
        val (shockR, shockW, shockS) = hole.shock(td)
        if (intensity <= 0.01f && shockS <= 0.01f) return
        ensure(colorTexture.getWidth(0), colorTexture.getHeight(0))
        val scene = copy ?: return
        val sceneView = copyView ?: return
        val buffer = uniforms ?: return

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.copyTextureToTexture(colorTexture, scene, 0, 0, 0, 0, 0, scene.getWidth(0), scene.getHeight(0))

        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val c = hole.center
        val core = hole.coreRadius(td)
        val normal = hole.diskNormal(td)
        MemoryStack.stackPush().use { stack ->
            val data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                .putVec4((c.x - cam.x).toFloat(), (c.y - cam.y).toFloat(), (c.z - cam.z).toFloat(), core)
                .putVec4(normal.x, normal.y, normal.z, core * 6.5f)
                .putVec4((hole.age + td) / 20f, intensity, hole.flash(td), hole.pull(td))
                .putVec4(shockR, shockW, shockS, 0f)
                .get()
            encoder.writeToBuffer(buffer.slice(), data)
        }
        val transforms = RenderSystem.getDynamicUniforms()
            .write(RenderSystem.getModelViewMatrix(), Vector4f(1f, 1f, 1f, 1f), Vector3f(), Matrix4f())
        val sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR)
        val depthSampler = RenderSystem.getSamplerCache().get(FilterMode.NEAREST)
        encoder.createRenderPass({ "hha_arius white hole" }, colorView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(ArtPipelines.WHITE_LENS)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setUniform("WhiteHole", buffer)
            pass.bindTexture("SceneSampler", sceneView, sampler)
            pass.bindTexture("DepthSampler", depthView, depthSampler)
            pass.draw(0, 3)
        }
    }
}

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
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.HhaArius.LOGGER
import dev.henny.hha_arius.gear.art.CutPlane
import dev.henny.hha_arius.gear.client.art.ArtAnims
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.Framebuffer
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gl.UniformType
import net.minecraft.client.render.VertexFormats
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.OptionalInt

/**
 * Die Brechung des Klaren Schnitts: Für ein paar Ticks nach dem Treffer verbiegt sich das Bild entlang der Schnittlinie,
 * als wäre die Luft selbst zerschnitten (das Bild weicht zu beiden Seiten der Naht auseinander, leichte Farbsäume, ein
 * haarfeiner Lichtriss). Vollbild-Pass nach dem Muster der Linse des weißen Lochs (Bild kopieren, darüber rechnen),
 * vor der Hand der ersten Person. Abschaltbar über `/ariusc fx 0`. Nur Darstellung.
 */
object CutLens {

    val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(HhaArius.id("pipeline/cut_lens"))
            .withVertexShader(HhaArius.id("core/cut_lens"))
            .withFragmentShader(HhaArius.id("core/cut_lens"))
            .withSampler("SceneSampler")
            .withSampler("DepthSampler")
            .withUniform("CutLens", UniformType.UNIFORM_BUFFER)
            .withoutBlend()
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withColorWrite(true, false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build(),
    )

    /** Dauer der Brechung in Ticks. */
    private const val LIFE = 9f

    private class Cut(val a: net.minecraft.util.math.Vec3d, val b: net.minecraft.util.math.Vec3d, val normal: net.minecraft.util.math.Vec3d, val strength: Float, val start: Float)

    private var cut: Cut? = null
    private val UNIFORM_SIZE = Std140SizeCalculator().putVec4().putVec4().putVec4().get()
    private var copy: GpuTexture? = null
    private var copyView: GpuTextureView? = null
    private var uniforms: GpuBuffer? = null
    private var warned = false

    fun init() {
        WorldRenderEvents.END_MAIN.register { _ ->
            val c = cut ?: return@register
            val client = MinecraftClient.getInstance()
            val td = client.renderTickCounter.getTickProgress(true)
            val t = ArtAnims.ticks + td - c.start
            if (t > LIFE || t < -1f) {
                cut = null
                return@register
            }
            val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
            if (fx <= 0f) return@register
            try {
                draw(client.framebuffer, c, t, fx)
            } catch (e: Exception) {
                if (!warned) {
                    warned = true
                    LOGGER.error("Brechung des Klaren Schnitts fehlgeschlagen; weitere Meldungen werden unterdrückt", e)
                }
            }
        }
    }

    /** Neuer Schnitt: Naht von der Brust bis zum Ende der Ebene, [strength] 0..1 (mit der Ladung). */
    fun trigger(plane: CutPlane, strength: Float) {
        val end = plane.origin.add(plane.forward.multiply(plane.length))
        cut = Cut(plane.origin, end, plane.normal, strength.coerceIn(0f, 1f), ArtAnims.ticks.toFloat())
    }

    private fun ensure(width: Int, height: Int) {
        val device = RenderSystem.getDevice()
        val existing = copy
        if (existing == null || existing.getWidth(0) != width || existing.getHeight(0) != height) {
            copyView?.close()
            existing?.close()
            val usage = GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING
            val texture = device.createTexture({ "hha_arius cut lens scene" }, usage, TextureFormat.RGBA8, width, height, 1, 1)
            copy = texture
            copyView = device.createTextureView(texture)
        }
        if (uniforms == null) {
            uniforms = device.createBuffer({ "hha_arius cut lens uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, UNIFORM_SIZE.toLong())
        }
    }

    private fun draw(framebuffer: Framebuffer, c: Cut, t: Float, fx: Float) {
        val colorTexture = framebuffer.colorAttachment ?: return
        val colorView = framebuffer.colorAttachmentView ?: return
        val depthView = framebuffer.depthAttachmentView ?: return
        // Hüllkurve: schlagartig da, dann ausklingend; die Naht läuft in gut einem Tick über die ganze Länge.
        val rise = (t / 0.6f).coerceIn(0f, 1f)
        val decay = (1f - (t - 0.6f).coerceAtLeast(0f) / (LIFE - 0.6f)).coerceIn(0f, 1f)
        val strength = c.strength * fx * rise * decay * decay
        if (strength <= 0.005f) return
        val reach = ((t + 0.4f) / 1.4f).coerceIn(0f, 1f)
        ensure(colorTexture.getWidth(0), colorTexture.getHeight(0))
        val scene = copy ?: return
        val sceneView = copyView ?: return
        val buffer = uniforms ?: return

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.copyTextureToTexture(colorTexture, scene, 0, 0, 0, 0, 0, scene.getWidth(0), scene.getHeight(0))

        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        MemoryStack.stackPush().use { stack ->
            val data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                .putVec4((c.a.x - cam.x).toFloat(), (c.a.y - cam.y).toFloat(), (c.a.z - cam.z).toFloat(), strength)
                .putVec4((c.b.x - cam.x).toFloat(), (c.b.y - cam.y).toFloat(), (c.b.z - cam.z).toFloat(), reach)
                .putVec4(c.normal.x.toFloat(), c.normal.y.toFloat(), c.normal.z.toFloat(), t / 20f)
                .get()
            encoder.writeToBuffer(buffer.slice(), data)
        }
        val transforms = RenderSystem.getDynamicUniforms()
            .write(RenderSystem.getModelViewMatrix(), Vector4f(1f, 1f, 1f, 1f), Vector3f(), Matrix4f())
        val sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR)
        val depthSampler = RenderSystem.getSamplerCache().get(FilterMode.NEAREST)
        encoder.createRenderPass({ "hha_arius cut lens" }, colorView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(PIPELINE)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setUniform("CutLens", buffer)
            pass.bindTexture("SceneSampler", sceneView, sampler)
            pass.bindTexture("DepthSampler", depthView, depthSampler)
            pass.draw(0, 3)
        }
    }

    fun clear() {
        cut = null
    }
}

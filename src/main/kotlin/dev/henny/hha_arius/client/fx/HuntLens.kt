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
 * Vollbild-Linse der Jagd (seit 1.10.0) nach dem Muster von [CutLens]: Farbsäume bei knappen Treffern, Zoom-Unschärfe
 * beim Sprinten und in Schreckmomenten, Farbentzug zum Herzschlag, Vignette, Frosttönung im Atem. Die Werte setzt
 * [HuntCine] jeden Frame; sind alle null, wird nichts gezeichnet. Abschaltbar über `/ariusc fx 0`.
 */
object HuntLens {

    val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(HhaArius.id("pipeline/hunt_lens"))
            .withVertexShader(HhaArius.id("core/cut_lens"))
            .withFragmentShader(HhaArius.id("core/hunt_lens"))
            .withSampler("SceneSampler")
            .withUniform("HuntLens", UniformType.UNIFORM_BUFFER)
            .withoutBlend()
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withColorWrite(true, false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build(),
    )

    var chroma = 0f
    var blur = 0f
    var desat = 0f
    var vignette = 0f
    var tint = 0
    var tintA = 0f
    var warp = 0f

    private val UNIFORM_SIZE = Std140SizeCalculator().putVec4().putVec4().putVec4().get()
    private var copy: GpuTexture? = null
    private var copyView: GpuTextureView? = null
    private var uniforms: GpuBuffer? = null
    private var warned = false
    private val start = System.nanoTime()

    fun init() {
        WorldRenderEvents.END_MAIN.register { _ ->
            val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
            if (fx <= 0f) return@register
            HuntCine.updateLens()
            if (chroma < 0.01f && blur < 0.01f && desat < 0.01f && vignette < 0.01f && tintA < 0.01f && warp < 0.01f) return@register
            try {
                draw(MinecraftClient.getInstance().framebuffer, fx)
            } catch (e: Exception) {
                if (!warned) {
                    warned = true
                    HhaArius.LOGGER.error("Filmlinse der Jagd fehlgeschlagen; weitere Meldungen werden unterdrückt", e)
                }
            }
        }
    }

    private fun ensure(width: Int, height: Int) {
        val device = RenderSystem.getDevice()
        val existing = copy
        if (existing == null || existing.getWidth(0) != width || existing.getHeight(0) != height) {
            copyView?.close()
            existing?.close()
            val usage = GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING
            val texture = device.createTexture({ "hha_arius hunt lens scene" }, usage, TextureFormat.RGBA8, width, height, 1, 1)
            copy = texture
            copyView = device.createTextureView(texture)
        }
        if (uniforms == null) {
            uniforms = device.createBuffer({ "hha_arius hunt lens uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, UNIFORM_SIZE.toLong())
        }
    }

    private fun draw(framebuffer: Framebuffer, fx: Float) {
        val colorTexture = framebuffer.colorAttachment ?: return
        val colorView = framebuffer.colorAttachmentView ?: return
        ensure(colorTexture.getWidth(0), colorTexture.getHeight(0))
        val scene = copy ?: return
        val sceneView = copyView ?: return
        val buffer = uniforms ?: return
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.copyTextureToTexture(colorTexture, scene, 0, 0, 0, 0, 0, scene.getWidth(0), scene.getHeight(0))
        val r = ((tint shr 16) and 0xFF) / 255f
        val g = ((tint shr 8) and 0xFF) / 255f
        val b = (tint and 0xFF) / 255f
        val time = (System.nanoTime() - start) / 1.0e9f
        MemoryStack.stackPush().use { stack ->
            val data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                .putVec4(chroma * fx, blur * fx, desat, vignette)
                .putVec4(r, g, b, tintA)
                .putVec4(time % 1000f, warp * fx, 0f, 0f)
                .get()
            encoder.writeToBuffer(buffer.slice(), data)
        }
        val transforms = RenderSystem.getDynamicUniforms()
            .write(RenderSystem.getModelViewMatrix(), Vector4f(1f, 1f, 1f, 1f), Vector3f(), Matrix4f())
        val sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR)
        encoder.createRenderPass({ "hha_arius hunt lens" }, colorView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(PIPELINE)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setUniform("HuntLens", buffer)
            pass.bindTexture("SceneSampler", sceneView, sampler)
            pass.draw(0, 3)
        }
    }
}

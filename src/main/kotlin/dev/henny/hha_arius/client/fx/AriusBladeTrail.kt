package dev.henny.hha_arius.client.fx

import dev.henny.hha_arius.boss.AriusForm
import dev.henny.hha_arius.client.model.arius.AriusModel
import dev.henny.hha_arius.client.model.arius.AriusRenderState
import dev.henny.hha_arius.gear.client.art.SwordTrails
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.floor

/**
 * Schwertspur an Arius' Klinge im Bosskampf (dunkle/ernste Form Eisblau, weiße Form Weiß). Als eigener Feature-Renderer
 * angehängt (Fabric-Ereignis in [dev.henny.hha_arius.gear.client.art.ArtRender]), damit Modell und Renderer von Arius
 * unberührt bleiben: Er läuft nach `setAngles`, liest die Lage des Knochens `solvane` und schreibt Ansatz und Spitze
 * der Klinge (Modell-z −3 … −33) jedes Bild mit. Gezeichnet wird nur bei schnellen Schwüngen ([SwordTrails]).
 */
internal class AriusBladeTrail(ctx: FeatureRendererContext<AriusRenderState, AriusModel>) : FeatureRenderer<AriusRenderState, AriusModel>(ctx) {

    private var chain: List<ModelPart>? = null

    private fun chain(model: AriusModel): List<ModelPart>? {
        chain?.let { return it }
        val parts = runCatching {
            val out = ArrayList<ModelPart>()
            var p = model.rootPart
            out.add(p)
            for (name in AriusModel.Keys.SOLVANE.split('/')) {
                p = p.getChild(name)
                out.add(p)
            }
            out
        }.getOrNull() ?: return null
        chain = parts
        return parts
    }

    override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: AriusRenderState, limbAngle: Float, limbDistance: Float) {
        if (state.invisibleToPlayer) return
        val parts = chain(contextModel) ?: return
        if (parts.any { !it.visible }) return
        val td = state.age - floor(state.age)
        matrices.push()
        for (p in parts) p.applyTransform(matrices)
        val m = matrices.peek().positionMatrix
        val base = m.transformPosition(Vector3f(0f, 0f, -3f / 16f))
        val tip = m.transformPosition(Vector3f(0f, 0f, -33f / 16f))
        matrices.pop()
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val style = if (state.form == AriusForm.WHITE) SwordTrails.Style.ARIUS_WHITE else SwordTrails.Style.ARIUS_ICE
        SwordTrails.recordWorld(state.entityId, Vec3d(base.x + cam.x, base.y + cam.y, base.z + cam.z), Vec3d(tip.x + cam.x, tip.y + cam.y, tip.z + cam.z), td, style)
        SwordTrails.renderWorld(queue, state.entityId, td)
    }
}

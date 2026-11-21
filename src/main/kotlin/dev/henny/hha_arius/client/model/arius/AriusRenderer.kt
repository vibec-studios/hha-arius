package dev.henny.hha_arius.client.model.arius

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.boss.AriusBlade
import dev.henny.hha_arius.boss.AriusEntity
import dev.henny.hha_arius.boss.AriusForm
import dev.henny.hha_arius.boss.AriusTimings
import dev.henny.hha_arius.client.fx.BladeAnchors
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.sin

/**
 * Arius: dunkler Atlas für DARK und SERIOUS, weißer Atlas für WHITE (gleiche UVs). Zwei Leuchtschichten wie bei Kronos
 * ([RenderLayers.eyes], Alpha moduliert): die dezente Leuchtmaske des Atlas (Klingenkante, Rüstungsnähte) und die Augen
 * aus einer eigenen Textur, deren Stärke [AriusEntity.eyeGlow] und [AriusEntity.lead] steuern.
 *
 * Abschnitt III ([AriusBlade.ICE]): Solvane ist der Drache, Arius führt eine Klinge aus weißem Eislicht. Gleiche
 * Geometrie am selben Knochen, gezeichnet von einer zweiten Modellinstanz, die nur das Schwert zeigt
 * ([AriusModel] mit `bladeOnly`): durchscheinend-leuchtend (entityTranslucentEmissive) plus ein additiver Schimmer.
 * Nebenbei schreibt der Renderer die Lage von Klinge und Scheide in [BladeAnchors] (für den Lichtstrom).
 */
class AriusRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<AriusEntity, AriusRenderState, AriusModel>(context, AriusModel(context.getPart(AriusRenderers.LAYER)), 0.45f) {

    private val dark = tex("arius_dark")
    private val white = tex("arius_white")
    private val darkGlow = RenderLayers.eyes(tex("arius_dark_glow"))
    private val whiteGlow = RenderLayers.eyes(tex("arius_white_glow"))
    private val eyes = RenderLayers.eyes(tex("arius_eyes"))
    private val iceModel = AriusModel(context.getPart(AriusRenderers.LAYER), bladeOnly = true)
    private val iceBody = RenderLayers.entityTranslucentEmissive(white)
    private val iceGlow = RenderLayers.eyes(white)
    private var bladeChain: List<ModelPart>? = null
    private var sheathChain: List<ModelPart>? = null

    init {
        addFeature(object : FeatureRenderer<AriusRenderState, AriusModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: AriusRenderState,
                                limbAngle: Float, limbDistance: Float) {
                if (state.invisibleToPlayer) return
                val batch = queue.getBatchingQueue(1)
                // Nähte und Klingenkante: dezent, atmen langsam mit.
                val seam = (0.72f + 0.1f * sin(state.age * 0.07f)) * (if (state.form == AriusForm.ECHO) 0.5f else 1f)
                val layer = if (state.form == AriusForm.WHITE) whiteGlow else darkGlow
                batch.submitModel(contextModel, state, matrices, layer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, tint(seam), null, 0, null)
                // Augen: stark; das goldene führt (Textur), lead macht beide heller.
                val base = MathHelper.clamp(state.eyeGlow / 100f, 0f, 1f)
                val eye = MathHelper.clamp(0.3f + 0.7f * base + (if (state.lead) 0.25f else 0f), 0f, 1f)
                batch.submitModel(contextModel, state, matrices, eyes, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, tint(eye), null, 0, null)
                if (state.lead || base > 0.85f) {
                    val extra = if (state.lead) 0.7f + 0.2f * sin(state.age * 0.25f) else (base - 0.85f) * 4f
                    batch.submitModel(contextModel, state, matrices, eyes, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, tint(extra), null, 0, null)
                }
                // Eisklinge: kühler, durchscheinender Körper, darüber ein leiser, atmender Schimmer.
                if (state.blade == AriusBlade.ICE) {
                    val shimmer = 0.42f + 0.12f * sin(state.age * 0.21f) + (if (state.lead) 0.12f else 0f)
                    batch.submitModel(iceModel, state, matrices, iceBody, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, iceTint(0.62f), null, 0, null)
                    batch.submitModel(iceModel, state, matrices, iceGlow, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, iceTint(shimmer), null, 0, null)
                }
                anchors(matrices, state)
            }
        })
    }

    private fun tint(alpha: Float): Int = ((MathHelper.clamp(alpha, 0f, 1f) * 255f).toInt() shl 24) or 0xFFFFFF

    /** Eislicht: helles Blauweiß. */
    private fun iceTint(alpha: Float): Int = ((MathHelper.clamp(alpha, 0f, 1f) * 255f).toInt() shl 24) or 0xD4F0FF

    private fun chainTo(path: String): List<ModelPart>? = runCatching {
        val out = ArrayList<ModelPart>()
        var p = model.rootPart
        out.add(p)
        for (name in path.split('/')) {
            p = p.getChild(name)
            out.add(p)
        }
        out.toList()
    }.getOrNull()

    /**
     * Lage von Klingenansatz, Spitze und Scheidenmund in Weltkoordinaten (für Effekte, siehe [BladeAnchors]). Liest die
     * Knochen des Hauptmodells; wie bei der Schwertspur gilt die Haltung des zuletzt gezeichneten Bildes.
     */
    private fun anchors(matrices: MatrixStack, state: AriusRenderState) {
        val blade = bladeChain ?: chainTo(AriusModel.Keys.SOLVANE)?.also { bladeChain = it } ?: return
        val sheath = sheathChain ?: chainTo(AriusModel.Keys.SHEATH_HILT)?.also { sheathChain = it } ?: return
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        fun world(v: Vector3f) = Vec3d(v.x + cam.x, v.y + cam.y, v.z + cam.z)
        matrices.push()
        for (p in blade) p.applyTransform(matrices)
        val m = matrices.peek().positionMatrix
        val base = world(m.transformPosition(Vector3f(0f, 0f, -3f / 16f)))
        val tip = world(m.transformPosition(Vector3f(0f, 0f, -33f / 16f)))
        matrices.pop()
        matrices.push()
        for (p in sheath) p.applyTransform(matrices)
        val mouth = world(matrices.peek().positionMatrix.transformPosition(Vector3f(0f, 0f, 0f)))
        matrices.pop()
        BladeAnchors.record(state.entityId, base, tip, mouth)
    }

    override fun createRenderState() = AriusRenderState()

    override fun getTexture(state: AriusRenderState): Identifier = if (state.form == AriusForm.WHITE || state.form == AriusForm.ECHO) white else dark

    /** Seit 1.10.0: Klingenabsicht (Nachbild) – durchscheinend und leuchtend statt fest. */
    private val echoBody = RenderLayers.entityTranslucentEmissive(white)

    override fun getRenderLayer(state: AriusRenderState, showBody: Boolean, translucent: Boolean, showOutline: Boolean): net.minecraft.client.render.RenderLayer? =
        if (state.form == AriusForm.ECHO) echoBody else super.getRenderLayer(state, showBody, translucent, showOutline)

    override fun getMixColor(state: AriusRenderState): Int {
        if (state.form != AriusForm.ECHO) return super.getMixColor(state)
        val a = 0.42f + 0.1f * sin(state.age * 0.3f)
        return ((MathHelper.clamp(a, 0f, 1f) * 255f).toInt() shl 24) or 0xBFE6FF
    }

    override fun updateRenderState(entity: AriusEntity, state: AriusRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.entityId = entity.id
        state.bossPose = entity.pose
        state.previousPose = entity.previousPose
        state.poseChangedAge = entity.poseChangedAge
        state.poseTime = (entity.age - entity.poseChangedAge) + tickDelta
        val pose = entity.pose.coerceIn(0, AriusTimings.ARIUS_WINDUP.size - 1)
        state.windup = if (entity.windup > 0) entity.windup else AriusTimings.ARIUS_WINDUP[pose]
        state.form = entity.form
        state.sheathed = entity.sheathed
        state.blade = entity.bladeState
        state.eyeGlow = entity.eyeGlow
        state.lead = entity.lead
        state.open = entity.open
        state.counter = entity.counterStance
        state.sideSign = entity.sideSign
        state.walk = entity.walkAmount
        state.walkPhase = entity.walkPhase
    }

    override fun scale(state: AriusRenderState, matrices: MatrixStack) {
        // Seit 1.8.0 (Crix: „neben einem Spieler sieht er komisch aus, größer und breiter?“): etwa 2,15 Blöcke hoch
        // und 12 % breiter als vorher – ein großer Krieger, aber noch ein Mensch.
        matrices.scale(SCALE * WIDE, SCALE, SCALE * WIDE)
    }

    override fun canBeCulled(entity: AriusEntity): Boolean = false

    override fun hasLabel(entity: AriusEntity, squaredDistance: Double): Boolean = false

    private companion object {
        /** Sohle bis Scheitel (Haarkappe) 62 Einheiten × 0,49 = 1,90 Blöcke; Haarspitzen ≈ 1,97, Trefferbox 1,95. */
        const val SCALE = 0.555f
        const val WIDE = 1.12f
        const val FULL_BRIGHT = 0xF000F0

        fun tex(name: String): Identifier = HhaArius.id("textures/entity/$name.png")
    }
}

package dev.henny.hha_arius.client.model.nivaris

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.boss.AriusTimings
import dev.henny.hha_arius.boss.NivarisEntity
import dev.henny.hha_arius.boss.NivarisMode
import dev.henny.hha_arius.boss.NivarisShape
import dev.henny.hha_arius.client.fx.DragonVeil
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.Frustum
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.command.RenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import org.joml.Matrix3f
import org.joml.Quaternionf
import java.util.WeakHashMap
import kotlin.math.sin

/**
 * Renderer der Drachin als Schlangendrache: setzt Kopf, 40 Glieder, Läufe, Schwingen und Schwanzfächer einzeln auf den
 * Körper, den [NivarisBody] je Frame berechnet (Matrix je Teil, die Teile selbst bleiben unverdreht). Farbtextur als
 * Cutout (Membranlöcher), darüber die drei Leuchtschichten mit denselben UVs: Augen + Hornspitzen
 * ([NivarisEntity.eyeGlow], heller bei [NivarisEntity.lead]), Kehle + Maul + Herzkern ([NivarisEntity.throatGlow]) und
 * die Resonanzlinien (nur bei [NivarisEntity.resonance]).
 *
 * Auflösen und Entstehen ([NivarisEntity.veil], Solvane als Licht): Teil für Teil schrumpft der Körper von der
 * Schwanzspitze zum Kopf (beim Entstehen umgekehrt) und leuchtet dabei weiß auf; Lichtschein, Funken und Splitter an
 * der Stelle jedes Teils zeichnet [DragonVeil]. Cutout kann nicht ausblenden, daher Schrumpfen + Leuchtüberzug.
 *
 * Der Körper reicht bis zu 96 Blöcke von der Entity weg (an der Nadel noch weiter): Culling ist aus, gezeichnet wird
 * immer, solange der Client die Entity kennt.
 */
class NivarisRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<NivarisEntity, NivarisRenderState, NivarisModel>(context, NivarisModel(context.getPart(NivarisRenderers.LAYER)), 3.2f) {

    private val texture = HhaArius.id("textures/entity/nivaris.png")
    private val eyesLayer = RenderLayers.eyes(HhaArius.id("textures/entity/nivaris_glow_eyes.png"))
    private val heartLayer = RenderLayers.eyes(HhaArius.id("textures/entity/nivaris_glow_heart.png"))
    private val resonanceLayer = RenderLayers.eyes(HhaArius.id("textures/entity/nivaris_resonance.png"))

    /** Leuchtüberzug beim Auflösen: dieselbe Farbtextur, selbstleuchtend und weiß-eisig getönt. */
    private val sheenLayer = RenderLayers.eyes(texture)

    /** Weiche Überblendung Boden ↔ Flug je Entity (Flag wechselt sofort, das Modell nicht). */
    private val flyBlend = WeakHashMap<NivarisEntity, FloatArray>()

    /** Körper (Spur, Überblendung) je Entity. */
    private val bodies = WeakHashMap<NivarisEntity, NivarisBody>()

    private val rot = Quaternionf()
    private val m3 = Matrix3f()

    override fun createRenderState() = NivarisRenderState()
    override fun getTexture(state: NivarisRenderState): Identifier = texture

    override fun updateRenderState(entity: NivarisEntity, state: NivarisRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        val pose = entity.pose
        state.bossPose = pose
        state.previousPose = entity.previousPose
        state.poseTime = (entity.age - entity.poseChangedAge) + tickDelta
        state.windup = (if (entity.windup > 0) entity.windup else AriusTimings.NIVARIS_WINDUP.getOrElse(pose) { 0 }).toFloat()
        state.walk = entity.walkAmount
        state.walkPhase = entity.walkPhase
        state.throatGlow = entity.throatGlow
        state.eyeGlow = entity.eyeGlow
        state.lead = entity.lead
        state.resonance = entity.resonance
        state.mode = entity.mode
        // Flug-Überblendung: etwa 10 Ticks von Boden zu Luft und zurück.
        val now = entity.age + tickDelta
        val blend = flyBlend.getOrPut(entity) { floatArrayOf(if (entity.flying) 1f else 0f, now) }
        val dt = MathHelper.clamp(now - blend[1], 0f, 5f)
        val target = if (entity.flying) 1f else 0f
        val step = dt * 0.1f
        blend[0] = if (blend[0] < target) minOf(target, blend[0] + step) else maxOf(target, blend[0] - step)
        blend[1] = now
        state.fly = blend[0]
        model.pose(state)
        bodies.getOrPut(entity) { NivarisBody() }.update(entity, state, tickDelta)
        dev.henny.hha_arius.client.fx.DragonHits.update(entity.id, state)
        // Schleier: je Teil Maßstab und Leuchten; die Lage geht an DragonVeil (Lichtschein, Funken, Splitter).
        val veil = entity.veilAt(tickDelta)
        state.veil = veil
        for (j in 0 until DragonVeil.PARTS) {
            val l = DragonVeil.progress(veil, j)
            state.shrink[j] = DragonVeil.shrink(l)
            state.sheen[j] = DragonVeil.sheen(l)
        }
        if (state.bodyReady && (veil > 0f || DragonVeil.tracks(entity.id))) DragonVeil.update(entity.id, entity.entityWorld.time, state, veil)
    }

    override fun render(state: NivarisRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
        if (!state.bodyReady || state.invisible || state.veil >= 1f) return
        val overlay = getOverlay(state, 0f)
        model.openJaw(state.p[NivarisModel.JAW])
        model.animateChildren(state)
        draw(queue, matrices, state, model.getLayer(texture), overlay, -1, ALL, true)
        val batch = queue.getBatchingQueue(1)
        // Augen: Grundglimmen, eyeGlow hebt; führt sie den Takt, leuchten sie heller und pulsieren ruhig.
        var eyes = 0.3f + state.eyeGlow / 100f * 0.7f
        if (state.lead) eyes = 0.85f + 0.15f * sin(state.age * 0.18f)
        glow(batch, matrices, state, eyesLayer, eyes, HEAD)
        // Kehle, Maul und Herzkern: kaum sichtbar in Ruhe, beim Atemholen hell mit leichtem Flackern.
        val throat = state.throatGlow / 100f
        glow(batch, matrices, state, heartLayer, 0.1f + throat * 0.9f * (0.92f + 0.08f * sin(state.age * 0.9f)), FRONT)
        if (state.resonance) glow(batch, matrices, state, resonanceLayer, 0.8f + 0.2f * sin(state.age * 0.25f), ALL)
        // Leuchtüberzug der Teile, die gerade zerfallen oder entstehen (Farbe je Teil aus state.sheen).
        if (state.veil > 0f) draw(batch, matrices, state, sheenLayer, net.minecraft.client.render.OverlayTexture.DEFAULT_UV, SHEEN, ALL, false)
    }

    private fun glow(q: RenderCommandQueue, matrices: MatrixStack, state: NivarisRenderState, layer: RenderLayer, alpha: Float, which: Int) {
        val a = (MathHelper.clamp(alpha, 0f, 1f) * 255f).toInt()
        if (a <= 2) return
        draw(q, matrices, state, layer, net.minecraft.client.render.OverlayTexture.DEFAULT_UV, (a shl 24) or 0xFFFFFF, which, false)
    }

    /** Alle (oder einige) Teile auf den Körper setzen. [lit] = Weltlicht je Glied, sonst voll leuchtend. */
    private fun draw(q: RenderCommandQueue, matrices: MatrixStack, s: NivarisRenderState, layer: RenderLayer, overlay: Int, color: Int, which: Int, lit: Boolean) {
        val m = model
        val scale = NivarisShape.SCALE
        fun light(i: Int) = if (lit) s.lights[i] else FULL
        val sheenPass = color == SHEEN
        // Teil j sichtbar? Im Überzug nur Teile im Übergang, mit eigener Helligkeit; sonst alle nicht ganz geschrumpften.
        fun shown(j: Int) = if (sheenPass) s.sheen[j] > 0.01f && s.shrink[j] > 0.02f else s.shrink[j] > 0.02f
        fun tint(j: Int) = if (sheenPass) ((MathHelper.clamp(s.sheen[j], 0f, 1f) * 255f).toInt() shl 24) or SHEEN_RGB else color

        // Kopf (mit Kiefer).
        if (shown(0)) {
            matrices.push()
            matrices.translate(s.px[0], s.py[0], s.pz[0])
            orient(matrices, s.headFrame, 0)
            val k = scale * s.shrink[0]
            matrices.scale(k, k, k)
            q.submitModelPart(m.head, matrices, layer, light(0), overlay, null, tint(0), null)
            matrices.pop()
        }
        if (which == HEAD) return

        val count = if (which == FRONT) 6 else NivarisShape.SEGMENTS
        for (i in 0 until count) {
            if (!shown(i + 1)) continue
            matrices.push()
            matrices.translate(s.px[i], s.py[i], s.pz[i])
            orient(matrices, s.frame, i * 9)
            // Beim Auflösen schrumpft das Glied (mit Läufen und Schwingen) zu seinem vorderen Gelenk.
            val k = s.shrink[i + 1]
            if (k < 1f) matrices.scale(k, k, k)
            val g = scale * s.girth[i]
            val st = s.stretch[i]
            // Etwas länger als der Abstand, damit an Biegungen keine Fugen aufgehen.
            matrices.scale(g, g, scale * st * 1.03f)
            q.submitModelPart(m.glied(i), matrices, layer, light(i), overlay, null, tint(i + 1), null)
            if (which == ALL) attachments(q, matrices, s, i, layer, light(i), overlay, tint(i + 1), s.girth[i], st)
            matrices.pop()
        }
        if (which != ALL) return

        // Schwanzfächer am letzten Punkt, unverjüngt (für den Endquerschnitt gebaut).
        val last = NivarisShape.SEGMENTS
        if (!shown(DragonVeil.PARTS - 1)) return
        matrices.push()
        matrices.translate(s.px[last], s.py[last], s.pz[last])
        orient(matrices, s.frame, (last - 1) * 9)
        val k = scale * s.shrink[DragonVeil.PARTS - 1]
        matrices.scale(k, k, k)
        q.submitModelPart(m.tailFan, matrices, layer, light(last), overlay, null, tint(DragonVeil.PARTS - 1), null)
        matrices.pop()
    }

    /** Läufe und Schwingen im Raum ihres Glieds (Ansatzstellen aus tools/assets/nivaris_model.py, ATTACH). */
    private fun attachments(
        q: RenderCommandQueue, matrices: MatrixStack, s: NivarisRenderState, i: Int, layer: RenderLayer, light: Int, overlay: Int, color: Int,
        girth: Float, stretch: Float,
    ) {
        val p = s.p
        val m = model
        when (i) {
            NivarisShape.WING_GLIED -> {
                // Haltungen aus docs/NIVARIS_SERPENT.md (Grad, für wing_r; wing_l mit umgekehrtem yaw und roll):
                // gespannt (0,0,0), Schlag oben (0,6,40) / unten (0,4,−34), angelegt (180,84,−45), eng (180,80,−70).
                // Zwischen angelegt und gespannt per slerp, damit sich die Schwinge um den Knochen dreht.
                val w = MathHelper.clamp(p[NivarisModel.WING], 0f, 1f)
                val flap = MathHelper.clamp(p[NivarisModel.FLAP], -1.2f, 1.2f)
                val tight = MathHelper.clamp(p[NivarisModel.TIGHT], 0f, 1f)
                val deg = MathHelper.RADIANS_PER_DEGREE
                val flapYaw = (if (flap > 0f) 6f * flap else -4f * flap) * deg
                val flapRoll = (if (flap > 0f) 40f * flap else 34f * flap) * deg
                val foldYaw = (84f - 4f * tight) * deg
                val foldRoll = (-45f - 25f * tight) * deg
                for (sgn in intArrayOf(1, -1)) {
                    val fold = Quaternionf().rotationZYX(sgn * foldRoll, sgn * foldYaw, 180f * deg)
                    val spread = Quaternionf().rotationZYX(sgn * flapRoll, sgn * flapYaw, 0f)
                    val r = fold.slerp(spread, w)
                    limb(q, matrices, if (sgn > 0) m.wingR else m.wingL, -6f * sgn, -6f, 4f, r, girth, stretch, layer, light, overlay, color)
                }
            }
            NivarisShape.FORE_GLIED -> {
                val fore = p[NivarisModel.FORE]
                val swipe = p[NivarisModel.SWIPE]
                limb(q, matrices, m.foreR, -7f, 3f, 8f, Quaternionf().rotationZYX(-0.6f * swipe, 0.3f * swipe, fore - 0.9f * swipe), girth, stretch, layer, light, overlay, color)
                limb(q, matrices, m.foreL, 7f, 3f, 8f, Quaternionf().rotationZYX(0f, 0f, fore), girth, stretch, layer, light, overlay, color)
            }
            NivarisShape.HIND_GLIED -> {
                val hind = p[NivarisModel.HIND]
                limb(q, matrices, m.hindR, -6f, 3f, 8f, Quaternionf().rotationZYX(0f, 0f, hind), girth, stretch, layer, light, overlay, color)
                limb(q, matrices, m.hindL, 6f, 3f, 8f, Quaternionf().rotationZYX(0f, 0f, hind), girth, stretch, layer, light, overlay, color)
            }
        }
    }

    private fun limb(
        q: RenderCommandQueue, matrices: MatrixStack, part: ModelPart, x: Float, y: Float, z: Float, r: Quaternionf, girth: Float, stretch: Float,
        layer: RenderLayer, light: Int, overlay: Int, color: Int,
    ) {
        matrices.push()
        // Ansatzpunkt im verjüngten Gliedraum, das Teil selbst unverjüngt und ohne die Längsdehnung des Glieds.
        matrices.translate(x / 16f, y / 16f, z / 16f)
        matrices.scale(1f / girth, 1f / girth, 1f / (stretch * 1.03f))
        matrices.multiply(r)
        q.submitModelPart(part, matrices, layer, light, overlay, null, color, null)
        matrices.pop()
    }

    /** Dreht den Modellraum auf den Rahmen (Spalten: Modell-x, Modell-y, Modell-z in Weltrichtung). */
    private fun orient(matrices: MatrixStack, f: FloatArray, at: Int) {
        m3.set(f[at], f[at + 1], f[at + 2], f[at + 3], f[at + 4], f[at + 5], f[at + 6], f[at + 7], f[at + 8])
        rot.setFromNormalized(m3)
        if (rot.isFinite) matrices.multiply(rot)
    }

    override fun getShadowRadius(state: NivarisRenderState): Float =
        if (state.mode != NivarisMode.NORMAL) 0f else super.getShadowRadius(state) * (1f - state.veil)

    override fun shouldRender(entity: NivarisEntity, frustum: Frustum, x: Double, y: Double, z: Double): Boolean = true

    override fun canBeCulled(entity: NivarisEntity): Boolean = false

    override fun hasLabel(entity: NivarisEntity, squaredDistance: Double): Boolean = false

    private companion object {
        const val ALL = 0
        const val HEAD = 1
        const val FRONT = 2
        const val FULL = 0xF000F0

        /** Marker-Farbe für den Leuchtüberzug in [draw] (Farbe kommt dann je Teil aus state.sheen). */
        const val SHEEN = 0x00E6F6FF
        const val SHEEN_RGB = 0xE6F6FF
    }
}

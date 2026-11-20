package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.boss.NivarisEntity
import dev.henny.hha_arius.boss.RideGripS2C
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.entity.Entity
import net.minecraft.text.Text
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ritt auf Solvanes Rücken im Client (seit 1.8.1, Crix: „man soll nicht sitzen, sondern sich an einem Eisdorn auf
 * ihrem Rücken festhalten; nach dem ersten Fehler nur noch mit einer Hand“):
 * * Vor jedem Reiter ragt ein Eisdorn schräg aus ihrem Rücken ([renderSpikes]).
 * * Dritte Person ([applyPose]): kniend, weit vorgebeugt, beide Hände am Dorn; mit nur einer Hand rudert der andere Arm
 *   frei, der Körper schwankt, ein Bein rutscht ab.
 * * Erste Person: [handTargets] liefert die Griffpunkte am Dorn im Blickraum; gezeichnet werden die Arme in
 *   [dev.henny.hha_arius.gear.client.art.GauntletFirstPerson].
 * Den Griff (2/1/0) meldet der Server je Spieler ([RideGripS2C]); ohne Meldung gelten beide Hände.
 */
object RideClient {

    private val grips = HashMap<Int, Int>()

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(RideGripS2C.ID) { p, _ ->
            if (p.grip <= 0) grips.remove(p.entityId) else grips[p.entityId] = p.grip
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> grips.clear() }
        WorldRenderEvents.END_MAIN.register { context ->
            val client = MinecraftClient.getInstance()
            val world = client.world ?: return@register
            if (world.players.none { it.vehicle is NivarisEntity }) return@register
            val tickDelta = client.renderTickCounter.getTickProgress(true)
            renderSpikes(VfxDraw(context.consumers(), client.gameRenderer.camera, tickDelta), tickDelta)
        }
    }

    /** Griff des Spielers [e]: 0 = reitet nicht auf ihr, sonst 2 (beide Hände) oder 1 (eine Hand). */
    @JvmStatic
    fun grip(e: Entity?): Int {
        if (e == null || e.vehicle !is NivarisEntity) return 0
        return grips[e.id] ?: 2
    }

    // --- Eisdorn ---------------------------------------------------------------------------------------------------

    private class Spike(val base: Vec3d, val tip: Vec3d) {
        fun at(k: Double): Vec3d = base.lerp(tip, k)
    }

    /** Dorn vor dem Reiter [e] (Weltkoordinaten): wurzelt im Rücken vor den Knien, neigt sich zur Brust hin. */
    private fun spike(e: Entity, tickDelta: Float): Spike? {
        val d = e.vehicle as? NivarisEntity ?: return null
        val yaw = MathHelper.lerpAngleDegrees(tickDelta, d.lastBodyYaw, d.bodyYaw) * MathHelper.RADIANS_PER_DEGREE
        val fwd = Vec3d(-sin(yaw.toDouble()), 0.0, cos(yaw.toDouble()))
        val feet = e.getLerpedPos(tickDelta)
        val base = feet.add(fwd.multiply(0.95)).add(0.0, -0.35, 0.0)
        val tip = base.add(fwd.multiply(-0.3)).add(0.0, 1.85, 0.0)
        return Spike(base, tip)
    }

    private fun renderSpikes(d: VfxDraw, tickDelta: Float) {
        val world = MinecraftClient.getInstance().world ?: return
        for (p in world.players) {
            val s = spike(p, tickDelta) ?: continue
            val pts = List(7) { s.at(it / 6.0) }
            d.tube(VfxLayers.SHARD_ALPHA, pts, { f -> 0.17 * (1.0 - f * 0.85) }, { f -> withAlpha(0xFFCFEFFF.toInt(), 0.92f - 0.25f * f) }, 5, 1f, 0f)
            d.tube(VfxLayers.GLOW_ADD, pts, { f -> 0.24 * (1.0 - f * 0.8) }, { f -> withAlpha(0xFF9FD8FF.toInt(), 0.35f * (1f - f)) }, 5, 1f, 0f)
            d.billboard(VfxLayers.GLOW_ADD, s.tip, 0.35, withAlpha(0xFFE6F6FF.toInt(), 0.6f), 0.0)
        }
    }

    /**
     * Griffpunkte am Dorn des eigenen Spielers im Blickraum der ersten Person (+x rechts, +y oben, −z vorn): obere
     * (rechte) und untere (linke) Hand. Null, wenn er nicht reitet.
     */
    fun handTargets(tickDelta: Float): Pair<Vector3f, Vector3f>? {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return null
        val s = spike(player, tickDelta) ?: return null
        val cam = client.gameRenderer.camera
        val inv = Quaternionf(cam.rotation).conjugate()
        fun view(p: Vec3d): Vector3f = inv.transform(Vector3f((p.x - cam.cameraPos.x).toFloat(), (p.y - cam.cameraPos.y).toFloat(), (p.z - cam.cameraPos.z).toFloat()))
        return view(s.at(0.74)) to view(s.at(0.5))
    }

    // --- Haltung (dritte Person) ------------------------------------------------------------------------------------

    /** Ende von PlayerEntityModel.setAngles: ersetzt die Sitzhaltung durch das Festhalten am Dorn. */
    @JvmStatic
    fun applyPose(model: PlayerEntityModel, state: PlayerEntityRenderState) {
        if (!state.hasVehicle) return
        val e = MinecraftClient.getInstance().world?.getEntityById(state.id) ?: return
        val grip = grip(e)
        if (grip <= 0) return
        val t = state.age
        val sway = sin(t * 0.21f)
        // Weit vorgebeugt und gekniet (Gelenke wie beim Schleichen, nur tiefer).
        model.body.pitch = 0.62f + 0.03f * sway
        model.body.roll = if (grip == 1) 0.16f * sin(t * 0.25f) else 0.02f * sway
        model.body.yaw = 0f
        model.body.originY = 3.4f
        model.head.originY = 4.4f
        model.rightArm.originY = 5.4f
        model.leftArm.originY = 5.4f
        for (leg in arrayOf(model.rightLeg, model.leftLeg)) {
            leg.originY = 12.2f
            leg.originZ = 4.6f
        }
        // Rechts kniet, links vorn aufgestellt.
        model.rightLeg.pitch = 0.42f; model.rightLeg.yaw = 0.1f; model.rightLeg.roll = 0.08f
        model.leftLeg.pitch = -0.78f; model.leftLeg.yaw = -0.08f; model.leftLeg.roll = -0.06f
        // Rechte Hand oben am Dorn.
        model.rightArm.pitch = -1.32f + 0.03f * sway
        model.rightArm.yaw = -0.3f
        model.rightArm.roll = 0f
        if (grip >= 2) {
            // Linke Hand darunter, beide Fäuste um den Dorn.
            model.leftArm.pitch = -1.02f + 0.03f * sway
            model.leftArm.yaw = 0.36f
            model.leftArm.roll = 0f
        } else {
            // Nur noch eine Hand: der linke Arm rudert frei, das linke Bein rutscht ab.
            model.leftArm.pitch = -2.3f + 0.55f * sin(t * 0.45f)
            model.leftArm.yaw = 0.25f
            model.leftArm.roll = -(0.55f + 0.35f * sin(t * 0.37f))
            model.leftLeg.pitch = -0.15f + 0.45f * sin(t * 0.3f)
            model.leftLeg.roll = -0.4f
            model.rightArm.yaw = -0.12f
        }
    }

    // --- Anzeige ------------------------------------------------------------------------------------------------------

    /** Kleine Anzeige oben: mit wie vielen Händen man sich hält. */
    fun renderHud(ctx: DrawContext) {
        val player = MinecraftClient.getInstance().player ?: return
        val g = grip(player)
        if (g <= 0) return
        val tr = MinecraftClient.getInstance().textRenderer
        val text = Text.translatable(if (g >= 2) "hha_arius.ride.grip_two" else "hha_arius.ride.grip_one")
        val col = if (g >= 2) 0xFFBFE6FF.toInt() else 0xFFFF8A7A.toInt()
        ctx.drawText(tr, text, ctx.scaledWindowWidth / 2 - tr.getWidth(text) / 2, 22, col, true)
    }
}

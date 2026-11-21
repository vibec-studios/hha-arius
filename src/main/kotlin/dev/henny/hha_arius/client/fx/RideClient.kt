package dev.henny.hha_arius.client.fx

import dev.henny.hha_arius.boss.NivarisEntity
import dev.henny.hha_arius.boss.NivarisShape
import dev.henny.hha_arius.boss.RideGripS2C
import dev.henny.hha_arius.boss.RideHooks
import dev.henny.hha_arius.client.model.nivaris.NivarisRenderState
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.text.Text
import net.minecraft.util.math.Vec3d
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.sin

/**
 * Ritt auf Solvanes Rücken im Client (seit 1.10.0 neu, Crix: „man soll sich am Eisdorn auf ihrem Rücken festhalten –
 * die hohe, blasse Kristallplatte –, die Beine stehen nicht auf ihr, sie flattern im Wind“):
 * * **Am echten Dorn:** Reiter `i` greift die Kristallplatte (`crest`) von Glied [RideHooks.glied] des gezeichneten
 *   Körpers. [anchor] rechnet den Griffpunkt je Frame aus dem Körper ([dev.henny.hha_arius.client.model.nivaris.NivarisBody]),
 *   [riderPos] setzt die Entity des Reiters im Tick dorthin (die Kamera der ersten Person sitzt so im Kopf des Reiters).
 * * **Dritte Person:** [transform] legt die Figur waagerecht in den Fahrtwind: Kopf zum Dorn, Brust zum Rücken der
 *   Drachin, die Füße schweben hinter ihr. [applyPose]: beide Arme umklammern den Dorn, die Beine flattern; nach einem
 *   Fehler hält nur noch eine Hand, der andere Arm reißt im Wind nach hinten.
 * * **Erste Person:** [handTargets] liefert die Griffpunkte links und rechts am Dorn im Blickraum (gezeichnet in
 *   [dev.henny.hha_arius.gear.client.art.GauntletFirstPerson]).
 * Den Griff (2/1/0) meldet der Server je Spieler ([RideGripS2C]); ohne Meldung gelten beide Hände.
 */
object RideClient {

    private val grips = HashMap<Int, Int>()

    /** Lage eines Reiters relativ zur Render-Position der Drachin: Griff, Kopfrichtung (vorn), Rückenrichtung (oben). */
    private class Anchor(val grip: Vec3d, val head: Vec3d, val back: Vec3d) {
        val right: Vec3d = head.crossProduct(back).normalize()

        /** Fußpunkt der liegenden Figur. */
        val feet: Vec3d = grip.subtract(head.multiply(REACH)).subtract(back.multiply(BELOW))

        /** Augen (Kopfmitte): hier sitzt die Kamera der ersten Person. */
        val eye: Vec3d = feet.add(head.multiply(HEAD)).add(back.multiply(0.06))

        /** Entity-Position des Reiters (stehende Augenhöhe abgezogen). */
        val entity: Vec3d = eye.subtract(0.0, RideHooks.EYE, 0.0)
    }

    /** Je Drachin (Entity-ID) die Reiterplätze aus dem letzten Frame. */
    private val anchors = HashMap<Int, Array<Anchor?>>()

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(RideGripS2C.ID) { p, _ ->
            if (p.grip <= 0) grips.remove(p.entityId) else grips[p.entityId] = p.grip
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            grips.clear()
            anchors.clear()
        }
        RideHooks.riderPos = { d, passenger -> riderPos(d, passenger) }
    }

    /** Griff des Spielers [e]: 0 = reitet nicht auf ihr, sonst 2 (beide Hände) oder 1 (eine Hand). */
    @JvmStatic
    fun grip(e: Entity?): Int {
        if (e == null || e.vehicle !is NivarisEntity) return 0
        return grips[e.id] ?: 2
    }

    // --- Griffpunkte am gezeichneten Körper ------------------------------------------------------------------------

    /**
     * Aus dem fertigen Körper eines Frames ([pts] Welt, Rahmen in [s]): Griffpunkt an der Kristallplatte jedes
     * Reiterglieds. Modell (tools/assets/nivaris_model.py, `crest`): Drehpunkt (0, −6,5, 9), Höhe 15, nach hinten
     * geneigt (−34°); gegriffen bei 72 % der Höhe, wo der Kristall noch kräftig ist.
     */
    fun anchor(d: NivarisEntity, s: NivarisRenderState, pts: Array<Vec3d>) {
        val n = d.passengerList.size.coerceAtMost(8)
        val out = arrayOfNulls<Anchor>(n)
        val base = Vec3d(s.x, s.y, s.z)
        val q = 0.72
        val c = kotlin.math.cos(-34.0 * PI / 180.0)
        val sn = kotlin.math.sin(-34.0 * PI / 180.0)
        val uy = -6.5 + (-15.0 * q) * c
        val uz = 9.0 + (-15.0 * q) * sn
        for (i in 0 until n) {
            val g = RideHooks.glied(i).coerceAtMost(NivarisShape.SEGMENTS - 1)
            val at = g * 9
            val f = s.frame
            val belly = Vec3d(f[at + 3].toDouble(), f[at + 4].toDouble(), f[at + 5].toDouble())
            val backZ = Vec3d(f[at + 6].toDouble(), f[at + 7].toDouble(), f[at + 8].toDouble())
            val gs = NivarisShape.SCALE * s.girth[g] / 16.0
            val zs = NivarisShape.SCALE * s.stretch[g] * 1.03 / 16.0
            val grip = pts[g].add(belly.multiply(uy * gs)).add(backZ.multiply(uz * zs))
            val head = backZ.negate().normalize()
            var up = belly.negate()
            up = up.subtract(head.multiply(up.dotProduct(head)))
            if (up.lengthSquared() < 1e-6) up = Vec3d(0.0, 1.0, 0.0)
            out[i] = Anchor(grip.subtract(base), head, up.normalize())
        }
        anchors[d.id] = out
    }

    private fun anchorOf(e: Entity): Anchor? {
        val d = e.vehicle as? NivarisEntity ?: return null
        val i = d.passengerList.indexOf(e)
        if (i < 0) return null
        return anchors[d.id]?.getOrNull(i)
    }

    /** Position der Reiter-Entity im Tick (null = noch kein Körper gezeichnet: Server-Näherung). */
    private fun riderPos(d: NivarisEntity, passenger: Entity): Vec3d? {
        val i = d.passengerList.indexOf(passenger)
        if (i < 0) return null
        val a = anchors[d.id]?.getOrNull(i) ?: return null
        return d.entityPos.add(a.entity)
    }

    // --- Dritte Person -----------------------------------------------------------------------------------------------

    private val m3 = Matrix3f()
    private val rot = Quaternionf()

    /**
     * Ersetzt die Vanilla-Drehung der Figur (PlayerEntityRenderer.setupTransforms): waagerecht, Kopf zum Dorn, Brust
     * zu ihrem Rücken. True = erledigt.
     */
    @JvmStatic
    fun transform(state: PlayerEntityRenderState, matrices: MatrixStack): Boolean {
        val world = MinecraftClient.getInstance().world ?: return false
        val e = world.getEntityById(state.id) ?: return false
        if (grip(e) <= 0) return false
        val a = anchorOf(e) ?: return false
        // Rumpf leicht im Wind wiegen (um die Kopfachse), mit einer Hand stärker.
        val t = state.age
        val roll = (if (grip(e) == 1) 0.16 else 0.05) * sin(t * 0.37)
        val back = a.back.multiply(kotlin.math.cos(roll)).add(a.right.multiply(kotlin.math.sin(roll)))
        val right = a.head.crossProduct(back)
        val off = a.feet.subtract(a.entity)
        matrices.translate(off.x.toFloat(), off.y.toFloat(), off.z.toFloat())
        // Spalten: lokal x → rechts, y → Kopf, z → Rücken (lokal −z ist die Brust/Front der Figur).
        m3.set(
            right.x.toFloat(), right.y.toFloat(), right.z.toFloat(),
            a.head.x.toFloat(), a.head.y.toFloat(), a.head.z.toFloat(),
            back.x.toFloat(), back.y.toFloat(), back.z.toFloat(),
        )
        matrices.multiply(rot.setFromNormalized(m3))
        return true
    }

    /** Ende von PlayerEntityModel.setAngles: Arme um den Dorn, Beine flattern im Fahrtwind. */
    @JvmStatic
    fun applyPose(model: PlayerEntityModel, state: PlayerEntityRenderState) {
        if (!state.hasVehicle) return
        val e = MinecraftClient.getInstance().world?.getEntityById(state.id) ?: return
        val grip = grip(e)
        if (grip <= 0) return
        val t = state.age
        val wild = if (grip == 1) 1.6f else 1f
        // Kopf hebt sich und blickt nach vorn in den Wind.
        model.head.pitch = -1.05f + 0.04f * sin(t * 0.23f)
        model.head.yaw = 0.12f * sin(t * 0.11f)
        model.head.roll = 0f
        model.body.pitch = 0f
        model.body.yaw = 0f
        model.body.roll = 0f
        val pi = PI.toFloat()
        val jitter = 0.035f * sin(t * 1.7f)
        // Rechte Hand am Dorn (rechte Seite), Arm über Kopf nach vorn-oben, leicht nach innen.
        model.rightArm.pitch = -pi - 0.5f + jitter
        model.rightArm.yaw = 0f
        model.rightArm.roll = 0.3f
        if (grip >= 2) {
            model.leftArm.pitch = -pi - 0.5f - jitter
            model.leftArm.yaw = 0f
            model.leftArm.roll = -0.3f
        } else {
            // Nur noch eine Hand: der linke Arm reißt im Wind nach hinten und rudert.
            model.leftArm.pitch = -pi - 1.5f + 0.55f * sin(t * 0.6f)
            model.leftArm.yaw = 0.2f * sin(t * 0.5f)
            model.leftArm.roll = -(0.7f + 0.35f * sin(t * 0.45f))
            model.rightArm.roll = 0.18f
        }
        // Beine: lose hinter dem Körper, sie flattern im Wind (zwei überlagerte Wellen, gegeneinander versetzt).
        model.rightLeg.pitch = wild * (0.3f * sin(t * 1.15f) + 0.1f * sin(t * 2.4f + 1f)) - 0.12f
        model.leftLeg.pitch = wild * (0.3f * sin(t * 1.15f + 2.3f) + 0.1f * sin(t * 2.9f)) - 0.12f
        model.rightLeg.yaw = 0.06f * sin(t * 0.7f)
        model.leftLeg.yaw = -0.06f * sin(t * 0.8f)
        model.rightLeg.roll = 0.09f + 0.05f * wild * sin(t * 0.9f)
        model.leftLeg.roll = -0.09f - 0.05f * wild * sin(t * 1.05f)
    }

    // --- Erste Person ------------------------------------------------------------------------------------------------

    /**
     * Griffpunkte am Dorn des eigenen Spielers im Blickraum der ersten Person (+x rechts, +y oben, −z vorn): rechte und
     * linke Hand, je an ihrer Seite des Kristalls. Null, wenn er nicht reitet.
     */
    fun handTargets(tickDelta: Float): Pair<Vector3f, Vector3f>? {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return null
        val d = player.vehicle as? NivarisEntity ?: return null
        val a = anchorOf(player) ?: return null
        val base = d.getLerpedPos(tickDelta)
        val grip = base.add(a.grip)
        val cam = client.gameRenderer.camera
        val inv = Quaternionf(cam.rotation).conjugate()
        fun view(p: Vec3d): Vector3f = inv.transform(Vector3f((p.x - cam.cameraPos.x).toFloat(), (p.y - cam.cameraPos.y).toFloat(), (p.z - cam.cameraPos.z).toFloat()))
        return view(grip.add(a.right.multiply(0.17))) to view(grip.subtract(a.right.multiply(0.17)))
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

    /** Hände über den Füßen entlang der Kopfachse (Arm gestreckt, 0,5 rad zum Rücken gehoben; Spielermodell × 0,9375). */
    private const val REACH = 1.80

    /** Die Figur liegt so weit unter dem Griff (Arme heben zum Dorn). */
    private const val BELOW = 0.30

    /** Kopfmitte über den Füßen entlang der Körperachse. */
    private const val HEAD = 1.64
}

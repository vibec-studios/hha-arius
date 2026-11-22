package dev.henny.hha_arius.client.fx

import dev.henny.hha_arius.boss.AriusEntity
import dev.henny.hha_arius.boss.NivarisEntity
import dev.henny.hha_arius.boss.NivarisShape
import dev.henny.hha_arius.boss.Qte
import dev.henny.hha_arius.boss.RideAction
import dev.henny.hha_arius.boss.RideActionS2C
import dev.henny.hha_arius.boss.RideGripS2C
import dev.henny.hha_arius.boss.RideHooks
import dev.henny.hha_arius.boss.RideSeatS2C
import dev.henny.hha_arius.boss.RideSeats
import dev.henny.hha_arius.client.model.nivaris.NivarisRenderState
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.entity.model.BipedEntityModel
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ritt auf Solvanes Rücken im Client:
 * * **Am echten Dorn:** Reiter `i` greift die Kristallplatte (`crest`) von Glied [RideHooks.glied] des gezeichneten
 *   Körpers. [anchor] rechnet den Griffpunkt je Frame aus dem Körper ([dev.henny.hha_arius.client.model.nivaris.NivarisBody]),
 *   [riderPos] setzt die Entity des Reiters im Tick dorthin (inkl. weichem Aufsprung-Bogen statt schlagartigem Teleport).
 * * **Sinnvolle QTE-Ausweichhaltungen:** Ducken presst Körper und Kamera tief flach an Solvanes Rückgrat unter die
 *   festen Bögen; Hochziehen hebt Körper und angezogene Beine hoch über die festen Eisgrate; Hämmern/Stützen ziehen
 *   spürbar am Eisdorn.
 * * **Mehrspieler:** Wer auf ihr reitet, entscheidet allein die Mitfahrerliste des Servers (siehe
 *   [NivarisEntity]); steigt jemand ab, verschwinden Haltung, Armlänge und Griff bei allen Clients.
 */
object RideClient {

    private val grips = HashMap<Int, Int>()

    /** Feste Plätze je Entity-ID ([RideSeatS2C]). */
    private val seats = HashMap<Int, Int>()

    /** Letzte freie Weltposition von Spielern vor dem Aufsitzen (für weichen Aufsprung statt Pop-In). */
    private val lastFreePos = HashMap<Int, Vec3d>()

    private class MountAnim(val from: Vec3d, val startTick: Int, val duration: Float, val arc: Double = 3.4)
    private val mountAnims = HashMap<Int, MountAnim>()

    private class ActionAnim(val action: Int, val startTick: Int, val duration: Float)
    private val actionAnims = HashMap<Int, ActionAnim>()

    private var clientTick = 0

    /** Je Drachin: Sitz von Arius relativ zu ihr und seine Blickrichtung (Grad). */
    private class Perch(val at: Vec3d, val yaw: Float)

    private val perches = HashMap<Int, Perch>()

    /** Fahrtwind je Drachin: Tempo (Blöcke je Tick) und je Platz, wie schnell der Körper dort gerade seitlich eindreht. */
    private class Wind {
        var age = Float.NaN
        var last: Vec3d? = null
        var speed = 0f
        val turn = FloatArray(SEATS)
        val heads = arrayOfNulls<Vec3d>(SEATS)
    }

    private val winds = HashMap<Int, Wind>()

    /** Lage eines Reiters relativ zur Render-Position der Drachin: Griff, Kopfrichtung (vorn), Rückenrichtung (oben). */
    private class Anchor(val grip: Vec3d, val head: Vec3d, val back: Vec3d) {
        val right: Vec3d = head.crossProduct(back).let { if (it.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it.normalize() }

        /** Augen (Kopfmitte): hier sitzt die Kamera der ersten Person. */
        val eye: Vec3d = grip.subtract(head.multiply(REACH - HEAD)).subtract(back.multiply(BELOW - 0.06))

        /** Entity-Position des Reiters (stehende Augenhöhe abgezogen). */
        val entity: Vec3d = eye.subtract(0.0, RideHooks.EYE, 0.0)
    }

    /** Je Drachin (Entity-ID) die Reiterplätze aus dem letzten Frame. */
    private val anchors = HashMap<Int, Array<Anchor?>>()

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(RideGripS2C.ID) { p, ctx ->
            if (p.grip <= 0) {
                grips.remove(p.entityId)
                actionAnims.remove(p.entityId)
                mountAnims.remove(p.entityId)
            } else {
                grips[p.entityId] = p.grip
                p.pickup?.let {
                    // grip == 1 mit pickup: verzweifeltes Fangen im Fall (kurzes Einhaken ohne Schwebebogen).
                    val quick = p.grip == 1
                    mountAnims[p.entityId] = MountAnim(it, clientTick, if (quick) CATCH_TICKS else MOUNT_TICKS, if (quick) 0.25 else 3.4)
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(RideSeatS2C.ID) { p, ctx ->
            if (p.seat == RideSeats.NONE) {
                seats.remove(p.entityId)
                grips.remove(p.entityId)
                actionAnims.remove(p.entityId)
                mountAnims.remove(p.entityId)
            } else {
                seats[p.entityId] = p.seat
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(RideActionS2C.ID) { p, _ ->
            if (p.action == RideAction.NONE || p.ticks <= 0) actionAnims.remove(p.entityId)
            else actionAnims[p.entityId] = ActionAnim(p.action, clientTick, p.ticks.toFloat())
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!client.isPaused) clientTick++
            val world = client.world ?: return@register
            reconcile(world)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            grips.clear()
            anchors.clear()
            seats.clear()
            perches.clear()
            winds.clear()
            lastFreePos.clear()
            mountAnims.clear()
            actionAnims.clear()
        }
        RideHooks.riderPos = { d, passenger -> riderPos(d, passenger) }
        RideHooks.clientSeat = { e -> seats[e.id] }
    }

    /**
     * Lokale Sofort-Rückmeldung aus [QteClient], sobald der Spieler die Taste drückt (ohne Netzwerklatenz).
     */
    fun onLocalQtePress(kind: Qte.Kind) {
        val player = MinecraftClient.getInstance().player ?: return
        if (grip(player) <= 0) return
        val act = when (kind) {
            Qte.Kind.SNEAK -> RideAction.DUCK
            Qte.Kind.JUMP -> RideAction.VAULT
            Qte.Kind.USE -> RideAction.BRACE
            Qte.Kind.MASH, Qte.Kind.ATTACK -> RideAction.TUG
            Qte.Kind.LEFT -> RideAction.SWING_L
            Qte.Kind.RIGHT -> RideAction.SWING_R
        }
        // Bis die Meldung des Servers kommt (sie hält die Haltung, bis das Hindernis vorbei ist).
        val dur = if (act == RideAction.TUG) 10f else 40f
        actionAnims[player.id] = ActionAnim(act, clientTick, dur)
    }

    /**
     * Je Tick: räumt den Zustand abgestiegener Spieler auf und startet beim frischen Aufsitzen den weichen Aufsprung-Bogen.
     */
    private fun reconcile(world: net.minecraft.client.world.ClientWorld) {
        for (p in world.players) {
            val v = p.vehicle
            if (v is NivarisEntity) {
                if (v.isRemoved) {
                    p.stopRiding()
                    seats.remove(p.id)
                    clearRiderAnimation(p.id)
                } else if (p.id !in mountAnims) {
                    val prev = lastFreePos[p.id]
                    val startFrom = prev ?: p.entityPos
                    val target = anchorOf(p)?.let { v.entityPos.add(it.entity) }
                    val distSq = if (target != null) startFrom.squaredDistanceTo(target) else 0.0
                    // Wird der Spieler von außerhalb auf Solvane gehoben: sichtbarer Bogen an seinen Dorn statt Pop-In.
                    val dur = if (distSq in 3.0..2400.0) MOUNT_TICKS else 0f
                    mountAnims[p.id] = MountAnim(startFrom, clientTick, dur)
                }
            } else {
                lastFreePos[p.id] = p.entityPos
                // The pickup packet can precede the vanilla passenger list by a tick.
                val pickup = mountAnims[p.id]
                if (pickup == null || clientTick - pickup.startTick > 3) clearRiderAnimation(p.id)
            }
        }
        for (e in world.entities) {
            if (e is AriusEntity) {
                val v = e.vehicle
                if (v is NivarisEntity && v.isRemoved) {
                    e.stopRiding()
                    seats.remove(e.id)
                }
            }
        }
        actionAnims.entries.removeIf { clientTick - it.value.startTick > it.value.duration + 4 }
    }

    private fun clearRiderAnimation(id: Int) {
        grips.remove(id)
        mountAnims.remove(id)
        actionAnims.remove(id)
    }

    /** Grip strength: 0 off the dragon, 1 loose, 2 secure. Both hands remain on the spike. */
    @JvmStatic
    fun grip(e: Entity?): Int {
        if (e == null || e !is PlayerEntity) return 0
        val v = e.vehicle as? NivarisEntity ?: return 0
        if (v.isRemoved) return 0
        if (seats[e.id] == RideSeats.NONE) return 0
        val g = grips[e.id]
        if (g != null && g <= 0) return 0
        return g ?: 2
    }

    /** Fortschritt des Aufsprung-Bogens 0..1 (1 = voll am Dorn). */
    private fun mountProgress(id: Int, tickDelta: Float = MinecraftClient.getInstance().renderTickCounter.getTickProgress(false)): Float {
        val m = mountAnims[id] ?: return 1f
        if (m.duration <= 0.1f) return 1f
        val u = ((clientTick - m.startTick + tickDelta) / m.duration).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** Aktive QTE-Ausweichaktion und ihre weiche Hüllkurve 0..1. */
    private fun actionWeight(id: Int): Pair<Int, Float> {
        val a = actionAnims[id] ?: return RideAction.NONE to 0f
        val age = (clientTick - a.startTick).toFloat()
        if (age < 0f || age > a.duration) return RideAction.NONE to 0f
        val u = age / a.duration
        val env = when {
            u < 0.18f -> (u / 0.18f).let { it * it * (3f - 2f * it) }
            u > 0.72f -> ((1f - u) / 0.28f).let { it * it * (3f - 2f * it) }
            else -> 1f
        }
        return a.action to env.coerceIn(0f, 1f)
    }

    // --- Griffpunkte am gezeichneten Körper ------------------------------------------------------------------------

    /**
     * Aus dem fertigen Körper eines Frames ([pts] Welt, Rahmen in [s]): Griffpunkt an der Kristallplatte jedes
     * Reiterglieds und Arius' fester Sitzplatz zwischen den Hörnern auf dem Schädeldach.
     */
    fun anchor(d: NivarisEntity, s: NivarisRenderState, pts: Array<Vec3d>, headUp: Vec3d, headFwd: Vec3d) {
        val out = arrayOfNulls<Anchor>(SEATS)
        val base = Vec3d(s.x, s.y, s.z)
        val wind = winds.getOrPut(d.id) { Wind() }
        val dt = if (wind.age.isNaN()) 0f else (s.age - wind.age).coerceIn(0f, 5f)
        if (wind.age.isNaN() || dt > 1e-3f) {
            wind.last?.let { if (dt > 1e-3f) wind.speed += ((base.distanceTo(it) / dt).toFloat() - wind.speed) * (1f - kotlin.math.exp(-dt * 0.12f)) }
            wind.last = base
            wind.age = s.age
        }
        val q = 0.72
        val c = cos(-34.0 * PI / 180.0)
        val sn = sin(-34.0 * PI / 180.0)
        val uy = -6.5 + (-15.0 * q) * c
        val uz = 9.0 + (-15.0 * q) * sn
        for (i in 0 until SEATS) {
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
            val made = Anchor(grip.subtract(base), head, up.normalize())
            out[i] = made
            if (dt > 1e-3f) {
                wind.heads[i]?.let { before ->
                    val rate = (head.subtract(before).dotProduct(made.right) / dt).toFloat()
                    wind.turn[i] += (rate - wind.turn[i]) * (1f - kotlin.math.exp(-dt * 0.14f))
                }
                wind.heads[i] = head
            }
        }
        anchors[d.id] = out
        // Arius: fest auf dem Schädeldach direkt zwischen den Hörnern, Blick nach vorn entlang headFwd (nie nach hinten rutschend).
        val levelUp = headUp.lerp(Vec3d(0.0, 1.0, 0.0), 0.45).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 1.0, 0.0) else it.normalize() }
        val top = pts[0].add(levelUp.multiply(PERCH_UP)).add(headFwd.multiply(PERCH_FWD)).subtract(0.0, PERCH_SEAT, 0.0)
        val flatYaw = Math.toDegrees(atan2(headFwd.z, headFwd.x)).toFloat() - 90f
        perches[d.id] = Perch(top.subtract(base), flatYaw)
    }

    private fun seatOf(d: NivarisEntity, e: Entity): Int = RideHooks.seatOf(d, e)

    private fun anchorOf(e: Entity): Anchor? {
        val d = e.vehicle as? NivarisEntity ?: return null
        val i = seatOf(d, e)
        if (i < 0) return null
        return anchors[d.id]?.getOrNull(i)
    }

    /** Position der Reiter-Entity im Tick (null = noch kein Körper gezeichnet: Server-Näherung). */
    private fun riderPos(d: NivarisEntity, passenger: Entity): Vec3d? {
        val i = seatOf(d, passenger)
        if (i == RideSeats.HEAD) {
            val p = perches[d.id] ?: return null
            val target = d.entityPos.add(p.at)
            passenger.yaw = p.yaw
            passenger.lastYaw = p.yaw
            if (passenger is LivingEntity) {
                passenger.bodyYaw = p.yaw
                passenger.lastBodyYaw = p.yaw
                passenger.headYaw = p.yaw
                passenger.lastHeadYaw = p.yaw
            }
            return target
        }
        if (i < 0) return null
        val a = anchors[d.id]?.getOrNull(i) ?: return null
        val target = d.entityPos.add(a.entity)
        val m = mountAnims[passenger.id]
        if (m != null && m.duration > 0.1f) {
            val k = mountProgress(passenger.id).toDouble()
            if (k < 0.999) {
                val arc = sin(k * PI) * m.arc
                return m.from.lerp(target, k).add(0.0, arc, 0.0)
            }
        }
        return target
    }

    // --- Dritte Person -----------------------------------------------------------------------------------------------

    private val m3 = Matrix3f()
    private val rot = Quaternionf()
    private val upright = Quaternionf()

    /**
     * Ersetzt die Vanilla-Drehung der Figur (PlayerEntityRenderer.setupTransforms): waagerecht, Kopf zum Dorn, Brust
     * zu ihrem Rücken – reagiert sichtbar auf Ducken (flach an den Rücken), Hochziehen (über den Dorn) und weiches
     * Aufspringen. True = erledigt.
     */
    @JvmStatic
    fun transform(state: PlayerEntityRenderState, matrices: MatrixStack): Boolean {
        if (!state.hasVehicle) return false
        val world = MinecraftClient.getInstance().world ?: return false
        val e = world.getEntityById(state.id) ?: return false
        val g = grip(e)
        if (g <= 0) return false
        val a = anchorOf(e) ?: return false
        // Keep a single rigid hand frame on the spike. QTEs move the legs and head around it.
        val figHead = a.head.multiply(cos(BODY_TILT)).subtract(a.back.multiply(sin(BODY_TILT))).normalize()
        val back = a.back.multiply(cos(BODY_TILT)).add(a.head.multiply(sin(BODY_TILT))).normalize()
        val feet = a.grip.subtract(figHead.multiply(REACH)).subtract(back.multiply(BELOW))
        val right = figHead.crossProduct(back).normalize()
        val off = feet.subtract(a.entity)
        val mp = mountProgress(e.id)
        matrices.translate((off.x * mp).toFloat(), (off.y * mp).toFloat(), (off.z * mp).toFloat())
        // Spalten: lokal x → rechts, y → Kopf, z → Rücken (lokal −z ist die Brust/Front der Figur).
        m3.set(
            right.x.toFloat(), right.y.toFloat(), right.z.toFloat(),
            figHead.x.toFloat(), figHead.y.toFloat(), figHead.z.toFloat(),
            back.x.toFloat(), back.y.toFloat(), back.z.toFloat(),
        )
        rot.setFromNormalized(m3)
        if (!rot.isFinite) return false
        if (mp < 0.999f) {
            // Während des Aufsprung-Bogens weich von aufrecht in die liegende Reithaltung überblenden.
            upright.rotationY((PI - Math.toRadians(state.bodyYaw.toDouble())).toFloat())
            upright.slerp(rot, mp)
            matrices.multiply(upright)
        } else {
            matrices.multiply(rot)
        }
        return true
    }

    /** Ende von PlayerEntityModel.setAngles: Arme um den Dorn, die Beine wehen geschlossen wie eine Fahne im Fahrtwind. */
    @JvmStatic
    fun applyPose(model: PlayerEntityModel, state: PlayerEntityRenderState) {
        // Immer zuerst zurücksetzen, damit kein Spieler nach dem Absteigen gestreckte Reit-Arme behält!
        model.rightArm.yScale = 1f
        model.leftArm.yScale = 1f
        if (!state.hasVehicle) return
        val e = MinecraftClient.getInstance().world?.getEntityById(state.id) ?: return
        val grip = grip(e)
        if (grip <= 0) return
        val t = state.age
        val (act, aw) = actionWeight(e.id)
        // Kopf hebt sich und blickt nach vorn in den Wind; beim Ducken flach nach unten an den Rücken gedrückt.
        val duckPitch = if (act == RideAction.DUCK) 0.95f * aw else 0f
        model.head.pitch = -1.05f + duckPitch + 0.04f * sin(t * 0.23f)
        model.head.yaw = 0.12f * sin(t * 0.11f) * (1f - aw * 0.8f)
        model.head.roll = 0f
        model.body.yaw = 0f
        model.body.roll = 0f
        val pi = PI.toFloat()
        model.rightArm.yScale = ARM_LENGTH
        model.leftArm.yScale = ARM_LENGTH
        model.rightArm.pitch = -pi - 0.24f
        model.leftArm.pitch = model.rightArm.pitch
        model.rightArm.yaw = 0f
        model.leftArm.yaw = 0f
        model.rightArm.roll = 0.2f
        model.leftArm.roll = -0.2f
        val vehicle = e.vehicle as? NivarisEntity
        val wind = vehicle?.let { winds[it.id] }
        val seat = vehicle?.let { seatOf(it, e) }?.takeIf { it in 0 until SEATS } ?: 0
        val pace = ((wind?.speed ?: 0f) / CRUISE_SPEED).coerceIn(0f, 1.25f)
        val bend = ((wind?.turn?.get(seat) ?: 0f) / TURN_FULL).coerceIn(-1f, 1f)
        val calm = 1f - aw * 0.72f
        val amp = (0.06f + 0.10f * pace) * (1f + 0.45f * kotlin.math.abs(bend)) * (if (grip == 1) 1.5f else 1f) * calm
        val phase = t * 0.36f
        val trail = when (act) {
            RideAction.DUCK -> MathHelper.lerp(aw, LEG_TRAIL + 0.16f * pace, 0.22f)
            RideAction.VAULT -> MathHelper.lerp(aw, LEG_TRAIL + 0.16f * pace, 1.45f)
            RideAction.HIT -> LEG_TRAIL + 0.16f * pace + 0.21f * aw
            else -> LEG_TRAIL + 0.16f * pace
        }
        // Im Wind treiben die Beine zur Kurvenaußenseite; beim Schwingen weg von der Felsnadel.
        val aside = -bend * 0.30f * calm + when (act) {
            RideAction.SWING_L -> -0.95f * aw
            RideAction.SWING_R -> 0.95f * aw
            RideAction.HIT -> 0.17f * aw
            else -> 0f
        }
        model.body.pitch = 0f
        model.rightLeg.pitch = trail + amp * sin(phase) + 0.35f * amp * sin(2f * phase - 0.7f)
        model.leftLeg.pitch = trail + amp * sin(phase - 0.85f) + 0.35f * amp * sin(2f * phase - 2.2f)
        model.rightLeg.roll = LEG_CLOSE + aside + 0.25f * amp * sin(phase * 0.7f)
        model.leftLeg.roll = -LEG_CLOSE + aside + 0.25f * amp * sin(phase * 0.7f - 0.6f)
        model.rightLeg.yaw = 0f
        model.leftLeg.yaw = 0f
    }

    /**
     * Ende von PlayerEntityRenderer.updateRenderState: Reiter halten nichts in der Hand (auch nicht in Szenen).
     */
    @JvmStatic
    fun updateState(e: Entity, state: PlayerEntityRenderState) {
        if (grip(e) > 0) {
            state.leftHandItemState.clear()
            state.rightHandItemState.clear()
            state.leftHandItem = ItemStack.EMPTY
            state.rightHandItem = ItemStack.EMPTY
            state.leftArmPose = BipedEntityModel.ArmPose.EMPTY
            state.rightArmPose = BipedEntityModel.ArmPose.EMPTY
        }
    }

    /** Szene: Die Kamera ist in oder direkt an der eigenen Figur (Rückweg zur Spielerkamera), die Figur bleibt weg. */
    @JvmStatic
    fun hideForCamera(e: Entity): Boolean {
        val client = MinecraftClient.getInstance()
        if (e !== client.player || !dev.henny.hha.client.cine.CinePlayer.active) return false
        val cam = client.gameRenderer.camera.cameraPos
        return cam.squaredDistanceTo(e.x, e.y + 1.2, e.z) < 2.4 * 2.4
    }

    // --- Erste Person ------------------------------------------------------------------------------------------------

    /**
     * Erste Person: Die Augen liegen über der Spitze des Dorns; beim Ducken taucht die Kamera tief hinter den Dorn ab,
     * beim Hochziehen steigt sie spürbar an. Null = nicht reitend.
     */
    fun overTheSpike(cam: Vec3d): Vec3d? {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return null
        if (!client.options.perspective.isFirstPerson || grip(player) <= 0) return null
        val a = anchorOf(player) ?: return null
        val (act, aw) = actionWeight(player.id)
        val delta = when (act) {
            // Nur so tief, dass die Kamera über dem eigenen Dorn bleibt (tiefer lag sie im Kristall).
            RideAction.DUCK -> -0.42 * aw
            RideAction.VAULT -> 0.72 * aw
            RideAction.BRACE -> -0.25 * aw
            else -> 0.0
        }
        val aside = when (act) {
            RideAction.SWING_L -> -0.85 * aw
            RideAction.SWING_R -> 0.85 * aw
            else -> 0.0
        }
        return cam.add(a.back.multiply(EYE_LIFT + delta)).add(a.right.multiply(aside))
    }

    /**
     * Griffpunkte am Dorn des eigenen Spielers im Blickraum der ersten Person (+x rechts, +y oben, −z vorn): rechte und
     * linke Hand, je an ihrer Seite des Kristalls. Null, wenn er nicht reitet.
     */
    fun handTargets(tickDelta: Float): Pair<Vector3f, Vector3f>? {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return null
        val d = player.vehicle as? NivarisEntity ?: return null
        if (grip(player) <= 0) return null
        val a = anchorOf(player) ?: return null
        val base = d.getLerpedPos(tickDelta)
        val grip = base.add(a.grip).add(a.back.multiply(HAND_LIFT))
        val cam = client.gameRenderer.camera
        val inv = Quaternionf(cam.rotation).conjugate()
        fun view(p: Vec3d): Vector3f = inv.transform(Vector3f((p.x - cam.cameraPos.x).toFloat(), (p.y - cam.cameraPos.y).toFloat(), (p.z - cam.cameraPos.z).toFloat()))
        return view(grip.add(a.right.multiply(0.17))) to view(grip.subtract(a.right.multiply(0.17)))
    }

    // --- Anzeige ------------------------------------------------------------------------------------------------------

    /** Current grip strength, above the QTE prompt. */
    fun renderHud(ctx: DrawContext) {
        if (dev.henny.hha.client.cine.CinePlayer.active) return
        val player = MinecraftClient.getInstance().player ?: return
        val g = grip(player)
        if (g <= 0) return
        val tr = MinecraftClient.getInstance().textRenderer
        val text = Text.translatable(if (g >= 2) "hha_arius.ride.grip_two" else "hha_arius.ride.grip_one")
        val col = if (g >= 2) 0xFFBFE6FF.toInt() else 0xFFE6F6FF.toInt()
        ctx.drawText(tr, text, ctx.scaledWindowWidth / 2 - tr.getWidth(text) / 2, 22, col, true)
    }

    /** So lange dauert der sichtbare Bogen, mit dem der Lichtstrom einen Spieler an seinen Dorn zieht. */
    private const val MOUNT_TICKS = 26f

    /** Kurzes Einhaken beim verzweifelten Fangen im Fall (Übergang I → II). */
    private const val CATCH_TICKS = 6f

    /** Plätze (Eisdorne), für die Griffpunkte gerechnet werden. */
    private const val SEATS = 8

    /** Hände über den Füßen entlang der Kopfachse: Der Kopf liegt damit deutlich hinter dem Dorn. */
    private const val REACH = 2.55

    /** Die Figur liegt so weit unter dem Griff (Arme heben zum Dorn). */
    private const val BELOW = 0.22

    /** So weit hebt die erste Person den Blick über den Dorn (Rückenrichtung, Blöcke). */
    private const val EYE_LIFT = 1.15

    /** Griff der ersten Person über dem Griffpunkt der Figur (Rückenrichtung, Blöcke). */
    private const val HAND_LIFT = 0.62

    /** Armlänge in der Haltung (Vielfaches), passend zu [REACH]. */
    private const val ARM_LENGTH = 1.62f

    /** Grundhaltung im Wind: Figur schräg (Bogenmaß), Beine dazu angehoben und fast geschlossen. */
    private const val BODY_TILT = 0.22
    private const val LEG_TRAIL = 0.38f
    private const val LEG_CLOSE = 0.035f

    /** Fahrtwind: Reisetempo (Blöcke je Tick) und Drehrate (rad je Tick), bei der die Wirkung voll ist. */
    private const val CRUISE_SPEED = 0.70f
    private const val TURN_FULL = 0.022f

    /** Arius auf dem Kopf: fest auf dem Schädeldach leicht vor dem Kopfgelenk (zwischen den Hörnern). */
    private const val PERCH_UP = 1.66
    private const val PERCH_FWD = 0.62
    private const val PERCH_SEAT = 0.55

    /** Kopfmitte über den Füßen entlang der Körperachse. */
    private const val HEAD = 1.64
}

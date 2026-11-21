package dev.henny.hha_arius.client.fx

import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.RotationAxis
import kotlin.math.PI
import kotlin.math.sin

/**
 * Benutzerdefinierte Spieler-Animation für den Tempel-Tritt ([dev.henny.hha_arius.boss.Scenes.kick]):
 * 1. **Treffer-Stopp (`0..hitstop`)**: Körper reißt nach hinten, Arme fliegen auf, Zittern im Schock.
 * 2. **Flug durch das Tor (`hitstop..hitstop + flight`)**: gestreckter Rückwärts-Überschlag / Schleuderbogen im Wind,
 *    Beine und Arme strampeln gegen den Fahrtwind, am Ende aufrichten zum Abfangen.
 * 3. **Aufprall & Aufstehen (`hitstop + flight..total`)**: tiefes Abfangen in den Knien, Stützhand zum Schnee, weiches
 *    Aufrichten zurück in die normale Standhaltung.
 */
object KickAnim {

    private class Track(val startAge: Float, val hitstop: Float, val flight: Float, val total: Float)

    private val tracks = HashMap<Int, Track>()

    fun start(entityId: Int, hitstop: Float, flight: Float, total: Int) {
        if (entityId < 0 || total <= 0) return
        val world = MinecraftClient.getInstance().world ?: return
        val e = world.getEntityById(entityId)
        val age = e?.age?.toFloat() ?: 0f
        tracks[entityId] = Track(
            startAge = age,
            hitstop = hitstop.coerceAtLeast(2f),
            flight = flight.coerceAtLeast(6f),
            total = total.toFloat().coerceAtLeast(hitstop + flight + 4f),
        )
    }

    fun reset() {
        tracks.clear()
    }

    private fun sample(state: PlayerEntityRenderState): Pair<Track, Float>? {
        val tr = tracks[state.id] ?: return null
        val t = state.age - tr.startAge
        if (t < -2f || t >= tr.total) {
            if (t >= tr.total || t < -20f) tracks.remove(state.id)
            return null
        }
        return tr to t.coerceAtLeast(0f)
    }

    private fun smooth(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    /** Ganzkörper-Neigung und Absenken am Ende von PlayerEntityRenderer.setupTransforms. */
    @JvmStatic
    fun applyTransform(state: PlayerEntityRenderState, matrices: MatrixStack) {
        if (state.hasVehicle) return
        val (tr, t) = sample(state) ?: return
        val h = tr.hitstop
        val fl = tr.flight
        val rec = (tr.total - h - fl).coerceAtLeast(4f)
        val pitchDeg: Float
        val rollDeg: Float
        val dropY: Float
        when {
            t < h -> {
                val u = smooth(t / h)
                val tremor = sin(t * 3.1f) * 3.0f * (1f - u * 0.35f)
                pitchDeg = 38f * u + tremor
                rollDeg = -12f * u
                dropY = 0.08f * u
            }
            t < h + fl -> {
                val u = ((t - h) / fl).coerceIn(0f, 1f)
                val arc = sin(u * PI.toFloat())
                // Erst heftig nach hinten geschleudert (~104°, Füße voraus durch das Tor), im Sinkflug aufrichten zum Abfangen.
                pitchDeg = 38f * (1f - u) + 74f * arc - 24f * smooth((u - 0.62f) / 0.38f)
                rollDeg = -12f * (1f - u) + 22f * sin(u * PI.toFloat() * 1.4f)
                dropY = 0.06f * (1f - u)
            }
            else -> {
                val u = ((t - h - fl) / rec).coerceIn(0f, 1f)
                val brace = sin(minOf(1f, u * 2.4f) * PI.toFloat() * 0.5f) * (1f - smooth((u - 0.22f) / 0.78f))
                pitchDeg = -32f * brace
                rollDeg = 8f * brace
                dropY = -0.38f * brace
            }
        }
        matrices.translate(0f, dropY, 0f)
        matrices.translate(0f, 0.9f, 0f)
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitchDeg))
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rollDeg))
        matrices.translate(0f, -0.9f, 0f)
    }

    /** Gliedmaßen-Haltung am Ende von PlayerEntityModel.setAngles. */
    @JvmStatic
    fun applyPose(model: PlayerEntityModel, state: PlayerEntityRenderState) {
        if (state.hasVehicle) return
        val (tr, t) = sample(state) ?: return
        val h = tr.hitstop
        val fl = tr.flight
        val rec = (tr.total - h - fl).coerceAtLeast(4f)
        val w = when {
            t < 2f -> smooth(t / 2f)
            t > tr.total - 6f -> 1f - smooth((t - (tr.total - 6f)) / 6f)
            else -> 1f
        }
        if (w <= 0.001f) return
        fun blend(cur: Float, target: Float) = cur + (target - cur) * w
        when {
            t < h -> {
                val u = smooth(t / h)
                val vib = 0.06f * sin(t * 3.2f)
                model.head.pitch = blend(model.head.pitch, -0.45f * u + vib)
                model.head.yaw = blend(model.head.yaw, 0.14f * u)
                model.rightArm.pitch = blend(model.rightArm.pitch, -1.35f * u + vib)
                model.rightArm.roll = blend(model.rightArm.roll, 0.78f * u)
                model.leftArm.pitch = blend(model.leftArm.pitch, -1.55f * u - vib)
                model.leftArm.roll = blend(model.leftArm.roll, -0.88f * u)
                model.rightLeg.pitch = blend(model.rightLeg.pitch, -0.52f * u)
                model.rightLeg.roll = blend(model.rightLeg.roll, 0.18f * u)
                model.leftLeg.pitch = blend(model.leftLeg.pitch, 0.36f * u)
                model.leftLeg.roll = blend(model.leftLeg.roll, -0.22f * u)
            }
            t < h + fl -> {
                val u = ((t - h) / fl).coerceIn(0f, 1f)
                val flail = sin(t * 0.75f)
                val flail2 = sin(t * 0.92f + 1.4f)
                model.head.pitch = blend(model.head.pitch, -0.55f * (1f - u) + 0.25f * u)
                model.head.yaw = blend(model.head.yaw, 0.2f * flail)
                // Arme weit im Wind gerissen und rudern leicht
                model.rightArm.pitch = blend(model.rightArm.pitch, -2.35f + 0.35f * flail)
                model.rightArm.roll = blend(model.rightArm.roll, 0.95f + 0.2f * flail2)
                model.leftArm.pitch = blend(model.leftArm.pitch, -2.15f - 0.4f * flail2)
                model.leftArm.roll = blend(model.leftArm.roll, -1.05f - 0.2f * flail)
                // Beine gespreizt und angewinkelt gegen die Flugrichtung
                model.rightLeg.pitch = blend(model.rightLeg.pitch, -0.75f + 0.4f * flail2)
                model.rightLeg.roll = blend(model.rightLeg.roll, 0.28f)
                model.leftLeg.pitch = blend(model.leftLeg.pitch, 0.45f - 0.35f * flail)
                model.leftLeg.roll = blend(model.leftLeg.roll, -0.28f)
            }
            else -> {
                val u = ((t - h - fl) / rec).coerceIn(0f, 1f)
                val crouch = 1f - smooth((u - 0.15f) / 0.85f)
                // Auf ein Knie und Stützhand abfangen, dann aufrichten
                model.head.pitch = blend(model.head.pitch, 0.35f * crouch)
                model.rightArm.pitch = blend(model.rightArm.pitch, -1.05f * crouch)
                model.rightArm.roll = blend(model.rightArm.roll, 0.25f * crouch)
                model.leftArm.pitch = blend(model.leftArm.pitch, 0.35f * crouch)
                model.leftArm.roll = blend(model.leftArm.roll, -0.65f * crouch)
                model.rightLeg.pitch = blend(model.rightLeg.pitch, -0.85f * crouch)
                model.leftLeg.pitch = blend(model.leftLeg.pitch, 0.72f * crouch)
            }
        }
    }
}

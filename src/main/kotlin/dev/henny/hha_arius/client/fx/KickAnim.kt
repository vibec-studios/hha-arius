package dev.henny.hha_arius.client.fx

import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.RotationAxis

/**
 * Spieler-Animation für den Tempel-Tritt ([dev.henny.hha_arius.boss.Scenes.kick]), für alle Zuschauer sichtbar:
 * Treffer (Körper reißt nach hinten), ein Rückwärtssalto durch das Tor, harter Aufprall auf dem Rücken mit kleinem
 * Nachfedern, Aufsetzen, auf ein Knie, Aufstehen. Alles läuft über Schlüsselbilder auf der Spielzeit der Figur.
 */
object KickAnim {

    /** Körper (Neigung nach hinten in Grad, Seitenkippen, Höhe) und Gliedmaßen (Bogenmaß). */
    private class Key(
        val at: Float, val pitch: Float, val roll: Float, val drop: Float, val head: Float,
        val rArm: Float, val rArmZ: Float, val lArm: Float, val lArmZ: Float,
        val rLeg: Float, val rLegZ: Float, val lLeg: Float, val lLegZ: Float,
    )

    private class Track(val total: Float, val keys: List<Key>) {
        var startAge = Float.NaN
    }

    private val tracks = HashMap<Int, Track>()

    private fun keys(h: Float, fl: Float, rec: Float): List<Key> {
        val land = h + fl
        return listOf(
            Key(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            // The torso recoils while the arms trail to either side.
            Key(h * 0.45f, 30f, -8f, 0.05f, -0.5f, -0.45f, 0.48f, -0.34f, -0.57f, -0.5f, 0.15f, 0.35f, -0.15f),
            Key(h, 42f, -12f, 0.08f, -0.6f, -0.35f, 0.76f, 0.08f, -0.68f, -0.7f, 0.2f, 0.45f, -0.2f),
            // Salto rückwärts: gehockt in der Mitte, vor dem Aufprall wieder gestreckt.
            Key(h + fl * 0.3f, 150f, -16f, 0.1f, 0.35f, -0.57f, 0.8f, -0.46f, -0.69f, -1.5f, 0.25f, -1.25f, -0.25f),
            Key(h + fl * 0.6f, 285f, 10f, 0.1f, 0.45f, -0.27f, 0.77f, -0.56f, -0.62f, -1.6f, 0.3f, -1.4f, -0.3f),
            Key(h + fl * 0.88f, 405f, 6f, 0.02f, -0.1f, 0.15f, 0.68f, -0.17f, -0.76f, -0.3f, 0.3f, -0.15f, -0.3f),
            // Aufprall flach auf dem Rücken, kurzes Nachfedern.
            Key(land, 450f, 0f, -0.68f, 0.3f, 0.18f, 0.92f, 0.35f, -1.13f, 0.1f, 0.3f, 0.1f, -0.3f),
            Key(land + rec * 0.08f, 442f, 3f, -0.5f, 0.45f, -0.18f, 0.83f, 0.08f, -0.97f, -0.35f, 0.32f, -0.2f, -0.32f),
            Key(land + rec * 0.2f, 450f, 0f, -0.68f, 0.2f, 0.12f, 0.98f, 0.28f, -1.05f, 0.05f, 0.25f, 0.05f, -0.25f),
            Key(land + rec * 0.32f, 449f, 0f, -0.68f, 0.3f, 0.32f, 0.74f, 0.49f, -0.8f, -0.25f, 0.2f, 0.0f, -0.2f),
            // Aufsetzen, die Hände stützen hinter dem Körper.
            Key(land + rec * 0.52f, 396f, 0f, -0.66f, 0.55f, 0.75f, 0.35f, 0.8f, -0.35f, -1.5f, 0.2f, -1.2f, -0.2f),
            // Auf ein Knie, vorgebeugt.
            Key(land + rec * 0.72f, 348f, 0f, -0.46f, 0.3f, -0.5f, 0.15f, 0.3f, -0.2f, -1.55f, 0.1f, 0.9f, -0.05f),
            Key(land + rec * 0.88f, 354f, 0f, -0.16f, 0.1f, -0.2f, 0.08f, 0.15f, -0.08f, -0.6f, 0.04f, 0.35f, 0f),
            Key(land + rec, 360f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        )
    }

    fun start(entityId: Int, hitstop: Float, flight: Float, total: Int) {
        if (entityId < 0 || total <= 0) return
        val h = hitstop.coerceAtLeast(2f)
        val fl = flight.coerceAtLeast(6f)
        val duration = total.toFloat().coerceAtLeast(h + fl + 10f)
        val timeline = keys(h, fl, duration - h - fl)
        // Solvane's rider is already airborne. Start with the arms thrown out as the
        // hands leave the spike, then use the same tumble and ground recovery.
        val release = Key(0f, 36f, -12f, 0.04f, -0.4f, -0.8f, 0.85f, -0.75f, -0.85f,
            -0.65f, 0.25f, 0.4f, -0.22f)
        tracks[entityId] = Track(duration, if (hitstop == 0f) listOf(release) + timeline.drop(2) else timeline)
    }

    /** Hochgeschleudert vom Kopfstoß Solvanes: Rückstoß → Überschlag hoch in der Luft → verzweifeltes Greifen im Fall. */
    fun ramLaunch(entityId: Int, flight: Float, total: Int) {
        if (entityId < 0 || total <= 0) return
        val fl = flight.coerceAtLeast(10f)
        val dur = total.toFloat().coerceAtLeast(fl + 4f)
        tracks[entityId] = Track(dur, listOf(
            Key(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            // Harter Stoß: Körper reißt nach hinten hoch, Arme fliegen auf.
            Key(fl * 0.14f, 52f, -14f, 0.05f, -0.55f, -0.95f, 0.85f, -0.85f, -0.85f, -0.85f, 0.25f, 0.55f, -0.25f),
            // Überschlag über den Scheitel hoch über ihrem Rücken.
            Key(fl * 0.45f, 175f, 10f, 0.08f, 0.35f, -1.6f, 0.65f, -1.4f, -0.65f, -1.4f, 0.28f, -1.1f, -0.28f),
            // Fall nach vorn gekippt: Kopf nach unten, beide Arme greifen verzweifelt nach dem Eisdorn.
            Key(fl * 0.76f, 295f, -6f, 0.04f, -0.75f, -2.85f, 0.18f, -2.85f, -0.18f, 0.45f, 0.15f, 0.65f, -0.15f),
            Key(fl, 310f, 0f, 0f, -0.95f, -3.1f, 0.12f, -3.1f, -0.12f, 0.55f, 0.08f, 0.45f, -0.08f),
            Key(dur, 360f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        ))
    }

    fun catchMace(id: Int, duration: Int) {
        if (id < 0) return
        val high = Key(5f, -18f, -5f, 0f, 0.25f, -0.71f, 0.09f, 0.25f, -0.6f, -0.36f, 0.16f, 0.35f, -0.12f)
        val low = Key(19f, -52f, 8f, -0.28f, -0.3f, -0.82f, 0.28f, -0.26f, -0.83f, -0.61f, 0.22f, 0.41f, -0.25f)
        fun Key.at(t: Float) = Key(t, pitch, roll, drop, head, rArm, rArmZ, lArm, lArmZ, rLeg, rLegZ, lLeg, lLegZ)
        tracks[id] = Track(duration.toFloat(), listOf(high.at(0f), high.at(15f), low, low.at(23f), high.at(29f),
            low.at(33f), low.at(37f), high.at(43f), low.at(47f),
            Key(duration.toFloat(), 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)))
    }

    fun reset() {
        tracks.clear()
    }

    private fun smooth(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    private fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k

    /** Aktuelles Schlüsselbild-Paar und die Mischung dazwischen; null, wenn keine Animation läuft. */
    private fun sample(state: PlayerEntityRenderState): Triple<Key, Key, Float>? {
        val tr = tracks[state.id] ?: return null
        // Die Spielzeit der Figur beim ersten Zeichnen: Pakete können vor der Figur ankommen (andere Spieler).
        if (tr.startAge.isNaN()) tr.startAge = state.age
        val t = state.age - tr.startAge
        if (t >= tr.total || t < -20f) {
            tracks.remove(state.id)
            return null
        }
        val time = t.coerceAtLeast(0f)
        val keys = tr.keys
        var i = 0
        while (i < keys.size - 2 && keys[i + 1].at <= time) i++
        val a = keys[i]
        val b = keys[i + 1]
        return Triple(a, b, smooth((time - a.at) / (b.at - a.at).coerceAtLeast(0.01f)))
    }

    /** Ganzkörper-Neigung und Absenken am Ende von PlayerEntityRenderer.setupTransforms. */
    @JvmStatic
    fun applyTransform(state: PlayerEntityRenderState, matrices: MatrixStack) {
        if (state.hasVehicle || RideClient.hasCarryGrip(state)) return
        val (a, b, k) = sample(state) ?: return
        matrices.translate(0f, mix(a.drop, b.drop, k), 0f)
        matrices.translate(0f, 0.9f, 0f)
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(mix(a.pitch, b.pitch, k)))
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(mix(a.roll, b.roll, k)))
        matrices.translate(0f, -0.9f, 0f)
    }

    /** Gliedmaßen-Haltung am Ende von PlayerEntityModel.setAngles. */
    @JvmStatic
    fun applyPose(model: PlayerEntityModel, state: PlayerEntityRenderState) {
        if (state.hasVehicle || RideClient.hasCarryGrip(state)) return
        val (a, b, k) = sample(state) ?: return
        model.head.pitch = mix(a.head, b.head, k)
        model.head.yaw = 0f
        model.rightArm.pitch = mix(a.rArm, b.rArm, k)
        model.rightArm.roll = mix(a.rArmZ, b.rArmZ, k)
        model.leftArm.pitch = mix(a.lArm, b.lArm, k)
        model.leftArm.roll = mix(a.lArmZ, b.lArmZ, k)
        model.rightLeg.pitch = mix(a.rLeg, b.rLeg, k)
        model.rightLeg.roll = mix(a.rLegZ, b.rLegZ, k)
        model.leftLeg.pitch = mix(a.lLeg, b.lLeg, k)
        model.leftLeg.roll = mix(a.lLegZ, b.lLegZ, k)
    }
}

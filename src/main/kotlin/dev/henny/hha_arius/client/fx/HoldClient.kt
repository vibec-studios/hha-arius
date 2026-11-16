package dev.henny.hha_arius.client.fx

import net.minecraft.client.MinecraftClient

/**
 * Haltezustand auf dem Client: Solange er gilt, sperren die Mixins Bewegung, Angriff und Benutzen. Der Server löst ihn
 * auf; zur Sicherheit endet er spätestens nach der mitgeschickten Höchstdauer von selbst.
 */
object HoldClient {

    private var endsAt = 0L

    fun set(on: Boolean, ticks: Int) {
        val world = MinecraftClient.getInstance().world
        endsAt = if (on && world != null) world.time + ticks.coerceIn(1, 80) else 0L
    }

    @JvmStatic
    fun isHeld(): Boolean {
        val world = MinecraftClient.getInstance().world ?: return false
        return endsAt != 0L && world.time < endsAt
    }

    fun reset() {
        endsAt = 0L
    }
}

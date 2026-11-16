package dev.henny.hha_arius.boss

import dev.henny.hha.api.HhaAddonContext

/**
 * Config-Schlüssel des Kampfes (über HHA: `/hha list|set|get`, Gruppe „Addon: hha_arius“). Werte erst zur Laufzeit
 * lesen: HHA lädt die Config nach den Addon-Entrypoints.
 */
object BossConfig {

    private var context: HhaAddonContext? = null

    fun register(context: HhaAddonContext) {
        this.context = context
        context.registerNumber("boss_damage_scale", 1.0)
        context.registerNumber("boss_health_scale", 1.0)
        context.registerNumber("boss_health_per_player", 0.55)
        context.registerNumber("boss_max_instances", 4.0)
        context.registerNumber("boss_build_budget", 12000.0)
    }

    private fun num(key: String, fallback: Double): Double = context?.let { runCatching { it.number(key) }.getOrNull() } ?: fallback

    fun damageScale(): Float = num("boss_damage_scale", 1.0).toFloat().coerceIn(0.05f, 10f)

    /** Leben eines Abschnitts: Grundwert × Skalierung × (1 + Anteil × (Spieler − 1)), höchstens 6 Spieler zählen. */
    fun health(base: Float, players: Int): Float {
        val per = num("boss_health_per_player", 0.55).toFloat()
        return base * num("boss_health_scale", 1.0).toFloat().coerceIn(0.05f, 20f) * (1f + per * (players.coerceIn(1, 6) - 1))
    }

    fun maxInstances(): Int = num("boss_max_instances", 4.0).toInt().coerceIn(1, 16)

    fun buildBudget(): Int = num("boss_build_budget", 12000.0).toInt().coerceIn(1000, 100000)
}

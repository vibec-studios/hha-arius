package dev.henny.hha_arius.gear

import dev.henny.hha.api.HhaAddonContext

/**
 * Alle Zahlen der Ausrüstung als HHA-Config (`/hha list` → „Addon: hha_arius“, `/hha set hha_arius.<key> <wert>`).
 * Zeiten in Ticks (die Lore rechnet sie in Sekunden um), Schaden in Halbherzen wie bei HHA.
 *
 * Nicht im Entrypoint lesen: Die Config wird erst nach den Addon-Entrypoints geladen. In Casts, Ticks und Events ist
 * Lesen immer korrekt.
 */
object GearConfig {

    private lateinit var context: HhaAddonContext
    private val fullKeys = HashMap<String, String>()

    private val NUMBERS = linkedMapOf(
        // Frostresonanz (10.1)
        "resonance_max" to 100.0,
        "resonance_gain_solvane" to 10.0,
        "resonance_gain_melee" to 4.0,
        "resonance_gain_counter" to 25.0,
        "resonance_gain_hurt" to 3.0,
        "resonance_full_charge" to 0.9,
        "resonance_decay_delay" to 120.0,
        "resonance_decay_per_second" to 2.0,
        "resonance_perfect_block_ticks" to 10.0,
        "resonance_frost_breath_threshold" to 80.0,
        // Solvane (10.2)
        "solvane_frost_damage" to 2.0,
        "solvane_shatter_every" to 3.0,
        "solvane_shatter_damage" to 5.0,
        "solvane_shatter_slow_ticks" to 20.0,
        "solvane_counter_ticks" to 8.0,
        "solvane_counter_cooldown_success" to 40.0,
        "solvane_counter_cooldown_fail" to 60.0,
        "solvane_counter_knockback" to 0.8,
        // Weiße Linie (Ability 1)
        "white_line_cost" to 40.0,
        "white_line_charge_ticks" to 12.0,
        "white_line_length" to 12.0,
        "white_line_width" to 2.0,
        "white_line_damage" to 8.0,
        "white_line_damage_per_resonance" to 0.15,
        "white_line_cooldown" to 160.0,
        // Frostschritt (Ability 2)
        "frost_step_cost" to 20.0,
        "frost_step_distance" to 6.0,
        "frost_step_guard_ticks" to 6.0,
        "frost_step_cooldown" to 50.0,
        "frost_step_ice_max_ticks" to 240.0,
        // Weißer Kern (Ability 4)
        "white_core_cost" to 60.0,
        "white_core_charge_ticks" to 10.0,
        "white_core_radius" to 6.0,
        "white_core_push" to 3.0,
        "white_core_damage" to 6.0,
        "white_core_resistance_ticks" to 60.0,
        "white_core_cooldown" to 500.0,
        // Nivaris' Schwingenschuppe (10.3)
        "wingscale_height" to 6.0,
        "wingscale_forward" to 4.0,
        "wingscale_slow_fall_ticks" to 100.0,
        "wingscale_landing_radius" to 4.0,
        "wingscale_landing_push" to 2.0,
        "wingscale_cooldown" to 400.0,
        // Siegel und Echo (2.2)
        "seal_blizzard_ticks" to 100.0,
        "seal_max_temperature" to 0.15,
        "echo_cooldown" to 40.0,
        // Beute (11)
        "reward_solvane_repeat_chance" to 0.10,
        "reward_heartfrost_first" to 4.0,
        "reward_heartfrost_repeat" to 2.0,
        "reward_template_first" to 1.0,
        "reward_template_repeat_chance" to 0.5,
        "reward_scales_first" to 6.0,
        "reward_scales_repeat" to 3.0,
    )

    private val TOGGLES = linkedMapOf(
        "white_line" to true,
        "frost_step" to true,
        "frost_step_ice" to true,
        "frost_step_ice_in_arena" to false,
        "white_core" to true,
        "solvane_counter" to true,
        "wingscale" to true,
        "resonance_frost_breath" to true,
        "passive_freeze_immunity" to true,
    )

    fun register(context: HhaAddonContext) {
        this.context = context
        for ((key, default) in NUMBERS) fullKeys[key] = context.registerNumber(key, default)
        for ((key, default) in TOGGLES) fullKeys[key] = context.registerToggle(key, default)
    }

    /** Voller Schlüssel (`hha_arius.<key>`) für Lore und [dev.henny.hha.logic.Cooldowns]. */
    fun key(short: String): String = fullKeys[short] ?: "hha_arius.$short"

    fun num(short: String): Double = context.number(short)

    fun float(short: String): Float = num(short).toFloat()

    fun ticks(short: String): Int = num(short).toInt().coerceAtLeast(0)

    fun on(short: String): Boolean = context.toggle(short)

    /** Alle registrierten Schlüssel (für den Bericht/`/hha addons`). */
    fun allKeys(): Collection<String> = fullKeys.values
}

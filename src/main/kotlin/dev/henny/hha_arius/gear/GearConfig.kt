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
        // Frostspeicher / Manabank (seit 1.4.2) und Litematica-Baupläne
        "bank_capacity" to 100000.0,
        "bank_per_kill" to 2.0,
        "bank_per_kill_health" to 0.2,
        "blueprint_max_blocks" to 20000.0,
        "blueprint_cost_mult" to 1.0,
        "blueprint_blocks_per_tick" to 300.0,
        "resonance_gain_solvane" to 10.0,
        "resonance_gain_melee" to 4.0,
        "resonance_gain_counter" to 25.0,
        "resonance_gain_hurt" to 3.0,
        "resonance_full_charge" to 0.9,
        "resonance_decay_delay" to 120.0,
        "resonance_decay_per_second" to 2.0,
        "resonance_perfect_block_ticks" to 10.0,
        "resonance_frost_breath_threshold" to 80.0,
        // Passives Aufladen (je Sekunde): immer ein Grundwert, außerhalb des Kampfs deutlich mehr. Bis zum Maximum.
        "resonance_passive_per_second" to 2.0,
        "resonance_regen_per_second" to 6.0,
        "resonance_regen_set_per_second" to 9.0,
        "resonance_set_gain_mult" to 1.25,
        // Nahkampf der Handschuhe (Grundschaden × Faktor aus Move)
        "melee_base_damage" to 6.0,
        "melee_gain_jab" to 3.0,
        "melee_gain_cross" to 3.0,
        "melee_gain_hook" to 4.0,
        "melee_gain_kick" to 5.0,
        "melee_gain_spin" to 8.0,
        "melee_gain_parry" to 20.0,
        "melee_gain_uppercut" to 6.0,
        "melee_gain_axe" to 10.0,
        // Vorrücken: Schläge schieben den Spieler ein Stück auf das nächste Ziel vorn zu (Blöcke je Tick, 0 = aus).
        "melee_step_in" to 0.32,
        "melee_link_ticks" to 8.0,
        "melee_knock_guard_ticks" to 30.0,
        // Zauber (Kosten in Resonanz, Zeiten in Ticks)
        "spell_wind_blade_cost" to 8.0,
        "spell_wind_blade_cooldown" to 15.0,
        "spell_wind_blade_damage" to 5.0,
        "spell_wind_blade_range" to 16.0,
        "spell_wind_leap_cost" to 12.0,
        "spell_wind_leap_cooldown" to 40.0,
        "spell_wind_leap_up" to 1.0,
        "spell_wind_leap_forward" to 0.7,
        "spell_gale_cost" to 15.0,
        "spell_gale_cost_full" to 30.0,
        "spell_gale_cooldown" to 80.0,
        "spell_gale_range" to 7.0,
        "spell_gale_knock" to 1.6,
        "spell_gale_damage" to 3.0,
        "spell_gale_charge_min" to 6.0,
        "spell_gale_charge_max" to 24.0,
        "spell_blade_storm_cost" to 18.0,
        "spell_blade_storm_cooldown" to 120.0,
        "spell_blade_storm_damage" to 5.0,
        "spell_blade_storm_range" to 22.0,
        "spell_ice_prison_cost" to 25.0,
        "spell_ice_prison_cooldown" to 300.0,
        "spell_ice_prison_ticks" to 70.0,
        "spell_ice_prison_range" to 22.0,
        "spell_full_pot_cost" to 70.0,
        "spell_full_pot_cooldown" to 1800.0,
        "spell_full_pot_ticks" to 1200.0,
        "spell_tank_mode_cost" to 60.0,
        "spell_tank_mode_cooldown" to 1200.0,
        "spell_tank_mode_ticks" to 400.0,
        "spell_frost_shards_cost" to 10.0,
        "spell_frost_shards_cooldown" to 20.0,
        "spell_frost_shards_damage" to 3.0,
        "spell_frost_shards_range" to 14.0,
        "spell_frost_shards_slow_ticks" to 30.0,
        "spell_ice_wall_cost" to 20.0,
        "spell_ice_wall_cooldown" to 160.0,
        "spell_ice_wall_ticks" to 200.0,
        "spell_frost_field_cost" to 20.0,
        "spell_frost_field_cooldown" to 200.0,
        "spell_frost_field_ticks" to 100.0,
        "spell_ice_armor_cost" to 25.0,
        "spell_ice_armor_cooldown" to 400.0,
        "spell_ice_armor_absorb" to 10.0,
        "spell_ice_armor_ticks" to 160.0,
        "spell_yang_orb_cost" to 10.0,
        "spell_yang_orb_cost_full" to 25.0,
        "spell_yang_orb_cooldown" to 30.0,
        "spell_yang_orb_damage" to 4.0,
        "spell_yang_orb_damage_full" to 10.0,
        "spell_yang_orb_range" to 24.0,
        "spell_yang_orb_charge_min" to 4.0,
        "spell_yang_orb_charge_max" to 30.0,
        "spell_yang_shield_cost" to 5.0,
        "spell_yang_shield_drain" to 8.0,
        "spell_yang_shield_max_ticks" to 100.0,
        "spell_yang_shield_cooldown" to 60.0,
        "spell_yang_shield_reduction" to 0.6,
        "spell_yang_platform_cost" to 12.0,
        "spell_yang_platform_cooldown" to 30.0,
        "spell_yang_platform_ticks" to 900.0,
        "spell_yang_platform_max" to 3.0,
        "spell_yang_bridge_cost" to 20.0,
        "spell_yang_bridge_cooldown" to 100.0,
        "spell_yang_bridge_ticks" to 600.0, // seit 1.7.0 wieder länger (Crix): 30 s
        "spell_yang_glide_cost" to 10.0,
        "spell_yang_glide_drain" to 4.0,
        "spell_yang_glide_max_ticks" to 160.0,
        "spell_yang_glide_cooldown" to 200.0,
        "spell_yang_glide_speed" to 0.55,
        "spell_tornado_cost" to 22.0,
        "spell_tornado_cooldown" to 220.0,
        "spell_tornado_ticks" to 100.0,
        "spell_tornado_damage" to 2.5,
        "spell_tornado_radius" to 4.5,
        "spell_glacier_lance_cost" to 15.0,
        "spell_glacier_lance_cost_full" to 40.0,
        "spell_glacier_lance_cooldown" to 140.0,
        "spell_glacier_lance_damage" to 8.0,
        "spell_glacier_lance_damage_full" to 18.0,
        "spell_glacier_lance_range" to 30.0,
        "spell_glacier_lance_charge_min" to 8.0,
        "spell_glacier_lance_charge_max" to 36.0,
        // Gemeinsam: nach einer großen Technik können andere große Techniken so lange nicht beginnen (Nachhall).
        "art_reverb_ticks" to 30.0,
        // Solvane – Klarer Schnitt
        "clear_cut_cost" to 50.0,
        "clear_cut_damage" to 10.0,
        "clear_cut_damage_full" to 20.0,
        "clear_cut_cooldown" to 240.0,
        "clear_cut_recover" to 20.0,
        "clear_cut_block_max" to 24.0,
        "clear_cut_max_hardness" to 3.0,
        // Weißes Loch (volles Set, Ability 3)
        "white_hole_cost" to 100.0,
        "white_hole_range" to 16.0,
        "white_hole_radius" to 6.0,
        "white_hole_damage" to 16.0,
        "white_hole_push" to 0.16,
        "white_hole_cooldown" to 2400.0,
        // Solvane (10.2)
        "solvane_frost_damage" to 2.0,
        "solvane_shatter_every" to 3.0,
        "solvane_shatter_damage" to 5.0,
        "solvane_shatter_slow_ticks" to 20.0,
        "solvane_counter_ticks" to 14.0, // seit 1.7.0 großzügiger
        "solvane_counter_cooldown_success" to 40.0,
        "solvane_counter_cooldown_fail" to 60.0,
        "solvane_counter_knockback" to 0.8,
        // Frostschritt (Ability 2)
        "frost_step_cost" to 15.0,
        "frost_step_distance" to 6.0,
        "frost_step_guard_ticks" to 6.0,
        "frost_step_cooldown" to 50.0,
        "frost_step_ice_max_ticks" to 240.0,
        // Weißer Kern (Ability 4)
        "white_core_cost" to 40.0,
        "white_core_charge_ticks" to 10.0,
        "white_core_radius" to 6.0,
        "white_core_push" to 3.0,
        "white_core_damage" to 6.0,
        "white_core_resistance_ticks" to 60.0,
        "white_core_cooldown" to 500.0,
        // Seit 1.3.1: Drachenatem (Brust, Ability 1) und Schwingensprung (Beine, Ability 2)
        "breath_cost" to 35.0,
        "breath_ticks" to 30.0,
        "breath_range" to 8.0,
        "breath_damage" to 1.8,
        "breath_slow_ticks" to 50.0,
        "breath_cooldown" to 300.0,
        "tail_cost" to 25.0,
        "tail_radius" to 5.5,
        "tail_damage" to 8.0,
        "tail_cooldown" to 160.0,
        "wing_leap_cost" to 20.0,
        "wing_leap_forward" to 1.15,
        "wing_leap_up" to 0.8,
        "wing_leap_radius" to 4.5,
        "wing_leap_damage" to 7.0,
        "wing_leap_cooldown" to 120.0,
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
        "reward_gauntlets_repeat_chance" to 0.10,
        "reward_heartfrost_first" to 4.0,
        "reward_heartfrost_repeat" to 2.0,
        "reward_template_first" to 1.0,
        "reward_template_repeat_chance" to 0.5,
        "reward_scales_first" to 6.0,
        "reward_scales_repeat" to 3.0,
    )

    private val TOGGLES = linkedMapOf(
        "gauntlets" to true,
        "clear_cut" to true,
        "clear_cut_blocks" to true,
        "white_hole" to true,
        "art_constructs_in_arena" to false,
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

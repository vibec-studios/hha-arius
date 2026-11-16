package dev.henny.hha_arius.gear

import dev.henny.hha.api.HhaAddonContext

/** Dynamische Lore über HHA: Zahlen kommen live aus der Config (auch nach `/hha set`). */
object GearLore {

    private const val P = "item.hha_arius"

    fun register(context: HhaAddonContext) {
        fun k(short: String) = GearConfig.key(short)

        context.registerLore(GearItems.EIRHOLM_HELMET) {
            line("$P.eirholm.lore.set")
            line("$P.eirholm_helmet.lore1")
            line("$P.eirholm_helmet.lore2", num(k("white_hole_range")), num(k("white_hole_damage")))
            line("$P.eirholm_helmet.lore3", seconds(k("white_hole_cooldown")))
        }
        context.registerLore(GearItems.EIRHOLM_CHESTPLATE) {
            line("$P.eirholm.lore.set")
            line("$P.eirholm_chestplate.lore1", num(k("white_core_cost")), num(k("white_core_radius")), num(k("white_core_damage")))
            line("$P.eirholm_chestplate.lore2", seconds(k("white_core_resistance_ticks")), seconds(k("white_core_cooldown")))
            line("$P.eirholm_chestplate.lore3")
        }
        context.registerLore(GearItems.EIRHOLM_LEGGINGS) {
            line("$P.eirholm.lore.set")
            line("$P.eirholm_leggings.lore1", num(k("frost_step_cost")), num(k("frost_step_distance")), seconds(k("frost_step_cooldown")))
            line("$P.eirholm_leggings.lore2")
        }
        context.registerLore(GearItems.EIRHOLM_BOOTS) {
            line("$P.eirholm.lore.set")
            line("$P.eirholm_boots.lore1")
            line("$P.eirholm_boots.lore2", num(k("resonance_rest")), num(k("resonance_rest_set")))
        }
        context.registerLore(GearItems.SOLVANE) {
            line("$P.solvane.lore1", num(k("solvane_frost_damage")))
            line("$P.solvane.lore2", num(k("solvane_shatter_every")), num(k("solvane_shatter_damage")))
            line("$P.solvane.lore3", seconds(k("solvane_counter_ticks")), seconds(k("solvane_counter_cooldown_success")), seconds(k("solvane_counter_cooldown_fail")))
            line("$P.solvane.lore4", num(k("clear_cut_cost")), num(k("clear_cut_damage")), num(k("clear_cut_damage_full")), seconds(k("clear_cut_cooldown")))
            line("$P.solvane.lore5", num(k("clear_cut_max_hardness")), num(k("clear_cut_block_max")))
        }
        context.registerLore(GearItems.ARIUS_GAUNTLETS) {
            line("$P.arius_gauntlets.lore1", num(k("melee_base_damage")))
            line("$P.arius_gauntlets.lore2")
            line("$P.arius_gauntlets.lore3")
            line("$P.arius_gauntlets.lore4")
        }
        context.registerLore(GearItems.NIVARIS_WINGSCALE) {
            line("$P.nivaris_wingscale.lore1", num(k("wingscale_height")), num(k("wingscale_forward")), seconds(k("wingscale_slow_fall_ticks")))
            line("$P.nivaris_wingscale.lore2", seconds(k("wingscale_cooldown")))
        }
        context.registerLore(GearItems.EIRHOLM_SEAL) {
            line("$P.eirholm_seal.lore1")
            line("$P.eirholm_seal.lore2", seconds(k("seal_blizzard_ticks")))
            line("$P.eirholm_seal.lore3")
        }
        context.registerLore(GearItems.ECHO_OF_EIRHOLM) {
            line("$P.echo_of_eirholm.lore1")
            line("$P.echo_of_eirholm.lore2")
        }
        context.registerLore(GearItems.HEARTFROST) { line("$P.heartfrost.lore1") }
        context.registerLore(GearItems.NIVARIS_SCALE) { line("$P.nivaris_scale.lore1") }
    }
}

package dev.henny.hha_arius.gear.art

import dev.henny.hha_arius.gear.GearItems
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.world.World

/**
 * Arius' Kampfhandschuhe: EIN Item in der Haupthand stellt beide Hände dar (kein eigener Rüstungsslot). Links-/
 * Rechtsklick fängt der Client ab und schickt [ArtInputC2S]; der Server entscheidet ([GauntletCombat], [SpellCasting]).
 * Kommt trotzdem ein normales Benutzen an (z. B. ohne Client-Mod), passiert nichts.
 */
class GauntletsItem(settings: Settings) : Item(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult = ActionResult.PASS

    companion object {
        fun mode(stack: ItemStack): GauntletMode = GauntletMode.of(stack.get(GearItems.GAUNTLET_MODE))

        fun isGauntlets(stack: ItemStack): Boolean = stack.isOf(GearItems.ARIUS_GAUNTLETS)
    }
}

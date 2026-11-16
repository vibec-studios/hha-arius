package dev.henny.hha_arius.gear

import dev.henny.hha.api.HhaAddonContext
import dev.henny.hha.api.set.ArmorSet
import dev.henny.hha_arius.HhaArius
import net.minecraft.entity.Entity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Ausrüstung von Eirholm (Konzept 10–11): Rüstung, Solvane, Schwingenschuppe, Siegel, Echo, Materialien, Vorlagen.
 * Alles läuft über die HHA-Addon-API: Items über `HhaItemHelpers`, Set (→ Kit `/hha kit hha_arius.eirholm`), Abilities
 * auf Ability 1/2/4, Config-Schlüssel und dynamische Lore.
 */
object Gear {

    lateinit var set: ArmorSet
        private set

    private var registered = false

    /** Registriert Items, Rüstung, Set, Abilities, Lore und Config über HHA. */
    fun register(context: HhaAddonContext) {
        if (registered) return
        registered = true

        GearConfig.register(context)
        GearItems.init()
        GearNet.init()

        set = context.armorSet("eirholm") {
            helmet(GearItems.EIRHOLM_HELMET)
            chestplate(GearItems.EIRHOLM_CHESTPLATE)
            leggings(GearItems.EIRHOLM_LEGGINGS)
            boots(GearItems.EIRHOLM_BOOTS)
            weapon(GearItems.SOLVANE)
        }

        EirholmAbilities.register(context)
        GearLore.register(context)

        GearServer.init()
        GearTasks.init()
        Resonance.init()
        SolvaneEdge.init()
        SolvaneCounter.init()
        EirholmAbilities.init()
        Wingscale.init()
        SealRitual.init()
    }

    fun hasFullSet(player: PlayerEntity): Boolean = registered && set.hasFullSet(player)
}

/**
 * Brücke für die Java-Mixins unter `mixin/gear` (beide Seiten: Pulverschnee-Kollision läuft auch im Client).
 */
object GearHooks {

    /** Volles Eirholm-Set trägt über Pulverschnee (keine Config: Client und Server müssen gleich entscheiden). */
    @JvmStatic
    fun walksOnPowderSnow(entity: Entity): Boolean = entity is PlayerEntity && Gear.hasFullSet(entity)

    /** Volles Set: kein Gefrieren. */
    @JvmStatic
    fun freezeImmune(entity: Entity): Boolean {
        if (entity !is PlayerEntity || !Gear.hasFullSet(entity)) return false
        return entity !is ServerPlayerEntity || GearConfig.on("passive_freeze_immunity")
    }
}

object GearRewards {
    /**
     * Gibt einem Teilnehmer seine Beute (eigene Beute je Spieler; volles Inventar → vor ihm fallen lassen).
     * Vergibt dabei auch die Fortschritte „Die weiße Klinge“ (Sieg) und „Kein Sturm mehr“ (Sieg ohne k. o.).
     * @param firstWin erster Sieg dieses Spielers
     * @param flawless niemand aus der Gruppe war k. o.
     */
    fun grant(player: ServerPlayerEntity, firstWin: Boolean, flawless: Boolean) {
        for (stack in loot(player, firstWin, flawless)) player.giveOrDropStack(stack)
        GearAdvancements.grant(player, "white_blade")
        if (flawless) GearAdvancements.grant(player, "no_storm")
    }

    /** Die Beute eines Siegs als Liste (ohne sie zu vergeben); der Kampf legt sie ins Eirholm-Fach des Spielers. */
    fun loot(player: ServerPlayerEntity, firstWin: Boolean, flawless: Boolean): List<ItemStack> {
        val random = player.random
        val loot = ArrayList<ItemStack>()
        fun add(item: Item, count: Int) {
            if (count > 0) loot.add(ItemStack(item, count))
        }
        fun chance(key: String): Boolean = random.nextDouble() < GearConfig.num(key)

        if (firstWin) {
            add(GearItems.SOLVANE, 1)
            add(GearItems.HEARTFROST, GearConfig.ticks("reward_heartfrost_first"))
            add(GearItems.EIRHOLM_UPGRADE_TEMPLATE, GearConfig.ticks("reward_template_first"))
            add(GearItems.NIVARIS_SCALE, GearConfig.ticks("reward_scales_first"))
            add(GearItems.ECHO_OF_EIRHOLM, 1)
        } else {
            if (chance("reward_solvane_repeat_chance")) add(GearItems.SOLVANE, 1)
            add(GearItems.HEARTFROST, GearConfig.ticks("reward_heartfrost_repeat"))
            if (chance("reward_template_repeat_chance")) add(GearItems.EIRHOLM_UPGRADE_TEMPLATE, 1)
            add(GearItems.NIVARIS_SCALE, GearConfig.ticks("reward_scales_repeat"))
        }
        if (flawless) add(GearItems.WHITE_BLADE_TRIM_TEMPLATE, 1)
        return loot
    }

    /**
     * Niederlage/Abbruch: Das Siegel bleibt beim Start im Inventar (es wird erst beim Sieg verbraucht). Ein Ersatz
     * kommt deshalb nur, wenn der Spieler gar keins mehr hat (z. B. fallen gelassen oder verloren).
     */
    fun returnSeal(player: ServerPlayerEntity) {
        if (SealRitual.findSeal(player) != null) return
        player.giveOrDropStack(ItemStack(GearItems.EIRHOLM_SEAL))
    }

    /** Sieg: entfernt ein Siegel aus dem Inventar des Beschwörers (falls noch vorhanden). */
    fun consumeSeal(player: ServerPlayerEntity): Boolean {
        val seal = SealRitual.findSeal(player) ?: return false
        seal.decrement(1)
        player.inventory.markDirty()
        return true
    }
}

/**
 * Fortschritte `hha_arius:<id>` mit einem `minecraft:impossible`-Kriterium „granted“; vergeben nur per Code.
 * IDs: `seal` (Siegel benutzt, Wurzel), `under_the_line` (Schnitt ohne Treffer überstanden), `takt_break` (Taktbruch),
 * `white_blade` (Sieg), `no_storm` (Sieg ohne k. o.).
 */
object GearAdvancements {

    val IDS = listOf("seal", "under_the_line", "takt_break", "white_blade", "no_storm")

    fun grant(player: ServerPlayerEntity, id: String) {
        val server = player.entityWorld.server ?: return
        val entry = server.advancementLoader.get(HhaArius.id(id))
        if (entry == null) {
            HhaArius.LOGGER.warn("Fortschritt hha_arius:{} fehlt", id)
            return
        }
        val tracker = player.advancementTracker
        val progress = tracker.getProgress(entry)
        if (progress.isDone) return
        for (criterion in progress.unobtainedCriteria.toList()) tracker.grantCriterion(entry, criterion)
    }
}

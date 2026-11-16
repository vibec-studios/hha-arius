package dev.henny.hha_arius.gear

import com.mojang.serialization.Codec
import dev.henny.hha.api.item.HhaItemHelpers
import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.minecraft.component.ComponentType
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.SmithingTemplateItem
import net.minecraft.item.ToolMaterial
import net.minecraft.item.equipment.ArmorMaterial
import net.minecraft.item.equipment.EquipmentAsset
import net.minecraft.item.equipment.EquipmentAssetKeys
import net.minecraft.item.equipment.EquipmentType
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.BlockTags
import net.minecraft.registry.tag.TagKey
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.Rarity

/**
 * Alle Items der Ausrüstung (Namen verbindlich laut docs/SPEC_TEAM.md). Registriert über [HhaItemHelpers], damit HHA sie
 * wie die eigenen Items behandelt; Assets (Modelle, Texturen, `equipment/eirholm.json`) liefert der Grafik-Teil.
 */
object GearItems {

    /** Reparaturmaterial der Rüstung und von Solvane (Tag `hha_arius:eirholm_repair` = Herzfrost). */
    val REPAIR_TAG: TagKey<Item> = TagKey.of(RegistryKeys.ITEM, HhaArius.id("eirholm_repair"))

    val EQUIPMENT_ASSET: RegistryKey<EquipmentAsset> = RegistryKey.of(EquipmentAssetKeys.REGISTRY_KEY, HhaArius.id("eirholm"))

    private val DEFENSE = mapOf(
        EquipmentType.HELMET to 3,
        EquipmentType.CHESTPLATE to 8,
        EquipmentType.LEGGINGS to 6,
        EquipmentType.BOOTS to 3,
    )

    /** Wie Netherite (Haltbarkeit 37, Verzauberbarkeit 15), Härte 3,5 statt 3, Rückstoßresistenz 0,15 statt 0,1. */
    val ARMOR_MATERIAL = ArmorMaterial(37, DEFENSE, 15, SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE, 3.5f, 0.15f, REPAIR_TAG, EQUIPMENT_ASSET)

    /** Netherite-Werkzeugstufe; Schadensbonus 4 → Solvane 1 + 4 + 4 = 9 Schaden. */
    val TOOL_MATERIAL = ToolMaterial(BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 2031, 9.0f, 4.0f, 15, REPAIR_TAG)

    /** Gewählter Abschnitt des Echos (1 Tempel, 2 Gletscher, 3 Herz, 4 Finale). */
    lateinit var ECHO_STAGE: ComponentType<Int> private set

    /** Modus der Kampfhandschuhe (0 Nahkampf, 1 Magie), am Stapel, damit alle Spieler ihn sehen. */
    lateinit var GAUNTLET_MODE: ComponentType<Int> private set

    /** Abklinggruppen ohne eigenes Item (HUD über `use_cooldown`-Stapel): Klarer Schnitt, Weißes Loch. */
    val CLEAR_CUT_GROUP: Identifier = HhaArius.id("clear_cut")
    val WHITE_HOLE_GROUP: Identifier = HhaArius.id("white_hole")

    lateinit var EIRHOLM_HELMET: Item private set
    lateinit var EIRHOLM_CHESTPLATE: Item private set
    lateinit var EIRHOLM_LEGGINGS: Item private set
    lateinit var EIRHOLM_BOOTS: Item private set
    lateinit var SOLVANE: Item private set
    lateinit var ARIUS_GAUNTLETS: Item private set
    lateinit var NIVARIS_WINGSCALE: Item private set
    lateinit var EIRHOLM_SEAL: Item private set
    lateinit var ECHO_OF_EIRHOLM: Item private set
    lateinit var HEARTFROST: Item private set
    lateinit var NIVARIS_SCALE: Item private set
    lateinit var EIRHOLM_UPGRADE_TEMPLATE: Item private set
    lateinit var WHITE_BLADE_TRIM_TEMPLATE: Item private set

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true

        ECHO_STAGE = Registry.register(
            Registries.DATA_COMPONENT_TYPE, HhaArius.id("echo_stage"),
            ComponentType.builder<Int>().codec(Codec.intRange(1, 4)).packetCodec(PacketCodecs.VAR_INT).build(),
        )

        GAUNTLET_MODE = Registry.register(
            Registries.DATA_COMPONENT_TYPE, HhaArius.id("gauntlet_mode"),
            ComponentType.builder<Int>().codec(Codec.intRange(0, 1)).packetCodec(PacketCodecs.VAR_INT).build(),
        )

        EIRHOLM_HELMET = armor("eirholm_helmet", EquipmentType.HELMET)
        EIRHOLM_CHESTPLATE = armor("eirholm_chestplate", EquipmentType.CHESTPLATE)
        EIRHOLM_LEGGINGS = armor("eirholm_leggings", EquipmentType.LEGGINGS)
        EIRHOLM_BOOTS = armor("eirholm_boots", EquipmentType.BOOTS)

        SOLVANE = HhaItemHelpers.register(HhaArius.id("solvane")) {
            SolvaneItem(
                it.sword(TOOL_MATERIAL, 4.0f, -2.4f).fireproof().rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false),
            )
        }
        ARIUS_GAUNTLETS = HhaItemHelpers.register(HhaArius.id("arius_gauntlets")) {
            dev.henny.hha_arius.gear.art.GauntletsItem(
                it.maxCount(1).maxDamage(2031).fireproof().rarity(Rarity.EPIC).repairable(REPAIR_TAG).enchantable(15)
                    .component(GAUNTLET_MODE, 0)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false),
            )
        }
        NIVARIS_WINGSCALE = HhaItemHelpers.register(HhaArius.id("nivaris_wingscale")) {
            WingscaleItem(it.maxCount(1).fireproof().rarity(Rarity.EPIC))
        }
        EIRHOLM_SEAL = HhaItemHelpers.register(HhaArius.id("eirholm_seal")) {
            SealItem(it.maxCount(1).fireproof().rarity(Rarity.RARE))
        }
        ECHO_OF_EIRHOLM = HhaItemHelpers.register(HhaArius.id("echo_of_eirholm")) {
            EchoItem(it.maxCount(1).fireproof().rarity(Rarity.EPIC).component(ECHO_STAGE, 1))
        }
        HEARTFROST = HhaItemHelpers.register(HhaArius.id("heartfrost")) { Item(it.fireproof().rarity(Rarity.RARE)) }
        NIVARIS_SCALE = HhaItemHelpers.register(HhaArius.id("nivaris_scale")) { Item(it.fireproof().rarity(Rarity.UNCOMMON)) }
        EIRHOLM_UPGRADE_TEMPLATE = HhaItemHelpers.register(HhaArius.id("eirholm_upgrade_smithing_template")) {
            eirholmUpgradeTemplate(it.fireproof().rarity(Rarity.RARE))
        }
        WHITE_BLADE_TRIM_TEMPLATE = HhaItemHelpers.register(HhaArius.id("white_blade_armor_trim_smithing_template")) {
            SmithingTemplateItem.of(it.rarity(Rarity.EPIC))
        }
        dev.henny.hha_arius.gear.art.Constructs.registerBlocks()
        registerItemGroup()
    }

    val armorPieces: List<Item> get() = listOf(EIRHOLM_HELMET, EIRHOLM_CHESTPLATE, EIRHOLM_LEGGINGS, EIRHOLM_BOOTS)

    private fun armor(name: String, type: EquipmentType): Item =
        HhaItemHelpers.registerArmor(HhaArius.id(name), ARMOR_MATERIAL, type) {
            it.fireproof().rarity(Rarity.EPIC).component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
        }

    /** Wie Vanillas Netherite-Aufwertung, nur mit eigenen Texten (Netherite-Teil + Herzfrost). */
    private fun eirholmUpgradeTemplate(settings: Item.Settings): SmithingTemplateItem {
        val base = "item.hha_arius.smithing_template.eirholm_upgrade"
        return SmithingTemplateItem(
            Text.translatable("$base.applies_to").formatted(Formatting.BLUE),
            Text.translatable("$base.ingredients").formatted(Formatting.BLUE),
            Text.translatable("$base.base_slot_description"),
            Text.translatable("$base.additions_slot_description"),
            listOf(
                Identifier.ofVanilla("container/slot/helmet"),
                Identifier.ofVanilla("container/slot/chestplate"),
                Identifier.ofVanilla("container/slot/leggings"),
                Identifier.ofVanilla("container/slot/boots"),
            ),
            listOf(Identifier.ofVanilla("container/slot/amethyst_shard")),
            settings,
        )
    }

    private fun registerItemGroup() {
        Registry.register(
            Registries.ITEM_GROUP,
            RegistryKey.of(RegistryKeys.ITEM_GROUP, HhaArius.id("eirholm")),
            FabricItemGroup.builder()
                .icon { ItemStack(SOLVANE) }
                .displayName(Text.translatable("itemGroup.hha_arius.eirholm"))
                .entries { _, entries ->
                    for (piece in armorPieces) entries.add(piece)
                    entries.add(SOLVANE)
                    entries.add(ARIUS_GAUNTLETS)
                    entries.add(NIVARIS_WINGSCALE)
                    entries.add(EIRHOLM_SEAL)
                    entries.add(ECHO_OF_EIRHOLM)
                    entries.add(HEARTFROST)
                    entries.add(NIVARIS_SCALE)
                    entries.add(EIRHOLM_UPGRADE_TEMPLATE)
                    entries.add(WHITE_BLADE_TRIM_TEMPLATE)
                }
                .build(),
        )
    }
}

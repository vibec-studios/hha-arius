package dev.henny.hha_arius.client

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient

/**
 * Vorwärmen beim Betreten einer Welt (seit 1.6.1, Crix: „teilweise laggt es, wenn man sich die Rüstung bzw. das Kit
 * holt – Framedrops, die Sounds haken“). Bisher wurden Modelle (Rüstung, Handschuhe, Geisterdrache), Entity-Texturen
 * und die großen Pose-Klassen erst beim ersten Zeichnen gebaut bzw. geladen – genau in dem Moment, in dem man das Kit
 * bekommt, also mitten im Spiel auf dem Render-Thread. Jetzt passiert das einmal beim Betreten, wo ein kurzer Ruck
 * nicht stört. (Die Klangeffekte lädt Minecraft dank `preload` schon beim Ressourcen-Laden.)
 */
object Preload {

    private val TEXTURES = listOf(
        "eirholm_armor", "eirholm_armor_glow", "gauntlets", "gauntlets_glow", "nivaris", "nivaris_glow_eyes",
        "nivaris_glow_heart", "nivaris_resonance", "arius_dark", "arius_dark_glow", "arius_white", "arius_white_glow",
    )

    private val CLASSES = listOf(
        "dev.henny.hha_arius.gear.client.art.ArtPose",
        "dev.henny.hha_arius.gear.client.art.ArtPoseMath",
        "dev.henny.hha_arius.gear.client.art.GauntletFirstPerson",
        "dev.henny.hha_arius.gear.client.art.ArtVisuals",
        "dev.henny.hha_arius.gear.client.art.SwordTrails",
        "dev.henny.hha_arius.client.fx.ArtEffectsKt",
        "dev.henny.hha_arius.client.fx.DragonBreathFx",
        "dev.henny.hha_arius.client.fx.DragonTailFx",
        "dev.henny.hha_arius.client.fx.WhiteHoleFx",
    )

    private var done = false

    fun init() {
        ClientPlayConnectionEvents.JOIN.register { _, _, client -> client.execute { warm(client) } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> done = false }
    }

    private fun warm(client: MinecraftClient) {
        if (done) return
        done = true
        val start = System.nanoTime()
        for (t in TEXTURES) runCatching { client.textureManager.getTexture(HhaArius.id("textures/entity/$t.png")) }
        runCatching { dev.henny.hha_arius.client.model.armor.ArmorParts.get() }
        runCatching { dev.henny.hha_arius.gear.client.art.GauntletParts.warm() }
        runCatching { dev.henny.hha_arius.client.fx.DragonBreathFx.model() }
        val loader = Preload::class.java.classLoader
        for (c in CLASSES) runCatching { Class.forName(c, true, loader) }
        HhaArius.LOGGER.info("HHA Arius vorgewärmt in {} ms", (System.nanoTime() - start) / 1_000_000)
    }
}

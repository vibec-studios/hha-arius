package dev.henny.hha_arius.client.fx

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Client-Einstellungen des Addons (`/ariusc`): [fx] skaliert eigene Partikel (0–1), [flash] schaltet Bildblitze ab,
 * [shake] das dezente Kamerarucken bei schweren Treffern ([CameraKick]). Kamerarütteln und 3D-Effekte allgemein steuert
 * HHA (`/hhac`, Menü K); ist dort das Kameraschütteln aus, ruckt auch das Addon nicht.
 */
object AriusClientConfig {
    var fx = 1f
    var flash = true
    var shake = true

    /** Seit 1.10.0: Paradeanzeige beim Halten der Solvane (`/ariusc parry`). */
    var parryCue = true

    /** Schneesturm-Darstellung: "auto" (Welt, bei Shaderpack zusätzlich Bildschirm), "world", "screen", "both". */
    var snow = "auto"

    val SNOW_MODES = listOf("auto", "world", "screen", "both")

    private val file get() = FabricLoader.getInstance().configDir.resolve("hha_arius_client.json")

    fun load() {
        runCatching {
            if (!Files.exists(file)) return
            val o = Gson().fromJson(Files.readString(file), JsonObject::class.java) ?: return
            fx = o.get("fx")?.asFloat?.coerceIn(0f, 1f) ?: fx
            flash = o.get("flash")?.asBoolean ?: flash
            shake = o.get("shake")?.asBoolean ?: shake
            parryCue = o.get("parry")?.asBoolean ?: parryCue
            snow = o.get("snow")?.asString?.takeIf { it in SNOW_MODES } ?: snow
        }
    }

    fun save() {
        runCatching {
            val o = JsonObject()
            o.addProperty("fx", fx)
            o.addProperty("flash", flash)
            o.addProperty("shake", shake)
            o.addProperty("parry", parryCue)
            o.addProperty("snow", snow)
            Files.writeString(file, Gson().toJson(o))
        }
    }
}

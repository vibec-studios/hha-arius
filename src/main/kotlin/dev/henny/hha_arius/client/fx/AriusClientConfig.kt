package dev.henny.hha_arius.client.fx

import com.google.gson.Gson
import com.google.gson.JsonObject
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * Client-Einstellungen des Addons (`/ariusc`): [fx] skaliert eigene Partikel (0–1), [flash] schaltet Bildblitze ab.
 * Kamerarütteln und 3D-Effekte allgemein steuert HHA (`/hhac`, Menü K); das Addon nutzt dafür HHAs Effekte.
 */
object AriusClientConfig {
    var fx = 1f
    var flash = true

    private val file get() = FabricLoader.getInstance().configDir.resolve("hha_arius_client.json")

    fun load() {
        runCatching {
            if (!Files.exists(file)) return
            val o = Gson().fromJson(Files.readString(file), JsonObject::class.java) ?: return
            fx = o.get("fx")?.asFloat?.coerceIn(0f, 1f) ?: fx
            flash = o.get("flash")?.asBoolean ?: flash
        }
    }

    fun save() {
        runCatching {
            val o = JsonObject()
            o.addProperty("fx", fx)
            o.addProperty("flash", flash)
            Files.writeString(file, Gson().toJson(o))
        }
    }
}

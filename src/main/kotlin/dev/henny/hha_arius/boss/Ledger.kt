package dev.henny.hha_arius.boss

import com.google.gson.Gson
import com.mojang.serialization.Codec
import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Spielerseitiges Hauptbuch: liegt **in der Spielerdatei** (Fabric-Attachment), wird also immer zusammen mit dem
 * Inventar gespeichert. Dadurch können Siegel-Entnahme und Einsatzvermerk bzw. Auszahlung und Quittung nie
 * auseinanderlaufen, auch nicht bei einem Absturz zwischen dem Speichern von Spieler- und Weltdaten.
 *
 * @property stake Begegnungs-ID, für die gerade ein Siegel eingesetzt ist (leer = keins)
 * @property paid IDs bereits ausgezahlter Ansprüche (Quittungen)
 */
class Ledger(var stake: String = "", var paid: MutableList<String> = ArrayList()) {
    /** In dieser Sitzung ausgezahlt (noch nicht nachweislich gespeichert). Nicht serialisiert. */
    @Transient
    var sessionPaid: MutableSet<String> = HashSet()

    fun fix(): Ledger {
        @Suppress("SENSELESS_COMPARISON")
        if (stake == null) stake = ""
        @Suppress("SENSELESS_COMPARISON")
        if (paid == null) paid = ArrayList()
        @Suppress("SENSELESS_COMPARISON")
        if (sessionPaid == null) sessionPaid = HashSet()
        return this
    }
}

object Ledgers {
    private val GSON = Gson()

    private val CODEC: Codec<Ledger> = Codec.STRING.xmap(
        { runCatching { GSON.fromJson(it, Ledger::class.java) }.getOrNull()?.fix() ?: Ledger() },
        { GSON.toJson(it) },
    )

    val TYPE: AttachmentType<Ledger> = AttachmentRegistry.create(HhaArius.id("ledger")) { builder ->
        builder.persistent(CODEC).copyOnDeath().initializer { Ledger() }
    }

    fun init() {
        TYPE.toString()
    }

    fun of(player: ServerPlayerEntity): Ledger = player.getAttachedOrCreate(TYPE).fix()
}

package dev.henny.hha_arius.boss

import com.google.gson.Gson
import com.mojang.serialization.Codec
import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier

/**
 * Spielerseitiges Hauptbuch: liegt **in der Spielerdatei** (Fabric-Attachment), wird also immer zusammen mit dem
 * Inventar gespeichert. Dadurch können Siegel-Entnahme und Einsatzvermerk bzw. Auszahlung und Quittung nie
 * auseinanderlaufen, auch nicht bei einem Absturz zwischen dem Speichern von Spieler- und Weltdaten.
 *
 * @property stake Begegnungs-ID, für die gerade ein Siegel eingesetzt ist (leer = keins)
 * @property paid IDs bereits ausgezahlter Ansprüche (Quittungen)
 */
class Ledger(
    var stake: String = "",
    var paid: MutableList<String> = ArrayList(),
    /** Eirholm-Fach: Gegenstände, die noch nicht ins Inventar passten (Item-ID → Anzahl je Eintrag). */
    var inbox: MutableList<Entry> = ArrayList(),
) {
    class Entry(var id: String = "", var count: Int = 0)

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
        @Suppress("SENSELESS_COMPARISON")
        if (inbox == null) inbox = ArrayList()
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

    /** Legt Gegenstände ins Fach (Spielerdatei). Nur eigene Items ohne Sonderdaten, darum genügen ID und Anzahl. */
    fun deposit(player: ServerPlayerEntity, stacks: List<ItemStack>) {
        val ledger = of(player)
        for (stack in stacks) {
            if (stack.isEmpty) continue
            ledger.inbox.add(Ledger.Entry(Registries.ITEM.getId(stack.item).toString(), stack.count))
        }
    }

    /**
     * Fach → Inventar, so weit Platz ist. Was nicht passt, bleibt im Fach (nie als Drop in der Welt). Fach und Inventar
     * liegen in derselben Spielerdatei, also geht dabei nichts verloren und nichts doppelt.
     * @return Anzahl der Gegenstände, die noch im Fach liegen
     */
    fun flush(player: ServerPlayerEntity): Int {
        val ledger = of(player)
        if (ledger.inbox.isEmpty()) return 0
        val it = ledger.inbox.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val item = Registries.ITEM.get(Identifier.of(entry.id))
            if (item == Items.AIR || entry.count <= 0) {
                it.remove()
                continue
            }
            while (entry.count > 0) {
                val stack = ItemStack(item, minOf(entry.count, item.maxCount))
                val before = stack.count
                player.inventory.insertStack(stack)
                val moved = before - stack.count
                if (moved <= 0) break
                entry.count -= moved
            }
            if (entry.count <= 0) it.remove()
        }
        player.inventory.markDirty()
        return ledger.inbox.sumOf { it.count }
    }
}

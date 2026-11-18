package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaLayout
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import dev.henny.hha_arius.HhaArius as Arius
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import dev.henny.hha.api.event.HhaEvents
import dev.henny.hha_arius.gear.GearRewards
import net.minecraft.item.BlockItem
import net.minecraft.item.BucketItem
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.util.ActionResult
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.floor

/** Laufende Begegnungen im Heiligtum (eine je Instanz-Slot) und ihre Anbindung an die Spielereignisse. */
object Encounters {

    val WORLD: RegistryKey<World> = dev.henny.hha_arius.arena.ArenaDimension.WORLD

    private val fights = HashMap<Int, EirholmFight>()

    /** Spieler, die gerade von uns in die Arena gebracht werden (kein Nachzügler). */
    val arriving = HashSet<UUID>()

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            Hold.tick(server)
            tickInboxes(server)
            for (fight in fights.values.toList()) fight.tick()
        }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            if (entity is ServerPlayerEntity) {
                val fight = fightOf(entity)
                !(fight != null && fight.tryKnockOut(entity))
            } else {
                true
            }
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (entity !is ServerPlayerEntity) return@register true
            if (Hold.isHeld(entity) && !Hold.allowDamage) return@register false
            // In der Arena gibt es keinen Fallschaden: Tritte, Stürze und das Gewölbe sollen nicht zusätzlich strafen.
            if (entity.entityWorld.registryKey == WORLD && source.isIn(DamageTypeTags.IS_FALL)) return@register false
            // Schildblöcke gegen Solvane zählen für die Anpassung „Schildmauer“.
            if (entity.isBlocking && source.attacker is AriusEntity) fightOf(entity)?.onShieldBlock(entity)
            true
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            fightOf(player)?.onLeave(player)
            Hold.forget(player.uuid)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player
            server.execute {
                onArrive(player, null)
                deliverPending(player)
            }
        }
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register { player, origin, destination ->
            if (origin.registryKey == WORLD) fights.values.firstOrNull { it.world === origin && it.isParticipant(player) && !it.ended }?.onLeave(player)
            if (destination.registryKey == WORLD && player.uuid !in arriving) onArrive(player, origin)
        }
        // Die Arena ist kein Bauplatz: kein Abbauen, kein Platzieren, keine Eimer (außer im Kreativmodus).
        PlayerBlockBreakEvents.BEFORE.register { world, player, _, _, _ ->
            !(world.registryKey == WORLD && !player.isCreative)
        }
        UseBlockCallback.EVENT.register { player, world, hand, _ ->
            val item = player.getStackInHand(hand).item
            if (world.registryKey == WORLD && !player.isCreative && (item is BlockItem || item is BucketItem)) ActionResult.FAIL else ActionResult.PASS
        }
        UseItemCallback.EVENT.register { player, world, hand ->
            val item = player.getStackInHand(hand).item
            if (world.registryKey == WORLD && !player.isCreative && item is BucketItem) ActionResult.FAIL else ActionResult.PASS
        }
        // Während eines Halte-Moments keine aktiven Fähigkeiten (HHA-Tasten schicken sonst trotz Eingabesperre Pakete).
        HhaEvents.ABILITY_CAST.register { player, _ -> !Hold.isHeld(player) }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            for (fight in fights.values.toList()) fight.finish(false)
            fights.clear()
        }
    }

    /** Ein Spieler steht (wieder) in der Arena-Welt: in seinen Kampf einsteigen oder nach Hause. */
    private fun onArrive(player: ServerPlayerEntity, from: ServerWorld?) {
        if (player.entityWorld.registryKey != WORLD) return
        val fight = fights[slotOf(player.x)]
        if (fight != null && !fight.ended) {
            // Kam er von außen dazu (nicht über das Siegel), braucht er einen eigenen Rückweg.
            if (from != null && from.registryKey != WORLD && !fight.isParticipant(player)) AriusState.rememberFallbackHome(player, from)
            fight.onJoin(player)
        } else if (!player.isCreative && !player.isSpectator) {
            AriusState.sendHome(player)
            player.sendMessage(Text.translatable("hha_arius.msg.sent_home").formatted(Formatting.GRAY), false)
        }
    }

    fun slotOf(x: Double): Int = floor((x + ArenaLayout.SLOT_SPACING / 2.0) / ArenaLayout.SLOT_SPACING).toInt()

    fun fightOf(player: ServerPlayerEntity): EirholmFight? =
        fights.values.firstOrNull { !it.ended && it.isParticipant(player) && player.entityWorld === it.world }

    fun isParticipant(player: ServerPlayerEntity): Boolean = fights.values.any { !it.ended && it.isParticipant(player) }

    fun all(): Collection<EirholmFight> = fights.values

    fun onFinished(fight: EirholmFight) {
        fights.remove(fight.slot)
    }

    fun requestStart(player: ServerPlayerEntity, seal: ItemStack): Boolean = start(player, 0, seal)

    fun requestTraining(player: ServerPlayerEntity, stage: Int): Boolean = start(player, stage.coerceIn(1, 4), ItemStack.EMPTY)

    /**
     * Gleicht Einsatz und Ansprüche ab und zahlt aus (nur außerhalb der Arena):
     * - Einsatzvermerk in der Spielerdatei ohne laufenden Kampf: gewonnen → verbraucht, sonst Siegel zurück. Vermerk
     *   und Inventar liegen in derselben Datei, sie können nicht auseinanderlaufen.
     * - Ansprüche im Weltzustand: ausgezahlt nur, wenn ihre ID noch nicht im [Ledger] quittiert ist. Aus dem Weltzustand
     *   entfernt wird ein Anspruch erst, wenn seine Quittung aus einer früheren Sitzung stammt, also nachweislich mit
     *   der Spielerdatei gespeichert wurde. So gibt es nach einem Absturz weder doppelte noch verlorene Beute.
     */
    fun deliverPending(player: ServerPlayerEntity) {
        if (player.entityWorld.registryKey == WORLD || player.isDisconnected) return
        val server = player.entityWorld.server ?: return
        val state = AriusState.get(server)
        val ledger = Ledgers.of(player)
        val seal = Registries.ITEM.get(HhaArius.id("eirholm_seal"))
        val data = state.player(player.uuid)
        var changed = false
        // 1) Ansprüche zustellen: Quittung, Beute ins Fach und ggf. Einsatz-Erledigung in EINEM Spielerdatei-Stand.
        for (claim in data.pendingRewards.toList()) {
            if (claim.id in ledger.paid) {
                // Quittung aus einer früheren Sitzung = nachweislich gespeichert: Anspruch darf weg.
                if (claim.id !in ledger.sessionPaid) {
                    data.pendingRewards.remove(claim)
                    changed = true
                }
                continue
            }
            ledger.paid.add(claim.id)
            ledger.sessionPaid.add(claim.id)
            if (claim.kind == "seal") {
                Ledgers.deposit(player, listOf(ItemStack(seal)))
            } else {
                // Erstbeute nur, wenn die Spielerdatei sie noch nicht kennt (ein zurückgerollter Weltstand ändert das nicht).
                val first = !ledger.firstLootTaken
                if (first) ledger.firstLootTaken = true
                Ledgers.deposit(player, GearRewards.loot(player, first, claim.flawless))
                Advancements.grant(player, "white_blade")
                if (claim.flawless) Advancements.grant(player, "no_storm")
            }
            if (claim.encounter.isNotEmpty() && claim.encounter == ledger.stake) ledger.stake = ""
        }
        // 2) Offener Einsatz ohne laufenden Kampf und ohne Anspruch dieser Begegnung: Sieg erledigt, sonst zurück.
        if (ledger.stake.isNotEmpty() && fights.values.none { it.encounterId == ledger.stake && !it.ended } &&
            data.pendingRewards.none { it.encounter == ledger.stake && it.id !in ledger.paid }
        ) {
            if (!state.isWon(ledger.stake)) Ledgers.deposit(player, listOf(ItemStack(seal)))
            ledger.stake = ""
        }
        // 3) Fach ins Inventar, so weit Platz ist.
        val left = Ledgers.flush(player)
        if (left > 0) player.sendMessage(Text.translatable("hha_arius.msg.inbox", left).formatted(Formatting.AQUA), false)
        if (changed) state.save()
    }

    /** Volles Fach regelmäßig nachfüllen, sobald Platz im Inventar ist. */
    private fun tickInboxes(server: MinecraftServer) {
        if (server.ticks % 40 != 0) return
        for (player in server.playerManager.playerList) {
            if (player.entityWorld.registryKey == WORLD) continue
            if (Ledgers.of(player).inbox.isNotEmpty()) Ledgers.flush(player)
        }
    }

    private fun start(player: ServerPlayerEntity, training: Int, seal: ItemStack): Boolean {
        val server = player.entityWorld.server ?: return false
        if (player.entityWorld.registryKey == WORLD) {
            player.sendMessage(Text.translatable("hha_arius.msg.already_inside").formatted(Formatting.RED), true)
            return false
        }
        if (isParticipant(player)) {
            player.sendMessage(Text.translatable("hha_arius.msg.busy").formatted(Formatting.RED), true)
            return false
        }
        val world: ServerWorld = server.getWorld(WORLD) ?: run {
            player.sendMessage(Text.translatable("hha_arius.msg.no_dimension").formatted(Formatting.RED), false)
            HhaArius.LOGGER.error("Arena-Dimension {} fehlt (Datapack nicht geladen?)", WORLD.value)
            return false
        }
        val slot = (0 until BossConfig.maxInstances()).firstOrNull { it !in fights } ?: run {
            player.sendMessage(Text.translatable("hha_arius.msg.full").formatted(Formatting.RED), true)
            return false
        }
        val invited = (player.entityWorld as ServerWorld).players.filter {
            it !== player && !it.isSpectator && it.isAlive && it.squaredDistanceTo(player) <= 12.0 * 12.0 && !isParticipant(it)
        }
        val origin = ArenaLayout.origin(slot)
        // Jeder Versuch prüft den ganzen Bauplan und ersetzt Abweichungen (schnell, wenn alles stimmt); nach einem
        // Absturz wird zusätzlich das ganze Volumen geleert.
        val dirty = slot in AriusState.get(server).data.dirtySlots
        val mode = if (dirty) 2 else 1
        // Das Siegel ist der Einsatz: Es wird jetzt entnommen und bei Niederlage/Abbruch zurückgegeben.
        val stake = if (training == 0 && !seal.isEmpty) seal.copyWithCount(1).also { seal.decrement(1) } else ItemStack.EMPTY
        val fight = EirholmFight(world, slot, player, invited, training, mode, stake)
        // Entnahme und Einsatzvermerk landen in derselben Spielerdatei.
        if (!stake.isEmpty) Ledgers.of(player).stake = fight.encounterId
        fights[slot] = fight
        fight.begin()
        if (training == 0) for (p in listOf(player) + invited) Advancements.grant(p, "seal")
        return true
    }

    /** Für Befehle: bringt einen laufenden Kampf direkt in einen Abschnitt. */
    fun stop(server: MinecraftServer) {
        for (fight in fights.values.toList()) {
            val everyone = fight.audience()
            fight.finish(false)
            for (p in everyone) AriusState.sendHome(p)
        }
    }
}

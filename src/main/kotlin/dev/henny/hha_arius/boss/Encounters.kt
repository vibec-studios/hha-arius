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

    /**
     * Ankünfte von außen, erst am Tickende behandelt. Seit 1.7.1: Wer im Weltwechsel-Ereignis sofort zurückgeschickt
     * wird, landet wieder in der Herkunftswelt, bevor Vanilla dort `teleportSpectatingPlayers` ausführt. Der Spieler
     * „beobachtet“ sich selbst, wird erneut teleportiert, wieder zurückgeschickt … – eine Endlosschleife, die den
     * Server einfriert (z. B. eine Enderperle, die nach dem Kampf im Hochland landet).
     */
    private val pendingArrivals = LinkedHashMap<UUID, Pair<ServerPlayerEntity, ServerWorld?>>()

    /**
     * Seit 1.10.0: Wer nach dem Sieg in Eirholm bleibt, um sich umzusehen (Spieler → Instanz-Slot). Solange jemand in
     * einer Instanz ist, wird ihr Slot nicht für einen neuen Kampf vergeben; zurück geht es durch die Rückkehrtore.
     */
    private val explorers = HashMap<UUID, Int>()

    fun init() {
        dev.henny.hha_arius.net.HuntNet.init()
        dev.henny.hha_arius.portal.Portals.init()
        dev.henny.hha_arius.boss.HuntGuard.init()
        dev.henny.hha_arius.arena.HighlandView.init()
        dev.henny.hha_arius.arena.RouteCheck.registerCommand()
        SplitRecovery.init()
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (pendingArrivals.isNotEmpty()) {
                val due = pendingArrivals.values.toList()
                pendingArrivals.clear()
                for ((player, origin) in due) if (!player.isRemoved && !player.isDisconnected) onArrive(player, origin)
            }
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
            if (origin.registryKey == WORLD) {
                if (destination.registryKey != WORLD) explorers.remove(player.uuid)
                fights.values.firstOrNull { it.world === origin && it.isParticipant(player) && !it.ended }?.onLeave(player)
                // Enderperlen, die noch im Hochland fliegen, würden ihn später ungefragt zurückziehen.
                discardPearlsIn(player, WORLD)
            }
            // Nie im Ereignis selbst teleportieren (siehe [pendingArrivals]).
            if (destination.registryKey == WORLD && player.uuid !in arriving) pendingArrivals[player.uuid] = player to origin
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
            val stack = player.getStackInHand(hand)
            val item = stack.item
            if (world.registryKey == WORLD && !player.isCreative && item is BucketItem) return@register ActionResult.FAIL
            // Seit 1.7.0: während der Jagd den Berg hinauf keine Enderperlen und Chorusfrüchte.
            if (world.registryKey == WORLD && player is net.minecraft.server.network.ServerPlayerEntity &&
                (stack.isOf(net.minecraft.item.Items.ENDER_PEARL) || stack.isOf(net.minecraft.item.Items.CHORUS_FRUIT)) &&
                fightOf(player)?.chasing == true
            ) {
                player.sendMessage(Text.translatable("hha_arius.msg.chase_no_pearl").formatted(Formatting.RED), true)
                return@register ActionResult.FAIL
            }
            ActionResult.PASS
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
        val slot = slotOf(player.x)
        val fight = fights[slot]
        if (fight != null && !fight.ended && !fight.isParticipant(player) && explorers[player.uuid] == slot) {
            // Ein neuer Kampf läuft in der Instanz, die er erkundet hat: nach Hause.
            explorers.remove(player.uuid)
            dev.henny.hha_arius.portal.Portals.sendBack(player)
        } else if (fight != null && !fight.ended) {
            // Kam er von außen dazu (nicht über das Siegel), braucht er einen eigenen Rückweg.
            if (from != null && from.registryKey != WORLD && !fight.isParticipant(player)) AriusState.rememberFallbackHome(player, from)
            fight.onJoin(player)
        } else if (explorers[player.uuid] == slot) {
            // Nach dem Sieg geblieben (Wiedereinloggen): bleibt, das Rückkehrtor wartet.
            player.sendMessage(Text.translatable("hha_arius.portal.explore").formatted(Formatting.AQUA), false)
        } else if (!player.isCreative && !player.isSpectator) {
            AriusState.sendHome(player)
            player.sendMessage(Text.translatable("hha_arius.msg.sent_home").formatted(Formatting.GRAY), false)
        }
    }

    /** Entfernt die Enderperlen des Spielers, die in [world] unterwegs sind. */
    fun discardPearlsIn(player: ServerPlayerEntity, world: RegistryKey<World>) {
        for (pearl in player.enderPearls.toList()) if (pearl.entityWorld.registryKey == world) pearl.discard()
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
        // Seit 1.10.0: kein Slot, in dem noch jemand ist (Erkunden nach dem Sieg).
        val occupied = world.players.map { slotOf(it.x) }.toSet() + explorers.values
        val slot = (0 until BossConfig.maxInstances()).firstOrNull { it !in fights && it !in occupied } ?: run {
            player.sendMessage(Text.translatable("hha_arius.msg.full").formatted(Formatting.RED), true)
            return false
        }
        val invited = (player.entityWorld as ServerWorld).players.filter {
            it !== player && !it.isSpectator && it.isAlive && it.squaredDistanceTo(player) <= 12.0 * 12.0 && !isParticipant(it)
        }
        val origin = ArenaLayout.origin(slot)
        SplitRecovery.ensureClean(server, slot)
        // Jeder Versuch prüft den ganzen Bauplan und ersetzt Abweichungen; nach einem Absturz oder neuer Tempel-Fassung
        // wird zusätzlich das ganze Kernvolumen geleert und sauber neu gebaut.
        val st = AriusState.get(server)
        @Suppress("SENSELESS_COMPARISON")
        if (st.data.coreBuilt == null) st.data.coreBuilt = HashMap()
        val dirty = slot in st.data.dirtySlots || st.data.coreBuilt[slot.toString()] != dev.henny.hha_arius.arena.ArenaPlan.VERSION
        val mode = if (dirty) 2 else 1
        // Das Siegel ist der Einsatz: Es wird jetzt entnommen und bei Niederlage/Abbruch zurückgegeben.
        val stake = if (training == 0 && !seal.isEmpty) seal.copyWithCount(1).also { seal.decrement(1) } else ItemStack.EMPTY
        dev.henny.hha_arius.portal.Portals.extinguishReturn(world, origin)
        val fight = EirholmFight(world, slot, player, invited, training, mode, stake)
        // Entnahme und Einsatzvermerk landen in derselben Spielerdatei.
        if (!stake.isEmpty) Ledgers.of(player).stake = fight.encounterId
        fights[slot] = fight
        fight.begin()
        if (training == 0) for (p in listOf(player) + invited) Advancements.grant(p, "seal")
        return true
    }

    /**
     * Nach dem Sieg (seit 1.10.0): Der Spieler bleibt in Eirholm. Rückkehrtore leuchten, die Beute wartet bis zur
     * Rückkehr ([dev.henny.hha_arius.portal.Portals.sendBack]).
     */
    fun stayAfterVictory(player: ServerPlayerEntity, slot: Int) {
        if (player.entityWorld.registryKey != WORLD || player.isDisconnected) return
        explorers[player.uuid] = slot
        dev.henny.hha_arius.net.AriusNet.send(player, dev.henny.hha_arius.net.AtmosS2C(dev.henny.hha_arius.net.AtmosMode.REVEAL, 120))
        player.sendMessage(Text.translatable("hha_arius.portal.stay").formatted(Formatting.AQUA), false)
    }

    fun leaveExplorer(player: ServerPlayerEntity) {
        explorers.remove(player.uuid)
    }

    fun isExplorer(player: ServerPlayerEntity): Boolean = player.uuid in explorers

    /** Für Befehle: bringt einen laufenden Kampf direkt in einen Abschnitt. */
    fun stop(server: MinecraftServer) {
        for (fight in fights.values.toList()) {
            val everyone = fight.audience()
            fight.finish(false)
            for (p in everyone) AriusState.sendHome(p)
        }
    }
}

package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaLayout
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.item.ItemStack
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

    val WORLD: RegistryKey<World> = RegistryKey.of(RegistryKeys.WORLD, HhaArius.id("eirholm"))

    private val fights = HashMap<Int, EirholmFight>()

    /** Spieler, die gerade von uns in die Arena gebracht werden (kein Nachzügler). */
    val arriving = HashSet<UUID>()

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            Hold.tick(server)
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
            server.execute { onArrive(player) }
        }
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register { player, origin, destination ->
            if (origin.registryKey == WORLD) fights[slotOf(player.lastX)]?.takeIf { it.isParticipant(player) }?.onLeave(player)
            if (destination.registryKey == WORLD && player.uuid !in arriving) onArrive(player)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            for (fight in fights.values.toList()) fight.finish(false)
            fights.clear()
        }
    }

    /** Ein Spieler steht (wieder) in der Arena-Welt: in seinen Kampf einsteigen oder nach Hause. */
    private fun onArrive(player: ServerPlayerEntity) {
        if (player.entityWorld.registryKey != WORLD) return
        val fight = fights[slotOf(player.x)]
        if (fight != null && !fight.ended) {
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

    fun requestStart(player: ServerPlayerEntity, seal: ItemStack): Boolean = start(player, 0)

    fun requestTraining(player: ServerPlayerEntity, stage: Int): Boolean = start(player, stage.coerceIn(1, 4))

    private fun start(player: ServerPlayerEntity, training: Int): Boolean {
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
        val dirty = slot in AriusState.get(server).data.dirtySlots
        val built = !world.getBlockState(origin.down()).isAir
        val mode = when {
            dirty -> 2
            !built -> 1
            else -> 0
        }
        val fight = EirholmFight(world, slot, player, invited, training, mode)
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

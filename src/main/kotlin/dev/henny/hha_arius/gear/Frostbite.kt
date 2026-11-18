package dev.henny.hha_arius.gear

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributeModifier
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.effect.StatusEffect
import net.minecraft.entity.effect.StatusEffectCategory
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import java.util.UUID

/** Frostaufbau für die eigene Anzeige: [value] 0..100, [frostbite] = verbleibende Ticks der Erfrierung. */
data class FrostS2C(val value: Int, val frostbite: Int) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<FrostS2C>(HhaArius.id("frost"))
        val CODEC: PacketCodec<RegistryByteBuf, FrostS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.value); buf.writeVarInt(v.frostbite) },
            { buf -> FrostS2C(buf.readVarInt(), buf.readVarInt()) },
        )
    }
}

class FrostbiteEffect : StatusEffect(StatusEffectCategory.HARMFUL, 0x9FD8FF)

/**
 * Erfrierung wie in Elden Ring (seit 1.6.5, Crix): Frostangriffe bauen einen Frostbalken auf (Arius und Solvane bei
 * Spielern, die Frost-Techniken der Ausrüstung bei Gegnern). Ist er voll, **bricht der Frost aus**: sofort 10 % der
 * maximalen Gesundheit + 3 Schaden, danach 30 s **Erfrierung** – erlittener Schaden +20 %, Angriffstempo −15 % (wie die
 * gesenkte Ausdauer-Erholung in Elden Ring); der Balken ist so lange gesperrt. Ohne neuen Frost sinkt er nach 3 s um 6
 * je Sekunde. Spieler sehen ihren Balken über der Hotbar ([dev.henny.hha_arius.gear.client.FrostHud]).
 */
object Frostbite {

    lateinit var EFFECT: RegistryEntry<StatusEffect>
        private set

    private class State(var value: Float = 0f, var last: Long = 0L, var sent: Int = -1, var sentBite: Int = -1)

    private val states = HashMap<UUID, State>()
    private var guard = false
    private val SLOW_ID = HhaArius.id("frostbite_attack_speed")

    fun register() {
        EFFECT = Registry.registerReference(
            Registries.STATUS_EFFECT, HhaArius.id("frostbite"),
            FrostbiteEffect().addAttributeModifier(EntityAttributes.ATTACK_SPEED, SLOW_ID, -0.15, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
        )
    }

    fun init() {
        PayloadTypeRegistry.playS2C().register(FrostS2C.ID, FrostS2C.CODEC)
        // Erfrierung: erlittener Schaden +20 %.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            if (guard || !entity.hasStatusEffect(EFFECT)) return@register true
            val world = entity.entityWorld as? ServerWorld ?: return@register true
            guard = true
            try {
                entity.damage(world, source, amount * 1.2f)
            } finally {
                guard = false
            }
            false
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            val now = server.ticks.toLong()
            val it = states.entries.iterator()
            while (it.hasNext()) {
                val (id, s) = it.next()
                val player = server.playerManager.getPlayer(id)
                if (now - s.last > 60 && s.value > 0f) s.value = (s.value - 0.3f).coerceAtLeast(0f)
                if (player != null) sync(player, s)
                if (s.value <= 0f && player == null) it.remove()
            }
        }
    }

    /** Baut Frost auf. Bei 100: Ausbruch. Während der Erfrierung kein Aufbau. */
    fun add(target: LivingEntity, amount: Float) {
        if (amount <= 0f || !target.isAlive || target.hasStatusEffect(EFFECT)) return
        val world = target.entityWorld as? ServerWorld ?: return
        val s = states.getOrPut(target.uuid) { State() }
        s.value = (s.value + amount).coerceAtMost(100f)
        s.last = world.server?.ticks?.toLong() ?: 0L
        if (s.value >= 100f) {
            s.value = 0f
            target.addStatusEffect(StatusEffectInstance(EFFECT, 600, 0, false, true, true))
            target.timeUntilRegen = 0
            guard = true
            try {
                target.damage(world, world.damageSources.freeze(), target.maxHealth * 0.1f + 3f)
            } finally {
                guard = false
            }
            val p = target.entityPos.add(0.0, target.height * 0.6, 0.0)
            world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.PACKED_ICE.defaultState), p.x, p.y, p.z, 30, 0.4, 0.5, 0.4, 0.2)
            world.spawnParticles(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, 40, 0.6, 0.6, 0.6, 0.15)
            world.playSound(null, target.blockPos, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.2f, 0.6f)
            world.playSound(null, target.blockPos, SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1.2f, 0.8f)
        }
        if (target is ServerPlayerEntity) sync(target, s)
    }

    private fun sync(player: ServerPlayerEntity, s: State) {
        val v = s.value.toInt()
        val bite = player.getStatusEffect(EFFECT)?.duration ?: 0
        if (v == s.sent && (bite == 0) == (s.sentBite == 0) && (bite == 0 || bite % 20 != 0)) return
        s.sent = v
        s.sentBite = bite
        if (ServerPlayNetworking.canSend(player, FrostS2C.ID)) ServerPlayNetworking.send(player, FrostS2C(v, bite))
    }
}

package dev.henny.hha_arius.gear

import dev.henny.hha.logic.Targeting
import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.math.Vec3d

/**
 * Sounds der Ausrüstung (`hha_arius:<id>` aus docs/SPEC_TEAM.md). Nachschlagen über die Registry statt über Felder von
 * `AriusSounds`, damit die Ausrüstung auch ohne fertigen Audio-Teil läuft: Fehlt ein Event, klingt der Vanilla-Ersatz.
 */
object GearSounds {

    fun play(world: ServerWorld, pos: Vec3d, id: String, fallback: SoundEvent?, volume: Float = 1f, pitch: Float = 1f) {
        val event = Registries.SOUND_EVENT.get(HhaArius.id(id)) ?: fallback ?: return
        world.playSound(null, pos.x, pos.y, pos.z, event, SoundCategory.PLAYERS, volume, pitch)
    }
}

/** Kleine Server-Zeitleiste für verzögerte Abläufe (Aufladen, Kern, Schneesturm). Läuft am Ende jedes Server-Ticks. */
object GearTasks {

    private class Task(val due: Long, val action: () -> Unit)

    private val tasks = ArrayList<Task>()
    private val tickers = ArrayList<() -> Unit>()

    /** Server-Ticks seit Start (unabhängig von Welt-Zeit und /time set). */
    var now = 0L
        private set

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { _ ->
            now++
            for (ticker in tickers) {
                try {
                    ticker()
                } catch (e: Exception) {
                    HhaArius.LOGGER.error("Gear-Ticker fehlgeschlagen", e)
                }
            }
            if (tasks.isEmpty()) return@register
            val due = tasks.filter { it.due <= now }
            if (due.isEmpty()) return@register
            tasks.removeAll(due.toSet())
            for (task in due) {
                try {
                    task.action()
                } catch (e: Exception) {
                    HhaArius.LOGGER.error("Gear-Ablauf fehlgeschlagen", e)
                }
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { _ -> tasks.clear() }
    }

    fun later(ticks: Int, action: () -> Unit) {
        tasks.add(Task(now + ticks.coerceAtLeast(1), action))
    }

    /** Jeder Server-Tick (nach allen Spieler-Ticks). */
    fun everyTick(action: () -> Unit) {
        tickers.add(action)
    }
}

/** Laufender Server (für Abläufe ohne Welt-Kontext, z. B. das Zurücksetzen des Frostschritt-Eises). */
object GearServer {
    var current: net.minecraft.server.MinecraftServer? = null
        private set

    fun init() {
        ServerLifecycleEvents.SERVER_STARTED.register { current = it }
        ServerLifecycleEvents.SERVER_STOPPED.register { current = null }
    }
}

/** Gemeinsame Kampf-Helfer der Fähigkeiten. */
object GearCombat {

    /** Ist der Spieler noch da, lebendig und in derselben Welt wie beim Auslösen? */
    fun stillThere(player: ServerPlayerEntity, world: ServerWorld): Boolean =
        !player.isRemoved && player.isAlive && !player.isSpectator && player.entityWorld === world

    /** Feindliches Geschoss: nicht vom Spieler selbst und nicht von einem Verbündeten (HHA-Trust/Brutes). */
    fun isHostileProjectile(player: ServerPlayerEntity, projectile: ProjectileEntity): Boolean {
        val owner = projectile.owner ?: return true
        if (owner === player) return false
        if (owner is LivingEntity) return Targeting.shouldHarm(player, owner)
        return true
    }

    /** Darf eine Fähigkeit dieses Ziel treffen (HHA-Targeting, keine Zuschauer)? */
    fun canHit(player: ServerPlayerEntity, target: Entity): Boolean {
        if (target !is LivingEntity || target === player || !target.isAlive) return false
        if (target is ServerPlayerEntity && (target.isSpectator || target.isCreative)) return false
        return Targeting.shouldHarm(player, target)
    }

    /** Waagerechter Stoß weg von [from]; berücksichtigt Rückstoßresistenz. Spieler über HHAs `Fx.launchPlayer`. */
    fun push(target: LivingEntity, from: Vec3d, horizontal: Double, up: Double) {
        var dx = target.x - from.x
        var dz = target.z - from.z
        val len = Math.sqrt(dx * dx + dz * dz)
        if (len < 1.0e-3) {
            dx = 1.0; dz = 0.0
        } else {
            dx /= len; dz /= len
        }
        val resist = (1.0 - target.getAttributeValue(EntityAttributes.KNOCKBACK_RESISTANCE)).coerceIn(0.0, 1.0)
        if (resist <= 0.0) return
        val velocity = Vec3d(dx * horizontal * resist, up * resist, dz * horizontal * resist)
        if (target is ServerPlayerEntity) {
            dev.henny.hha.logic.Fx.launchPlayer(target, target.velocity.multiply(0.2).add(velocity))
        } else {
            target.velocity = target.velocity.multiply(0.2).add(velocity)
            target.velocityDirty = true
        }
    }

    fun actionbar(player: ServerPlayerEntity, key: String, vararg args: Any) {
        player.sendMessage(Text.translatable(key, *args), true)
    }
}

/**
 * HHA-Effekte (`dev.henny.hha.logic.Vfx`) für Spielerfähigkeiten. Gleiche Effektarten und Stile, aber nie mit der
 * Gefahrenpalette: `Vfx` färbt während eines Telegraph-Kampfs alles als Gegner-Effekt ein, und im Arius-Kampf sollen
 * Weiße Linie, Kern und Konter klar als eigene Aktionen lesbar bleiben. Respektiert `Vfx.styleOverride` und Cutscenes.
 */
object GearVfx {

    private const val RANGE = 160.0

    private fun emit(
        world: ServerWorld, kind: dev.henny.hha.logic.Vfx.Kind, style: dev.henny.hha.logic.Vfx.Style, pos: Vec3d,
        dir: Vec3d = Vec3d.ZERO, a: Float = 0f, b: Float = 0f, life: Int = 20, entity: Entity? = null,
    ) {
        if (dev.henny.hha.logic.boss.Cine.muted) return
        val resolved = dev.henny.hha.logic.Vfx.styleOverride ?: style
        val payload = dev.henny.hha.net.VfxPayload(
            kind.ordinal, resolved.ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life, entity?.id ?: -1, -1, 0,
        )
        for (player in world.players) {
            if (player.squaredDistanceTo(pos) <= RANGE * RANGE) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload)
            }
        }
    }

    fun beam(world: ServerWorld, style: dev.henny.hha.logic.Vfx.Style, start: Vec3d, end: Vec3d, width: Float, life: Int) =
        emit(world, dev.henny.hha.logic.Vfx.Kind.BEAM, style, start, end, width, life = life)

    fun crescent(world: ServerWorld, style: dev.henny.hha.logic.Vfx.Style, origin: Vec3d, dir: Vec3d, range: Float, width: Float, life: Int) =
        emit(world, dev.henny.hha.logic.Vfx.Kind.CRESCENT, style, origin, dir, range, width, life)

    fun shockwave(world: ServerWorld, style: dev.henny.hha.logic.Vfx.Style, center: Vec3d, radius: Float, life: Int, height: Float) =
        emit(world, dev.henny.hha.logic.Vfx.Kind.SHOCKWAVE, style, center, a = radius, b = height, life = life)

    fun nova(world: ServerWorld, style: dev.henny.hha.logic.Vfx.Style, center: Vec3d, radius: Float, life: Int) =
        emit(world, dev.henny.hha.logic.Vfx.Kind.NOVA, style, center, a = radius, life = life)

    fun flare(world: ServerWorld, style: dev.henny.hha.logic.Vfx.Style, center: Vec3d, size: Float, life: Int) =
        emit(world, dev.henny.hha.logic.Vfx.Kind.FLARE, style, center, a = size, life = life)

    fun wings(world: ServerWorld, style: dev.henny.hha.logic.Vfx.Style, target: Entity, life: Int, spread: Float) =
        emit(world, dev.henny.hha.logic.Vfx.Kind.WINGS, style, target.entityPos, a = spread, life = life, entity = target)

    fun trail(world: ServerWorld, style: dev.henny.hha.logic.Vfx.Style, target: Entity, width: Float, life: Int) =
        emit(world, dev.henny.hha.logic.Vfx.Kind.TRAIL, style, target.entityPos, a = width, life = life, entity = target)
}

package dev.henny.hha_arius.gear

import dev.henny.hha_arius.mixin.gear.MeleeAttackGoalAccessor
import dev.henny.hha_arius.mixin.gear.MobGoalsAccessor
import dev.henny.hha_arius.net.ParryCueS2C
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.ai.goal.MeleeAttackGoal
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import java.lang.reflect.Method
import java.util.UUID

/**
 * Paradeanzeige der Solvane (seit 1.10.0), Server-Teil: Wer Solvane in der Haupthand hält, bekommt für Angreifer in der
 * Nähe den Zeitpunkt des nächsten Schlags ([ParryCueS2C]) – aus echtem Spielwissen, nicht geraten:
 * * **Nahkampf-Mobs** (Zombie, Skelett im Nahkampf, Spinne, Vindicator …, auch modded): Abklingzeit ihres
 *   [MeleeAttackGoal] (Accessor-Mixin), sobald sie in Reichweite sind.
 * * **Bosse mit Aktionszustand** (z. B. HHA_Blood: Minotaurus, Zerberus – `getAction()`/`getActionAge()`, weich über
 *   Reflexion, ohne harte Abhängigkeit): Der Server lernt je Bossart und Aktion, in welchem Aktions-Tick der Treffer kam,
 *   und sagt ihn ab dem zweiten Mal genau an.
 * * HHA-Bossansagen (HHA-Bosse, Arius, Solvane) kennt der Client schon selbst (Telegraph-Effekte, siehe ParryHud).
 *
 * Billig: nur für Halter des Schwerts, nur alle 2 Ticks, nur Angreifer im Umkreis von [RANGE] Blöcken.
 */
object ParryCues {

    private const val RANGE = 14.0

    /** Gesendeter Stand je Spieler und Angreifer: erwarteter Treffer-Tick (Serverzeit). */
    private val sent = HashMap<UUID, HashMap<Int, Long>>()

    /** Gelernt: (Bossklasse, Aktion) → Aktions-Tick des ersten Treffers. */
    private val learned = HashMap<Pair<Class<*>, Int>, Int>()

    private class ActionAccess(val action: Method, val age: Method)

    private val access = HashMap<Class<*>, ActionAccess?>()

    fun init() {
        GearTasks.everyTick { if (GearTasks.now % 2L == 0L) tick() }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> sent.remove(handler.player.uuid) }
        // Lernen: Ein Boss mit Aktionszustand trifft einen Spieler – merken, in welchem Aktions-Tick.
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, _, _ ->
            if (entity !is ServerPlayerEntity) return@register
            val attacker = source.attacker as? LivingEntity ?: return@register
            if (source.source !== attacker) return@register
            val acc = actionAccess(attacker.javaClass) ?: return@register
            val action = runCatching { acc.action.invoke(attacker) as Int }.getOrNull() ?: return@register
            val age = runCatching { acc.age.invoke(attacker) as Int }.getOrNull() ?: return@register
            if (age < 2 || age > 200) return@register
            val key = attacker.javaClass to action
            val old = learned[key]
            if (old == null || age < old) learned[key] = age
        }
    }

    private fun actionAccess(c: Class<*>): ActionAccess? = access.getOrPut(c) {
        runCatching {
            val a = c.getMethod("getAction")
            val g = c.getMethod("getActionAge")
            if (a.returnType == Int::class.javaPrimitiveType && g.returnType == Int::class.javaPrimitiveType) ActionAccess(a, g) else null
        }.getOrNull()
    }

    private fun tick() {
        val server = GearServer.current ?: return
        for (p in server.playerManager.playerList) {
            if (!p.mainHandStack.isOf(GearItems.SOLVANE) || p.isSpectator || !GearConfig.on("solvane_counter")) {
                sent.remove(p.uuid)?.let { m -> for (id in m.keys) send(p, ParryCueS2C(id, -1, 0, 0)) }
                continue
            }
            if (!ServerPlayNetworking.canSend(p, ParryCueS2C.ID)) continue
            val mine = sent.getOrPut(p.uuid) { HashMap() }
            val seen = HashSet<Int>()
            val world = p.entityWorld
            val now = GearTasks.now
            val lead = GearConfig.ticks("solvane_counter_ticks").coerceIn(2, 40)
            for (e in world.getOtherEntities(p, p.boundingBox.expand(RANGE)) { it is LivingEntity && it.isAlive }) {
                val mob = e as LivingEntity
                val ticks = predict(mob, p) ?: continue
                seen.add(mob.id)
                val hitAt = now + ticks
                val old = mine[mob.id]
                if (old != null && kotlin.math.abs(old - hitAt) <= 1) continue
                mine[mob.id] = hitAt
                send(p, ParryCueS2C(mob.id, ticks, colorOf(mob), lead))
            }
            val gone = mine.keys.filter { it !in seen }
            for (id in gone) {
                val at = mine.remove(id) ?: continue
                // Nur abbrechen, wenn der Schlag noch nicht fällig war (sonst läuft die Anzeige von selbst aus).
                if (at > now + 1) send(p, ParryCueS2C(id, -1, 0, 0))
            }
        }
    }

    /** Ticks bis zum nächsten Schlag von [mob] auf [p], oder null (kein absehbarer Schlag). */
    private fun predict(mob: LivingEntity, p: ServerPlayerEntity): Int? {
        if (mob is MobEntity) {
            if (mob.target !== p) return null
            val goals = (mob as MobGoalsAccessor).`hhaArius$goals`()
            for (g in goals.goals) {
                if (!g.isRunning) continue
                val goal = g.goal as? MeleeAttackGoal ?: continue
                val reach = mob.width * 2.0 + p.width + 1.5
                if (mob.squaredDistanceTo(p) > reach * reach) return null
                return (goal as MeleeAttackGoalAccessor).`hhaArius$cooldown`().coerceAtLeast(1)
            }
        }
        val acc = actionAccess(mob.javaClass) ?: return null
        if (mob is MobEntity && mob.target != null && mob.target !== p) return null
        if (mob.squaredDistanceTo(p) > 10.0 * 10.0) return null
        val action = runCatching { acc.action.invoke(mob) as Int }.getOrNull() ?: return null
        val age = runCatching { acc.age.invoke(mob) as Int }.getOrNull() ?: return null
        val hit = learned[mob.javaClass to action] ?: return null
        val left = hit - age
        return if (left in 1..80) left else null
    }

    /** Farbe der Markierung nach Angreifer: Bosse kräftig, sonst aus der Art abgeleitet (gleiche Art = gleiche Farbe). */
    private fun colorOf(mob: LivingEntity): Int {
        val id = Registries.ENTITY_TYPE.getId(mob.type)
        if (id.namespace == "hha_blood") return 0xFFE0342C.toInt()
        if (id.namespace == "hha_void") return 0xFF9B59FF.toInt()
        val h = (id.toString().hashCode() and 0x7FFFFFFF) % 360
        return 0xFF000000.toInt() or hsv(h / 360f, if (mob is HostileEntity) 0.75f else 0.5f, 1f)
    }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val k = { n: Float -> (n + h * 6f) % 6f }
        val f = { n: Float -> v - v * s * maxOf(0f, minOf(k(n), 4f - k(n), 1f)) }
        val r = (f(5f) * 255f).toInt().coerceIn(0, 255)
        val g = (f(3f) * 255f).toInt().coerceIn(0, 255)
        val b = (f(1f) * 255f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun send(p: ServerPlayerEntity, payload: ParryCueS2C) {
        if (!p.isDisconnected && ServerPlayNetworking.canSend(p, ParryCueS2C.ID)) ServerPlayNetworking.send(p, payload)
    }
}

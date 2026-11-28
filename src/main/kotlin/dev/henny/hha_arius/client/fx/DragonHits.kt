package dev.henny.hha_arius.client.fx

import dev.henny.hha_arius.boss.DragonAim
import dev.henny.hha_arius.boss.NivarisShape
import dev.henny.hha_arius.client.model.nivaris.NivarisRenderState
import dev.henny.hha_arius.gear.art.GauntletsItem
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Hand
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d

/**
 * Trefferprüfung am gezeichneten Körper der Drachin (siehe [DragonAim]). Der Renderer legt je Frame die Weltlage der
 * Glieder ab ([update]); beim Angriff prüft [onAttack] den Blickstrahl gegen Kugeln auf jedem Gliedpunkt und jeder
 * Gliedmitte (Radius = halber Querschnitt). Aufgelöste Glieder (Schleier) zählen nicht.
 */
object DragonHits {

    private class Body(val points: Array<Vec3d>, val radii: DoubleArray, var frame: Long)

    private val bodies = HashMap<Int, Body>()
    private var frameCounter = 0L
    private var level: net.minecraft.client.world.ClientWorld? = null

    /** Veraltete Körperdaten beim Trennen oder Weltwechsel verwerfen. */
    fun clear() {
        bodies.clear()
        frameCounter = 0L
        level = null
    }

    /** Prüft auf Weltwechsel und leert den Zwischenspeicher, sobald die Client-Welt wechselt. */
    fun syncWorld(world: net.minecraft.client.world.ClientWorld?) {
        if (world !== level) {
            bodies.clear()
            frameCounter = 0L
            level = world
        }
    }

    /** Seit 1.8.0 für die Aura: Weltpunkte des Körpers (Kopf … Schwanz) bzw. halbe Gliedbreiten, falls bekannt. */
    fun points(entityId: Int): Array<Vec3d>? = bodies[entityId]?.points

    fun radii(entityId: Int): DoubleArray? = bodies[entityId]?.radii

    /** Vom Renderer nach der Körperberechnung (Punkte relativ zur Render-Position). */
    fun update(entityId: Int, s: NivarisRenderState) {
        if (!s.bodyReady) return
        syncWorld(MinecraftClient.getInstance().world)
        val n = NivarisShape.SEGMENTS + 1
        val body = bodies.getOrPut(entityId) { Body(Array(n) { Vec3d.ZERO }, DoubleArray(NivarisShape.SEGMENTS), 0) }
        for (i in 0 until n) body.points[i] = Vec3d(s.x + s.px[i], s.y + s.py[i], s.z + s.pz[i])
        for (i in 0 until NivarisShape.SEGMENTS) {
            // Halbe Breite des Glieds (±7 Einheiten × SCALE / 16) mal Verjüngung, aufgelöste Glieder fallen weg.
            val visible = s.shrink.getOrElse(i + 1) { 1f }
            body.radii[i] = if (visible < 0.35f) 0.0 else s.girth[i] * 7.0 * NivarisShape.SCALE / 16.0 + 0.4
        }
        body.frame = ++frameCounter
        if (bodies.size > 8) bodies.entries.removeIf { frameCounter - it.value.frame > 200 }
    }

    /**
     * Linksklick. Trifft der Blick ein Glied (und nichts liegt näher): mit Handschuhen nur melden (die Schlagfolge des
     * Servers nimmt die Drachin als Ziel, Rückgabe false), sonst Angriff über den Server und true (kein Vanilla-Schlag).
     */
    @JvmStatic
    fun onAttack(): Boolean {
        val client = MinecraftClient.getInstance()
        val world = client.world
        syncWorld(world)
        val player = client.player ?: return false
        if (world == null || client.currentScreen != null || player.isSpectator || bodies.isEmpty()) return false
        val target = client.crosshairTarget
        // Zielt der Spieler schon auf die Drachin selbst (Rumpf- oder Kopfbox), regelt das der normale Weg.
        if (target?.type == HitResult.Type.ENTITY) {
            val e = (target as net.minecraft.util.hit.EntityHitResult).entity
            if (e is dev.henny.hha_arius.boss.NivarisEntity || e is dev.henny.hha_arius.boss.NivarisHeadEntity) return false
        }
        val eye = player.getCameraPosVec(1f)
        val look = player.getRotationVec(1f)
        // Seit 1.5.1 großzügiger: der Körper ist riesig, eine Kugel mehr Reichweite und vier Prüfpunkte je Glied.
        val reach = maxOf(player.entityInteractionRange, 3.0) + 1.75
        val limit = if (target != null && target.type != HitResult.Type.MISS) eye.distanceTo(target.pos) else reach
        var best = Double.MAX_VALUE
        var bestId = -1
        var bestSeg = -1
        val it = bodies.entries.iterator()
        while (it.hasNext()) {
            val (id, body) = it.next()
            // Verabschiedete oder entladene Drachin nicht als Geister-Trefferbox behalten.
            val entity = world.getEntityById(id)
            if (entity !is dev.henny.hha_arius.boss.NivarisEntity || entity.isRemoved) {
                it.remove()
                continue
            }
            if (frameCounter - body.frame > 4) continue
            for (i in 0 until NivarisShape.SEGMENTS) {
                val r = body.radii[i]
                if (r <= 0.0) continue
                val a = body.points[i]
                val b = body.points[i + 1]
                for (c in arrayOf(a, a.lerp(b, 0.25), a.lerp(b, 0.5), a.lerp(b, 0.75))) {
                    val t = hit(eye, look, c, r) ?: continue
                    if (t <= reach && t < limit && t < best) {
                        best = t
                        bestId = id
                        bestSeg = i
                    }
                }
            }
        }
        if (bestId < 0 || !ClientPlayNetworking.canSend(DragonAim.DragonAimC2S.ID)) return false
        val gauntlets = GauntletsItem.isGauntlets(player.mainHandStack)
        ClientPlayNetworking.send(DragonAim.DragonAimC2S(bestId, bestSeg, !gauntlets))
        if (gauntlets) return false
        player.swingHand(Hand.MAIN_HAND)
        player.resetTicksSinceLastAttack()
        return true
    }

    /** Strahl gegen Kugel: Abstand bis zum Eintritt, sonst null. [dir] ist normiert. */
    private fun hit(origin: Vec3d, dir: Vec3d, center: Vec3d, r: Double): Double? {
        val oc = center.subtract(origin)
        val t = oc.dotProduct(dir)
        if (t < 0.0) return null
        val d2 = oc.lengthSquared() - t * t
        if (d2 > r * r) return null
        return (t - kotlin.math.sqrt(r * r - d2)).coerceAtLeast(0.0)
    }
}

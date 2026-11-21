package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.boss.AriusEntity
import dev.henny.hha_arius.boss.AriusForm
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d
import kotlin.math.sin

/** Konterhaltung und offenes Trefferfenster direkt an Klinge und Boden. */
object CounterStance {

    private const val RED = 0xFFFF2A38.toInt()
    private const val HOT = 0xFFFFD0C8.toInt()

    /** Deckendes Rot, das auch auf weißem Schnee stehen bleibt. */
    private const val SOLID = 0xFFD8101E.toInt()
    private const val GOLD = 0xFFFFD45A.toInt()

    /** Reichweite der Parade auf dem Server (siehe `EirholmFight.hitArius`). */
    private const val REACH = 5.0


    private const val RELEASE_TICKS = 16

    private class Live(val id: Int) {
        var age = 0
    }

    private class Release(val pos: Vec3d, val open: Boolean) {
        var age = 0
    }

    private val live = HashMap<Int, Live>()
    private val released = ArrayList<Release>()

    /** Für Tests und die Anzeige: Steht gerade ein Arius in Konterhaltung? */
    val active: Boolean get() = live.isNotEmpty()

    fun clear() {
        live.clear()
        released.clear()
    }

    fun tick(client: MinecraftClient) {
        val world = client.world
        if (world == null || world.registryKey != ArenaDimension.WORLD) {
            clear()
            return
        }
        val seen = HashSet<Int>()
        for (e in world.entities) {
            if (e !is AriusEntity || e.form == AriusForm.ECHO || !e.counterStance) continue
            seen.add(e.id)
            live.getOrPut(e.id) { Live(e.id) }.age++
        }
        val gone = live.keys.filter { it !in seen }
        for (id in gone) {
            live.remove(id)
            val e = world.getEntityById(id) as? AriusEntity ?: continue
            released.add(Release(e.entityPos, e.open))
        }
        for (r in released) r.age++
        released.removeIf { it.age > RELEASE_TICKS }
    }

    fun renderWorld(context: WorldRenderContext) {
        if (live.isEmpty() && released.isEmpty()) return
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val consumers = context.consumers() ?: return
        val td = client.renderTickCounter.getTickProgress(true)
        val d = VfxDraw(consumers, client.gameRenderer.camera, td)
        runCatching {
            for (l in live.values) {
                val e = world.getEntityById(l.id) as? AriusEntity ?: continue
                drawStance(d, e, l.age + td, td)
            }
            for (r in released) drawRelease(d, r, r.age + td)
        }
    }

    private fun drawStance(d: VfxDraw, e: AriusEntity, t: Float, td: Float) {
        val base = e.getLerpedPos(td)
        val intro = (t / 4f).coerceIn(0f, 1f)
        val pulse = 0.72f + 0.28f * sin(t * 0.9f)
        val a = intro * pulse
        val ground = base.add(0.0, 0.08, 0.0)
        val x = Vec3d(1.0, 0.0, 0.0)
        val z = Vec3d(0.0, 0.0, 1.0)

        // Warnring: seine Reichweite, dazu eine rotierende Rune (nebelfrei für Sturm-Sichtbarkeit).
        d.disc(AriusLayers.VFX_RUNE, ground, x, z, REACH, withAlpha(RED, 0.62f * a), t * 0.05)
        d.ring(AriusLayers.INK, ground.add(0.0, 0.008, 0.0), x, z, REACH - 0.30, REACH + 0.08, withAlpha(0xFF42060C.toInt(), 0.94f * intro), withAlpha(0xFF42060C.toInt(), 0.94f * intro), segments = 72)
        d.ring(AriusLayers.VFX_SHARD, ground.add(0.0, 0.015, 0.0), x, z, REACH - 0.22, REACH + 0.02, withAlpha(SOLID, 0.96f * intro), withAlpha(SOLID, 0.96f * intro), segments = 72)
        d.ring(AriusLayers.VFX_GLOW, ground, x, z, REACH - 0.55, REACH + 0.22, withAlpha(RED, 0.85f * a), withAlpha(RED, 0.85f * a), segments = 72)
        d.ring(AriusLayers.VFX_GLOW, ground, x, z, REACH * 0.55, REACH - 0.28, withAlpha(RED, 0f), withAlpha(RED, 0.28f * a), segments = 64)

        // Glühende Klinge (in der Scheide: glühender Griff).
        val anchor = BladeAnchors.of(e)
        if (anchor != null && !e.sheathed) {
            val side = d.camForward.crossProduct(anchor.tip.subtract(anchor.base)).let { if (it.lengthSquared() < 1e-5) d.camRight else it.normalize() }
            d.band(AriusLayers.VFX_SHARD, anchor.base, anchor.tip, side, 0.14, withAlpha(SOLID, 0.96f * intro), withAlpha(SOLID, 0.88f * intro))
            d.band(AriusLayers.VFX_GLOW, anchor.base, anchor.tip, side, 0.32, withAlpha(RED, 0.85f * a), withAlpha(RED, 0.6f * a))
            d.band(AriusLayers.VFX_GLOW, anchor.base, anchor.tip, side, 0.10, withAlpha(HOT, 0.95f * a), withAlpha(HOT, 0.85f * a))
            d.billboard(AriusLayers.VFX_FLARE, anchor.tip, 0.62 + 0.22 * pulse, withAlpha(RED, 0.95f * a))
        } else if (anchor != null) {
            d.billboard(AriusLayers.VFX_GLOW, anchor.sheath, 0.82, withAlpha(RED, 0.9f * a))
            d.billboard(AriusLayers.VFX_FLARE, anchor.sheath, 0.45, withAlpha(HOT, 0.95f * a))
        }

        // Zeichen über dem Kopf: durchgestrichene Klinge (nur visuelle Markierung, kein HUD-Text / kein Fadenkreuz).
        val icon = base.add(0.0, e.height + 0.95, 0.0)
        val r = d.camRight
        val u = d.camUp
        val size = 0.48
        d.ring(AriusLayers.INK, icon, r, u, size * 0.78, size * 1.06, withAlpha(0xFF3A040A.toInt(), intro), withAlpha(0xFF3A040A.toInt(), intro), segments = 40)
        d.ring(AriusLayers.VFX_SHARD, icon, r, u, size * 0.83, size, withAlpha(SOLID, intro), withAlpha(SOLID, intro), segments = 40)
        val bladeA = icon.add(u.multiply(-size * 0.62)).add(r.multiply(-size * 0.62))
        val bladeB = icon.add(u.multiply(size * 0.62)).add(r.multiply(size * 0.62))
        d.band(AriusLayers.VFX_GLOW, bladeA, bladeB, r.subtract(u).normalize(), 0.055, withAlpha(HOT, 0.98f * a), withAlpha(HOT, 0.98f * a))
        val barA = icon.add(u.multiply(size * 0.68)).add(r.multiply(-size * 0.68))
        val barB = icon.add(u.multiply(-size * 0.68)).add(r.multiply(size * 0.68))
        d.band(AriusLayers.VFX_SHARD, barA, barB, r.add(u).normalize(), 0.08, withAlpha(SOLID, intro), withAlpha(SOLID, intro))
    }

    private fun drawRelease(d: VfxDraw, r: Release, t: Float) {
        val k = (t / RELEASE_TICKS).coerceIn(0f, 1f)
        val col = if (r.open) GOLD else 0xFFFFFFFF.toInt()
        val radius = REACH * (0.75 + 0.6 * k)
        val a = (1f - k) * (if (r.open) 0.92f else 0.5f)
        d.ring(AriusLayers.VFX_GLOW, r.pos.add(0.0, 0.1, 0.0), Vec3d(1.0, 0.0, 0.0), Vec3d(0.0, 0.0, 1.0), radius - 0.35, radius, withAlpha(col, a), withAlpha(col, a), segments = 64)
    }
}

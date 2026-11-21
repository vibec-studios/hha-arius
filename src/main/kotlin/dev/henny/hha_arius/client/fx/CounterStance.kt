package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.boss.AriusEntity
import dev.henny.hha_arius.boss.AriusForm
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import kotlin.math.sin

/**
 * Seit 1.11.0: Die Konterhaltung von Arius ist unübersehbar. Solange [AriusEntity.counterStance] gilt: rot glühende
 * Klinge, roter Warnring genau in Konter-Reichweite, ein durchgestrichenes Schwert über seinem Kopf, rote Bildränder und
 * „NICHT SCHLAGEN“ am Fadenkreuz. Endet die Haltung, ohne dass jemand hineingeschlagen hat, zeigt ein goldener Stoß:
 * jetzt darf man. Nur Darstellung; was zählt, entscheidet der Server.
 */
object CounterStance {

    private const val RED = 0xFFFF2A38.toInt()
    private const val HOT = 0xFFFFD0C8.toInt()

    /** Deckendes Rot, das auch auf weißem Schnee stehen bleibt. */
    private const val SOLID = 0xFFD8101E.toInt()
    private const val GOLD = 0xFFFFD45A.toInt()

    /** Reichweite der Parade auf dem Server (siehe `EirholmFight.hitArius`). */
    private const val REACH = 5.0

    /** Wie weit die Bildschirmwarnung reicht. */
    private const val WARN_RANGE = 22.0

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

        // Warnring: seine Reichweite, dazu eine rotierende Rune.
        d.disc(VfxLayers.RUNE_ADD, ground, x, z, REACH, withAlpha(RED, 0.5f * a), t * 0.05)
        d.ring(VfxLayers.SHARD_ALPHA, ground.add(0.0, 0.01, 0.0), x, z, REACH - 0.24, REACH + 0.04, withAlpha(SOLID, 0.92f * intro), withAlpha(SOLID, 0.92f * intro), segments = 72)
        d.ring(VfxLayers.GLOW_ADD, ground, x, z, REACH - 0.5, REACH + 0.2, withAlpha(RED, 0.7f * a), withAlpha(RED, 0.7f * a), segments = 72)
        d.ring(VfxLayers.GLOW_ADD, ground, x, z, REACH * 0.55, REACH - 0.28, withAlpha(RED, 0f), withAlpha(RED, 0.22f * a), segments = 64)

        // Glühende Klinge (in der Scheide: glühender Griff).
        val anchor = BladeAnchors.of(e)
        if (anchor != null && !e.sheathed) {
            val side = d.camForward.crossProduct(anchor.tip.subtract(anchor.base)).let { if (it.lengthSquared() < 1e-5) d.camRight else it.normalize() }
            d.band(VfxLayers.SHARD_ALPHA, anchor.base, anchor.tip, side, 0.13, withAlpha(SOLID, 0.95f * intro), withAlpha(SOLID, 0.85f * intro))
            d.band(VfxLayers.GLOW_ADD, anchor.base, anchor.tip, side, 0.3, withAlpha(RED, 0.75f * a), withAlpha(RED, 0.5f * a))
            d.band(VfxLayers.GLOW_ADD, anchor.base, anchor.tip, side, 0.09, withAlpha(HOT, 0.95f * a), withAlpha(HOT, 0.85f * a))
            d.billboard(VfxLayers.FLARE_ADD, anchor.tip, 0.55 + 0.2 * pulse, withAlpha(RED, 0.9f * a))
        } else if (anchor != null) {
            d.billboard(VfxLayers.GLOW_ADD, anchor.sheath, 0.75, withAlpha(RED, 0.85f * a))
            d.billboard(VfxLayers.FLARE_ADD, anchor.sheath, 0.4, withAlpha(HOT, 0.95f * a))
        }

        // Zeichen über dem Kopf: durchgestrichene Klinge.
        val icon = base.add(0.0, e.height + 0.95, 0.0)
        val r = d.camRight
        val u = d.camUp
        val size = 0.46
        d.ring(VfxLayers.SHARD_ALPHA, icon, r, u, size * 0.82, size, withAlpha(SOLID, intro), withAlpha(SOLID, intro), segments = 40)
        val bladeA = icon.add(u.multiply(-size * 0.62)).add(r.multiply(-size * 0.62))
        val bladeB = icon.add(u.multiply(size * 0.62)).add(r.multiply(size * 0.62))
        d.band(VfxLayers.GLOW_ADD, bladeA, bladeB, r.subtract(u).normalize(), 0.05, withAlpha(HOT, 0.95f * a), withAlpha(HOT, 0.95f * a))
        val barA = icon.add(u.multiply(size * 0.68)).add(r.multiply(-size * 0.68))
        val barB = icon.add(u.multiply(-size * 0.68)).add(r.multiply(size * 0.68))
        d.band(VfxLayers.SHARD_ALPHA, barA, barB, r.add(u).normalize(), 0.075, withAlpha(SOLID, intro), withAlpha(SOLID, intro))
    }

    private fun drawRelease(d: VfxDraw, r: Release, t: Float) {
        val k = (t / RELEASE_TICKS).coerceIn(0f, 1f)
        val col = if (r.open) GOLD else 0xFFFFFFFF.toInt()
        val radius = REACH * (0.75 + 0.6 * k)
        val a = (1f - k) * (if (r.open) 0.9f else 0.45f)
        d.ring(VfxLayers.GLOW_ADD, r.pos.add(0.0, 0.1, 0.0), Vec3d(1.0, 0.0, 0.0), Vec3d(0.0, 0.0, 1.0), radius - 0.35, radius, withAlpha(col, a), withAlpha(col, a), segments = 64)
    }

    fun renderHud(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val world = client.world ?: return
        val td = client.renderTickCounter.getTickProgress(true)
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        val cx = w / 2
        val cy = h / 2

        val near = live.values.mapNotNull { l ->
            val e = world.getEntityById(l.id) ?: return@mapNotNull null
            if (e.squaredDistanceTo(player) > WARN_RANGE * WARN_RANGE) null else l
        }.maxByOrNull { it.age }
        if (near != null) {
            val t = near.age + td
            val a = (t / 3f).coerceIn(0f, 1f) * (0.78f + 0.22f * sin(t * 0.9f))
            // Rote Bildränder.
            val edge = (h * 0.07f).toInt().coerceAtLeast(10)
            ctx.fillGradient(0, 0, w, edge, withAlpha(RED, 0.5f * a), withAlpha(RED, 0f))
            ctx.fillGradient(0, h - edge, w, h, withAlpha(RED, 0f), withAlpha(RED, 0.5f * a))
            // Rotes Kreuz über dem Fadenkreuz.
            for (i in -7..7) {
                ctx.fill(cx + i, cy + i, cx + i + 2, cy + i + 2, withAlpha(RED, a))
                ctx.fill(cx + i, cy - i, cx + i + 2, cy - i + 2, withAlpha(RED, a))
            }
            label(ctx, Text.translatable("hha_arius.counter.warn").formatted(Formatting.BOLD), cx, cy - 72, 2f, withAlpha(RED, a.coerceAtLeast(0.6f)))
            label(ctx, Text.translatable("hha_arius.counter.warn_sub"), cx, cy - 54, 1f, withAlpha(0xFFFFE0DC.toInt(), a.coerceAtLeast(0.6f)))
            return
        }
        val rel = released.filter { it.open && it.pos.squaredDistanceTo(player.entityPos) <= WARN_RANGE * WARN_RANGE }.minByOrNull { it.age } ?: return
        val k = ((rel.age + td) / RELEASE_TICKS).coerceIn(0f, 1f)
        label(ctx, Text.translatable("hha_arius.counter.open").formatted(Formatting.BOLD), cx, cy - 72, 2f + 0.5f * (1f - k), withAlpha(GOLD, 1f - k * k))
    }

    private fun label(ctx: DrawContext, text: Text, cx: Int, y: Int, scale: Float, color: Int) {
        val font = MinecraftClient.getInstance().textRenderer
        val m = ctx.matrices
        m.pushMatrix()
        m.translate(cx.toFloat(), y.toFloat())
        m.scale(scale, scale)
        ctx.drawTextWithShadow(font, text, -font.getWidth(text) / 2, -4, color)
        m.popMatrix()
    }
}

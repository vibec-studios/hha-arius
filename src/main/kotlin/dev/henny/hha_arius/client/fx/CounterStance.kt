package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.boss.AriusEntity
import dev.henny.hha_arius.boss.AriusForm
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import kotlin.math.min
import kotlin.math.sin

/** Konterhaltung: nur an Arius selbst sichtbar (Klinge, Nähte, Augen), ohne Bodenfläche, Text oder Zeichen. */
object CounterStance {

    private const val RED = 0xFFFF2A38.toInt()
    private const val HOT = 0xFFFFD0C8.toInt()

    /** Deckendes Rot, das auch auf weißem Schnee stehen bleibt. */
    private const val SOLID = 0xFFD8101E.toInt()
    private const val GOLD = 0xFFFFD45A.toInt()
    private const val GOLD_INK = 0xFF5A3A04.toInt()

    /** Dunkler Grund unter der roten Klinge: Auf weißem Schnee trägt er die Farbe. */
    private const val INK = 0xFF3A0610.toInt()

    private const val RELEASE_TICKS = 16

    private class Live(val id: Int) {
        var age = 0
    }

    private class Release(val id: Int, val open: Boolean) {
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
            released.add(Release(id, e.open))
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
            for (r in released) drawRelease(d, world, r, r.age + td)
        }
    }

    /**
     * Die Haltung liest man an ihm selbst: Die Klinge färbt sich von der Wurzel her blutrot, ein Glanz läuft zur Spitze,
     * Funken lösen sich von der Schneide. Nähte und Augen färbt [dev.henny.hha_arius.client.model.arius.AriusRenderer].
     */
    private fun drawStance(d: VfxDraw, e: AriusEntity, t: Float, td: Float) {
        val anchor = BladeAnchors.of(e) ?: return
        val intro = (t / 5f).coerceIn(0f, 1f)
        val pulse = 0.78f + 0.22f * sin(t * 0.62f)
        if (e.sheathed) {
            d.billboard(AriusLayers.VFX_SHARD, anchor.sheath, 0.3 * intro, withAlpha(INK, 0.9f * intro))
            d.billboard(AriusLayers.VFX_GLOW, anchor.sheath, 0.46, withAlpha(RED, 0.75f * intro * pulse))
            return
        }
        val axis = anchor.tip.subtract(anchor.base)
        val across = d.camForward.crossProduct(axis).let { if (it.lengthSquared() < 1e-5) d.camRight else it.normalize() }
        val edge = anchor.base.lerp(anchor.tip, intro.toDouble())
        d.band(AriusLayers.VFX_SHARD, anchor.base, edge, across, 0.105, withAlpha(INK, 0.92f * intro), withAlpha(INK, 0.86f * intro))
        d.band(AriusLayers.VFX_SHARD, anchor.base, edge, across, 0.062, withAlpha(SOLID, intro), withAlpha(RED, intro))
        d.band(AriusLayers.VFX_GLOW, anchor.base, edge, across, 0.2, withAlpha(RED, 0.55f * intro * pulse), withAlpha(RED, 0.3f * intro * pulse))
        val run = (t * 0.055f) % 1f
        val glintA = anchor.base.lerp(anchor.tip, (run - 0.09f).coerceAtLeast(0f).toDouble())
        val glintB = anchor.base.lerp(anchor.tip, (run + 0.09f).coerceAtMost(1f).toDouble())
        d.band(AriusLayers.VFX_SHARD, glintA, glintB, across, 0.03, withAlpha(HOT, 0.2f * intro), withAlpha(HOT, intro))
        for (i in 0 until 5) {
            val k = (t * 0.045f + i * 0.2f) % 1f
            val at = anchor.base.lerp(anchor.tip, 0.12 + 0.84 * ((i * 0.37f + 0.1f) % 1f)).add(across.multiply(0.09 * sin(k * 7f + i)))
                .add(0.0, 0.05 + k * 0.42, 0.0)
            val fade = (min(k, 1f - k) * 5f).coerceIn(0f, 1f) * intro
            if (fade > 0.03f) d.billboard(AriusLayers.VFX_SHARD, at, 0.045 * (1f - k * 0.5f), withAlpha(SOLID, 0.95f * fade), (t * 3f + i * 40f).toDouble())
        }
    }

    /** Ende der Haltung: Ein goldener Schimmer läuft über die Klinge, jetzt darf man. Ohne Fenster bleibt er weiß und leise. */
    private fun drawRelease(d: VfxDraw, world: net.minecraft.client.world.ClientWorld, r: Release, t: Float) {
        val e = world.getEntityById(r.id) as? AriusEntity ?: return
        val anchor = BladeAnchors.of(e) ?: return
        val k = (t / RELEASE_TICKS).coerceIn(0f, 1f)
        val a = (1f - k) * (1f - k) * (if (r.open) 1f else 0.45f)
        val col = if (r.open) GOLD else 0xFFFFFFFF.toInt()
        if (e.sheathed) {
            d.billboard(AriusLayers.VFX_FLARE, anchor.sheath, 0.5, withAlpha(col, a))
            return
        }
        val across = d.camForward.crossProduct(anchor.tip.subtract(anchor.base)).let { if (it.lengthSquared() < 1e-5) d.camRight else it.normalize() }
        d.band(AriusLayers.VFX_SHARD, anchor.base, anchor.tip, across, 0.085, withAlpha(GOLD_INK, 0.8f * a), withAlpha(GOLD_INK, 0.7f * a))
        d.band(AriusLayers.VFX_SHARD, anchor.base, anchor.tip, across, 0.05, withAlpha(col, a), withAlpha(col, a))
        d.billboard(AriusLayers.VFX_FLARE, anchor.tip, 0.5 + 0.5 * k, withAlpha(col, a))
    }

    /** Während der Haltung und der Bestrafung bleibt das Bild ruhig (auch gegenüber dem Kameraschütteln von HHA). */
    @JvmStatic
    fun steadyView(): Boolean = active || PunishCam.hidesHands()
}

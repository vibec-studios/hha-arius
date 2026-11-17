package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.net.AtmosMode
import dev.henny.hha_arius.net.AtmosS2C
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wetter und Licht in Eirholm (nur in der Arena-Dimension): der Server wählt eine Stimmung ([AtmosMode]), der Client
 * blendet Nebel, Himmel und Licht über ([override], per Mixin auf den Umgebungswerten) und zeichnet dazu
 * * Schneetreiben im Wind (Partikel um die Kamera, Böen),
 * * Schneeschleier: große, weiche, treibende Bahnen aus Rauschen (Volumen im Sturm, wie Nebelbänke),
 * * Glitzern auf Eisflächen (blaues/festes Eis mit Luft darüber funkelt kurz auf).
 * Alles skaliert mit `/ariusc fx`.
 */
object Atmos {

    private class Mood(
        val fogStart: Float, val fogEnd: Float, val fog: Int, val sky: Int, val light: Float,
        val snow: Float, val wind: Float, val veil: Float,
    )

    private val MOODS = arrayOf(
        // Seit 1.2.2: draußen immer Sturm (Crix: „die ganze Zeit ein Schneesturm“), je Stimmung stärker oder lichter.
        Mood(22f, 230f, 0xFFD3DFEC.toInt(), 0xFFA9C2E0.toInt(), 0.95f, 0.75f, 0.8f, 0.35f), // CLEAR
        Mood(12f, 160f, 0xFFC9D7E7.toInt(), 0xFF9FB8D6.toInt(), 0.88f, 1.1f, 1.05f, 0.5f), // SNOW
        Mood(1f, 30f, 0xFFE6EDF5.toInt(), 0xFFCAD7E6.toInt(), 0.72f, 1.8f, 1.7f, 1.0f), // BLIZZARD
        Mood(34f, 300f, 0xFFCADAEC.toInt(), 0xFF6F9BD6.toInt(), 1.05f, 0.6f, 0.8f, 0.25f), // REVEAL
        Mood(4f, 80f, 0xFF1C3352.toInt(), 0xFF12223A.toInt(), 0.55f, 0.08f, 0.05f, 0.15f), // VAULT
        Mood(8f, 150f, 0xFFF5F9FF.toInt(), 0xFFE8F1FF.toInt(), 1.2f, 0.55f, 0.6f, 0.3f), // WHITE
    )

    private var from: Mood = MOODS[AtmosMode.CLEAR]
    private var to: Mood = MOODS[AtmosMode.CLEAR]
    private var blend = 1f
    private var blendTicks = 1
    private var active = false
    private var age = 0L

    /** Windrichtung (waagerecht) und Böen. */
    private var windAngle = 0.6

    private class Veil(var pos: Vec3d, val size: Double, val life: Int, val seed: Float) {
        var age = 0
    }

    private val veils = ArrayList<Veil>()

    private class Glint(val pos: Vec3d, val life: Int) {
        var age = 0
    }

    private val glints = ArrayList<Glint>()

    fun handle(p: AtmosS2C) {
        if (p.mode < 0) {
            active = false
            return
        }
        val target = MOODS.getOrNull(p.mode) ?: return
        from = current()
        to = target
        blend = 0f
        blendTicks = p.ticks.coerceAtLeast(1)
        active = true
    }

    fun reset() {
        active = false
        SnowStorm.reset()
        veils.clear()
        glints.clear()
    }

    private fun inEirholm(): Boolean {
        val world = MinecraftClient.getInstance().world ?: return false
        return world.registryKey == ArenaDimension.WORLD
    }

    private fun current(): Mood {
        val t = blend.coerceIn(0f, 1f)
        val s = t * t * (3 - 2 * t)
        fun f(a: Float, b: Float) = a + (b - a) * s
        return Mood(
            f(from.fogStart, to.fogStart), f(from.fogEnd, to.fogEnd), lerpColor(from.fog, to.fog, s), lerpColor(from.sky, to.sky, s),
            f(from.light, to.light), f(from.snow, to.snow), f(from.wind, to.wind), f(from.veil, to.veil),
        )
    }

    /** Für den Mixin auf den Umgebungswerten: ersetzt Nebel, Himmel und Licht in Eirholm. */
    @JvmStatic
    fun override(attribute: EnvironmentAttribute<*>, value: Any?): Any? {
        if (!active || !inEirholm()) return value
        val m = current()
        return when (attribute) {
            // Sturmböe: Nebel zieht kurz zu (Whiteout).
            EnvironmentAttributes.FOG_START_DISTANCE_VISUAL -> if (value is Float) m.fogStart * (1f - 0.8f * SnowStorm.whiteout) else value
            EnvironmentAttributes.FOG_END_DISTANCE_VISUAL -> if (value is Float) m.fogEnd * (1f - 0.65f * SnowStorm.whiteout) else value
            EnvironmentAttributes.FOG_COLOR_VISUAL -> if (value is Int) m.fog else value
            EnvironmentAttributes.SKY_COLOR_VISUAL -> if (value is Int) m.sky else value
            EnvironmentAttributes.SKY_LIGHT_FACTOR_VISUAL -> if (value is Float) (value * m.light).coerceIn(0f, 1.25f) else value
            else -> value
        }
    }

    fun tick(world: ClientWorld) {
        age++
        if (blend < 1f) blend = (blend + 1f / blendTicks).coerceAtMost(1f)
        veils.removeIf { ++it.age >= it.life }
        glints.removeIf { ++it.age >= it.life }
        if (!active || world.registryKey != ArenaDimension.WORLD) return
        val client = MinecraftClient.getInstance()
        val cam = client.gameRenderer.camera.cameraPos
        val m = current()
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        val r = AriusFx.random()
        // Wind dreht langsam, Böen pulsieren.
        windAngle += (r.nextDouble() - 0.5) * 0.01
        val gust = 0.7 + 0.3 * sin(age * 0.05) + 0.2 * sin(age * 0.13)
        val wx = cos(windAngle) * m.wind * gust
        val wz = sin(windAngle) * m.wind * gust
        // Schneesturm (eigenes Flockenfeld mit Bewegungsunschärfe, Böen, Bodentreiben)
        SnowStorm.tick(world, m.snow, m.wind * 0.55f, windAngle)
        // Schneeschleier
        if (m.veil > 0.05f && veils.size < (14 * fx).toInt() && r.nextFloat() < m.veil * 0.4f) {
            val a = r.nextDouble() * 2 * PI
            val d = 8 + r.nextDouble() * 34
            veils.add(Veil(cam.add(cos(a) * d - wx * 20, -2 + r.nextDouble() * 10, sin(a) * d - wz * 20), 6.0 + r.nextDouble() * 10, 70 + r.nextInt(60), r.nextFloat()))
        }
        for (v in veils) v.pos = v.pos.add(wx * 0.35, 0.0, wz * 0.35)
        // Eisglitzern
        if (glints.size < (40 * fx).toInt()) {
            repeat((3 * fx).toInt().coerceAtLeast(if (fx > 0f) 1 else 0)) {
                val bx = BlockPos.ofFloored(cam.x + (r.nextDouble() - 0.5) * 36, cam.y + (r.nextDouble() - 0.6) * 14, cam.z + (r.nextDouble() - 0.5) * 36)
                val state = world.getBlockState(bx)
                if ((state.isOf(Blocks.BLUE_ICE) || state.isOf(Blocks.PACKED_ICE)) && world.getBlockState(bx.up()).isAir) {
                    glints.add(Glint(Vec3d(bx.x + r.nextDouble(), bx.y + 1.02, bx.z + r.nextDouble()), 14 + r.nextInt(16)))
                }
            }
        }
    }

    /** Frostrand im HUD (nur im Heiligtum). */
    fun renderHud(ctx: net.minecraft.client.gui.DrawContext) {
        if (!active || !inEirholm()) return
        SnowStorm.renderVignette(ctx)
    }

    fun render(d: VfxDraw) {
        if (!active || !inEirholm()) return
        val m = current()
        SnowStorm.render(d, m.snow)
        for (v in veils) {
            val t = (v.age + d.tickDelta) / v.life
            val k = (sin(t * PI) * m.veil * 0.22).toFloat().coerceIn(0f, 0.3f)
            if (k <= 0.005f) continue
            d.billboard(VfxLayers.FLOW_ALPHA, v.pos, v.size, withAlpha(0xFFFFFFFF.toInt(), k), (v.seed * 6.0) + t * 0.6)
        }
        for (g in glints) {
            val t = (g.age + d.tickDelta) / g.life
            val k = sin(t * PI).toFloat()
            d.billboard(VfxLayers.FLARE_ADD, g.pos, 0.12 + 0.1 * k, withAlpha(0xFFDFF4FF.toInt(), 0.8f * k))
        }
    }
}

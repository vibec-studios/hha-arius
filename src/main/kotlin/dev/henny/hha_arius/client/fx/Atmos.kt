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
        Mood(10f, 150f, 0xFFB7C7DB.toInt(), 0xFF7F9CC2.toInt(), 0.82f, 1.3f, 1.6f, 0.7f), // VAULT = Himmelsbalkon im Sturm
        Mood(8f, 150f, 0xFFF5F9FF.toInt(), 0xFFE8F1FF.toInt(), 1.2f, 0.55f, 0.6f, 0.3f), // WHITE
        Mood(14f, 190f, 0xFFA9B9CF.toInt(), 0xFF4D6488.toInt(), 0.7f, 1.2f, 1.5f, 0.65f), // AERIE: dunkler Sturmhimmel
        Mood(0f, 22f, 0xFFEEF3F9.toInt(), 0xFFDCE6F1.toInt(), 0.75f, 2.6f, 2.6f, 1.5f), // WHITEOUT: Jagd
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
        client.player?.let { SnowStorm.tickScreen(it.yaw, it.pitch) }
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

    /**
     * Seit 1.8.0 (Crix: „die Arena und die unendliche Leere sollen sich lebendiger anfühlen“): ohne Zustand, nur aus
     * Zeit und Kamera berechnet –
     * * Polarlichter: drei lange, wehende Lichtvorhänge hoch am Himmel (im dichten Sturm kaum zu sehen),
     * * treibende Eisfunken, die im Wind um die Kamera ziehen und funkeln,
     * * Windschlieren dicht über dem Boden, die mit den Böen laufen,
     * * ab und zu eine Sternschnuppe aus Frost.
     */
    private fun life(d: VfxDraw, m: Mood) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        if (fx <= 0f) return
        val cam = d.cam
        val time = age + d.tickDelta
        val clear = (1f - (m.snow - 0.6f) / 1.6f).coerceIn(0.1f, 1f)
        // Polarlichter
        for (band in 0 until 3) {
            val path = ArrayList<Vec3d>(32)
            val baseA = band * 2.1 + 0.4
            for (j in 0 until 32) {
                val q = j / 31.0
                val ang = baseA + (q - 0.5) * 1.6
                val dist = 230.0 + 40.0 * sin(q * 5.0 + band)
                val y = 120.0 + band * 18.0 + 10.0 * sin(q * 7.0 + time * 0.01 + band)
                path.add(Vec3d(cam.x + cos(ang) * dist, cam.y + y, cam.z + sin(ang) * dist))
            }
            val col = when (band) { 0 -> 0xFF7FE8D0.toInt(); 1 -> 0xFF8FB8FF.toInt(); else -> 0xFFB79CFF.toInt() }
            d.ribbon(VfxLayers.FLOW_ADD, path, { q -> 14.0 + 8.0 * sin(q * 9.0 + time * 0.02) }, { q -> withAlpha(col, (0.22f * clear * fx * sin(q * PI).toFloat()).coerceAtLeast(0f)) }, uScale = 0.05f, uOffset = -time * 0.004f)
        }
        // Treibende Eisfunken
        val wx = cos(windAngle)
        val wz = sin(windAngle)
        val n = (36 * fx).toInt()
        for (i in 0 until n) {
            val seed = i * 12.9898
            val phase = ((time * (0.004 + (i % 7) * 0.0007) + (seed % 1.0)) % 1.0)
            val ox = ((sin(seed) * 43758.5453) % 1.0) * 24.0
            val oz = ((sin(seed * 1.7) * 24634.6345) % 1.0) * 24.0
            val p = Vec3d(cam.x + ox + wx * (phase - 0.5) * 30.0, cam.y - 2.0 + (i % 9) * 1.1 + sin(time * 0.05 + i) * 0.6, cam.z + oz + wz * (phase - 0.5) * 30.0)
            val tw = (0.5 + 0.5 * sin(time * 0.3 + i * 1.7)).toFloat()
            d.billboard(VfxLayers.FLARE_ADD, p, 0.07 + 0.06 * tw, withAlpha(0xFFDFF4FF.toInt(), 0.75f * tw * sin(phase * PI).toFloat()))
        }
        // Windschlieren über dem Boden
        val world = MinecraftClient.getInstance().world ?: return
        for (i in 0 until (6 * fx).toInt()) {
            val k = ((time * 0.012 + i * 0.37) % 1.0)
            val side = Vec3d(-wz, 0.0, wx)
            val c0 = Vec3d(cam.x + side.x * (i - 3) * 6.0 - wx * 30.0 * (0.5 - k), cam.y, cam.z + side.z * (i - 3) * 6.0 - wz * 30.0 * (0.5 - k))
            val top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, kotlin.math.floor(c0.x).toInt(), kotlin.math.floor(c0.z).toInt()).toDouble()
            if (kotlin.math.abs(top - cam.y) > 12.0) continue
            val a = Vec3d(c0.x, top + 0.3 + (i % 3) * 0.25, c0.z)
            val b = a.add(wx * 7.0, 0.1 * sin(time * 0.1 + i), wz * 7.0)
            d.band(VfxLayers.FLOW_ADD, a, b, VfxDrawUp, 0.18, withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFFFFFFF.toInt(), 0.25f * sin(k * PI).toFloat()), uScale = 0.3f, uOffset = -time * 0.05f)
        }
        // Sternschnuppe aus Frost (etwa alle 15 s)
        val cycle = (time / 300.0)
        val ph = cycle - kotlin.math.floor(cycle)
        if (ph < 0.06 && clear > 0.3f) {
            val k = ph / 0.06
            val seed = kotlin.math.floor(cycle)
            val ang = seed * 2.39996
            val from = Vec3d(cam.x + cos(ang) * 160.0, cam.y + 110.0, cam.z + sin(ang) * 160.0)
            val dir = Vec3d(-sin(ang), -0.35, cos(ang)).normalize()
            val head = from.add(dir.multiply(k * 140.0))
            d.band(VfxLayers.GLOW_ADD, head.subtract(dir.multiply(24.0)), head, d.camUp, 0.5, withAlpha(0xFFBFE8FF.toInt(), 0f), withAlpha(0xFFFFFFFF.toInt(), 0.9f * clear * (1f - k.toFloat())))
            d.billboard(VfxLayers.FLARE_ADD, head, 2.5, withAlpha(0xFFFFFFFF.toInt(), 0.9f * clear * (1f - k.toFloat())))
        }
    }

    private val VfxDrawUp = Vec3d(0.0, 1.0, 0.0)

    /** Frostrand im HUD (nur im Heiligtum). */
    fun renderHud(ctx: net.minecraft.client.gui.DrawContext) {
        if (!active || !inEirholm()) return
        if (SnowStorm.screenMode()) SnowStorm.renderScreen(ctx, current().snow, MinecraftClient.getInstance().renderTickCounter.getTickProgress(true))
        SnowStorm.renderVignette(ctx)
    }

    fun render(d: VfxDraw) {
        if (!active || !inEirholm()) return
        val m = current()
        SnowStorm.render(d, m.snow)
        life(d, m)
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

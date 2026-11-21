package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.arena.Highland
import dev.henny.hha_arius.net.AtmosMode
import dev.henny.hha_arius.net.AtmosS2C
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.fog.FogData
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

    /**
     * Seit 1.10.0: Stimmung der Jagd (Hunt.HUNT_ATMOS). Ziel ist der Whiteout, aber [current] mischt nach Ort: auf dem
     * Feld nur Schneetreiben, zum Fuß der Berge hin zieht der Sturm zu, in Höhlen dunkel und still.
     */
    private var hunt = false

    /** 0 = weit vor den Bergen … 1 = am Fuß oder auf dem Berg (geglättet). */
    private var footK = 0f

    /** 0..1 in einer Höhle der Jagd (geglättet), dazu ihre Art. */
    private var caveK = 0f
    private var caveTheme: dev.henny.hha_arius.arena.HuntRoute.Theme? = null

    fun handle(p: AtmosS2C) {
        if (p.mode < 0) {
            active = false
            hunt = false
            return
        }
        hunt = p.mode == dev.henny.hha_arius.boss.Hunt.HUNT_ATMOS
        val target = (if (hunt) MOODS[AtmosMode.WHITEOUT] else MOODS.getOrNull(p.mode)) ?: return
        from = current()
        to = target
        blend = 0f
        blendTicks = p.ticks.coerceAtLeast(1)
        active = true
    }

    fun reset() {
        active = false
        hunt = false
        footK = 0f
        caveK = 0f
        SnowStorm.reset()
        veils.clear()
        glints.clear()
    }

    private fun inEirholm(): Boolean {
        val world = MinecraftClient.getInstance().world ?: return false
        return world.registryKey == ArenaDimension.WORLD
    }

    /**
     * Seit 1.8.1: In der Arena-Dimension ist immer Sturm. Hat der Server (noch) keine Stimmung geschickt – Nachzügler,
     * nach dem Kampf, beim Wiederbetreten –, gilt das Schneetreiben ([AtmosMode.SNOW]).
     */
    private fun stormOn(): Boolean = inEirholm()

    private fun current(): Mood {
        if (!active) return MOODS[AtmosMode.SNOW]
        val t = blend.coerceIn(0f, 1f)
        val s = t * t * (3 - 2 * t)
        fun f(a: Float, b: Float) = a + (b - a) * s
        val m = Mood(
            f(from.fogStart, to.fogStart), f(from.fogEnd, to.fogEnd), lerpColor(from.fog, to.fog, s), lerpColor(from.sky, to.sky, s),
            f(from.light, to.light), f(from.snow, to.snow), f(from.wind, to.wind), f(from.veil, to.veil),
        )
        return if (hunt) huntMood(m) else m
    }

    private fun mix(a: Mood, b: Mood, k: Float): Mood {
        fun f(x: Float, y: Float) = x + (y - x) * k
        return Mood(f(a.fogStart, b.fogStart), f(a.fogEnd, b.fogEnd), lerpColor(a.fog, b.fog, k), lerpColor(a.sky, b.sky, k), f(a.light, b.light), f(a.snow, b.snow), f(a.wind, b.wind), f(a.veil, b.veil))
    }

    /** Höhlenstimmung je Art: kurze, dunkle Sicht, kaum Schnee. */
    private fun caveMood(t: dev.henny.hha_arius.arena.HuntRoute.Theme?): Mood = when (t) {
        dev.henny.hha_arius.arena.HuntRoute.Theme.DARK -> Mood(0f, 13f, 0xFF0B111A.toInt(), 0xFF0B111A.toInt(), 0.35f, 0.05f, 0.2f, 0f)
        dev.henny.hha_arius.arena.HuntRoute.Theme.CRAWL -> Mood(0f, 11f, 0xFF141A22.toInt(), 0xFF141A22.toInt(), 0.4f, 0.05f, 0.2f, 0f)
        dev.henny.hha_arius.arena.HuntRoute.Theme.CRYSTAL -> Mood(2f, 34f, 0xFF7FA9CF.toInt(), 0xFF7FA9CF.toInt(), 0.7f, 0.1f, 0.3f, 0f)
        dev.henny.hha_arius.arena.HuntRoute.Theme.WIND -> Mood(1f, 22f, 0xFFB5C6D8.toInt(), 0xFFB5C6D8.toInt(), 0.6f, 0.9f, 2.4f, 0.6f)
        else -> Mood(1f, 30f, 0xFF3B5068.toInt(), 0xFF3B5068.toInt(), 0.55f, 0.1f, 0.3f, 0f)
    }

    private fun huntMood(whiteout: Mood): Mood {
        var m = mix(MOODS[AtmosMode.SNOW], whiteout, footK)
        if (caveK > 0.01f) m = mix(m, caveMood(caveTheme), caveK)
        val breath = HuntScares.breathAt()
        if (breath > 0.01f) m = mix(m, Mood(0f, 6f, 0xFFDCEBF7.toInt(), 0xFFDCEBF7.toInt(), 0.9f, 0.4f, 0.4f, 0.4f), breath * 0.85f)
        return m
    }

    /** Ort der Kamera für die Jagdstimmung: Abstand zum Fuß der Berge, Höhle. */
    private fun huntPlace(cam: Vec3d) {
        val slot = Math.floorDiv(kotlin.math.floor(cam.x).toInt() + ArenaLayout.SLOT_SPACING / 2, ArenaLayout.SLOT_SPACING)
        val o = ArenaLayout.origin(slot)
        val rx = cam.x - o.x
        val rz = cam.z - o.z
        var foot = Double.MAX_VALUE
        for (pk in dev.henny.hha_arius.arena.HuntRoute.MOUNTS) foot = minOf(foot, kotlin.math.hypot(rx - pk.x, rz - pk.z) - pk.r)
        foot = minOf(foot, kotlin.math.hypot(rx - (ArenaLayout.SPIRE.x + 0.5), rz - (ArenaLayout.SPIRE.z + 0.5)) - FarPlan.MASSIF_R)
        // ab 45 Blöcke vor dem Fuß zieht der Sturm zu, ab dem Fuß voll
        val want = (1.0 - (foot / 45.0)).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }.toFloat()
        footK += (want - footK) * 0.04f
        val cave = HuntScares.caveAtCamera()
        if (cave != null) caveTheme = cave.theme
        caveK += ((if (cave != null) 1f else 0f) - caveK) * 0.08f
    }

    // Sturmböe: Nebel zieht kurz zu (Whiteout).
    // Seit 1.11.0: In Szenen reicht die Sicht weiter, damit man den Berg sieht ([CineClient.widenFog]); dichter Sturm bleibt.
    private fun fogStart(m: Mood) = CineClient.widenFog(m.fogStart * (1f - 0.8f * SnowStorm.whiteout), m.fogEnd * (1f - 0.65f * SnowStorm.whiteout), 150f)
    private fun fogEnd(m: Mood) = CineClient.widenFog(m.fogEnd * (1f - 0.65f * SnowStorm.whiteout), m.fogEnd * (1f - 0.65f * SnowStorm.whiteout), 760f)

    /** Aktueller Nebel (Anfang, Ende) und seine Farbe, für die Fernlandschaft in Szenen ([FarTerrain]). */
    fun fogRange(): Pair<Float, Float> = current().let { fogStart(it) to fogEnd(it) }
    fun fogColorNow(): Int = current().fog

    /** Für den Mixin auf den Umgebungswerten: ersetzt Nebel, Himmel und Licht in Eirholm. */
    @JvmStatic
    fun override(attribute: EnvironmentAttribute<*>, value: Any?): Any? {
        if (!stormOn()) return value
        val m = current()
        return when (attribute) {
            EnvironmentAttributes.FOG_START_DISTANCE_VISUAL -> if (value is Float) fogStart(m) else value
            EnvironmentAttributes.FOG_END_DISTANCE_VISUAL -> if (value is Float) fogEnd(m) else value
            EnvironmentAttributes.FOG_COLOR_VISUAL -> if (value is Int) m.fog else value
            EnvironmentAttributes.SKY_COLOR_VISUAL -> if (value is Int) m.sky else value
            EnvironmentAttributes.SKY_LIGHT_FACTOR_VISUAL -> if (value is Float) (value * m.light).coerceIn(0f, 1.25f) else value
            else -> value
        }
    }

    /**
     * Für den Mixin auf dem FogRenderer: schreibt die Sturm-Nebeldistanz kurz vor dem Nebel-Puffer noch einmal fest.
     * Mods wie NoFog, ClearViews oder bactromod setzen den Atmosphären-Nebel vorher auf „unendlich“.
     */
    @JvmStatic
    fun enforceFog(data: FogData) {
        if (!stormOn()) return
        val m = current()
        data.environmentalStart = fogStart(m)
        data.environmentalEnd = fogEnd(m)
    }

    fun tick(world: ClientWorld) {
        age++
        if (blend < 1f) blend = (blend + 1f / blendTicks).coerceAtMost(1f)
        veils.removeIf { ++it.age >= it.life }
        glints.removeIf { ++it.age >= it.life }
        if (world.registryKey != ArenaDimension.WORLD) return
        val client = MinecraftClient.getInstance()
        val cam = client.gameRenderer.camera.cameraPos
        if (hunt) huntPlace(cam)
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
        // Polarlichter: senkrechte Vorhänge, unten hell, nach oben und zu den Enden weich auslaufend (seit 1.8.1 – das
        // alte flache Band hatte harte Kanten und wirkte im Flug wie eine durchsichtige Fläche am Himmel). Hoch über dem
        // Hochland verankert, nicht an der Kamera, und nur, wenn der Sturm aufreißt.
        val aurora = (clear - 0.35f).coerceAtLeast(0f) / 0.65f
        if (aurora > 0.01f) {
            val vc = d.buffer(VfxLayers.FLOW_ADD)
            val skyY = ArenaLayout.BASE_Y + 150.0
            for (band in 0 until 3) {
                val baseA = band * 2.1 + 0.4
                val col = when (band) { 0 -> 0xFF7FE8D0.toInt(); 1 -> 0xFF8FB8FF.toInt(); else -> 0xFFB79CFF.toInt() }
                val segs = 40
                var prev: Vec3d? = null
                var prevA = 0f
                for (j in 0..segs) {
                    val q = j / segs.toDouble()
                    val ang = baseA + (q - 0.5) * 1.6
                    val dist = 260.0 + 40.0 * sin(q * 5.0 + band)
                    val wave = 8.0 * sin(q * 7.0 + time * 0.01 + band)
                    val p = Vec3d(cam.x + cos(ang) * dist + wave, skyY + band * 16.0, cam.z + sin(ang) * dist - wave)
                    val a = (0.2f * aurora * fx * sin(q * PI).toFloat() * (0.7f + 0.3f * sin(q * 23.0 + time * 0.05).toFloat())).coerceAtLeast(0f)
                    val o = prev
                    if (o != null) {
                        val h = 46.0 + 14.0 * sin(q * 9.0 + time * 0.02)
                        val u0 = (j - 1) * 0.08f - time * 0.002f
                        val u1 = j * 0.08f - time * 0.002f
                        // unterer Saum (weich einblenden) und Vorhang (nach oben ausblenden)
                        with(d) { vc.put(o.x, o.y - 6.0, o.z, u0, 1f, withAlpha(col, 0f)) }
                        with(d) { vc.put(p.x, p.y - 6.0, p.z, u1, 1f, withAlpha(col, 0f)) }
                        with(d) { vc.put(p.x, p.y, p.z, u1, 0.85f, withAlpha(col, a)) }
                        with(d) { vc.put(o.x, o.y, o.z, u0, 0.85f, withAlpha(col, prevA)) }
                        with(d) { vc.put(o.x, o.y, o.z, u0, 0.85f, withAlpha(col, prevA)) }
                        with(d) { vc.put(p.x, p.y, p.z, u1, 0.85f, withAlpha(col, a)) }
                        with(d) { vc.put(p.x, p.y + h, p.z, u1, 0f, withAlpha(lerpColor(col, 0xFFB79CFF.toInt(), 0.5f), 0f)) }
                        with(d) { vc.put(o.x, o.y + h, o.z, u0, 0f, withAlpha(lerpColor(col, 0xFFB79CFF.toInt(), 0.5f), 0f)) }
                    }
                    prev = p
                    prevA = a
                }
            }
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

    /**
     * Hochland-Stimmung (seit 1.8.1): Über den Resten des Ordens der weißen Klinge (Klingen, Türme, Schwurkreise,
     * Klingengräber) steigen langsam Lichtfunken auf; über dem Tor der Klinge auf dem Gipfel steht eine schwache
     * Lichtsäule (Wegweiser durch den Sturm); bei aufreißendem Himmel ein 22°-Halo um die Sonne wie in Polarnächten.
     * Die Lage kommt aus denselben Formeln wie der Bau ([Highland], [FarPlan]); die Instanz ergibt sich aus der Kamera.
     */
    private fun landmarks(d: VfxDraw, m: Mood) {
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        if (fx <= 0f) return
        val cam = d.cam
        val time = age + d.tickDelta
        val slot = Math.floorDiv(kotlin.math.floor(cam.x).toInt() + ArenaLayout.SLOT_SPACING / 2, ArenaLayout.SLOT_SPACING)
        val o = ArenaLayout.origin(slot)
        val rx = cam.x - o.x
        val rz = cam.z - o.z
        val vis = visibility().toDouble()
        for (lm in Highland.landmarks) {
            val dx = lm.x + 0.5 - rx
            val dz = lm.z + 0.5 - rz
            val dist = kotlin.math.sqrt(dx * dx + dz * dz)
            if (dist > 96.0) continue
            val fade = (1.0 - dist / (vis + 20.0)).coerceIn(0.0, 1.0).toFloat()
            if (fade <= 0.02f) continue
            val n = (10 * fx).toInt()
            for (i in 0 until n) {
                val seed = lm.seed * 0.001 + i * 1.618
                val ph = ((time * 0.006 + seed) % 1.0)
                val a = seed * 6.283
                val r = 1.0 + (i % 4) * 1.6
                val p = Vec3d(o.x + lm.x + 0.5 + cos(a + time * 0.01) * r, o.y + lm.base + ph * 14.0, o.z + lm.z + 0.5 + sin(a + time * 0.01) * r)
                val tw = (0.6 + 0.4 * sin(time * 0.2 + i)).toFloat()
                d.billboard(AriusLayers.MOON_FLARE, p, 0.18 + 0.1 * tw, withAlpha(0xFFBFE6FF.toInt(), 0.8f * fade * tw * sin(ph * PI).toFloat()))
            }
        }
        // Lichtsäule über dem Tor der Klinge
        val gate = FarPlan.SUMMIT_GATE
        val g = Vec3d(o.x + gate.x + 0.5, o.y + gate.y + 10.0, o.z + gate.z + 0.5)
        val gd = g.distanceTo(cam)
        if (gd in 40.0..700.0) {
            val a = (0.16f * fx * (1f - (gd / 700.0).toFloat())).coerceAtLeast(0f)
            d.band(AriusLayers.MOON_GLOW, g, g.add(0.0, 90.0, 0.0), d.camRight, 1.6, withAlpha(0xFFCFEFFF.toInt(), a), withAlpha(0xFFCFEFFF.toInt(), 0f))
        }
        // 22°-Halo um die Sonne (feste Tageszeit, Sonne im Osten), nur bei klarer Sicht
        val clear = ((m.fogEnd - 120f) / 150f).coerceIn(0f, 1f)
        if (clear > 0.02f) {
            val sunDir = Vec3d(1.0, 0.62, 0.15).normalize()
            val sun = cam.add(sunDir.multiply(220.0))
            val (ax, az) = dev.henny.hha.client.vfx.basisOf(sunDir)
            val rr = 220.0 * kotlin.math.tan(Math.toRadians(22.0))
            d.ring(AriusLayers.MOON_GLOW, sun, ax, az, rr - 4.0, rr + 3.0, withAlpha(0xFFFFE6C8.toInt(), 0.12f * clear * fx), withAlpha(0xFFBFD8FF.toInt(), 0f), segments = 72)
        }
    }

    /** Sichtweite der laufenden Stimmung in Blöcken (Nebelende, in Böen kürzer). */
    fun visibility(): Float = if (stormOn()) fogEnd(current()) else 200f

    /** Frostrand, Whiteout und (je nach Modus) Bildschirmflocken im HUD (nur im Heiligtum). */
    fun renderHud(ctx: net.minecraft.client.gui.DrawContext) {
        if (!stormOn()) return
        val snow = current().snow
        val td = MinecraftClient.getInstance().renderTickCounter.getTickProgress(true)
        // Ohne Shaderpack zeichnet die Welt den Sturm; in Böen fliegen trotzdem ein paar Flocken „ins Gesicht“.
        SnowStorm.renderScreen(ctx, snow, td, if (SnowStorm.screenMode()) 1f else SnowStorm.gustScreenShare())
        SnowStorm.renderWhiteout(ctx, snow)
        SnowStorm.renderVignette(ctx)
    }

    fun render(d: VfxDraw) {
        if (!stormOn()) return
        val m = current()
        SnowStorm.render(d, m.snow)
        life(d, m)
        landmarks(d, m)
        // Schneeschleier: weiche Rauchbänke (runde Ränder) statt der alten Rauschquadrate, nah ausgeblendet.
        for (v in veils) {
            val t = (v.age + d.tickDelta) / v.life
            val near = ((v.pos.distanceTo(d.cam) - v.size * 0.6) / 6.0).coerceIn(0.0, 1.0)
            val k = (sin(t * PI) * m.veil * 0.3 * near).toFloat().coerceIn(0f, 0.32f)
            if (k <= 0.005f) continue
            d.billboard(AriusLayers.VEIL, v.pos, v.size, withAlpha(0xFFD3DDE9.toInt(), k), (v.seed * 6.0) + t * 0.6)
        }
        for (g in glints) {
            val t = (g.age + d.tickDelta) / g.life
            val k = sin(t * PI).toFloat()
            d.billboard(VfxLayers.FLARE_ADD, g.pos, 0.12 + 0.1 * k, withAlpha(0xFFDFF4FF.toInt(), 0.8f * k))
        }
    }
}

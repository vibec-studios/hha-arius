package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.boss.NivarisShape
import dev.henny.hha_arius.client.model.nivaris.NivarisRenderState
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Solvane (Drachenform) löst sich Glied für Glied in Licht und Eiskristalle auf bzw. entsteht daraus
 * ([dev.henny.hha_arius.boss.NivarisEntity.veil]).
 *
 * Teile: 0 = Kopf, 1..40 = Glieder, 41 = Schwanzfächer. Der Schleier läuft von der Schwanzspitze zum Kopf (Auflösen);
 * beim Entstehen läuft derselbe Wert rückwärts, also erscheint der Kopf zuerst und der Körper wächst entlang der
 * Windung nach hinten. Der Renderer schrumpft jedes Teil in seinem Fenster ([shrink]) und legt einen hellen
 * Leuchtüberzug darüber ([sheen]); hier entstehen dazu Lichtschein, Funken und Kristallsplitter an der Stelle des Teils.
 *
 * Schrumpfen ist nie abschaltbar (sonst verschwände der Körper wieder schlagartig); Leuchten, Funken und Splitter
 * skalieren mit [AriusClientConfig.fx].
 */
object DragonVeil {
    const val PARTS = NivarisShape.SEGMENTS + 2

    /** Anteil des Schleiers, über den sich ein einzelnes Teil auflöst (Überlappung der Nachbarn). */
    private const val WINDOW = 0.18f

    private fun smooth(x: Float): Float = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

    /** Fortschritt des Teils [part] beim Schleier [veil]: 0 = ganz da, 1 = fort. */
    fun progress(veil: Float, part: Int): Float {
        if (veil <= 0f) return 0f
        if (veil >= 1f) return 1f
        val u = part / (PARTS - 1f)
        val start = (1f - u) * (1f - WINDOW)
        return ((veil - start) / WINDOW).coerceIn(0f, 1f)
    }

    /** Maßstab des Teils (1 = voll, 0 = fort). */
    fun shrink(l: Float): Float = if (l <= 0f) 1f else if (l >= 1f) 0f else 1f - smooth(l)

    /** Helligkeit des Leuchtüberzugs 0..1 (schon schwach bei 0, voll mitten im Zerfall). */
    fun sheen(l: Float): Float {
        if (l <= 0f || l >= 1f) return 0f
        return smooth(l * 3f) * (1f - 0.35f * l) * (0.35f + 0.65f * particlesAllowed())
    }

    // --- Lage der Teile je Drache (vom Renderer jedes Bild geschrieben) ----------------------------------------------

    private class Body {
        val pos = Array(PARTS) { Vec3d.ZERO }
        val size = FloatArray(PARTS)
        val l = FloatArray(PARTS)
        val prev = FloatArray(PARTS) { -1f }
        var seen = 0L
    }

    private val bodies = HashMap<Int, Body>()

    /** Ob der Drache [id] gerade beobachtet wird (der Renderer meldet dann auch Schleier 0, um das Ende zu sehen). */
    fun tracks(id: Int): Boolean = id in bodies

    /** Lage und Fortschritt aller Teile in Weltkoordinaten, aus dem fertig berechneten Körper. */
    fun update(id: Int, time: Long, s: NivarisRenderState, veil: Float) {
        if (bodies.size > 8 && id !in bodies) bodies.clear()
        val b = bodies.getOrPut(id) { Body() }
        b.seen = time
        val sc = NivarisShape.SCALE
        // Kopf: Mitte vor dem Halsgelenk (Spalte z des Kopfrahmens zeigt nach hinten).
        val hf = s.headFrame
        val hc = NivarisShape.HEAD_CENTER
        b.pos[0] = Vec3d(s.x + s.px[0] - hf[6] * hc, s.y + s.py[0] - hf[7] * hc, s.z + s.pz[0] - hf[8] * hc)
        b.size[0] = 1.1f * sc
        for (i in 0 until NivarisShape.SEGMENTS) {
            b.pos[i + 1] = Vec3d(s.x + (s.px[i] + s.px[i + 1]) * 0.5, s.y + (s.py[i] + s.py[i + 1]) * 0.5, s.z + (s.pz[i] + s.pz[i + 1]) * 0.5)
            b.size[i + 1] = 0.75f * sc * s.girth[i]
        }
        val last = NivarisShape.SEGMENTS
        b.pos[PARTS - 1] = Vec3d(s.x + s.px[last], s.y + s.py[last], s.z + s.pz[last])
        b.size[PARTS - 1] = 0.8f * sc
        for (j in 0 until PARTS) b.l[j] = progress(veil, j)
    }

    // --- Funken und Splitter --------------------------------------------------------------------------------------

    private class Burst(val pos: Vec3d, val size: Double, val dissolving: Boolean, seed: Int) {
        var age = 0
        val life = if (dissolving) 20 else 16
        val shards: List<Vec3d>
        val spins: DoubleArray

        init {
            val r = Random(seed)
            val n = 3 + (4 * particlesAllowed()).toInt()
            shards = List(n) { Vec3d(r.nextDouble() * 2 - 1, r.nextDouble() * 1.4 - 0.4, r.nextDouble() * 2 - 1).let { if (it.lengthSquared() < 1e-3) AriusFx.UP else it.normalize() } }
            spins = DoubleArray(n) { 0.15 + r.nextDouble() * 0.3 }
        }
    }

    private val bursts = ArrayList<Burst>()
    private const val MAX_BURSTS = 96
    private val rnd = Random(41)

    private fun gauss(): Double = sqrt(-2.0 * kotlin.math.ln(rnd.nextDouble().coerceAtLeast(1e-9))) * cos(2.0 * PI * rnd.nextDouble())

    fun tick(world: ClientWorld) {
        bursts.removeIf { ++it.age >= it.life }
        if (bodies.isEmpty()) return
        val now = world.time
        val fx = particlesAllowed()
        val cam = net.minecraft.client.MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val iter = bodies.entries.iterator()
        while (iter.hasNext()) {
            val b = iter.next().value
            if (now - b.seen > 10 || now < b.seen - 40) { iter.remove(); continue }
            var busy = false
            for (j in 0 until PARTS) {
                val l = b.l[j]
                val p = b.prev[j]
                b.prev[j] = l
                if (l > 0f && l < 1f) busy = true
                if (p < 0f || l == p) continue
                val dissolving = l > p
                val pos = b.pos[j]
                val size = b.size[j].toDouble()
                // Mitten im Fenster: Splitter und Lichtschein an der Stelle des Glieds.
                val crossed = if (dissolving) p < 0.45f && l >= 0.45f else p > 0.55f && l <= 0.55f
                if (crossed && fx > 0f && bursts.size < MAX_BURSTS) bursts.add(Burst(pos, size, dissolving, rnd.nextInt()))
                // Funken und Schnee nur nahe der Kamera (Vanilla-Partikel reichen nicht weiter).
                if (l <= 0f || l >= 1f || cam.squaredDistanceTo(pos) > 48.0 * 48.0) continue
                val n = (2.2f * fx + rnd.nextFloat()).toInt()
                repeat(n) { k ->
                    val off = Vec3d(gauss(), gauss() * 0.7, gauss()).multiply(size * 0.45)
                    val type = if ((k + j) % 2 == 0) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE
                    if (dissolving) {
                        val q = pos.add(off)
                        world.addParticleClient(type, q.x, q.y, q.z, off.x * 0.03, 0.04 + rnd.nextDouble() * 0.06, off.z * 0.03)
                    } else {
                        // Entstehen: Kristalle ziehen aus dem Umkreis zur Stelle des Glieds.
                        val q = pos.add(off.multiply(2.6))
                        val v = pos.subtract(q).multiply(0.09)
                        world.addParticleClient(type, q.x, q.y, q.z, v.x, v.y, v.z)
                    }
                }
            }
            if (!busy && b.l.all { it <= 0f }) iter.remove()
        }
    }

    fun render(d: VfxDraw) {
        if (bodies.isEmpty() && bursts.isEmpty()) return
        val fx = particlesAllowed()
        // Lichtschein über den Gliedern, die gerade zerfallen oder entstehen (auch in der Ferne sichtbar).
        for (b in bodies.values) {
            for (j in 0 until PARTS) {
                val l = b.l[j]
                if (l <= 0f || l >= 1f) continue
                val glow = sheen(l) * (1f - 0.5f * l)
                if (glow <= 0.02f) continue
                val pos = b.pos[j]
                val far = max(1.0, pos.distanceTo(d.cam) / 48.0)
                val size = b.size[j] * (1.1 + 0.9 * l) * far
                d.billboard(VfxLayers.GLOW_ADD, pos, size, withAlpha(AriusFx.ICE, glow * 0.55f))
                if (fx > 0f) d.billboard(VfxLayers.FLARE_ADD, pos, size * 0.45, withAlpha(AriusFx.WHITE, glow * 0.6f * fx))
            }
        }
        for (s in bursts) burst(d, s, s.age + d.tickDelta)
    }

    private fun burst(d: VfxDraw, s: Burst, t: Float) {
        val k = (t / s.life).coerceIn(0f, 1f)
        val far = max(1.0, s.pos.distanceTo(d.cam) / 48.0)
        val fx = particlesAllowed()
        if (s.dissolving) {
            // Aufblitzen, dann steigen die Splitter auseinander und verglühen.
            val flash = (1f - k) * (1f - k)
            d.billboard(VfxLayers.FLARE_ADD, s.pos, s.size * (0.5 + 0.9 * k) * far, withAlpha(AriusFx.WHITE, flash * 0.8f * fx))
            val out = 1.0 - (1.0 - k) * (1.0 - k)
            for ((i, dir) in s.shards.withIndex()) {
                val p = s.pos.add(dir.multiply(s.size * (0.25 + 1.3 * out))).add(0.0, s.size * 0.7 * k * k, 0.0)
                shard(d, p, dir, s.spins[i] * t, 0.09 * s.size * far, (1f - k) * fx)
            }
        } else {
            // Splitter ziehen sich zusammen, zuletzt leuchtet die Stelle kurz auf.
            val inK = smooth(k / 0.75f)
            for ((i, dir) in s.shards.withIndex()) {
                val p = s.pos.add(dir.multiply(s.size * 1.7 * (1.0 - inK)))
                shard(d, p, dir, s.spins[i] * t, 0.09 * s.size * far, smooth(k * 4f) * (1f - smooth((k - 0.75f) / 0.25f)) * fx)
            }
            val flash = smooth((k - 0.6f) / 0.2f) * (1f - smooth((k - 0.8f) / 0.2f))
            if (flash > 0.01f) d.billboard(VfxLayers.FLARE_ADD, s.pos, s.size * 1.1 * far, withAlpha(AriusFx.WHITE, flash * 0.8f * fx))
        }
    }

    private fun shard(d: VfxDraw, p: Vec3d, dir: Vec3d, spin: Double, size: Double, alpha: Float) {
        if (alpha <= 0.02f) return
        var side = dir.crossProduct(AriusFx.UP)
        if (side.lengthSquared() < 1e-6) side = AriusFx.X
        side = side.normalize()
        val up = side.crossProduct(dir).normalize()
        val ax = dir.multiply(size * 2.4)
        val ay = side.multiply(cos(spin) * size).add(up.multiply(sin(spin) * size))
        val az = ax.crossProduct(ay).let { if (it.lengthSquared() < 1e-9) up else it.normalize() }.multiply(size * 0.55)
        d.box(VfxLayers.SHARD_ALPHA, p, ax, ay, az, withAlpha(0xFFD8F2FF.toInt(), alpha * 0.85f))
        d.billboard(VfxLayers.GLOW_ADD, p, size * 4.0, withAlpha(AriusFx.ICE, alpha * 0.5f))
    }

    fun reset() {
        bodies.clear()
        bursts.clear()
    }
}

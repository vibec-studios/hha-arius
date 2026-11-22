package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.arena.HuntRoute
import dev.henny.hha_arius.net.HuntFx
import dev.henny.hha_arius.net.HuntFxS2C
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Schreckmomente und Schauplätze der Jagd am Client (seit 1.10.0). Solvanes Auge ist eine eigene Zeichnung (kein Modell
 * des Drachen): Lederlider, Iris aus Frostlicht mit Schlitzpupille, die dem Spieler folgt, blinzelt, verschwindet.
 * Dazu Frostatem durch Risse, der Atem, der eine Höhle füllt, die Lawine, Felsbrocken, stürzende Planken, Arius' Schnitt
 * durch den Fels und das Grollen. Außerdem die Stimmung in Höhlen (Eiszapfen in der Kriechpassage, glimmende Eisadern,
 * Windschlieren im Windtunnel).
 */
object HuntScares {

    private abstract class Fx(val life: Int) {
        var age = 0
        open fun tick(world: ClientWorld) = Unit
        abstract fun render(d: VfxDraw, t: Float)
    }

    private val list = ArrayList<Fx>()
    private val rnd = Random()
    private var time = 0L

    fun reset() {
        list.clear()
        fillA0 = 0.0; fillA1 = 0.0; fillSpeed = 0.0; fillLeft = 0
    }

    private fun origin(x: Double): BlockPos {
        val slot = Math.floorDiv(floor(x).toInt() + ArenaLayout.SLOT_SPACING / 2, ArenaLayout.SLOT_SPACING)
        return ArenaLayout.origin(slot)
    }

    // --- Pakete -----------------------------------------------------------------------------------------------------

    fun handle(p: HuntFxS2C) {
        val pos = Vec3d(p.x, p.y, p.z)
        val dir = Vec3d(p.dx, p.dy, p.dz)
        when (HuntFx.entries.getOrNull(p.kind) ?: return) {
            HuntFx.STATE -> {
                HuntCine.state(p.a > 0.5f, p.e1)
                if (p.a <= 0.5f) RopeBridges.clearDrops()
            }
            HuntFx.NEAR_MISS -> HuntCine.nearMiss(pos)
            HuntFx.IMPACT -> HuntCine.impact(p.a)
            HuntFx.LETTERBOX -> HuntCine.letterbox(p.life)
            HuntFx.FADE -> HuntCine.fade(p.a, p.b, p.life, p.color)
            HuntFx.RUMBLE -> {
                HuntCine.rumble(p.a * near(pos, 50.0), p.life)
                add(Rumble(pos, p.life))
            }
            HuntFx.BOULDER -> add(Boulder(pos, dir, p.a.toDouble(), p.life.coerceAtLeast(3), p.b > 0.5f))
            HuntFx.PLANKS_SHAKE -> {
                RopeBridges.shake(pos, p.a.toDouble(), p.life)
                HuntCine.playAt(SoundEvents.ENTITY_CREAKING_TWITCH, pos, 1.2f, 0.6f)
            }
            HuntFx.PLANKS_FALL -> add(Planks(pos, dir, p.a.toInt().coerceIn(1, 6)))
            HuntFx.EYE -> {
                add(Eye(pos, dir, p.a.toDouble(), p.life))
                HuntCine.scare(near(pos, 30.0))
            }
            HuntFx.CRACK_BREATH -> add(Crack(pos, dir, p.a.toInt(), p.life))
            HuntFx.BREATH_FILL -> {
                fillA0 = dir.x; fillA1 = dir.y; fillSpeed = p.a.toDouble(); fillLeft = p.life; fillAge = 0
                fillOrigin = origin(pos.x)
            }
            HuntFx.ROAR -> HuntCine.roar(p.a * near(pos, 60.0).coerceAtLeast(0.4f))
            HuntFx.AVALANCHE -> add(Avalanche(origin(pos.x), dir.x, dir.y, p.a.toDouble(), p.life))
            HuntFx.ROCK_CUT -> add(Seam(pos, dir, p.life))
            HuntFx.PORTAL_CHARGE -> HuntCine.portalCharge(p.a.toInt())
            HuntFx.BRIDGE_DROP -> RopeBridges.drop(p.a.toInt(), pos)
            HuntFx.ROPE_PROGRESS -> HuntCine.ropeProgress(p.a, p.life)
        }
    }

    private fun near(pos: Vec3d, r: Double): Float {
        val p = MinecraftClient.getInstance().player ?: return 0f
        return (1.0 - p.entityPos.distanceTo(pos) / r).coerceIn(0.0, 1.0).toFloat()
    }

    private fun add(fx: Fx) {
        if (list.size > 80) list.removeAt(0)
        list += fx
    }

    // --- Höhlenatem ---------------------------------------------------------------------------------------------------

    private var fillA0 = 0.0
    private var fillA1 = 0.0
    private var fillSpeed = 0.0
    private var fillLeft = 0
    private var fillAge = 0
    private var fillOrigin: BlockPos = BlockPos.ORIGIN
    private var breathK = 0f

    /** 0..1: wie dicht der eigene Spieler im Atem steht (für Linse und Nebel). */
    fun breathAt(): Float = breathK

    private fun fillFront(td: Float): Double = (fillA0 + fillSpeed * (fillAge + td)).coerceAtMost(fillA1)

    // --- Tick und Zeichnen ----------------------------------------------------------------------------------------------

    fun tick(world: ClientWorld) {
        time++
        list.removeIf { it.tick(world); ++it.age >= it.life }
        val p = MinecraftClient.getInstance().player
        var want = 0f
        if (fillLeft > 0) {
            fillLeft--
            fillAge++
            if (p != null) {
                val rx = floor(p.x).toInt() - fillOrigin.x
                val rz = floor(p.z).toInt() - fillOrigin.z
                val i = HuntRoute.indexAtHeight(rx, rz, p.y - fillOrigin.y, 3.5, 3.0)
                if (i >= 0 && HuntRoute.kind(i) == HuntRoute.TUNNEL && HuntRoute.arc(i) in fillA0..fillFront(0f)) want = 1f
                else if (i >= 0 && HuntRoute.arc(i) in fillA0..fillFront(0f) + 6.0) want = 0.35f
            }
            if (fillLeft == 0) fillAge = 0
        }
        breathK += (want - breathK) * 0.12f
        if (world.registryKey == ArenaDimension.WORLD && p != null) ambience(world, p.entityPos)
    }

    fun render(d: VfxDraw) {
        val world = MinecraftClient.getInstance().world ?: return
        if (world.registryKey != ArenaDimension.WORLD) return
        for (fx in list) fx.render(d, fx.age + d.tickDelta)
        if (fillLeft > 0) renderFill(d)
        renderCave(d)
    }

    private fun renderFill(d: VfxDraw) {
        val front = fillFront(d.tickDelta)
        val o = fillOrigin
        var s = fillA0
        var k = 0
        while (s <= front) {
            val i = HuntRoute.indexOfArc(s)
            val c = Vec3d(o.x + HuntRoute.x(i), o.y + HuntRoute.standY(i) + 1.6, o.z + HuntRoute.z(i))
            if (c.squaredDistanceTo(d.cam) < 48.0 * 48.0) {
                val edge = (1.0 - (front - s) / 6.0).coerceIn(0.0, 1.0)
                val a = (0.22 + 0.25 * edge).toFloat()
                val wob = sin((time + d.tickDelta) * 0.05 + k).toDouble()
                d.billboard(VfxLayers.CLOUD_ALPHA, c.add(wob * 0.6, wob * 0.3, -wob * 0.4), 3.5 + edge * 2.0, withAlpha(0xFFDCEEFF.toInt(), a), k * 0.7 + (time + d.tickDelta) * 0.01)
                if (edge > 0.5) d.billboard(VfxLayers.GLOW_ADD, c, 2.2, withAlpha(0xFF9FD8FF.toInt(), 0.15f * edge.toFloat()), 0.0)
            }
            s += 1.4
            k++
        }
    }

    // --- Höhlenstimmung -------------------------------------------------------------------------------------------

    private class Glow(val pos: Vec3d, val life: Int, val color: Int) {
        var age = 0
    }

    private val glows = ArrayList<Glow>()
    private var windSound = 0

    /** Höhle, in der die Kamera steht (oder null) und ihr Ursprung. */
    internal fun caveAtCamera(): HuntRoute.Cave? {
        val mc = MinecraftClient.getInstance()
        val cam = mc.gameRenderer.camera.cameraPos
        val o = origin(cam.x)
        val i = HuntRoute.indexAtHeight(floor(cam.x).toInt() - o.x, floor(cam.z).toInt() - o.z, cam.y - o.y - 1.6, 3.5, 6.0)
        return if (i >= 0 && HuntRoute.kind(i) == HuntRoute.TUNNEL) HuntRoute.caveOf(i) else null
    }

    private fun ambience(world: ClientWorld, at: Vec3d) {
        glows.removeIf { ++it.age >= it.life }
        val cave = caveAtCamera() ?: return
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        // glimmende Adern/Kristalle: Eisflächen mit Luft daneben
        if (glows.size < (50 * fx).toInt()) repeat(6) {
            val bp = BlockPos.ofFloored(at.x + (rnd.nextDouble() - 0.5) * 18, at.y + (rnd.nextDouble() - 0.3) * 8, at.z + (rnd.nextDouble() - 0.5) * 18)
            val st = world.getBlockState(bp)
            if (!(st.isOf(Blocks.BLUE_ICE) || st.isOf(Blocks.PACKED_ICE) || st.isOf(Blocks.ICE))) return@repeat
            for (dir in Direction.entries) {
                if (!world.getBlockState(bp.offset(dir)).isAir) continue
                val face = Vec3d.ofCenter(bp).add(Vec3d.of(dir.vector).multiply(0.52))
                val col = if (cave.theme == HuntRoute.Theme.DARK) 0xFF6FC8FF.toInt() else 0xFFCFF1FF.toInt()
                glows += Glow(face, 30 + rnd.nextInt(40), col)
                break
            }
        }
        // Windtunnel: Heulen
        if (cave.theme == HuntRoute.Theme.WIND && --windSound <= 0) {
            windSound = 50 + rnd.nextInt(40)
            HuntCine.playAt(SoundEvents.ENTITY_BREEZE_WHIRL, at, 0.8f, 0.4f + rnd.nextFloat() * 0.2f)
        }
        // Kriechpassage: Tropfen
        if (cave.theme == HuntRoute.Theme.CRAWL && rnd.nextInt(14) == 0) {
            world.addParticleClient(ParticleTypes.FALLING_WATER, at.x + (rnd.nextDouble() - 0.5) * 6, at.y + 1.8, at.z + (rnd.nextDouble() - 0.5) * 6, 0.0, 0.0, 0.0)
        }
    }

    private fun renderCave(d: VfxDraw) {
        val t = time + d.tickDelta
        for (g in glows) {
            val k = sin((g.age + d.tickDelta) / g.life * PI).toFloat()
            d.billboard(VfxLayers.FLARE_ADD, g.pos, 0.35 + 0.25 * k, withAlpha(g.color, 0.55f * k), 0.0)
            d.billboard(VfxLayers.GLOW_ADD, g.pos, 1.2, withAlpha(g.color, 0.12f * k), 0.0)
        }
        val cave = caveAtCamera() ?: return
        val cam = d.cam
        val o = origin(cam.x)
        when (cave.theme) {
            HuntRoute.Theme.CRAWL, HuntRoute.Theme.CRYSTAL, HuntRoute.Theme.FALL -> {
                // Eiszapfen an der Decke über dem Gang (fest je Spalte, nur Darstellung)
                val cx = floor(cam.x).toInt() - o.x
                val cz = floor(cam.z).toInt() - o.z
                for (x in cx - 10..cx + 10) for (z in cz - 10..cz + 10) {
                    val h = HuntRoute.hit(x, z)
                    if (h < 0) continue
                    val i = HuntRoute.hitIndex(h)
                    if (i !in cave.i0..cave.i1 || HuntRoute.hitLateral(h) > cave.theme.lane) continue
                    val r = FarPlan.h32(x, 0, z, 1601)
                    if (r > (if (cave.theme == HuntRoute.Theme.CRAWL) 0.4 else 0.18)) continue
                    val ceil = o.y + floor(HuntRoute.y(i)).toInt() + cave.theme.height + 1.0
                    val len = 0.4 + FarPlan.h32(x, 1, z, 1603) * (if (cave.theme == HuntRoute.Theme.CRAWL) 0.7 else 1.6)
                    val top = Vec3d(o.x + x + 0.2 + r * 1.5, ceil, o.z + z + 0.3 + r)
                    val tip = top.add(0.0, -len, 0.0)
                    d.band(VfxLayers.SHARD_ALPHA, top, tip, d.camRight, 0.16, withAlpha(0xFFE6F6FF.toInt(), 0.85f), withAlpha(0xFFBFE6FF.toInt(), 0.6f))
                    val tw = (0.5 + 0.5 * sin(t * 0.2 + x * 1.3 + z)).toFloat()
                    d.billboard(VfxLayers.FLARE_ADD, tip, 0.08 + 0.05 * tw, withAlpha(0xFFFFFFFF.toInt(), 0.5f * tw), 0.0)
                }
            }
            HuntRoute.Theme.WIND -> {
                // Windschlieren entgegen der Laufrichtung
                val i0 = HuntRoute.indexAtHeight(floor(cam.x).toInt() - o.x, floor(cam.z).toInt() - o.z, cam.y - o.y - 1.6, 3.5, 6.0)
                if (i0 >= 0) {
                    val (tx, tz) = HuntRoute.tangent(i0)
                    for (k in 0 until 14) {
                        val ph = ((t * 0.045 + k * 0.137) % 1.0)
                        val off = ((k * 37) % 11) / 11.0 - 0.5
                        val base = Vec3d(o.x + HuntRoute.x(i0) - tz * off * 6.0, o.y + HuntRoute.standY(i0) + 0.6 + (k % 5) * 0.8, o.z + HuntRoute.z(i0) + tx * off * 6.0)
                        val a = base.add(tx * (12.0 - ph * 24.0), 0.0, tz * (12.0 - ph * 24.0))
                        val b = a.add(-tx * 3.5, 0.0, -tz * 3.5)
                        d.band(VfxLayers.FLOW_ADD, a, b, Vec3d(0.0, 1.0, 0.0), 0.12, withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFEAF6FF.toInt(), 0.3f * sin(ph * PI).toFloat()), uScale = 0.3f, uOffset = -t.toFloat() * 0.08f)
                    }
                }
            }
            else -> Unit
        }
    }

    // --- Effekte ----------------------------------------------------------------------------------------------------

    private val ms = MatrixStack()

    private fun block(d: VfxDraw, state: BlockState, center: Vec3d, size: Double, angle: Float, axis: org.joml.Vector3f) {
        val mc = MinecraftClient.getInstance()
        val world = mc.world ?: return
        ms.push()
        ms.translate(center.x - d.cam.x, center.y - d.cam.y, center.z - d.cam.z)
        ms.multiply(RotationAxis.of(axis).rotation(angle))
        ms.scale(size.toFloat(), size.toFloat(), size.toFloat())
        ms.translate(-0.5f, -0.5f, -0.5f)
        val light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(center))
        mc.blockRenderManager.renderBlockAsEntity(state, ms, d.consumers, light, OverlayTexture.DEFAULT_UV)
        ms.pop()
    }

    /**
     * Solvanes Auge hinter dem Felsspalt (seit 1.12.0 aus einem eigenen Atlas, tools/assets/solvane_eye.py): Erst ist da
     * nur Schwärze. Das Lid hebt sich langsam, die weite Pupille sucht den Gang ab, findet die Kamera, zieht sich schlagartig
     * zum Schlitz zusammen und starrt. Ein langsames Blinzeln, dann weicht das Auge in die Dunkelheit zurück.
     */
    private class Eye(val at: Vec3d, val into: Vec3d, val size: Double, life: Int) : Fx(life) {
        private val lock = life * 0.45f

        private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

        private fun openness(t: Float): Float {
            val rise = 0.62f * ease((t - life * 0.12f) / (life * 0.26f)) + 0.38f * ease((t - lock) / 3f)
            val blink = abs(t - life * 0.74f) / (life * 0.045f)
            val fall = ease((life - 2f - t) / (life * 0.15f))
            return rise * blink.coerceIn(0f, 1f) * fall
        }

        private fun quad(v: VertexConsumer, d: VfxDraw, c: Vec3d, r: Vec3d, u: Vec3d, n: Vec3d, u0: Float, v0: Float, u1: Float, v1: Float, argb: Int) {
            fun put(p: Vec3d, tu: Float, tv: Float) {
                v.vertex((p.x - d.cam.x).toFloat(), (p.y - d.cam.y).toFloat(), (p.z - d.cam.z).toFloat()).color(argb).texture(tu, tv)
                    .overlay(OverlayTexture.DEFAULT_UV).light(0xF000F0).normal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
            }
            put(c.subtract(r).add(u), u0, v0)
            put(c.subtract(r).subtract(u), u0, v1)
            put(c.add(r).subtract(u), u1, v1)
            put(c.add(r).add(u), u1, v0)
        }

        override fun render(d: VfxDraw, t: Float) {
            val fx = AriusClientConfig.fx.coerceIn(0.35f, 1f)
            val presence = ease(t / 6f) * ease((life - t) / 5f)
            if (presence <= 0.01f) return
            val open = openness(t)
            val n = into.normalize()
            var right = n.crossProduct(Vec3d(0.0, 1.0, 0.0))
            if (right.lengthSquared() < 1e-4) right = Vec3d(1.0, 0.0, 0.0)
            right = right.normalize()
            val up = right.crossProduct(n).normalize().let { if (it.y < 0) it.negate() else it }
            // Beim Rückzug sinkt das Auge in den Spalt.
            val back = 0.5 * (1f - ease((life - t) / (life * 0.18f)))
            val c = at.add(n.multiply(-0.08 - back))
            val alpha = (presence * 255).toInt().coerceIn(0, 255) shl 24
            val white = alpha or 0xFFFFFF
            val v = d.consumers.getBuffer(LAYER)

            // Schwärze der Höhle hinter dem Auge: kein Schnee, kein Himmel im Spalt.
            d.disc(VfxLayers.SHARD_ALPHA, c.add(n.multiply(-0.3)), right.multiply(size * 2.5), up.multiply(size * 1.9), 1.0, withAlpha(0xFF04070C.toInt(), 0.98f * presence), 0.0)

            // Blick: erst suchend, ab [lock] fest auf die Kamera.
            val to = d.cam.subtract(c)
            val toX = (to.dotProduct(right) / 7.0).coerceIn(-1.0, 1.0)
            val toY = (to.dotProduct(up) / 5.0).coerceIn(-1.0, 1.0)
            val found = ease((t - lock) / 2.5f).toDouble()
            val scanX = sin(t * 0.21) * 0.8 + sin(t * 0.47) * 0.2
            val scanY = sin(t * 0.13 + 1.0) * 0.35
            val lookX = (scanX + (toX - scanX) * found) * size * 0.3
            val lookY = (scanY + (toY - scanY) * found) * size * 0.14
            val ball = c.add(right.multiply(lookX)).add(up.multiply(lookY))
            quad(v, d, ball.add(n.multiply(0.01)), right.multiply(size * 1.05), up.multiply(size * 1.05), n, 0f, 0f, 0.5f, 1f, white)

            // Schlitzpupille: weit im Dunkeln, schnappt zusammen, sobald sie dich sieht.
            val snap = ease((t - lock) / 2f)
            val slit = (0.34f + (0.075f - 0.34f) * snap + 0.012f * sin(t * 0.3f)).toDouble()
            quad(v, d, ball.add(n.multiply(0.03)), right.multiply(size * slit), up.multiply(size * 0.7), n, 0.5f, 0f, 0.75f, 1f, white)

            if (open > 0.03f) {
                d.billboard(VfxLayers.GLOW_ADD, ball.add(n.multiply(0.12)), size * (1.2 + 0.5 * snap), withAlpha(0xFF3FA8F0.toInt(), (0.13f + 0.13f * snap) * open * presence * fx), 0.0)
                d.billboard(VfxLayers.FLARE_ADD, ball.add(right.multiply(size * 0.24)).add(up.multiply(size * 0.26)).add(n.multiply(0.1)), size * 0.15 * open, withAlpha(0xFFF4FCFF.toInt(), 0.85f * open * presence), 0.0)
            }

            // Lider: Die Atlas-Kante ist mandelförmig, geschlossen überlappen sie sich in der Mitte.
            val halfW = size * 1.5
            val h = size * 1.7
            val shift = -0.45 * h * (1f - open)
            val front = c.add(n.multiply(0.07))
            quad(v, d, front.add(up.multiply(shift + h / 2)), right.multiply(halfW), up.multiply(h / 2), n, 0.75f, 0f, 1f, 0.5f, white)
            quad(v, d, front.add(up.multiply(-shift - h / 2)), right.multiply(halfW), up.multiply(h / 2), n, 0.75f, 0.5f, 1f, 1f, white)
        }

        private companion object {
            val LAYER: RenderLayer = RenderLayers.entityTranslucent(dev.henny.hha_arius.HhaArius.id("textures/fx/solvane_eye.png"))
        }
    }

    /** Frostatem durch einen Riss: Leuchten, Zischen, dann der Strahl quer über den Gang. */
    private class Crack(val at: Vec3d, val end: Vec3d, val charge: Int, life: Int) : Fx(life) {
        private var fired = false

        override fun tick(world: ClientWorld) {
            if (age < charge) {
                if (age % 3 == 0) world.addParticleClient(ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, (end.x - at.x) * 0.01, 0.0, (end.z - at.z) * 0.01)
                if (age == 2) HuntCine.playAt(SoundEvents.BLOCK_POWDER_SNOW_STEP, at, 1.5f, 0.5f)
            } else if (!fired) {
                fired = true
                HuntCine.scare(0.7f)
                val v = end.subtract(at)
                repeat(40) {
                    val k = it / 40.0
                    val p = at.add(v.multiply(k))
                    world.addParticleClient(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, v.x * 0.03, 0.02, v.z * 0.03)
                    world.addParticleClient(ParticleTypes.CLOUD, p.x, p.y, p.z, v.x * 0.01, 0.0, v.z * 0.01)
                }
            }
        }

        override fun render(d: VfxDraw, t: Float) {
            if (t < charge) {
                val k = (t / charge).coerceIn(0f, 1f)
                d.billboard(VfxLayers.GLOW_ADD, at, 0.6 + 0.8 * k, withAlpha(0xFF7FD0FF.toInt(), 0.25f + 0.5f * k), 0.0)
                d.billboard(VfxLayers.FLARE_ADD, at, 0.3 + 0.3 * k, withAlpha(0xFFFFFFFF.toInt(), 0.4f * k), 0.0)
                return
            }
            val k = (1f - (t - charge) / (life - charge)).coerceIn(0f, 1f)
            d.band(VfxLayers.FLOW_ADD, at, end, d.camUp, 0.9 * k + 0.3, withAlpha(0xFFE8F8FF.toInt(), 0.85f * k), withAlpha(0xFF9FD8FF.toInt(), 0.4f * k), uScale = 0.4f, uOffset = -t * 0.2f)
            d.band(VfxLayers.GLOW_ADD, at, end, d.camUp, 1.8 * k, withAlpha(0xFF6FC0FF.toInt(), 0.3f * k), withAlpha(0xFF6FC0FF.toInt(), 0.1f * k))
        }
    }

    /** Lawine: Schneewalze, die der Route folgt (Bogenlänge a0 → a1). */
    private class Avalanche(val o: BlockPos, val a0: Double, val a1: Double, val speed: Double, life: Int) : Fx(life) {
        private fun front(t: Float) = (a0 + speed * t).coerceAtMost(a1)

        override fun tick(world: ClientWorld) {
            val p = MinecraftClient.getInstance().player ?: return
            val i = HuntRoute.indexOfArc(front(age.toFloat()))
            val c = Vec3d(o.x + HuntRoute.x(i), o.y + HuntRoute.standY(i), o.z + HuntRoute.z(i))
            val dist = c.distanceTo(p.entityPos)
            if (dist < 30.0 && age % 4 == 0) HuntCine.rumble(((30.0 - dist) / 30.0).toFloat() * 0.7f, 6)
            if (age % 2 == 0) world.addParticleClient(BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultState), c.x, c.y + 1.0, c.z, 0.0, 0.3, 0.0)
        }

        override fun render(d: VfxDraw, t: Float) {
            val fr = front(t)
            val tt = t.toDouble()
            for (k in 0 until 16) {
                val s = fr - k * 0.8
                if (s < a0) break
                val i = HuntRoute.indexOfArc(s)
                val (tx, tz) = HuntRoute.tangent(i)
                val side = ((k * 53) % 9) / 9.0 - 0.5
                val c = Vec3d(o.x + HuntRoute.x(i) - tz * side * 7.0, o.y + HuntRoute.standY(i) + 0.8 + (k % 3) * 0.9 + sin(tt * 0.3 + k) * 0.4, o.z + HuntRoute.z(i) + tx * side * 7.0)
                if (c.squaredDistanceTo(d.cam) > 90.0 * 90.0) continue
                val a = (0.75f - k * 0.04f).coerceAtLeast(0.1f)
                d.billboard(VfxLayers.CLOUD_ALPHA, c, 4.5 + k * 0.25, withAlpha(0xFFF4F8FC.toInt(), a), tt * 0.05 + k)
                if (k < 5) d.billboard(VfxLayers.SMOKE_ALPHA, c.add(0.0, 1.5, 0.0), 6.0, withAlpha(0xFFFFFFFF.toInt(), 0.35f), -tt * 0.04 + k)
            }
        }
    }

    /** Fallender Felsbrocken (oder Eiszapfen, [ice]). */
    private class Boulder(val from: Vec3d, val to: Vec3d, val size: Double, life: Int, val ice: Boolean) : Fx(life + 1) {
        private val spin = Random().nextFloat() * 6f
        private var landed = false

        override fun tick(world: ClientWorld) {
            if (!landed && age >= life - 1) {
                landed = true
                val st = if (ice) Blocks.PACKED_ICE.defaultState else Blocks.STONE.defaultState
                repeat(14) { world.addParticleClient(BlockStateParticleEffect(ParticleTypes.BLOCK, st), to.x, to.y, to.z, (Math.random() - 0.5) * 0.4, Math.random() * 0.3, (Math.random() - 0.5) * 0.4) }
                HuntCine.playAt(if (ice) SoundEvents.BLOCK_GLASS_BREAK else SoundEvents.BLOCK_STONE_FALL, to, 1.5f, if (ice) 1.2f else 0.6f)
                val p = MinecraftClient.getInstance().player
                if (p != null && p.entityPos.distanceTo(to) < 10.0) HuntCine.impact(0.3f)
            }
        }

        override fun render(d: VfxDraw, t: Float) {
            val k = (t / (life - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
            val e = k * k
            val p = from.lerp(to, e.toDouble())
            if (ice) {
                d.band(VfxLayers.SHARD_ALPHA, p.add(0.0, size * 1.6, 0.0), p, d.camRight, size * 0.45, withAlpha(0xFFE6F6FF.toInt(), 0.9f), withAlpha(0xFFBFE6FF.toInt(), 0.7f))
            } else {
                HuntScares.block(d, Blocks.COBBLESTONE.defaultState, p, size, spin + t * 0.3f, org.joml.Vector3f(0.6f, 1f, 0.3f).normalize())
            }
        }
    }

    /** Stürzende Planken einer Hängebrücke. */
    private class Planks(val at: Vec3d, val dir: Vec3d, n: Int) : Fx(40) {
        private val pieces = (0 until n).map {
            doubleArrayOf((Math.random() - 0.5) * 2.4, (Math.random() - 0.5) * 0.6, Math.random() * 6.0, (Math.random() - 0.5) * 0.15, (Math.random() - 0.5) * 0.15)
        }

        override fun tick(world: ClientWorld) {
            if (age == 0) HuntCine.playAt(SoundEvents.BLOCK_BAMBOO_WOOD_BREAK, at, 1.4f, 0.6f)
        }

        override fun render(d: VfxDraw, t: Float) {
            val side = Vec3d(-dir.z, 0.0, dir.x)
            for (p in pieces) {
                val pos = at.add(side.multiply(p[0] + p[3] * t)).add(dir.multiply(p[1] + p[4] * t)).add(0.0, -0.04 * t * t, 0.0)
                ms.push()
                ms.translate(pos.x - d.cam.x, pos.y - d.cam.y, pos.z - d.cam.z)
                ms.multiply(RotationAxis.of(org.joml.Vector3f(dir.x.toFloat(), 0.3f, dir.z.toFloat()).normalize()).rotation((p[2] + t * 0.25).toFloat()))
                ms.scale(0.44f, 0.1f, 3.2f)
                ms.translate(-0.5f, -0.5f, -0.5f)
                val mc = MinecraftClient.getInstance()
                val world = mc.world ?: return
                val light = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(pos))
                mc.blockRenderManager.renderBlockAsEntity(Blocks.SPRUCE_PLANKS.defaultState, ms, d.consumers, light, OverlayTexture.DEFAULT_UV)
                ms.pop()
            }
        }
    }

    /** Arius' Schnitt durch den Fels: eine leuchtende Naht wächst, dann Splitter. */
    private class Seam(val a: Vec3d, val b: Vec3d, life: Int) : Fx(life) {
        override fun tick(world: ClientWorld) {
            if (age == life - 2) {
                HuntCine.impact(0.5f)
                HuntCine.nearMiss(a.lerp(b, 0.5))
                repeat(24) {
                    val p = a.lerp(b, Math.random())
                    world.addParticleClient(ParticleTypes.END_ROD, p.x, p.y, p.z, (Math.random() - 0.5) * 0.3, Math.random() * 0.2, (Math.random() - 0.5) * 0.3)
                }
            }
        }

        override fun render(d: VfxDraw, t: Float) {
            val k = (t / life).coerceIn(0f, 1f)
            val tip = a.lerp(b, k.toDouble())
            d.band(VfxLayers.GLOW_ADD, a, tip, d.camUp, 0.35 + 0.4 * k, withAlpha(0xFFFFFFFF.toInt(), 0.9f), withAlpha(0xFF9FE0FF.toInt(), 0.7f))
            d.billboard(VfxLayers.FLARE_ADD, tip, 0.8 + 0.6 * k, withAlpha(0xFFFFFFFF.toInt(), 0.9f), t * 0.1)
        }
    }

    /** Grollen: Staub rieselt von der Decke um die Kamera. */
    private class Rumble(val at: Vec3d, life: Int) : Fx(life) {
        override fun tick(world: ClientWorld) {
            val p = MinecraftClient.getInstance().player ?: return
            if (p.entityPos.distanceTo(at) > 40.0) return
            repeat(3) {
                val x = p.x + (Math.random() - 0.5) * 8
                val z = p.z + (Math.random() - 0.5) * 8
                world.addParticleClient(BlockStateParticleEffect(ParticleTypes.FALLING_DUST, Blocks.STONE.defaultState), x, p.y + 3.2, z, 0.0, 0.0, 0.0)
            }
            if (age % 10 == 0) HuntCine.playAt(SoundEvents.BLOCK_POINTED_DRIPSTONE_FALL, p.entityPos.add(0.0, 2.0, 0.0), 0.6f, 0.6f)
        }

        override fun render(d: VfxDraw, t: Float) = Unit
    }

}

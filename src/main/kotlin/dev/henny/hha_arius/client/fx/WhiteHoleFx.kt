package dev.henny.hha_arius.client.fx

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.HhaArius.LOGGER
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.Framebuffer
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gl.UniformType
import net.minecraft.client.render.VertexFormats
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.OptionalInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Das weiße Loch (Setfähigkeit des Spielers und Arius' Signatur im Bosskampf). Eigener starker Effekt, entwickelt aus
 * dem schwarzen Loch von HHA Void: eine Vollbild-Linse ([WhiteLens], Shader `white_lens.fsh`) biegt Licht vom Kern
 * WEG, der Kern gleißt weiß, eine Scheibe aus Eis und goldenem Licht strömt spiralförmig nach außen; dazu Schnee, der
 * eingesogen wird, zwei Lichtsäulen entlang der Achse und beim Kollaps eine Brechungs-Schockwelle durch das Bild.
 *
 * Zeitachse (Ticks): 0–12 Öffnen, bis [pullTicks] Sog, danach Kollaps (Kern fällt in 5 Ticks zusammen, Blitz) und
 * Schockwelle (18 Ticks).
 */
class WhiteHoleFx(val center: Vec3d, private val radius: Double, private var pullTicks: Int, life: Int) : AriusFx(life) {

    // --- Seit 1.3.1 nach dem Vorbild des schwarzen Lochs aus HHA Void, aber umgekehrt (weißes Loch stößt aus) ---------
    // Echte Blöcke werden aus dem Boden gerissen, steigen auf und umkreisen den Kern; beim Kollaps fliegen sie nach
    // außen und zerschellen. Eiskristalle kreisen in der Scheibe, drei Lichtarme strömen spiralig nach außen, entlang
    // der Achse schießen Licht-Jets, Lichtfesseln greifen nach Wesen in der Nähe, am Boden glimmt ein Frostrunenkreis.

    private class Piece(val state: net.minecraft.block.BlockState, var pos: Vec3d, var vel: Vec3d, val axis: org.joml.Vector3f, val size: Float) {
        var prev = pos
        var thrown = false
        var landed = false
    }

    private val rng = java.util.Random(center.hashCode().toLong())
    private val pieces = ArrayList<Piece>()
    private var ground: Vec3d? = null
    private val crystals = List(8) { i ->
        floatArrayOf(2.6f + rng.nextFloat() * 3.2f, rng.nextFloat() * 6.283f, 0.05f + rng.nextFloat() * 0.04f, (rng.nextFloat() - 0.5f) * 0.9f, 0.3f + rng.nextFloat() * 0.25f, i.toFloat())
    }

    /** Der Server meldet den Kollaps (Bosskampf): ab jetzt fällt der Kern zusammen. */
    fun collapseNow() {
        if (pullTicks > age) pullTicks = age
        lifeLimit = age + 24
    }

    /** Frühzeitiges Ende nach dem Kollaps (Bosskampf). */
    var lifeLimit = Int.MAX_VALUE
        private set

    val finished: Boolean get() = age >= lifeLimit

    private val coreMax = (0.55 + radius * 0.07).coerceIn(0.5, 1.4)

    fun coreRadius(td: Float): Float {
        val t = age + td
        val open = easeOutBack((t / 12f).coerceIn(0f, 1f))
        val breath = 1f + 0.04f * sin(t * 0.35f)
        val collapse = if (t > pullTicks) (1f - (t - pullTicks) / 5f).coerceIn(0f, 1f).let { it * it } else 1f
        return (coreMax * open * breath * collapse).toFloat()
    }

    fun intensity(td: Float): Float {
        val t = age + td
        val end = minOf(life, lifeLimit).toFloat()
        return minOf((t / 10f).coerceIn(0f, 1f), ((end - t) / 10f).coerceIn(0f, 1f))
    }

    fun pull(td: Float): Float = ((age + td - 12f) / (pullTicks - 12f).coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun flash(td: Float): Float {
        val t = age + td
        val open = (1f - t / 6f).coerceIn(0f, 1f)
        val c = t - pullTicks
        val close = if (c in 0f..8f) (1f - c / 8f) else 0f
        val k = if (AriusClientConfig.flash) 1f else 0.25f
        return (open * open * 0.08f + close * close * 0.45f) * k
    }

    fun shock(td: Float): Triple<Float, Float, Float> {
        val c = age + td - pullTicks
        if (c < 0f) return Triple(0f, 1f, 0f)
        val s = (c / 18f).coerceIn(0f, 1f)
        return Triple((radius * 2.4 * (1 - (1 - s) * (1 - s))).toFloat(), 1.2f + 1.5f * s, (1f - s) * 1.4f)
    }

    fun diskNormal(td: Float): Vector3f {
        val t = (age + td) * 0.015f
        val tilt = 0.32f
        return Vector3f(sin(tilt) * cos(t), cos(tilt), sin(tilt) * sin(t)).normalize()
    }

    override fun tick(world: ClientWorld) {
        tickPieces(world)
        if (age > pullTicks) return
        val r = random()
        // Schnee und Licht werden eingesogen.
        repeat((10 * particlesAllowed()).toInt()) {
            val a = r.nextDouble() * 2 * PI
            val e = (r.nextDouble() - 0.5) * 1.2
            val d = radius * (0.8 + 0.6 * r.nextDouble())
            val p = center.add(cos(a) * cos(e) * d, sin(e) * d * 0.6, sin(a) * cos(e) * d)
            val v = center.subtract(p).multiply(0.07)
            world.addParticleClient(if (r.nextFloat() < 0.7f) ParticleTypes.SNOWFLAKE else ParticleTypes.END_ROD, p.x, p.y, p.z, v.x, v.y, v.z)
        }
    }

    /** Trümmer: aus dem Boden reißen, aufsteigen und kreisen lassen; beim Kollaps nach außen schleudern. */
    private fun tickPieces(world: ClientWorld) {
        if (ground == null) {
            var y = net.minecraft.util.math.BlockPos.ofFloored(center)
            repeat(16) { if (world.getBlockState(y.down()).isAir) y = y.down() }
            ground = Vec3d(center.x, y.y.toDouble(), center.z)
        }
        val g = ground ?: return
        val fx = particlesAllowed()
        if (age in 4 until pullTicks && age % 2 == 0 && pieces.size < (26 * fx).toInt()) {
            val a = rng.nextDouble() * 2 * PI
            val dist = 2.0 + kotlin.math.sqrt(rng.nextDouble()) * radius
            val bx = net.minecraft.util.math.BlockPos.ofFloored(g.x + cos(a) * dist, g.y - 1, g.z + sin(a) * dist)
            val st = world.getBlockState(bx)
            if (!st.isAir && st.fluidState.isEmpty && st.renderType == net.minecraft.block.BlockRenderType.MODEL) {
                val start = Vec3d(bx.x + 0.5, bx.y + 1.1, bx.z + 0.5)
                pieces += Piece(st, start, Vec3d((rng.nextDouble() - 0.5) * 0.1, 0.22 + rng.nextDouble() * 0.2, (rng.nextDouble() - 0.5) * 0.1),
                    org.joml.Vector3f(rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f, rng.nextFloat() - 0.5f).normalize(), 0.3f + rng.nextFloat() * 0.35f)
                world.addParticleClient(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, st), start.x, start.y, start.z, 0.0, 0.1, 0.0)
            }
        }
        val collapse = age >= pullTicks
        for (p in pieces) {
            p.prev = p.pos
            if (p.landed) continue
            if (collapse && !p.thrown) {
                p.thrown = true
                val away = p.pos.subtract(center).let { if (it.lengthSquared() < 1e-4) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
                p.vel = away.multiply(0.55 + rng.nextDouble() * 0.45).add(0.0, 0.25 + rng.nextDouble() * 0.3, 0.0)
            }
            if (p.thrown) {
                p.vel = p.vel.multiply(0.98).add(0.0, -0.06, 0.0)
                p.pos = p.pos.add(p.vel)
                val bp = net.minecraft.util.math.BlockPos.ofFloored(p.pos)
                if (p.vel.y < 0 && !world.getBlockState(bp).isAir) {
                    p.landed = true
                    repeat(6) { world.addParticleClient(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, p.state), p.pos.x, p.pos.y + 0.3, p.pos.z, (rng.nextDouble() - 0.5) * 0.2, 0.15, (rng.nextDouble() - 0.5) * 0.2) }
                }
                continue
            }
            // Zum Ring um den Kern ziehen und darum kreisen (weißes Loch hält sie in der Schwebe)
            val to = center.subtract(p.pos)
            val dist = to.length().coerceAtLeast(0.1)
            val pull = to.multiply(1.0 / dist)
            val swirl = Vec3d(-pull.z, 0.0, pull.x)
            val want = 3.2
            p.vel = p.vel.multiply(0.92).add(pull.multiply((dist - want) * 0.02)).add(swirl.multiply(0.06)).add(0.0, if (p.pos.y < center.y - 0.5) 0.02 else -0.005, 0.0)
            p.pos = p.pos.add(p.vel)
        }
        pieces.removeIf { it.landed && age % 20 == 0 }
    }

    private fun drawBlock(d: VfxDraw, state: net.minecraft.block.BlockState, pos: Vec3d, axis: org.joml.Vector3f, angle: Float, size: Float) {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val ms = MatrixStack()
        ms.translate(pos.x - d.cam.x, pos.y - d.cam.y, pos.z - d.cam.z)
        ms.multiply(net.minecraft.util.math.RotationAxis.of(axis).rotation(angle))
        ms.scale(size, size, size)
        ms.translate(-0.5f, -0.5f, -0.5f)
        val light = net.minecraft.client.render.WorldRenderer.getLightmapCoordinates(world, net.minecraft.util.math.BlockPos.ofFloored(pos))
        client.blockRenderManager.renderBlockAsEntity(state, ms, d.consumers, light, net.minecraft.client.render.OverlayTexture.DEFAULT_UV)
    }

    override fun render(d: VfxDraw, matrices: MatrixStack, t: Float) {
        val k = intensity(d.tickDelta)
        val core = coreRadius(d.tickDelta).toDouble()
        val time = age + d.tickDelta
        // Trümmer (echte Blockmodelle)
        for (p in pieces) {
            if (p.landed) continue
            val pos = p.prev.lerp(p.pos, d.tickDelta.toDouble())
            drawBlock(d, p.state, pos, p.axis, time * 0.25f, p.size)
        }
        val n0 = diskNormal(d.tickDelta)
        val nrm = Vec3d(n0.x.toDouble(), n0.y.toDouble(), n0.z.toDouble())
        val u = nrm.crossProduct(Vec3d(0.0, 0.0, 1.0)).let { if (it.lengthSquared() < 1e-4) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
        val v = nrm.crossProduct(u).normalize()
        if (core > 0.05) {
            // Eiskristalle in der Scheibe
            for (c in crystals) {
                val ang = c[1] + time * c[2]
                val pos = center.add(u.multiply((cos(ang) * c[0]).toDouble())).add(v.multiply((sin(ang) * c[0]).toDouble())).add(nrm.multiply(c[3].toDouble()))
                drawBlock(d, if (c[5].toInt() % 3 == 0) net.minecraft.block.Blocks.BLUE_ICE.defaultState else net.minecraft.block.Blocks.PACKED_ICE.defaultState,
                    pos, org.joml.Vector3f(0.3f, 1f, 0.2f).normalize(), time * 0.08f + c[1], c[4] * (core / coreMax).toFloat())
            }
            // Seit 1.4.2 (Crix: „sieht immer noch nicht cool aus“) klar lesbar aufgebaut wie ein echtes Schwarzes Loch in
            // Weiß: harte, gleißende Kugel, Photonenring, breite gebänderte Akkretionsscheibe, die nach außen strömt,
            // Spiralarme als Lichtbänder, Polar-Jets als pulsierende Lichtröhren; beim Kollaps eine Lichtkuppel.
            val pullK = pull(d.tickDelta)
            // Akkretionsscheibe: 8 Bänder, innen weiß-golden, außen eisblau und durchscheinend; jedes strömt nach außen.
            val diskR = radius * 0.95
            val bands = 8
            for (bnd in 0 until bands) {
                val f = bnd / (bands - 1f)
                val r0 = core * 1.7 + (diskR - core * 1.7) * f
                val r1 = r0 + (diskR - core * 1.7) / bands * 0.85
                val col = dev.henny.hha.client.vfx.lerpColor(if (bnd < 2) 0xFFFFF1C8.toInt() else 0xFFFFFFFF.toInt(), AriusFx.ICE, f)
                val a = (1f - f * 0.8f) * 0.62f * k
                d.ring(VfxLayers.FLOW_ADD, center, u, v, r0, r1, withAlpha(col, a), withAlpha(col, a * 0.35f), segments = 72,
                    uTile = 3f + bnd, uOffset = -time * (0.05f + 0.012f * bnd))
            }
            // Heller Innenrand der Scheibe (Lichtkante) und Photonenring um den Kern, zur Kamera gedreht.
            d.ring(VfxLayers.GLOW_ADD, center, u, v, core * 1.45, core * 1.95, withAlpha(0xFFFFFFFF.toInt(), 0.95f * k), withAlpha(0xFFFFE7B0.toInt(), 0f), segments = 64)
            d.ring(VfxLayers.GLOW_ADD, center, d.camRight, d.camUp, core * 1.12, core * 1.32, withAlpha(0xFFFFF4D6.toInt(), 0f), withAlpha(0xFFFFFFFF.toInt(), 0.9f * k), segments = 64)
            d.ring(VfxLayers.GLOW_ADD, center, d.camRight, d.camUp, core * 1.32, core * 1.6, withAlpha(0xFFFFFFFF.toInt(), 0.9f * k), withAlpha(0xFFFFE7B0.toInt(), 0f), segments = 64)
            // Drei Spiralarme als Lichtbänder in der Scheibe (nach außen gedreht, laufen weiter)
            for (arm in 0 until 3) {
                val path = ArrayList<Vec3d>(30)
                for (i in 0 until 30) {
                    val f = i / 29.0
                    val rr = core * 1.6 + f * diskR
                    val ang = arm * 2.094 + f * 3.6 - time * 0.07
                    path += center.add(u.multiply(cos(ang) * rr)).add(v.multiply(sin(ang) * rr)).add(nrm.multiply(sin(f * 9.0 + time * 0.2) * 0.08))
                }
                d.ribbon(VfxLayers.FLOW_ADD, path, { f -> 0.15 + 0.55 * f }, { f -> withAlpha(if (f < 0.3f) 0xFFFFFFFF.toInt() else 0xFFE8F6FF.toInt(), (1f - f) * 0.75f * k) },
                    uScale = 0.4f, uOffset = -time * 0.1f)
            }
            // Funken strömen entlang der Arme nach außen
            for (j in 0 until 24) {
                val f = ((time * 0.025 + j / 24.0) % 1.0)
                val rr = core * 1.6 + f * diskR
                val ang = (j % 3) * 2.094 + f * 3.6 - time * 0.07 + (j / 3) * 0.13
                val pos = center.add(u.multiply(cos(ang) * rr)).add(v.multiply(sin(ang) * rr))
                d.billboard(VfxLayers.FLARE_ADD, pos, 0.22 + 0.2 * (1 - f), withAlpha(if (j % 4 == 0) 0xFFFFE7B0.toInt() else 0xFFFFFFFF.toInt(), (1 - f).toFloat() * 0.85f * k))
            }
            // Polar-Jets: Lichtröhren entlang der Achse, schmal zulaufend, mit Pulswellen, die nach außen wandern
            val len = radius * 2.6 * pullK
            if (len > 0.3) for (sg in intArrayOf(-1, 1)) {
                val path = (0..16).map { i -> center.add(nrm.multiply(len * sg * i / 16.0)) }
                d.tube(VfxLayers.GLOW_ADD, path, { f -> core * (0.55 - 0.4 * f) * (1.0 + 0.25 * sin(f * 18.0 - time * 0.9)) },
                    { f -> withAlpha(dev.henny.hha.client.vfx.lerpColor(0xFFFFFFFF.toInt(), AriusFx.ICE, f), (1f - f) * 0.9f * k) }, sides = 10, uScale = 1f, uOffset = 0f)
                d.tube(VfxLayers.FLOW_ADD, path, { f -> core * (1.1 - 0.7 * f) }, { f -> withAlpha(AriusFx.ICE, (1f - f) * 0.35f * k) }, sides = 12, uScale = 0.6f, uOffset = -time * 0.15f)
                for (j in 0 until 5) {
                    val f = ((time * 0.05 + j / 5.0) % 1.0)
                    val at = center.add(nrm.multiply(len * sg * f))
                    d.ring(VfxLayers.GLOW_ADD, at, u, v, core * (0.5 + f * 0.6), core * (0.62 + f * 0.7), withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFFFFFFF.toInt(), (1 - f).toFloat() * 0.6f * k), segments = 24)
                }
            }
            // Lichtfesseln zu Wesen in der Nähe (nicht zum eigenen Spieler): geschwungene Lichtbänder
            val world = MinecraftClient.getInstance().world
            if (world != null && age < pullTicks) {
                val me = MinecraftClient.getInstance().player
                val box = net.minecraft.util.math.Box.of(center, radius * 2.0, radius * 2.0, radius * 2.0)
                for (e in world.getOtherEntities(me, box) { it is net.minecraft.entity.LivingEntity }.take(6)) {
                    val to = e.getLerpedPos(d.tickDelta).add(0.0, e.height * 0.55, 0.0)
                    val path = (0..10).map { i ->
                        val f = i / 10.0
                        center.lerp(to, f).add(nrm.multiply(sin(f * PI) * 0.8)).add(u.multiply(sin(f * 7.0 + time * 0.5 + e.id) * 0.15 * sin(f * PI)))
                    }
                    d.ribbon(VfxLayers.GLOW_ADD, path, { 0.12 }, { f -> withAlpha(0xFFFFF4D6.toInt(), (0.85f - f * 0.4f) * k) }, uScale = 1f, uOffset = 0f)
                }
            }
            // Frostrunenkreis am Boden
            ground?.let { g ->
                d.disc(VfxLayers.RUNE_ADD, g.add(0.0, 0.06, 0.0), X, Z, radius * 0.9, withAlpha(AriusFx.ICE, 0.55f * k), time * 0.01)
                d.disc(VfxLayers.GLOW_ADD, g.add(0.0, 0.05, 0.0), X, Z, radius * 0.7, withAlpha(0xFFFFFFFF.toInt(), 0.18f * k), 0.0)
            }
            // Der Kern selbst: harte, gleißend weiße Kugel mit Korona (zuletzt, damit er über allem liegt)
            d.sphere(VfxLayers.SHARD_ALPHA, center, core, { _ -> withAlpha(0xFFFFFFFF.toInt(), k) }, lat = 12, lon = 20)
            d.billboard(VfxLayers.FLARE_ADD, center, core * 3.6, withAlpha(0xFFFFFFFF.toInt(), 0.95f * k), time * 2.0)
            d.billboard(VfxLayers.RAYS_ADD, center, core * 8.0, withAlpha(0xFFFFF4D6.toInt(), 0.45f * k), time * 0.6)
            d.billboard(VfxLayers.GLOW_ADD, center, core * 11.0, withAlpha(AriusFx.ICE, 0.3f * k))
        }
        // Nach dem Kollaps: Lichtkuppel, die sich aufbläht und verblasst, dazu heller Ring am Boden
        val (sr, _, ss) = shock(d.tickDelta)
        if (ss > 0.02f) {
            d.sphere(VfxLayers.GLOW_ADD, center, sr * 0.8, { up -> withAlpha(0xFFFFFFFF.toInt(), 0.18f * ss * (0.6f + 0.4f * up)) }, lat = 10, lon = 24)
            d.ring(VfxLayers.GLOW_ADD, center.add(0.0, -0.4, 0.0), X, Z, (sr - 1.4).toDouble().coerceAtLeast(0.0), sr.toDouble(), withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(AriusFx.ICE, 0.7f * ss), segments = 72)
            d.ring(VfxLayers.FLOW_ADD, center.add(0.0, -0.35, 0.0), X, Z, (sr * 0.6).toDouble(), sr.toDouble() + 0.6, withAlpha(0xFFFFFFFF.toInt(), 0f), withAlpha(0xFFFFFFFF.toInt(), 0.4f * ss), segments = 72)
        }
    }

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val y = x - 1f
        return 1f + c3 * y * y * y + c1 * y * y
    }
}

/** Eigene Render-Pipelines der Ausrüstung/des Kampfes. */
object ArtPipelines {
    val WHITE_LENS: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(HhaArius.id("pipeline/white_lens"))
            .withVertexShader(HhaArius.id("core/white_lens"))
            .withFragmentShader(HhaArius.id("core/white_lens"))
            .withSampler("SceneSampler")
            .withSampler("DepthSampler")
            .withUniform("WhiteHole", UniformType.UNIFORM_BUFFER)
            .withoutBlend()
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withColorWrite(true, false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build(),
    )

    /** Erzwingt die Registrierung vor dem ersten Shader-Reload. */
    fun init() = Unit
}

/** Vollbild-Pass: kopiert das Weltbild und rechnet darüber die Linse des weißen Lochs (siehe `white_lens.fsh`). */
object WhiteLens {

    private val UNIFORM_SIZE = Std140SizeCalculator().putVec4().putVec4().putVec4().putVec4().get()
    private var copy: GpuTexture? = null
    private var copyView: GpuTextureView? = null
    private var uniforms: GpuBuffer? = null
    private var warned = false

    private fun ensure(width: Int, height: Int) {
        val device = RenderSystem.getDevice()
        val existing = copy
        if (existing == null || existing.getWidth(0) != width || existing.getHeight(0) != height) {
            copyView?.close()
            existing?.close()
            val usage = GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING
            val texture = device.createTexture({ "hha_arius white lens scene" }, usage, TextureFormat.RGBA8, width, height, 1, 1)
            copy = texture
            copyView = device.createTextureView(texture)
        }
        if (uniforms == null) {
            uniforms = device.createBuffer({ "hha_arius white lens uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, UNIFORM_SIZE.toLong())
        }
    }

    fun render(hole: WhiteHoleFx, tickDelta: Float) {
        try {
            draw(MinecraftClient.getInstance().framebuffer, hole, tickDelta)
        } catch (e: Exception) {
            if (!warned) {
                warned = true
                LOGGER.error("Linse des weißen Lochs fehlgeschlagen; weitere Meldungen werden unterdrückt", e)
            }
        }
    }

    private fun draw(framebuffer: Framebuffer, hole: WhiteHoleFx, td: Float) {
        val colorTexture = framebuffer.colorAttachment ?: return
        val colorView = framebuffer.colorAttachmentView ?: return
        val depthView = framebuffer.depthAttachmentView ?: return
        val intensity = hole.intensity(td)
        val (shockR, shockW, shockS) = hole.shock(td)
        if (intensity <= 0.01f && shockS <= 0.01f) return
        ensure(colorTexture.getWidth(0), colorTexture.getHeight(0))
        val scene = copy ?: return
        val sceneView = copyView ?: return
        val buffer = uniforms ?: return

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.copyTextureToTexture(colorTexture, scene, 0, 0, 0, 0, 0, scene.getWidth(0), scene.getHeight(0))

        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val c = hole.center
        val core = hole.coreRadius(td)
        val normal = hole.diskNormal(td)
        MemoryStack.stackPush().use { stack ->
            val data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                .putVec4((c.x - cam.x).toFloat(), (c.y - cam.y).toFloat(), (c.z - cam.z).toFloat(), core)
                .putVec4(normal.x, normal.y, normal.z, core * 6.5f)
                .putVec4((hole.age + td) / 20f, intensity, hole.flash(td), hole.pull(td))
                .putVec4(shockR, shockW, shockS, 0f)
                .get()
            encoder.writeToBuffer(buffer.slice(), data)
        }
        val transforms = RenderSystem.getDynamicUniforms()
            .write(RenderSystem.getModelViewMatrix(), Vector4f(1f, 1f, 1f, 1f), Vector3f(), Matrix4f())
        val sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR)
        val depthSampler = RenderSystem.getSamplerCache().get(FilterMode.NEAREST)
        encoder.createRenderPass({ "hha_arius white hole" }, colorView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(ArtPipelines.WHITE_LENS)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setUniform("WhiteHole", buffer)
            pass.bindTexture("SceneSampler", sceneView, sampler)
            pass.bindTexture("DepthSampler", depthView, depthSampler)
            pass.draw(0, 3)
        }
    }
}

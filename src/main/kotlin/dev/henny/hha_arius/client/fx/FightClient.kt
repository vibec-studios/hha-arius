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
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.client.art.ArtAnims
import dev.henny.hha_arius.net.BeatS2C
import dev.henny.hha_arius.net.CineCamS2C
import dev.henny.hha_arius.net.ParryCueS2C
import dev.henny.hha_arius.net.SilenceS2C
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.Framebuffer
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gl.UniformType
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.VertexFormats
import net.minecraft.particle.ParticleTypes
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.OptionalInt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client-Teil der Neuerungen aus 1.10.0: persönliche Kampfkamera ([PunishCam]), Impact-Frames ([ImpactFrame]), das
 * Schwarz-Weiß der Stille ([SilenceLens]) und die Paradeanzeige der Solvane ([ParryHud]). Alles nur Darstellung.
 */
object FightClient {
    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(CineCamS2C.ID) { p, _ -> PunishCam.set(p.on, p.focus, p.ticks) }
        ClientPlayNetworking.registerGlobalReceiver(BeatS2C.ID) { p, ctx -> beat(p, ctx.client()) }
        ClientPlayNetworking.registerGlobalReceiver(SilenceS2C.ID) { p, _ -> SilenceLens.set(p.strength, p.fade) }
        ClientPlayNetworking.registerGlobalReceiver(ParryCueS2C.ID) { p, _ -> ParryHud.onCue(p) }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (client.isPaused) return@register
            PunishCam.tick(client)
            SilenceLens.tick()
            ParryHud.tick(client)
        }
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register { ctx, _ ->
            ImpactFrame.render(ctx)
            if (!MinecraftClient.getInstance().options.hudHidden) ParryHud.renderHud(ctx)
        }
        WorldRenderEvents.END_MAIN.register { context ->
            SilenceLens.render()
            ParryHud.renderWorld(context)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    fun reset() {
        PunishCam.reset()
        SilenceLens.reset()
        ParryHud.clear()
        ImpactFrame.reset()
    }

    private fun beat(p: BeatS2C, client: MinecraftClient) {
        val world = client.world ?: return
        if (p.attacker >= 0) HitStop.stop(p.attacker, ArtAnims.ticks.toFloat(), p.stop.coerceIn(0f, 8f))
        if (p.kick > 0f) CameraKick.kick(p.kick, if (world.random.nextBoolean()) 0.6f else -0.6f)
        if (p.frame > 0f && AriusClientConfig.flash) ImpactFrame.trigger(p.frame)
        val fx = AriusClientConfig.fx.coerceIn(0f, 1f)
        repeat((14 * fx).toInt()) {
            val r = world.random
            world.addParticleClient(if (it % 3 == 0) ParticleTypes.END_ROD else ParticleTypes.CRIT, p.x, p.y, p.z,
                (r.nextDouble() - 0.5) * 0.9, r.nextDouble() * 0.5, (r.nextDouble() - 0.5) * 0.9)
        }
    }
}

/**
 * Persönliche Kampfkamera der Konter-Bestrafung: seitlich auf Arius und den eigenen Spieler, langsamer Schwenk, weich
 * ein- und ausgeblendet, nie in der Wand. Endet spätestens nach der mitgeschickten Dauer von selbst.
 */
object PunishCam {
    private var on = false
    private var focus = -1
    private var until = 0L
    private var k = 0f
    private var prevK = 0f
    private var age = 0
    private var side = 1.0

    fun set(on: Boolean, focus: Int, ticks: Int) {
        if (on) {
            if (!this.on && k <= 0.01f) {
                age = 0
                side = if (Math.random() < 0.5) 1.0 else -1.0
            }
            this.on = true
            if (focus >= 0) this.focus = focus
            until = ArtAnims.ticks + ticks.coerceIn(1, 200)
        } else {
            this.on = false
        }
    }

    fun tick(client: MinecraftClient) {
        prevK = k
        if (on && (ArtAnims.ticks >= until || client.player?.isAlive != true)) on = false
        val want = if (on) 1f else 0f
        k = if (want > k) (k + 0.2f).coerceAtMost(1f) else (k - 0.14f).coerceAtLeast(0f)
        if (k > 0f) age++
    }

    fun reset() {
        on = false
        k = 0f
        prevK = 0f
    }

    private fun ease(t: Double) = t * t * (3 - 2 * t)

    private fun lerpAngle(a: Double, b: Double, t: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d < -180) d += 360.0
        return a + d * t
    }

    @JvmStatic
    fun apply(cam: Vec3d, yaw: Float, pitch: Float, td: Float): DoubleArray? {
        val kk = (prevK + (k - prevK) * td).toDouble()
        if (kk <= 0.001) return null
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return null
        val world = client.world ?: return null
        val pp = player.getLerpedPos(td)
        val e = world.getEntityById(focus)
        val ep = e?.getLerpedPos(td) ?: pp.add(player.rotationVector.multiply(2.0))
        val mid = pp.lerp(ep, 0.5).add(0.0, 1.15, 0.0)
        var axis = Vec3d(ep.x - pp.x, 0.0, ep.z - pp.z)
        val sep = axis.length()
        axis = if (sep < 1e-3) Vec3d(0.0, 0.0, 1.0) else axis.multiply(1.0 / sep)
        val across = Vec3d(-axis.z, 0.0, axis.x).multiply(side)
        val t = age + td
        val ang = Math.toRadians(-25.0 + t * 0.55)
        val dir = across.multiply(cos(ang)).add(axis.multiply(sin(ang)))
        var target = mid.add(dir.multiply(3.4 + sep * 0.55)).add(0.0, 0.85, 0.0)
        val hit = world.raycast(RaycastContext(mid, target, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
        if (hit.type == HitResult.Type.BLOCK) target = mid.lerp(hit.pos, 0.85)
        val w = ease(kk)
        val pos = cam.lerp(target, w)
        val look = mid.subtract(pos)
        val flat = sqrt(look.x * look.x + look.z * look.z)
        val wantYaw = Math.toDegrees(atan2(-look.x, look.z))
        val wantPitch = -Math.toDegrees(atan2(look.y, flat))
        return doubleArrayOf(pos.x, pos.y, pos.z, lerpAngle(yaw.toDouble(), wantYaw, w), pitch + (wantPitch - pitch) * w)
    }
}

/**
 * Impact-Frame wie in Kampf-Animes: ein Bild fast weiß, dann ein kurzer dunkler Schlag, zusammen ≈ 0,12 s.
 * Folgt `/ariusc flash` (aus = keine Frames).
 */
object ImpactFrame {
    private var start = 0L
    private var strength = 0f

    fun trigger(s: Float) {
        start = System.nanoTime()
        strength = s.coerceIn(0f, 1f)
    }

    fun reset() {
        strength = 0f
    }

    fun render(ctx: DrawContext) {
        if (strength <= 0f) return
        val t = (System.nanoTime() - start) / 1.0e9f
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        when {
            t < 0.04f -> ctx.fill(0, 0, w, h, withAlpha(0xFFF4F8FF.toInt(), 0.75f * strength))
            t < 0.11f -> ctx.fill(0, 0, w, h, withAlpha(0xFF05070C.toInt(), 0.55f * strength * (1f - (t - 0.04f) / 0.07f)))
            else -> strength = 0f
        }
    }
}

/**
 * Die Stille (Gipfel-Finale): Vollbild-Pass nach dem Muster der Schnittlinse, der das Bild in hartes Schwarz-Weiß
 * taucht. Gold (Ansagen, der Blitz der Klinge) bleibt als einzige Farbe stehen. Abschaltbar über `/ariusc fx 0`.
 */
object SilenceLens {

    val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(HhaArius.id("pipeline/silence_lens"))
            .withVertexShader(HhaArius.id("core/cut_lens"))
            .withFragmentShader(HhaArius.id("core/silence_lens"))
            .withSampler("SceneSampler")
            .withUniform("SilenceLens", UniformType.UNIFORM_BUFFER)
            .withoutBlend()
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withColorWrite(true, false)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build(),
    )

    private var target = 0f
    private var value = 0f
    private var prev = 0f
    private var step = 0.05f
    private var copy: GpuTexture? = null
    private var copyView: GpuTextureView? = null
    private var uniforms: GpuBuffer? = null
    private var warned = false
    private val SIZE = Std140SizeCalculator().putVec4().get()

    fun set(strength: Float, fade: Int) {
        target = strength.coerceIn(0f, 1f)
        step = 1f / fade.coerceAtLeast(1)
    }

    fun reset() {
        target = 0f
        value = 0f
        prev = 0f
    }

    fun tick() {
        prev = value
        value = if (value < target) (value + step).coerceAtMost(target) else (value - step).coerceAtLeast(target)
        val world = MinecraftClient.getInstance().world
        if (world == null || world.registryKey != dev.henny.hha_arius.arena.ArenaDimension.WORLD) target = 0f
    }

    fun render() {
        val client = MinecraftClient.getInstance()
        val td = client.renderTickCounter.getTickProgress(true)
        val s = (prev + (value - prev) * td) * AriusClientConfig.fx.coerceIn(0f, 1f).let { if (it > 0f) 1f else 0f }
        if (s <= 0.005f) return
        try {
            draw(client.framebuffer, s, (ArtAnims.ticks + td) / 20f)
        } catch (e: Exception) {
            if (!warned) {
                warned = true
                HhaArius.LOGGER.error("Stille-Linse fehlgeschlagen; weitere Meldungen werden unterdrückt", e)
            }
        }
    }

    private fun draw(framebuffer: Framebuffer, strength: Float, time: Float) {
        val colorTexture = framebuffer.colorAttachment ?: return
        val colorView = framebuffer.colorAttachmentView ?: return
        val device = RenderSystem.getDevice()
        val width = colorTexture.getWidth(0)
        val height = colorTexture.getHeight(0)
        val existing = copy
        if (existing == null || existing.getWidth(0) != width || existing.getHeight(0) != height) {
            copyView?.close()
            existing?.close()
            val texture = device.createTexture({ "hha_arius silence scene" }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, width, height, 1, 1)
            copy = texture
            copyView = device.createTextureView(texture)
        }
        if (uniforms == null) uniforms = device.createBuffer({ "hha_arius silence uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, SIZE.toLong())
        val scene = copy ?: return
        val sceneView = copyView ?: return
        val buffer = uniforms ?: return
        val encoder = device.createCommandEncoder()
        encoder.copyTextureToTexture(colorTexture, scene, 0, 0, 0, 0, 0, width, height)
        MemoryStack.stackPush().use { stack ->
            val data = Std140Builder.onStack(stack, SIZE).putVec4(strength, time, 0f, 0f).get()
            encoder.writeToBuffer(buffer.slice(), data)
        }
        val transforms = RenderSystem.getDynamicUniforms().write(RenderSystem.getModelViewMatrix(), Vector4f(1f, 1f, 1f, 1f), Vector3f(), Matrix4f())
        val sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR)
        encoder.createRenderPass({ "hha_arius silence" }, colorView, OptionalInt.empty()).use { pass ->
            pass.setPipeline(PIPELINE)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setUniform("DynamicTransforms", transforms)
            pass.setUniform("SilenceLens", buffer)
            pass.bindTexture("SceneSampler", sceneView, sampler)
            pass.draw(0, 3)
        }
    }
}

/**
 * Paradeanzeige der Solvane (seit 1.10.0): Solange man Solvane in der Haupthand hält, zeigt ein Ring, WANN man
 * rechtsklicken muss. Er zieht sich auf das Fadenkreuz zusammen und leuchtet golden, genau solange ein Rechtsklick den
 * nächsten Schlag abfängt (Länge der Konterhaltung, Ping eingerechnet). Über dem Angreifer schwebt eine Raute in seiner
 * Farbe, golden im Fenster. Quellen: Server-Ansagen ([ParryCueS2C]: Nahkampf-Mobs, Bosse mit Aktionszustand) und die
 * HHA-Bossansagen, die der Client ohnehin zeichnet (Arius, Solvane, HHA-Bosse; weich über Reflexion).
 * Abschaltbar mit `/ariusc parry false`.
 */
object ParryHud {

    private class Cue(val entityId: Int, var origin: Vec3d?, val deadline: Float, val color: Int, val lead: Int)

    private val fromServer = HashMap<Int, Cue>()
    private val fromTelegraphs = ArrayList<Cue>()
    private var lead = 14

    private const val GOLD = 0xFFFFD45A.toInt()

    private fun now(): Float = ArtAnims.ticks.toFloat()

    /** Zeit für Hin- und Rückweg in Ticks (der Rechtsklick muss vor dem Treffer beim Server sein). */
    private fun rtt(client: MinecraftClient): Float {
        val p = client.player ?: return 0f
        val ms = client.networkHandler?.getPlayerListEntry(p.uuid)?.latency ?: 0
        return (ms / 50f).coerceIn(0f, 10f)
    }

    private fun holding(client: MinecraftClient): Boolean =
        AriusClientConfig.parryCue && client.player?.mainHandStack?.isOf(GearItems.SOLVANE) == true

    fun onCue(p: ParryCueS2C) {
        if (p.ticks < 0) {
            fromServer.remove(p.attacker)
            return
        }
        if (p.lead > 0) lead = p.lead
        val client = MinecraftClient.getInstance()
        fromServer[p.attacker] = Cue(p.attacker, null, now() + p.ticks - rtt(client), p.color, p.lead.coerceAtLeast(2))
    }

    fun clear() {
        fromServer.clear()
        fromTelegraphs.clear()
    }

    fun tick(client: MinecraftClient) {
        val t = now()
        fromServer.values.removeIf { t > it.deadline + 8f }
        fromTelegraphs.clear()
        if (!holding(client)) return
        val player = client.player ?: return
        scanTelegraphs(client, player.entityPos)
    }

    // --- HHA-Bossansagen (Client-Effekte, weich über Reflexion: HHA 0.8 und 0.9) --------------------------------------

    private val effectsField by lazy {
        runCatching { Class.forName("dev.henny.hha.client.vfx.VfxManager").getDeclaredField("effects").apply { isAccessible = true } }.getOrNull()
    }
    private val fieldCache = HashMap<Pair<Class<*>, String>, java.lang.reflect.Field?>()
    private var broken = false

    private fun field(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        val key = o.javaClass to name
        val f = fieldCache.getOrPut(key) {
            var found: java.lang.reflect.Field? = null
            while (c != null && found == null) {
                found = runCatching { c!!.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                c = c!!.superclass
            }
            found
        } ?: return null
        return runCatching { f.get(o) }.getOrNull()
    }

    private fun scanTelegraphs(client: MinecraftClient, me: Vec3d) {
        if (broken) return
        val list = runCatching { (effectsField?.get(null) as? List<*>)?.toList() }.getOrNull() ?: return
        val t = now()
        val ping = rtt(client)
        try {
            for (e in list) {
                if (e !is dev.henny.hha.client.vfx.TelegraphEffect) continue
                if (field(e, "safe") == true) continue
                val left = e.life - e.age
                if (left < 0 || left > 60) continue
                val origin: Vec3d
                val inside: Boolean
                when (e.javaClass.simpleName) {
                    "TelegraphLineEffect" -> {
                        val a = field(e, "from") as? Vec3d ?: continue
                        val b = field(e, "to") as? Vec3d ?: continue
                        val hw = (field(e, "halfWidth") as? Float ?: 1f).toDouble()
                        origin = a
                        inside = segDist(me, a, b) <= hw + 0.6
                    }
                    "TelegraphRingEffect" -> {
                        val c = field(e, "center") as? Vec3d ?: continue
                        val r = (field(e, "radius") as? Float ?: 1f).toDouble()
                        val inner = (field(e, "inner") as? Float ?: 0f).toDouble()
                        val d = flatDist(me, c)
                        origin = c
                        inside = d <= r + 0.6 && d >= inner - 0.6
                    }
                    "TelegraphConeEffect" -> {
                        val apex = field(e, "apex") as? Vec3d ?: continue
                        val r = (field(e, "radius") as? Float ?: 1f).toDouble()
                        val fwd = field(e, "forward") as? Vec3d ?: continue
                        val half = field(e, "half") as? Double ?: 1.0
                        val full = field(e, "full") == true
                        val to = Vec3d(me.x - apex.x, 0.0, me.z - apex.z)
                        val d = to.length()
                        val dot = if (d < 1e-3) 1.0 else (to.x * fwd.x + to.z * fwd.z) / (d * sqrt(fwd.x * fwd.x + fwd.z * fwd.z).coerceAtLeast(1e-4))
                        origin = apex
                        // „half“ ist je nach HHA-Version Winkel oder Kosinus: großzügig beides gelten lassen.
                        inside = d <= r + 0.6 && (full || dot >= half || Math.acos(dot.coerceIn(-1.0, 1.0)) <= half)
                    }
                    else -> continue
                }
                if (!inside) continue
                fromTelegraphs.add(Cue(-1, origin.add(0.0, 1.6, 0.0), t + left - ping, GOLD, lead))
            }
        } catch (ex: Throwable) {
            broken = true
            HhaArius.LOGGER.warn("Paradeanzeige: HHA-Ansagen nicht lesbar ({}), nur Server-Ansagen", ex.toString())
        }
    }

    private fun flatDist(a: Vec3d, b: Vec3d): Double = sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z))

    private fun segDist(p: Vec3d, a: Vec3d, b: Vec3d): Double {
        val vx = b.x - a.x
        val vz = b.z - a.z
        val len2 = vx * vx + vz * vz
        val k = if (len2 < 1e-9) 0.0 else (((p.x - a.x) * vx + (p.z - a.z) * vz) / len2).coerceIn(0.0, 1.0)
        val dx = p.x - (a.x + vx * k)
        val dz = p.z - (a.z + vz * k)
        return sqrt(dx * dx + dz * dz)
    }

    private fun upcoming(): List<Cue> {
        val t = now()
        return (fromServer.values + fromTelegraphs).filter { it.deadline - t in -6f..40f }.sortedBy { it.deadline }.take(3)
    }

    /** Fenster offen: von (Frist − Haltung + 2) bis zur Frist. */
    private fun open(c: Cue, t: Float): Boolean = t >= c.deadline - c.lead + 2 && t <= c.deadline

    fun renderHud(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        if (!holding(client)) return
        val cues = upcoming()
        if (cues.isEmpty()) return
        val td = client.renderTickCounter.getTickProgress(true)
        val t = now() + td
        val cx = ctx.scaledWindowWidth / 2
        val cy = ctx.scaledWindowHeight / 2
        for ((i, c) in cues.withIndex()) {
            val opensAt = c.deadline - c.lead + 2
            val toOpen = opensAt - t
            val base = 9f + i * 3f
            when {
                open(c, t) -> {
                    // Golden: jetzt klicken. Pulsiert; zur Frist hin enger.
                    val k = ((t - opensAt) / (c.deadline - opensAt).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val pulse = 0.75f + 0.25f * sin(t * 2.2f)
                    ring(ctx, cx, cy, base + 1.5f * (1f - k), withAlpha(GOLD, pulse), 2)
                    ring(ctx, cx, cy, base + 4f, withAlpha(GOLD, 0.35f * pulse), 1)
                }
                toOpen > 0f -> {
                    // Zieht sich zusammen: trifft den Grundring genau, wenn das Fenster aufgeht.
                    val r = base + (toOpen / 24f).coerceIn(0f, 1f) * 46f
                    val a = (1f - toOpen / 40f).coerceIn(0.25f, 0.9f)
                    ring(ctx, cx, cy, r, withAlpha(c.color, a), 1)
                    ring(ctx, cx, cy, base, withAlpha(0xFFFFFFFF.toInt(), 0.18f), 1)
                }
                else -> {
                    // Vorbei: kurz rot verblassend.
                    val a = (1f + (t - c.deadline) / 6f).coerceIn(0f, 1f) * 0.5f
                    if (a > 0f) ring(ctx, cx, cy, base, withAlpha(0xFFE04040.toInt(), a), 1)
                }
            }
        }
    }

    private fun ring(ctx: DrawContext, cx: Int, cy: Int, r: Float, color: Int, size: Int) {
        val n = (r * 2.2f).toInt().coerceIn(16, 96)
        for (i in 0 until n) {
            val a = i * 2.0 * Math.PI / n
            val x = (cx + cos(a) * r).toInt()
            val y = (cy + sin(a) * r).toInt()
            ctx.fill(x, y, x + size, y + size, color)
        }
    }

    fun renderWorld(context: net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext) {
        val client = MinecraftClient.getInstance()
        if (!holding(client)) return
        val cues = upcoming()
        if (cues.isEmpty()) return
        val world = client.world ?: return
        val consumers = context.consumers() ?: return
        val td = client.renderTickCounter.getTickProgress(true)
        val d = VfxDraw(consumers, client.gameRenderer.camera, td)
        val t = now() + td
        for (c in cues) {
            val pos = if (c.entityId >= 0) world.getEntityById(c.entityId)?.let { it.getLerpedPos(td).add(0.0, it.height + 0.55, 0.0) } else c.origin
            pos ?: continue
            val isOpen = open(c, t)
            val col = if (isOpen) GOLD else c.color
            val s = if (isOpen) 0.32 + 0.06 * sin(t * 2.0) else 0.22
            runCatching {
                d.billboard(VfxLayers.GLOW_ADD, pos, s, withAlpha(col, 0.85f))
                d.billboard(VfxLayers.GLOW_ADD, pos, s * 0.45, withAlpha(0xFFFFFFFF.toInt(), 0.9f))
            }
        }
    }
}

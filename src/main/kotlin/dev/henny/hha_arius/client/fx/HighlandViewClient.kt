package dev.henny.hha_arius.client.fx

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.arena.HighlandChunk
import io.netty.buffer.Unpooled
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.LeavesBlock
import net.minecraft.block.SnowBlock
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.DynamicUniforms
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.VertexFormats
import net.minecraft.client.texture.SpriteAtlasTexture
import net.minecraft.client.world.ClientWorld
import net.minecraft.network.PacketByteBuf
import net.minecraft.registry.Registries
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.PalettedContainer
import net.minecraft.world.chunk.PalettesFactory
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryUtil
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Echte Berge jenseits der Sichtweite (seit 1.12.0, ersetzt das frühere Nebelbild).
 *
 * Der Server schickt die Blockpaletten der Fernlandschaft ([HighlandChunk]). Gebaut wird nur, was gerade gebraucht wird:
 * Abschnitte außerhalb des runden Bereichs, den Sodium selbst zeichnet. Innere Flächen des hohlen Bergs entfallen über
 * die Höhenkarte ([FarPlan.terrain]), ab [LOD_FROM] Chunks Abstand genügt ein Raster aus 2er-Zellen. So bleibt der
 * Grafikspeicher klein (mit 32 Chunks Sichtweite fast leer), und alles Zeichnen ist ein einziger Durchgang.
 */
object HighlandViewClient {

    private const val BASE_SECTION = (ArenaLayout.BASE_Y - 1) shr 4
    private const val MAX_SECTION = 24
    private const val MAX_UPLOADS_PER_TICK = 12
    private const val MAX_JOBS_PER_TICK = 24
    private const val VERTEX_BYTES = 24 // float x, y, z (12) + float u, v (8) + ubyte4 color (4)

    /** Ab diesem Chunk-Abstand zur Kamera wird in 2er-Zellen gebaut. */
    private const val LOD_FROM = 14

    /** Flächen, die mehr als so viele Blöcke unter der Geländeoberkante liegen, sieht von außen niemand. */
    private const val SKIN = 4

    private val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET)
            .withLocation(HhaArius.id("pipeline/highland_terrain"))
            .withVertexShader(HhaArius.id("core/highland_terrain"))
            .withFragmentShader(HhaArius.id("core/highland_terrain"))
            .withSampler("Sampler0")
            .withoutBlend()
            .withCull(true)
            .withDepthWrite(true)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .build(),
    )

    private class SpriteUv(val u0: Float, val v0: Float, val u1: Float, val v1: Float, val tintR: Float, val tintG: Float, val tintB: Float)
    private class BuiltMesh(val epoch: Int, val pos: ChunkPos, val rev: Int, val step: Int, val minY: Int, val maxY: Int, val vertices: ByteArray?, val quadCount: Int)
    private class GpuChunk(val pos: ChunkPos, val rev: Int, val step: Int, val minY: Int, val maxY: Int, val buffer: GpuBuffer, val indexCount: Int)

    /** Empfangene Palette eines Abschnitts; [rev] steigt mit jeder neuen Fassung (z. B. nach dem Bergschnitt). */
    private class Source(var payload: HighlandChunk, var rev: Int) {
        var queuedRev = -1
        var queuedStep = 0
    }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hha-arius-highland-mesh").apply { isDaemon = true }
    }

    private val epoch = AtomicInteger(0)
    private val readyQueue = ConcurrentLinkedQueue<BuiltMesh>()
    private val chunks = HashMap<Long, GpuChunk>()
    private val sources = HashMap<Long, Source>()
    private val uvCache = ConcurrentHashMap<Block, SpriteUv>()

    @Volatile
    private var uvReady = false

    @Volatile
    private var palettesFactory: PalettesFactory? = null

    private var activeWorld: ClientWorld? = null
    private var warned = false

    fun init() {
        PIPELINE
        ClientPlayNetworking.registerGlobalReceiver(HighlandChunk.ID) { payload, ctx ->
            val client = ctx.client()
            val world = client.world ?: return@registerGlobalReceiver
            if (world.registryKey != ArenaDimension.WORLD) return@registerGlobalReceiver
            if (world !== activeWorld) {
                reset()
                activeWorld = world
                palettesFactory = PalettesFactory.fromRegistryManager(world.registryManager)
            }
            val key = payload.pos.toLong()
            val src = sources[key]
            if (src == null) sources[key] = Source(payload, 0) else {
                src.payload = payload
                src.rev++
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val world = client.world
            if (world == null || world.registryKey != ArenaDimension.WORLD) {
                if (activeWorld != null || chunks.isNotEmpty()) reset()
                return@register
            }
            if (world !== activeWorld) {
                reset()
                activeWorld = world
                palettesFactory = PalettesFactory.fromRegistryManager(world.registryManager)
                uvReady = false
            }
            if (!uvReady) warmUvCache(client)
            schedule(client)
            drainUploads()
        }

        WorldRenderEvents.BEFORE_ENTITIES.register { _ ->
            val client = MinecraftClient.getInstance()
            val world = client.world ?: return@register
            if (world.registryKey != ArenaDimension.WORLD || chunks.isEmpty() || debugOff) return@register
            try {
                render(client, world)
            } catch (t: Throwable) {
                if (!warned) {
                    warned = true
                    HhaArius.LOGGER.warn("HighlandViewClient render failed; suppressing further warnings", t)
                }
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    /** Nur für die Sichtprüfung: Fernlandschaft aus (Vergleich der Bildzeit). */
    @JvmField
    var debugOff = false

    /** Nur für die Sichtprüfung: Abschnitte, Vierecke, Puffergröße in MB. */
    fun debugStats(): String {
        val quads = chunks.values.sumOf { it.indexCount / 6L }
        return "chunks=%d quads=%d vram=%.1fMB queued=%d".format(chunks.size, quads, quads * 4.0 * VERTEX_BYTES / (1024 * 1024), readyQueue.size)
    }

    fun reset() {
        epoch.incrementAndGet()
        activeWorld = null
        palettesFactory = null
        uvReady = false
        readyQueue.clear()
        for (c in chunks.values) runCatching { c.buffer.close() }
        chunks.clear()
        sources.clear()
    }

    /** Liegt der Abschnitt im runden Bereich, den die normale Chunk-Darstellung selbst zeichnet? */
    private fun coveredByWorld(world: ClientWorld, cx: Int, cz: Int, camCx: Int, camCz: Int, view: Int): Boolean {
        val dx = cx - camCx
        val dz = cz - camCz
        val r = view - 1
        return dx * dx + dz * dz < r * r && world.chunkManager.isChunkLoaded(cx, cz)
    }

    /** Baut fehlende Abschnitte jenseits der Sichtweite, gibt innen liegende frei und wechselt die Auflösung nach Abstand. */
    private fun schedule(client: MinecraftClient) {
        val world = client.world ?: return
        val factory = palettesFactory ?: return
        if (!uvReady || sources.isEmpty()) return
        val cam = client.gameRenderer.camera.cameraPos
        val camCx = floor(cam.x).toInt() shr 4
        val camCz = floor(cam.z).toInt() shr 4
        val view = client.options.clampedViewDistance
        val currentEpoch = epoch.get()
        val wanted = ArrayList<Triple<Int, Source, Int>>()
        for ((key, src) in sources) {
            val pos = src.payload.pos
            if (coveredByWorld(world, pos.x, pos.z, camCx, camCz, view - 2)) {
                chunks.remove(key)?.let { runCatching { it.buffer.close() } }
                src.queuedRev = -1
                continue
            }
            val d = maxOf(abs(pos.x - camCx), abs(pos.z - camCz))
            val step = if (d >= LOD_FROM) 2 else 1
            val have = chunks[key]
            val fresh = have != null && have.rev == src.rev && (have.step == step || abs(d - LOD_FROM) < 2)
            val queued = src.queuedRev == src.rev && (src.queuedStep == step || abs(d - LOD_FROM) < 2)
            if (!fresh && !queued) wanted.add(Triple(d, src, step))
        }
        wanted.sortBy { it.first }
        for ((_, src, step) in wanted.take(MAX_JOBS_PER_TICK)) {
            src.queuedRev = src.rev
            src.queuedStep = step
            val payload = src.payload
            val rev = src.rev
            worker.execute {
                if (epoch.get() != currentEpoch) return@execute
                val mesh = runCatching { buildMesh(currentEpoch, factory, payload, rev, step) }.getOrNull() ?: return@execute
                if (epoch.get() == currentEpoch) readyQueue.add(mesh)
            }
        }
    }

    private fun warmUvCache(client: MinecraftClient) {
        uvCache.clear()
        val models = client.blockRenderManager.models
        for (block in Registries.BLOCK) {
            val state = block.defaultState
            if (state.isAir) continue
            val sprite = runCatching { models.getModelParticleSprite(state) }.getOrNull() ?: continue
            val (tr, tg, tb) = tintFor(block)
            uvCache[block] = SpriteUv(sprite.minU, sprite.minV, sprite.maxU, sprite.maxV, tr, tg, tb)
        }
        uvReady = true
    }

    private fun tintFor(block: Block): Triple<Float, Float, Float> = when (block) {
        Blocks.SPRUCE_LEAVES -> Triple(0.38f, 0.60f, 0.38f)
        Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES, Blocks.DARK_OAK_LEAVES -> Triple(0.45f, 0.66f, 0.40f)
        Blocks.GRASS_BLOCK -> Triple(0.55f, 0.72f, 0.50f)
        Blocks.WATER -> Triple(0.25f, 0.48f, 0.78f)
        else -> Triple(1.0f, 1.0f, 1.0f)
    }

    private fun drainUploads() {
        val device = RenderSystem.tryGetDevice() ?: return
        var uploaded = 0
        while (uploaded < MAX_UPLOADS_PER_TICK) {
            val mesh = readyQueue.poll() ?: break
            if (mesh.epoch != epoch.get()) continue
            val key = mesh.pos.toLong()
            val src = sources[key]
            if (src == null || src.rev != mesh.rev || src.queuedRev != mesh.rev) continue
            chunks.remove(key)?.let { runCatching { it.buffer.close() } }
            val bytes = mesh.vertices
            if (bytes == null || mesh.quadCount <= 0) continue
            var direct: ByteBuffer? = null
            try {
                direct = MemoryUtil.memAlloc(bytes.size)
                direct.put(bytes).flip()
                val gpuBuf = device.createBuffer({ "hha_arius highland ${mesh.pos.x},${mesh.pos.z}" }, GpuBuffer.USAGE_VERTEX, direct)
                chunks[key] = GpuChunk(mesh.pos, mesh.rev, mesh.step, mesh.minY, mesh.maxY, gpuBuf, mesh.quadCount * 6)
                uploaded++
            } finally {
                if (direct != null) MemoryUtil.memFree(direct)
            }
        }
    }

    private fun render(client: MinecraftClient, world: ClientWorld) {
        val fb = client.framebuffer
        val colorView = fb.colorAttachmentView ?: return
        val depthView = fb.depthAttachmentView ?: return
        val cam = client.gameRenderer.camera.cameraPos
        val look = client.gameRenderer.camera.horizontalPlane
        val fwdX = look.x().toDouble()
        val fwdY = look.y().toDouble()
        val fwdZ = look.z().toDouble()
        val camCx = floor(cam.x).toInt() shr 4
        val camCz = floor(cam.z).toInt() shr 4
        val view = client.options.clampedViewDistance

        val visible = ArrayList<GpuChunk>(chunks.size.coerceAtMost(512))
        var maxIndices = 0
        for (c in chunks.values) {
            // Innen zeichnet die normale Chunk-Darstellung (Sodium: rund); am Rand bleibt ein Chunk Überlappung.
            if (coveredByWorld(world, c.pos.x, c.pos.z, camCx, camCz, view)) continue
            val centerX = c.pos.startX + 8.0
            val centerY = (c.minY + c.maxY) * 0.5
            val centerZ = c.pos.startZ + 8.0
            val rx = centerX - cam.x
            val ry = centerY - cam.y
            val rz = centerZ - cam.z
            val distSq = rx * rx + ry * ry + rz * rz
            // Generous behind-camera cull so tall peaks near screen edges never pop out.
            if (distSq > 48.0 * 48.0 && (rx * fwdX + ry * fwdY + rz * fwdZ) < -28.0) continue
            visible.add(c)
            if (c.indexCount > maxIndices) maxIndices = c.indexCount
        }
        if (visible.isEmpty() || maxIndices <= 0) return

        val modelView = Matrix4f(RenderSystem.getModelViewMatrix())
        val colorMod = Vector4f(1f, 1f, 1f, 1f)
        val texMat = Matrix4f()
        val transforms = Array(visible.size) { i ->
            val c = visible[i]
            DynamicUniforms.TransformsValue(
                modelView,
                colorMod,
                Vector3f((c.pos.startX - cam.x).toFloat(), (ArenaLayout.BASE_Y - cam.y).toFloat(), (c.pos.startZ - cam.z).toFloat()),
                texMat,
            )
        }
        val slices = RenderSystem.getDynamicUniforms().writeTransforms(*transforms)
        val shapeBuf = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS)
        val indexBuffer = shapeBuf.getIndexBuffer(maxIndices)
        val indexType = shapeBuf.indexType
        val atlas = client.textureManager.getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)
        val atlasView = atlas.glTextureView
        val atlasSampler = atlas.sampler

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.createRenderPass({ "hha_arius highland terrain" }, colorView, OptionalInt.empty(), depthView, OptionalDouble.empty()).use { pass ->
            pass.setPipeline(PIPELINE)
            RenderSystem.bindDefaultUniforms(pass)
            pass.setIndexBuffer(indexBuffer, indexType)
            pass.bindTexture("Sampler0", atlasView, atlasSampler)
            for (i in visible.indices) {
                val c = visible[i]
                pass.setUniform("DynamicTransforms", slices[i])
                pass.setVertexBuffer(0, c.buffer)
                pass.drawIndexed(0, 0, c.indexCount, 1)
            }
        }
    }

    // --- Bau im Hintergrund: Palette lesen, innere Flächen weglassen, Vierecke schreiben -----------------------------------

    private fun empty(currentEpoch: Int, payload: HighlandChunk, rev: Int, step: Int) =
        BuiltMesh(currentEpoch, payload.pos, rev, step, ArenaLayout.BASE_Y, ArenaLayout.BASE_Y, null, 0)

    private fun buildMesh(currentEpoch: Int, factory: PalettesFactory, payload: HighlandChunk, rev: Int, step: Int): BuiltMesh {
        if (payload.blocks.isEmpty()) return empty(currentEpoch, payload, rev, step)
        val sections = HashMap<Int, PalettedContainer<BlockState>>()
        val buf = PacketByteBuf(Unpooled.wrappedBuffer(payload.blocks))
        try {
            while (buf.isReadable) {
                val sy = buf.readByte().toInt()
                val container = factory.getBlockStateContainer()
                container.readPacket(buf)
                if (sy in BASE_SECTION..MAX_SECTION) sections[sy] = container
            }
        } finally {
            buf.release()
        }
        if (sections.isEmpty()) return empty(currentEpoch, payload, rev, step)

        val baseY = ArenaLayout.BASE_Y
        val yStart = max(sections.keys.min() shl 4, baseY - 1)
        val yEnd = ((sections.keys.max() + 1) shl 4) - 1
        val height = yEnd - yStart + 1
        if (height <= 0) return empty(currentEpoch, payload, rev, step)

        // Zellen: Kantenlänge [step]; eine Zelle ist belegt, wenn ein Block darin zählt, und zeigt den obersten.
        val cells = 16 / step
        val layers = (height + step - 1) / step
        val cellStates = arrayOfNulls<BlockState>(cells * layers * cells)
        val solid = BooleanArray(cells * layers * cells)
        val topOf = IntArray(cells * layers * cells) { Int.MIN_VALUE }
        fun cell(cx: Int, cy: Int, cz: Int) = (cy * cells + cz) * cells + cx
        var minLayer = Int.MAX_VALUE
        var maxLayer = Int.MIN_VALUE
        for ((sy, container) in sections) {
            val secBaseY = sy shl 4
            for (ly in 0 until 16) {
                val wy = secBaseY + ly
                if (wy < yStart || wy > yEnd) continue
                val cy = (wy - yStart) / step
                for (lz in 0 until 16) for (lx in 0 until 16) {
                    val st = container.get(lx, ly, lz)
                    if (st.isAir) continue
                    val opaque = isOccluder(st)
                    if (!opaque && !isSpecialVisible(st)) continue
                    val c = cell(lx / step, cy, lz / step)
                    // Von oben nach unten gelesen gewinnt der höchste Block; die Reihenfolge der Abschnitte ist beliebig.
                    val prev = cellStates[c]
                    if (prev == null || wy >= topOf[c]) {
                        cellStates[c] = st
                        topOf[c] = wy
                    }
                    if (opaque || step > 1) solid[c] = true
                    if (cy < minLayer) minLayer = cy
                    if (cy > maxLayer) maxLayer = cy
                }
            }
        }
        if (minLayer > maxLayer) return empty(currentEpoch, payload, rev, step)

        // Höhenkarte der Fernlandschaft (relativ), eine Zelle Rand rundum: Was tiefer liegt, ist Inneres des Bergs.
        val slot = Math.floorDiv(payload.pos.startX + 8 + ArenaLayout.SLOT_SPACING / 2, ArenaLayout.SLOT_SPACING)
        val ox = ArenaLayout.origin(slot).x
        val oz = ArenaLayout.origin(slot).z
        val tops = IntArray((cells + 2) * (cells + 2))
        for (cz in -1..cells) for (cx in -1..cells) {
            val rx = payload.pos.startX + cx * step + step / 2 - ox
            val rz = payload.pos.startZ + cz * step + step / 2 - oz
            tops[(cz + 1) * (cells + 2) + cx + 1] = runCatching { FarPlan.terrain(rx, rz) }.getOrDefault(Int.MIN_VALUE / 2)
        }
        fun topAt(cx: Int, cz: Int) = tops[(cz + 1) * (cells + 2) + cx + 1]
        fun solidAt(cx: Int, cy: Int, cz: Int): Boolean =
            cx in 0 until cells && cz in 0 until cells && cy in 0 until layers && solid[cell(cx, cy, cz)]

        val out = ByteArrayOutputStream(16384)
        val scratch = ByteBuffer.allocate(VERTEX_BYTES * 4).order(ByteOrder.LITTLE_ENDIAN)
        var quads = 0

        fun quad(
            x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
            x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
            x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
            x3: Float, y3: Float, z3: Float, u3: Float, v3: Float,
            abgr: Int,
        ) {
            scratch.clear()
            scratch.putFloat(x0).putFloat(y0).putFloat(z0).putFloat(u0).putFloat(v0).putInt(abgr)
            scratch.putFloat(x1).putFloat(y1).putFloat(z1).putFloat(u1).putFloat(v1).putInt(abgr)
            scratch.putFloat(x2).putFloat(y2).putFloat(z2).putFloat(u2).putFloat(v2).putInt(abgr)
            scratch.putFloat(x3).putFloat(y3).putFloat(z3).putFloat(u3).putFloat(v3).putInt(abgr)
            out.write(scratch.array(), 0, VERTEX_BYTES * 4)
            quads++
        }

        fun color(uv: SpriteUv, r: Float, g: Float, b: Float): Int {
            val rr = (uv.tintR * r * 255f).toInt().coerceIn(0, 255)
            val gg = (uv.tintG * g * 255f).toInt().coerceIn(0, 255)
            val bb = (uv.tintB * b * 255f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (bb shl 16) or (gg shl 8) or rr
        }

        val size = step.toFloat()
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (cy in minLayer..maxLayer) {
            val ry = cy * step + yStart - baseY
            val fy = ry.toFloat()
            for (cz in 0 until cells) for (cx in 0 until cells) {
                val c = cell(cx, cy, cz)
                val st = cellStates[c] ?: continue
                val uv = uvCache[st.block] ?: continue
                val own = topAt(cx, cz)
                val x0 = (cx * step).toFloat()
                val z0 = (cz * step).toFloat()
                val x1 = x0 + size
                val z1 = z0 + size
                val h = if (step == 1 && st.block is SnowBlock) (st.get(SnowBlock.LAYERS) / 8f).coerceAtLeast(0.125f) else size
                val yTop = fy + h
                val u0 = uv.u0; val v0 = uv.v0; val u1 = uv.u1; val v1 = uv.v1
                val vSide = v0 + (v1 - v0) * (1f - h / size)
                val before = quads
                // oben
                if ((h < size || !solidAt(cx, cy + 1, cz)) && ry + step > own - SKIN) {
                    var ao = 1f
                    if (solidAt(cx - 1, cy + 1, cz)) ao -= 0.06f
                    if (solidAt(cx + 1, cy + 1, cz)) ao -= 0.06f
                    if (solidAt(cx, cy + 1, cz - 1)) ao -= 0.06f
                    if (solidAt(cx, cy + 1, cz + 1)) ao -= 0.06f
                    quad(x0, yTop, z0, u0, v0, x0, yTop, z1, u0, v1, x1, yTop, z1, u1, v1, x1, yTop, z0, u1, v0, color(uv, ao, ao, ao))
                }
                // unten: nur Überhänge (Bögen, Äste), nie der Bergbauch
                if (ry > 0 && ry > own && !solidAt(cx, cy - 1, cz)) {
                    quad(x0, fy, z0, u0, v0, x1, fy, z0, u1, v0, x1, fy, z1, u1, v1, x0, fy, z1, u0, v1, color(uv, 0.52f, 0.54f, 0.59f))
                }
                // Seiten: nur, wo die Nachbarspalte tiefer liegt als diese Fläche
                if (!solidAt(cx, cy, cz - 1) && ry + step > topAt(cx, cz - 1) - SKIN) {
                    val ao = if (solidAt(cx, cy - 1, cz - 1)) 0.92f else 1f
                    quad(x1, yTop, z0, u0, vSide, x1, fy, z0, u0, v1, x0, fy, z0, u1, v1, x0, yTop, z0, u1, vSide, color(uv, 0.78f * ao, 0.81f * ao, 0.87f * ao))
                }
                if (!solidAt(cx, cy, cz + 1) && ry + step > topAt(cx, cz + 1) - SKIN) {
                    val ao = if (solidAt(cx, cy - 1, cz + 1)) 0.92f else 1f
                    quad(x0, yTop, z1, u0, vSide, x0, fy, z1, u0, v1, x1, fy, z1, u1, v1, x1, yTop, z1, u1, vSide, color(uv, 0.84f * ao, 0.85f * ao, 0.88f * ao))
                }
                if (!solidAt(cx - 1, cy, cz) && ry + step > topAt(cx - 1, cz) - SKIN) {
                    val ao = if (solidAt(cx - 1, cy - 1, cz)) 0.91f else 1f
                    quad(x0, yTop, z0, u0, vSide, x0, fy, z0, u0, v1, x0, fy, z1, u1, v1, x0, yTop, z1, u1, vSide, color(uv, 0.65f * ao, 0.70f * ao, 0.78f * ao))
                }
                if (!solidAt(cx + 1, cy, cz) && ry + step > topAt(cx + 1, cz) - SKIN) {
                    val ao = if (solidAt(cx + 1, cy - 1, cz)) 0.93f else 1f
                    quad(x1, yTop, z1, u0, vSide, x1, fy, z1, u0, v1, x1, fy, z0, u1, v1, x1, yTop, z0, u1, vSide, color(uv, 0.93f * ao, 0.92f * ao, 0.90f * ao))
                }
                if (quads > before) {
                    if (ry + baseY < minY) minY = ry + baseY
                    if (ry + baseY + step > maxY) maxY = ry + baseY + step
                }
            }
        }
        if (quads == 0) return empty(currentEpoch, payload, rev, step)
        return BuiltMesh(currentEpoch, payload.pos, rev, step, minY, maxY, out.toByteArray(), quads)
    }

    private fun isOccluder(state: BlockState): Boolean =
        state.isOpaqueFullCube || state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.BLUE_ICE) || state.isOf(Blocks.SNOW_BLOCK)

    private fun isSpecialVisible(state: BlockState): Boolean =
        state.block is SnowBlock || state.block is LeavesBlock || state.isOf(Blocks.ICE) || state.isOf(Blocks.FROSTED_ICE) ||
            state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.GLASS) || state.isOf(Blocks.LIGHT_BLUE_STAINED_GLASS) ||
            state.isOf(Blocks.WHITE_STAINED_GLASS)
}

package dev.henny.hha_arius.client.fx

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.arena.ArenaLayout
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
 * Real distant mountain terrain renderer for Eirholm.
 *
 * Receives [HighlandChunk] block-palette payloads from the server, decodes and meshes exposed faces on a background
 * worker thread using the live block atlas sprites and directional/AO shading, uploads budgeted GPU vertex buffers on
 * the render thread, and draws all visible chunks beyond vanilla render distance in a single [BEFORE_ENTITIES] pass.
 */
object HighlandViewClient {

    private const val BASE_SECTION = (ArenaLayout.BASE_Y - 1) shr 4
    private const val MAX_SECTION = 24
    private const val MAX_UPLOADS_PER_TICK = 12
    private const val VERTEX_BYTES = 24 // float x, y, z (12) + float u, v (8) + ubyte4 color (4)

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
    private class BuiltMesh(val epoch: Int, val pos: ChunkPos, val minY: Int, val maxY: Int, val vertices: ByteArray?, val quadCount: Int)
    private class GpuChunk(val pos: ChunkPos, val minY: Int, val maxY: Int, val buffer: GpuBuffer, val indexCount: Int)

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hha-arius-highland-mesh").apply { isDaemon = true }
    }

    private val epoch = AtomicInteger(0)
    private val readyQueue = ConcurrentLinkedQueue<BuiltMesh>()
    private val chunks = HashMap<Long, GpuChunk>()
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
            if (!uvReady) warmUvCache(client)
            val currentEpoch = epoch.get()
            val factory = palettesFactory ?: return@registerGlobalReceiver
            worker.execute {
                if (epoch.get() != currentEpoch) return@execute
                val mesh = runCatching { buildMesh(currentEpoch, factory, payload) }.getOrNull() ?: return@execute
                if (epoch.get() == currentEpoch) readyQueue.add(mesh)
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
            drainUploads()
        }

        WorldRenderEvents.BEFORE_ENTITIES.register { _ ->
            val client = MinecraftClient.getInstance()
            val world = client.world ?: return@register
            if (world.registryKey != ArenaDimension.WORLD || chunks.isEmpty()) return@register
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

    fun reset() {
        epoch.incrementAndGet()
        activeWorld = null
        palettesFactory = null
        uvReady = false
        readyQueue.clear()
        for (c in chunks.values) runCatching { c.buffer.close() }
        chunks.clear()
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
            chunks.remove(key)?.let { runCatching { it.buffer.close() } }
            val bytes = mesh.vertices
            if (bytes == null || mesh.quadCount <= 0) continue
            var direct: ByteBuffer? = null
            try {
                direct = MemoryUtil.memAlloc(bytes.size)
                direct.put(bytes).flip()
                val gpuBuf = device.createBuffer({ "hha_arius highland ${mesh.pos.x},${mesh.pos.z}" }, GpuBuffer.USAGE_VERTEX, direct)
                chunks[key] = GpuChunk(mesh.pos, mesh.minY, mesh.maxY, gpuBuf, mesh.quadCount * 6)
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
        val chunkManager = world.chunkManager

        val visible = ArrayList<GpuChunk>(chunks.size.coerceAtMost(512))
        var maxIndices = 0
        for (c in chunks.values) {
            val dcx = abs(c.pos.x - camCx)
            val dcz = abs(c.pos.z - camCz)
            // Vanilla already draws loaded interior chunks; skip them cleanly. Keep 1-chunk overlap at the edge.
            if (dcx < view - 1 && dcz < view - 1 && chunkManager.isChunkLoaded(c.pos.x, c.pos.z)) continue
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

    // --- Background chunk decoding & culled block meshing -----------------------------------------------------------

    private fun buildMesh(currentEpoch: Int, factory: PalettesFactory, payload: HighlandChunk): BuiltMesh {
        if (payload.blocks.isEmpty()) return BuiltMesh(currentEpoch, payload.pos, ArenaLayout.BASE_Y, ArenaLayout.BASE_Y, null, 0)
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
        if (sections.isEmpty()) return BuiltMesh(currentEpoch, payload.pos, ArenaLayout.BASE_Y, ArenaLayout.BASE_Y, null, 0)

        val minSy = sections.keys.min()
        val maxSy = sections.keys.max()
        val baseY = ArenaLayout.BASE_Y
        val yStart = max(minSy shl 4, baseY - 1)
        val yEnd = ((maxSy + 1) shl 4) - 1
        val height = yEnd - yStart + 1
        if (height <= 0) return BuiltMesh(currentEpoch, payload.pos, baseY, baseY, null, 0)

        // Unpack into a fast local array for O(1) neighbor lookups & AO checks.
        val states = arrayOfNulls<BlockState>(16 * height * 16)
        val solid = BooleanArray(16 * height * 16)
        val renderable = BooleanArray(16 * height * 16)
        var actualMinY = Int.MAX_VALUE
        var actualMaxY = Int.MIN_VALUE

        for ((sy, container) in sections) {
            val secBaseY = sy shl 4
            for (ly in 0 until 16) {
                val wy = secBaseY + ly
                if (wy < yStart || wy > yEnd) continue
                val yi = wy - yStart
                val yOff = yi shl 8
                for (lz in 0 until 16) {
                    val zOff = yOff or (lz shl 4)
                    for (lx in 0 until 16) {
                        val st = container.get(lx, ly, lz)
                        if (st.isAir) continue
                        val idx = zOff or lx
                        states[idx] = st
                        val opaque = isOccluder(st)
                        solid[idx] = opaque
                        if (opaque || isSpecialVisible(st)) {
                            renderable[idx] = true
                            if (wy < actualMinY) actualMinY = wy
                            if (wy > actualMaxY) actualMaxY = wy
                        }
                    }
                }
            }
        }
        if (actualMinY > actualMaxY) return BuiltMesh(currentEpoch, payload.pos, baseY, baseY, null, 0)

        fun isSolidAt(x: Int, yi: Int, z: Int): Boolean {
            if (x !in 0..15 || z !in 0..15 || yi !in 0 until height) return false
            return solid[(yi shl 8) or (z shl 4) or x]
        }

        val out = ByteArrayOutputStream(16384)
        val scratch = ByteBuffer.allocate(VERTEX_BYTES * 4).order(ByteOrder.LITTLE_ENDIAN)
        var quads = 0

        fun emitQuad(
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

        fun packColor(uv: SpriteUv, rMul: Float, gMul: Float, bMul: Float): Int {
            val r = (uv.tintR * rMul * 255f).toInt().coerceIn(0, 255)
            val g = (uv.tintG * gMul * 255f).toInt().coerceIn(0, 255)
            val b = (uv.tintB * bMul * 255f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (b shl 16) or (g shl 8) or r
        }

        for (wy in actualMinY..actualMaxY) {
            val yi = wy - yStart
            val yOff = yi shl 8
            val ry = (wy - baseY).toFloat()
            for (lz in 0 until 16) {
                val zOff = yOff or (lz shl 4)
                val fz = lz.toFloat()
                for (lx in 0 until 16) {
                    val idx = zOff or lx
                    if (!renderable[idx]) continue
                    val st = states[idx] ?: continue
                    val uv = uvCache[st.block] ?: continue
                    val fx = lx.toFloat()
                    val h = if (st.block is SnowBlock) (st.get(SnowBlock.LAYERS) / 8f).coerceAtLeast(0.125f) else 1f
                    val yTop = ry + h
                    val u0 = uv.u0; val v0 = uv.v0; val u1 = uv.u1; val v1 = uv.v1
                    val vSideTop = v0 + (v1 - v0) * (1f - h)

                    // +Y top face
                    if (h < 1f || !isSolidAt(lx, yi + 1, lz)) {
                        var ao = 1.0f
                        if (isSolidAt(lx - 1, yi + 1, lz)) ao -= 0.06f
                        if (isSolidAt(lx + 1, yi + 1, lz)) ao -= 0.06f
                        if (isSolidAt(lx, yi + 1, lz - 1)) ao -= 0.06f
                        if (isSolidAt(lx, yi + 1, lz + 1)) ao -= 0.06f
                        val c = packColor(uv, 1.00f * ao, 1.00f * ao, 1.00f * ao)
                        emitQuad(fx, yTop, fz, u0, v0, fx, yTop, fz + 1f, u0, v1, fx + 1f, yTop, fz + 1f, u1, v1, fx + 1f, yTop, fz, u1, v0, c)
                    }
                    // -Y bottom face (skip ground base)
                    if (wy > baseY && !isSolidAt(lx, yi - 1, lz)) {
                        val c = packColor(uv, 0.52f, 0.54f, 0.59f)
                        emitQuad(fx, ry, fz, u0, v0, fx + 1f, ry, fz, u1, v0, fx + 1f, ry, fz + 1f, u1, v1, fx, ry, fz + 1f, u0, v1, c)
                    }
                    // -Z north face
                    if (!isSolidAt(lx, yi, lz - 1)) {
                        val ao = if (isSolidAt(lx, yi - 1, lz - 1)) 0.92f else 1.0f
                        val c = packColor(uv, 0.78f * ao, 0.81f * ao, 0.87f * ao)
                        emitQuad(fx + 1f, yTop, fz, u0, vSideTop, fx + 1f, ry, fz, u0, v1, fx, ry, fz, u1, v1, fx, yTop, fz, u1, vSideTop, c)
                    }
                    // +Z south face
                    if (!isSolidAt(lx, yi, lz + 1)) {
                        val ao = if (isSolidAt(lx, yi - 1, lz + 1)) 0.92f else 1.0f
                        val c = packColor(uv, 0.84f * ao, 0.85f * ao, 0.88f * ao)
                        emitQuad(fx, yTop, fz + 1f, u0, vSideTop, fx, ry, fz + 1f, u0, v1, fx + 1f, ry, fz + 1f, u1, v1, fx + 1f, yTop, fz + 1f, u1, vSideTop, c)
                    }
                    // -X west face (alpine shadow side)
                    if (!isSolidAt(lx - 1, yi, lz)) {
                        val ao = if (isSolidAt(lx - 1, yi - 1, lz)) 0.91f else 1.0f
                        val c = packColor(uv, 0.65f * ao, 0.70f * ao, 0.78f * ao)
                        emitQuad(fx, yTop, fz, u0, vSideTop, fx, ry, fz, u0, v1, fx, ry, fz + 1f, u1, v1, fx, yTop, fz + 1f, u1, vSideTop, c)
                    }
                    // +X east face (sunlit side)
                    if (!isSolidAt(lx + 1, yi, lz)) {
                        val ao = if (isSolidAt(lx + 1, yi - 1, lz)) 0.93f else 1.0f
                        val c = packColor(uv, 0.93f * ao, 0.92f * ao, 0.90f * ao)
                        emitQuad(fx + 1f, yTop, fz + 1f, u0, vSideTop, fx + 1f, ry, fz + 1f, u0, v1, fx + 1f, ry, fz, u1, v1, fx + 1f, yTop, fz, u1, vSideTop, c)
                    }
                }
            }
        }
        return BuiltMesh(currentEpoch, payload.pos, actualMinY, actualMaxY + 1, if (quads > 0) out.toByteArray() else null, quads)
    }

    private fun isOccluder(state: BlockState): Boolean =
        state.isOpaqueFullCube || state.isOf(Blocks.PACKED_ICE) || state.isOf(Blocks.BLUE_ICE) || state.isOf(Blocks.SNOW_BLOCK)

    private fun isSpecialVisible(state: BlockState): Boolean =
        state.block is SnowBlock || state.block is LeavesBlock || state.isOf(Blocks.ICE) || state.isOf(Blocks.FROSTED_ICE) ||
            state.isOf(Blocks.POWDER_SNOW) || state.isOf(Blocks.GLASS) || state.isOf(Blocks.LIGHT_BLUE_STAINED_GLASS) ||
            state.isOf(Blocks.WHITE_STAINED_GLASS)
}

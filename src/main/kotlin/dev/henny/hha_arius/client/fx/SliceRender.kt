package dev.henny.hha_arius.client.fx

import dev.henny.hha_arius.net.SliceS2C
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d

/**
 * Der Schnitt: Die Osthälfte des Tempels wird als **ein** bewegtes Blockbündel gezeichnet (keine Entities). Sie steht
 * [SliceS2C.delay] Ticks still („nichts passiert“), rutscht dann entlang der Schnittebene nach außen, sinkt und kippt.
 * Der Server hat die echten Blöcke im selben Tick entfernt und setzt am Ende Trümmer.
 */
object SliceRender {

    private class Bundle(val id: Int, val pivot: Vec3d, val dir: Vec3d, val delay: Int, val slide: Int, val blocks: List<Pair<BlockPos, BlockState>>) {
        var age = 0
    }

    private val bundles = ArrayList<Bundle>()

    fun handle(p: SliceS2C) {
        if (p.id < 0) {
            bundles.clear()
            return
        }
        bundles.removeIf { it.id == p.id }
        val list = ArrayList<Pair<BlockPos, BlockState>>(p.positions.size)
        for (i in p.positions.indices) list.add(BlockPos.fromLong(p.positions[i]) to Block.getStateFromRawId(p.states[i]))
        bundles.add(Bundle(p.id, Vec3d(p.pivotX, p.pivotY, p.pivotZ), Vec3d(p.dirX, 0.0, p.dirZ).normalize(), p.delay, p.slide, list))
    }

    fun tick() {
        bundles.removeIf { ++it.age > it.delay + it.slide + 3 }
    }

    fun clear() = bundles.clear()

    fun render(matrices: MatrixStack, consumers: VertexConsumerProvider, cam: Vec3d, tickDelta: Float) {
        if (bundles.isEmpty()) return
        val brm = MinecraftClient.getInstance().blockRenderManager
        for (b in bundles) {
            val t = b.age + tickDelta
            val s = ((t - b.delay) / b.slide).coerceIn(0f, 1f)
            val ease = s * s
            val offset = b.dir.multiply(5.5 * ease).add(0.0, -4.0 * ease * ease, 0.0)
            // Kippen um die Schnittkante (Achse senkrecht zu dir in der Waagerechten).
            val tilt = 26f * ease
            matrices.push()
            matrices.translate(b.pivot.x - cam.x + offset.x, b.pivot.y - cam.y + offset.y, b.pivot.z - cam.z + offset.z)
            if (b.dir.x >= 0) matrices.multiply(RotationAxis.NEGATIVE_Z.rotationDegrees(tilt)) else matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(tilt))
            for ((pos, state) in b.blocks) {
                matrices.push()
                matrices.translate(pos.x - b.pivot.x, pos.y - b.pivot.y, pos.z - b.pivot.z)
                brm.renderBlockAsEntity(state, matrices, consumers, LIGHT, OverlayTexture.DEFAULT_UV)
                matrices.pop()
            }
            matrices.pop()
        }
    }

    private const val LIGHT = (11 shl 4) or (15 shl 20)
}

package dev.henny.hha_arius.client.model.gauntlets

import net.minecraft.client.model.Dilation
import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData

/**
 * GENERIERT von tools/assets/boss_models.py, nicht von Hand ändern.
 * UVs passen exakt zu den Texturen unter textures/entity/.
 */
object GauntletsModelData {

    fun gauntlets(): TexturedModelData {
        val data = ModelData()
        val root = data.root
        val p0 = root.addChild("r_base", ModelPartBuilder.create().uv(34, 0).cuboid(-3.5000f, 3.0000f, -2.5000f, 5.0000f, 1.0000f, 5.0000f, Dilation(0.1200f)).uv(0, 0).cuboid(-3.0000f, 4.0000f, -2.0000f, 4.0000f, 4.0000f, 4.0000f, Dilation(0.4500f)).uv(17, 22).cuboid(-4.1500f, 4.6000f, -1.0000f, 1.0000f, 3.0000f, 2.0000f, Dilation(0.0000f)).uv(21, 9).cuboid(-3.0000f, 8.0000f, -2.0000f, 4.0000f, 1.0000f, 4.0000f, Dilation(0.3600f)).uv(38, 9).cuboid(-3.0000f, 9.0000f, -2.0000f, 4.0000f, 1.0000f, 4.0000f, Dilation(0.2600f)).uv(0, 28).cuboid(-3.7500f, 8.7000f, -1.0000f, 1.0000f, 2.0000f, 2.0000f, Dilation(-0.1200f)), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p1 = root.addChild("r_fist", ModelPartBuilder.create().uv(0, 16).cuboid(-3.0000f, 10.0000f, -2.0000f, 4.0000f, 1.0000f, 4.0000f, Dilation(0.0500f)).uv(42, 28).cuboid(-3.0000f, 9.1000f, -2.7500f, 4.0000f, 2.0000f, 1.0000f, Dilation(-0.0500f)), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p2 = root.addChild("r_open", ModelPartBuilder.create().uv(7, 28).cuboid(-3.0000f, 10.1000f, -1.4000f, 1.0000f, 2.0000f, 2.0000f, Dilation(-0.1400f)).uv(24, 22).cuboid(-2.0000f, 10.1000f, -1.4000f, 1.0000f, 3.0000f, 2.0000f, Dilation(-0.1400f)).uv(31, 22).cuboid(-1.0000f, 10.1000f, -1.4000f, 1.0000f, 3.0000f, 2.0000f, Dilation(-0.1400f)).uv(14, 28).cuboid(0.0000f, 10.1000f, -1.4000f, 1.0000f, 2.0000f, 2.0000f, Dilation(-0.1400f)).uv(0, 33).cuboid(-1.5000f, 9.4000f, -2.9000f, 2.0000f, 2.0000f, 1.0000f, Dilation(-0.1200f)), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p3 = root.addChild("l_base", ModelPartBuilder.create().uv(0, 9).cuboid(-1.5000f, 3.0000f, -2.5000f, 5.0000f, 1.0000f, 5.0000f, Dilation(0.1200f)).uv(17, 0).cuboid(-1.0000f, 4.0000f, -2.0000f, 4.0000f, 4.0000f, 4.0000f, Dilation(0.4500f)).uv(38, 22).cuboid(3.1500f, 4.6000f, -1.0000f, 1.0000f, 3.0000f, 2.0000f, Dilation(0.0000f)).uv(17, 16).cuboid(-1.0000f, 8.0000f, -2.0000f, 4.0000f, 1.0000f, 4.0000f, Dilation(0.3600f)).uv(34, 16).cuboid(-1.0000f, 9.0000f, -2.0000f, 4.0000f, 1.0000f, 4.0000f, Dilation(0.2600f)).uv(21, 28).cuboid(2.7500f, 8.7000f, -1.0000f, 1.0000f, 2.0000f, 2.0000f, Dilation(-0.1200f)), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p4 = root.addChild("l_fist", ModelPartBuilder.create().uv(0, 22).cuboid(-1.0000f, 10.0000f, -2.0000f, 4.0000f, 1.0000f, 4.0000f, Dilation(0.0500f)).uv(53, 28).cuboid(-1.0000f, 9.1000f, -2.7500f, 4.0000f, 2.0000f, 1.0000f, Dilation(-0.0500f)), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p5 = root.addChild("l_open", ModelPartBuilder.create().uv(28, 28).cuboid(2.0000f, 10.1000f, -1.4000f, 1.0000f, 2.0000f, 2.0000f, Dilation(-0.1400f)).uv(45, 22).cuboid(1.0000f, 10.1000f, -1.4000f, 1.0000f, 3.0000f, 2.0000f, Dilation(-0.1400f)).uv(52, 22).cuboid(-0.0000f, 10.1000f, -1.4000f, 1.0000f, 3.0000f, 2.0000f, Dilation(-0.1400f)).uv(35, 28).cuboid(-1.0000f, 10.1000f, -1.4000f, 1.0000f, 2.0000f, 2.0000f, Dilation(-0.1400f)).uv(7, 33).cuboid(-0.5000f, 9.4000f, -2.9000f, 2.0000f, 2.0000f, 1.0000f, Dilation(-0.1200f)), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        return TexturedModelData.of(data, 64, 64)
    }
}

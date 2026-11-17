package dev.henny.hha_arius.client.model.armor

import net.minecraft.client.model.Dilation
import net.minecraft.client.model.ModelData
import net.minecraft.client.model.ModelPartBuilder
import net.minecraft.client.model.ModelTransform
import net.minecraft.client.model.TexturedModelData

/**
 * GENERIERT von tools/assets/boss_models.py, nicht von Hand ändern.
 * UVs passen exakt zu den Texturen unter textures/entity/.
 */
object ArmorModelData {

    fun armor(): TexturedModelData {
        val data = ModelData()
        val root = data.root
        val p0 = root.addChild("helm_head", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p1 = p0.addChild("helm_horn_ar_0", ModelPartBuilder.create().uv(36, 0).cuboid(-1.0000f, -1.0000f, 0.0000f, 2.0000f, 2.0000f, 2.5000f, Dilation(0.0000f)), ModelTransform.of(-3.7500f, -8.7500f, -2.7500f, 0.4189f, -0.2443f, -0.1047f))
        val p2 = p1.addChild("helm_horn_ar_1", ModelPartBuilder.create().uv(54, 0).cuboid(-0.7500f, -0.7500f, 0.0000f, 1.5000f, 1.5000f, 2.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, 2.5000f, 0.2094f, 0.0000f, 0.0000f))
        val p3 = p2.addChild("helm_horn_ar_2", ModelPartBuilder.create().uv(88, 0).cuboid(-0.5000f, -0.5000f, 0.0000f, 1.0000f, 1.0000f, 2.0000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, 2.5000f, 0.2094f, 0.0000f, 0.0000f))
        val p4 = p3.addChild("helm_horn_ar_3", ModelPartBuilder.create().uv(2, 7).cuboid(-0.2500f, -0.2500f, 0.0000f, 0.5000f, 0.5000f, 1.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, 2.0000f, 0.2094f, 0.0000f, 0.0000f))
        val p5 = p0.addChild("helm_horn_br_0", ModelPartBuilder.create().uv(62, 0).cuboid(-0.7500f, -0.7500f, 0.0000f, 1.5000f, 1.5000f, 2.0000f, Dilation(0.0000f)), ModelTransform.of(-4.2500f, -7.5000f, -0.2500f, 0.2443f, -0.3491f, -0.1396f))
        val p6 = p5.addChild("helm_horn_br_1", ModelPartBuilder.create().uv(94, 0).cuboid(-0.5000f, -0.5000f, 0.0000f, 1.0000f, 1.0000f, 2.0000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, 2.0000f, 0.2094f, 0.0000f, 0.0000f))
        val p7 = p6.addChild("helm_horn_br_2", ModelPartBuilder.create().uv(6, 7).cuboid(-0.2500f, -0.2500f, 0.0000f, 0.5000f, 0.5000f, 1.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, 2.0000f, 0.2094f, 0.0000f, 0.0000f))
        val p8 = p0.addChild("helm_horn_cr_0", ModelPartBuilder.create().uv(118, 0).cuboid(-0.5000f, -0.5000f, 0.0000f, 1.0000f, 1.0000f, 1.5000f, Dilation(0.0000f)), ModelTransform.of(-4.2500f, -6.2500f, 2.2500f, 0.0698f, -0.3840f, -0.1047f))
        val p9 = p8.addChild("helm_horn_cr_1", ModelPartBuilder.create().uv(10, 7).cuboid(-0.2500f, -0.2500f, 0.0000f, 0.5000f, 0.5000f, 1.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, 1.5000f, 0.1745f, 0.0000f, 0.0000f))
        val p10 = p0.addChild("helm_horn_al_0", ModelPartBuilder.create().uv(45, 0).cuboid(-1.0000f, -1.0000f, 0.0000f, 2.0000f, 2.0000f, 2.5000f, Dilation(0.0000f)), ModelTransform.of(3.7500f, -8.7500f, -2.7500f, 0.4189f, 0.2443f, 0.1047f))
        val p11 = p10.addChild("helm_horn_al_1", ModelPartBuilder.create().uv(100, 0).cuboid(-0.7500f, -0.7500f, 0.0000f, 1.5000f, 1.5000f, 1.5000f, Dilation(0.0000f)).uv(34, 7).cuboid(0.2500f, -0.7500f, 1.5000f, 0.5000f, 0.5000f, 0.5000f, Dilation(0.0000f)).uv(36, 7).cuboid(-0.7500f, 0.0000f, 1.5000f, 0.5000f, 0.5000f, 0.5000f, Dilation(0.0000f)), ModelTransform.of(-0.0000f, 0.0000f, 2.5000f, 0.2094f, -0.0000f, -0.0000f))
        val p12 = p0.addChild("helm_horn_bl_0", ModelPartBuilder.create().uv(69, 0).cuboid(-0.7500f, -0.7500f, 0.0000f, 1.5000f, 1.5000f, 2.0000f, Dilation(0.0000f)), ModelTransform.of(4.2500f, -7.5000f, -0.2500f, 0.2443f, 0.3491f, 0.1396f))
        val p13 = p12.addChild("helm_horn_bl_1", ModelPartBuilder.create().uv(106, 0).cuboid(-0.5000f, -0.5000f, 0.0000f, 1.0000f, 1.0000f, 2.0000f, Dilation(0.0000f)), ModelTransform.of(-0.0000f, 0.0000f, 2.0000f, 0.2094f, -0.0000f, -0.0000f))
        val p14 = p13.addChild("helm_horn_bl_2", ModelPartBuilder.create().uv(14, 7).cuboid(-0.2500f, -0.2500f, 0.0000f, 0.5000f, 0.5000f, 1.5000f, Dilation(0.0000f)), ModelTransform.of(-0.0000f, 0.0000f, 2.0000f, 0.2094f, -0.0000f, -0.0000f))
        val p15 = p0.addChild("helm_horn_cl_0", ModelPartBuilder.create().uv(123, 0).cuboid(-0.5000f, -0.5000f, 0.0000f, 1.0000f, 1.0000f, 1.5000f, Dilation(0.0000f)), ModelTransform.of(4.2500f, -6.2500f, 2.2500f, 0.0698f, 0.3840f, 0.1047f))
        val p16 = p15.addChild("helm_horn_cl_1", ModelPartBuilder.create().uv(18, 7).cuboid(-0.2500f, -0.2500f, 0.0000f, 0.5000f, 0.5000f, 1.5000f, Dilation(0.0000f)), ModelTransform.of(-0.0000f, 0.0000f, 1.5000f, 0.1745f, -0.0000f, -0.0000f))
        val p17 = root.addChild("chest_body", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p18 = root.addChild("chest_heart", ModelPartBuilder.create().uv(26, 7).cuboid(-0.2500f, -2.5000f, -0.2500f, 0.5000f, 1.5000f, 0.5000f, Dilation(0.0000f)).uv(0, 7).cuboid(-0.2500f, 1.0000f, -0.2500f, 0.5000f, 2.0000f, 0.5000f, Dilation(0.0000f)).uv(28, 7).cuboid(-2.2500f, -0.2500f, -0.2500f, 1.0000f, 0.5000f, 0.5000f, Dilation(0.0000f)).uv(31, 7).cuboid(1.2500f, -0.2500f, -0.2500f, 1.0000f, 0.5000f, 0.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 2.7500f, -3.2500f, 0.0000f, 0.0000f, 0.0000f))
        val p19 = p18.addChild("chest_heart_setting", ModelPartBuilder.create().uv(112, 0).cuboid(-1.2500f, -1.2500f, -0.5000f, 2.5000f, 2.5000f, 0.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7854f))
        val p20 = p18.addChild("chest_heart_jewel", ModelPartBuilder.create().uv(22, 7).cuboid(-0.7500f, -0.7500f, -0.5000f, 1.5000f, 1.5000f, 0.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.7854f))
        val p21 = root.addChild("chest_arm_r", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p22 = root.addChild("chest_wing_r", ModelPartBuilder.create(), ModelTransform.of(-3.5000f, -2.5000f, 1.7500f, 0.0000f, 0.0000f, 0.0000f))
        val p23 = p22.addChild("chest_wing_r0", ModelPartBuilder.create().uv(0, 0).cuboid(-0.5000f, -1.0000f, 0.0000f, 0.5000f, 2.0000f, 4.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, -0.3840f, -0.2094f, 0.0000f))
        val p24 = p22.addChild("chest_wing_r1", ModelPartBuilder.create().uv(20, 0).cuboid(-0.5000f, -0.7500f, 0.0000f, 0.5000f, 1.5000f, 3.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.7500f, 0.0000f, -0.7330f, -0.1571f, 0.0000f))
        val p25 = p22.addChild("chest_wing_r2", ModelPartBuilder.create().uv(76, 0).cuboid(-0.5000f, -0.5000f, 0.0000f, 0.5000f, 1.0000f, 2.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 1.5000f, -0.2500f, -1.1170f, -0.1047f, 0.0000f))
        val p26 = root.addChild("chest_forearm_r", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p27 = root.addChild("chest_arm_l", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p28 = root.addChild("chest_wing_l", ModelPartBuilder.create(), ModelTransform.of(3.5000f, -2.5000f, 1.7500f, 0.0000f, 0.0000f, 0.0000f))
        val p29 = p28.addChild("chest_wing_l0", ModelPartBuilder.create().uv(10, 0).cuboid(-0.0000f, -1.0000f, 0.0000f, 0.5000f, 2.0000f, 4.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, -0.3840f, 0.2094f, 0.0000f))
        val p30 = p28.addChild("chest_wing_l1", ModelPartBuilder.create().uv(28, 0).cuboid(-0.0000f, -0.7500f, 0.0000f, 0.5000f, 1.5000f, 3.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 0.7500f, 0.0000f, -0.7330f, 0.1571f, 0.0000f))
        val p31 = p28.addChild("chest_wing_l2", ModelPartBuilder.create().uv(82, 0).cuboid(-0.0000f, -0.5000f, 0.0000f, 0.5000f, 1.0000f, 2.5000f, Dilation(0.0000f)), ModelTransform.of(0.0000f, 1.5000f, -0.2500f, -1.1170f, 0.1047f, 0.0000f))
        val p32 = root.addChild("chest_forearm_l", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p33 = root.addChild("legs_body", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p34 = root.addChild("legs_tasset_fr", ModelPartBuilder.create(), ModelTransform.of(-2.2500f, 10.0000f, -3.5000f, 0.0000f, 0.0000f, 0.0000f))
        val p35 = root.addChild("legs_leg_r", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p36 = root.addChild("legs_tasset_fl", ModelPartBuilder.create(), ModelTransform.of(2.2500f, 10.0000f, -3.5000f, 0.0000f, 0.0000f, 0.0000f))
        val p37 = root.addChild("legs_leg_l", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p38 = root.addChild("legs_tasset_b", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 10.0000f, 3.2500f, 0.0000f, 0.0000f, 0.0000f))
        val p39 = root.addChild("boots_leg_r", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        val p40 = root.addChild("boots_leg_l", ModelPartBuilder.create(), ModelTransform.of(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f))
        return TexturedModelData.of(data, 128, 128)
    }
}

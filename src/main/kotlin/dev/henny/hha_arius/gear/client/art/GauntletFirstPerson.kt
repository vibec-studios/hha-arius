package dev.henny.hha_arius.gear.client.art

import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.ArtTimings
import dev.henny.hha_arius.gear.art.GauntletsItem
import dev.henny.hha_arius.gear.art.School
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.bezier
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.breathe
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.clamp01
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.easeIn
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.easeOut
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.easeOutCubic
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.lerp
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.seg
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.smooth
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.item.HeldItemRenderer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.player.PlayerModelPart
import net.minecraft.entity.player.PlayerSkinType
import net.minecraft.item.ItemDisplayContext
import net.minecraft.item.ItemStack
import net.minecraft.util.Arm
import net.minecraft.util.Hand
import net.minecraft.util.math.RotationAxis
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Haltung der ersten Person im Blickraum (Blöcke; +x rechts, +y oben, -z vorn), rechtshändig gedacht. */
internal class FpPose {
    /** Hand (Mitte des Handendes), gedachte Schulter (für die Armrichtung), Richtung der Handfläche. */
    val rH = Vector3f(); val rS = Vector3f(); val rN = Vector3f()
    val lH = Vector3f(); val lS = Vector3f(); val lN = Vector3f()

    /** Neigung der ganzen Hand-Anordnung um die Blickachse (rad, + = gegen den Uhrzeigersinn) und Absenken. */
    var roll = 0f
    var dy = 0f

    fun set(o: FpPose): FpPose {
        rH.set(o.rH); rS.set(o.rS); rN.set(o.rN); lH.set(o.lH); lS.set(o.lS); lN.set(o.lN); roll = o.roll; dy = o.dy
        return this
    }

    fun mix(a: FpPose, b: FpPose, t: Float): FpPose {
        if (t <= 0f) return set(a)
        if (t >= 1f) return set(b)
        ArtPoseMath.lerpV(a.rH, b.rH, t, rH); ArtPoseMath.lerpV(a.rS, b.rS, t, rS); ArtPoseMath.nlerp(a.rN, b.rN, t, rN)
        ArtPoseMath.lerpV(a.lH, b.lH, t, lH); ArtPoseMath.lerpV(a.lS, b.lS, t, lS); ArtPoseMath.nlerp(a.lN, b.lN, t, lN)
        roll = lerp(a.roll, b.roll, t); dy = lerp(a.dy, b.dy, t)
        return this
    }

    fun shoulders(): FpPose {
        rS.set(0.62f, -1.05f, -0.38f); lS.set(-0.62f, -1.05f, -0.40f); roll = 0f; dy = 0f
        return this
    }
}

/**
 * Erste Person: Mit den Kampfhandschuhen in der Haupthand zeichnet der Mixin auf
 * [HeldItemRenderer.renderFirstPersonItem] hier BEIDE Arme (Spieler-Skin + Handschuh); der Nebenhand-Aufruf wird
 * unterdrückt. Mit Solvane übernimmt er nur während Haltung und Schnitt des Klaren Schnitts.
 */
internal object GauntletFirstPerson {

    private val pose = FpPose()
    private val a1 = FpPose()
    private val a2 = FpPose()
    private val a3 = FpPose()
    private val from = FpPose()
    private val last = FpPose()
    private var hasLast = false
    private var key = Long.MIN_VALUE
    private var fromTime = 0f
    private var blendDur = 2f
    private var blending = false

    /** true = der Aufruf ist erledigt (Vanilla überspringen). */
    @JvmStatic
    fun render(
        renderer: HeldItemRenderer, player: AbstractClientPlayerEntity, tickProgress: Float, hand: Hand,
        item: ItemStack, equipProgress: Float, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int,
    ): Boolean {
        val main = player.mainHandStack
        val anim = ArtAnims.get(player.id)
        val art = anim != null && (anim.anim == Anim.ART_CHARGE || anim.anim == Anim.ART_RELEASE)
        if (hand == Hand.OFF_HAND) {
            // Nebenhand ausblenden: sie würde durch die Handschuhe bzw. den beidhändigen Griff schneiden.
            return GauntletsItem.isGauntlets(main) || (art && main.isOf(GearItems.SOLVANE))
        }
        if (GauntletsItem.isGauntlets(item)) {
            renderGauntlets(player, tickProgress, item, anim, equipProgress, matrices, queue, light)
            return true
        }
        if (art && item.isOf(GearItems.SOLVANE)) {
            renderSolvane(renderer, player, tickProgress, item, anim, equipProgress, matrices, queue, light)
            return true
        }
        return false
    }

    // --- Handschuhe --------------------------------------------------------------------------------------------------

    private val q = Quaternionf()
    private val tmp = Vector3f()
    private val dir = Vector3f()
    private val pivot = Vector3f()
    private val glowPts = Vector3f()

    private fun renderGauntlets(
        player: AbstractClientPlayerEntity, td: Float, stack: ItemStack, anim: ArtAnims.State?, equip: Float,
        matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int,
    ) {
        val now = ArtAnims.ticks + td
        val magic = GauntletLook.displayMode(stack, anim, td) == dev.henny.hha_arius.gear.art.GauntletMode.MAGIC
        build(anim, anim?.age(td) ?: 0f, now, magic, pose)

        // Weiche Übergänge zwischen Animationen (aus der zuletzt gezeigten Haltung).
        val k = if (anim == null) -1L else (anim.start shl 8) or anim.anim.ordinal.toLong()
        if (k != key) {
            if (hasLast) { from.set(last); fromTime = now; blendDur = blendTicks(anim); blending = true }
            key = k
        }
        if (blending) {
            val f = smooth((now - fromTime) / blendDur)
            if (f >= 1f) blending = false
            a1.set(pose); pose.mix(from, a1, f)
        }
        last.set(pose); hasLast = true

        val leftHanded = player.mainArm == Arm.LEFT
        val slim = player.skin.model() == PlayerSkinType.SLIM
        val open = GauntletLook.open(stack, anim, td)
        val glow = GauntletLook.glow(stack, anim, td, now)
        val glowB = ArtVisualGlow.Builder(ArtVisualGlow.VIEW_RIGHT, ArtVisualGlow.VIEW_UP)

        matrices.push()
        matrices.translate(0f, -equip * 0.6f + pose.dy, 0f)
        if (pose.roll != 0f) matrices.multiply(RotationAxis.POSITIVE_Z.rotation(if (leftHanded) -pose.roll else pose.roll))
        for (canonRight in booleanArrayOf(true, false)) {
            // Rechtshändig gedachte Pose; Linkshänder: gespiegelt (x → -x), Seiten getauscht.
            val right = if (leftHanded) !canonRight else canonRight
            val h = if (canonRight) pose.rH else pose.lH
            val s = if (canonRight) pose.rS else pose.lS
            val n = if (canonRight) pose.rN else pose.lN
            val hv = Vector3f(h); val sv = Vector3f(s); val nv = Vector3f(n)
            if (leftHanded) { hv.x = -hv.x; sv.x = -sv.x; nv.x = -nv.x }
            arm(player, matrices, queue, light, right, hv, sv, nv, slim, true, open, glow)
            handGlow(matrices, glowB, anim, td, right, slim, canonRight)
        }
        if (anim != null && anim.anim == Anim.WHITE_HOLE) whiteHoleCore(matrices, glowB, anim.age(td), leftHanded)
        matrices.pop()
        glowB.submit(queue, VfxLayers.GLOW_ADD)
    }

    /** Ein Arm: Handende auf [h], Arm entlang (h - s), Handfläche nach [n]. Danach steht [matrices] wieder wie vorher. */
    private fun arm(
        player: AbstractClientPlayerEntity, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int,
        right: Boolean, h: Vector3f, s: Vector3f, n: Vector3f, slim: Boolean, gloves: Boolean, open: Boolean, glow: Float,
    ) {
        dir.set(h).sub(s)
        ArtPoseMath.armFrame(dir, n, right, q)
        val cx = if (right) (if (slim) -0.5f else -1f) else (if (slim) 0.5f else 1f)
        q.transform(tmp.set(cx / 16f, 10f / 16f, 0f))
        pivot.set(h).sub(tmp)
        matrices.push()
        matrices.translate(pivot.x, pivot.y, pivot.z)
        matrices.multiply(q)
        if (!player.isInvisible) {
            // renderArm setzt den Arm auf seine Grundlage (Drehpunkt ∓5,2,0, Rollen ±0,1) – hier vorab ausgleichen.
            matrices.push()
            matrices.multiply(RotationAxis.POSITIVE_Z.rotation(if (right) -0.1f else 0.1f))
            matrices.translate((if (right) 5f else -5f) / 16f, -2f / 16f, 0f)
            val renderer = MinecraftClient.getInstance().entityRenderDispatcher.getPlayerRenderer(player)
            val skin = player.skin.body().texturePath()
            if (right) renderer.renderRightArm(matrices, queue, light, skin, player.isModelPartVisible(PlayerModelPart.RIGHT_SLEEVE))
            else renderer.renderLeftArm(matrices, queue, light, skin, player.isModelPartVisible(PlayerModelPart.LEFT_SLEEVE))
            matrices.pop()
        }
        if (gloves) GauntletParts.submit(queue, matrices, right, slim, open, glow, light)
        matrices.pop()
    }

    /** Energie an Handfläche/Fingern beim Zaubern (erste Person). Muss im selben Zustand wie [arm] gerufen werden. */
    private fun handGlow(matrices: MatrixStack, b: ArtVisualGlow.Builder, anim: ArtAnims.State?, td: Float, right: Boolean, slim: Boolean, canonRight: Boolean) {
        if (anim == null) return
        val fx = ArtVisualGlow.fx()
        if (fx <= 0f) return
        val a = anim.age(td)
        val school = School.entries.getOrElse(anim.param) { School.WIND }
        val strength = when (anim.anim) {
            Anim.CAST_CHARGE -> 0.45f + 0.55f * clamp01(a / 30f)
            Anim.CAST_TAP -> if (canonRight) 1f - seg(a, 1f, ArtTimings.CAST_TAP.toFloat()) else 0f
            Anim.CAST_RELEASE -> 1f - seg(a, 2f, ArtTimings.CAST_RELEASE.toFloat())
            Anim.PLACE_AIM -> if (canonRight) 0.6f else 0f
            Anim.SHIELD -> 0.35f
            else -> 0f
        } * fx
        if (strength <= 0.01f) return
        val color = if (anim.anim == Anim.SHIELD) GauntletLook.schoolColor(School.YANG) else GauntletLook.schoolColor(school)
        val core = if (anim.anim == Anim.SHIELD) GauntletLook.schoolCore(School.YANG) else GauntletLook.schoolCore(school)
        // Lage wie in arm(): Handende auf dem Zielpunkt.
        val m = matrices.peek().positionMatrix
        val local = GauntletParts.palmPoint(right, slim, Vector3f()).mul(1f / 16f)
        q.transform(local).add(pivotOf(right))
        m.transformPosition(local, glowPts)
        b.billboard(glowPts, 0.05f + 0.05f * strength, ArtVisualGlow.alpha(core, 0.8f * strength))
        b.billboard(glowPts, 0.14f + 0.1f * strength, ArtVisualGlow.alpha(color, 0.45f * strength))
    }

    /** handGlow braucht den Drehpunkt des zuletzt gezeichneten Arms. */
    private fun pivotOf(@Suppress("UNUSED_PARAMETER") right: Boolean): Vector3f = pivot

    private fun whiteHoleCore(matrices: MatrixStack, b: ArtVisualGlow.Builder, a: Float, leftHanded: Boolean) {
        val fx = ArtVisualGlow.fx()
        if (fx <= 0f) return
        val grow = smooth(a / ArtTimings.WHITE_HOLE_CAST)
        val fade = 1f - seg(a, ArtTimings.WHITE_HOLE - 4f, ArtTimings.WHITE_HOLE.toFloat())
        val c = Vector3f((pose.rH.x + pose.lH.x) / 2f * (if (leftHanded) -1f else 1f), (pose.rH.y + pose.lH.y) / 2f + 0.02f, (pose.rH.z + pose.lH.z) / 2f - 0.03f)
        matrices.peek().positionMatrix.transformPosition(c)
        val k = grow * fade * fx
        val pulse = 1f + 0.06f * sin(a * 0.5f)
        b.billboard(c, (0.025f + 0.035f * grow) * pulse, ArtVisualGlow.alpha(0xFFFFFFFF.toInt(), k))
        b.billboard(c, (0.08f + 0.1f * grow) * pulse, ArtVisualGlow.alpha(0xFFDDF2FF.toInt(), 0.5f * k))
    }

    private fun blendTicks(anim: ArtAnims.State?): Float {
        if (anim == null) return 3f
        val w = anim.param.coerceAtLeast(1).toFloat()
        return when (anim.anim) {
            Anim.JAB, Anim.CROSS -> minOf(1.4f, w * 0.7f)
            Anim.HOOK, Anim.COUNTER -> minOf(1.6f, w * 0.5f)
            Anim.SIDE_KICK, Anim.SPIN_KICK -> 2f
            Anim.PARRY, Anim.CAST_TAP, Anim.CAST_RELEASE -> 1.4f
            else -> 2.5f
        }
    }

    // --- Haltungen (erste Person) ------------------------------------------------------------------------------------

    private fun guard(p: FpPose, t: Float) {
        p.shoulders()
        val b = 0.01f * breathe(t)
        p.rH.set(0.36f, -0.30f + b, -0.80f); p.rS.set(0.72f, -1.05f, -0.32f); p.rN.set(-0.8f, 0f, 0.6f)
        p.lH.set(-0.30f, -0.25f + 0.01f * breathe(t, phase = 0.6f), -0.92f); p.lS.set(-0.70f, -1.05f, -0.40f); p.lN.set(0.8f, 0f, 0.6f)
    }

    private fun magicIdle(p: FpPose, t: Float) {
        p.shoulders()
        p.rH.set(0.42f, -0.48f + 0.012f * breathe(t, 0.07f), -0.82f); p.rS.set(0.62f, -1.15f, -0.40f); p.rN.set(-0.7f, 0.7f, 0f)
        p.lH.set(-0.40f, -0.50f + 0.012f * breathe(t, 0.07f, 1f), -0.84f); p.lS.set(-0.62f, -1.15f, -0.42f); p.lN.set(0.7f, 0.7f, 0f)
    }

    private fun idle(p: FpPose, t: Float, magic: Boolean) = if (magic) magicIdle(p, t) else guard(p, t)

    private fun strikeK(a: Float, w: Float, d: Float): Float = when {
        a < w -> easeOutCubic(a / w)
        a < w + 1f -> 1f
        else -> 1f - smooth(seg(a, w + 1f, d))
    }

    private val HOOK_C = Vector3f(-0.50f, -0.44f, -1.18f)
    private val WIND_C = Vector3f(0.25f, -0.20f, -1.25f)

    private fun build(s: ArtAnims.State?, a: Float, t: Float, magic: Boolean, p: FpPose) {
        if (s == null) { idle(p, t, magic); return }
        val w = s.param.coerceAtLeast(1).toFloat()
        val d = if (s.duration > 0) s.duration.toFloat() else w + 6f
        when (s.anim) {
            Anim.JAB -> {
                guard(a1, t)
                a2.set(a1).apply { lH.set(-0.06f, -0.14f, -1.38f); lS.set(-0.42f, -1.3f, -0.85f); lN.set(0f, -1f, 0f); rH.set(0.34f, -0.26f, -0.72f) }
                p.mix(a1, a2, strikeK(a, w, d))
            }
            Anim.CROSS -> {
                guard(a1, t)
                a2.set(a1).apply { rH.set(0.05f, -0.14f, -1.40f); rS.set(0.42f, -1.3f, -0.85f); rN.set(0f, -1f, 0f); lH.set(-0.26f, -0.22f, -0.74f) }
                p.mix(a1, a2, strikeK(a, w, d))
            }
            Anim.HOOK -> {
                guard(a1, t)
                a2.set(a1).apply { lH.set(-0.62f, -0.46f, -0.78f); lS.set(-0.75f, -1.1f, -0.30f); lN.set(0f, -1f, 0f); dy = -0.02f }
                a3.set(a1).apply { lH.set(0.02f, -0.40f, -1.02f); lS.set(-0.55f, -0.95f, -0.55f); lN.set(0f, -1f, 0.3f); roll = -0.05f }
                val load = 0.4f * w
                when {
                    a < load -> p.mix(a1, a2, easeOut(a / load))
                    a < w -> { val u = seg(a, load, w); p.mix(a2, a3, u); bezier(a2.lH, HOOK_C, a3.lH, u, p.lH) }
                    a < w + 1f -> p.set(a3)
                    else -> p.mix(a3, a1, smooth(seg(a, w + 1f, d)))
                }
            }
            Anim.SIDE_KICK -> {
                // Tritt: Arme fangen das Gleichgewicht ab, die Hand-Anordnung neigt sich leicht.
                guard(a1, t)
                a2.set(a1).apply { rH.set(0.48f, -0.46f, -0.72f); lH.set(-0.24f, -0.22f, -0.82f); roll = 0.05f }
                a3.set(a1).apply { rH.set(0.66f, -0.70f, -0.60f); rS.set(0.80f, -1.2f, -0.2f); rN.set(-0.3f, -1f, 0f); lH.set(-0.18f, -0.18f, -0.78f); roll = 0.08f; dy = -0.02f }
                val ch = 0.55f * w
                when {
                    a < ch -> p.mix(a1, a2, easeOut(a / ch))
                    a < w -> p.mix(a2, a3, easeOutCubic(seg(a, ch, w)))
                    a < w + 1.5f -> p.set(a3)
                    else -> p.mix(a3, a1, smooth(seg(a, w + 1.5f, d)))
                }
            }
            Anim.SPIN_KICK -> {
                guard(a1, t)
                a2.set(a1).apply { rH.set(0.25f, -0.40f, -0.62f); lH.set(-0.20f, -0.38f, -0.64f); roll = -0.04f; dy = -0.02f }
                a3.set(a1).apply { rH.set(0.85f, -0.55f, -0.55f); rN.set(0f, -1f, 0f); lH.set(-0.60f, -0.30f, -0.75f); lN.set(0f, -1f, 0f); roll = 0.06f }
                when {
                    a < 0.6f * w -> p.mix(a1, a2, easeOut(seg(a, 0f, 0.6f * w)))
                    a < w -> p.mix(a2, a3, easeOutCubic(seg(a, 0.6f * w, w)))
                    a < w + 2f -> p.set(a3)
                    else -> p.mix(a3, a1, smooth(seg(a, w + 2f, d)))
                }
            }
            Anim.PARRY -> {
                guard(a1, t); parry(a2)
                p.mix(a1, a2, if (a < w) easeOut(a / 2f) else 1f - smooth(seg(a, w, maxOf(d, w + 1f))))
            }
            Anim.COUNTER -> {
                parry(a1); guard(a3, t)
                a2.set(a3).apply { rH.set(0.04f, -0.16f, -1.38f); rS.set(0.40f, -1.3f, -0.85f); rN.set(0f, -0.4f, -1f); lH.set(-0.26f, -0.22f, -0.74f) }
                when {
                    a < w -> p.mix(a1, a2, easeOutCubic(a / w))
                    a < w + 1f -> p.set(a2)
                    else -> p.mix(a2, a3, smooth(seg(a, w + 1f, d)))
                }
            }
            Anim.MODE_SWITCH -> {
                val u = seg(a, 0f, ArtTimings.MODE_SWITCH.toFloat())
                idle(a1, t, s.param != 1); idle(a2, t, s.param == 1)
                p.mix(a1, a2, smooth(u))
                a3.set(p).apply { rH.set(0.22f, -0.40f, -0.75f); lH.set(-0.22f, -0.40f, -0.75f); rN.set(-1f, 0f, 0f); lN.set(1f, 0f, 0f) }
                a1.set(p)
                p.mix(a1, a3, sin(u * PI.toFloat()))
            }
            Anim.CAST_TAP -> {
                magicIdle(a1, t)
                val school = School.entries.getOrElse(s.param) { School.WIND }
                if (school == School.WIND) {
                    a2.set(a1).apply { rH.set(0.60f, -0.30f, -0.70f); rN.set(-1f, 0f, 0f) }
                    a3.set(a2).apply { rH.set(-0.25f, -0.22f, -1.05f) }
                    when {
                        a < 0.8f -> p.mix(a1, a2, easeOut(a / 0.8f))
                        a < 2.2f -> { val u = easeOut(seg(a, 0.8f, 2.2f)); p.mix(a2, a3, u); bezier(a2.rH, WIND_C, a3.rH, u, p.rH) }
                        a < 3f -> p.set(a3)
                        else -> p.mix(a3, a1, smooth(seg(a, 3f, ArtTimings.CAST_TAP.toFloat())))
                    }
                } else {
                    a2.set(a1).apply {
                        rS.set(0.42f, -1.3f, -0.85f)
                        if (school == School.ICE) { rH.set(0.10f, -0.20f, -1.30f); rN.set(0f, -1f, 0f) }
                        else { rH.set(0.10f, -0.08f, -1.25f); rN.set(0f, 0f, -1f) }
                    }
                    p.mix(a1, a2, when {
                        a < 2f -> easeOutCubic(a / 2f)
                        a < 3f -> 1f
                        else -> 1f - smooth(seg(a, 3f, ArtTimings.CAST_TAP.toFloat()))
                    })
                }
            }
            Anim.CAST_CHARGE -> {
                magicIdle(a1, t)
                val school = School.entries.getOrElse(s.param) { School.WIND }
                // ≥ 0,22: sonst kreuzen sich die Fingerspitzen der zusammenlaufenden Arme.
                val sep = 0.26f - 0.03f * clamp01(a / 40f)
                var ox = 0f; var oy = 0f
                if (school == School.WIND) { ox = 0.02f * sin(t * 0.15f); oy = 0.02f * cos(t * 0.15f) }
                val cy = if (school == School.YANG) -0.30f else -0.36f
                a2.shoulders().apply {
                    rH.set(sep + ox, cy + oy, -0.74f); rS.set(0.55f, -1.0f, -0.40f); rN.set(-1f, 0f, 0f)
                    lH.set(-sep - ox, cy - oy, -0.74f); lS.set(-0.55f, -1.0f, -0.40f); lN.set(1f, 0f, 0f)
                }
                p.mix(a1, a2, easeOut(a / 5f))
            }
            Anim.CAST_RELEASE -> {
                magicIdle(a1, t)
                a2.shoulders().apply {
                    rH.set(0.20f, -0.22f, -1.28f); rS.set(0.42f, -1.3f, -0.85f); rN.set(-1f, -0.4f, 0f)
                    lH.set(-0.20f, -0.22f, -1.28f); lS.set(-0.42f, -1.3f, -0.85f); lN.set(1f, -0.4f, 0f)
                }
                p.mix(a1, a2, if (a < 3f) easeOutCubic(a / 2f) else 1f - smooth(seg(a, 3f, ArtTimings.CAST_RELEASE.toFloat())))
            }
            Anim.PLACE_AIM -> {
                magicIdle(a1, t)
                a2.set(a1).apply { rH.set(0.12f, -0.16f, -1.25f); rS.set(0.42f, -1.3f, -0.80f); rN.set(0f, -1f, 0f) }
                p.mix(a1, a2, easeOut(a / 4f))
            }
            Anim.SHIELD -> {
                idle(a1, t, magic)
                val b = 0.008f * breathe(t, 0.08f)
                a2.shoulders().apply {
                    rH.set(0.30f, -0.06f + b, -0.80f); rS.set(0.62f, -1.0f, -0.45f); rN.set(0f, 0f, -1f)
                    lH.set(-0.30f, -0.06f + b, -0.80f); lS.set(-0.62f, -1.0f, -0.45f); lN.set(0f, 0f, -1f)
                }
                p.mix(a1, a2, easeOut(a / 4f))
            }
            Anim.GLIDE -> {
                idle(a1, t, magic)
                a2.shoulders().apply {
                    rH.set(0.80f, -0.52f, -0.72f); rS.set(0.55f, -1.0f, -0.30f); rN.set(0f, -1f, 0f)
                    lH.set(-0.80f, -0.52f, -0.72f); lS.set(-0.55f, -1.0f, -0.30f); lN.set(0f, -1f, 0f)
                    roll = 0.03f * breathe(t, 0.06f)
                }
                p.mix(a1, a2, easeOut(a / 5f))
            }
            Anim.WHITE_HOLE -> {
                idle(a1, t, magic)
                val u = seg(a, 0f, ArtTimings.WHITE_HOLE_CAST.toFloat())
                val r = 1f - smooth(u)
                a2.shoulders().apply {
                    val ox = r * 0.04f * cos(a * 0.3f); val oy = r * 0.05f * sin(a * 0.3f)
                    rH.set(0.22f + r * 0.08f + ox, -0.26f + oy, -0.80f); rN.set(-1f, 0f, 0f)
                    lH.set(-0.22f - r * 0.08f - ox, -0.26f - oy, -0.80f); lN.set(1f, 0f, 0f)
                }
                val end = ArtTimings.WHITE_HOLE.toFloat()
                p.mix(a1, a2, if (a < end - 4f) easeOut(a / 6f) else 1f - smooth(seg(a, end - 4f, end)))
            }
            else -> idle(p, t, magic)
        }
    }

    private fun parry(p: FpPose) {
        p.shoulders()
        p.rH.set(0.24f, -0.08f, -0.78f); p.rS.set(0.55f, -0.95f, -0.45f); p.rN.set(0f, 0f, -1f)
        p.lH.set(-0.24f, -0.06f, -0.80f); p.lS.set(-0.55f, -0.95f, -0.45f); p.lN.set(0f, 0f, -1f)
    }

    // --- Solvane -------------------------------------------------------------------------------------------------------

    /** Griffmitte und Klingenrichtung im Hand-Rahmen nach applyEquipOffset (aus display.firstperson_righthand). */
    private val FP_GRIP = Vector3f(1.13f / 16f, -1.14f / 16f, -0.45f / 16f)
    private val FP_BLADE = Vector3f(0f, 0.940f, 0.341f)
    private const val FP_BLADE_FROM = 2.2f / 16f
    private const val FP_BLADE_TO = 11.0f / 16f

    private class Sw(var tx: Float = 0f, var ty: Float = 0f, var tz: Float = 0f, var rx: Float = 0f, var ry: Float = 0f, var rz: Float = 0f) {
        fun set(o: Sw) = apply { tx = o.tx; ty = o.ty; tz = o.tz; rx = o.rx; ry = o.ry; rz = o.rz }
        fun mix(a: Sw, b: Sw, t: Float) = apply {
            tx = lerp(a.tx, b.tx, t); ty = lerp(a.ty, b.ty, t); tz = lerp(a.tz, b.tz, t)
            rx = lerp(a.rx, b.rx, t); ry = lerp(a.ry, b.ry, t); rz = lerp(a.rz, b.rz, t)
        }
    }

    private val sw = Sw()
    private val CH_V = Sw(0.06f, -0.06f, 0.08f, 35f, 0f, -15f)
    private val CH_H = Sw(0.04f, -0.14f, 0.04f, 0f, 0f, -80f)
    private val RV = arrayOf(CH_V, Sw(-0.08f, 0.02f, -0.05f, -40f, 0f, -5f), Sw(-0.22f, -0.08f, -0.18f, -110f, 0f, 0f), Sw(-0.25f, -0.14f, -0.15f, -135f, 0f, 0f))
    private val RH = arrayOf(CH_H, Sw(-0.10f, -0.12f, -0.10f, 0f, 40f, -80f), Sw(-0.25f, -0.12f, -0.12f, 0f, 90f, -80f), Sw(-0.45f, -0.14f, 0f, 0f, 190f, -80f))
    private val REST = Sw()

    private fun renderSolvane(
        renderer: HeldItemRenderer, player: AbstractClientPlayerEntity, td: Float, item: ItemStack, anim: ArtAnims.State,
        equip: Float, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int,
    ) {
        val a = anim.age(td)
        val horizontal = anim.param == 1
        var leftW = 1f
        if (anim.anim == Anim.ART_CHARGE) {
            sw.mix(REST, if (horizontal) CH_H else CH_V, easeOut(a / 5f))
            leftW = easeOut(a / 4f)
        } else {
            val k = if (horizontal) RH else RV
            val hit = ArtTimings.ART_HIT.toFloat()
            when {
                a < hit * 0.5f -> sw.mix(k[0], k[1], easeIn(a / (hit * 0.5f)))
                a < hit -> sw.mix(k[1], k[2], seg(a, hit * 0.5f, hit))
                a < hit + 3f -> sw.mix(k[2], k[3], easeOut(seg(a, hit, hit + 3f)))
                a < 8f -> sw.set(k[3])
                else -> sw.mix(k[3], REST, smooth(seg(a, 8f, 16f)))
            }
            leftW = 1f - smooth(seg(a, 7f, 11f))
        }
        val leftHanded = player.mainArm == Arm.LEFT
        val f = if (leftHanded) -1f else 1f
        val grip = Vector3f(FP_GRIP.x * f, FP_GRIP.y, FP_GRIP.z)

        val base = Matrix4fCopy.of(matrices)
        matrices.push()
        matrices.translate(f * 0.56f, -0.52f - equip * 0.6f, -0.72f)
        matrices.translate(f * sw.tx, sw.ty, sw.tz)
        matrices.translate(grip.x, grip.y, grip.z)
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(f * sw.ry))
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(sw.rx))
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(f * sw.rz))
        matrices.translate(-grip.x, -grip.y, -grip.z)
        val m = Matrix4fCopy.of(matrices)
        renderer.renderItem(player, item, if (leftHanded) ItemDisplayContext.FIRST_PERSON_LEFT_HAND else ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, matrices, queue, light)
        matrices.pop()

        val blade = Vector3f(FP_BLADE.x * f, FP_BLADE.y, FP_BLADE.z)
        // Zweite Hand am Griff (unterhalb der ersten), damit die Haltung beidhändig wirkt.
        if (leftW > 0.01f) {
            // Im Grundrahmen von [matrices] (arm() wendet ihn selbst an): local = base⁻¹ · m.
            val local = org.joml.Matrix4f(base).invert().mul(m)
            val pommel = local.transformPosition(Vector3f(grip).fma(-0.17f, blade), Vector3f())
            pommel.y -= (1f - leftW) * 0.5f
            val s = Vector3f(-0.55f * f, -1.0f, -0.35f)
            val n = Vector3f(f, 0f, 0f)
            arm(player, matrices, queue, light, leftHanded, pommel, s, n, player.skin.model() == PlayerSkinType.SLIM, false, false, 0f)
        }

        // Energie entlang der Schneide.
        val fx = ArtVisualGlow.fx()
        if (fx > 0f) {
            val strength = if (anim.anim == Anim.ART_CHARGE) ArtTimings.artCharge(a) else (1f - seg(a, 2f, 9f)) * 0.9f
            if (anim.anim == Anim.ART_CHARGE || strength > 0.01f) {
                val b = ArtVisualGlow.Builder(ArtVisualGlow.VIEW_RIGHT, ArtVisualGlow.VIEW_UP)
                val p0 = m.transformPosition(Vector3f(grip).fma(FP_BLADE_FROM, blade), Vector3f())
                val p1 = m.transformPosition(Vector3f(grip).fma(FP_BLADE_TO, blade), Vector3f())
                ArtVisualGlow.bladeEnergy(b, p0, p1, strength, ArtAnims.ticks + td, if (anim.anim == Anim.ART_CHARGE) a else 99f, 0.6f, fx)
                b.submit(queue, VfxLayers.GLOW_ADD)
            }
        }
    }
}

/** Kopie der aktuellen Lage (die MatrixStack wird danach weiter verändert). */
internal object Matrix4fCopy {
    fun of(matrices: MatrixStack): org.joml.Matrix4f = org.joml.Matrix4f(matrices.peek().positionMatrix)
}

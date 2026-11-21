package dev.henny.hha_arius.gear.client.art

import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.ArtParam
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

    /** Wie [mix], ohne Begrenzung (t > 1 Überschwung, t < 0 Ausholen). */
    fun mixFree(a: FpPose, b: FpPose, t: Float): FpPose {
        if (t in 0f..1f) return mix(a, b, t)
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
    private var chain = false

    /** true = der Aufruf ist erledigt (Vanilla überspringen). */
    @JvmStatic
    fun render(
        renderer: HeldItemRenderer, player: AbstractClientPlayerEntity, tickProgress: Float, hand: Hand,
        item: ItemStack, equipProgress: Float, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int,
    ): Boolean {
        val main = player.mainHandStack
        // Seit 1.8.1: Ritt auf Solvane – beide (bzw. eine) Hände am Eisdorn; Gegenstände bleiben verborgen.
        if (dev.henny.hha_arius.client.fx.RideClient.grip(player) > 0) {
            if (hand == Hand.MAIN_HAND) renderRide(player, tickProgress, matrices, queue, light)
            return true
        }
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
            if (hasLast) { from.set(last); fromTime = now; blendDur = blendTicks(anim); chain = anim != null && key >= 0L; blending = true }
            key = k
        }
        if (blending) {
            val u = (now - fromTime) / blendDur
            // Seit 1.10.0: Schlag → Schlag mit Schwung (sofort los), sonst weich.
            val f = if (chain) u.coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - 0.35f * it) } else smooth(u)
            if (u >= 1f) blending = false
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
            arm(player, matrices, queue, light, right, hv, sv, nv, slim, true, open, glow, anim, td, now, canonRight)
            handGlow(matrices, glowB, anim, td, right, slim, canonRight)
        }
        if (anim != null && anim.anim == Anim.WHITE_HOLE) whiteHoleCore(matrices, glowB, anim.age(td), leftHanded)
        matrices.pop()
        glowB.submit(queue, VfxLayers.GLOW_ADD)
    }

    /**
     * Ritt (erste Person): die Hände greifen den Eisdorn an seiner Stelle in der Welt (über die Kameradrehung in den
     * Blickraum gerechnet, er bleibt also beim Umsehen am Drachen). Mit nur einer Hand rudert die linke frei.
     */
    private fun renderRide(player: AbstractClientPlayerEntity, td: Float, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int) {
        val (upper, lower) = dev.henny.hha_arius.client.fx.RideClient.handTargets(td) ?: return
        val grip = dev.henny.hha_arius.client.fx.RideClient.grip(player)
        val leftHanded = player.mainArm == Arm.LEFT
        val slim = player.skin.model() == PlayerSkinType.SLIM
        val gloves = GauntletsItem.isGauntlets(player.mainHandStack)
        val t = player.age + td
        val rS = Vector3f(0.62f, -1.05f, -0.38f)
        val lS = Vector3f(-0.62f, -1.05f, -0.40f)
        // Rechte Hand oben, linke darunter, beide Handflächen zum Dorn.
        val rH = Vector3f(upper).add(0.05f, 0f, 0.04f)
        val lH = if (grip >= 2) Vector3f(lower).add(-0.05f, 0f, 0.04f)
        else Vector3f(-0.74f, -0.46f + 0.12f * sin(t * 0.45f), -0.74f + 0.08f * cos(t * 0.37f))
        val rN = Vector3f(-1f, 0f, 0.15f).normalize()
        val lN = if (grip >= 2) Vector3f(1f, 0f, 0.15f).normalize() else Vector3f(0.6f, -0.6f, 0.4f).normalize()
        matrices.push()
        for (canonRight in booleanArrayOf(true, false)) {
            val right = if (leftHanded) !canonRight else canonRight
            val h = Vector3f(if (canonRight) rH else lH)
            val sh = Vector3f(if (canonRight) rS else lS)
            val n = Vector3f(if (canonRight) rN else lN)
            if (leftHanded) { h.x = -h.x; sh.x = -sh.x; n.x = -n.x }
            // Liegt der Griff hinter der Kamera (Blick nach hinten/oben), ist der Arm ohnehin nicht zu sehen.
            if (h.z > 0.15f) continue
            // Steigt sie steil, liegt der Dorn fast über der Kamera: Die Hand bleibt dann vor der Nahebene sichtbar.
            if (h.z > -0.38f) h.z = -0.38f
            // Seit 1.11.0 greift man weiter vor sich: Der Arm wird bis zur Schulter gestreckt.
            val stretch = (h.distance(sh) / 0.72f).coerceIn(1f, 2.4f)
            arm(player, matrices, queue, light, right, h, sh, n, slim, gloves, false, 0f, null, td, t, canonRight, stretch)
        }
        matrices.pop()
    }

    /** Ein Arm: Handende auf [h], Arm entlang (h - s), Handfläche nach [n]. Danach steht [matrices] wieder wie vorher. */
    private fun arm(
        player: AbstractClientPlayerEntity, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int,
        right: Boolean, h: Vector3f, s: Vector3f, n: Vector3f, slim: Boolean, gloves: Boolean, open: Boolean, glow: Float,
        anim: ArtAnims.State? = null, td: Float = 0f, now: Float = 0f, canonRight: Boolean = right, stretch: Float = 1f,
    ) {
        dir.set(h).sub(s)
        ArtPoseMath.armFrame(dir, n, right, q)
        val cx = if (right) (if (slim) -0.5f else -1f) else (if (slim) 0.5f else 1f)
        q.transform(tmp.set(cx / 16f, 10f / 16f * stretch, 0f))
        pivot.set(h).sub(tmp)
        matrices.push()
        matrices.translate(pivot.x, pivot.y, pivot.z)
        matrices.multiply(q)
        if (stretch != 1f) matrices.scale(1f, stretch, 1f)
        if (!player.isInvisible) {
            // renderArm setzt den Arm auf seine Grundlage (Drehpunkt ∓5,2,0, Rollen ±0,1) – hier vorab ausgleichen.
            matrices.push()
            matrices.multiply(RotationAxis.POSITIVE_Z.rotation(if (right) -0.1f else 0.1f))
            matrices.translate((if (right) 5f else -5f) / 16f, -2f / 16f, 0f)
            val renderer = MinecraftClient.getInstance().entityRenderDispatcher.getPlayerRenderer(player)
            val skin = player.skin.body().texturePath()
            // Mit Handschuhen ohne Ärmel-Schicht (seit 1.6.2): Der Ärmel (±2,25) lag fast genau auf den Platten und
            // flimmerte in der Ego-Sicht durch (Crix' Screenshot). Die Platten decken den Unterarm ohnehin ab.
            if (right) renderer.renderRightArm(matrices, queue, light, skin, !gloves && player.isModelPartVisible(PlayerModelPart.RIGHT_SLEEVE))
            else renderer.renderLeftArm(matrices, queue, light, skin, !gloves && player.isModelPartVisible(PlayerModelPart.LEFT_SLEEVE))
            matrices.pop()
        }
        if (gloves) GauntletParts.submit(queue, matrices, right, slim, open, glow, light, anim = anim, td = td, time = now, canonRight = canonRight)
        matrices.pop()
    }

    /** Energie an Handfläche/Fingern beim Zaubern (erste Person). Muss im selben Zustand wie [arm] gerufen werden. */
    private fun handGlow(matrices: MatrixStack, b: ArtVisualGlow.Builder, anim: ArtAnims.State?, td: Float, right: Boolean, slim: Boolean, canonRight: Boolean) {
        if (anim == null) return
        val fx = ArtVisualGlow.fx()
        if (fx <= 0f) return
        val a = anim.age(td)
        val school = School.entries.getOrElse(ArtParam.school(anim.param)) { School.WIND }
        val strength = when (anim.anim) {
            Anim.CAST_CHARGE -> 0.45f + 0.55f * ArtParam.castCharge(anim.param, a)
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
            Anim.SIDE_KICK, Anim.SPIN_KICK, Anim.AXE_KICK, Anim.FLYING_KICK, Anim.DIVE_KICK, Anim.WHIRL_KICK, Anim.ROUNDHOUSE, Anim.LOW_SWEEP, Anim.KNEE -> 2f
            Anim.UPPERCUT -> minOf(1.6f, w * 0.5f)
            Anim.PARRY, Anim.CAST_TAP, Anim.CAST_RELEASE -> 1.4f
            else -> 2.5f
        }
    }

    // --- Haltungen (erste Person) ------------------------------------------------------------------------------------

    /**
     * Kampfhaltung der ersten Person (seit 1.8.1, passend zur dritten Person): vordere linke Faust höher und weiter
     * vorn nahe der Mitte, hintere rechte Faust tiefer und näher am Körper; beide wippen mit dem Stand, die vordere
     * kreist minimal, die ganze Anordnung pendelt leicht.
     */
    private fun guard(p: FpPose, t: Float) {
        p.shoulders()
        val b = 0.01f * breathe(t)
        val bob = sin(t * 0.36f)
        p.lH.set(-0.15f + 0.012f * sin(t * 0.3f), -0.19f + b + 0.012f * cos(t * 0.3f), -1.02f)
        p.lS.set(-0.6f, -1.0f, -0.46f); p.lN.set(0.85f, 0.15f, 0.5f)
        p.rH.set(0.24f, -0.37f + b, -0.74f)
        p.rS.set(0.7f, -1.05f, -0.32f); p.rN.set(-0.8f, 0.2f, 0.55f)
        p.dy = 0.012f * bob
        p.roll = 0.03f * sin(t * 0.17f)
    }

    private fun magicIdle(p: FpPose, t: Float) {
        p.shoulders()
        p.rH.set(0.42f, -0.48f + 0.012f * breathe(t, 0.07f), -0.82f); p.rS.set(0.62f, -1.15f, -0.40f); p.rN.set(-0.7f, 0.7f, 0f)
        p.lH.set(-0.40f, -0.50f + 0.012f * breathe(t, 0.07f, 1f), -0.84f); p.lS.set(-0.62f, -1.15f, -0.42f); p.lN.set(0.7f, 0.7f, 0f)
    }

    private fun idle(p: FpPose, t: Float, magic: Boolean) = if (magic) magicIdle(p, t) else guard(p, t)

    /** Beschleunigen bis zum Treffer (kurz ausholen), über das Ziel hinausschwingen, federnd zurück. */
    private fun strikeK(a: Float, w: Float, d: Float): Float = when {
        a < w -> drive(a / w, 0.3f)
        a < w + 2f -> 1f + 0.09f * bump(seg(a, w, w + 2f))
        else -> seg(a, w + 2f, d).let { 1f - smooth(it) - 0.05f * bump(it) }
    }

    private fun drive(u: Float, back: Float): Float {
        val x = clamp01(u)
        return x * x - back * x * (1f - x) * (1f - x)
    }

    private fun bump(u: Float): Float = if (u <= 0f || u >= 1f) 0f else sin(PI.toFloat() * u)

    /** Einsinken beim Treffer: die ganze Hand-Anordnung geht kurz mit dem Gewicht nach unten. */
    private fun sink(p: FpPose, a: Float, w: Float, amount: Float) {
        p.dy -= amount * bump(seg(a, w - 0.3f, w + 3f))
    }

    private val HOOK_C = Vector3f(-0.50f, -0.44f, -1.18f)
    private val UPPER_C = Vector3f(0.40f, -0.40f, -1.10f)
    private val WIND_C = Vector3f(0.25f, -0.20f, -1.25f)

    private fun build(s: ArtAnims.State?, a: Float, t: Float, magic: Boolean, p: FpPose) {
        if (s == null) { idle(p, t, magic); return }
        val w = s.param.coerceAtLeast(1).toFloat()
        val d = if (s.duration > 0) s.duration.toFloat() else w + 6f
        when (s.anim) {
            Anim.JAB -> {
                guard(a1, t)
                a2.set(a1).apply { lH.set(-0.06f, -0.14f, -1.38f); lS.set(-0.42f, -1.3f, -0.85f); lN.set(0f, -1f, 0f); rH.set(0.34f, -0.26f, -0.72f) }
                p.mixFree(a1, a2, strikeK(a, w, d))
                sink(p, a, w, 0.012f)
            }
            Anim.CROSS -> {
                guard(a1, t)
                a2.set(a1).apply { rH.set(0.05f, -0.14f, -1.40f); rS.set(0.42f, -1.3f, -0.85f); rN.set(0f, -1f, 0f); lH.set(-0.26f, -0.22f, -0.74f) }
                p.mixFree(a1, a2, strikeK(a, w, d))
                sink(p, a, w, 0.016f)
            }
            Anim.HOOK -> {
                guard(a1, t)
                a2.set(a1).apply { lH.set(-0.62f, -0.46f, -0.78f); lS.set(-0.75f, -1.1f, -0.30f); lN.set(0f, -1f, 0f); dy = -0.02f }
                a3.set(a1).apply { lH.set(0.02f, -0.40f, -1.02f); lS.set(-0.55f, -0.95f, -0.55f); lN.set(0f, -1f, 0.3f); roll = -0.05f }
                val load = 0.4f * w
                when {
                    a < load -> p.mix(a1, a2, easeOut(a / load))
                    a < w -> { val u = drive(seg(a, load, w), 0f); p.mix(a2, a3, u); bezier(a2.lH, HOOK_C, a3.lH, u, p.lH) }
                    a < w + 2.5f -> p.mixFree(a2, a3, 1f + 0.12f * bump(seg(a, w, w + 2.5f)))
                    else -> seg(a, w + 2.5f, d).let { p.mixFree(a3, a1, smooth(it) + 0.05f * bump(it)) }
                }
                sink(p, a, w, 0.022f)
            }
            Anim.SIDE_KICK, Anim.FLYING_KICK, Anim.ROUNDHOUSE, Anim.KNEE -> {
                // Tritt: Arme fangen das Gleichgewicht ab, die Hand-Anordnung neigt sich leicht.
                guard(a1, t)
                a2.set(a1).apply { rH.set(0.48f, -0.46f, -0.72f); lH.set(-0.24f, -0.22f, -0.82f); roll = 0.05f }
                a3.set(a1).apply { rH.set(0.66f, -0.70f, -0.60f); rS.set(0.80f, -1.2f, -0.2f); rN.set(-0.3f, -1f, 0f); lH.set(-0.18f, -0.18f, -0.78f); roll = 0.08f; dy = -0.02f }
                val ch = 0.55f * w
                when {
                    a < ch -> p.mix(a1, a2, easeOut(a / ch))
                    a < w -> p.mix(a2, a3, drive(seg(a, ch, w), 0f))
                    a < w + 1.5f -> p.set(a3)
                    else -> seg(a, w + 1.5f, d).let { v -> p.mix(a3, a1, smooth(v)); p.dy -= 0.03f * bump(seg(v, 0.3f, 0.8f)) }
                }
                sink(p, a, w, 0.015f)
            }
            Anim.SPIN_KICK, Anim.WHIRL_KICK, Anim.LOW_SWEEP -> {
                guard(a1, t)
                a2.set(a1).apply { rH.set(0.25f, -0.40f, -0.62f); lH.set(-0.20f, -0.38f, -0.64f); roll = -0.04f; dy = -0.02f }
                a3.set(a1).apply { rH.set(0.85f, -0.55f, -0.55f); rN.set(0f, -1f, 0f); lH.set(-0.60f, -0.30f, -0.75f); lN.set(0f, -1f, 0f); roll = 0.06f }
                when {
                    a < 0.6f * w -> { p.mix(a1, a2, easeOut(seg(a, 0f, 0.6f * w))); p.dy -= 0.03f * bump(seg(a, 0f, 0.5f * w)) }
                    a < w -> p.mix(a2, a3, drive(seg(a, 0.6f * w, w), 0f))
                    a < w + 2f -> p.set(a3)
                    else -> seg(a, w + 2f, d).let { v -> p.mix(a3, a1, smooth(v)); p.dy -= 0.035f * bump(seg(v, 0.15f, 0.7f)) }
                }
                sink(p, a, w, 0.02f)
            }
            Anim.UPPERCUT -> {
                // Faust taucht tief rechts unten ab und schießt im Bogen nach oben vorn durch die Bildmitte.
                guard(a1, t)
                a2.set(a1).apply { rH.set(0.42f, -0.66f, -0.70f); rS.set(0.70f, -1.15f, -0.25f); rN.set(-0.6f, 0.8f, 0f); lH.set(-0.26f, -0.20f, -0.86f); dy = -0.03f }
                a3.set(a1).apply { rH.set(0.06f, 0.06f, -1.18f); rS.set(0.45f, -1.0f, -0.80f); rN.set(0f, -0.6f, -0.8f); lH.set(-0.30f, -0.28f, -0.80f); roll = 0.04f; dy = 0.02f }
                val load = 0.45f * w
                when {
                    a < load -> p.mix(a1, a2, easeOut(a / load))
                    a < w -> { val u = drive(seg(a, load, w), 0f); p.mix(a2, a3, u); bezier(a2.rH, UPPER_C, a3.rH, u, p.rH) }
                    a < w + 2.5f -> p.mixFree(a2, a3, 1f + 0.1f * bump(seg(a, w, w + 2.5f)))
                    else -> seg(a, w + 2.5f, d).let { p.mixFree(a3, a1, smooth(it) + 0.05f * bump(it)) }
                }
                sink(p, a, w, -0.015f)
            }
            Anim.AXE_KICK, Anim.DIVE_KICK -> {
                // Sprung: Arme öffnen sich zum Gleichgewicht, Blick hebt sich; Ferse herab: alles sackt tief ein.
                guard(a1, t)
                a2.set(a1).apply { rH.set(0.80f, -0.30f, -0.62f); rN.set(0f, -1f, 0f); lH.set(-0.80f, -0.30f, -0.62f); lN.set(0f, -1f, 0f); dy = 0.05f; roll = -0.03f }
                a3.set(a1).apply { rH.set(0.48f, -0.72f, -0.72f); lH.set(-0.46f, -0.70f, -0.74f); dy = -0.07f; roll = 0.02f }
                val up = 0.6f * w
                when {
                    a < up -> p.mix(a1, a2, easeOut(a / up))
                    a < w -> p.mix(a2, a3, drive(seg(a, up, w), 0f))
                    a < w + 2f -> p.set(a3)
                    else -> seg(a, w + 2f, d).let { v -> p.mix(a3, a1, smooth(v)) }
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
                val school = School.entries.getOrElse(ArtParam.school(s.param)) { School.WIND }
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
                val school = School.entries.getOrElse(ArtParam.school(s.param)) { School.WIND }
                // Seit 1.7.0 weiter auseinander, als hielten die Hände eine Energiekugel (vorher berührten sich die
                // Finger fast und wirkten wie ein Kribbeln, Crix).
                val sep = 0.42f - 0.04f * clamp01(a / 40f)
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

    /**
     * Entwurfsrahmen der Haltungen (Klinge senkrecht in der Hand, wie das frühere diagonale Modell sie zeigte): Griffmitte,
     * Klingenrichtung und Flachseite im Hand-Rahmen nach applyEquipOffset. Die Schlüssel [CH_V] … [RH] sind darin gebaut.
     */
    private val FP_GRIP = Vector3f(1.13f / 16f, -1.14f / 16f, -0.45f / 16f)
    private val FP_BLADE = Vector3f(0f, 0.940f, 0.341f)
    private val FP_FLAT = Vector3f(-1f, 0f, 0f)

    /**
     * Das aufrechte Modell (display.firstperson_righthand von solvane_3d.json): Griffmitte, Klinge (schräg nach vorn
     * oben), Flachseite (Modell-z), Klingenansatz/-spitze und Knauf in Blöcken ab der Griffmitte.
     */
    private val ITEM_GRIP = Vector3f(1.13f / 16f, -0.443f / 16f, -0.192f / 16f)
    private val ITEM_BLADE = Vector3f(0f, 0.94f, 0.342f).normalize()
    private val ITEM_FLAT = Vector3f(-1f, 0f, 0f)
    private const val ITEM_BLADE_FROM = 1.82f / 16f
    private const val ITEM_BLADE_TO = 14.82f / 16f
    private const val ITEM_POMMEL = -0.184f

    /** Drehung, die das aufrechte Modell in den Entwurfsrahmen legt (Klinge → Klinge, Flachseite → Flachseite). */
    private val CORRECT: Quaternionf = run {
        fun frame(blade: Vector3f, flat: Vector3f): org.joml.Matrix3f {
            val b = Vector3f(blade).normalize()
            val f = Vector3f(flat).fma(-flat.dot(b), b).normalize()
            val n = Vector3f(b).cross(f)
            return org.joml.Matrix3f().setColumn(0, b).setColumn(1, f).setColumn(2, n)
        }
        val m = frame(FP_BLADE, FP_FLAT).mul(frame(ITEM_BLADE, ITEM_FLAT).transpose())
        Quaternionf().setFromNormalized(m)
    }

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

    /** Senkrecht: Haltung, Ausholen (Klinge weiter zurück, Hände heben sich), Treffer, Durchzug (tief). */
    private val RV = arrayOf(CH_V, Sw(0.02f, 0.06f, 0.12f, 58f, 0f, -12f), Sw(-0.22f, -0.08f, -0.18f, -110f, 0f, 0f), Sw(-0.26f, -0.17f, -0.14f, -140f, 0f, 2f))

    /** Waagerecht: Haltung, Ausholen (weiter nach rechts hinten), Treffer, Durchzug nach links. */
    private val RH = arrayOf(CH_H, Sw(0.06f, -0.12f, 0.06f, 0f, -22f, -80f), Sw(-0.25f, -0.12f, -0.12f, 0f, 90f, -80f), Sw(-0.48f, -0.15f, 0f, 0f, 198f, -80f))
    private val REST = Sw()

    private fun renderSolvane(
        renderer: HeldItemRenderer, player: AbstractClientPlayerEntity, td: Float, item: ItemStack, anim: ArtAnims.State,
        equip: Float, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int,
    ) {
        val a = anim.age(td)
        val horizontal = ArtParam.horizontal(anim.param)
        var leftW: Float
        // Anteil des Entwurfsrahmens: 0 = Vanilla-Haltung des aufrechten Modells, 1 = Haltungen des Klaren Schnitts.
        val frameW: Float
        var sink = 0f
        if (anim.anim == Anim.ART_CHARGE) {
            val charge = ArtTimings.artCharge(a)
            sw.mix(REST, if (horizontal) CH_H else CH_V, easeOut(a / 5f))
            // Spannung: mit der Ladung sinkt die Haltung, bei voller Ladung zittert sie kaum merklich.
            sink = 0.03f * smooth(charge) + if (charge >= 0.999f) 0.003f * sin((ArtAnims.ticks + td) * 1.9f) else 0f
            leftW = easeOut(a / 4f)
            frameW = easeOut(a / 4f)
        } else {
            val k = if (horizontal) RH else RV
            val hit = ArtTimings.ART_HIT.toFloat()
            val charge = ArtParam.charge(anim.param)
            val raise = 1.1f
            val linger = 9f + 3f * charge
            when {
                a < raise -> sw.mix(k[0], k[1], easeOut(a / raise))
                a < hit -> sw.mix(k[1], k[2], seg(a, raise, hit).let { it * it })
                a < hit + 2.5f -> {
                    val v = seg(a, hit, hit + 2.5f)
                    // Durchziehen mit Überschwung über die Endhaltung hinaus
                    sw.mix(k[2], k[3], easeOut(v) + 0.12f * sin(PI.toFloat() * v))
                }
                a < linger -> sw.set(k[3])
                else -> sw.mix(k[3], REST, smooth(seg(a, linger, 18f)))
            }
            sink = 0.05f * (seg(a, hit - 0.5f, hit + 2.5f) - seg(a, linger, 18f))
            leftW = 1f - smooth(seg(a, hit + 0.5f, hit + 4f))
            frameW = 1f - smooth(seg(a, linger, 17f))
        }
        val leftHanded = player.mainArm == Arm.LEFT
        val f = if (leftHanded) -1f else 1f
        val grip = Vector3f(FP_GRIP.x * f, FP_GRIP.y, FP_GRIP.z)
        val itemGrip = Vector3f(ITEM_GRIP.x * f, ITEM_GRIP.y, ITEM_GRIP.z)

        val base = Matrix4fCopy.of(matrices)
        matrices.push()
        matrices.translate(f * 0.56f, -0.52f - equip * 0.6f - sink, -0.72f)
        matrices.translate(f * sw.tx, sw.ty, sw.tz)
        matrices.translate(grip.x, grip.y, grip.z)
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(f * sw.ry))
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(sw.rx))
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(f * sw.rz))
        matrices.translate(-grip.x, -grip.y, -grip.z)
        // Aufrechtes Modell in den Entwurfsrahmen: Griff auf den Entwurfsgriff, Klinge in die Entwurfsrichtung.
        val corr = Quaternionf().slerp(mirrored(CORRECT, leftHanded), frameW)
        val to = Vector3f(itemGrip).lerp(grip, frameW)
        matrices.translate(to.x, to.y, to.z)
        matrices.multiply(corr)
        matrices.translate(-itemGrip.x, -itemGrip.y, -itemGrip.z)
        val m = Matrix4fCopy.of(matrices)
        renderer.renderItem(player, item, if (leftHanded) ItemDisplayContext.FIRST_PERSON_LEFT_HAND else ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, matrices, queue, light)
        matrices.pop()

        val blade = Vector3f(ITEM_BLADE.x * f, ITEM_BLADE.y, ITEM_BLADE.z)
        // Zweite Hand am Griff (unterhalb der ersten), damit die Haltung beidhändig wirkt.
        if (leftW > 0.01f) {
            // Im Grundrahmen von [matrices] (arm() wendet ihn selbst an): local = base⁻¹ · m.
            val local = org.joml.Matrix4f(base).invert().mul(m)
            val pommel = local.transformPosition(Vector3f(itemGrip).fma(ITEM_POMMEL, blade), Vector3f())
            pommel.y -= (1f - leftW) * 0.5f
            val s = Vector3f(-0.55f * f, -1.0f, -0.35f)
            val n = Vector3f(f, 0f, 0f)
            arm(player, matrices, queue, light, leftHanded, pommel, s, n, player.skin.model() == PlayerSkinType.SLIM, false, false, 0f)
        }

        val p0 = m.transformPosition(Vector3f(itemGrip).fma(ITEM_BLADE_FROM, blade), Vector3f())
        val p1 = m.transformPosition(Vector3f(itemGrip).fma(ITEM_BLADE_TO, blade), Vector3f())
        // Schwertspur des Schnitts (Blickraum): vom Ausholen bis zum Ende des Durchzugs.
        if (anim.anim == Anim.ART_RELEASE) {
            SwordTrails.recordView(p0, p1, td, SwordTrails.Style.ART, forced = a < ArtTimings.ART_HIT + 3f)
            SwordTrails.renderView(queue, td)
        }
        // Energie entlang der Schneide.
        val fx = ArtVisualGlow.fx()
        if (fx > 0f) {
            val strength = if (anim.anim == Anim.ART_CHARGE) ArtTimings.artCharge(a) else (1f - seg(a, 2f, 9f)) * 0.9f
            if (anim.anim == Anim.ART_CHARGE || strength > 0.01f) {
                val b = ArtVisualGlow.Builder(ArtVisualGlow.VIEW_RIGHT, ArtVisualGlow.VIEW_UP)
                ArtVisualGlow.bladeEnergy(b, p0, p1, strength, ArtAnims.ticks + td, if (anim.anim == Anim.ART_CHARGE) a else 99f, 0.6f, fx)
                b.submit(queue, VfxLayers.GLOW_ADD)
            }
        }
    }

    private fun mirrored(q: Quaternionf, left: Boolean): Quaternionf = if (!left) Quaternionf(q) else Quaternionf(q.x, -q.y, -q.z, q.w)

    /**
     * Normale Schläge mit Solvane in der ersten Person (Vanilla zeichnet): Ansatz und Spitze der Klinge in den Blickraum
     * der Hand mitschreiben und die Spur zeichnen (sichtbar nur bei vollen Treffern, siehe [SwordTrails]). Aufruf aus
     * dem Mixin direkt vor `renderItem`, [matrices] steht dann im Hand-Rahmen nach Ausrüsten und Schwung.
     */
    @JvmStatic
    fun vanillaSolvane(player: AbstractClientPlayerEntity, hand: Hand, item: ItemStack, matrices: MatrixStack, queue: OrderedRenderCommandQueue, td: Float) {
        if (hand != Hand.MAIN_HAND || !item.isOf(GearItems.SOLVANE)) return
        val anim = ArtAnims.get(player.id)
        if (anim != null && (anim.anim == Anim.ART_CHARGE || anim.anim == Anim.ART_RELEASE)) return
        val f = if (player.mainArm == Arm.LEFT) -1f else 1f
        val m = matrices.peek().positionMatrix
        val g = Vector3f(ITEM_GRIP.x * f, ITEM_GRIP.y, ITEM_GRIP.z)
        val b = Vector3f(ITEM_BLADE.x * f, ITEM_BLADE.y, ITEM_BLADE.z)
        val p0 = m.transformPosition(Vector3f(g).fma(ITEM_BLADE_FROM, b), Vector3f())
        val p1 = m.transformPosition(Vector3f(g).fma(ITEM_BLADE_TO, b), Vector3f())
        SwordTrails.recordView(p0, p1, td, SwordTrails.Style.SOLVANE)
        SwordTrails.renderView(queue, td)
    }
}

/** Kopie der aktuellen Lage (die MatrixStack wird danach weiter verändert). */
internal object Matrix4fCopy {
    fun of(matrices: MatrixStack): org.joml.Matrix4f = org.joml.Matrix4f(matrices.peek().positionMatrix)
}

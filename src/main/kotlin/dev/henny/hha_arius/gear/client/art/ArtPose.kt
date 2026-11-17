package dev.henny.hha_arius.gear.client.art

import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.ArtParam
import dev.henny.hha_arius.gear.art.ArtTimings
import dev.henny.hha_arius.gear.art.GauntletMode
import dev.henny.hha_arius.gear.art.GauntletsItem
import dev.henny.hha_arius.gear.art.Move
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
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.entity.EntityPose
import net.minecraft.item.ItemStack
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Arm
import net.minecraft.util.math.RotationAxis
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Zielhaltung eines Spielers, immer RECHTSHÄNDIG gedacht (Linkshänder werden am Ende gespiegelt). Modellraum in
 * Pixeln: Hals (0,0,0), Schultern (±5,2,0), Hüfte (0,12,0), Füße y = 24; -z vorn, +x links.
 *
 * Arme werden über Zielpunkte der Hand (+ Richtung der Handfläche) beschrieben, der Rumpf dreht um die Hüfte, die
 * Beine über Eulerwinkel. [turn] dreht die ganze Figur um die Hochachse (Entity-Raum, + = von oben gegen den
 * Uhrzeigersinn) – das macht der Renderer in setupTransforms, damit Rüstung, Umhang und Gegenstände mitgehen.
 */
internal class Pose {
    var turn = 0f
    var tYaw = 0f
    var tPitch = 0f
    var tRoll = 0f
    var drop = 0f
    val rHand = Vector3f()
    val rPalm = Vector3f(1f, 0f, 0f)
    val lHand = Vector3f()
    val lPalm = Vector3f(-1f, 0f, 0f)

    /** 1 = rechter Arm wird über die Klingenrichtung [blade] ausgerichtet (Solvane). */
    var sword = 0f
    val blade = Vector3f(0f, 0f, -1f)

    /** 1 = linke Hand greift unter der rechten an den Griff (beidhändig). */
    var grip = 0f
    var rLegP = 0f
    var rLegY = 0f
    var rLegR = 0f
    var lLegP = 0f
    var lLegY = 0f
    var lLegR = 0f

    /** Anteil der Pose an den Beinen (0 = Vanilla-Gehen bleibt). */
    var legW = 1f
    var headP = 0f
    var headY = 0f

    fun reset(): Pose {
        turn = 0f; tYaw = 0f; tPitch = 0f; tRoll = 0f; drop = 0f
        rHand.set(-5.5f, 12f, -1f); rPalm.set(1f, 0f, 0f)
        lHand.set(5.5f, 12f, -1f); lPalm.set(-1f, 0f, 0f)
        sword = 0f; blade.set(0f, 0f, -1f); grip = 0f
        rLegP = 0f; rLegY = 0f; rLegR = 0f; lLegP = 0f; lLegY = 0f; lLegR = 0f
        legW = 1f; headP = 0f; headY = 0f
        return this
    }

    fun set(o: Pose): Pose {
        turn = o.turn; tYaw = o.tYaw; tPitch = o.tPitch; tRoll = o.tRoll; drop = o.drop
        rHand.set(o.rHand); rPalm.set(o.rPalm); lHand.set(o.lHand); lPalm.set(o.lPalm)
        sword = o.sword; blade.set(o.blade); grip = o.grip
        rLegP = o.rLegP; rLegY = o.rLegY; rLegR = o.rLegR; lLegP = o.lLegP; lLegY = o.lLegY; lLegR = o.lLegR
        legW = o.legW; headP = o.headP; headY = o.headY
        return this
    }

    /** this = Mischung aus [a] und [b]. Handpunkte linear, Richtungen normiert gemischt. */
    fun mix(a: Pose, b: Pose, t: Float): Pose {
        if (t <= 0f) return set(a)
        if (t >= 1f) return set(b)
        turn = lerp(a.turn, b.turn, t); tYaw = lerp(a.tYaw, b.tYaw, t); tPitch = lerp(a.tPitch, b.tPitch, t)
        tRoll = lerp(a.tRoll, b.tRoll, t); drop = lerp(a.drop, b.drop, t)
        ArtPoseMath.lerpV(a.rHand, b.rHand, t, rHand); ArtPoseMath.nlerp(a.rPalm, b.rPalm, t, rPalm)
        ArtPoseMath.lerpV(a.lHand, b.lHand, t, lHand); ArtPoseMath.nlerp(a.lPalm, b.lPalm, t, lPalm)
        sword = lerp(a.sword, b.sword, t); ArtPoseMath.nlerp(a.blade, b.blade, t, blade); grip = lerp(a.grip, b.grip, t)
        rLegP = lerp(a.rLegP, b.rLegP, t); rLegY = lerp(a.rLegY, b.rLegY, t); rLegR = lerp(a.rLegR, b.rLegR, t)
        lLegP = lerp(a.lLegP, b.lLegP, t); lLegY = lerp(a.lLegY, b.lLegY, t); lLegR = lerp(a.lLegR, b.lLegR, t)
        legW = lerp(a.legW, b.legW, t); headP = lerp(a.headP, b.headP, t); headY = lerp(a.headY, b.headY, t)
        return this
    }

    /**
     * Wie [mix], aber ohne Begrenzung von [t]: t > 1 schwingt über [b] hinaus, t < 0 holt gegen die Richtung aus
     * (Überschwung und Ausholen). Richtungen werden normiert.
     */
    fun mixFree(a: Pose, b: Pose, t: Float): Pose {
        if (t in 0f..1f) return mix(a, b, t)
        turn = lerp(a.turn, b.turn, t); tYaw = lerp(a.tYaw, b.tYaw, t); tPitch = lerp(a.tPitch, b.tPitch, t)
        tRoll = lerp(a.tRoll, b.tRoll, t); drop = lerp(a.drop, b.drop, t)
        ArtPoseMath.lerpV(a.rHand, b.rHand, t, rHand); ArtPoseMath.nlerp(a.rPalm, b.rPalm, t, rPalm)
        ArtPoseMath.lerpV(a.lHand, b.lHand, t, lHand); ArtPoseMath.nlerp(a.lPalm, b.lPalm, t, lPalm)
        sword = lerp(a.sword, b.sword, t).coerceIn(0f, 1f); ArtPoseMath.nlerp(a.blade, b.blade, t, blade); grip = lerp(a.grip, b.grip, t).coerceIn(0f, 1f)
        rLegP = lerp(a.rLegP, b.rLegP, t); rLegY = lerp(a.rLegY, b.rLegY, t); rLegR = lerp(a.rLegR, b.rLegR, t)
        lLegP = lerp(a.lLegP, b.lLegP, t); lLegY = lerp(a.lLegY, b.lLegY, t); lLegR = lerp(a.lLegR, b.lLegR, t)
        legW = lerp(a.legW, b.legW, t).coerceIn(0f, 1f); headP = lerp(a.headP, b.headP, t); headY = lerp(a.headY, b.headY, t)
        return this
    }

    /**
     * Körper (Hüfte, Rumpf, Beine) mit eigenem Fortschritt [t] zwischen [a] und [b] (Arme bleiben): Die Hüfte führt,
     * die Hand folgt (Kette Hüfte → Rumpf → Schulter → Arm).
     */
    fun bodyFrom(a: Pose, b: Pose, t: Float): Pose {
        turn = lerp(a.turn, b.turn, t); tYaw = lerp(a.tYaw, b.tYaw, t); tPitch = lerp(a.tPitch, b.tPitch, t)
        tRoll = lerp(a.tRoll, b.tRoll, t); drop = lerp(a.drop, b.drop, t)
        rLegP = lerp(a.rLegP, b.rLegP, t); rLegY = lerp(a.rLegY, b.rLegY, t); rLegR = lerp(a.rLegR, b.rLegR, t)
        lLegP = lerp(a.lLegP, b.lLegP, t); lLegY = lerp(a.lLegY, b.lLegY, t); lLegR = lerp(a.lLegR, b.lLegR, t)
        return this
    }

    fun rLeg(p: Float, y: Float, r: Float) { rLegP = p; rLegY = y; rLegR = r }
    fun lLeg(p: Float, y: Float, r: Float) { lLegP = p; lLegY = y; lLegR = r }
}

/** Aufgelöste Haltung: Drehungen und Drehpunkte der Modellteile. */
internal class Solved {
    var turn = 0f
    val body = Quaternionf()
    val bodyO = Vector3f()
    val headO = Vector3f()
    var headP = 0f
    var headY = 0f
    val rArm = Quaternionf()
    val rArmO = Vector3f()
    val lArm = Quaternionf()
    val lArmO = Vector3f()
    val rLeg = Quaternionf()
    val rLegO = Vector3f()
    val lLeg = Quaternionf()
    val lLegO = Vector3f()
    var legW = 1f

    fun set(o: Solved): Solved {
        turn = o.turn; body.set(o.body); bodyO.set(o.bodyO); headO.set(o.headO); headP = o.headP; headY = o.headY
        rArm.set(o.rArm); rArmO.set(o.rArmO); lArm.set(o.lArm); lArmO.set(o.lArmO)
        rLeg.set(o.rLeg); rLegO.set(o.rLegO); lLeg.set(o.lLeg); lLegO.set(o.lLegO); legW = o.legW
        return this
    }

    fun mix(a: Solved, b: Solved, t: Float): Solved {
        if (t <= 0f) return set(a)
        if (t >= 1f) return set(b)
        turn = a.turn + ArtPoseMath.wrap(b.turn - a.turn) * t
        a.body.slerp(b.body, t, body); ArtPoseMath.lerpV(a.bodyO, b.bodyO, t, bodyO)
        ArtPoseMath.lerpV(a.headO, b.headO, t, headO); headP = lerp(a.headP, b.headP, t); headY = lerp(a.headY, b.headY, t)
        a.rArm.slerp(b.rArm, t, rArm); ArtPoseMath.lerpV(a.rArmO, b.rArmO, t, rArmO)
        a.lArm.slerp(b.lArm, t, lArm); ArtPoseMath.lerpV(a.lArmO, b.lArmO, t, lArmO)
        a.rLeg.slerp(b.rLeg, t, rLeg); ArtPoseMath.lerpV(a.rLegO, b.rLegO, t, rLegO)
        a.lLeg.slerp(b.lLeg, t, lLeg); ArtPoseMath.lerpV(a.lLegO, b.lLegO, t, lLegO)
        legW = lerp(a.legW, b.legW, t)
        return this
    }

    /** Spiegelt an der Mittelebene (x → -x) und tauscht die Seiten: rechtshändige Pose → linkshändig. */
    fun mirrorFrom(o: Solved): Solved {
        turn = -o.turn
        mq(o.body, body); mv(o.bodyO, bodyO); mv(o.headO, headO); headP = o.headP; headY = -o.headY
        mq(o.lArm, rArm); mv(o.lArmO, rArmO); mq(o.rArm, lArm); mv(o.rArmO, lArmO)
        mq(o.lLeg, rLeg); mv(o.lLegO, rLegO); mq(o.rLeg, lLeg); mv(o.rLegO, lLegO)
        legW = o.legW
        return this
    }

    private fun mq(q: Quaternionf, out: Quaternionf) { out.set(q.x, -q.y, -q.z, q.w) }
    private fun mv(v: Vector3f, out: Vector3f) { out.set(-v.x, v.y, v.z) }
}

/**
 * Haltungen der dritten Person für Kampfhandschuhe und Solvane. Wird aus dem Mixin auf
 * [PlayerEntityModel.setAngles] (Teile) und [net.minecraft.client.render.entity.PlayerEntityRenderer.setupTransforms]
 * (Drehung der ganzen Figur) aufgerufen. Je Spieler und Zeitpunkt wird genau einmal gerechnet; neue Animationen
 * blenden weich aus der zuletzt gezeigten Haltung über, Beginn/Ende des Handschuh-Haltens weich aus/zu Vanilla.
 */
internal object ArtPose {

    /**
     * Solvane im Armraum der Hand (rechter Arm, px): Mitte des Griffs, Klingenrichtung, Klingenansatz/-spitze, Flachseite.
     * Abgeleitet aus HeldItemFeatureRenderer + display.thirdperson_righthand des **aufrechten** solvane_3d.json (Klinge
     * steht 80° zum Unterarm, leicht zur Hand geneigt; vorher diagonal 110°). Griff im Modell y −4,5..1,5, Knauf −6,15.
     * V6: Klinge 25 statt 18,5 lang (Spitze y 28), Anzeigegröße 0,9 statt 0,95 (gear_art.SOLVANE_TP_SCALE); GRIP liegt
     * wie bisher eine Modelleinheit unter dem Griffpunkt (y −1,5), Ansatz/Spitze = (3 bzw. 28 + 1,5) × 0,9.
     */
    val GRIP = Vector3f(-1f, 8.31f, 0.75f)
    val BLADE = Vector3f(0f, 0.174f, -0.985f)
    const val BLADE_FROM = 4.05f
    const val BLADE_TO = 26.55f
    private val POMMEL = Vector3f(GRIP).fma(-3.6f, BLADE)

    class Ctx {
        var gauntlets = false
        var solvane = false
        var magic = false
        var sneaking = false
        var walk = 0f
        var headPitch = 0f
        var headYaw = 0f
    }

    private class Mem {
        var key = Long.MIN_VALUE
        val from = Solved()
        var fromTime = 0f
        var blendDur = 3f
        var blending = false
        val last = Solved()
        var hasLast = false
        var weight = 0f
        var weightTime = Float.NaN
        var evalTime = Float.NaN
        val out = Solved()
        var outWeight = 0f
        var touched = 0L
    }

    private val mems = HashMap<Int, Mem>()
    private val pose = Pose()
    private val keyA = Pose()
    private val keyB = Pose()
    private val keyC = Pose()
    private val keyD = Pose()
    private val keyE = Pose()
    private val target = Solved()
    private val ctx = Ctx()
    private var lastSweep = 0L

    // --- Schnittstelle für Mixins ----------------------------------------------------------------------------------

    /**
     * Drehung der ganzen Figur (Ende von setupTransforms, Entity-Raum in Blöcken). Gedreht wird um das Standbein
     * (rechtshändig das linke), damit der Fuß beim Seiten- und Drehtritt nicht rutscht.
     */
    @JvmStatic
    fun applyTurn(state: PlayerEntityRenderState, matrices: MatrixStack) {
        val m = evaluate(state) ?: return
        val turn = m.out.turn * m.outWeight
        if (turn == 0f) return
        // Modell-x (px) → Entity-x (Blöcke): gespiegelt, /16, mal Spielermaßstab 0,9375.
        val supportModelX = if (state.mainArm == Arm.LEFT) -1.9f else 1.9f
        val foot = -supportModelX / 16f * 0.9375f
        matrices.translate(foot, 0f, 0f)
        matrices.multiply(RotationAxis.POSITIVE_Y.rotation(turn))
        matrices.translate(-foot, 0f, 0f)
    }

    /** Setzt die Teile des Modells (Ende von setAngles). */
    @JvmStatic
    fun apply(model: PlayerEntityModel, state: PlayerEntityRenderState) {
        val m = evaluate(state) ?: return
        val w = m.outWeight
        if (w <= 0.001f) return
        val s = m.out
        part(model.body, s.body, s.bodyO, w)
        part(model.rightArm, s.rArm, s.rArmO, w)
        part(model.leftArm, s.lArm, s.lArmO, w)
        part(model.rightLeg, s.rLeg, s.rLegO, w * s.legW)
        part(model.leftLeg, s.lLeg, s.lLegO, w * s.legW)
        val head = model.head
        head.originX = lerp(head.originX, s.headO.x, w)
        head.originY = lerp(head.originY, s.headO.y, w)
        head.originZ = lerp(head.originZ, s.headO.z, w)
        head.pitch += s.headP * w
        head.yaw = (head.yaw + s.headY * w).coerceIn(-1.75f, 1.75f)
    }

    // --- Auswertung --------------------------------------------------------------------------------------------------

    private val qv = Quaternionf()
    private val ev = Vector3f()

    private fun part(p: ModelPart, q: Quaternionf, o: Vector3f, w: Float) {
        if (w <= 0.001f) return
        if (w >= 0.999f) {
            q.getEulerAnglesZYX(ev)
            p.originX = o.x; p.originY = o.y; p.originZ = o.z
        } else {
            ArtPoseMath.quat(p.pitch, p.yaw, p.roll, qv).slerp(q, w).getEulerAnglesZYX(ev)
            p.originX = lerp(p.originX, o.x, w); p.originY = lerp(p.originY, o.y, w); p.originZ = lerp(p.originZ, o.z, w)
        }
        p.pitch = ev.x; p.yaw = ev.y; p.roll = ev.z
    }

    private fun evaluate(state: PlayerEntityRenderState): Mem? {
        val td = state.age - floor(state.age)
        val now = ArtAnims.ticks + td
        val mem = mems.getOrPut(state.id) { Mem() }
        mem.touched = ArtAnims.ticks
        if (mem.evalTime == now) return mem
        mem.evalTime = now
        sweep()

        val anim = ArtAnims.get(state.id)
        val stack: ItemStack = state.mainHandItemStack
        fillCtx(state, stack, anim, td)
        val posture = normalPosture(state)
        val age = anim?.age(td) ?: 0f
        val wanted = if (!posture) 0f else wantedWeight(anim, age)

        // Gewicht zu Vanilla (zeitbasiert, daher bei mehreren Aufrufen je Bild gleich).
        val dt = if (mem.weightTime.isNaN()) 99f else (now - mem.weightTime).coerceIn(0f, 10f)
        mem.weightTime = now
        val rate = if (wanted > mem.weight) 0.3f else 0.22f
        mem.weight = if (dt >= 99f) wanted else approach(mem.weight, wanted, dt * rate)

        val key = keyOf(anim)
        if (wanted <= 0f && anim == null) {
            // Nur noch ausblenden: letzte Haltung einfrieren.
            mem.key = key
            mem.blending = false
            mem.outWeight = mem.weight
            if (!mem.hasLast) return null
            output(mem, state)
            return mem
        }

        build(anim, age, now, ctx, pose)
        solve(pose, ctx, target)

        if (key != mem.key) {
            if (mem.hasLast && mem.weight > 0.02f) {
                mem.from.set(mem.last)
                mem.fromTime = now
                mem.blendDur = blendTicks(anim)
                mem.blending = true
            } else {
                mem.blending = false
            }
            mem.key = key
        }
        val cur = if (mem.blending) {
            val f = smooth((now - mem.fromTime) / mem.blendDur)
            if (f >= 1f) mem.blending = false
            keySolved.mix(mem.from, target, f)
        } else target
        mem.last.set(cur)
        mem.hasLast = true
        mem.outWeight = mem.weight
        output(mem, state)
        return mem
    }

    private val keySolved = Solved()

    private fun output(mem: Mem, state: PlayerEntityRenderState) {
        if (state.mainArm == Arm.LEFT) mem.out.mirrorFrom(mem.last) else mem.out.set(mem.last)
    }

    private fun approach(v: Float, to: Float, step: Float): Float =
        if (v < to) minOf(to, v + step) else maxOf(to, v - step)

    private fun sweep() {
        if (ArtAnims.ticks - lastSweep < 200) return
        lastSweep = ArtAnims.ticks
        mems.values.removeIf { ArtAnims.ticks - it.touched > 100 }
    }

    private fun keyOf(anim: ArtAnims.State?): Long {
        val item = if (ctx.gauntlets) 1L else if (ctx.solvane) 2L else 0L
        if (anim == null) return -1L - item
        return (anim.start shl 8) or (anim.anim.ordinal.toLong() shl 2) or item
    }

    private fun normalPosture(s: PlayerEntityRenderState): Boolean =
        !s.spectator && !s.isGliding && !s.isSwimming && s.leaningPitch <= 0f && !s.hasVehicle &&
            !s.isInPose(EntityPose.SLEEPING) && !s.usingRiptide && s.deathTime <= 0f

    private fun wantedWeight(anim: ArtAnims.State?, age: Float): Float {
        if (anim == null) return if (ctx.gauntlets) 1f else 0f
        return when (anim.anim) {
            Anim.NONE -> if (ctx.gauntlets) 1f else 0f
            Anim.ART_RELEASE -> if (age < 13f || ctx.gauntlets) 1f else 0f
            Anim.WHITE_HOLE -> if (age < ArtTimings.WHITE_HOLE - 4 || ctx.gauntlets) 1f else 0f
            else -> 1f
        }
    }

    private fun blendTicks(anim: ArtAnims.State?): Float {
        if (anim == null) return 4f
        val w = anim.param.coerceAtLeast(1).toFloat()
        return when (anim.anim) {
            Anim.JAB, Anim.CROSS -> minOf(1.6f, w * 0.8f)
            Anim.HOOK -> minOf(2f, w * 0.5f)
            Anim.SIDE_KICK, Anim.SPIN_KICK -> minOf(2.5f, w * 0.5f)
            Anim.PARRY -> 1.5f
            Anim.COUNTER -> 1.2f
            Anim.ART_RELEASE -> 1.2f
            Anim.CAST_TAP, Anim.CAST_RELEASE -> 1.5f
            else -> 3f
        }
    }

    private fun fillCtx(state: PlayerEntityRenderState, stack: ItemStack, anim: ArtAnims.State?, td: Float) {
        ctx.gauntlets = GauntletsItem.isGauntlets(stack)
        ctx.solvane = stack.isOf(GearItems.SOLVANE)
        ctx.magic = ctx.gauntlets && GauntletLook.displayMode(stack, anim, td) == GauntletMode.MAGIC
        ctx.sneaking = state.isInSneakingPose
        ctx.walk = clamp01(state.limbSwingAmplitude * 1.4f)
        ctx.headPitch = state.pitch * (PI.toFloat() / 180f)
        ctx.headYaw = state.relativeHeadYaw * (PI.toFloat() / 180f) * (if (state.mainArm == Arm.LEFT) -1f else 1f)
    }

    // --- Lösen -------------------------------------------------------------------------------------------------------

    private val qT = Quaternionf()
    private val qa = Quaternionf()
    private val qb = Quaternionf()
    private val hip = Vector3f()
    private val tmp = Vector3f()
    private val tmp2 = Vector3f()
    private val shR = Vector3f()
    private val shL = Vector3f()

    fun solve(p: Pose, c: Ctx, out: Solved) {
        val tPitch = p.tPitch + if (c.sneaking) 0.42f else 0f
        qT.rotationZYX(p.tRoll, p.tYaw, tPitch)
        ArtPoseMath.quat(p.rLegP, p.rLegY, p.rLegR, out.rLeg)
        ArtPoseMath.quat(p.lLegP, p.lLegY, p.lLegR, out.lLeg)
        val drop = p.drop + autoDrop(out.rLeg, out.lLeg) * p.legW
        hip.set(0f, 12f + drop, 0f)
        out.body.set(qT)
        qT.transform(tmp.set(0f, 12f, 0f))
        out.bodyO.set(hip).sub(tmp)
        out.headO.set(out.bodyO)
        qT.transform(shR.set(-5f, -10f, 0f)).add(hip)
        qT.transform(shL.set(5f, -10f, 0f)).add(hip)

        // Rechter Arm: Handfläche oder Klinge.
        tmp.set(p.rHand).sub(shR)
        if (p.sword > 0.001f) wrist(tmp, p.blade, clamp01(p.sword))
        ArtPoseMath.armFrame(tmp, p.rPalm, true, qa)
        if (p.sword > 0.001f) {
            ArtPoseMath.bladeFrame(tmp, p.blade, qb)
            qa.slerp(qb, clamp01(p.sword))
        }
        out.rArm.set(qa)
        out.rArmO.set(shR)

        // Linker Arm: Zielpunkt, beim beidhändigen Griff zum Knauf unter der rechten Hand.
        tmp2.set(p.lHand)
        val palm = Vector3f(p.lPalm)
        if (p.grip > 0.001f) {
            val pommel = out.rArm.transform(Vector3f(POMMEL)).add(shR)
            ArtPoseMath.lerpV(p.lHand, pommel, clamp01(p.grip), tmp2)
            val rPalmNow = out.rArm.transform(Vector3f(1f, 0f, 0f))
            ArtPoseMath.nlerp(p.lPalm, rPalmNow, clamp01(p.grip), palm)
        }
        tmp.set(tmp2).sub(shL)
        if (p.grip > 0.001f) {
            // Starre Arme: reicht der Griff nicht ganz, schiebt sich die Schulter ein Stück nach (höchstens 2,5 px).
            val dist = tmp.length()
            val shift = (dist - 9.6f).coerceIn(-1f, 2.5f) * clamp01(p.grip)
            if (dist > 1e-3f) shL.fma(shift / dist, tmp)
            tmp.set(tmp2).sub(shL)
        }
        ArtPoseMath.armFrame(tmp, palm, false, out.lArm)
        out.lArmO.set(shL)

        out.rLegO.set(-1.9f, 12f + drop, 0f)
        out.lLegO.set(1.9f, 12f + drop, 0f)
        out.headP = p.headP
        out.headY = p.headY
        out.turn = p.turn
        out.legW = p.legW
    }

    private val wT = Vector3f()
    private val wP = Vector3f()

    /**
     * Starres Handgelenk: Solvane steht fest 80° zum Unterarm ([BLADE]). Damit die Klinge genau in die gewünschte Richtung
     * [blade] zeigt, weicht die Hand auf der Kugel um die Schulter aus: [dir] (Schulter → Hand) wird in der Ebene aus
     * dir und blade auf den nötigen Winkel gedreht (Anteil [w]). Länge bleibt.
     */
    private fun wrist(dir: Vector3f, blade: Vector3f, w: Float) {
        val len = dir.length()
        if (len < 1e-3f) return
        wT.set(blade).normalize()
        val cosNow = dir.dot(wT) / len
        wP.set(dir).fma(-dir.dot(wT), wT)
        if (wP.lengthSquared() < 1e-6f) return
        wP.normalize()
        val want = Math.acos(BLADE.y.toDouble()).toFloat()
        val now = Math.acos(cosNow.coerceIn(-1f, 1f).toDouble()).toFloat()
        val ang = lerp(now, want, w)
        dir.set(wT).mul(cos(ang)).fma(sin(ang), wP).mul(len)
    }

    /** Hüfte so weit absenken, dass schräg stehende Standbeine (nicht das tretende) den Boden behalten. */
    private fun autoDrop(r: Quaternionf, l: Quaternionf): Float {
        var sum = 0f
        var n = 0
        for (q in arrayOf(r, l)) {
            val y = q.transform(tmp.set(0f, 1f, 0f)).y
            if (y > 0.75f) { sum += 12f * (1f - y); n++ }
        }
        return if (n == 0) 0f else sum / n
    }

    // --- Haltungen ---------------------------------------------------------------------------------------------------

    /** Ruhehaltung je nach gehaltenem Gegenstand und Modus. */
    private fun idle(p: Pose, c: Ctx, t: Float, magic: Boolean = c.magic) {
        when {
            c.gauntlets && magic -> magicIdle(p, c, t)
            c.gauntlets -> guard(p, c, t)
            else -> neutral(p, c)
        }
    }

    /** Boxdeckung (Rechtsauslage: linke Seite vorn), Fäuste vor Kinn und Gesicht, leichtes Atmen. */
    fun guard(p: Pose, c: Ctx, t: Float) {
        p.reset()
        val b = breathe(t)
        p.tYaw = 0.25f; p.tPitch = 0.08f + 0.015f * b
        p.rHand.set(-1.8f, -1.0f + 0.25f * b, -6.5f); p.rPalm.set(1f, 0.3f, -0.2f)
        p.lHand.set(1.8f, -2.0f + 0.25f * breathe(t, phase = 0.6f), -9.0f); p.lPalm.set(-1f, 0.3f, -0.2f)
        p.rLeg(0.22f, 0.25f, 0.06f); p.lLeg(-0.28f, -0.1f, -0.06f)
        p.legW = 1f - c.walk
        p.headP = 0.08f
    }

    /** Magiemodus: lockere, offene Hände neben der Hüfte, ruhiger Stand. */
    fun magicIdle(p: Pose, c: Ctx, t: Float) {
        p.reset()
        p.tYaw = 0.1f; p.tPitch = 0.03f + 0.01f * breathe(t)
        p.rHand.set(-6.2f, 10f + 0.3f * breathe(t, 0.07f), -3.6f); p.rPalm.set(1f, -0.3f, 0f)
        p.lHand.set(6.2f, 10f + 0.3f * breathe(t, 0.07f, 1f), -3.6f); p.lPalm.set(-1f, -0.3f, 0f)
        p.rLeg(0.12f, 0.15f, 0.05f); p.lLeg(-0.14f, -0.05f, -0.05f)
        p.legW = 1f - c.walk
        p.headP = 0.02f
    }

    /** Ohne Handschuhe: Arme locker hängend (nur Ausgangspunkt für Weißes Loch). */
    private fun neutral(p: Pose, c: Ctx) {
        p.reset()
        p.rHand.set(-5.8f, 12f, -1.5f); p.lHand.set(5.8f, 12f, -1.5f)
        p.legW = 0f
    }

    private fun build(s: ArtAnims.State?, a: Float, t: Float, c: Ctx, p: Pose) {
        if (s == null || s.anim == Anim.NONE) { idle(p, c, t); return }
        val dur = s.duration.toFloat()
        when (s.anim) {
            Anim.JAB -> strike(p, c, t, a, s, Move.JAB) { e ->
                e.tYaw = 0.5f; e.tPitch = 0.12f
                e.lHand.set(0.6f, -2.4f, -16f); e.lPalm.set(0f, 1f, -0.2f)
                e.rHand.set(-1.6f, -1.3f, -6.0f)
                e.lLegP = -0.34f; e.rLegP = 0.3f; e.legW = 1f - 0.6f * c.walk; e.headP = 0.12f
            }
            Anim.CROSS -> strike(p, c, t, a, s, Move.CROSS) { e ->
                e.tYaw = -0.35f; e.tPitch = 0.15f; e.drop = 0.5f
                e.rHand.set(-0.4f, -2.4f, -16.5f); e.rPalm.set(0f, 1f, -0.2f)
                e.lHand.set(1.4f, -1.2f, -6.2f)
                e.rLeg(0.26f, -0.15f, 0.06f); e.lLegP = -0.32f; e.legW = 1f - 0.6f * c.walk; e.headP = 0.1f
            }
            Anim.HOOK -> hook(p, c, t, a, s)
            Anim.SIDE_KICK -> sideKick(p, c, t, a, s)
            Anim.SPIN_KICK -> spinKick(p, c, t, a, s)
            Anim.PARRY -> {
                val window = s.param.coerceAtLeast(1).toFloat()
                guard(keyA, c, t); parry(keyB, c, t)
                val k = if (a < window) easeOut(a / 2f) else 1f - smooth(seg(a, window, maxOf(dur, window + 1f)))
                p.mix(keyA, keyB, k)
            }
            Anim.COUNTER -> {
                val w = s.param.coerceAtLeast(1).toFloat()
                val d = if (dur > 0f) dur else Move.COUNTER.total.toFloat()
                parry(keyA, c, t); guard(keyC, c, t)
                keyB.set(keyC).apply {
                    tYaw = -0.4f; tPitch = 0.14f; drop = 0.4f
                    rHand.set(-0.4f, -1.5f, -16f); rPalm.set(0f, -0.2f, -1f)
                    lHand.set(1.6f, -1.2f, -6.3f)
                    rLeg(0.28f, -0.15f, 0.06f); lLegP = -0.34f; legW = 1f
                }
                when {
                    a < w -> p.mix(keyA, keyB, easeOutCubic(a / w))
                    a < w + 1f -> p.set(keyB)
                    else -> p.mix(keyB, keyC, smooth(seg(a, w + 1f, d)))
                }
            }
            Anim.MODE_SWITCH -> {
                val u = seg(a, 0f, ArtTimings.MODE_SWITCH.toFloat())
                val newMagic = s.param == 1
                idle(keyA, c, t, !newMagic); idle(keyB, c, t, newMagic)
                p.mix(keyA, keyB, smooth(u))
                keyC.set(p).apply {
                    tYaw = 0.05f; tPitch = 0.1f
                    rHand.set(-3.2f, 4.5f, -7.5f); rPalm.set(1f, 0f, 0f)
                    lHand.set(3.2f, 4.5f, -7.5f); lPalm.set(-1f, 0f, 0f)
                }
                keyA.set(p)
                p.mix(keyA, keyC, sin(u * PI.toFloat()))
            }
            Anim.CAST_TAP -> castTap(p, c, t, a, School.entries.getOrElse(ArtParam.school(s.param)) { School.WIND })
            Anim.CAST_CHARGE -> {
                magicIdle(keyA, c, t); castCharge(keyB, c, t, a, School.entries.getOrElse(ArtParam.school(s.param)) { School.WIND })
                p.mix(keyA, keyB, easeOut(a / 5f))
            }
            Anim.CAST_RELEASE -> {
                magicIdle(keyC, c, t)
                keyB.set(keyC).apply {
                    tPitch = 0.16f; tYaw = -0.05f; drop = 1f
                    rHand.set(-1.3f, 1.0f, -15.5f); rPalm.set(1f, 0.4f, 0f)
                    lHand.set(1.3f, 1.0f, -15.5f); lPalm.set(-1f, 0.4f, 0f)
                    rLeg(0.3f, 0.2f, 0.06f); lLeg(-0.38f, -0.05f, -0.06f); legW = 1f
                }
                p.mix(if (a < 3f) keyC else keyB, if (a < 3f) keyB else keyC,
                    if (a < 3f) easeOutCubic(a / 2f) else smooth(seg(a, 3f, ArtTimings.CAST_RELEASE.toFloat())))
            }
            Anim.PLACE_AIM -> {
                magicIdle(keyA, c, t)
                keyB.set(keyA).apply {
                    tYaw = -0.2f; tPitch = 0.04f
                    val hp = c.headPitch; val hy = c.headYaw
                    rHand.set(-5f - 14f * sin(hy) * cos(hp), 2f + 14f * sin(hp), -14f * cos(hy) * cos(hp))
                    rPalm.set(0f, 1f, 0f)
                    lHand.set(5.5f, 6.5f, -6f); lPalm.set(-1f, -0.5f, 0f)
                }
                p.mix(keyA, keyB, easeOut(a / 4f))
            }
            Anim.SHIELD -> {
                idle(keyA, c, t)
                keyB.reset().apply {
                    tPitch = -0.04f; drop = 0.8f
                    val b = 0.2f * breathe(t, 0.08f)
                    rHand.set(-2.6f, -4.8f + b, -9f); rPalm.set(0f, 0f, -1f)
                    lHand.set(2.6f, -4.8f + b, -9f); lPalm.set(0f, 0f, -1f)
                    rLeg(0.3f, 0.15f, 0.08f); lLeg(-0.32f, -0.05f, -0.08f); legW = 1f
                }
                p.mix(keyA, keyB, easeOut(a / 4f))
            }
            Anim.GLIDE -> {
                idle(keyA, c, t)
                keyB.reset().apply {
                    tPitch = 0.12f; tRoll = 0.06f * breathe(t, 0.06f)
                    rHand.set(-14f, 6.5f, -1.5f); rPalm.set(0f, 1f, 0f)
                    lHand.set(14f, 6.5f, -1.5f); lPalm.set(0f, 1f, 0f)
                    rLeg(0.12f, 0.05f, 0.04f); lLeg(-0.16f, -0.05f, -0.04f); legW = 1f; headP = 0.1f
                }
                p.mix(keyA, keyB, easeOut(a / 5f))
            }
            Anim.ART_CHARGE -> artCharge(p, c, t, ArtParam.horizontal(s.param), ArtTimings.artCharge(a))
            Anim.ART_RELEASE -> artRelease(p, c, t, a, ArtParam.horizontal(s.param), ArtParam.charge(s.param))
            Anim.WHITE_HOLE -> whiteHole(p, c, t, a)
            Anim.NONE -> idle(p, c, t)
        }
    }

    /**
     * Ausholen und Beschleunigen bis zum Treffer: kurz gegen die Schlagrichtung (bis −[back]), dann immer schneller, am
     * Treffer (u = 1) mit voller Geschwindigkeit genau auf 1. Wucht statt Abbremsen vor dem Ziel.
     */
    private fun drive(u: Float, back: Float = 0.35f): Float {
        val x = clamp01(u)
        return x * x - back * x * (1f - x) * (1f - x)
    }

    /** Kleiner Ausschlag 0 → 1 → 0 über [u] (für Überschwung, Einsinken, Federn). */
    private fun bump(u: Float): Float = if (u <= 0f || u >= 1f) 0f else sin(PI.toFloat() * u)

    /**
     * Gerader Schlag: Hüfte und Rumpf führen (erreichen die Schlaghaltung vor der Faust), die Faust holt minimal aus und
     * beschleunigt bis t = windup (Treffer). Danach schwingt sie etwas über das Ziel hinaus, das Gewicht sinkt kurz ein,
     * und sie federt zurück in die Deckung (mit leichtem Rückprall).
     */
    private inline fun strike(p: Pose, c: Ctx, t: Float, a: Float, s: ArtAnims.State, move: Move, ext: (Pose) -> Unit) {
        val w = s.param.coerceAtLeast(1).toFloat()
        val d = if (s.duration > 0) s.duration.toFloat() else (w + move.recover)
        guard(keyA, c, t)
        keyB.set(keyA)
        ext(keyB)
        val follow = 2.5f
        when {
            a < w -> {
                val u = a / w
                p.mixFree(keyA, keyB, drive(u, 0.3f))
                p.bodyFrom(keyA, keyB, smooth(u * 1.3f))
            }
            a < w + follow -> {
                val v = seg(a, w, w + follow)
                p.mixFree(keyA, keyB, 1f + 0.1f * bump(v))
                p.bodyFrom(keyA, keyB, 1f + 0.22f * bump(v))
                p.drop += 0.45f * bump(v)
            }
            else -> {
                val v = seg(a, w + follow, d)
                p.mixFree(keyA, keyB, 1f - smooth(v) - 0.06f * bump(v))
            }
        }
    }

    /**
     * Körperhaken links: Gewicht aufs vordere Bein laden (Hüfte dreht zurück, Ellbogen öffnet), dann peitscht die Hüfte
     * vor, Schulter und Faust folgen im Bogen bis zum Treffer; die Hüfte dreht über die Mitte nach, die Faust zieht durch
     * und kehrt federnd in die Deckung zurück.
     */
    private fun hook(p: Pose, c: Ctx, t: Float, a: Float, s: ArtAnims.State) {
        val w = s.param.coerceAtLeast(1).toFloat()
        val d = if (s.duration > 0) s.duration.toFloat() else (w + Move.HOOK.recover)
        guard(keyA, c, t)
        keyB.set(keyA).apply {   // geladen
            tYaw = -0.12f; tPitch = 0.16f; tRoll = 0.16f; drop = 1.3f
            lHand.set(8.0f, 4.2f, -4.5f); lPalm.set(0f, 1f, 0f)
            rHand.set(-1.8f, -1.2f, -6.3f); lLeg(-0.36f, -0.1f, -0.06f); rLeg(0.26f, 0.2f, 0.06f); legW = 1f - 0.5f * c.walk
        }
        keyC.set(keyA).apply {   // Treffer
            tYaw = 0.8f; tPitch = 0.17f; tRoll = -0.06f; drop = 0.9f
            lHand.set(-0.8f, 5.0f, -11.5f); lPalm.set(0f, 1f, 0.2f)
            rHand.set(-1.8f, -1.4f, -6.0f)
            lLeg(-0.2f, -0.38f, -0.06f); rLeg(0.2f, 0.34f, 0.08f); legW = 1f - 0.5f * c.walk; headP = 0.12f
        }
        val load = 0.42f * w
        when {
            a < load -> p.mix(keyA, keyB, easeOut(a / load))
            a < w -> {
                val u = seg(a, load, w)
                p.mix(keyB, keyC, drive(u, 0f))
                bezier(keyB.lHand, HOOK_CTRL, keyC.lHand, drive(u, 0f), p.lHand)
                p.bodyFrom(keyB, keyC, smooth(u * 1.35f))
            }
            a < w + 3f -> {
                val v = seg(a, w, w + 3f)
                p.mixFree(keyC, keyB, -0.12f * bump(v))
                p.bodyFrom(keyA, keyC, 1f + 0.18f * bump(v))
                p.drop += 0.5f * bump(v)
            }
            else -> {
                val v = seg(a, w + 3f, d)
                p.mixFree(keyC, keyA, smooth(v) + 0.06f * bump(v))
            }
        }
    }

    private val HOOK_CTRL = Vector3f(5.5f, 4.8f, -12f)

    /**
     * Seitentritt rechts: Gewicht aufs Standbein (leicht einsinken), Knie hoch in die Kammer, der Rumpf lehnt sich als
     * Gegengewicht zurück; das Bein schießt beschleunigend aus (Treffer bei t = windup), hält kurz, kehrt in die Kammer
     * zurück und setzt mit spürbarem Gewicht auf (Einsinken), dann zurück in die Deckung.
     */
    private fun sideKick(p: Pose, c: Ctx, t: Float, a: Float, s: ArtAnims.State) {
        val w = s.param.coerceAtLeast(2).toFloat()
        val d = if (s.duration > 0) s.duration.toFloat() else (w + Move.SIDE_KICK.recover)
        guard(keyA, c, t)
        keyB.set(keyA).apply {   // Kammer
            turn = 0.75f; headY = 0.7f; tYaw = 0.22f; tRoll = 0.24f; tPitch = 0.02f; drop = 1.1f
            rLeg(-1.3f, 0f, 0.38f); lLeg(0.08f, -0.1f, -0.12f); legW = 1f
            rHand.set(-3.0f, 0f, -6.5f); lHand.set(2.0f, -2f, -8f)
        }
        keyC.set(keyA).apply {   // gestreckt
            turn = 1.2f; headY = 1.05f; tYaw = 0.1f; tRoll = 0.56f; tPitch = 0f; drop = 0.4f
            rLeg(-0.3f, 0f, 1.58f); lLeg(0.05f, -0.15f, -0.2f); legW = 1f
            rHand.set(-11f, 7f, -2f); rPalm.set(0f, 1f, 0f)
            lHand.set(2.5f, -1.0f, -6.0f); lPalm.set(-1f, 0.3f, 0f)
        }
        val ch = 0.55f * w
        when {
            a < ch -> p.mix(keyA, keyB, easeOut(a / ch))
            a < w -> {
                val u = seg(a, ch, w)
                p.mix(keyB, keyC, drive(u, 0.12f).coerceAtLeast(0f))
                p.bodyFrom(keyB, keyC, smooth(u * 1.25f))
            }
            a < w + 1.5f -> { p.set(keyC); p.tRoll += 0.05f * bump(seg(a, w, w + 1.5f)) }
            a < w + 4.5f -> p.mix(keyC, keyB, smooth(seg(a, w + 1.5f, w + 4.5f)))
            else -> {
                val v = seg(a, w + 4.5f, d)
                p.mix(keyB, keyA, smooth(v))
                // Aufsetzen: der Fuß landet, das Gewicht sinkt kurz ein.
                p.drop += 1.0f * bump(seg(v, 0.15f, 0.7f))
            }
        }
    }

    /**
     * Drehtritt: Sammeln (einsinken, kurz gegen die Drehrichtung ausholen), dann eine Umdrehung im Uhrzeigersinn um das
     * Standbein, immer schneller. Das rechte Bein ist angezogen und streckt sich zum Treffer (t = windup, Drehung ≈ 285°)
     * nach vorn; die Arme schwingen als Gegengewicht mit. Danach zieht das Bein ein, die Drehung läuft weich auf 360° aus,
     * der Fuß setzt mit Gewicht auf.
     */
    private fun spinKick(p: Pose, c: Ctx, t: Float, a: Float, s: ArtAnims.State) {
        val w = s.param.coerceAtLeast(2).toFloat()
        val d = if (s.duration > 0) s.duration.toFloat() else (w + Move.SPIN_KICK.recover)
        val hitDeg = 285f
        val gather = 0.22f * w
        val deg = when {
            a < gather -> -16f * sin(PI.toFloat() * 0.5f * (a / gather))
            a < w -> {
                val u = seg(a, gather, w)
                -16f * (1f - u) * (1f - u) + hitDeg * Math.pow(u.toDouble(), 1.7).toFloat()
            }
            a < w + 6f -> hitDeg + (360f - hitDeg) * easeOut(seg(a, w, w + 6f))
            else -> 0f
        }
        guard(keyA, c, t)
        keyD.set(keyA).apply {   // Sammeln: tief, Rumpf leicht eingedreht
            drop = 1.6f; tYaw = 0.4f; tPitch = 0.12f
            rLeg(0.3f, 0.2f, 0.12f); lLeg(-0.36f, -0.1f, -0.1f); legW = 1f
            rHand.set(-2.6f, 0.2f, -6.0f); lHand.set(2.4f, -0.8f, -7.6f)
        }
        keyB.set(keyA).apply {   // Drehen, Bein angezogen, Arme eng
            tPitch = 0.05f; tRoll = 0.15f; drop = 0.6f
            rLeg(-0.7f, 0f, 0.32f); lLeg(0.02f, 0f, -0.1f); legW = 1f
            rHand.set(-5.5f, 3f, -5f); lHand.set(3f, -1.5f, -6f)
        }
        keyC.set(keyA).apply {   // gestreckt, Arme öffnen als Gegengewicht
            tPitch = 0.04f; tRoll = 0.48f; drop = 0.3f
            rLeg(-0.25f, 0f, 1.52f); lLeg(0.05f, 0f, -0.16f); legW = 1f
            rHand.set(-11f, 6f, -1f); rPalm.set(0f, 1f, 0f)
            lHand.set(6.5f, 2.5f, -3.5f); lPalm.set(-1f, 0.4f, 0f)
        }
        when {
            a < gather -> p.mix(keyA, keyD, easeOut(a / gather))
            a < 0.55f * w -> p.mix(keyD, keyB, smooth(seg(a, gather, 0.55f * w)))
            a < w -> p.mix(keyB, keyC, drive(seg(a, 0.55f * w, w), 0f))
            a < w + 1.5f -> p.set(keyC)
            a < w + 6f -> p.mix(keyC, keyB, smooth(seg(a, w + 1.5f, w + 6f)))
            else -> {
                val v = seg(a, w + 6f, d)
                p.mix(keyB, keyA, smooth(v))
                p.drop += 1.1f * bump(seg(v, 0.05f, 0.6f))
            }
        }
        // Umdrehung im Uhrzeigersinn = negative Drehung im Entity-Raum; 360° ≙ 0 (kein Sprung).
        p.turn = if (deg >= 359.9f) 0f else -Math.toRadians(deg.toDouble()).toFloat()
        p.headY = 0.4f * sin(PI.toFloat() * seg(a, gather, w))
    }

    /** Handflächen-Parade: beide offenen Hände hoch vor dem Gesicht, Handflächen nach vorn, Gewicht hinten. */
    private fun parry(p: Pose, c: Ctx, t: Float) {
        guard(p, c, t)
        p.tYaw = 0.1f; p.tPitch = -0.05f
        p.rHand.set(-2.5f, -4.5f, -9.0f); p.rPalm.set(0f, 0f, -1f)
        p.lHand.set(2.5f, -4.0f, -9.5f); p.lPalm.set(0f, 0f, -1f)
        p.rLegP = 0.32f; p.lLegP = -0.25f; p.legW = 1f; p.headP = 0.02f
    }

    private fun castTap(p: Pose, c: Ctx, t: Float, a: Float, school: School) {
        magicIdle(keyA, c, t)
        keyB.set(keyA).apply { lHand.set(6f, 6.5f, -5.5f) }
        when (school) {
            School.WIND -> {
                // Offene Hand fegt von rechts außen nach vorn-links: Windklinge.
                keyC.set(keyB).apply { tYaw = 0.3f; rHand.set(-10f, 3f, -4f); rPalm.set(1f, 0f, 0f) }
                val end = WIND_END
                when {
                    a < 0.8f -> p.mix(keyA, keyC, easeOut(a / 0.8f))
                    a < 2.2f -> {
                        val u = seg(a, 0.8f, 2.2f)
                        p.set(keyC); p.tYaw = lerp(0.3f, -0.35f, u)
                        bezier(keyC.rHand, WIND_CTRL, end, easeOut(u), p.rHand)
                    }
                    a < 3f -> { p.set(keyC); p.tYaw = -0.35f; p.rHand.set(end) }
                    else -> {
                        keyC.tYaw = -0.35f; keyC.rHand.set(end)
                        p.mix(keyC, keyA, smooth(seg(a, 3f, ArtTimings.CAST_TAP.toFloat())))
                    }
                }
                return
            }
            School.ICE -> keyC.set(keyB).apply {
                tYaw = -0.3f; tPitch = 0.1f; rHand.set(-1.2f, -0.8f, -15.5f); rPalm.set(0f, 1f, 0f)
            }
            School.YANG -> keyC.set(keyB).apply {
                tYaw = -0.25f; tPitch = 0.05f; rHand.set(-1.5f, -4.0f, -14.5f); rPalm.set(0f, 0f, -1f)
            }
        }
        when {
            a < 2f -> p.mix(keyA, keyC, easeOutCubic(a / 2f))
            a < 3f -> p.set(keyC)
            else -> p.mix(keyC, keyA, smooth(seg(a, 3f, ArtTimings.CAST_TAP.toFloat())))
        }
    }

    private val WIND_CTRL = Vector3f(-4f, 0.5f, -14f)
    private val WIND_END = Vector3f(3.5f, 1f, -12.5f)

    /** Aufladen: beide Hände vor der Brust, Handflächen zueinander; sie rücken langsam zusammen. */
    private fun castCharge(p: Pose, c: Ctx, t: Float, a: Float, school: School) {
        p.reset()
        p.tPitch = 0.06f; p.drop = 0.8f
        p.rLeg(0.1f, 0.2f, 0.14f); p.lLeg(-0.18f, -0.1f, -0.14f); p.legW = 1f - c.walk * 0.7f
        // Starre Arme laufen spitz zusammen: unter ±2,8 px würden sich die Fingerspitzen kreuzen.
        val sep = 3.6f - 0.8f * clamp01(a / 40f)
        var cy = 4.5f
        var ox = 0f
        var oy = 0f
        when (school) {
            School.WIND -> { ox = 0.5f * sin(t * 0.15f); oy = 0.4f * cos(t * 0.15f) }
            School.ICE -> cy = 5.0f
            School.YANG -> cy = 3.0f
        }
        p.rHand.set(-sep + ox, cy + oy, -8.5f)
        p.lHand.set(sep - ox, cy - oy, -8.5f)
        if (school == School.YANG) { p.rPalm.set(1f, -0.5f, 0f); p.lPalm.set(-1f, -0.5f, 0f) }
        else { p.rPalm.set(1f, 0f, 0f); p.lPalm.set(-1f, 0f, 0f) }
    }

    /**
     * Solvane-Haltung des Klaren Schnitts, beidhändig (Solvane steht aufrecht 80° zum Unterarm):
     * senkrecht = Hasso (Hände neben der rechten Wange, Klinge steil nach oben-hinten), waagerecht = an der Hüfte, Klinge
     * zurück. Mit der Ladung [charge] sinkt das Gewicht tiefer, der Rumpf dreht weiter ein, bei voller Ladung zittert die
     * Spannung ganz leicht in den Händen.
     */
    private fun artCharge(p: Pose, c: Ctx, t: Float, horizontal: Boolean, charge: Float) {
        p.reset()
        val b = breathe(t, 0.08f)
        val k = smooth(charge)
        val tremor = if (charge >= 0.999f) 0.12f * sin(t * 1.9f) + 0.06f * sin(t * 3.3f) else 0f
        p.sword = 1f; p.grip = 1f; p.legW = 1f
        if (!horizontal) {
            p.tYaw = 0.35f + 0.15f * k; p.tPitch = -0.04f + 0.01f * b + 0.04f * k; p.drop = 1.2f + 1.0f * k
            p.rHand.set(-6.2f, -3f + 0.2f * b + tremor, -5.2f); p.blade.set(-0.1f, -0.85f, 0.5f + 0.1f * k)
            p.lLeg(-0.38f - 0.08f * k, -0.05f, -0.06f); p.rLeg(0.36f + 0.06f * k, 0.25f, 0.08f)
        } else {
            p.tYaw = 0.45f + 0.15f * k; p.tPitch = 0.05f + 0.01f * b; p.drop = 1.6f + 0.8f * k
            p.rHand.set(-8f, 12.8f + 0.2f * b + tremor, 1.5f); p.blade.set(-0.5f, 0f, 1f)
            p.lLeg(-0.36f - 0.08f * k, -0.1f, -0.2f); p.rLeg(0.3f + 0.06f * k, 0.3f, 0.24f)
        }
        p.lHand.set(4f, 6f, -5f)
    }

    /**
     * Der Klare Schnitt, Treffer bei [ArtTimings.ART_HIT] (3 Ticks). Ausholen mit Spannung (die Klinge geht noch einmal
     * zurück, der Körper hebt sich), dann beschleunigt der Schnitt bis zum Treffer, die Hüfte führt. Durchziehen: Der Körper
     * sinkt in den Ausfallschritt, die Klinge schwingt über die Endhaltung hinaus, die linke Hand löst sich als Gegengewicht.
     * Nachhall: tief verharren (länger bei voller Ladung), ausatmen, dann zurück in die Ruhe.
     */
    private fun artRelease(p: Pose, c: Ctx, t: Float, a: Float, horizontal: Boolean, charge: Float) {
        val hit = ArtTimings.ART_HIT.toFloat()
        artCharge(keyA, c, t, horizontal, charge)
        // keyB = Ausholen, keyC = Treffer, keyD = Durchzug, keyE = Ruhe
        keyB.set(keyA)
        keyC.set(keyA)
        if (!horizontal) {
            keyB.apply {
                rHand.set(-5f, -7.3f, 0.5f); blade.set(0f, -0.3f, 1f); tYaw = 0.45f; tPitch = -0.12f; drop = 0.5f
                lLeg(-0.3f, -0.05f, -0.06f); rLeg(0.3f, 0.25f, 0.08f)
            }
            keyC.apply {
                rHand.set(0f, 13f, -3f); blade.set(0f, 0.35f, -1f); tYaw = -0.2f; tPitch = 0.32f; drop = 2.4f; grip = 0.55f
                lHand.set(5f, 9f, -6f)
                lLeg(-0.56f, -0.05f, -0.06f); rLeg(0.46f, 0.25f, 0.08f)
            }
        } else {
            keyB.apply { rHand.set(-9f, 12.5f, 2.5f); blade.set(-0.7f, 0f, 0.7f); tYaw = 0.68f; drop = 1.2f }
            keyC.apply {
                rHand.set(-1.5f, 13f, -3.7f); blade.set(0f, 0f, -1f); tYaw = -0.15f; tPitch = 0.12f; drop = 1.9f; grip = 0.6f
                lHand.set(6f, 10f, -5f)
            }
        }
        val follow = keyD.set(keyC).apply {
            grip = 0f
            if (!horizontal) {
                rHand.set(-1f, 12.5f, 1f); blade.set(0f, 0.8f, -0.6f); tYaw = -0.3f; tPitch = 0.4f; drop = 2.9f
                lHand.set(8.5f, 8f, 2f); lPalm.set(-0.5f, 0.6f, 0.6f)
            } else {
                rHand.set(-3.6f, 13.6f, -3f); blade.set(1f, 0.15f, 0f); tYaw = -0.62f; tPitch = 0.12f; drop = 2.2f
                lHand.set(9f, 10f, 3f); lPalm.set(-0.5f, 0.6f, 0.6f)
            }
        }
        val rest = keyE.set(follow).apply {
            tYaw = 0f; tPitch = 0.02f; drop = 0f; grip = 0f
            rHand.set(-6f, 10f, -4.5f); blade.set(0f, -0.5f, -1f)
            lHand.set(6f, 10.5f, -2f); lPalm.set(-1f, 0f, 0f)
            rLeg(0.05f, 0.05f, 0.03f); lLeg(-0.05f, -0.05f, -0.03f)
        }
        val raise = 1.1f
        val linger = 9f + 3f * charge
        when {
            a < raise -> p.mix(keyA, keyB, easeOut(a / raise))
            a < hit -> {
                val u = seg(a, raise, hit)
                p.mix(keyB, keyC, drive(u, 0f))
                p.bodyFrom(keyB, keyC, smooth(u * 1.4f))
            }
            a < hit + 2.5f -> {
                val v = seg(a, hit, hit + 2.5f)
                p.mixFree(keyC, follow, easeOut(v) + 0.14f * bump(v))
                p.drop += 0.4f * bump(v)
            }
            a < linger -> {
                // Nachhall: tief verharren, langsam ausatmen; die Klinge sinkt kaum merklich nach.
                val v = seg(a, hit + 2.5f, linger)
                p.set(follow)
                p.drop += 0.35f * bump(v)
                p.blade.y += 0.06f * bump(v)
            }
            else -> p.mix(follow, rest, smooth(seg(a, linger, ArtTimings.ART_RELEASE.toFloat())))
        }
    }

    /** Weißes Loch: die Hände führen in einer langsamen Spirale zusammen (20 Ticks), dann ruhiges Halten. */
    private fun whiteHole(p: Pose, c: Ctx, t: Float, a: Float) {
        idle(keyA, c, t)
        val u = seg(a, 0f, ArtTimings.WHITE_HOLE_CAST.toFloat())
        val r = 1f - smooth(u)
        keyB.reset().apply {
            tPitch = 0.1f; drop = 1.0f
            val ox = r * 1.2f * cos(a * 0.3f)
            val oy = r * 1.5f * sin(a * 0.3f)
            rHand.set(-2.8f - r * 1.5f + ox, 3.8f + oy, -9.2f); rPalm.set(1f, 0f, 0f)
            lHand.set(2.8f + r * 1.5f - ox, 3.8f - oy, -9.2f); lPalm.set(-1f, 0f, 0f)
            rLeg(0.18f, 0.2f, 0.14f); lLeg(-0.22f, -0.1f, -0.14f); legW = 1f
        }
        val end = ArtTimings.WHITE_HOLE.toFloat()
        val k = if (a < end - 4f) easeOut(a / 6f) else 1f - smooth(seg(a, end - 4f, end))
        p.mix(keyA, keyB, k)
    }
}

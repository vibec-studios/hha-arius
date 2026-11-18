package dev.henny.hha_arius.gear.client.art

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.basisOf
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.client.fx.AriusClientConfig
import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.ArtParam
import dev.henny.hha_arius.gear.art.ArtTimings
import dev.henny.hha_arius.gear.art.CutPlane
import dev.henny.hha_arius.gear.art.School
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.clamp01
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.easeOut
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.seg
import dev.henny.hha_arius.gear.client.art.ArtPoseMath.smooth
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.Arm
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Weiche Lichtpunkte und -linien, die an Händen oder Klinge hängen. Die Ecken werden beim Einreichen schon im Zielraum
 * berechnet (Welt: kamerarelativ, erste Person: Blickraum) und als eigener Befehl auf HHAs `GLOW_ADD` gezeichnet –
 * so sitzen sie exakt auf dem posierten Arm, auch wenn die Befehle verzögert ausgeführt werden.
 */
internal object ArtVisualGlow {
    val VIEW_RIGHT = Vector3f(1f, 0f, 0f)
    val VIEW_UP = Vector3f(0f, 1f, 0f)

    fun fx(): Float = AriusClientConfig.fx.coerceIn(0f, 1f)

    fun alpha(color: Int, a: Float): Int = withAlpha(color, clamp01(a))

    class Builder(private val right: Vector3f, private val up: Vector3f) {
        private var data = FloatArray(5 * 4 * 8)
        private var colors = IntArray(4 * 8)
        private var n = 0
        private var nc = 0

        private fun put(x: Float, y: Float, z: Float, u: Float, v: Float, c: Int) {
            if (n + 5 > data.size) data = data.copyOf(data.size * 2)
            if (nc + 1 > colors.size) colors = colors.copyOf(colors.size * 2)
            data[n++] = x; data[n++] = y; data[n++] = z; data[n++] = u; data[n++] = v; colors[nc++] = c
        }

        fun billboard(c: Vector3f, size: Float, color: Int) {
            if ((color ushr 24) == 0 || size <= 0f) return
            val rx = right.x * size; val ry = right.y * size; val rz = right.z * size
            val ux = up.x * size; val uy = up.y * size; val uz = up.z * size
            put(c.x - rx - ux, c.y - ry - uy, c.z - rz - uz, 0f, 1f, color)
            put(c.x + rx - ux, c.y + ry - uy, c.z + rz - uz, 1f, 1f, color)
            put(c.x + rx + ux, c.y + ry + uy, c.z + rz + uz, 1f, 0f, color)
            put(c.x - rx + ux, c.y - ry + uy, c.z - rz + uz, 0f, 0f, color)
        }

        private val d = Vector3f()
        private val side = Vector3f()
        private val mid = Vector3f()

        /** Zur Kamera gedrehtes Band von [a] nach [b] (weiche Lichtlinie: u fest, v quer). Kamera im Ursprung. */
        fun line(a: Vector3f, b: Vector3f, half: Float, ca: Int, cb: Int) {
            if (((ca ushr 24) or (cb ushr 24)) == 0) return
            d.set(b).sub(a)
            mid.set(a).add(b).mul(-0.5f)
            d.cross(mid, side)
            if (side.lengthSquared() < 1e-10f) side.set(right)
            side.normalize(half)
            put(a.x - side.x, a.y - side.y, a.z - side.z, 0.5f, 0f, ca)
            put(a.x + side.x, a.y + side.y, a.z + side.z, 0.5f, 1f, ca)
            put(b.x + side.x, b.y + side.y, b.z + side.z, 0.5f, 1f, cb)
            put(b.x - side.x, b.y - side.y, b.z - side.z, 0.5f, 0f, cb)
        }

        /** Viereck a0–a1–b1–b0 (Streifen quer von v0 nach v1, u fest 0,5), Farbe [ca] an a, [cb] an b. */
        fun strip(a0: Vector3f, a1: Vector3f, b0: Vector3f, b1: Vector3f, v0: Float, v1: Float, ca: Int, cb: Int) {
            if (((ca ushr 24) or (cb ushr 24)) == 0) return
            put(a0.x, a0.y, a0.z, 0.5f, v0, ca)
            put(a1.x, a1.y, a1.z, 0.5f, v1, ca)
            put(b1.x, b1.y, b1.z, 0.5f, v1, cb)
            put(b0.x, b0.y, b0.z, 0.5f, v0, cb)
        }

        fun submit(queue: OrderedRenderCommandQueue, layer: RenderLayer) {
            if (n == 0) return
            val buf = data.copyOf(n)
            val cols = colors.copyOf(nc)
            queue.submitCustom(MatrixStack(), layer) { _, vc ->
                for (k in cols.indices) {
                    val i = k * 5
                    vc.vertex(buf[i], buf[i + 1], buf[i + 2]).texture(buf[i + 3], buf[i + 4]).color(cols[k])
                }
            }
            n = 0
            nc = 0
        }
    }

    /**
     * Energie entlang der Schneide: ruhige Lichtlinie (wächst mit der Ladung), wenige Lichtpunkte wandern zur Spitze,
     * bei voller Ladung ein sanftes Leuchten an der Spitze. [chargeAge] < 99 = noch in der Haltung.
     */
    fun bladeEnergy(b: Builder, p0: Vector3f, p1: Vector3f, charge: Float, time: Float, chargeAge: Float, scale: Float, fx: Float) {
        val pre = if (chargeAge < 99f) 0.12f * clamp01(chargeAge / ArtTimings.ART_CHARGE_MIN) else 0f
        val s = maxOf(pre, charge)
        if (s <= 0.01f) return
        val k = fx * s
        b.line(p0, p1, (0.03f + 0.05f * s) * scale, alpha(0xFFB8E8FF.toInt(), 0.3f * k), alpha(0xFFB8E8FF.toInt(), 0.45f * k))
        b.line(p0, p1, (0.008f + 0.014f * s) * scale, alpha(0xFFFFFFFF.toInt(), 0.55f * k), alpha(0xFFFFFFFF.toInt(), 0.85f * k))
        if (charge > 0f) {
            val pt = Vector3f()
            for (i in 0 until 4) {
                val u = ((i * 0.27f + time * 0.025f) % 1f)
                p0.lerp(p1, u, pt)
                val fade = sin(u * PI.toFloat())
                b.billboard(pt, (0.025f + 0.02f * charge) * scale, alpha(0xFFEAF8FF.toInt(), 0.6f * k * fade))
            }
        }
        if (chargeAge < 99f && charge >= 0.999f) {
            val pulse = 1f + 0.06f * sin(time * 0.2f)
            b.billboard(p1, 0.12f * scale * pulse, alpha(0xFFDDF2FF.toInt(), 0.5f * fx))
        }
    }
}

/**
 * Darstellung, die an Animationen hängt: an Händen/Klinge (dritte Person, aus dem Feature-Renderer) und in der Welt
 * (Schild, Lichtgleiter, Schnittbogen, Zielvorschau des Klaren Schnitts).
 */
internal object ArtVisuals {

    private const val YANG = 0xFFF2F6FF.toInt() // seit 1.4.1 perlweiß
    private const val YANG_CORE = 0xFFFFFFFF.toInt()
    private const val ICE = 0xFFB8E8FF.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private val UP = Vec3d(0.0, 1.0, 0.0)
    private val X = Vec3d(1.0, 0.0, 0.0)
    private val Z = Vec3d(0.0, 0.0, 1.0)

    fun init() {
        WorldRenderEvents.END_MAIN.register { context ->
            runCatching { renderWorld(context.consumers()) }
        }
    }

    // --- Hände / Klinge, dritte Person -------------------------------------------------------------------------------

    private val q = Quaternionf()
    private val tmp = Vector3f()

    /** Punkt im Armraum (px) → Zielraum, über den Arm-Teil (Drehpunkt + Drehung) und die Wurzel-Matrix [m]. */
    private fun point(m: Matrix4f, arm: ModelPart, local: Vector3f, shiftX: Float, out: Vector3f): Vector3f {
        ArtPoseMath.quat(arm.pitch, arm.yaw, arm.roll, q).transform(out.set(local))
        out.add(arm.originX + shiftX, arm.originY, arm.originZ).mul(1f / 16f)
        return m.transformPosition(out)
    }

    fun handFx(
        matrices: MatrixStack, queue: OrderedRenderCommandQueue, state: PlayerEntityRenderState, model: PlayerEntityModel,
        anim: ArtAnims.State, td: Float, slim: Boolean,
    ) {
        val fx = ArtVisualGlow.fx()
        if (fx <= 0f) return
        val a = anim.age(td)
        val time = ArtAnims.ticks + td
        val camera = MinecraftClient.getInstance().gameRenderer.camera
        val camR = camera.diagonalPlane.let { Vector3f(-it.x(), -it.y(), -it.z()) }
        val camU = Vector3f(camera.verticalPlane)
        val b = ArtVisualGlow.Builder(camR, camU)
        val mainRight = state.mainArm != Arm.LEFT
        val mainArm = if (mainRight) model.rightArm else model.leftArm

        matrices.push()
        model.rootPart.applyTransform(matrices)
        val m = Matrix4f(matrices.peek().positionMatrix)
        matrices.pop()

        val school = School.entries.getOrElse(ArtParam.school(anim.param)) { School.WIND }
        val pR = Vector3f(); val pL = Vector3f()
        fun palms() {
            point(m, model.rightArm, GauntletParts.palmPoint(true, slim, Vector3f()), 0f, pR)
            point(m, model.leftArm, GauntletParts.palmPoint(false, slim, Vector3f()), 0f, pL)
        }
        fun palmGlow(p: Vector3f, s: Float, color: Int, core: Int) {
            b.billboard(p, 0.05f + 0.05f * s, ArtVisualGlow.alpha(core, 0.8f * s * fx))
            b.billboard(p, 0.15f + 0.12f * s, ArtVisualGlow.alpha(color, 0.4f * s * fx))
        }
        val color = GauntletLook.schoolColor(school)
        val core = GauntletLook.schoolCore(school)
        when (anim.anim) {
            Anim.CAST_CHARGE -> {
                palms()
                val s = 0.4f + 0.6f * ArtParam.castCharge(anim.param, a)
                palmGlow(pR, s * 0.7f, color, core); palmGlow(pL, s * 0.7f, color, core)
                val mid = Vector3f(pR).add(pL).mul(0.5f)
                val orb = 0.05f + 0.09f * ArtParam.castCharge(anim.param, a)
                val pulse = 1f + 0.05f * sin(time * 0.25f)
                b.billboard(mid, orb * pulse, ArtVisualGlow.alpha(core, 0.9f * fx))
                b.billboard(mid, orb * 3.2f, ArtVisualGlow.alpha(color, 0.35f * fx))
                if (school == School.WIND) {
                    // drei kleine Böen kreisen ruhig um die geformte Kugel
                    for (i in 0 until 3) {
                        val ang = time * 0.12f + i * 2.094f
                        val r = orb * 2.2f
                        val pt = Vector3f(mid).add(Vector3f(camR).mul(cos(ang) * r)).add(Vector3f(camU).mul(sin(ang) * r))
                        b.billboard(pt, 0.025f, ArtVisualGlow.alpha(core, 0.5f * fx))
                    }
                }
            }
            Anim.CAST_TAP -> {
                palms()
                val s = 1f - seg(a, 1f, ArtTimings.CAST_TAP.toFloat())
                palmGlow(if (mainRight) pR else pL, s, color, core)
            }
            Anim.CAST_RELEASE -> {
                palms()
                val s = 1f - seg(a, 2f, ArtTimings.CAST_RELEASE.toFloat())
                palmGlow(pR, s, color, core); palmGlow(pL, s, color, core)
            }
            Anim.PLACE_AIM -> {
                val tip = point(m, mainArm, GauntletParts.tipPoint(mainRight, slim, Vector3f()), 0f, Vector3f())
                palmGlow(tip, 0.55f, color, core)
            }
            Anim.SHIELD -> {
                palms()
                palmGlow(pR, 0.35f, YANG, YANG_CORE); palmGlow(pL, 0.35f, YANG, YANG_CORE)
            }
            Anim.WHITE_HOLE -> {
                palms()
                val grow = smooth(a / ArtTimings.WHITE_HOLE_CAST)
                val fade = 1f - seg(a, ArtTimings.WHITE_HOLE - 4f, ArtTimings.WHITE_HOLE.toFloat())
                val mid = Vector3f(pR).add(pL).mul(0.5f)
                val k = grow * fade * fx
                val pulse = 1f + 0.06f * sin(a * 0.5f)
                b.billboard(mid, (0.04f + 0.06f * grow) * pulse, ArtVisualGlow.alpha(WHITE, k))
                b.billboard(mid, (0.15f + 0.2f * grow) * pulse, ArtVisualGlow.alpha(0xFFDDF2FF.toInt(), 0.45f * k))
            }
            Anim.ART_CHARGE, Anim.ART_RELEASE -> {
                val charging = anim.anim == Anim.ART_CHARGE
                val s = if (charging) ArtTimings.artCharge(a) else (1f - seg(a, 2f, 9f)) * 0.9f
                val shift = if (slim) (if (mainRight) 0.5f else -0.5f) else 0f
                val grip = Vector3f(ArtPose.GRIP).also { if (!mainRight) it.x = -it.x }
                val p0 = point(m, mainArm, Vector3f(grip).fma(ArtPose.BLADE_FROM, ArtPose.BLADE), shift, Vector3f())
                val p1 = point(m, mainArm, Vector3f(grip).fma(ArtPose.BLADE_TO, ArtPose.BLADE), shift, Vector3f())
                ArtVisualGlow.bladeEnergy(b, p0, p1, s, time, if (charging) a else 99f, 1f, fx)
            }
            else -> {}
        }
        b.submit(queue, VfxLayers.GLOW_ADD)
    }

    // --- Welt ---------------------------------------------------------------------------------------------------------

    private class Shot(val start: Long, val plane: CutPlane, val charge: Float = 0.5f) {
        var struck = false
    }

    private val chargeAge = HashMap<Int, Float>()
    private val shots = HashMap<Int, Shot>()

    private fun renderWorld(consumers: net.minecraft.client.render.VertexConsumerProvider) {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val fx = ArtVisualGlow.fx()
        val camera = client.gameRenderer.camera
        val td = client.renderTickCounter.getTickProgress(true)
        val d = VfxDraw(consumers, camera, td)
        val self = client.player
        val firstPerson = client.options.perspective.isFirstPerson
        for (p in world.players) {
            val s = ArtAnims.get(p.id)
            if (s == null) { chargeAge.remove(p.id); shots.remove(p.id); continue }
            if (p.isInvisible || p.isSpectator) continue
            val a = s.age(td)
            val own = p === self && firstPerson
            when (s.anim) {
                Anim.SHIELD -> if (fx > 0f) shield(d, p, a, td, fx, own)
                Anim.GLIDE -> if (fx > 0f) glide(d, p, a, td, fx)
                Anim.ART_CHARGE -> {
                    chargeAge[p.id] = a
                    shots.remove(p.id)
                    if (p === self) preview(d, p, a, td)
                }
                Anim.ART_RELEASE -> {
                    var shot = shots[p.id]
                    if (shot == null || shot.start != s.start) {
                        // Ladung kommt mit dem Schnitt (param); auch wer die Haltung nicht gesehen hat, sieht die richtige Länge.
                        val c = ArtParam.charge(s.param)
                        shot = Shot(s.start, plane(p, td, ArtTimings.artLength(c), ArtTimings.artHalf(c), ArtParam.horizontal(s.param)), c)
                        shots[p.id] = shot
                    }
                    if (!shot.struck && s.rawAge(td) >= ArtTimings.ART_HIT - 0.3f) {
                        shot.struck = true
                        // Im Treffermoment: Brechung entlang der Schnittebene (Bildpass) und, beim eigenen Schnitt, ein Rucken.
                        dev.henny.hha_arius.client.fx.CutLens.trigger(shot.plane, 0.55f + 0.45f * shot.charge)
                        if (p === self) dev.henny.hha_arius.client.fx.CameraKick.kick(0.55f + 0.5f * shot.charge)
                    }
                    if (fx > 0f) cut(d, shot.plane, a, fx, shot.charge)
                }
                else -> {}
            }
        }
    }

    /** Wie [CutPlane.of], aber mit interpolierter Augenposition und Blickrichtung (ruhig bei hoher Bildrate). */
    fun plane(p: PlayerEntity, td: Float, length: Double, half: Double, horizontal: Boolean): CutPlane {
        val eye = p.getCameraPosVec(td)
        val forward = p.getRotationVec(td).normalize()
        var right = forward.crossProduct(UP)
        if (right.lengthSquared() < 1.0e-4) {
            val yaw = Math.toRadians(p.getYaw(td).toDouble())
            right = Vec3d(-cos(yaw), 0.0, -sin(yaw))
        }
        right = right.normalize()
        val up = right.crossProduct(forward).normalize()
        val span = if (horizontal) right else up
        val normal = forward.crossProduct(span).normalize()
        val origin = eye.add(0.0, -0.35, 0.0).add(forward.multiply(0.6))
        return CutPlane(origin, forward, span, normal, length, half)
    }

    /** Yang-Schild: Lichtscheibe vor der Brust, ruhig, mit weichem Rand. In der eigenen ersten Person zurückhaltender. */
    private fun shield(d: VfxDraw, p: PlayerEntity, a: Float, td: Float, fx: Float, own: Boolean) {
        val k = easeOut(a / 4f) * fx
        val look = p.getRotationVec(td)
        val center = p.getCameraPosVec(td).add(0.0, -0.35, 0.0).add(look.multiply(ArtTimings.SHIELD_DISTANCE))
        val (ax, ay) = basisOf(look)
        val r = ArtTimings.SHIELD_RADIUS * (0.85 + 0.15 * easeOut(a / 4f))
        val veil = if (own) 0.35f else 1f
        d.disc(VfxLayers.GLOW_ADD, center, ax, ay, r * 1.05, withAlpha(YANG_CORE, 0.16f * k * veil))
        d.ring(VfxLayers.GLOW_ADD, center, ax, ay, r - 0.07, r + 0.07, withAlpha(YANG, 0.7f * k), withAlpha(YANG, 0.7f * k), segments = 64, uTile = 0f, uOffset = 0.5f)
        d.disc(VfxLayers.RUNE_ADD, center, ax, ay, r * 0.9, withAlpha(YANG, 0.2f * k * veil), rotation = (a * 0.01).toDouble())
    }

    /** Lichtgleiter: helle Scheibe unter den Füßen. */
    private fun glide(d: VfxDraw, p: PlayerEntity, a: Float, td: Float, fx: Float) {
        val k = easeOut(a / 4f) * fx
        val c = p.getLerpedPos(td).add(0.0, 0.04, 0.0)
        // Seit 1.6.5 größere Lichtscheibe mit doppeltem Ring, Runen und Lichtschweifen, die hinter der Bewegung herziehen.
        val r = 1.25
        d.disc(VfxLayers.GLOW_ADD, c, X, Z, r * 1.3, withAlpha(YANG_CORE, 0.35f * k))
        d.ring(VfxLayers.GLOW_ADD, c, X, Z, r - 0.07, r + 0.07, withAlpha(YANG, 0.75f * k), withAlpha(YANG, 0.75f * k), segments = 64, uTile = 0f, uOffset = 0.5f)
        d.ring(VfxLayers.FLOW_ADD, c, X, Z, r * 0.55, r * 0.7, withAlpha(YANG, 0.5f * k), withAlpha(YANG, 0.2f * k), segments = 48, uTile = 4f, uOffset = a * 0.05f)
        d.disc(VfxLayers.RUNE_ADD, c, X, Z, r * 0.95, withAlpha(YANG, 0.3f * k), rotation = (-a * 0.02).toDouble())
        val v = p.velocity.let { net.minecraft.util.math.Vec3d(it.x, 0.0, it.z) }
        if (v.lengthSquared() > 1e-3) {
            val back = v.normalize().multiply(-1.0)
            val side = net.minecraft.util.math.Vec3d(-back.z, 0.0, back.x)
            for (sg in doubleArrayOf(-1.0, 0.0, 1.0)) {
                val from = c.add(side.multiply(sg * r * 0.8))
                val path = (0..8).map { i -> from.add(back.multiply(i * 0.45)).add(0.0, kotlin.math.sin(a * 0.3 + i * 0.6 + sg) * 0.05, 0.0) }
                d.ribbon(VfxLayers.GLOW_ADD, path, { f -> 0.12 * (1.0 - f) }, { f -> withAlpha(YANG, (1f - f) * 0.6f * k) }, uScale = 1f, uOffset = 0f)
            }
        }
    }

    /**
     * Zielvorschau des Klaren Schnitts (nur eigener Spieler): dünner Rahmen + Mittellinie der Schnittebene; unter der
     * Mindestladung blasser. Der Rahmen beginnt erst 2 Blöcke vor dem Ursprung, damit er nahe der Kamera nicht stört.
     */
    private fun preview(d: VfxDraw, p: PlayerEntity, a: Float, td: Float) {
        val c = ArtTimings.artCharge(a)
        val ready = a >= ArtTimings.ART_CHARGE_MIN
        val k = (if (ready) 0.5f else 0.18f) * clamp01(a / 6f)
        val pl = plane(p, td, ArtTimings.artLength(c), ArtTimings.artHalf(c), p.isSneaking)
        val near = minOf(2.0, pl.length * 0.5)
        val o = pl.origin.add(pl.forward.multiply(near))
        val e = pl.origin.add(pl.forward.multiply(pl.length))
        val s = pl.span.multiply(pl.half)
        val color = withAlpha(ICE, k)
        val w = { _: Float -> 0.018 }
        val col = { _: Float -> color }
        d.ribbon(VfxLayers.GLOW_ADD, listOf(o.add(s), e.add(s)), w, col, uScale = 0f, uOffset = 0.5f)
        d.ribbon(VfxLayers.GLOW_ADD, listOf(o.subtract(s), e.subtract(s)), w, col, uScale = 0f, uOffset = 0.5f)
        d.ribbon(VfxLayers.GLOW_ADD, listOf(e.subtract(s), e.add(s)), w, col, uScale = 0f, uOffset = 0.5f)
        d.ribbon(VfxLayers.GLOW_ADD, listOf(o.subtract(s), o.add(s)), w, { withAlpha(ICE, k * 0.5f) }, uScale = 0f, uOffset = 0.5f)
        d.ribbon(VfxLayers.GLOW_ADD, listOf(o, e), { 0.012 }, { withAlpha(WHITE, k * 0.9f) }, uScale = 0f, uOffset = 0.5f)
    }

    /**
     * Der Klare Schnitt, gewaltig: Bis zum Treffer ([ArtTimings.ART_HIT]) läuft ein breiter Lichtbogen durch die
     * Schnittebene dicht vor dem Spieler, mit zwei nachziehenden Schleiern. Im Treffer schießt eine Lichtklinge über die
     * ganze Länge (Kern schnell breit, dann haarfein), darunter die begrenzte Ebene als weiches Band, am Ende ein Aufglühen;
     * feine Nachlinien zittern zu beiden Seiten der Ebene aus. Größe und Helligkeit wachsen mit der Ladung [charge].
     * Die Brechung des Bildes entlang der Ebene macht [dev.henny.hha_arius.client.fx.CutLens].
     */
    private fun cut(d: VfxDraw, pl: CutPlane, a: Float, fx: Float, charge: Float) {
        val hit = ArtTimings.ART_HIT.toFloat()
        val power = 0.7f + 0.3f * charge
        val fade = 1f - seg(a, hit + 1f, hit + 11f)
        if (fade <= 0f) return
        val top = Math.toRadians(75.0)
        val r0 = 1.2
        val r1 = 2.4 + 0.6 * charge
        // Bogen und zwei Schleier (leicht verzögert, schwächer)
        for (ghost in 0..2) {
            val lag = ghost * 0.6f
            val sweep = easeOut((a - lag) / hit)
            if (sweep <= 0f) continue
            val from = top - 2 * top * sweep
            val tail = (from + 0.9 * (1.0 + ghost)).coerceAtMost(top)
            val k = fade * fx * power * (if (ghost == 0) 0.8f else 0.35f / ghost)
            d.arc(VfxLayers.GLOW_ADD, pl.origin, pl.forward, pl.span, r0 - ghost * 0.1, r1 + ghost * 0.15, from, tail,
                withAlpha(WHITE, k), withAlpha(ICE, k), segments = 28, uTile = 0f, uOffset = 0.5f)
        }
        if (a < hit - 0.5f) return
        val t = a - hit
        val end = pl.origin.add(pl.forward.multiply(pl.length))
        val k = (1f - seg(t, 1f, 8f)) * fx * power
        // Ebene als weiches Band (in der Mitte am hellsten)
        d.band(VfxLayers.GLOW_ADD, pl.origin, end, pl.span, pl.half * (0.9 + 0.2 * easeOut(t / 3f)), withAlpha(ICE, 0.24f * k), withAlpha(ICE, 0.1f * k))
        // Lichtklinge: Kern schießt nach vorn (ein Tick), wird breit und dann haarfein
        val reach = easeOut((t + 0.5f) / 1.5f).toDouble()
        val tipAt = pl.origin.add(pl.forward.multiply(pl.length * reach))
        val core = 0.03 + 0.11 * (1.0 - seg(t, 0f, 5f)) * power
        d.ribbon(VfxLayers.GLOW_ADD, listOf(pl.origin, pl.origin.lerp(tipAt, 0.5), tipAt), { core * (0.6 + 0.4 * it) },
            { withAlpha(WHITE, k * (1f - it * 0.35f)) }, uScale = 0f, uOffset = 0.5f)
        d.ribbon(VfxLayers.GLOW_ADD, listOf(pl.origin, tipAt), { core * 3.2 }, { withAlpha(ICE, 0.35f * k) }, uScale = 0f, uOffset = 0.5f)
        // Aufglühen am Ende der Ebene
        if (t < 6f) d.billboard(VfxLayers.GLOW_ADD, end, (0.6 + 0.8 * charge) * easeOut(t / 2f), withAlpha(ICE, 0.45f * fx * power * (1f - t / 6f)))
        // Nachlinien zu beiden Seiten der Ebene, die auseinanderzittern
        if (t < 8f) {
            val spread = 0.04 + 0.12 * easeOut(t / 8f)
            val kk = 0.4f * fx * power * (1f - t / 8f)
            for (side in intArrayOf(-1, 1)) {
                val off = pl.normal.multiply(side * spread)
                val wob = pl.span.multiply(0.05 * sin(t * 2.3 + side))
                d.ribbon(VfxLayers.GLOW_ADD, listOf(pl.origin.add(off), end.add(off).add(wob)), { 0.012 }, { withAlpha(WHITE, kk * (1f - it * 0.5f)) },
                    uScale = 0f, uOffset = 0.5f)
            }
        }
    }

    /**
     * Schwertspur von Solvane in der dritten Person (jeder Spieler): Klingenansatz und -spitze aus dem Arm des Modells
     * (wie HeldItemFeatureRenderer) jedes Bild mitschreiben. Normale Schläge zeigen die Spur nur bei vollen Treffern, der
     * Klare Schnitt immer (vom Ausholen bis zum Ende des Durchzugs).
     */
    fun solvaneTrail(
        matrices: MatrixStack, queue: OrderedRenderCommandQueue, state: PlayerEntityRenderState, model: PlayerEntityModel,
        anim: ArtAnims.State?, td: Float, slim: Boolean,
    ) {
        val mainRight = state.mainArm != Arm.LEFT
        val mainArm = if (mainRight) model.rightArm else model.leftArm
        if (!mainArm.visible) return
        matrices.push()
        model.rootPart.applyTransform(matrices)
        val m = Matrix4f(matrices.peek().positionMatrix)
        matrices.pop()
        val shift = if (slim) (if (mainRight) 0.5f else -0.5f) else 0f
        val grip = Vector3f(ArtPose.GRIP).also { if (!mainRight) it.x = -it.x }
        val p0 = point(m, mainArm, Vector3f(grip).fma(ArtPose.BLADE_FROM, ArtPose.BLADE), shift, Vector3f())
        val p1 = point(m, mainArm, Vector3f(grip).fma(ArtPose.BLADE_TO, ArtPose.BLADE), shift, Vector3f())
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val art = anim?.anim == Anim.ART_RELEASE
        val style = if (art) SwordTrails.Style.ART else SwordTrails.Style.SOLVANE
        val forced = art && anim!!.age(td) < ArtTimings.ART_HIT + 3f
        SwordTrails.recordWorld(state.id, Vec3d(p0.x + cam.x, p0.y + cam.y, p0.z + cam.z), Vec3d(p1.x + cam.x, p1.y + cam.y, p1.z + cam.z), td, style, forced)
        SwordTrails.renderWorld(queue, state.id, td)
    }
}

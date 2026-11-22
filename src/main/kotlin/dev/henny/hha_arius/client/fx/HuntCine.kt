package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.arena.ArenaDimension
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Filmsprache der Jagd (seit 1.10.0, Crix: „mehr Stress, mehr Film“). Nur Darstellung und Ton am Client:
 * * **Herzschlag und Atem**, je näher Arius ist (Abstand zur Entity aus dem Zustandspaket), schneller und lauter; jeder
 *   Schlag pulsiert Vignette und Farbentzug. Nach Schreckmomenten rast das Herz eine Weile.
 * * **Knapp verfehlt:** Farbsäume, kurze Zeitlupe ([CineGrade.slow]), Zoom-Unschärfe, Luftzug.
 * * **Einschlag:** Kamerarucken ([CameraKick]), Kippen (Dutch Tilt, abklingende Feder), Farbsäume.
 * * **Balken** (Letterbox) in den Szenen der Jagd, **Blenden** (Rettung, Tor), **Grollen** (Zittern), **Brüllen**.
 * * Beim Sprinten über die Route leichte Bewegungsunschärfe am Bildrand.
 * Werte gehen an [HuntLens] (Shader) und das HUD; das Kippen/Zittern liest der Kamera-Mixin über [roll]/[jitter].
 */
object HuntCine {

    var active = false
        private set
    var ariusId = -1
        private set
    private var stateAge = 0

    private val rnd = Random()
    private var ticks = 0L

    // Herz
    private var beatTimer = 0
    private var beatPulse = 0f
    private var rush = 0
    private var breathTimer = 0
    var proximity = 0f
        private set

    // Effekte
    private var chroma = 0f
    private var blurKick = 0f
    private var vignetteKick = 0f
    private var letterbox = 0
    private var letterboxK = 0f
    private var rollVel = 0f
    private var rollAng = 0f
    private var tremor = 0f
    private var tremorLeft = 0
    private var warpLeft = 0

    private var fadeAge = -1
    private var fadeIn = 1f
    private var fadeOut = 1f
    private var fadeLife = 1
    private var fadeColor = 0xFFF2F6FA.toInt()

    private var portalLeft = 0
    private var portalTotal = 1

    private var lastSlow = -100L

    private var ropeProgress = 0f
    private var ropeLeft = 0

    fun reset() {
        active = false
        ariusId = -1
        rush = 0
        chroma = 0f
        letterbox = 0
        letterboxK = 0f
        rollAng = 0f
        rollVel = 0f
        tremor = 0f
        fadeAge = -1
        portalLeft = 0
        ropeLeft = 0
    }

    /** Fortschrittsbalken beim Kappen der Seile. */
    fun ropeProgress(k: Float, life: Int) {
        ropeProgress = k.coerceIn(0f, 1f)
        ropeLeft = life
    }

    fun state(on: Boolean, id: Int) {
        active = on
        ariusId = id
        stateAge = 0
    }

    private fun inEirholm(): Boolean = MinecraftClient.getInstance().world?.registryKey == ArenaDimension.WORLD

    private fun play(event: SoundEvent, volume: Float, pitch: Float) {
        MinecraftClient.getInstance().soundManager.play(PositionedSoundInstance.ambient(event, pitch, volume))
    }

    fun playAt(event: SoundEvent, at: Vec3d, volume: Float, pitch: Float) {
        val mc = MinecraftClient.getInstance()
        mc.world?.playSoundClient(at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch, false)
    }

    // --- Auslöser ---------------------------------------------------------------------------------------------------

    fun nearMiss(at: Vec3d) {
        chroma = maxOf(chroma, 1f)
        blurKick = maxOf(blurKick, 0.8f)
        vignetteKick = maxOf(vignetteKick, 0.35f)
        rollVel += (if (rnd.nextBoolean()) 1f else -1f) * 1.6f
        rush = maxOf(rush, 80)
        val now = ticks
        if (now - lastSlow > 50) {
            lastSlow = now
            CineGrade.slow(12, 0.55f)
        }
        playAt(SoundEvents.ENTITY_BREEZE_WHIRL, at, 1.4f, 0.55f + rnd.nextFloat() * 0.2f)
        play(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 0.6f, 0.5f)
    }

    fun impact(strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        CameraKick.kick(2.5f + 4f * s, (rnd.nextFloat() - 0.5f) * 2f)
        rollVel += (if (rnd.nextBoolean()) 1f else -1f) * (2f + 5f * s)
        chroma = maxOf(chroma, 0.5f + 0.5f * s)
        vignetteKick = maxOf(vignetteKick, 0.4f * s + 0.15f)
        rush = maxOf(rush, 60)
    }

    fun letterbox(ticks: Int) {
        letterbox = maxOf(letterbox, ticks)
    }

    fun fade(inTicks: Float, outTicks: Float, life: Int, color: Int) {
        fadeAge = 0
        fadeIn = inTicks.coerceAtLeast(1f)
        fadeOut = outTicks.coerceAtLeast(1f)
        fadeLife = life.coerceAtLeast(2)
        fadeColor = if (color == 0) 0xFFF2F6FA.toInt() else color
    }

    fun rumble(strength: Float, life: Int) {
        tremor = maxOf(tremor, strength.coerceIn(0f, 1.5f))
        tremorLeft = maxOf(tremorLeft, life)
        play(SoundEvents.AMBIENT_BASALT_DELTAS_ADDITIONS.value(), 0.8f * strength, 0.5f)
    }

    fun roar(strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        CameraKick.kick(3f + 5f * s, (rnd.nextFloat() - 0.5f) * 2f)
        rollVel += (if (rnd.nextBoolean()) 1f else -1f) * 4f * s
        chroma = maxOf(chroma, 0.9f * s)
        blurKick = maxOf(blurKick, 0.6f * s)
        vignetteKick = maxOf(vignetteKick, 0.6f * s)
        letterbox(30 + (20 * s).toInt())
        tremor = maxOf(tremor, 0.6f * s)
        tremorLeft = maxOf(tremorLeft, 30)
        warpLeft = maxOf(warpLeft, 24)
        rush = maxOf(rush, 160)
    }

    /** Schreck (Auge, Atem durch den Riss): Herz rast, kurzer Ruck. */
    fun scare(strength: Float) {
        rush = maxOf(rush, 140)
        vignetteKick = maxOf(vignetteKick, 0.5f * strength)
        chroma = maxOf(chroma, 0.35f * strength)
        play(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.6f)
    }

    fun portalCharge(ticks: Int) {
        portalLeft = ticks
        portalTotal = ticks.coerceAtLeast(1)
    }

    // --- Tick -----------------------------------------------------------------------------------------------------

    fun tick() {
        ticks++
        val mc = MinecraftClient.getInstance()
        val player = mc.player
        val world = mc.world
        if (active && ++stateAge > 80) active = false // Zustand wird alle 20 Ticks erneuert
        if (!inEirholm()) active = false
        chroma *= 0.86f
        blurKick *= 0.85f
        vignetteKick *= 0.9f
        beatPulse *= 0.8f
        if (letterbox > 0) letterbox--
        letterboxK += ((if (letterbox > 0) 1f else 0f) - letterboxK) * 0.18f
        // Kippen: gedämpfte Feder
        rollVel += -rollAng * 0.25f
        rollVel *= 0.78f
        rollAng += rollVel * 0.5f
        if (tremorLeft > 0) tremorLeft-- else tremor *= 0.85f
        if (warpLeft > 0) warpLeft--
        if (fadeAge >= 0 && ++fadeAge > fadeLife) fadeAge = -1
        if (portalLeft > 0) portalLeft--
        if (ropeLeft > 0) ropeLeft--
        if (rush > 0) rush--
        if (player == null || world == null) return
        // Nähe zu Arius
        val a = if (active && ariusId >= 0) world.getEntityById(ariusId) else null
        proximity = if (a != null) ((30.0 - a.entityPos.distanceTo(player.entityPos)) / 24.0).coerceIn(0.0, 1.0).toFloat() else 0f
        val heart = maxOf(proximity, if (rush > 0) 0.75f else 0f)
        if (active && heart > 0.05f && !CinePlayer.active) {
            if (--beatTimer <= 0) {
                beatTimer = (26 - 16 * heart).toInt().coerceAtLeast(8)
                beatPulse = 0.4f + 0.6f * heart
                play(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 0.25f + 0.75f * heart, 0.85f + 0.25f * heart)
            }
            if (heart > 0.35f && --breathTimer <= 0) {
                breathTimer = (60 - 30 * heart).toInt()
                play(SoundEvents.ENTITY_PLAYER_BREATH, 0.35f + 0.5f * heart, 0.75f + rnd.nextFloat() * 0.15f)
            }
        }
    }

    /** Kamera-Kippen in Grad (Dutch Tilt + Hängebrücke). */
    @JvmStatic
    fun roll(tickDelta: Float): Float {
        if (!CameraKick.enabled() || !inEirholm() || CounterStance.steadyView()) return 0f
        val t = ticks + tickDelta
        val shake = if (tremor > 0.01f) (sin(t * 2.7f) * 0.7f + sin(t * 4.1f + 1.3f) * 0.4f) * tremor else 0f
        return rollAng + shake + RopeBridges.localRoll(tickDelta)
    }

    /** Zittern (Gieren, Nicken) in Grad. */
    @JvmStatic
    fun jitter(tickDelta: Float): FloatArray? {
        if (tremor < 0.01f || !CameraKick.enabled() || !inEirholm() || CounterStance.steadyView()) return null
        val t = ticks + tickDelta
        return floatArrayOf(sin(t * 3.3f) * 0.35f * tremor, sin(t * 2.9f + 0.7f) * 0.45f * tremor)
    }

    /** Linsenwerte für diesen Frame setzen (vor dem Zeichnen der Linse). */
    fun updateLens() {
        val mc = MinecraftClient.getInstance()
        val p = mc.player
        if (!inEirholm() || p == null) {
            HuntLens.chroma = 0f; HuntLens.blur = 0f; HuntLens.desat = 0f; HuntLens.vignette = 0f; HuntLens.tintA = 0f; HuntLens.warp = 0f
            return
        }
        val sprint = if (active && p.isSprinting) (p.velocity.horizontalLength() * 2.2).toFloat().coerceIn(0f, 0.35f) else 0f
        val fog = HuntScares.breathAt()
        HuntLens.chroma = (chroma * 1.2f + fog * 0.2f).coerceIn(0f, 1.5f)
        HuntLens.blur = (blurKick + sprint).coerceIn(0f, 1.2f)
        HuntLens.desat = (beatPulse * 0.22f * maxOf(proximity, if (rush > 0) 0.6f else 0f) + proximity * 0.12f + fog * 0.35f).coerceIn(0f, 0.8f)
        HuntLens.vignette = (beatPulse * 0.25f * maxOf(proximity, 0.4f) * (if (active) 1f else 0f) + vignetteKick + proximity * 0.18f + fog * 0.3f).coerceIn(0f, 0.85f)
        HuntLens.tint = 0xDCEBFA
        HuntLens.tintA = (fog * 0.45f).coerceIn(0f, 0.6f)
        HuntLens.warp = if (warpLeft > 0) warpLeft / 24f else fog * 0.6f
    }

    // --- HUD ----------------------------------------------------------------------------------------------------------

    /**
     * Filmbalken. Seit 1.11.0 schmaler und **unter** dem HUD gezeichnet (eigene Ebene vor den Vanilla-Ebenen, siehe
     * [HuntClient]): Bossleiste, Herzen und Hotbar liegen darüber und bleiben immer lesbar.
     */
    fun renderBars(ctx: DrawContext) {
        if (!inEirholm() || letterboxK <= 0.01f) return
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        val bar = (h * 0.075f * letterboxK).toInt()
        ctx.fill(0, 0, w, bar, 0xFF000000.toInt())
        ctx.fill(0, h - bar, w, h, 0xFF000000.toInt())
    }

    fun renderHud(ctx: DrawContext, tickDelta: Float) {
        if (!inEirholm()) return
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        // Seile kappen: Balken unter dem Fadenkreuz
        if (ropeLeft > 0) {
            val bw = 64
            val x = (w - bw) / 2
            val y = h / 2 + 14
            ctx.fill(x - 1, y - 1, x + bw + 1, y + 4, 0xC0000000.toInt())
            ctx.fill(x, y, x + (bw * ropeProgress).toInt(), y + 3, 0xFFFFD27A.toInt())
        }
        // Tor lädt: frostiger Wirbel vom Rand
        if (portalLeft > 0) {
            val k = (1f - portalLeft / portalTotal.toFloat()).coerceIn(0f, 1f)
            val steps = 12
            val depth = minOf(w, h) * 0.45f * k
            for (i in 0 until steps) {
                val a = 0.45f * k * (1f - i / steps.toFloat()).let { it * it }
                val o = (depth * i / steps).toInt()
                val o2 = (depth * (i + 1) / steps).toInt()
                val c = withAlpha(0xFF8FD4FF.toInt(), a)
                ctx.fill(0, o, w, o2, c); ctx.fill(0, h - o2, w, h - o, c)
                ctx.fill(o, o2, o2, h - o2, c); ctx.fill(w - o2, o2, w - o, h - o2, c)
            }
        }
        // Blende
        if (fadeAge >= 0) {
            val t = fadeAge + tickDelta
            val a = when {
                t < fadeIn -> t / fadeIn
                t > fadeLife - fadeOut -> ((fadeLife - t) / fadeOut).coerceIn(0f, 1f)
                else -> 1f
            }.coerceIn(0f, 1f)
            if (a > 0.003f) ctx.fill(0, 0, w, h, withAlpha(fadeColor, a))
        }
    }

}

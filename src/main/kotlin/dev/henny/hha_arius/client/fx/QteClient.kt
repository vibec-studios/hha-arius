package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.boss.Qte
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.block.Blocks
import net.minecraft.client.option.KeyBinding
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.math.Vec3d

/**
 * Anzeige und Eingabe der Quick-Time-Events ([Qte]): große Taste in der Bildmitte unten, Aufforderung darüber, ein
 * ablaufender Balken; beim Hämmern füllt jeder Druck den goldenen Balken. Erfolg = goldener Blitz, Fehlschlag = rot.
 * Die Tasten werden über ihren Zustand gelesen (Flanken), damit es auch während einer Szene funktioniert.
 */
object QteClient {

    private var id = -1
    private var kind = Qte.Kind.ATTACK
    private var total = 1
    private var left = 0
    private var need = 1
    private var presses = 0
    private var text = ""
    private var wasDown = false
    private var result = 0      // 0 offen, 1 Erfolg, -1 Fehlschlag
    private var resultAge = 0

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(Qte.QteS2C.ID) { p, _ ->
            id = p.id
            kind = Qte.Kind.entries.getOrElse(p.kind) { Qte.Kind.ATTACK }
            total = p.ticks.coerceAtLeast(1)
            left = total
            need = p.count.coerceAtLeast(1)
            presses = 0
            text = p.text
            result = 0
            resultAge = 0
            wasDown = key()?.let { down(it) } == true
        }
        ClientTickEvents.END_CLIENT_TICK.register { tick(it) }
        RideClient.init()
    }

    private fun key(): KeyBinding? {
        val o = MinecraftClient.getInstance().options ?: return null
        return when (kind) {
            Qte.Kind.ATTACK, Qte.Kind.MASH -> o.attackKey
            Qte.Kind.JUMP -> o.jumpKey
            Qte.Kind.USE -> o.useKey
            Qte.Kind.SNEAK -> o.sneakKey
        }
    }

    /** Das Eis wächst gerade über die Hände (Prompt `ride_ice`): Frost am Bildrand und Eissplitter an den Händen. */
    private val frosting: Boolean get() = id >= 0 && result == 0 && text.endsWith("ride_ice")

    private fun frostTick(client: MinecraftClient) {
        val p = client.player ?: return
        val world = client.world ?: return
        val look = p.rotationVector
        val side = Vec3d(-look.z, 0.0, look.x).let { if (it.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
        val hands = p.eyePos.add(look.multiply(0.9)).add(0.0, -0.25, 0.0)
        val r = world.random
        repeat(3) {
            val q = hands.add(side.multiply((r.nextDouble() - 0.5) * 0.9)).add(0.0, (r.nextDouble() - 0.5) * 0.3, 0.0)
            world.addParticleClient(BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultState), q.x, q.y, q.z, 0.0, 0.02, 0.0)
        }
    }

    private fun tick(client: MinecraftClient) {
        if (frosting) frostTick(client)
        if (result != 0) {
            if (++resultAge > 24) { result = 0; id = -1 }
            return
        }
        if (id < 0) return
        val k = key() ?: return
        val down = down(k)
        if (down && !wasDown) {
            presses++
            client.player?.playSound(SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.6f, 1.2f + 0.08f * presses)
            if (presses >= need) finish(true)
        }
        wasDown = down
        if (result == 0 && --left <= 0) finish(false)
    }

    private fun finish(ok: Boolean) {
        if (ClientPlayNetworking.canSend(Qte.QteC2S.ID)) ClientPlayNetworking.send(Qte.QteC2S(id, ok))
        result = if (ok) 1 else -1
        resultAge = 0
        MinecraftClient.getInstance().player?.playSound(if (ok) SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE else SoundEvents.BLOCK_GLASS_BREAK, 1f, if (ok) 1.4f else 0.8f)
    }

    /** Kreis aus kleinen Quadraten (für den sich schließenden Ring um die Taste). */
    private fun ring(ctx: DrawContext, cx: Int, cy: Int, r: Float, thick: Int, color: Int) {
        val n = (r * 2.2f).toInt().coerceIn(24, 120)
        for (i in 0 until n) {
            val a = i / n.toFloat() * 6.2832f
            val x = cx + (kotlin.math.cos(a) * r).toInt()
            val y = cy + (kotlin.math.sin(a) * r).toInt()
            ctx.fill(x - thick / 2, y - thick / 2, x + (thick + 1) / 2, y + (thick + 1) / 2, color)
        }
    }

    /** Taste gedrückt? Über den Tastenzustand und zusätzlich roh über GLFW (Szenen sperren die normale Eingabe). */
    private fun down(k: KeyBinding): Boolean {
        if (k.isPressed) return true
        val c = MinecraftClient.getInstance()
        val key = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.getBoundKeyOf(k)
        if (c.currentScreen != null) return false
        return when (key.category) {
            net.minecraft.client.util.InputUtil.Type.MOUSE -> org.lwjgl.glfw.GLFW.glfwGetMouseButton(c.window.handle, key.code) == org.lwjgl.glfw.GLFW.GLFW_PRESS
            net.minecraft.client.util.InputUtil.Type.KEYSYM -> key.code >= 0 && net.minecraft.client.util.InputUtil.isKeyPressed(c.window, key.code)
            else -> false
        }
    }

    /** Wird aus [dev.henny.hha_arius.mixin.client.QteHudMixin] gerufen – auch während HHA-Szenen. */
    @JvmStatic
    fun render(ctx: DrawContext, td: Float) {
        RideClient.renderHud(ctx)
        if (id < 0 && result == 0) return
        // Zeitlupen-Schleier: dunkle, kalte Ränder, solange ein Prompt offen ist (wie die Zeitlupe in Action-Szenen).
        if (result == 0) {
            val w0 = ctx.scaledWindowWidth
            val h0 = ctx.scaledWindowHeight
            val steps = 14
            for (i in 0 until steps) {
                val a = 0.38f * (1f - i / steps.toFloat()).let { it * it }
                val o = (minOf(w0, h0) * 0.28f * i / steps).toInt()
                val o2 = (minOf(w0, h0) * 0.28f * (i + 1) / steps).toInt()
                val c = withAlpha(0xFF050A18.toInt(), a)
                ctx.fill(0, o, w0, o2, c); ctx.fill(0, h0 - o2, w0, h0 - o, c)
                ctx.fill(o, o2, o2, h0 - o2, c); ctx.fill(w0 - o2, o2, w0 - o, h0 - o2, c)
            }
        }
        if (frosting) {
            // Das Eis kriecht von unten ins Bild, je weniger Schläge, desto höher.
            val w1 = ctx.scaledWindowWidth
            val h1 = ctx.scaledWindowHeight
            val grow = 1f - (presses / need.toFloat()).coerceIn(0f, 1f)
            val top = (h1 * (0.62f + 0.1f * (1f - grow))).toInt()
            ctx.fillGradient(0, top, w1, h1, withAlpha(0xFF9FD8FF.toInt(), 0f), withAlpha(0xFFBFE8FF.toInt(), 0.55f * grow + 0.1f))
        }
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val w = ctx.scaledWindowWidth
        val h = ctx.scaledWindowHeight
        val cx = w / 2
        // Seit 1.11.0 im oberen Bilddrittel: Unten lag die Taste hinter der Fähigkeitenleiste und war nicht lesbar.
        val cy = (h * 0.3f).toInt().coerceAtLeast(70)
        val fade = if (result != 0) (1f - resultAge / 24f).coerceIn(0f, 1f) else 1f
        // Taste
        val keyText = key()?.boundKeyLocalizedText?.copy() ?: Text.literal("?")
        val label = if (kind == Qte.Kind.MASH) Text.translatable("hha_arius.qte.mash", keyText) else keyText
        val m = ctx.matrices
        m.pushMatrix()
        m.translate(cx.toFloat(), (cy - 6).toFloat())
        val pulse = 2.6f + if (result == 0) 0.25f * kotlin.math.sin((total - left + td) * 0.6f) else 0f
        m.scale(pulse, pulse)
        val lw = tr.getWidth(label)
        val col = when (result) {
            1 -> withAlpha(0xFFFFD98A.toInt(), fade)
            -1 -> withAlpha(0xFFE07A6E.toInt(), fade)
            else -> 0xFFFFFFFF.toInt()
        }
        ctx.fill(-lw / 2 - 4, -3, lw / 2 + 4, 11, withAlpha(0xFF0B1020.toInt(), 0.75f * fade))
        ctx.drawText(tr, label, -lw / 2, 0, col, true)
        m.popMatrix()
        // Aufforderung
        if (text.isNotEmpty()) {
            val t = Text.translatable(text)
            ctx.drawText(tr, t, cx - tr.getWidth(t) / 2, cy - 34, withAlpha(0xFFE8F2FF.toInt(), fade), true)
        }
        // Was genau zu tun ist (seit 1.6.5, Crix: „ich hab keinen Plan, was smash sein soll“): Taste und Handlung im Klartext.
        val how = when (kind) {
            Qte.Kind.JUMP -> Text.translatable("hha_arius.qte.how.jump", keyText)
            Qte.Kind.USE -> Text.translatable("hha_arius.qte.how.use", keyText)
            Qte.Kind.SNEAK -> Text.translatable("hha_arius.qte.how.sneak", keyText)
            Qte.Kind.ATTACK -> Text.translatable("hha_arius.qte.how.attack", keyText)
            Qte.Kind.MASH -> Text.translatable("hha_arius.qte.how.mash", keyText, presses.coerceAtMost(need), need)
        }
        ctx.drawText(tr, how, cx - tr.getWidth(how) / 2, cy + 36, withAlpha(0xFFFFD98A.toInt(), fade), true)
        // Sich schließender Kreis um die Taste (Erfolg: Kreis springt golden auf)
        val tRing = if (result == 0) (left - td).coerceAtLeast(0f) / total else 0f
        val baseR = 22f
        if (result == 0) {
            ring(ctx, cx, cy - 2, baseR + 26f * tRing, 2, withAlpha(0xFFFFFFFF.toInt(), 0.9f))
            ring(ctx, cx, cy - 2, baseR, 1, withAlpha(0xFF9FE6FF.toInt(), 0.7f))
        } else if (result == 1) {
            ring(ctx, cx, cy - 2, baseR + resultAge * 2.5f, 3, withAlpha(0xFFFFD98A.toInt(), fade))
        }
        // Zeitbalken und Fortschritt
        val bw = 120
        val x0 = cx - bw / 2
        val y0 = cy + 26
        ctx.fill(x0 - 1, y0 - 1, x0 + bw + 1, y0 + 4, withAlpha(0xFF000000.toInt(), 0.6f * fade))
        val time = if (result == 0) (left - td).coerceAtLeast(0f) / total else 0f
        ctx.fill(x0, y0, x0 + (bw * time).toInt(), y0 + 3, withAlpha(0xFF9FE6FF.toInt(), fade))
        if (kind == Qte.Kind.MASH) {
            val prog = (presses / need.toFloat()).coerceIn(0f, 1f)
            ctx.fill(x0, y0 + 5, x0 + (bw * prog).toInt(), y0 + 8, withAlpha(0xFFFFD98A.toInt(), fade))
        }
    }
}

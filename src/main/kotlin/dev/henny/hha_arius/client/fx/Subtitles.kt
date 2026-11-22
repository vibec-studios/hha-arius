package dev.henny.hha_arius.client.fx

import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.net.LineS2C
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Untertitel und Szenentexte (docs/STIMME.md). Zu jeder Arius-Zeile wird, falls vorhanden, die Stimme
 * `hha_arius:voice.<de|en>.<id>` gespielt.
 *
 * Seit 1.8.0 (Crix: „die Texte kann man im Kampf nicht mal lesen“):
 * * In Szenen waren sie unsichtbar (HHA blendet dort das HUD aus); jetzt zeichnet [renderAlways] sie auch dann, groß
 *   im unteren Kinobalken.
 * * Jede Zeile bleibt so lange, wie man zum Lesen braucht (mindestens die Serverzeit), und wird wie eine
 *   Schreibmaschine aufgebaut. Eine neue Zeile drängt die laufende nicht mehr weg, sondern wartet (höchstens drei).
 * * Im Kampf steht der Text über Frostbalken und Aktionsleiste, mit dunklem Band und Sprechername.
 */
object Subtitles {

    private class Line(val speaker: Int, val text: Text, val ticks: Int, val forge: Boolean) {
        var age = 0
    }

    private val queue = ArrayDeque<Line>()
    private var voice: SoundInstance? = null
    private var pendingVoice: String? = null

    /** Lesezeit in Ticks: etwa 14 Zeichen je Sekunde, dazu eine Sekunde zum Ankommen. */
    private fun readTicks(text: Text): Int = (24 + text.string.length * 1.45).toInt()

    fun handle(p: LineS2C) {
        val text = Text.translatable(p.key)
        val forge = p.key == "hha_arius.scene.forge_ice"
        if (forge) {
            queue.clear()
            pendingVoice = null
        }
        val line = Line(p.speaker, text, maxOf(p.ticks, readTicks(text)).coerceIn(30, 260), forge)
        queue.addLast(line)
        while (queue.size > 3) queue.removeFirst()
        if (p.speaker == 1) {
            if (queue.size == 1) playVoice(p.voice) else pendingVoice = p.voice
        }
    }

    private fun playVoice(id: String) {
        if (id.isEmpty()) return
        val client = MinecraftClient.getInstance()
        val lang = if (client.languageManager.language.startsWith("de")) "de" else "en"
        val sound = HhaArius.id("voice.$lang.$id")
        if (client.soundManager.get(sound) == null) return
        voice?.let { client.soundManager.stop(it) }
        val instance = PositionedSoundInstance.ui(SoundEvent.of(sound), 1f, 1f)
        voice = instance
        client.soundManager.play(instance)
    }

    fun tick() {
        val first = queue.firstOrNull() ?: return
        // Liegt viel an, geht es etwas schneller weiter (ab der Hälfte doppelt), damit nichts hinterherhinkt.
        first.age += if (queue.size > 1 && first.age > first.ticks / 2) 2 else 1
        if (first.age > first.ticks) {
            queue.removeFirst()
            val next = queue.firstOrNull()
            if (next != null && next.speaker == 1) pendingVoice?.let { playVoice(it) }
            pendingVoice = null
        }
    }

    fun clear() {
        queue.clear()
        pendingVoice = null
        voice?.let { MinecraftClient.getInstance().soundManager.stop(it) }
        voice = null
    }

    /** Aus dem HUD-Mixin (läuft auch in Szenen): in Szenen hier zeichnen, sonst übernimmt [render]. */
    @JvmStatic
    fun renderAlways(context: DrawContext) {
        if (CinePlayer.active) draw(context, true)
    }

    fun render(context: DrawContext) {
        if (!CinePlayer.active) draw(context, false)
    }

    private fun draw(context: DrawContext, cinema: Boolean) {
        val line = queue.firstOrNull() ?: return
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val w = context.scaledWindowWidth
        val h = context.scaledWindowHeight
        val alpha = ((minOf(line.age + 1, line.ticks - line.age).toFloat() / 6f).coerceIn(0f, 1f) * 255).toInt()
        if (alpha <= 8) return
        val scale = if (cinema) 1.25f else 1.1f
        val maxWidth = ((w * 0.62f) / scale).toInt().coerceAtMost(330)
        // Schreibmaschine: 3 Zeichen je Tick.
        val full = line.text.string
        val shown = minOf(full.length, (line.age * 3) + 1)
        var body: Text = Text.literal(full.substring(0, shown))
        if (line.speaker == 0) body = body.copy().formatted(Formatting.ITALIC)
        val wrapped = font.wrapLines(body, maxWidth)
        val rows = maxOf(1, font.wrapLines(Text.literal(full), maxWidth).size)
        val blockH = (rows * 10 + (if (line.speaker == 1) 12 else 0)) * scale
        val bottom = if (cinema) h - (if (line.forge) h * 0.11f + 5f else 12f) else h - 126f
        val top = bottom - blockH
        val a = alpha shl 24
        val m = context.matrices
        m.pushMatrix()
        m.translate(w / 2f, top)
        m.scale(scale, scale)
        val half = maxWidth / 2
        if (!cinema) {
            context.fill(-half - 8, -4, half + 8, (blockH / scale).toInt() + 3, ((alpha * 0.55f).toInt() shl 24) or 0x05080F)
            context.fill(-half - 8, -4, -half - 6, (blockH / scale).toInt() + 3, a or (if (line.speaker == 1) 0x9FE6FF else 0x8C887C))
        }
        var y = 0
        if (line.speaker == 1) {
            val name = Text.translatable("hha_arius.speaker.arius").formatted(Formatting.BOLD)
            context.drawTextWithShadow(font, name, -font.getWidth(name) / 2, y, a or 0x9FE6FF)
            y += 12
        }
        val color = if (line.speaker == 0) 0xC8C4B8 else 0xF4F6FA
        for (row in wrapped) {
            context.drawTextWithShadow(font, row, -font.getWidth(row) / 2, y, a or color)
            y += 10
        }
        m.popMatrix()
    }
}

package dev.henny.hha_arius.client.fx

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
 * Untertitel und Szenentexte (docs/STIMME.md): unten mittig über der Hotbar, immer sichtbar, auch ohne Ton. Zu jeder
 * Arius-Zeile wird, falls vorhanden, die Stimme `hha_arius:voice.<de|en>.<id>` gespielt.
 */
object Subtitles {

    private class Line(val speaker: Int, val text: Text, val ticks: Int) {
        var age = 0
    }

    private val queue = ArrayDeque<Line>()
    private var voice: SoundInstance? = null

    fun handle(p: LineS2C) {
        val line = Line(p.speaker, Text.translatable(p.key), p.ticks.coerceIn(20, 200))
        if (p.speaker == 1) {
            // Eine neue Arius-Zeile ersetzt eine laufende (er wird nie überlagert).
            queue.removeIf { it.speaker == 1 }
            playVoice(p.voice)
        }
        queue.addLast(line)
        while (queue.size > 3) queue.removeFirst()
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
        if (++first.age > first.ticks) queue.removeFirst()
    }

    fun clear() {
        queue.clear()
        voice?.let { MinecraftClient.getInstance().soundManager.stop(it) }
        voice = null
    }

    fun render(context: DrawContext) {
        val line = queue.firstOrNull() ?: return
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val w = context.scaledWindowWidth
        val h = context.scaledWindowHeight
        val alpha = ((minOf(line.age, line.ticks - line.age).toFloat() / 6f).coerceIn(0f, 1f) * 255).toInt()
        if (alpha <= 8) return
        val maxWidth = (w * 0.6).toInt().coerceAtMost(360)
        val body = if (line.speaker == 0) line.text.copy().formatted(Formatting.ITALIC) else line.text
        val wrapped = font.wrapLines(body, maxWidth)
        var y = h - 74 - wrapped.size * 10
        val a = alpha shl 24
        if (line.speaker == 1) {
            val name = Text.translatable("hha_arius.speaker.arius")
            context.drawTextWithShadow(font, name, (w - font.getWidth(name)) / 2, y - 11, a or 0x9FE6FF)
        }
        val color = if (line.speaker == 0) 0xC8C4B8 else 0xF4F6FA
        val bgTop = y - (if (line.speaker == 1) 14 else 3)
        context.fill((w - maxWidth) / 2 - 6, bgTop, (w + maxWidth) / 2 + 6, y + wrapped.size * 10 + 3, ((alpha * 0.45f).toInt() shl 24))
        for (row in wrapped) {
            context.drawTextWithShadow(font, row, (w - font.getWidth(row)) / 2, y, a or color)
            y += 10
        }
    }
}

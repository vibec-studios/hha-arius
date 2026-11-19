package dev.henny.hha_arius.gear.client.art

import dev.henny.hha.client.gui.Ui
import dev.henny.hha_arius.client.fx.ArtColors
import dev.henny.hha_arius.gear.art.ArtAction
import dev.henny.hha_arius.gear.art.School
import dev.henny.hha_arius.gear.art.Spell
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.input.KeyInput
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.text.Text
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Zauberrad in zwei Stufen, nie mehr als fünf Einträge auf einmal:
 * 1. Drei Schulen (Wind oben, Eis rechts unten, Yang links unten).
 * 2. Maus weiter nach außen ziehen oder Linksklick auf eine Schule → deren Techniken als eigener Kreis.
 *
 * Wählen: Technik-Taste loslassen (oder Linksklick auf eine Technik). Loslassen auf einer Schule wählt deren zuletzt
 * benutzte Technik. Abbruch: Loslassen in der Mitte, Esc oder Rechtsklick in Stufe 1 (Rechtsklick in Stufe 2 = zurück).
 * Kurz tippen ohne Mausbewegung = zur vorigen Technik wechseln. Wer mit dem Rad etwas im Nahkampfmodus wählt, wechselt
 * in den Magiemodus. Das Rad pausiert nichts (auch im Einzelspieler nicht); nur der eigene Spieler steht so lange.
 */
class SpellWheelScreen(private val key: KeyBinding) : Screen(Text.translatable("hha_arius.wheel.title")) {

    private val openedAt = System.currentTimeMillis()
    private var school: School? = null
    private var hoverSchool: School? = null
    private var hoverSpell: Spell? = null
    private var hoverBuff: Spell? = null
    private var moved = false
    private var startX = -1.0
    private var startY = -1.0
    private var done = false

    override fun shouldPause(): Boolean = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        context.fill(0, 0, width, height, 0x38000000)
    }

    override fun tick() {
        // Sicherung: wurde die Taste losgelassen, ohne dass das Ereignis ankam, gilt das als Loslassen.
        val bound = InputUtil.fromTranslationKey(key.boundKeyTranslationKey)
        if (bound.category == InputUtil.Type.KEYSYM && !InputUtil.isKeyPressed(MinecraftClient.getInstance().window, bound.code)) commit()
    }

    override fun keyReleased(input: KeyInput): Boolean {
        if (key.matchesKey(input)) {
            commit()
            return true
        }
        return super.keyReleased(input)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (key.matchesMouse(click)) {
            commit()
            return true
        }
        return super.mouseReleased(click)
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        when (click.button()) {
            0 -> {
                hoverBuff?.let { choose(it); return true }
                val spell = hoverSpell
                if (school != null && spell != null) {
                    choose(spell)
                } else if (school == null && hoverSchool != null) {
                    school = hoverSchool
                    moved = true
                }
                return true
            }
            1 -> {
                if (school != null) school = null else close()
                return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    private fun commit() {
        if (done) return
        val spell = hoverSpell
        val sch = hoverSchool
        when {
            hoverBuff != null -> choose(hoverBuff!!)
            !moved && System.currentTimeMillis() - openedAt < 250 -> choose(ArtClient.previous)
            school != null && spell != null -> choose(spell)
            school == null && sch != null -> choose(Spell.entries[ArtClient.lastOfSchool[sch.ordinal]])
            else -> close()
        }
    }

    private fun choose(spell: Spell) {
        if (done) return
        done = true
        ArtClient.send(ArtAction.SELECT, spell.ordinal)
        close()
    }

    // ------------------------------------------------------------------------------------------------- Zeichnen

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        super.render(context, mouseX, mouseY, deltaTicks)
        val cx = width / 2
        val cy = height / 2
        if (startX < 0) {
            startX = mouseX.toDouble()
            startY = mouseY.toDouble()
        }
        if (hypot(mouseX - startX, mouseY - startY) > 6) moved = true
        val dx = (mouseX - cx).toDouble()
        val dy = (mouseY - cy).toDouble()
        val dist = hypot(dx, dy)
        val angle = atan2(dy, dx)
        val tr = textRenderer

        buffBar(context, cx, cy - OUTER - 30, mouseX, mouseY)
        val current = school
        if (hoverBuff != null) {
            hoverSchool = null
            hoverSpell = null
        }
        if (current == null) {
            hoverSpell = null
            hoverSchool = if (hoverBuff == null && dist > INNER - 4 && dist < OUTER + 24) School.entries[sectorOf(angle, 3, SCHOOL_START)] else null
            if (hoverSchool != null && dist > OUTER + 10) school = hoverSchool
            drawRing(context, cx, cy, 3, SCHOOL_START) { i -> ArtColors.of(i) to (hoverSchool?.ordinal == i) }
            for (s in School.entries) {
                val mid = SCHOOL_START + (s.ordinal + 0.5) * 2 * PI / 3
                val (ix, iy) = polar(cx, cy, (INNER + OUTER) / 2.0, mid)
                val size = if (hoverSchool == s) 26 else 22
                context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, ArtClient.icon("school_${s.name.lowercase()}"), ix - size / 2, iy - size / 2, size, size)
            }
            val label = hoverSchool?.let { Text.translatable("hha_arius.school.${it.name.lowercase()}") } ?: Text.translatable("hha_arius.wheel.hint")
            centered(context, label, cx, cy - 4, Ui.TEXT)
            val sel = Text.translatable("hha_arius.wheel.current", Text.translatable("hha_arius.spell.${ArtClient.selected.key}"))
            if (hoverBuff == null) centered(context, sel, cx, cy + OUTER + 14, Ui.MUTED)
        } else {
            val spells = Spell.bySchool(current)
            val n = spells.size
            hoverSchool = null
            hoverSpell = if (hoverBuff == null && dist > INNER - 4 && dist < OUTER + 24) spells[sectorOf(angle, n, spellStart(n))] else null
            drawRing(context, cx, cy, n, spellStart(n)) { i -> ArtColors.of(current.ordinal) to (hoverSpell == spells[i]) }
            for ((i, spell) in spells.withIndex()) {
                val mid = spellStart(n) + (i + 0.5) * 2 * PI / n
                val (ix, iy) = polar(cx, cy, (INNER + OUTER) / 2.0, mid)
                val left = ArtClient.cooldownLeft(spell)
                val size = if (hoverSpell == spell) 26 else 22
                val tint = if (left > 0) 0x80FFFFFF.toInt() else -1
                context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, ArtClient.icon(spell.key), ix - size / 2, iy - size / 2, size, size, tint)
                if (spell == ArtClient.selected) frame(context, ix - 14, iy - 14, ix + 14, iy + 14, 0x90FFFFFF.toInt())
                if (left > 0) {
                    val t = String.format("%.0f", Math.ceil(left / 20.0))
                    context.drawText(tr, t, ix - tr.getWidth(t) / 2, iy + 12, Ui.MUTED, true)
                }
            }
            centered(context, Text.translatable("hha_arius.school.${current.name.lowercase()}"), cx, cy - 4, ArtColors.of(current.ordinal))
            if (hoverBuff == null) info(context, cx, cy + OUTER + 12, hoverSpell)
        }
    }

    /**
     * Trank-Leiste über dem Rad (seit 1.8.0): Full-Pot und Tank-Mode als zwei Felder, wählbar per Maus (Loslassen der
     * Technik-Taste über einem Feld oder Linksklick). Zeigt Abklingzeit und Kosten.
     */
    private fun buffBar(context: DrawContext, cx: Int, y: Int, mouseX: Int, mouseY: Int) {
        val buffs = Spell.BUFFS
        val w = 66
        val gap = 8
        val total = buffs.size * w + (buffs.size - 1) * gap
        var x = cx - total / 2
        hoverBuff = null
        for (spell in buffs) {
            val hot = mouseX in x until x + w && mouseY in y - 2 until y + 24
            if (hot) hoverBuff = spell
            val color = if (spell == Spell.FULL_POT) 0xFFE7B95A.toInt() else 0xFF8FB8E8.toInt()
            context.fill(x, y - 2, x + w, y + 24, if (hot) Ui.alpha(color, 0.45f) else 0xB00B0B0E.toInt())
            frame(context, x, y - 2, x + w, y + 24, if (spell == ArtClient.selected) 0xC0FFFFFF.toInt() else 0x50FFFFFF)
            val left = ArtClient.cooldownLeft(spell)
            context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, ArtClient.icon(spell.key), x + 3, y + 1, 18, 18, if (left > 0) 0x80FFFFFF.toInt() else -1)
            val name = Text.translatable("hha_arius.spell.${spell.key}")
            context.drawText(textRenderer, name, x + 24, y + 2, if (hot) Ui.TEXT else Ui.MUTED, true)
            val sub = if (left > 0) String.format("%.0f s", Math.ceil(left / 20.0)) else "${ArtClient.costs[spell.ordinal]} ✦"
            context.drawText(textRenderer, sub, x + 24, y + 12, Ui.DIM, true)
            x += w + gap
        }
        if (hoverBuff != null) info(context, cx, cy0() + OUTER + 12, hoverBuff)
    }

    private fun cy0() = height / 2

    /** Infozeile unter dem Rad: Name, Art, Kosten, Abklingzeit, Kurzbeschreibung. */
    private fun info(context: DrawContext, cx: Int, y: Int, spell: Spell?) {
        if (spell == null) {
            centered(context, Text.translatable("hha_arius.wheel.back"), cx, y, Ui.DIM)
            return
        }
        centered(context, Text.translatable("hha_arius.spell.${spell.key}"), cx, y, Ui.TEXT)
        val line = Text.translatable(
            "hha_arius.wheel.info",
            Text.translatable("hha_arius.kind.${spell.kind.name.lowercase()}"),
            ArtClient.costs[spell.ordinal],
            Text.translatable("hha_arius.role.${spell.role.name.lowercase()}"),
        )
        centered(context, line, cx, y + 11, Ui.MUTED)
        centered(context, Text.translatable("hha_arius.spell.${spell.key}.desc"), cx, y + 22, Ui.DIM)
    }

    private fun centered(context: DrawContext, text: Text, cx: Int, y: Int, color: Int) {
        context.drawText(textRenderer, text, cx - textRenderer.getWidth(text) / 2, y, color, true)
    }

    private fun frame(context: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        context.fill(x1, y1, x2, y1 + 1, color)
        context.fill(x1, y2 - 1, x2, y2, color)
        context.fill(x1, y1 + 1, x1 + 1, y2 - 1, color)
        context.fill(x2 - 1, y1 + 1, x2, y2 - 1, color)
    }

    private fun polar(cx: Int, cy: Int, r: Double, a: Double): Pair<Int, Int> = (cx + cos(a) * r).toInt() to (cy + sin(a) * r).toInt()

    private fun sectorOf(angle: Double, n: Int, start: Double): Int {
        var a = angle - start
        while (a < 0) a += 2 * PI
        while (a >= 2 * PI) a -= 2 * PI
        return (a / (2 * PI / n)).toInt().coerceIn(0, n - 1)
    }

    /** Ring mit n Sektoren; je Sektor Farbe und ob er hervorgehoben ist. Zeichnet zeilenweise in Läufen. */
    private fun drawRing(context: DrawContext, cx: Int, cy: Int, n: Int, start: Double, style: (Int) -> Pair<Int, Boolean>) {
        val r0 = INNER.toDouble()
        val r1 = OUTER.toDouble()
        val styles = (0 until n).map(style)
        for (y in -OUTER..OUTER) {
            var runStart = Int.MIN_VALUE
            var runColor = 0
            fun flush(endX: Int) {
                if (runStart != Int.MIN_VALUE && runColor != 0) context.fill(cx + runStart, cy + y, cx + endX, cy + y + 1, runColor)
                runStart = Int.MIN_VALUE
            }
            for (x in -OUTER..OUTER + 1) {
                val d = hypot(x + 0.5, y + 0.5)
                var color = 0
                if (x <= OUTER && d in r0..r1) {
                    val sec = sectorOf(atan2(y + 0.5, x + 0.5), n, start)
                    val (c, hot) = styles[sec]
                    val local = ((atan2(y + 0.5, x + 0.5) - start) % (2 * PI / n) + 2 * PI) % (2 * PI / n)
                    val edge = local * d < 1.2 || d < r0 + 1.2 || d > r1 - 1.2
                    color = when {
                        edge -> 0x50FFFFFF
                        hot -> Ui.alpha(c, 0.42f)
                        else -> 0xB00B0B0E.toInt()
                    }
                }
                if (color != runColor) {
                    flush(x)
                    if (color != 0) runStart = x
                    runColor = color
                }
            }
            flush(OUTER + 1)
        }
    }

    private fun spellStart(n: Int): Double = -PI / 2 - PI / n

    private companion object {
        const val INNER = 26
        const val OUTER = 74
        const val SCHOOL_START = -PI / 2 - PI / 3
    }
}

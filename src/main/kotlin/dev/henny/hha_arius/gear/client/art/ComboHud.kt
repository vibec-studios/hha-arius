package dev.henny.hha_arius.gear.client.art

import dev.henny.hha.client.gui.Ui
import dev.henny.hha_arius.gear.art.ArtAction
import dev.henny.hha_arius.gear.art.ComboS2C
import dev.henny.hha_arius.gear.art.ComboStyle
import dev.henny.hha_arius.gear.art.GauntletMode
import dev.henny.hha_arius.gear.art.Move
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.input.KeyInput
import net.minecraft.client.option.KeyBinding
import net.minecraft.text.Text

/**
 * Kombo-Anzeige der Handschuhe (seit 1.8.0, Crix: „man soll die Kombos sehen, frei oder vorgegeben, und welche Tasten
 * man drücken muss“). Rechts neben dem Fadenkreuz:
 * * Vorgegebene Kombo: ihr Name und die Tastenfolge als Kästchen (L = Angriff, R = Tritt); getroffene leuchten golden,
 *   die nächste pulsiert; beim Finisher blitzt alles auf.
 * * Frei: die letzten Schläge als Kette und darunter, was als Nächstes kommt (L → …, R → …).
 * Die Auswahl öffnet die Technik-Taste im Nahkampfmodus ([ComboScreen]).
 */
object ComboHud {

    var style = ComboStyle.FREESTYLE
        private set
    private var progress = 0
    private var chain = 0
    private val recent = ArrayDeque<Pair<Move, Long>>()
    private var flash = 0L
    private var lastInput = 0L

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(ComboS2C.ID) { p, _ ->
            style = ComboStyle.of(p.style)
            progress = p.progress
            chain = p.chain
            val now = MinecraftClient.getInstance().world?.time ?: 0L
            Move.entries.getOrNull(p.move)?.let {
                recent.addLast(it to now)
                while (recent.size > 5) recent.removeFirst()
                lastInput = now
            }
            if (p.finisher) flash = now
        }
    }

    private fun name(m: Move): Text = Text.translatable("hha_arius.move.${m.name.lowercase()}")

    /** Was folgt auf den Kettencode (siehe GauntletCombat.start). */
    private fun nextL(c: Int): Move = when (c) { 1 -> Move.CROSS; 2 -> Move.HOOK; 3, 21 -> Move.UPPERCUT; else -> Move.JAB }
    private fun nextR(c: Int): Move = when (c) {
        1 -> Move.KNEE; 2 -> Move.SIDE_KICK; 3 -> Move.SPIN_KICK; 4 -> Move.AXE_KICK
        11 -> Move.ROUNDHOUSE; 12 -> Move.SPIN_KICK; 13 -> Move.AXE_KICK; 21 -> Move.LOW_SWEEP
        else -> Move.SIDE_KICK
    }

    fun render(context: DrawContext, mode: GauntletMode) {
        if (mode != GauntletMode.MELEE) return
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val now = client.world?.time ?: 0L
        val x = context.scaledWindowWidth / 2 + 24
        var y = context.scaledWindowHeight / 2 - 18
        val idle = now - lastInput > 80
        val alpha = if (idle && style == ComboStyle.FREESTYLE) 0.45f else 1f
        val fl = ((10 - (now - flash)).coerceAtLeast(0) / 10f)
        context.drawText(tr, Text.translatable("hha_arius.combo.${style.key}"), x, y, Ui.alpha(if (fl > 0f) 0xFFFFD98A.toInt() else 0xFFBFE6FF.toInt(), alpha), true)
        y += 11
        if (style != ComboStyle.FREESTYLE) {
            var bx = x
            for ((i, c) in style.keys.withIndex()) {
                val done = i < progress
                val nextOne = i == progress
                val pulse = if (nextOne) (0.6f + 0.4f * kotlin.math.sin(now * 0.4f)) else 1f
                val fill = when {
                    fl > 0f -> Ui.alpha(0xFFFFD98A.toInt(), 0.9f * fl + 0.1f)
                    done -> 0xE0D9B26A.toInt()
                    nextOne -> Ui.alpha(0xFF3A5A80.toInt(), pulse)
                    else -> 0xB0101420.toInt()
                }
                context.fill(bx, y, bx + 12, y + 12, fill)
                context.fill(bx, y, bx + 12, y + 1, 0x60FFFFFF)
                val label = if (c == 'L') "L" else "R"
                context.drawText(tr, label, bx + 6 - tr.getWidth(label) / 2, y + 2, if (done) 0xFF1A1206.toInt() else 0xFFEAF2FF.toInt(), false)
                bx += 14
            }
            y += 15
            val legend = Text.translatable("hha_arius.combo.legend", client.options.attackKey.boundKeyLocalizedText, client.options.useKey.boundKeyLocalizedText)
            context.drawText(tr, legend, x, y, Ui.alpha(Ui.MUTED, 0.8f), true)
            return
        }
        // Frei: Kette und nächste Schläge
        val chainNow = if (idle) 0 else chain
        if (!idle) {
            var line = Text.empty()
            for ((i, e) in recent.withIndex()) {
                if (now - e.second > 60) continue
                if (line.string.isNotEmpty()) line = line.copy().append(Text.literal(" › ").withColor(0x7F8FA8))
                line = line.copy().append(name(e.first).copy().withColor(if (i == recent.size - 1) 0xFFFFFF else 0xA9B6CC))
            }
            if (line.string.isNotEmpty()) {
                context.drawText(tr, line, x, y, 0xFFFFFFFF.toInt(), true)
                y += 11
            }
        }
        context.drawText(tr, Text.literal("L ").withColor(0xFFD98A).append(name(nextL(chainNow)).copy().withColor(0xDCE6F5)), x, y, Ui.alpha(0xFFFFFFFF.toInt(), alpha), true)
        y += 10
        context.drawText(tr, Text.literal("R ").withColor(0xFFD98A).append(name(nextR(chainNow)).copy().withColor(0xDCE6F5)), x, y, Ui.alpha(0xFFFFFFFF.toInt(), alpha), true)
    }
}

/** Auswahl der Kombo (Technik-Taste im Nahkampfmodus): Liste mit Tastenfolge und Kurzbeschreibung. */
class ComboScreen(private val key: KeyBinding) : Screen(Text.translatable("hha_arius.combo.title")) {

    private var hover = -1
    private var done = false

    override fun shouldPause(): Boolean = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        context.fill(0, 0, width, height, 0x50000000)
    }

    private fun rowY(i: Int): Int = height / 2 - ComboStyle.entries.size * 15 + i * 30

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        super.render(context, mouseX, mouseY, deltaTicks)
        val cx = width / 2
        val w = 220
        context.drawText(textRenderer, title, cx - textRenderer.getWidth(title) / 2, rowY(0) - 18, Ui.TEXT, true)
        hover = -1
        for ((i, st) in ComboStyle.entries.withIndex()) {
            val y = rowY(i)
            val hot = mouseX in cx - w / 2 until cx + w / 2 && mouseY in y until y + 26
            if (hot) hover = i
            val sel = st == ComboHud.style
            context.fill(cx - w / 2, y, cx + w / 2, y + 26, if (hot) 0xC0283C58.toInt() else 0xB00B0B0E.toInt())
            if (sel) context.fill(cx - w / 2, y, cx - w / 2 + 2, y + 26, 0xFFFFD98A.toInt())
            context.drawText(textRenderer, Text.translatable("hha_arius.combo.${st.key}"), cx - w / 2 + 8, y + 3, if (sel) 0xFFFFD98A.toInt() else Ui.TEXT, true)
            context.drawText(textRenderer, Text.translatable("hha_arius.combo.${st.key}.desc"), cx - w / 2 + 8, y + 14, Ui.MUTED, true)
            var bx = cx + w / 2 - 8 - st.keys.length * 13
            for (c in st.keys) {
                context.fill(bx, y + 7, bx + 11, y + 18, 0xD03A5A80.toInt())
                context.drawText(textRenderer, c.toString(), bx + 3, y + 9, 0xFFEAF2FF.toInt(), false)
                bx += 13
            }
        }
    }

    private fun choose(i: Int) {
        if (done || i !in ComboStyle.entries.indices) return
        done = true
        ArtClient.send(ArtAction.COMBO_SELECT, i)
        close()
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0 && hover >= 0) {
            choose(hover)
            return true
        }
        if (click.button() == 1) {
            close()
            return true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun keyReleased(input: KeyInput): Boolean {
        if (key.matchesKey(input)) {
            if (hover >= 0) choose(hover) else close()
            return true
        }
        return super.keyReleased(input)
    }
}

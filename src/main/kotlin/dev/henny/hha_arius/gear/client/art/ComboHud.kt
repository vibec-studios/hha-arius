package dev.henny.hha_arius.gear.client.art

import dev.henny.hha.client.gui.Ui
import dev.henny.hha_arius.gear.art.Combo
import dev.henny.hha_arius.gear.art.ComboS2C
import dev.henny.hha_arius.gear.art.ComboTree
import dev.henny.hha_arius.gear.art.GauntletMode
import dev.henny.hha_arius.gear.art.Move
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Kombo-Anzeige der Handschuhe (seit 1.8.1 nur noch Links-/Rechtsklick-Folgen, keine Auswahl über eine Taste mehr).
 * Rechts neben dem Fadenkreuz:
 * * In Ruhe: die Kombo-Liste, je Zeile die Folge als Kästchen (L = Linksklick, R = Rechtsklick) und der Name, gedämpft.
 * * Während einer Kette: die getroffenen Tasten golden, darunter nur die Kombos, die noch möglich sind, mit der
 *   nächsten Taste pulsierend.
 * * Beim Finisher: ihr Name blitzt auf.
 */
object ComboHud {

    private var seq = ""
    private var lastInput = 0L
    private var flash = 0L
    private var flashCombo: Combo? = null
    private var lastMove: Move? = null

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(ComboS2C.ID) { p, _ ->
            val now = MinecraftClient.getInstance().world?.time ?: 0L
            seq = p.seq
            Move.entries.getOrNull(p.move)?.let {
                lastMove = it
                lastInput = now
            }
            Combo.of(p.finisher)?.let {
                flashCombo = it
                flash = now
            }
        }
    }

    private fun box(context: DrawContext, x: Int, y: Int, c: Char, fill: Int, dark: Boolean) {
        val tr = MinecraftClient.getInstance().textRenderer
        context.fill(x, y, x + 11, y + 11, fill)
        context.fill(x, y, x + 11, y + 1, 0x60FFFFFF)
        val label = c.toString()
        context.drawText(tr, label, x + 6 - tr.getWidth(label) / 2, y + 2, if (dark) 0xFF1A1206.toInt() else 0xFFEAF2FF.toInt(), false)
    }

    fun render(context: DrawContext, mode: GauntletMode) {
        if (mode != GauntletMode.MELEE) return
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val now = client.world?.time ?: 0L
        val x = context.scaledWindowWidth / 2 + 24
        var y = context.scaledWindowHeight / 2 - 26
        val active = seq.isNotEmpty() && now - lastInput < 40
        // Finisher: Name groß und golden.
        val fl = ((14 - (now - flash)).coerceAtLeast(0) / 14f)
        flashCombo?.let { c ->
            if (fl > 0f) {
                context.drawText(tr, Text.translatable("hha_arius.combo.${c.key}"), x, y - 12, Ui.alpha(0xFFFFD98A.toInt(), fl), true)
            }
        }
        val list = if (active) ComboTree.continuations(seq) else Combo.entries
        val alpha = if (active) 1f else 0.5f
        if (active) {
            // Getroffene Folge
            var bx = x
            for (c in seq) {
                box(context, bx, y, c, 0xE0D9B26A.toInt(), true)
                bx += 13
            }
            lastMove?.let { context.drawText(tr, Text.translatable("hha_arius.move.${it.name.lowercase()}"), bx + 3, y + 2, 0xFFFFFFFF.toInt(), true) }
            y += 15
        }
        for (combo in list) {
            var bx = x
            for ((i, c) in combo.keys.withIndex()) {
                val done = active && i < seq.length
                val nextOne = active && i == seq.length
                val pulse = if (nextOne) 0.6f + 0.4f * kotlin.math.sin(now * 0.5f) else 1f
                val fill = when {
                    done -> Ui.alpha(0xFFD9B26A.toInt(), 0.85f * alpha)
                    nextOne -> Ui.alpha(0xFF3A6A9A.toInt(), pulse)
                    else -> Ui.alpha(0xFF101420.toInt(), 0.7f * alpha)
                }
                box(context, bx, y, c, fill, done)
                bx += 12
            }
            context.drawText(tr, Text.translatable("hha_arius.combo.${combo.key}"), x + 4 * 12 + 4, y + 2, Ui.alpha(0xFFDCE6F5.toInt(), alpha), true)
            y += 12
        }
        if (!active) {
            val legend = Text.translatable("hha_arius.combo.legend", client.options.attackKey.boundKeyLocalizedText, client.options.useKey.boundKeyLocalizedText)
            context.drawText(tr, legend, x, y + 2, Ui.alpha(Ui.MUTED, 0.7f), true)
        }
    }
}

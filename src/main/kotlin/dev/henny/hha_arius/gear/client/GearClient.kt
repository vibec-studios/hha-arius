package dev.henny.hha_arius.gear.client

import dev.henny.hha.api.client.HhaClientAddonContext
import dev.henny.hha.api.client.HudCooldowns
import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.gear.Gear
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.ResonancePayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.util.Identifier
import kotlin.math.sin

/** Ausrüstung im Client: HUD-Cooldowns in HHAs Leiste, Resonanzbalken darüber, Leuchtebene der Rüstung (Mixin). */
object GearClient {

    private const val ICE = 0x9FE6FF
    private const val STEEL = 0x5A7FAE

    /** Vom Server gemeldete Resonanz; der Balken gleitet weich zum Zielwert. */
    private var target = 0
    private var max = 100
    private var shown = 0f

    fun register(context: HhaClientAddonContext) {
        val full: (PlayerEntity) -> Boolean = { Gear.hasFullSet(it) }

        context.registerHudCooldown(HhaArius.id("hud/white_line"), ICE) { p ->
            if (full(p)) p.getEquippedStack(EquipmentSlot.CHEST) else null
        }
        context.registerHudCooldown(HhaArius.id("hud/frost_step"), ICE) { p ->
            if (full(p)) p.getEquippedStack(EquipmentSlot.FEET) else null
        }
        context.registerHudCooldown(HhaArius.id("hud/white_core"), ICE) { p ->
            if (full(p)) p.getEquippedStack(EquipmentSlot.HEAD) else null
        }
        context.registerHudCooldown(HhaArius.id("hud/counter"), ICE) { p -> held(p) { it.isOf(GearItems.SOLVANE) } }
        context.registerHudCooldown(HhaArius.id("hud/wingscale"), ICE) { p -> held(p) { it.isOf(GearItems.NIVARIS_WINGSCALE) } }

        ClientPlayNetworking.registerGlobalReceiver(ResonancePayload.ID) { payload, _ ->
            target = payload.value
            max = payload.max.coerceAtLeast(1)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            target = 0
            shown = 0f
        }
        ClientTickEvents.END_CLIENT_TICK.register { shown += (target - shown) * 0.35f }
        HudRenderCallback.EVENT.register { drawContext, _ -> renderResonance(MinecraftClient.getInstance(), drawContext) }
    }

    private fun held(player: PlayerEntity, test: (ItemStack) -> Boolean): ItemStack? = when {
        test(player.mainHandStack) -> player.mainHandStack
        test(player.offHandStack) -> player.offHandStack
        else -> null
    }

    /** Schmaler Balken direkt über der HHA-Cooldown-Leiste (gleiche Skalierung/Verschiebung wie HHAs HUD). */
    private fun renderResonance(client: MinecraftClient, context: DrawContext) {
        val player = client.player ?: return
        if (client.options.hudHidden || !Gear.hasFullSet(player)) return
        if (!HhaClientConfig.enabled("hud_enabled")) return

        val slots = countSlots(player)
        val scale = HhaClientConfig.numF("hud_scale").takeIf { it > 0f } ?: 1f
        val offsetX = HhaClientConfig.num("hud_offset_x").toInt()
        val offsetY = HhaClientConfig.num("hud_offset_y").toInt()
        val matrices = context.matrices
        matrices.pushMatrix()
        try {
            matrices.scale(scale, scale)
            val viewW = context.scaledWindowWidth / scale
            val viewH = context.scaledWindowHeight / scale
            // HHA: Slots bei y = viewH - 82, Rahmen 2 px darüber.
            val slotTop = (viewH - 82).toInt() + offsetY - 2
            val width = (slots * 22 - 4).coerceAtLeast(62)
            val left = ((viewW - width) / 2).toInt() + offsetX
            val top = slotTop - 6
            val fraction = (shown / max).coerceIn(0f, 1f)
            val fill = (width * fraction).toInt()

            context.fill(left - 1, top - 1, left + width + 1, top + 4, 0xA0000000.toInt())
            context.fill(left, top, left + width, top + 3, 0xFF1B2230.toInt())
            if (fill > 0) {
                val strong = shown >= 80f
                val pulse = if (strong) (sin((client.world?.time ?: 0L) * 0.25) * 0.5 + 0.5).toFloat() else 0f
                val color = if (strong) lerp(ICE, 0xFFFFFF, pulse * 0.45f) else lerp(STEEL, ICE, fraction)
                context.fill(left, top, left + fill, top + 3, 0xFF000000.toInt() or color)
                context.fill(left, top, left + fill, top + 1, 0x60FFFFFF)
            }
            // Kosten-Marken: Frostschritt, Weiße Linie, Weißer Kern.
            for (cost in intArrayOf(20, 40, 60)) {
                val x = left + (width * cost / max.toFloat()).toInt()
                if (x in (left + 1) until (left + width)) context.fill(x, top, x + 1, top + 3, 0x90FFFFFF.toInt())
            }
        } finally {
            matrices.popMatrix()
        }
    }

    /** Anzahl der Slots, die HHA gerade zeigt (für die Balkenbreite; HHAs eigene Slots werden grob mitgezählt). */
    private fun countSlots(player: PlayerEntity): Int {
        var n = 0
        for (entry in HudCooldowns.all()) {
            val stack = entry.provider(player) ?: continue
            if (!stack.isEmpty) n++
        }
        return n
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * tt).toInt() and 0xFF
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Für den Java-Mixin der Rüstung: Leuchtnähte `eirholm_glow.png`, lichtunabhängig, sanfter Puls 70–100 % in 3 s. */
object EirholmGlow {

    private val ASSET = HhaArius.id("eirholm")
    private val textures = HashMap<String, Identifier>()
    private val present = HashMap<String, Boolean>()
    private var checkedAt = 0L

    @JvmStatic
    fun isEirholm(asset: Identifier): Boolean = asset == ASSET

    /** Textur der Leuchtebene für den Layer-Typ (`humanoid` / `humanoid_leggings`) oder null, wenn sie (noch) fehlt. */
    @JvmStatic
    fun texture(layerType: String): Identifier? {
        if (layerType != "humanoid" && layerType != "humanoid_leggings") return null
        val id = textures.getOrPut(layerType) { HhaArius.id("textures/entity/equipment/$layerType/eirholm_glow.png") }
        val now = System.currentTimeMillis()
        if (now - checkedAt > 5000L) {
            present.clear()
            checkedAt = now
        }
        val exists = present.getOrPut(layerType) {
            MinecraftClient.getInstance().resourceManager.getResource(id).isPresent
        }
        return if (exists) id else null
    }

    /** ARGB-Tönung (Weiß, Helligkeit 70–100 %, 3-s-Zyklus). */
    @JvmStatic
    fun tint(): Int {
        val t = (System.currentTimeMillis() % 3000L) / 3000.0
        val f = 0.85 + 0.15 * sin(t * Math.PI * 2)
        val v = (255 * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
}

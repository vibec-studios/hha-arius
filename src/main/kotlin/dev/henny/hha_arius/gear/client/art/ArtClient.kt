package dev.henny.hha_arius.gear.client.art

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.client.fx.ArtColors
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.ArtAction
import dev.henny.hha_arius.gear.art.ArtInputC2S
import dev.henny.hha_arius.gear.art.ArtSelfS2C
import dev.henny.hha_arius.gear.art.ArtTimings
import dev.henny.hha_arius.gear.art.CastKind
import dev.henny.hha_arius.gear.art.GauntletMode
import dev.henny.hha_arius.gear.art.GauntletsItem
import dev.henny.hha_arius.gear.art.Placement
import dev.henny.hha_arius.gear.art.Spell
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW

/**
 * Client der Handschuhe und der Schwerttechnik: Tasten, Klick-Weiche, Zauberrad, HUD, Bauvorschau und das Steuern des
 * Lichtgleiters. Die Spiellogik entscheidet der Server; hier wird nur gesendet und angezeigt.
 *
 * Bedienung über die HHA-Ability-Tasten für Addons (Steuerung → HHA, frei belegbar; beim ersten Start einmal mit R/V
 * vorbelegt, wenn sie noch frei sind):
 * * Ability 4 = Technik: mit Handschuhen Rad öffnen (halten, Maus in Richtung, loslassen = wählen; kurz tippen = zur
 *   vorigen Technik wechseln); mit Solvane Klarer Schnitt (halten = Haltung, loslassen = Schnitt).
 * * Ability 5 = Modus: Nahkampf ↔ Magie (nicht mitten in einer Aktion).
 * HHA verbraucht die Tastendrücke selbst (Server-Abilities), deshalb wertet Arius die Flanken über `isPressed` aus.
 * * Nahkampf: Linksklick Schlagfolge, Rechtsklick Tritt, Schleichen + Rechtsklick Parade. Auf einen Block in Reichweite
 *   gehen Links- und Rechtsklick normal durch (abbauen, Türen/Truhen öffnen).
 * * Magie: Rechtsklick wirkt die gewählte Technik (tippen, laden, zielen/setzen, halten), Linksklick bricht ab.
 */
object ArtClient {

    /** HHA „Ability 4“ (Technik) und „Ability 5“ (Modus), gesucht über ihre Steuerungs-ID. */
    private const val TECH_ID = "key.hha.ability_4"
    private const val MODE_ID = "key.hha.ability_5"
    private var techKey: KeyBinding? = null
    private var modeKey: KeyBinding? = null
    private var techDown = false
    private var modeDown = false
    private var defaultsChecked = false

    private fun key(client: MinecraftClient, id: String): KeyBinding? = client.options.allKeys.firstOrNull { it.id == id }

    /** Technik-Taste (für Rad und Hinweise); null, solange die Optionen noch nicht geladen sind. */
    fun techKey(): KeyBinding? = techKey ?: MinecraftClient.getInstance().options?.let { key(MinecraftClient.getInstance(), TECH_ID) }?.also { techKey = it }

    // Eigener Zustand vom Server
    var selected = Spell.WIND_BLADE
        private set
    var previous = Spell.YANG_PLATFORM
        private set
    private val cdUntil = LongArray(Spell.entries.size)

    /** Grundkosten je Technik (vom Server). */
    val costs = IntArray(Spell.entries.size)
    private var artCdUntil = 0L
    private var holeCdUntil = 0L
    var gliding = false
        private set

    /** Zuletzt gewählte Technik je Schule (für Auswahl über die Schule im Rad). */
    val lastOfSchool = intArrayOf(Spell.WIND_BLADE.ordinal, Spell.FROST_SHARDS.ordinal, Spell.YANG_ORB.ordinal)

    private var useHeld = false

    /** Was zuletzt in der Haupthand lag (0 nichts, 1 Handschuhe, 2 Solvane): beim Ziehen einmal die Tasten zeigen. */
    private var lastHeld = 0
    private var conflictsWarned = false
    private var castHeld = false
    private var artHeld = false

    /** Ladezeiten für die Anzeige (Standardwerte des Servers). */
    private val CHARGE = mapOf(Spell.GALE to (6 to 24), Spell.YANG_ORB to (4 to 30), Spell.GLACIER_LANCE to (8 to 36))

    private const val GLIDE_SPEED = 0.55

    fun init() {
        // Bau-Blöcke der Zauber sind durchscheinend.
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(dev.henny.hha_arius.gear.art.Constructs.FROST, net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT)
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(dev.henny.hha_arius.gear.art.Constructs.YANG, net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT)
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(dev.henny.hha_arius.gear.art.Constructs.YANG_BRICKS, net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT)
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(dev.henny.hha_arius.gear.art.Constructs.YANG_WALL, net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT)

        ComboHud.init()
        ClientPlayNetworking.registerGlobalReceiver(ArtSelfS2C.ID) { p, _ ->
            val now = ArtAnims.ticks
            Spell.of(p.selected)?.let {
                selected = it
                lastOfSchool[it.school.ordinal] = it.ordinal
            }
            Spell.of(p.previous)?.let { previous = it }
            for (i in cdUntil.indices) cdUntil[i] = now + (p.cooldowns.getOrNull(i) ?: 0)
            artCdUntil = now + p.artCooldown
            holeCdUntil = now + p.holeCooldown
            gliding = p.gliding
            for (i in costs.indices) costs[i] = p.costs.getOrNull(i) ?: costs[i]
        }
        ClientTickEvents.START_CLIENT_TICK.register { client -> steerGlide(client) }
        ClientTickEvents.END_CLIENT_TICK.register { client -> tickInput(client) }
        HudRenderCallback.EVENT.register { context, tickCounter -> renderHud(context, tickCounter.getTickProgress(true)) }
        WorldRenderEvents.END_MAIN.register { context -> renderPreview(context.consumers()) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            gliding = false
            useHeld = false
            castHeld = false
            artHeld = false
        }
    }

    fun send(action: ArtAction, arg: Int = 0) {
        if (ClientPlayNetworking.canSend(ArtInputC2S.ID)) ClientPlayNetworking.send(ArtInputC2S(action.ordinal, arg))
    }

    fun cooldownLeft(spell: Spell): Long = (cdUntil[spell.ordinal] - ArtAnims.ticks).coerceAtLeast(0)

    private fun holdsGauntlets(player: PlayerEntity) = GauntletsItem.isGauntlets(player.mainHandStack)

    private fun mode(player: PlayerEntity) = GauntletsItem.mode(player.mainHandStack)

    private fun selfAnim(): Anim? {
        val player = MinecraftClient.getInstance().player ?: return null
        return ArtAnims.get(player.id)?.anim
    }

    private fun castingLocally(): Boolean = when (selfAnim()) {
        Anim.CAST_CHARGE, Anim.PLACE_AIM, Anim.SHIELD, Anim.GLIDE -> true
        else -> castHeld
    }

    private fun blockTargeted(client: MinecraftClient): Boolean = client.crosshairTarget?.type == HitResult.Type.BLOCK

    // ------------------------------------------------------------------------------------------------- Klick-Weiche

    /** Linksklick. true = eigene Aktion (kein Vanilla-Schlag). */
    @JvmStatic
    fun onAttack(): Boolean {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return false
        if (client.currentScreen != null || player.isSpectator) return false
        // Langer Drachenkörper: Treffer auf ein gezeichnetes Glied (Handschuhe melden nur und schlagen normal weiter).
        if (dev.henny.hha_arius.client.fx.DragonHits.onAttack()) return true
        val main = player.mainHandStack
        if (GauntletsItem.isGauntlets(main)) {
            return if (mode(player) == GauntletMode.MELEE) {
                if (blockTargeted(client)) return false
                send(ArtAction.ATTACK)
                true
            } else if (castingLocally()) {
                send(ArtAction.CAST_CANCEL)
                castHeld = false
                true
            } else {
                false
            }
        }
        if (main.isOf(GearItems.SOLVANE) && (artHeld || selfAnim() == Anim.ART_CHARGE)) {
            send(ArtAction.ART_CANCEL)
            artHeld = false
            return true
        }
        if (main.isOf(GearItems.SOLVANE)) {
            // Schwertspur nur bei vollen Treffern: Schlagkraft merken, bevor Vanilla sie zurücksetzt.
            SwordTrails.lastAttackStrength = player.getAttackCooldownProgress(0.5f)
            if (SwordTrails.lastAttackStrength >= 0.9f && client.crosshairTarget?.type == HitResult.Type.ENTITY) SwordTrails.confirmLocal(player.id)
        }
        return false
    }

    /** Rechtsklick (Vanilla-Benutzen). true = unterdrücken; die eigene Aktion entsteht in [tickInput] beim Drücken. */
    @JvmStatic
    fun onUse(): Boolean {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return false
        val main = player.mainHandStack
        if (GauntletsItem.isGauntlets(main)) {
            return !(mode(player) == GauntletMode.MELEE && blockTargeted(client))
        }
        if (main.isOf(GearItems.SOLVANE)) return artHeld || selfAnim() == Anim.ART_CHARGE || selfAnim() == Anim.ART_RELEASE
        return false
    }

    /** Während des Zauberns kein Abbauen mit gehaltener Maustaste. */
    @JvmStatic
    fun blockBreakingSuppressed(): Boolean {
        val player = MinecraftClient.getInstance().player ?: return false
        return holdsGauntlets(player) && castingLocally()
    }

    /**
     * Einmalig: Sind HHA Ability 4/5 noch unbelegt, R bzw. V vorbelegen (Merker in config/hha_arius/keys_v1, damit eine
     * später bewusst gelöschte Belegung gelöscht bleibt).
     */
    private fun checkDefaults(client: MinecraftClient) {
        if (defaultsChecked) return
        defaultsChecked = true
        val marker = net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("hha_arius").resolve("keys_v1")
        try {
            if (java.nio.file.Files.exists(marker)) return
            var changed = false
            for ((id, code) in listOf(TECH_ID to GLFW.GLFW_KEY_R, MODE_ID to GLFW.GLFW_KEY_V)) {
                val k = key(client, id) ?: continue
                if (k.isUnbound) {
                    k.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(code))
                    changed = true
                }
            }
            if (changed) {
                KeyBinding.updateKeysByCode()
                client.options.write()
            }
            java.nio.file.Files.createDirectories(marker.parent)
            java.nio.file.Files.writeString(marker, "HHA Ability 4/5 einmal mit R/V vorbelegt\n")
        } catch (_: Exception) {
        }
    }

    private fun tickInput(client: MinecraftClient) {
        val player = client.player ?: return
        checkDefaults(client)
        val tech = techKey ?: key(client, TECH_ID)?.also { techKey = it } ?: return
        val modeK = modeKey ?: key(client, MODE_ID)?.also { modeKey = it } ?: return
        val gauntlets = holdsGauntlets(player)
        val solvane = player.mainHandStack.isOf(GearItems.SOLVANE)
        val inGame = client.currentScreen == null
        val held = if (gauntlets) 1 else if (solvane) 2 else 0
        if (held != lastHeld) {
            lastHeld = held
            if (held != 0) showKeys(client, player, gauntlets)
        }

        // Rechtsklick-Flanken
        val pressed = inGame && client.options.useKey.isPressed
        if (pressed && !useHeld && gauntlets && !player.isSpectator) {
            if (mode(player) == GauntletMode.MELEE) {
                if (!blockTargeted(client)) send(if (player.isSneaking) ArtAction.PARRY else ArtAction.KICK)
            } else {
                send(ArtAction.CAST_PRESS)
                castHeld = true
            }
        }
        if (!pressed && castHeld) {
            send(ArtAction.CAST_RELEASE)
            castHeld = false
        }
        useHeld = pressed
        if (!gauntlets) castHeld = false

        // Brücke: beim Zielen dauerhaft anzeigen, wie man Länge und Breite einstellt (seit 1.6.5).
        if (gauntlets && selected == Spell.YANG_BRIDGE && ArtAnims.get(player.id)?.anim == Anim.PLACE_AIM && client.world!!.time % 10L == 0L) {
            val id = player.uuid
            player.sendMessage(net.minecraft.text.Text.translatable("hha_arius.msg.art.bridge_dims",
                dev.henny.hha_arius.gear.art.BridgeShape.length(id), dev.henny.hha_arius.gear.art.BridgeShape.width(id)), true)
        }
        val modeNow = modeK.isPressed
        if (modeNow && !modeDown && gauntlets && inGame) send(ArtAction.MODE_TOGGLE)
        modeDown = modeNow
        val techNow = tech.isPressed
        if (techNow && !techDown && inGame) {
            // Seit 1.8.0: im Nahkampfmodus öffnet die Technik-Taste die Kombo-Auswahl, im Magiemodus das Zauberrad.
            if (gauntlets) client.setScreen(if (mode(player) == GauntletMode.MELEE) ComboScreen(tech) else SpellWheelScreen(tech))
            else if (solvane && !artHeld) {
                send(ArtAction.ART_PRESS, if (player.isSneaking) 1 else 0)
                artHeld = true
            }
        }
        techDown = techNow && inGame
        if (artHeld && (!tech.isPressed || !solvane)) {
            if (solvane) send(ArtAction.ART_RELEASE, if (player.isSneaking) 1 else 0)
            artHeld = false
        }
    }

    /**
     * Beim Ziehen: die tatsächlich belegten Tasten in der Aktionsleiste (Text.keybind zeigt die eigene Belegung). Liegt
     * eine Arius-Taste auf derselben Taste wie eine andere Belegung (etwa V bei Sprach-Chat-Mods), einmal je Sitzung ein
     * Hinweis im Chat, weil die andere Belegung den Druck sonst schluckt.
     */
    private fun showKeys(client: MinecraftClient, player: PlayerEntity, gauntlets: Boolean) {
        val techK = techKey ?: return
        val modeK = modeKey ?: return
        val tech = Text.keybind(TECH_ID)
        val mode = Text.keybind(MODE_ID)
        val use = Text.keybind("key.use")
        val msg = if (gauntlets) {
            if (mode(player) == GauntletMode.MAGIC) Text.translatable("hha_arius.keys.gauntlets_magic", use, tech, mode)
            else Text.translatable("hha_arius.keys.gauntlets_melee", mode, tech)
        } else {
            Text.translatable("hha_arius.keys.solvane", tech)
        }
        player.sendMessage(msg, true)
        if (conflictsWarned) return
        for (own in arrayOf(techK, modeK)) {
            if (own.isUnbound) continue
            if (own.isUnbound) {
                conflictsWarned = true
                player.sendMessage(Text.translatable("hha_arius.keys.unbound", Text.translatable(own.id)).formatted(net.minecraft.util.Formatting.GOLD), false)
                continue
            }
            val other = client.options.allKeys.firstOrNull { it !== own && !it.isUnbound && it.equals(own) } ?: continue
            conflictsWarned = true
            player.sendMessage(
                Text.translatable("hha_arius.keys.conflict", own.boundKeyLocalizedText, Text.translatable(own.id), Text.translatable(other.id))
                    .formatted(net.minecraft.util.Formatting.GOLD),
                false,
            )
        }
    }

    /** Lichtgleiter: der Client steuert die eigene Bewegung (sanft sinkend, entlang des Blicks). Der Server misst Zeit/Kosten. */
    private fun steerGlide(client: MinecraftClient) {
        val player = client.player ?: return
        if (!gliding || client.isPaused) return
        val look = player.rotationVector
        val flat = Vec3d(look.x, 0.0, look.z)
        if (flat.lengthSquared() < 1e-4) return
        val h = flat.normalize().multiply(GLIDE_SPEED)
        val vy = (look.y * GLIDE_SPEED).coerceIn(-0.35, -0.04)
        player.velocity = Vec3d(h.x, vy, h.z)
        player.fallDistance = 0.0
    }

    // ------------------------------------------------------------------------------------------------- HUD

    fun icon(name: String): Identifier = HhaArius.id("hud/art/$name")

    private fun renderHud(context: DrawContext, tickDelta: Float) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        if (client.options.hudHidden || client.currentScreen is SpellWheelScreen || client.currentScreen is ComboScreen) return
        val cx = context.scaledWindowWidth / 2
        val cy = context.scaledWindowHeight / 2
        val anim = ArtAnims.get(player.id)
        if (holdsGauntlets(player)) {
            // Dezente Anzeige rechts unter dem Fadenkreuz: Modus, im Magiemodus dazu die gewählte Technik.
            val magic = mode(player) == GauntletMode.MAGIC
            val x = cx + 9
            val y = cy + 7
            context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, icon(if (magic) "mode_magic" else "mode_melee"), x, y, 9, 9, 0xC0FFFFFF.toInt())
            ComboHud.render(context, mode(player))
            if (magic) {
                val left = cooldownLeft(selected)
                val alpha = if (left > 0) 0x70FFFFFF else 0xD0FFFFFF.toInt()
                context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, icon(selected.key), x + 11, y - 1, 11, 11, alpha)
                if (left > 0) {
                    val text = String.format("%.0f", Math.ceil(left / 20.0))
                    context.drawText(client.textRenderer, text, x + 24, y + 1, 0xFFB0B8C8.toInt(), true)
                }
            }
            if (anim?.anim == Anim.CAST_CHARGE) {
                // Höchstladung kommt mit der Animation (param = Schule + 4 × Höchstladung); Mindestladung aus der Tabelle.
                val (min, fallbackMax) = CHARGE[selected] ?: (10 to 30)
                val max = dev.henny.hha_arius.gear.art.ArtParam.chargeMax(anim.param).takeIf { it > 0 } ?: fallbackMax
                chargeBar(context, cx, cy + 20, anim.age(tickDelta), min.toFloat(), max.toFloat(), ArtColors.of(selected.school.ordinal))
            }
            if (anim?.anim == Anim.PLACE_AIM && client.world != null && Placement.target(client.world!!, player, selected) == null) {
                val msg = Text.translatable("hha_arius.msg.art.no_target")
                context.drawText(client.textRenderer, msg, cx - client.textRenderer.getWidth(msg) / 2, cy + 18, 0xC0E07A6E.toInt(), true)
            }
        } else if (player.mainHandStack.isOf(GearItems.SOLVANE) && anim?.anim == Anim.ART_CHARGE) {
            // Ladebalken des Klaren Schnitts (Vertrag: Ladung aus der gehaltenen Zeit; Mindest- und Volladung fest).
            chargeBar(context, cx, cy + 20, anim.age(tickDelta), ArtTimings.ART_CHARGE_MIN.toFloat(), ArtTimings.ART_CHARGE_FULL.toFloat(), 0xFFFFFFFF.toInt())
        }
    }

    /** Schmaler Ladebalken mit Marke bei Mindestladung; voll = golden. */
    private fun chargeBar(context: DrawContext, cx: Int, y: Int, age: Float, min: Float, max: Float, color: Int) {
        val w = 40
        val x = cx - w / 2
        val f = (age / max).coerceIn(0f, 1f)
        context.fill(x - 1, y - 1, x + w + 1, y + 3, 0x90000000.toInt())
        val c = if (f >= 1f) 0xFFFFD98A.toInt() else if (age < min) withAlpha(color, 0.45f) else color
        context.fill(x, y, x + (w * f).toInt(), y + 2, c)
        val m = x + (w * (min / max)).toInt()
        context.fill(m, y - 1, m + 1, y + 3, 0xC0FFFFFF.toInt())
    }

    // ------------------------------------------------------------------------------------------------- Brückenmaße

    /**
     * Mausrad beim Zielen der Yang-Brücke: Länge ±2 (Schleichen: Breite ±2). true = Rad verbraucht. Die Maße gelten
     * sofort für die Vorschau und gehen an den Server.
     */
    @JvmStatic
    fun bridgeScroll(amount: Double): Boolean {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return false
        if (client.currentScreen != null || amount == 0.0) return false
        if (selected != Spell.YANG_BRIDGE || ArtAnims.get(player.id)?.anim != Anim.PLACE_AIM || !holdsGauntlets(player)) return false
        val step = if (amount > 0) 1 else -1
        val id = player.uuid
        var len = dev.henny.hha_arius.gear.art.BridgeShape.length(id)
        var wid = dev.henny.hha_arius.gear.art.BridgeShape.width(id)
        if (player.isSneaking) wid += 2 * step else len += 2 * step
        dev.henny.hha_arius.gear.art.BridgeShape.set(id, len, wid)
        len = dev.henny.hha_arius.gear.art.BridgeShape.length(id)
        wid = dev.henny.hha_arius.gear.art.BridgeShape.width(id)
        send(ArtAction.BRIDGE_DIMS, dev.henny.hha_arius.gear.art.BridgeShape.pack(len, wid))
        player.sendMessage(net.minecraft.text.Text.translatable("hha_arius.msg.art.bridge_dims", len, wid), true)
        player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.2f, 1.4f + step * 0.1f)
        return true
    }

    // ------------------------------------------------------------------------------------------------- Bauvorschau

    private fun renderPreview(consumers: net.minecraft.client.render.VertexConsumerProvider?) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val world = client.world ?: return
        if (consumers == null || ArtAnims.get(player.id)?.anim != Anim.PLACE_AIM || !holdsGauntlets(player)) return
        if (selected.kind != CastKind.PLACE) return
        val target = Placement.target(world, player, selected) ?: return
        val camera = client.gameRenderer.camera
        val d = VfxDraw(consumers, camera, client.renderTickCounter.getTickProgress(true))
        val color = ArtColors.of(selected.school.ordinal)
        val pulse = 0.22f + 0.08f * kotlin.math.sin(ArtAnims.ticks * 0.3f)
        val x = Vec3d(0.5, 0.0, 0.0)
        val y = Vec3d(0.0, 0.5, 0.0)
        val z = Vec3d(0.0, 0.0, 0.5)
        if (selected == Spell.FROST_FIELD) {
            d.ring(VfxLayers.GLOW_ADD, target.center.add(0.0, 0.06, 0.0), Vec3d(1.0, 0.0, 0.0), Vec3d(0.0, 0.0, 1.0),
                Placement.FIELD_RADIUS - 0.25, Placement.FIELD_RADIUS, withAlpha(color, 0f), withAlpha(color, 0.7f), segments = 64)
            return
        }
        for (cell in target.cells) d.box(VfxLayers.GLOW_ADD, Vec3d.ofCenter(cell), x, y, z, withAlpha(color, pulse))
    }
}

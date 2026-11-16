package dev.henny.hha_arius.gear.art

import dev.henny.hha_arius.gear.GearConfig
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.GearSounds
import dev.henny.hha_arius.gear.GearTasks
import dev.henny.hha_arius.gear.Resonance
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvents
import net.minecraft.util.ActionResult

/** Server-Einstieg der Handschuhe und der Schwerttechnik: Eingaben annehmen und an die Teile verteilen. */
object ArtServer {

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        Focus.init()
        Bolts.init()
        GauntletCombat.init()
        SpellCasting.init()
        SolvaneArt.init()

        ServerPlayNetworking.registerGlobalReceiver(ArtInputC2S.ID) { payload, context ->
            val player = context.player()
            val action = ArtAction.of(payload.action) ?: return@registerGlobalReceiver
            handle(player, action, payload.arg)
        }
        // Während Aufladen, Zielen, Kanalisieren, Gleiten und der Schwerttechnik keine normalen Angriffe.
        AttackEntityCallback.EVENT.register { player, world, _, _, _ ->
            if (world.isClient || player !is ServerPlayerEntity) return@register ActionResult.PASS
            when (Focus.busy(player)) {
                Focus.Busy.CHARGE, Focus.Busy.AIM, Focus.Busy.CHANNEL, Focus.Busy.GLIDE, Focus.Busy.ART, Focus.Busy.MOVE -> ActionResult.FAIL
                else -> ActionResult.PASS
            }
        }
    }

    private fun handle(player: ServerPlayerEntity, action: ArtAction, arg: Int) {
        if (!player.isAlive) return
        when (action) {
            ArtAction.ATTACK, ArtAction.KICK, ArtAction.PARRY -> GauntletCombat.input(player, action)
            ArtAction.MODE_TOGGLE -> toggleMode(player)
            ArtAction.SELECT -> {
                val spell = Spell.of(arg) ?: return
                SpellCasting.select(player, spell)
                val stack = player.mainHandStack
                if (GauntletsItem.isGauntlets(stack) && GauntletsItem.mode(stack) == GauntletMode.MELEE) toggleMode(player)
            }
            ArtAction.CAST_PRESS -> SpellCasting.press(player)
            ArtAction.CAST_RELEASE -> SpellCasting.release(player)
            ArtAction.CAST_CANCEL -> SpellCasting.cancel(player)
            ArtAction.ART_PRESS -> SolvaneArt.press(player)
            ArtAction.ART_RELEASE -> SolvaneArt.release(player, arg == 1)
            ArtAction.ART_CANCEL -> SolvaneArt.cancel(player)
        }
    }

    /** Moduswechsel: nur aus der Ruhe (nicht mitten in einer Aktion), kurze Wechselzeit, sichtbar für alle. */
    fun toggleMode(player: ServerPlayerEntity) {
        if (!GearConfig.on("gauntlets")) return
        val stack = player.mainHandStack
        if (!GauntletsItem.isGauntlets(stack) || !Focus.isFree(player) || Focus.drawing(player)) return
        val next = if (GauntletsItem.mode(stack) == GauntletMode.MELEE) GauntletMode.MAGIC else GauntletMode.MELEE
        stack.set(GearItems.GAUNTLET_MODE, next.ordinal)
        Focus.begin(player, Focus.Busy.MOVE, GearTasks.now + ArtTimings.MODE_SWITCH)
        ArtNet.anim(player, Anim.MODE_SWITCH, ArtTimings.MODE_SWITCH, next.ordinal)
        val pitch = if (next == GauntletMode.MAGIC) 1.6f else 1.1f
        GearSounds.play(player.entityWorld, player.entityPos, "gauntlet.mode", SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), 0.6f, pitch)
        player.sendMessage(net.minecraft.text.Text.translatable("hha_arius.msg.art.mode_${next.name.lowercase()}"), true)
        SpellCasting.markDirty(player)
        Resonance.sync(player)
    }
}

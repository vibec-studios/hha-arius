package dev.henny.hha_arius.gear

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.world.World

/**
 * Frostspeicher – die Manabank (seit 1.4.2, Crix: „wenn man über Nacht an der Monsterfarm steht und richtig viele
 * tötet, soll man sich das abspeichern können“). Ein Gefäß im Inventar sammelt Resonanz:
 * * jedes Wesen, das der Spieler tötet (auch mit Pfeil oder Fallen-Farm-Schlag), gibt `bank_per_kill` + seine
 *   maximale Gesundheit × `bank_per_kill_health` – das funktioniert ohne Rüstung und Waffen;
 * * was der Resonanzbalken über sein Maximum hinaus gewinnen würde, fließt hinein.
 *
 * Ausgegeben wird es automatisch: Reicht der Balken für eine Technik nicht, deckt der Speicher den Rest
 * ([Resonance.spend]); Rechtsklick füllt den Balken auf. Litematica-Baupläne bezahlt man nur aus dem Speicher
 * ([dev.henny.hha_arius.gear.art.Blueprints]). Mehrere Speicher werden der Reihe nach gefüllt.
 */
object FrostBank {

    fun capacity(): Int = runCatching { GearConfig.num("bank_capacity").toInt() }.getOrDefault(100_000).coerceAtLeast(1)

    fun amount(stack: ItemStack): Int = stack.getOrDefault(GearItems.BANK_AMOUNT, 0)

    private fun banks(player: PlayerEntity): List<ItemStack> {
        val inv = player.inventory
        val out = ArrayList<ItemStack>()
        for (i in 0 until inv.size()) {
            val s = inv.getStack(i)
            if (s.isOf(GearItems.FROST_BANK)) out.add(s)
        }
        return out
    }

    fun total(player: PlayerEntity): Long = banks(player).sumOf { amount(it).toLong() }

    /** Legt bis zu [n] ab; liefert, was angenommen wurde. */
    fun deposit(player: ServerPlayerEntity, n: Int): Int {
        if (n <= 0) return 0
        var left = n
        val cap = capacity()
        for (s in banks(player)) {
            val have = amount(s)
            val put = minOf(left, cap - have)
            if (put > 0) {
                s.set(GearItems.BANK_AMOUNT, have + put)
                left -= put
            }
            if (left <= 0) break
        }
        return n - left
    }

    /** Nimmt genau [n] heraus, wenn genug da ist. */
    fun withdraw(player: ServerPlayerEntity, n: Long): Boolean {
        if (n <= 0) return true
        if (total(player) < n) return false
        var left = n
        for (s in banks(player).sortedBy { amount(it) }) {
            val take = minOf(left, amount(s).toLong()).toInt()
            s.set(GearItems.BANK_AMOUNT, amount(s) - take)
            left -= take
            if (left <= 0) break
        }
        return true
    }

    fun init() {
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source ->
            if (entity is PlayerEntity) return@register
            val attacker = source.attacker ?: (source.source as? ProjectileEntity)?.owner
            val player = attacker as? ServerPlayerEntity ?: return@register
            val gain = GearConfig.num("bank_per_kill") + entity.maxHealth * GearConfig.num("bank_per_kill_health")
            val took = deposit(player, gain.toInt().coerceAtLeast(1))
            if (took > 0 && player.entityWorld.time % 4L == 0L) {
                player.entityWorld.playSound(null, player.blockPos, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, net.minecraft.sound.SoundCategory.PLAYERS, 0.25f, 1.6f)
            }
        }
    }
}

class FrostBankItem(settings: Settings) : Item(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        val player = user as? ServerPlayerEntity ?: return ActionResult.SUCCESS
        val need = (Resonance.max() - Resonance.get(player)).toInt()
        if (need <= 0) {
            player.sendMessage(Text.translatable("hha_arius.msg.bank_full_bar").formatted(Formatting.AQUA), true)
            return ActionResult.FAIL
        }
        val give = minOf(need.toLong(), FrostBank.total(player)).toInt()
        if (give <= 0 || !FrostBank.withdraw(player, give.toLong())) {
            player.sendMessage(Text.translatable("hha_arius.msg.bank_empty").formatted(Formatting.GRAY), true)
            return ActionResult.FAIL
        }
        Resonance.fill(player, give.toFloat())
        player.entityWorld.playSound(null, player.blockPos, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.3f)
        player.sendMessage(Text.translatable("hha_arius.msg.bank_drawn", give, FrostBank.total(player)).formatted(Formatting.AQUA), true)
        return ActionResult.SUCCESS
    }

    override fun getName(stack: ItemStack): Text =
        Text.translatable("item.hha_arius.frost_bank.named", FrostBank.amount(stack), FrostBank.capacity())

    override fun isItemBarVisible(stack: ItemStack): Boolean = true

    override fun getItemBarStep(stack: ItemStack): Int =
        (13.0 * FrostBank.amount(stack) / FrostBank.capacity()).toInt().coerceIn(0, 13)

    override fun getItemBarColor(stack: ItemStack): Int = 0x8FD8FF
}

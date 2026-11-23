package dev.henny.hha_arius.boss

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.command.CommandSource
import net.minecraft.item.ItemStack
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Admin-Befehle (OP 2): `/hhaarius start` (echter Kampf ohne Siegel), `/hhaarius stage <1-4>` (Training eines
 * Abschnitts), `/hhaarius stop`, `/hhaarius info`, `/hhaarius skip` (aktuellen Abschnitt beenden),
 * `/hhaarius test [on|off]` (Testmodus der Ausrüstung: unendliche Resonanz, kaum Abklingzeit), sowie
 * `/arius test <target|list|stop>` zum gezielten Testen einzelner Phasen, Cutscenes, QTEs und Jagdabschnitte.
 */
object AriusCommands {

    fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("arius")
                    .requires(CommandManager.requirePermissionLevel<ServerCommandSource>(CommandManager.GAMEMASTERS_CHECK))
                    .then(
                        CommandManager.literal("test")
                            .then(CommandManager.literal("list").executes { ctx -> listTargets(ctx.source) })
                            .then(CommandManager.literal("stop").executes { ctx -> stopTarget(ctx.source) })
                            .then(
                                CommandManager.argument("target", StringArgumentType.word())
                                    .suggests { _, builder -> CommandSource.suggestMatching(AriusTestTarget.ids(), builder) }
                                    .executes { ctx -> startTarget(ctx.source, StringArgumentType.getString(ctx, "target")) },
                            ),
                    ),
            )
            dispatcher.register(
                CommandManager.literal("hhaarius")
                    .requires(CommandManager.requirePermissionLevel<ServerCommandSource>(CommandManager.GAMEMASTERS_CHECK))
                    .then(CommandManager.literal("start").executes { ctx ->
                        val p = ctx.source.playerOrThrow
                        if (Encounters.requestStart(p, ItemStack.EMPTY)) 1 else 0
                    })
                    .then(CommandManager.literal("stage").then(CommandManager.argument("stage", IntegerArgumentType.integer(1, 4)).executes { ctx ->
                        val p = ctx.source.playerOrThrow
                        if (Encounters.requestTraining(p, IntegerArgumentType.getInteger(ctx, "stage"))) 1 else 0
                    }))
                    .then(CommandManager.literal("stop").executes { ctx ->
                        Encounters.stop(ctx.source.server)
                        ctx.source.sendFeedback({ Text.literal("Eirholm: alle Begegnungen beendet.") }, true)
                        1
                    })
                    .then(CommandManager.literal("skip").executes { ctx -> skip(ctx.source) })
                    .then(CommandManager.literal("test")
                        .executes { ctx -> test(ctx.source, null) }
                        .then(CommandManager.literal("on").executes { ctx -> test(ctx.source, true) })
                        .then(CommandManager.literal("off").executes { ctx -> test(ctx.source, false) }))
                    .then(CommandManager.literal("info").executes { ctx ->
                        val list = Encounters.all()
                        if (list.isEmpty()) ctx.source.sendFeedback({ Text.literal("Eirholm: keine Begegnung.") }, false)
                        for (f in list) {
                            ctx.source.sendFeedback({
                                Text.literal(
                                    "Slot ${f.slot}: Phase ${f.phase}, Arius ${"%.0f".format(f.ariusHp)}/${"%.0f".format(f.ariusMax)}, " +
                                        "Nivaris ${"%.0f".format(f.dragonHp)}/${"%.0f".format(f.dragonMax)}, Spieler ${f.players().size}/${f.fighters.size}, " +
                                        "Änderungen ${f.edits.size}, Training ${f.training}",
                                )
                            }, false)
                        }
                        1
                    }),
            )
        }
    }

    private fun listTargets(source: ServerCommandSource): Int {
        source.sendFeedback({ Text.translatable("hha_arius.test.list_header", AriusTestTarget.entries.size).formatted(Formatting.AQUA, Formatting.BOLD) }, false)
        for (target in AriusTestTarget.entries) {
            source.sendFeedback({
                Text.literal("  ")
                    .append(Text.literal(target.id).formatted(Formatting.WHITE))
                    .append(Text.literal(" — ").formatted(Formatting.DARK_GRAY))
                    .append(Text.translatable(target.descKey).formatted(Formatting.GRAY))
            }, false)
        }
        return AriusTestTarget.entries.size
    }

    private fun startTarget(source: ServerCommandSource, id: String): Int {
        val player = source.playerOrThrow
        val target = AriusTestTarget.byId(id)
        if (target == null) {
            source.sendError(Text.translatable("hha_arius.test.unknown", id))
            return 0
        }
        if (!Encounters.requestTest(player, target)) return 0
        source.sendFeedback({ Text.translatable("hha_arius.test.started", target.id, Text.translatable(target.descKey)).formatted(Formatting.AQUA) }, true)
        return 1
    }

    private fun stopTarget(source: ServerCommandSource): Int {
        val player = source.playerOrThrow
        if (!Encounters.stopTest(player)) {
            source.sendFeedback({ Text.translatable("hha_arius.test.none_running").formatted(Formatting.GRAY) }, false)
            return 0
        }
        source.sendFeedback({ Text.translatable("hha_arius.test.stopped").formatted(Formatting.AQUA) }, true)
        return 1
    }

    private fun test(source: ServerCommandSource, on: Boolean?): Int {
        val p = source.playerOrThrow
        val now = on ?: !dev.henny.hha_arius.gear.art.ArtTest.on(p)
        dev.henny.hha_arius.gear.art.ArtTest.set(p, now)
        source.sendFeedback({ Text.translatable(if (now) "hha_arius.msg.test_on" else "hha_arius.msg.test_off") }, false)
        return 1
    }

    private fun skip(source: ServerCommandSource): Int {
        val p = source.playerOrThrow
        val f = Encounters.fightOf(p) ?: return 0
        when (f.phase) {
            EirholmFight.Phase.TEMPLE -> f.ariusHp = 0f
            EirholmFight.Phase.GLACIER -> f.dragonHp = 0f
            EirholmFight.Phase.VAULT -> f.ariusHp = 0f
            EirholmFight.Phase.FINALE -> f.ariusHp = 0f
        }
        return 1
    }
}

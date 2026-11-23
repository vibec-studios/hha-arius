package dev.henny.hha_arius.gametest

import dev.henny.hha_arius.boss.AriusEntity
import dev.henny.hha_arius.boss.AriusTestTarget
import dev.henny.hha_arius.boss.Encounters
import dev.henny.hha_arius.boss.Hold
import dev.henny.hha_arius.boss.Hunt
import dev.henny.hha_arius.boss.NivarisEntity
import dev.henny.hha_arius.boss.NivarisHeadEntity
import dev.henny.hha_arius.boss.Qte
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.Box

/**
 * Prüft `/arius test list`, `/arius test <target>` und `/arius test stop` für JEDES Testziel
 * sowie das direkte Umschalten ohne Relog und verifiziert am Ende null Restzustand.
 */
class TestCommandTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        // Bei gezielten Sichtprüfungen (`-Darius.verify=...`) überspringen, damit Bildläufe schnell bleiben.
        if (System.getProperty("arius.verify") != null) return

        context.worldBuilder().create().use { sp ->
            context.waitTicks(30)
            Qte.testAnswer = { true }
            try {
                // 1) `/arius test list` darf fehlerfrei laufen.
                sp.server.runCommand("arius test list")
                context.waitTicks(2)

                // 2) Jedes einzelne Testziel starten, anlaufen lassen und sauber stoppen.
                for (target in AriusTestTarget.entries) {
                    sp.server.runCommand("execute as @p run arius test ${target.id}")
                    val started = waitUntil(context, sp.server, maxTicks = 3000) { srv ->
                        val f = Encounters.all().firstOrNull { !it.ended } ?: return@waitUntil false
                        val p = srv.playerManager.playerList.firstOrNull() ?: return@waitUntil false
                        f.testTarget == target && p.entityWorld.registryKey == Encounters.WORLD && f.farReady
                    }
                    check(started) { "Target '${target.id}' did not enter active world state with farReady in time" }

                    // Einige Ticks im laufenden Ziel weiterlaufen lassen (fängt frühe Skript-Ausnahmen ab).
                    context.waitTicks(8)
                    sp.server.runOnServer<RuntimeException> { srv ->
                        val p = srv.playerManager.playerList.first()
                        check(p.entityWorld.registryKey == Encounters.WORLD) { "Player left Eirholm unexpectedly during '${target.id}'" }
                    }

                    sp.server.runCommand("execute as @p run arius test stop")
                    context.waitTicks(4)
                    assertCleanState(sp.server, "after stopping '${target.id}'")
                }

                // 3) Direktes Umschalten von einem laufenden Ziel auf ein anderes (ohne vorheriges stop).
                sp.server.runCommand("execute as @p run arius test phase1")
                check(waitUntil(context, sp.server, maxTicks = 200) { srv ->
                    val f = Encounters.all().firstOrNull { !it.ended }
                    val p = srv.playerManager.playerList.firstOrNull()
                    f?.testTarget == AriusTestTarget.PHASE1 && p?.entityWorld?.registryKey == Encounters.WORLD
                }) { "Direct switch test: phase1 did not start" }
                context.waitTicks(6)

                sp.server.runCommand("execute as @p run arius test glacier")
                check(waitUntil(context, sp.server, maxTicks = 200) { srv ->
                    val f = Encounters.all().firstOrNull { !it.ended }
                    val p = srv.playerManager.playerList.firstOrNull()
                    f?.testTarget == AriusTestTarget.GLACIER && p?.entityWorld?.registryKey == Encounters.WORLD
                }) { "Direct switch test: glacier did not cleanly replace phase1" }
                context.waitTicks(6)

                sp.server.runCommand("execute as @p run arius test stop")
                context.waitTicks(4)
                assertCleanState(sp.server, "after direct switch phase1 -> glacier -> stop")
            } finally {
                Qte.testAnswer = null
            }
        }
    }

    private fun waitUntil(
        context: ClientGameTestContext,
        server: net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext,
        maxTicks: Int,
        cond: (MinecraftServer) -> Boolean,
    ): Boolean {
        var ticks = 0
        while (ticks < maxTicks) {
            var ok = false
            server.runOnServer<RuntimeException> { srv -> ok = cond(srv) }
            if (ok) return true
            context.waitTicks(2)
            ticks += 2
        }
        return false
    }

    private fun assertCleanState(
        server: net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext,
        label: String,
    ) {
        server.runOnServer<RuntimeException> { srv ->
            val p = srv.playerManager.playerList.first()
            check(Encounters.all().isEmpty()) { "Leftover active fights $label: ${Encounters.all().size}" }
            check(Hunt.current == null) { "Leftover Hunt.current $label" }
            check(!Hold.isHeld(p)) { "Player still held by Hold $label" }
            check(!p.hasVehicle()) { "Player still riding a vehicle $label" }
            check(!Encounters.isExplorer(p)) { "Player still registered as explorer $label" }
            check(p.entityWorld.registryKey != Encounters.WORLD) { "Player was not returned home $label" }
            val world = srv.getWorld(Encounters.WORLD)
            if (world != null) {
                val box = Box(-600.0, -64.0, -300.0, 600.0, 320.0, 500.0)
                val leftovers = world.getOtherEntities(null, box) {
                    it is AriusEntity || it is NivarisEntity || it is NivarisHeadEntity
                }
                check(leftovers.isEmpty()) { "Leftover boss entities in Eirholm $label: ${leftovers.map { it.type }}" }
            }
        }
    }
}

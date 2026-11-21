package dev.henny.hha_arius.gametest

import dev.henny.hha_arius.boss.EirholmFight
import dev.henny.hha_arius.boss.Encounters
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.fabricmc.fabric.mixin.client.gametest.screenshot.DeltaTrackerDefaultValueAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import java.io.File

/**
 * Sichtprüfung im echten Client: startet eine Welt, fährt den Kampf per Befehl an und schreibt Standbilder.
 * Start: `./gradlew runClientGameTest -Darius.verify=<dragon|boss|hunt> -Darius.verify.out=<Ordner>`.
 * Ohne `arius.verify` passiert nichts; der normale Starttest bleibt unberührt.
 */
class VerifyKit(val ctx: ClientGameTestContext, val sp: TestSingleplayerContext, val out: File) {

    /** Hält den Spieler am Leben. Für Schadensmessungen auf false setzen. */
    var godMode = true

    fun cmd(s: String) = sp.server.runCommand(s)

    @Suppress("UNCHECKED_CAST")
    fun <T> server(f: (MinecraftServer) -> T): T {
        var r: Any? = null
        sp.server.runOnServer<RuntimeException> { r = f(it) }
        return r as T
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> client(f: (MinecraftClient) -> T): T {
        var r: Any? = null
        ctx.runOnClient<RuntimeException> { r = f(it) }
        return r as T
    }

    fun player(srv: MinecraftServer): ServerPlayerEntity = srv.playerManager.playerList.first()

    fun fight(): EirholmFight? = Encounters.all().firstOrNull { !it.ended }

    fun phase(): Int = server { fight()?.phase ?: -1 }

    fun log(s: String) {
        println("[verify] $s")
        File(out, "log.txt").appendText(s + "\n")
    }

    fun tick(n: Int = 1) {
        repeat(n) {
            if (godMode) sp.server.runOnServer<RuntimeException> { srv ->
                val p = srv.playerManager.playerList.firstOrNull() ?: return@runOnServer
                if (p.isAlive) {
                    p.health = p.maxHealth
                    p.frozenTicks = 0
                    p.hungerManager.foodLevel = 20
                }
            }
            ctx.waitTick()
        }
    }

    fun waitUntil(max: Int, every: Int = 5, cond: () -> Boolean): Boolean {
        var t = 0
        while (t < max) {
            if (cond()) return true
            tick(every)
            t += every
        }
        return cond()
    }

    /** Wartet, bis die Chunks gerendert sind; läuft die Frist ab, geht es trotzdem weiter. */
    fun settle(timeout: Int = 600) {
        try {
            sp.clientWorld.waitForChunksRender(true, timeout)
        } catch (e: Throwable) {
            log("chunks not fully rendered after $timeout ticks: ${e.message}")
        }
    }

    /** Standbild `<name>.png` im Ausgabeordner, mit HUD, so wie der Spieler es sieht. */
    fun shot(name: String, tickDelta: Float = 1f) {
        val file = File(out, "$name.png")
        client { c ->
            c.gameRenderer.render(DeltaTrackerDefaultValueAccessor.create(tickDelta), true)
            ScreenshotRecorder.takeScreenshot(c.framebuffer) { img -> img.use { it.writeTo(file) } }
        }
        ctx.waitTick()
    }

    /** [count] Standbilder im Abstand von [every] Ticks: `<name>_00.png`, `<name>_01.png`, ... */
    fun burst(name: String, count: Int, every: Int = 5) {
        for (i in 0 until count) {
            shot("${name}_%02d".format(i))
            tick(every)
        }
    }

    fun setup() {
        cmd("difficulty easy")
        cmd("time set 1000")
        cmd("hha set hha_arius.boss_build_budget 100000")
        for ((slot, item) in listOf(
            "armor.head" to "eirholm_helmet", "armor.chest" to "eirholm_chestplate", "armor.legs" to "eirholm_leggings",
            "armor.feet" to "eirholm_boots", "hotbar.0" to "solvane", "hotbar.1" to "arius_gauntlets",
        )) cmd("item replace entity @p $slot with hha_arius:$item")
        tick(20)
    }

    /** Startet den Trainingsabschnitt [stage] (1-4) und wartet, bis Arena und Fernlandschaft gebaut sind. */
    fun startStage(stage: Int): Boolean {
        cmd("execute as @p at @s run hhaarius stage $stage")
        waitUntil(4000, 10) { phase() >= EirholmFight.Phase.INTRO }
        val ready = waitUntil(8000, 20) { server { fight()?.farReady == true } }
        log("stage $stage: far ready=$ready phase=${phase()}")
        return ready
    }

    /** Startet die volle Begegnung und wartet, bis Arena und Fernlandschaft gebaut sind. */
    fun startFull(): Boolean {
        cmd("execute as @p at @s run hhaarius start")
        waitUntil(4000, 2) { phase() >= EirholmFight.Phase.INTRO }
        val ready = waitUntil(8000, 20) { server { fight()?.farReady == true } }
        log("full: far ready=$ready phase=${phase()}")
        return ready
    }

    companion object {
        fun run(context: ClientGameTestContext, name: String, body: VerifyKit.() -> Unit) {
            if (System.getProperty("arius.verify") != name) return
            val out = File(System.getProperty("arius.verify.out", "verify/$name"))
            out.mkdirs()
            context.getInput().resizeWindow(1280, 720)
            context.runOnClient<RuntimeException> { c ->
                c.options.viewDistance.value = System.getProperty("arius.verify.view", "16").toInt()
                c.options.fov.value = 70
            }
            context.worldBuilder().create().use { world ->
                val kit = VerifyKit(context, world, out)
                kit.settle()
                kit.setup()
                kit.body()
            }
        }
    }
}

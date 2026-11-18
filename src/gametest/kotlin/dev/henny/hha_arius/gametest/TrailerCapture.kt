package dev.henny.hha_arius.gametest

import dev.henny.hha.logic.boss.Cine
import dev.henny.hha_arius.AriusEntities
import dev.henny.hha_arius.boss.EirholmFight
import dev.henny.hha_arius.boss.Encounters
import dev.henny.hha_arius.gear.Resonance
import dev.henny.hha_arius.gear.art.ArtAction
import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.GauntletMode
import dev.henny.hha_arius.gear.art.GauntletsItem
import dev.henny.hha_arius.gear.art.Spell
import dev.henny.hha_arius.gear.art.WhiteHole
import dev.henny.hha_arius.gear.client.art.ArtAnims
import dev.henny.hha_arius.gear.client.art.ArtClient
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.fabricmc.fabric.mixin.client.gametest.screenshot.DeltaTrackerDefaultValueAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.Perspective
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Nimmt Rohmaterial für den Gameplay-Trailer im echten Spiel auf (nur mit `-Darius.trailer=true`, sonst sofort fertig).
 *
 * Je Spiel-Tick werden [sub] Bilder mit Zwischenständen (tickDelta k/sub) gerendert und direkt an ffmpeg gereicht; so
 * entstehen flüssige 60-fps-Clips, obwohl das Spiel im Gametest Tick für Tick läuft. HUD aus, Fenster 1920×1080.
 * Kameras laufen über HHAs Cine-Kamera (dieselbe wie in den Cutscenes), die Kampf-Cutscenes nimmt der Test so auf, wie
 * sie im Spiel ablaufen. Keine Spielmechanik wird verändert: nur Befehle, Config-Werte und Eingaben wie ein Spieler.
 *
 * Ausgabe: `-Darius.trailer.out=<Ordner>` (Clips `*.mp4` + `timeline.txt`). Teile: `-Darius.trailer.parts=land,gear,cut,fight`.
 */
class TrailerCapture : FabricClientGameTest {

    private lateinit var ctx: ClientGameTestContext
    private lateinit var sp: TestSingleplayerContext
    private val out = File(System.getProperty("arius.trailer.out", "trailer"))
    private val sub = System.getProperty("arius.trailer.sub", "3").toInt().coerceIn(1, 4)
    private val parts = System.getProperty("arius.trailer.parts", "land,gear,cut,fight").split(',').map { it.trim() }.toSet()
    private val ffmpeg = System.getProperty("arius.trailer.ffmpeg", "ffmpeg")
    private val timeline = StringBuilder()
    /** Clips, die schon gut im Kasten sind: der Ablauf läuft durch, aber ohne Bilder (schnell). */
    private val skipClips = System.getProperty("arius.trailer.skip", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val clearSubtitles = System.getProperty("arius.trailer.subtitles", "off") == "off"

    /** Standbilder statt Video (-Darius.trailer.stills=true): je Einstellung zwei PNGs bei 40 % und 75 %. */
    private val stills = System.getProperty("arius.trailer.stills") == "true"
    private var clock = 0L

    override fun runTest(context: ClientGameTestContext) {
        if (System.getProperty("arius.trailer") != "true") return
        ctx = context
        out.mkdirs()
        context.getInput().resizeWindow(1920, 1080)
        context.runOnClient<RuntimeException> { c ->
            c.options.hudHidden = true
            c.options.viewDistance.value = 20
            c.options.fov.value = 70
            c.options.perspective = Perspective.THIRD_PERSON_BACK
        }
        context.worldBuilder().create().use { world ->
            sp = world
            settle(1200)
            try {
                setup()
                warmup()
                if ("land" in parts) landscape()
                if ("gear" in parts || "spells" in parts) gear()
                if ("cut" in parts) overworldCut()
                if ("catalog" in parts) catalog()
                if ("fight" in parts) fight()
            } finally {
                File(out, "timeline.txt").writeText(timeline.toString())
            }
        }
    }

    // --- Helfer: Server, Client, Zeit ---------------------------------------------------------------------------------

    private fun log(s: String) {
        val line = "[t=$clock] $s"
        println("[TrailerCapture] $line")
        timeline.append(line).append('\n')
        File(out, "timeline.txt").writeText(timeline.toString())
    }

    private fun cmd(s: String) = sp.server.runCommand(s)

    @Suppress("UNCHECKED_CAST")
    private fun <T> server(f: (MinecraftServer) -> T): T {
        var r: Any? = null
        sp.server.runOnServer<RuntimeException> { r = f(it) }
        return r as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> client(f: (MinecraftClient) -> T): T {
        var r: Any? = null
        ctx.runOnClient<RuntimeException> { r = f(it) }
        return r as T
    }

    private fun player(srv: MinecraftServer): ServerPlayerEntity = srv.playerManager.playerList.first()

    private fun fightNow(): EirholmFight? = Encounters.all().firstOrNull { !it.ended }

    private fun phase(): Int = server { fightNow()?.phase ?: -1 }

    private fun tick(n: Int = 1) {
        for (i in 0 until n) {
            keepAlive()
            ctx.waitTick()
            clock++
        }
    }

    /** Auf fertig gebaute Chunks warten; läuft die Frist ab, trotzdem weiter (große Sichtweite wird selten ganz fertig). */
    private fun settle(timeout: Int) {
        try {
            sp.clientWorld.waitForChunksRender(true, timeout)
        } catch (e: Throwable) {
            log("chunks not fully rendered after $timeout ticks: ${e.message}")
        }
    }

    private fun waitUntil(max: Int, every: Int = 5, cond: () -> Boolean): Boolean {
        var t = 0
        while (t < max) {
            if (cond()) return true
            tick(every)
            t += every
        }
        return cond()
    }

    /** Spieler unverwundbar halten (Resistenz allein reicht nicht für jeden Bosstreffer). */
    private fun keepAlive() {
        sp.server.runOnServer<RuntimeException> { srv ->
            val p = srv.playerManager.playerList.firstOrNull() ?: return@runOnServer
            if (p.isAlive) {
                p.health = p.maxHealth
                p.frozenTicks = 0
                p.hungerManager.foodLevel = 20
            }
        }
    }

    // --- Aufnahme ------------------------------------------------------------------------------------------------------

    /**
     * Nimmt [ticks] Ticks als Clip [name] auf (oder bis [until] wahr ist). [step] läuft vor jedem Tick (Steuerung,
     * Kameras); die Bilder entstehen danach mit den Zwischenständen zwischen dem letzten und dem neuen Tick.
     */
    private fun rec(name: String, ticks: Int, s: Int = sub, until: (() -> Boolean)? = null, step: (Int) -> Unit = {}) {
        if (name in skipClips) {
            log("SKIP $name (läuft ohne Aufnahme durch)")
            var i = 0
            while (i < ticks) {
                step(i)
                tick()
                i++
                if (until != null && i % 5 == 0 && until()) break
            }
            return
        }
        if (stills) {
            log("STILLS $name")
            val marks = setOf((ticks * 0.4).toInt(), (ticks * 0.75).toInt())
            var j = 0
            while (j < ticks) {
                step(j)
                keepAlive()
                ctx.waitTick()
                clock++
                if (j in marks) {
                    val file = File(out, "${name}_${if (j == marks.min()) "a" else "b"}.png")
                    ctx.runOnClient<RuntimeException> { c ->
                        if (clearSubtitles) dev.henny.hha_arius.client.fx.Subtitles.clear()
                        c.gameRenderer.render(DeltaTrackerDefaultValueAccessor.create(1f), true)
                        ScreenshotRecorder.takeScreenshot(c.framebuffer) { img -> img.use { it.writeTo(file) } }
                    }
                }
                j++
                if (until != null && j % 5 == 0 && until()) break
            }
            return
        }
        val writer = ClipWriter(File(out, "$name.mp4"), 20 * s, ffmpeg)
        val phases = StringBuilder()
        log("REC $name start phase=${phase()} fps=${20 * s} max=$ticks")
        val started = System.nanoTime()
        var i = 0
        var lastPhase = -99
        while (i < ticks) {
            step(i)
            keepAlive()
            ctx.waitTick()
            clock++
            ctx.runOnClient<RuntimeException> { c ->
                if (clearSubtitles) dev.henny.hha_arius.client.fx.Subtitles.clear()
                for (k in 1..s) {
                    c.gameRenderer.render(DeltaTrackerDefaultValueAccessor.create(k / s.toFloat()), true)
                    ScreenshotRecorder.takeScreenshot(c.framebuffer) { img -> writer.push(img) }
                }
            }
            val ph = phase()
            if (ph != lastPhase) {
                phases.append("tick $i frame ${i * s} phase $ph\n")
                lastPhase = ph
            }
            i++
            if (until != null && i % 5 == 0 && until()) break
        }
        // Ausstehende Rückrufe abholen (läuft höchstens wenige Ticks weiter).
        var guard = 0
        while (writer.received < i * s && guard++ < 200) ctx.waitTick()
        writer.close()
        File(out, "$name.phases.txt").writeText(phases.toString())
        val secs = (System.nanoTime() - started) / 1e9
        log("REC $name done ticks=$i frames=${writer.received} ${writer.width}x${writer.height} ${writer.format} wall=${"%.0f".format(secs)}s")
    }

    // --- Kamera (HHA-Cine) ---------------------------------------------------------------------------------------------

    private fun cineOn() = server { Cine.start(listOf(player(it))) }

    private fun cineOff() = server { Cine.end(listOf(player(it))) }

    private fun cam(from: Vec3d, to: Vec3d, lookFrom: Vec3d, lookTo: Vec3d, ticks: Int, fov0: Double = 70.0, fov1: Double = fov0,
                    lookAt: ((MinecraftServer) -> Entity?)? = null, off: Double = 1.6) = server { srv ->
        Cine.camera(listOf(player(srv)), from, to, lookFrom, lookTo, ticks, fov0, fov1, lookAt?.invoke(srv), off)
    }

    private fun orbit(center: Vec3d, r0: Double, r1: Double, h0: Double, h1: Double, a0: Double, a1: Double, ticks: Int,
                      fov0: Double = 60.0, fov1: Double = fov0, lookAt: ((MinecraftServer) -> Entity?)? = null, off: Double = 1.2) = server { srv ->
        Cine.orbit(listOf(player(srv)), center, r0, h0, a0, a1, ticks, fov0, fov1, lookAt?.invoke(srv), off, r1, h1)
    }

    private val self: (MinecraftServer) -> Entity? = { player(it) }
    private val ariusS: (MinecraftServer) -> Entity? = { fightNow()?.arius }
    private val dragonS: (MinecraftServer) -> Entity? = { fightNow()?.nivaris }

    private fun serverPos(e: (MinecraftServer) -> Entity?): Vec3d? = server { e(it)?.entityPos }

    // --- Spielersteuerung (Client) -------------------------------------------------------------------------------------

    private fun find(c: MinecraftClient, type: EntityType<*>): Entity? = c.world?.entities?.firstOrNull { it.type == type && it.isAlive }

    private fun nearest(c: MinecraftClient, type: EntityType<*>): Entity? {
        val p = c.player ?: return null
        return c.world?.entities?.filter { it.type == type && it.isAlive }?.minByOrNull { it.squaredDistanceTo(p) }
    }

    /** Blick zum Ziel drehen und bis auf [keep] Blöcke herangehen (nicht während einer Cutscene oder eines Halts). */
    private fun face(c: MinecraftClient, target: Entity, keep: Double = 2.6, speed: Double = 0.2, turn: Float = 0.35f, move: Boolean = true) {
        val p = c.player ?: return
        val t = target.boundingBox.center
        val dx = t.x - p.x
        val dz = t.z - p.z
        val dy = t.y - p.eyeY
        val d = hypot(dx, dz)
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, d))).toFloat().coerceIn(-50f, 50f)
        p.yaw = MathHelper.lerpAngleDegrees(turn, p.yaw, yaw)
        p.pitch = MathHelper.lerp(turn, p.pitch, pitch)
        p.headYaw = p.yaw
        if (move && d > keep) {
            val v = p.velocity
            p.setVelocity(dx / d * speed, v.y, dz / d * speed)
        }
    }

    private fun anim(c: MinecraftClient): Anim? = c.player?.let { ArtAnims.get(it.id)?.anim }

    /** Kombo Jab → Cross → Haken → Drehtritt, mit Pause. Liefert true, wenn eine Kombo durch ist. */
    private inner class Combo(private val pause: Int = 18) {
        private val actions = listOf(ArtAction.ATTACK, ArtAction.ATTACK, ArtAction.ATTACK, ArtAction.KICK)
        private val anims = listOf(Anim.JAB, Anim.CROSS, Anim.HOOK, Anim.SPIN_KICK)
        private var k = 0
        private var rest = 0
        private var spent = 0
        fun step(c: MinecraftClient, inRange: Boolean) {
            if (rest > 0) { rest--; return }
            if (!inRange && k == 0) return
            if (k < 4 && anim(c) == anims[k]) { k++; spent = 0 }
            if (k >= 4) {
                k = 0
                rest = pause
                return
            }
            if (++spent > 40) { k = 0; rest = 10; spent = 0; return }
            ArtClient.send(actions[k])
        }
    }

    private fun hotbar(slot: Int) {
        ctx.getInput().pressKey { it.hotbarKeys[slot] }
        tick(2)
    }

    private fun gauntletMode(want: GauntletMode) {
        hotbar(1)
        val now = client { c -> c.player?.let { GauntletsItem.mode(it.mainHandStack) } }
        if (now != want) {
            client { ArtClient.send(ArtAction.MODE_TOGGLE) }
            tick(12)
        }
    }

    private fun select(spell: Spell) {
        client { ArtClient.send(ArtAction.SELECT, spell.ordinal) }
        tick(2)
    }

    private fun fillResonance() = server { srv -> Resonance.gain(player(srv), 1000.0) }

    // --- Ablauf ------------------------------------------------------------------------------------------------------

    private fun setup() {
        cmd("difficulty easy")
        cmd("time set 1000")
        cmd("effect give @p minecraft:resistance infinite 255 true")
        cmd("effect give @p minecraft:saturation infinite 10 true")
        for ((k, v) in listOf(
            "boss_damage_scale" to "0.05", "boss_build_budget" to "100000",
        )) cmd("hha set hha_arius.$k $v")
        // Seit 1.2.1: Testmodus statt Ruhepunkt-Werten (unendliche Resonanz, kurze Abklingzeiten).
        cmd("execute as @p run hhaarius test on")
        // Bauwerke (Eiswand, Brücke, Plattform) und Gleiter sind in der Arena-Dimension sonst aus (Toggle, kein Zahlenwert).
        cmd("hha toggle hha_arius.art_constructs_in_arena true")
        equip()
        tick(20)
    }

    private fun equip() {
        cmd("clear @p")
        for ((slot, item) in listOf(
            "armor.head" to "eirholm_helmet", "armor.chest" to "eirholm_chestplate", "armor.legs" to "eirholm_leggings",
            "armor.feet" to "eirholm_boots", "hotbar.0" to "solvane", "hotbar.1" to "arius_gauntlets",
        )) cmd("item replace entity @p $slot with hha_arius:$item")
    }

    /** Erster Lauf im Training: baut Arena und Fernlandschaft (Nadel, Schlucht, Gletscher), danach Abbruch. */
    private fun warmup() {
        log("warmup")
        cmd("execute as @p at @s run hhaarius stage 1")
        waitUntil(4000, 10) { phase() >= EirholmFight.Phase.INTRO }
        val ready = waitUntil(8000, 20) { server { fightNow()?.farReady == true } }
        log("far ready=$ready phase=${phase()}")
        cmd("hhaarius stop")
        tick(40)
    }

    private val eir = "hha_arius:highland"
    private fun at(x: Double, y: Double, z: Double) = Vec3d(x, 160.0 + y, z)

    /** Arius-Wetterstimmung am Client setzen (ohne Kampf ist sie aus; der Kampf schickt dieselben Pakete). */
    private fun atmos(mode: Int) = client { dev.henny.hha_arius.client.fx.Atmos.handle(dev.henny.hha_arius.net.AtmosS2C(mode, 1)) }

    private fun enterArena(mode: String, x: Double, y: Double, z: Double, yaw: Float = 0f, settleTicks: Int = 400) {
        cmd("gamemode creative @p")
        cmd("execute in $eir run tp @p $x ${160.0 + y} $z $yaw 0")
        tick(20)
        settle(settleTicks)
        tick(40)
        cmd("gamemode $mode @p")
        atmos(0)
        tick(5)
    }

    /** Totalen: Heiligtum im Schnee, Gletscher, Schlucht, die Nadel. Kein Kampf, Spieler unsichtbar im Boden. */
    private fun landscape() {
        log("landscape")
        enterArena("spectator", 0.5, -3.0, -2.5, settleTicks = 1200)
        cmd("effect give @p minecraft:invisibility infinite 0 true")
        atmos(3) // Enthüllung: tiefblauer Himmel, weite Sicht (wie im Kampf beim Auftritt)
        tick(60)
        cineOn()
        // 1) Hoch über den Klippen im Norden: über die Tempelkuppel nach Süden, die Nadel wächst aus dem Dunst.
        cam(at(-26.0, 98.0, -116.0), at(-8.0, 80.0, -80.0), at(0.0, 10.0, -30.0), at(0.0, 80.0, 128.0), 170, 60.0, 54.0)
        rec("land_totale", 170)
        // 2) Das Heiligtum im Schnee: seitlich am Tempel vorbei, Blick auf das Tor.
        cam(at(44.0, 13.0, -10.0), at(36.0, 9.0, -20.0), at(0.0, 8.0, -50.0), at(0.0, 6.0, -48.0), 130, 58.0, 54.0)
        rec("land_temple", 130)
        // 3) Tief über die Schlucht, Blick hebt sich zur Spitze.
        cam(at(-8.0, -6.0, 46.0), at(4.0, -14.0, 96.0), at(0.0, 40.0, 128.0), at(0.0, 175.0, 128.0), 160, 72.0, 64.0)
        rec("land_canyon", 160)
        // 4) Vom Plateaurand hebt sich die Kamera, die Nadel steht über der Schlucht.
        cam(at(16.0, 3.0, 30.0), at(8.0, 34.0, 44.0), at(0.0, 50.0, 128.0), at(0.0, 150.0, 128.0), 150, 62.0, 52.0)
        rec("land_needle", 150)
        // 5) Gletscherzug, flach über den Seracs.
        cam(at(-70.0, 8.0, -10.0), at(-60.0, 12.0, 30.0), at(0.0, 20.0, 60.0), at(0.0, 60.0, 128.0), 140, 66.0, 60.0)
        rec("land_glacier", 140)
        cineOff()
        cmd("effect clear @p minecraft:invisibility")
        tick(10)
    }

    private fun summonDummies(n: Int, cx: Double, cz: Double, r: Double, y: Double = 0.0, noAi: Boolean = false) {
        for (i in 0 until n) {
            val a = i / n.toDouble() * Math.PI * 2
            val x = cx + cos(a) * r
            val z = cz + sin(a) * r
            val nbt = if (noAi) "{NoAI:1b,PersistenceRequired:1b,Rotation:[180f,0f]}" else "{PersistenceRequired:1b}"
            cmd("execute in $eir run summon minecraft:husk $x ${160.0 + y} $z $nbt")
        }
        cmd("effect give @e[type=husk] minecraft:slowness infinite 3 true")
        cmd("effect give @e[type=husk] minecraft:weakness infinite 10 true")
        cmd("effect give @e[type=husk] minecraft:resistance infinite 1 true")
    }

    private fun killDummies() = cmd("kill @e[type=minecraft:husk]")

    /** Rüstung, Handschuhe, Zauberrad, Zauber, Klarer Schnitt und Weißes Loch auf dem Plateau vor der Nadel. */
    private fun gear() {
        log("gear")
        // "gear" = alles, "spells" = nur Zauber, Bauwerke, Gleiter, Klarer Schnitt (Nachlauf).
        val all = "gear" in parts
        cmd("time set 1000")
        enterArena("survival", 0.5, 0.0, 6.5, 0f)
        equip()
        fillResonance()
        tick(20)
        val p0 = server { player(it).entityPos }
        val me = { server { player(it).entityPos } }
        var p: Vec3d

        if (all) {
            // Rüstung: langsamer Kreis um den Spieler, Solvane in der Hand.
            hotbar(0)
            cineOn()
            orbit(p0, 3.4, 2.6, 0.9, 1.5, 120.0, 30.0, 120, 50.0, 44.0, self, 1.25)
            rec("gear_armor", 120) { client { c -> c.player?.let { it.yaw = 0f; it.headYaw = 0f; it.pitch = 5f } } }
            cineOff()

            // Handschuhe: Kombo gegen Husks, seitliche Kamera tief.
            gauntletMode(GauntletMode.MELEE)
            summonDummies(1, p0.x, p0.z + 2.4, 0.0)
            tick(10)
            val combo = Combo(14)
            cineOn()
            cam(p0.add(3.4, 0.7, 0.6), p0.add(3.0, 0.9, 2.0), p0.add(0.0, 1.1, 1.2), p0.add(0.0, 1.1, 1.4), 140, 56.0, 50.0)
            rec("gear_combo_side", 140) { _ -> client { c -> nearest(c, EntityType.HUSK)?.let { h -> face(c, h, 2.1); combo.step(c, (c.player?.distanceTo(h) ?: 9f) < 3.2f) } } }
            cineOff()
            rec("gear_combo_f5", 140) { _ -> client { c -> nearest(c, EntityType.HUSK)?.let { h -> face(c, h, 2.1); combo.step(c, (c.player?.distanceTo(h) ?: 9f) < 3.2f) } } }
            killDummies()
            tick(30)

            // Zauberrad: Taste halten, Maus kreist.
            gauntletMode(GauntletMode.MAGIC)
            tick(4)
            client { _ ->
                ArtClient.techKey()!!.setBoundKey(net.minecraft.client.util.InputUtil.Type.KEYSYM.createFromCode(org.lwjgl.glfw.GLFW.GLFW_KEY_R))
                net.minecraft.client.option.KeyBinding.updateKeysByCode()
            }
            ctx.getInput().holdKey(ArtClient.techKey()!!)
            rec("gear_wheel", 50, step = { i ->
                val a = i / 50.0 * Math.PI * 2.0
                ctx.getInput().setCursorPos(960.0 + cos(a) * 300.0, 540.0 + sin(a) * 300.0)
            })
            ctx.getInput().releaseKey(ArtClient.techKey()!!)
            tick(10)
            client { it.currentScreen?.close() }
            tick(4)
        }

        // Zauber gegen eine Reihe Husks in Richtung Nadel, Kamera über die Schulter.
        gauntletMode(GauntletMode.MAGIC)
        p = me()
        summonDummies(4, p.x, p.z + 10.0, 2.5)
        tick(20)
        fun aimAtHusks(c: MinecraftClient) { nearest(c, EntityType.HUSK)?.let { face(c, it, 99.0, move = false, turn = 0.5f) } }
        fun cast(spell: Spell, name: String, ticks: Int, pressAt: Int, releaseAt: Int) {
            select(spell)
            fillResonance()
            rec(name, ticks) { i ->
                client { c ->
                    aimAtHusks(c)
                    if (i == pressAt) ArtClient.send(ArtAction.CAST_PRESS)
                    if (i == releaseAt) ArtClient.send(ArtAction.CAST_RELEASE)
                }
            }
        }
        cineOn()
        cam(p.add(-1.3, 2.1, -2.8), p.add(-1.0, 1.9, -2.2), p.add(0.2, 1.4, 6.0), p.add(0.0, 1.2, 10.0), 60, 60.0, 56.0)
        cast(Spell.WIND_BLADE, "gear_wind_blade", 60, 8, 9)
        cam(p.add(1.4, 1.6, -2.4), p.add(1.1, 1.7, -1.6), p.add(-0.2, 1.3, 6.0), p.add(0.0, 1.2, 10.0), 60, 58.0, 54.0)
        cast(Spell.FROST_SHARDS, "gear_frost_shards", 60, 8, 9)
        cam(p.add(3.2, 1.2, 1.2), p.add(2.6, 1.5, 3.4), p.add(0.0, 1.4, 2.0), p.add(0.0, 1.3, 9.0), 90, 56.0, 60.0)
        cast(Spell.YANG_ORB, "gear_yang_orb", 90, 6, 40)
        // Seit 1.3.1/1.4.0: Wirbelsturm und Gletscherlanze
        cam(p.add(4.5, 2.0, 1.0), p.add(4.0, 2.6, 3.5), p.add(0.0, 1.5, 5.0), p.add(0.0, 2.0, 9.0), 90, 60.0, 58.0)
        cast(Spell.TORNADO, "gear_tornado", 90, 6, 7)
        cam(p.add(-2.4, 1.8, -2.6), p.add(-1.8, 1.7, -1.8), p.add(0.2, 1.4, 6.0), p.add(0.0, 1.2, 12.0), 80, 58.0, 54.0)
        cast(Spell.GLACIER_LANCE, "gear_glacier_lance", 80, 6, 44)
        cineOff()
        killDummies()
        tick(30)

        // Eiswand: zielen, setzen; Kamera seitlich.
        p = me()
        select(Spell.ICE_WALL)
        fillResonance()
        cineOn()
        cam(p.add(5.0, 2.2, -1.0), p.add(4.2, 2.6, 0.5), p.add(0.0, 1.0, 4.0), p.add(0.0, 1.6, 4.5), 80, 60.0, 56.0)
        rec("gear_ice_wall", 80) { i ->
            client { c ->
                c.player?.let { it.yaw = 0f; it.pitch = 32f; it.headYaw = 0f }
                if (i == 8) ArtClient.send(ArtAction.CAST_PRESS)
                if (i == 26) ArtClient.send(ArtAction.CAST_RELEASE)
            }
        }
        cineOff()
        tick(10)

        // Lichtbrücke: der Spieler schwebt über dem Plateau, die Brücke wächst Richtung Nadel.
        cmd("gamemode creative @p")
        cmd("execute in $eir run tp @p -6.5 ${160.0 + 7.0} -4.5 0 10")
        tick(4)
        client { c -> c.player?.let { it.abilities.flying = true; it.sendAbilitiesUpdate() } }
        tick(10)
        p = me()
        select(Spell.YANG_BRIDGE)
        fillResonance()
        cineOn()
        cam(p.add(7.0, 1.0, -3.0), p.add(7.5, 2.5, 5.0), p.add(0.0, 0.0, 3.0), p.add(0.0, -0.5, 12.0), 90, 62.0, 58.0)
        rec("gear_bridge", 90) { i ->
            client { c ->
                c.player?.let { it.yaw = 0f; it.pitch = 20f; it.headYaw = 0f; it.setVelocity(0.0, 0.0, 0.0) }
                if (i == 8) ArtClient.send(ArtAction.CAST_PRESS)
                if (i == 26) ArtClient.send(ArtAction.CAST_RELEASE)
            }
        }
        cineOff()
        // Plattform: im Flug nach unten schauen, sie entsteht unter den Füßen.
        cmd("execute in $eir run tp @p 8.5 ${160.0 + 9.0} -2.5 0 75")
        tick(4)
        p = me()
        select(Spell.YANG_PLATFORM)
        fillResonance()
        cineOn()
        cam(p.add(-4.5, 0.6, 4.5), p.add(-5.0, 1.6, 3.0), p.add(0.0, 0.0, 0.0), p.add(0.0, 0.4, 0.0), 70, 60.0, 56.0)
        rec("gear_platform", 70) { i ->
            client { c ->
                c.player?.let { it.yaw = 0f; it.pitch = 75f; it.headYaw = 0f; it.setVelocity(0.0, 0.0, 0.0) }
                if (i == 6) ArtClient.send(ArtAction.CAST_PRESS)
                if (i == 22) ArtClient.send(ArtAction.CAST_RELEASE)
            }
        }
        cineOff()

        // Gleiter: über der Schlucht Richtung Nadel, Kamera fährt seitlich mit.
        client { c -> c.player?.let { it.abilities.flying = false; it.sendAbilitiesUpdate() } }
        cmd("gamemode survival @p")
        cmd("execute in $eir run tp @p 0.5 ${160.0 + 24.0} 32.5 180 12")
        select(Spell.YANG_GLIDE)
        fillResonance()
        tick(2)
        client { ArtClient.send(ArtAction.CAST_PRESS) }
        tick(1)
        client { ArtClient.send(ArtAction.CAST_RELEASE) }
        tick(2)
        cineOn()
        cam(at(7.5, 25.5, 36.0), at(8.5, 14.0, -22.0), at(0.5, 24.0, 30.0), at(0.5, 14.0, -26.0), 110, 62.0, 66.0, self, 0.8)
        rec("gear_glide", 110) { client { c -> c.player?.let { it.yaw = 180f; it.pitch = 12f; it.headYaw = 180f } } }
        cineOff()
        tick(60)

        // Klarer Schnitt gegen einen Husk, Seitenansicht nah, Zeitlupe-tauglich.
        enterArena("survival", 0.5, 0.0, 6.5, 0f)
        p = me()
        summonDummies(1, p.x, p.z + 3.0, 0.0)
        hotbar(0)
        tick(100)
        fillResonance()
        cineOn()
        cam(p.add(3.6, 1.1, 0.6), p.add(3.2, 1.3, 1.6), p.add(0.0, 1.3, 1.5), p.add(0.0, 1.2, 1.7), 90, 52.0, 44.0)
        rec("gear_clear_cut", 90) { i ->
            client { c ->
                nearest(c, EntityType.HUSK)?.let { face(c, it, 99.0, move = false, turn = 0.6f) }
                if (i == 6) ArtClient.send(ArtAction.ART_PRESS, 0)
                if (i == 50) ArtClient.send(ArtAction.ART_RELEASE, 0)
            }
        }
        cineOff()
        killDummies()
        tick(20)

        // Seit 1.4.0: Drachenatem (Ability 1) und Schwingensprung (Ability 2) der Rüstung
        summonDummies(3, p.x, p.z + 5.0, 1.8)
        tick(10)
        client { c -> nearest(c, EntityType.HUSK)?.let { face(c, it, 99.0, move = false, turn = 1f) } }
        cineOn()
        cam(p.add(4.0, 1.6, -1.0), p.add(3.5, 2.0, 1.0), p.add(0.0, 1.4, 3.0), p.add(0.0, 1.3, 5.0), 60, 58.0, 52.0)
        rec("gear_dragon_breath", 60) { i ->
            client { c -> nearest(c, EntityType.HUSK)?.let { face(c, it, 99.0, move = false, turn = 0.6f) } }
            if (i == 4) server { dev.henny.hha.api.ability.HhaAbilities.dispatch(player(it), dev.henny.hha.api.ability.AbilitySlot.ABILITY_1) }
        }
        cam(p.add(-6.0, 2.5, 2.0), p.add(-7.0, 3.5, 6.0), p.add(0.0, 2.0, 3.0), p.add(0.0, 1.0, 7.0), 70, 62.0, 58.0)
        rec("gear_wing_leap", 70) { i ->
            if (i == 4) server { dev.henny.hha.api.ability.HhaAbilities.dispatch(player(it), dev.henny.hha.api.ability.AbilitySlot.ABILITY_2) }
        }
        cineOff()
        killDummies()
        tick(30)

        if (all) {
            // Weißes Loch als Setfähigkeit, Husks im Ring.
            summonDummies(6, p.x, p.z + 8.0, 4.0)
            fillResonance()
            tick(10)
            client { c -> nearest(c, EntityType.HUSK)?.let { face(c, it, 99.0, move = false, turn = 1f) } }
            cineOn()
            cam(p.add(-9.0, 4.0, -6.0), p.add(-11.0, 6.0, -4.0), p.add(0.0, 2.0, 7.0), p.add(0.0, 3.0, 8.0), 170, 62.0, 70.0)
            rec("gear_white_hole", 170) { i -> if (i == 10) server { WhiteHole.cast(player(it)) } }
            cineOff()
            killDummies()
            tick(40)
        }
    }

    /** Klarer Schnitt spaltet Stamm und Stein (nur außerhalb der Arena möglich): kleine Schneelichtung in der Oberwelt. */
    private fun overworldCut() {
        log("overworld cut")
        cmd("gamemode creative @p")
        cmd("execute in minecraft:overworld run tp @p 0.5 -60 0.5 0 0")
        tick(20)
        cmd("time set 1000")
        cmd("fill -20 -61 -20 20 -61 30 minecraft:snow_block")
        cmd("fill -20 -60 -20 20 -60 30 minecraft:snow")
        cmd("fill 0 -60 4 0 -56 4 minecraft:spruce_log")
        cmd("fill -1 -60 4 -1 -58 4 minecraft:spruce_log")
        cmd("fill 1 -60 5 2 -59 6 minecraft:cobblestone")
        for ((x, z) in listOf(-8 to 14, 7 to 16, -12 to 6, 11 to 9, -4 to 22, 5 to 26)) {
            cmd("place feature minecraft:spruce $x -60 $z")
        }
        cmd("gamemode survival @p")
        cmd("hha set hha_arius.clear_cut_block_max 40")
        tick(20)
        settle(1200)
        hotbar(0)
        fillResonance()
        val p = server { player(it).entityPos }
        cineOn()
        cam(p.add(4.5, 1.4, 0.0), p.add(3.8, 1.6, 1.2), p.add(0.0, 1.4, 3.0), p.add(0.0, 1.6, 3.6), 80, 56.0, 48.0)
        rec("cut_log", 80) { i ->
            client { c ->
                c.player?.let { it.yaw = 0f; it.pitch = 0f; it.headYaw = 0f }
                if (i == 4) ArtClient.send(ArtAction.ART_PRESS, 0)
                if (i == 42) ArtClient.send(ArtAction.ART_RELEASE, 0)
            }
        }
        cineOff()
        tick(20)
    }

    // --- Katalog (Website „Leaks / Gameplay“): alle Schauplätze, Modelle, Zauber -------------------------------------

    private fun catalog() {
        log("catalog")
        val vb = dev.henny.hha_arius.arena.ArenaLayout.VAULT
        // 1) Schauplätze ohne Kampf, Spieler unsichtbar
        enterArena("spectator", 0.5, 2.0, -2.5, settleTicks = 600)
        cmd("effect give @p minecraft:invisibility infinite 0 true")
        atmos(1)
        tick(40)
        cineOn()
        // Tempel innen: durchs Tor zum Altar
        cam(at(2.5, 3.0, -36.0), at(1.5, 3.5, -46.0), at(0.0, 3.0, -64.0), at(0.0, 3.5, -64.0), 60, 64.0, 58.0)
        rec("cat_temple_inside", 60)
        // Plateau mit Obelisken und Ruinenbogen
        cam(at(-26.0, 6.0, -2.0), at(-20.0, 8.0, 10.0), at(31.0, 4.0, -4.0), at(30.0, 4.0, 0.0), 60, 66.0, 60.0)
        rec("cat_plateau", 60)
        // Das offene Hochland
        cam(at(60.0, 14.0, -30.0), at(70.0, 18.0, 10.0), at(0.0, 5.0, 0.0), at(0.0, 30.0, 128.0), 60, 70.0, 64.0)
        rec("cat_highland", 60)
        // Das Feld im Hochland (Abschnitt III und Finale)
        atmos(4)
        cam(at(vb.x - 40.0, vb.y + 10.0, vb.z - 30.0), at(vb.x - 34.0, vb.y + 14.0, vb.z - 18.0), at(vb.x.toDouble(), vb.y + 1.0, vb.z.toDouble()), at(vb.x + 2.0, vb.y + 2.0, vb.z + 4.0), 60, 66.0, 60.0)
        rec("cat_field", 60)
        cam(at(vb.x.toDouble(), vb.y + 40.0, vb.z - 44.0), at(vb.x + 6.0, vb.y + 36.0, vb.z - 36.0), at(vb.x.toDouble(), vb.y.toDouble(), vb.z.toDouble()), at(vb.x.toDouble(), vb.y.toDouble(), vb.z + 2.0), 60, 62.0, 58.0)
        rec("cat_field_top", 60)
        // Das Felshorn bis zum Gipfel
        cam(at(-70.0, 140.0, 60.0), at(-60.0, 160.0, 80.0), at(0.0, 120.0, 128.0), at(0.0, 175.0, 128.0), 60, 60.0, 54.0)
        rec("cat_horn", 60)
        cineOff()
        cmd("effect clear @p minecraft:invisibility")
        atmos(0)

        // 2) Ausrüstung: Rüstungsständer und alle Items als schwebende Anzeigen
        enterArena("survival", 0.5, 0.0, 6.5, 0f)
        val p = server { player(it).entityPos }
        cmd("execute in $eir run summon minecraft:armor_stand ${p.x + 2.5} ${p.y} ${p.z + 3.0} {ShowArms:1b,NoGravity:1b,Rotation:[200f,0f],equipment:{head:{id:\"hha_arius:eirholm_helmet\"},chest:{id:\"hha_arius:eirholm_chestplate\"},legs:{id:\"hha_arius:eirholm_leggings\"},feet:{id:\"hha_arius:eirholm_boots\"},mainhand:{id:\"hha_arius:solvane\"}}}")
        val items = listOf("solvane", "arius_gauntlets", "eirholm_helmet", "eirholm_chestplate", "eirholm_leggings", "eirholm_boots", "heartfrost", "nivaris_scale", "nivaris_wingscale", "eirholm_seal", "echo_of_eirholm")
        for ((i, it) in items.withIndex()) {
            val x = p.x - 4.0 + i * 0.8
            cmd("execute in $eir run summon minecraft:item_display $x ${p.y + 1.6} ${p.z + 4.0} {item:{id:\"hha_arius:$it\"},item_display:\"gui\",transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[0.6f,0.6f,0.6f]}}")
        }
        tick(10)
        cineOn()
        cam(p.add(0.5, 1.8, -0.5), p.add(0.2, 1.7, 0.2), p.add(-0.2, 1.4, 4.0), p.add(0.0, 1.5, 4.0), 40, 54.0, 50.0)
        rec("cat_items", 40)
        cam(p.add(4.6, 1.5, 1.0), p.add(4.0, 1.7, 1.6), p.add(2.5, 1.1, 3.0), p.add(2.5, 1.2, 3.0), 40, 44.0, 40.0)
        rec("cat_armor_stand", 40)
        cineOff()
        cmd("kill @e[type=minecraft:item_display]")
        cmd("kill @e[type=minecraft:armor_stand]")

        // 3) Alle übrigen Zauber
        gauntletMode(GauntletMode.MAGIC)
        summonDummies(3, p.x, p.z + 7.0, 2.0)
        tick(10)
        fun aim(c: MinecraftClient) { nearest(c, EntityType.HUSK)?.let { face(c, it, 99.0, move = false, turn = 0.5f) } }
        fun castShot(spell: Spell, name: String, ticks: Int, pressAt: Int, releaseAt: Int, camFrom: Vec3d, camTo: Vec3d) {
            select(spell)
            fillResonance()
            val q = server { player(it).entityPos }
            cam(q.add(camFrom), q.add(camTo), q.add(0.0, 1.4, 3.0), q.add(0.0, 1.3, 5.0), ticks, 60.0, 56.0)
            rec(name, ticks) { i ->
                client { c ->
                    aim(c)
                    if (i == pressAt) ArtClient.send(ArtAction.CAST_PRESS)
                    if (i == releaseAt) ArtClient.send(ArtAction.CAST_RELEASE)
                }
            }
            tick(10)
        }
        cineOn()
        castShot(Spell.GALE, "cat_gale", 50, 4, 28, Vec3d(3.5, 1.6, -1.5), Vec3d(3.2, 1.8, 0.5))
        castShot(Spell.WIND_PULL, "cat_wind_pull", 40, 4, 5, Vec3d(-3.5, 2.0, -2.0), Vec3d(-3.0, 2.2, 0.0))
        castShot(Spell.ICE_ARMOR, "cat_ice_armor", 40, 4, 5, Vec3d(2.6, 1.4, 2.6), Vec3d(2.2, 1.6, 2.2))
        castShot(Spell.YANG_SHIELD, "cat_yang_shield", 50, 4, 44, Vec3d(2.0, 1.5, 4.5), Vec3d(1.5, 1.6, 4.0))
        castShot(Spell.YANG_ORB, "cat_yang_orb", 60, 4, 34, Vec3d(3.0, 1.4, 0.0), Vec3d(2.5, 1.6, 3.0))
        castShot(Spell.FROST_FIELD, "cat_frost_field", 70, 6, 30, Vec3d(-5.0, 4.0, -3.0), Vec3d(-4.0, 5.0, 0.0))
        castShot(Spell.WIND_LEAP, "cat_wind_leap", 40, 4, 5, Vec3d(-5.0, 1.5, 2.0), Vec3d(-5.0, 3.0, 5.0))
        cineOff()
        killDummies()
        tick(20)

        // 4) Arius in allen Formen und Solvane, nah (Training, Kampf ruht)
        cmd("gamemode survival @p")
        cmd("execute in minecraft:overworld run tp @p 0.5 -60 0.5 0 0")
        tick(40)
        cmd("execute as @p at @s run hhaarius stage 1")
        waitUntil(3000, 5) { phase() >= EirholmFight.Phase.TEMPLE }
        tick(40)
        server { fightNow()?.let { it.ariusVulnerable = false } }
        cmd("gamemode spectator @p")
        for ((form, name) in listOf(dev.henny.hha_arius.boss.AriusForm.DARK to "cat_arius_dark", dev.henny.hha_arius.boss.AriusForm.SERIOUS to "cat_arius_serious", dev.henny.hha_arius.boss.AriusForm.WHITE to "cat_arius_white")) {
            server { fightNow()?.arius?.let { a -> a.form = form; if (form == dev.henny.hha_arius.boss.AriusForm.WHITE) a.bladeState = dev.henny.hha_arius.boss.AriusBlade.ICE } }
            val ap = serverPos(ariusS) ?: break
            cineOn()
            orbit(ap, 3.2, 2.8, 1.5, 1.7, 20.0, 70.0, 50, 46.0, 40.0, ariusS, 1.5)
            rec(name, 50)
            cineOff()
        }
        cmd("hhaarius stop")
        tick(60)
        cmd("gamemode survival @p")
        cmd("execute in minecraft:overworld run tp @p 0.5 -60 0.5 0 0")
        tick(40)
        cmd("execute as @p at @s run hhaarius stage 2")
        waitUntil(4000, 5) { server { fightNow()?.nivaris != null } }
        tick(160)
        cmd("gamemode spectator @p")
        val dp = serverPos(dragonS)
        if (dp != null) {
            cineOn()
            orbit(dp, 9.0, 7.0, 4.0, 6.0, 0.0, 80.0, 60, 60.0, 52.0, dragonS, 3.0)
            rec("cat_solvane", 60)
            cam(dp.add(18.0, 12.0, -18.0), dp.add(22.0, 16.0, -10.0), dp, dp.add(0.0, 2.0, 0.0), 60, 70.0, 64.0)
            rec("cat_solvane_wide", 60)
            cineOff()
        }
        cmd("hhaarius stop")
        tick(40)
    }

    // --- Der Kampf ------------------------------------------------------------------------------------------------------

    private fun fightBot(c: MinecraftClient, combo: Combo, target: EntityType<*>, keep: Double = 2.4) {
        val t = find(c, target) ?: return
        face(c, t, keep, move = true)
        val d = c.player?.distanceTo(t) ?: 99f
        combo.step(c, d < 3.4f)
    }

    private fun spellBot(c: MinecraftClient, i: Int, target: EntityType<*>) {
        val t = find(c, target) ?: return
        face(c, t, 99.0, move = false, turn = 0.3f)
        when (i % 60) {
            10 -> ArtClient.send(ArtAction.SELECT, Spell.FROST_SHARDS.ordinal)
            12 -> ArtClient.send(ArtAction.CAST_PRESS)
            13 -> ArtClient.send(ArtAction.CAST_RELEASE)
            30 -> ArtClient.send(ArtAction.SELECT, Spell.YANG_ORB.ordinal)
            32 -> ArtClient.send(ArtAction.CAST_PRESS)
            56 -> ArtClient.send(ArtAction.CAST_RELEASE)
        }
    }

    /** Kamera-Wechsel während des Kampfs: alle [len] Ticks eine neue Einstellung um Spieler und Gegner. */
    private fun fightCams(i: Int, len: Int, focus: (MinecraftServer) -> Entity?, wide: Double) {
        if (i % len != 0) return
        val k = i / len
        server { srv ->
            val pl = player(srv)
            val f = focus(srv) ?: return@server
            val mid = pl.entityPos.lerp(f.entityPos, 0.5)
            val axis = f.entityPos.subtract(pl.entityPos).let { Vec3d(it.x, 0.0, it.z) }.let { if (it.lengthSquared() < 1e-3) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
            val side = Vec3d(-axis.z, 0.0, axis.x).multiply(if (k % 2 == 0) 1.0 else -1.0)
            val (from, to) = when (k % 3) {
                0 -> mid.add(side.multiply(wide)).add(0.0, 1.6, 0.0) to mid.add(side.multiply(wide * 0.85)).add(axis.multiply(1.2)).add(0.0, 2.0, 0.0)
                1 -> pl.entityPos.subtract(axis.multiply(2.6)).add(side.multiply(1.2)).add(0.0, 2.1, 0.0) to pl.entityPos.subtract(axis.multiply(2.1)).add(side.multiply(1.7)).add(0.0, 1.9, 0.0)
                else -> mid.add(side.multiply(wide * 1.2)).add(0.0, wide * 0.9 + 1.5, 0.0) to mid.add(side.multiply(wide * 1.05)).subtract(axis.multiply(1.5)).add(0.0, wide * 0.8 + 1.5, 0.0)
            }
            Cine.camera(listOf(pl), from, to, mid, mid, len, 62.0, 56.0, f, 1.2)
        }
    }

    private fun fight() {
        log("fight")
        cmd("kill @e[type=minecraft:husk]")
        cmd("gamemode survival @p")
        cmd("execute in minecraft:overworld run tp @p 0.5 -60 0.5 0 0")
        cmd("time set 1000")
        equip()
        tick(20)
        cmd("execute as @p at @s run hhaarius start")
        waitUntil(3000, 1) { phase() >= EirholmFight.Phase.INTRO }
        // Erster Besuch: Etablierungsfahrt und Erheben.
        rec("f_intro", 420)
        waitUntil(900, 5) { phase() >= EirholmFight.Phase.TEMPLE }
        tick(20)

        // I: Tempel. Kurz aus der Spielersicht, dann enge Kamera-Einstellungen (Schnitt alle 2,5 s), auch in der Wut.
        gauntletMode(GauntletMode.MELEE)
        val combo = Combo(10)
        rec("f_temple_f5", 120) { client { c -> fightBot(c, combo, AriusEntities.ARIUS) } }
        cineOn()
        rec("f_temple_cine", 400) { i ->
            fightCams(i, 50, ariusS, 3.6)
            client { c -> fightBot(c, combo, AriusEntities.ARIUS) }
        }
        server { fightNow()?.let { it.ariusHp = it.ariusMax * 0.36f } }
        rec("f_temple_serious", 300) { i ->
            fightCams(i + 25, 50, ariusS, 3.6)
            client { c -> fightBot(c, combo, AriusEntities.ARIUS) }
        }
        cineOff()

        // Der Schnitt, der Tritt, Solvanes Auftritt an der Nadel (alles Cutscenes des Spiels).
        cmd("execute as @p at @s run hhaarius skip")
        rec("f_cut_entrance", 2400, until = { phase() >= EirholmFight.Phase.GLACIER }) { client { c -> find(c, AriusEntities.ARIUS)?.let { face(c, it, 99.0, move = false) } } }
        rec("f_dragon_name", 120) { client { c -> find(c, AriusEntities.NIVARIS)?.let { face(c, it, 99.0, move = false) } } }

        // II: Gletscher. Zauber gegen Solvane aus der Spielersicht, dann weite Einstellungen auf den Drachen.
        gauntletMode(GauntletMode.MAGIC)
        rec("f_glacier_f5", 240) { i -> fillResonance(); client { c -> spellBot(c, i, AriusEntities.NIVARIS) } }
        cineOn()
        rec("f_glacier_cine", 288) { i ->
            fightCams(i, 72, dragonS, 14.0)
            fillResonance()
            client { c -> spellBot(c, i, AriusEntities.NIVARIS) }
        }
        cineOff()

        // Gletschersturz und Verwandlung.
        cmd("execute as @p at @s run hhaarius skip")
        rec("f_collapse", 1600, until = { phase() >= EirholmFight.Phase.VAULT }) {}
        tick(10)

        // III: Herzgewölbe mit Eisklinge und Drache.
        gauntletMode(GauntletMode.MELEE)
        rec("f_vault_f5", 200) { client { c -> fightBot(c, combo, AriusEntities.ARIUS) } }
        cineOn()
        rec("f_vault_cine", 180) { i ->
            fightCams(i, 60, ariusS, 5.0)
            client { c -> fightBot(c, combo, AriusEntities.ARIUS) }
        }
        cineOff()
        // Das weiße Loch (unter der Hälfte).
        server { fightNow()?.let { it.ariusHp = it.ariusMax * 0.48f } }
        rec("f_white_hole", 560) { client { c -> find(c, AriusEntities.ARIUS)?.let { face(c, it, 99.0, move = false) } } }
        cineOn()
        rec("f_white_hole_cine", 200) { i -> fightCams(i, 100, ariusS, 12.0) }
        cineOff()

        // Finale: „Komm zurück.“, die Klinge kehrt in die Scheide, Iai-Austausch.
        cmd("execute as @p at @s run hhaarius skip")
        waitUntil(200, 1) { phase() >= EirholmFight.Phase.FINALE }
        rec("f_finale", 640) { client { c -> find(c, AriusEntities.ARIUS)?.let { face(c, it, 6.0, move = true, speed = 0.12) } } }
        cmd("execute as @p at @s run hhaarius skip")
        rec("f_ending", 420) {}
        log("fight done phase=${phase()}")
    }
}

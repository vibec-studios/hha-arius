package dev.henny.hha_arius.boss

import dev.henny.hha.api.set.HhaSets
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.arena.ArenaBuilder
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.net.AriusNet
import dev.henny.hha_arius.net.FxKind
import dev.henny.hha_arius.net.SliceS2C
import net.minecraft.block.Block
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Abschnitte, Übergänge und Signaturmomente (Konzept 2–8). */
class Scenes(private val f: EirholmFight) {

    private val rnd = java.util.Random()
    private val P = EirholmFight.Phase

    private suspend fun SequenceScope<Unit>.runWhile(s: Script, condition: () -> Boolean) {
        val r = Runner(s)
        while (condition() && r.step()) yield(Unit)
        if (!condition()) f.calls.reset()
    }

    private fun state() = AriusState.get(f.server)

    private fun newcomers(flag: String): List<ServerPlayerEntity> =
        f.audience().filter { flag !in state().player(it.uuid).heard }

    private fun markHeard(flag: String, players: Collection<ServerPlayerEntity> = f.audience()) {
        val s = state()
        for (p in players) s.player(p.uuid).heard.add(flag)
        s.save()
    }

    // --- Intro -------------------------------------------------------------------------------------------------------------

    private fun rel(x: Double, y: Double, z: Double): Vec3d = f.abs(Vec3d(x, y, z))

    /** Nur wenn die Szene nicht gerade übersprungen (vorgespult) wird: Klänge, Texte und kurze Effekte. */
    private inline fun shown(block: () -> Unit) {
        if (!Cine.muted) block()
    }

    /** Szenenklang (still beim Vorspulen). */
    private fun cue(id: String, at: Vec3d, volume: Float = 3f, pitch: Float = 1f) = shown { f.ambient(id, at, volume, pitch) }

    /**
     * Dumpfer Aufschlag eines Spielers im Schnee (Tritt aus dem Tempel, Abwurf von Solvane), über die Szenenkamera hörbar.
     * Beim Abwurf hält Hold den Körper und setzt die Fallhöhe jeden Tick auf 0, darum gibt es dort keinen Vanilla-Aufprallklang;
     * beim Tritt war der alte Aufschlag (hold.impact, 1,6) zu leise. Still beim Vorspulen.
     */
    private fun landThud(at: Vec3d) {
        f.sceneSound("hold.impact", at, 2.6f, 0.78f)
        f.sceneSound(net.minecraft.sound.SoundEvents.BLOCK_SNOW_BREAK, at, 2.2f, 0.6f)
        f.sceneSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_BIG_FALL, at, 1.6f, 0.9f)
    }

    fun intro(): Script = script {
        val a = f.arius ?: return@script
        f.ariusVulnerable = false
        val firstVisit = f.training == 0 && f.audience().any { !state().player(it.uuid).seenIntro }
        if (firstVisit) yieldAll(establish(a)) else f.music("music.temple", 40)
        waitUntil(160) { f.players().any { it.squaredDistanceTo(a) < 16.0 * 16.0 } }
        if (f.training == 1) {
            f.say("echo", 60)
            a.play(AriusPose.RISE)
            wait(40)
        } else {
            val s = state()
            val fresh = f.audience().filter { !s.player(it.uuid).seenIntro }
            if (fresh.isNotEmpty()) {
                // Seit 1.8.0 kürzer (Crix: Texte kann man nicht lesen): drei kurze Zeilen mit Zeit zum Lesen.
                f.say("intro.seal", 60); wait(66)
                a.play(AriusPose.RISE); wait(24)
                f.say("intro.reason", 70); wait(76)
                f.say("intro.test", 80); wait(50)
                for (p in f.audience()) s.player(p.uuid).seenIntro = true
                s.save()
            } else {
                a.play(AriusPose.RISE)
                val summoner = s.player(f.summonerId)
                val pool = if (summoner.wins > 0) listOf("again.1", "again.2", "again.3") else listOf("again.1", "again.2")
                f.say(pool[summoner.againIndex % pool.size], 50)
                summoner.againIndex++
                s.save()
                wait(40)
            }
        }
        yieldAll(temple())
    }

    /**
     * Etablierungsfahrt beim ersten Besuch (≈ 14 s, überspringbar, keine Gefahr: der Kampf hat noch nicht begonnen).
     * 1. Hoch im Norden hinter der Tempelkuppel: Die Kamera gleitet nach Süden, unten die Kuppel, dahinter das Plateau und
     *    in der Ferne die Nadel (oberes Bilddrittel). Nur Wind.
     * 2. Stille: über den Köpfen der Ankommenden durch das Tor in die Halle, langsame Annäherung an den Sitzenden.
     * 3. Detail: sein Gesicht, die Augen glimmen auf, das Tempelthema setzt ein. Rückweg zur Spielerkamera.
     */
    private fun establish(a: AriusEntity): Script {
        val scene = CineScene(f) { f.music("music.temple", 20) }
        return scene.play(script {
            val temple = f.abs(Vec3d.of(ArenaLayout.TEMPLE))
            scene.once { Cine.fade(it, 0x000000, 1.0, 0.0, 30) }
            // 1) Über das Heiligtum. Blickmitte knapp unter dem Horizont: Kuppel unten, Spitze der Nadel oben im Bild.
            scene.line(rel(-16.0, 64.0, -112.0), rel(-8.0, 56.0, -96.0), rel(0.0, 40.0, -20.0), rel(0.0, 54.0, 0.0), 120, 66.0, 62.0)
            shown { f.ambient("seal.blizzard", f.abs(ArenaLayout.ARRIVAL), 1.2f, 0.55f) }
            wait(120)
            // 2) Stille: über den Köpfen durch das Tor, langsam auf ihn zu.
            scene.line(rel(2.5, 3.1, -33.5), rel(1.6, 2.7, -50.0), temple, temple, 90, 52.0, 42.0, lookAt = a, lookOffset = 1.45)
            wait(90)
            // 3) Detail: das Gesicht, die Augen glimmen auf.
            val face = a.entityPos.add(0.0, 1.55, 0.0)
            val front = f.facing(a)
            scene.line(face.add(front.multiply(2.4)).add(0.6, -0.25, 0.0), face.add(front.multiply(1.7)).add(0.4, -0.15, 0.0), face, face.add(0.0, 0.05, 0.0), 62, 46.0, 38.0)
            wait(20)
            a.eyeGlow = 70
            f.music("music.temple", 40)
            shown { f.ambient("resonance.chime", face, 1.5f, 0.6f) }
            wait(26)
            scene.home(16)
            wait(16)
        })
    }

    // --- I: Tempel ----------------------------------------------------------------------------------------------------------

    fun temple(): Script = script {
        val a = f.arius ?: return@script
        // Vom Sitz auf den Altarstufen herunter: Die Laufschritte gehen nur auf Bodenhöhe, auf den Stufen gäbe es keinen
        // freien Schritt (Arius blieb sonst am Sitz stehen).
        if (a.y > f.floorY + 0.5) yieldAll(leapFromDais(a))
        f.setPhase(P.TEMPLE)
        f.reviveAll(listOf(ArenaLayout.ARRIVAL))
        // Seit 1.8.0 deutlich zäher (Crix: „stirbt echt schnell“): 820 statt 520.
        f.ariusMax = BossConfig.health(820f, f.startCount)
        f.ariusHp = f.ariusMax
        f.ariusVulnerable = true
        a.form = AriusForm.DARK
        a.eyeGlow = 45
        f.moves.resetStance()
        f.music("music.temple", 20)
        var serious = false
        while (f.ariusHp > 0f) {
            if (!serious && f.ariusHp <= f.ariusMax * 0.4f) {
                serious = true
                a.form = AriusForm.SERIOUS
                a.eyeGlow = 85
                f.fx(FxKind.FRAGMENTS, a.entityPos, a = 0.4f, life = 20, e1 = a)
                f.ambient("transform.shatter", a.entityPos, 1.4f, 1.3f)
                f.say("serious", 40)
                MusicCue.sting(f, "phase")
                MusicCue.play(f, "temple_serious", 40)
            }
            runWhile(f.moves.turnTemple()) { f.ariusHp > 0f }
        }
        f.ariusVulnerable = false
        if (f.training == 1) {
            a.play(AriusPose.STAGGER)
            f.victory()
            return@script
        }
        yieldAll(theCut())
    }

    /** Sprung im Bogen vom Altar auf den Hallenboden vor den Stufen, mit Windschritt-Klang und Landestaub. */
    private fun leapFromDais(a: AriusEntity): Script = script {
        val start = a.entityPos
        val land = f.abs(ArenaLayout.DAIS_FRONT)
        f.face(a, land)
        cue("arius.wind_step", start, 2f, 0.8f)
        val steps = 12
        for (i in 1..steps) {
            val k = i / steps.toDouble()
            f.place(a, start.lerp(land, k).add(0.0, sin(k * PI) * 2.5, 0.0))
            yield(Unit)
        }
        f.place(a, land)
        f.fx(FxKind.FRAGMENTS, land, a = 0.25f, life = 14)
        cue("arius.stab_ground", land, 1.4f, 1.2f)
        wait(6)
    }

    // --- Der Schnitt -------------------------------------------------------------------------------------------------------

    /**
     * Der Schnitt mit Kamera in zwei Teilen, beide außerhalb jeder Gefahr:
     * * Spannungsaufbau (42 Ticks, vor der Ansage): Sprung auf die Altarstufe in der Halbtotalen, dann Stille und die
     *   Klinge im Detail, während der Wind sich umkehrt; Rückweg zur Spielerkamera **bevor** die Ansage beginnt. Die
     *   Ansage behält ihre vollen 60 Ticks, die Spieler haben die Steuerung.
     * * Nach dem Treffer (Schaden ist schon verrechnet): Stille auf Arius, dann die weite Totale, in der die Osthälfte des
     *   Tempels abgleitet, und ohne Schnitt weiter in den Tritt durchs Tor ([kick]), bis Nivaris' Auftritt übernimmt.
     */
    fun theCut(): Script = script {
        val a = f.arius ?: return@script
        f.setPhase(P.CUT)
        f.ariusVulnerable = false
        f.music(null, 30)
        MusicCue.play(f, "cut", 30)
        val stand = f.abs(ArenaLayout.CUT_STAND)
        var chargeShown = false
        val pre = CineScene(f) {
            f.place(a, stand)
            a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
            a.play(AriusPose.HORIZON_RAISE)
        }
        yieldAll(pre.play(script {
            // Sprung auf die Altarstufe: Halbtotale von der Westseite der Halle, der Blick folgt ihm.
            val start = a.entityPos
            pre.line(rel(-9.0, 1.4, -50.0), rel(-8.0, 2.2, -54.5), stand, stand, 14, 62.0, 56.0, lookAt = a, lookOffset = 1.3)
            cue("arius.wind_step", start, 1.8f, 0.72f)
            for (i in 1..12) {
                val k = i / 12.0
                f.place(a, start.lerp(stand, k).add(0.0, sin(k * PI) * 2.0, 0.0))
                yield(Unit)
            }
            val chargeTicks = AriusTimings.CUT_LINE_AT + AriusTimings.CUT_LINE_TICKS
            a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
            a.play(AriusPose.HORIZON_RAISE, chargeTicks - 6)
            cue("cut.reverse_wind", stand, 2.8f, 0.85f)
            // Das Aufladen beginnt mit dem Heben der Klinge und läuft ohne Neustart bis zum Schlag (+2 Ticks Taktpuffer;
            // der Niederschlag löst das Aufladen auf dem Client exakt ab).
            shown {
                f.fxAll(FxKind.HORIZON, Vec3d(stand.x, f.floorY, stand.z), Vec3d(0.0, 0.0, 1.0), a = 56f, b = 3f, life = chargeTicks + 2, e1 = a)
                chargeShown = true
            }
            // Detail: Arius hebt Solvane gerade senkrecht über den Kopf in der Schnittebene (ganze Klinge im Bild).
            pre.line(stand.add(-3.4, 0.95, 4.4), stand.add(-2.8, 1.85, 3.9), stand.add(0.0, 2.1, 0.0), stand.add(0.0, 2.95, 0.0), 30, 58.0, 50.0)
            wait(20)
            cue("white_line.charge", stand, 2.6f, 0.72f)
            // Rückweg zur Spielerkamera, fertig bevor die Ansage beginnt.
            pre.home(10)
            wait(AriusTimings.CUT_LINE_AT - 20)
        }))
        f.cutSurvivors.clear()
        f.cutSurvivors.addAll(f.players().map { it.uuid })
        // Titel erst NACH dem Schnitt zeigen, damit Arius und die senkrechte Ladelinie beim Aufladen vollständig frei bleiben.
        // Steigende Spannung entlang der sich sammelnden senkrechten Frostlinie.
        cue("core.form", stand, 2.2f, 0.65f)
        shown { f.farVanilla(net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, stand, 1.4f, 0.62f) }
        val cutDir = Vec3d(0.0, 0.0, 1.0)
        val group = f.calls.boss(pace = Telegraph.Pace.ROOM)
        val line = group.line(f.abs(ArenaLayout.CUT_FROM), f.abs(ArenaLayout.CUT_TO), ArenaLayout.CUT_WIDTH.toFloat(), AriusTimings.CUT_LINE_TICKS)
        a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
        // Pose & Aufladen (b = 3f) nur starten, falls die Vorkamera übersprungen wurde — sonst läuft beides ohne Ruck durch.
        if (!chargeShown) {
            a.play(AriusPose.HORIZON_RAISE, (line.ticks - 6).coerceAtLeast(12))
            f.fxAll(FxKind.HORIZON, Vec3d(stand.x, f.floorY, stand.z), cutDir, a = 56f, b = 3f, life = line.ticks + 2, e1 = a)
        }
        // Spannung baut sich in der Mitte weiter auf, dann ein klarer Beat Beinahe-Stille direkt vor dem Niederschlag.
        val midCharge = (line.ticks / 2).coerceAtLeast(1)
        val silenceWait = (line.ticks - 12 - midCharge).coerceAtLeast(1)
        wait(midCharge)
        cue("cut.charge", stand, 2.8f, 0.78f)
        wait(silenceWait)
        MusicCue.duck(f, 0.03f, 22)
        cue("iai.glint", stand, 1.8f, 1.25f)
        wait(6)
        a.play(AriusPose.CUT_RELEASE, 6)
        wait(6)
        // Gewaltiger senkrechter Abwärtsschnitt exakt in der Schnittebene x = CUT_X (b = 4f): EIN tiefer Einschlag.
        f.fxAll(FxKind.HORIZON, Vec3d(stand.x, f.floorY, stand.z), cutDir, a = 78f, b = 4f, life = 24)
        cue("cut.strike", stand, 4.2f, 0.58f)
        cue("hold.impact", stand, 2.8f, 0.50f)
        for (p in line.hits(f.players())) {
            f.cutSurvivors.remove(p.uuid)
            f.hurt(p, 30f, Damage.Kind.NULL_CUT)
        }
        wait(18)
        val vantage = Vantage(f)
        val post = CineScene(f) {
            ensureSplitImmediate()
            vantage.restore()
        }
        yieldAll(post.play(script { yieldAll(cutAftermath(post, vantage, a, stand)) }))
        ensureSplitImmediate()
        vantage.restore()
        f.atmos(dev.henny.hha_arius.net.AtmosMode.CLEAR, 30)
        yieldAll(arrival())
    }

    /** Gespaltene Blöcke der laufenden Szene, nach Höhe sortiert für die spätere Yang-Wiederherstellung in der Jagd. */
    private var activeSplit: List<Pair<BlockPos, net.minecraft.block.BlockState>> = emptyList()
    private var splitActive = false

    /** Ist der Berg gerade gespalten (und wartet auf die Yang-Wiederherstellung vor der ersten Querung in der Jagd)? */
    val isSplitActive: Boolean get() = splitActive || activeSplit.isNotEmpty()

    /** Spaltet den Berg sofort ohne Animation, falls er noch nicht gespalten ist (Skip von cutAftermath oder Training II/III). */
    fun ensureSplitImmediate() {
        if (isSplitActive) return
        val z0 = (ArenaLayout.SPIRE.z - FarPlan.MASSIF_R - 6).toInt()
        val z1 = (ArenaLayout.SPIRE.z + FarPlan.MASSIF_R + 6).toInt()
        val cutX = kotlin.math.floor(ArenaLayout.CUT_X).toInt()
        val maxY = ArenaLayout.SPIRE_TOP + 38
        val collected = ArrayList<Pair<BlockPos, net.minecraft.block.BlockState>>(48_000)
        val pos = BlockPos.Mutable()
        for (z in z0..z1) {
            for (dx in -8..8) {
                val x = cutX + dx
                if (FarPlan.inCore(x, z)) continue
                for (y in 0..maxY) {
                    if (!SplitRecovery.inCutPlane(x, y, z)) continue
                    pos.set(f.origin.x + x, f.origin.y + y, f.origin.z + z)
                    val st = f.world.getBlockState(pos)
                    if (!st.isAir) collected.add(pos.toImmutable() to st)
                }
            }
        }
        if (collected.isEmpty()) return
        SplitRecovery.saveSnapshot(f.world, f.slot, z0, z1, collected)
        activeSplit = collected
        splitActive = true
        for ((p, _) in collected) f.edits.clear(p)
    }

    /** Stellt alle noch gespaltenen Blöcke sofort exakt wieder her (Abbruch, Überspringen oder Sicherheitspfad). */
    fun restoreSplitInstant() {
        if (!splitActive && activeSplit.isEmpty()) return
        val list = activeSplit
        activeSplit = emptyList()
        splitActive = false
        if (list.isNotEmpty()) {
            f.edits.revert(list.map { it.first })
            for ((pos, state) in list) {
                if (f.world.getBlockState(pos) != state) {
                    f.world.setBlockState(pos, state, ArenaEdits.FLAGS)
                    dev.henny.hha_arius.arena.HighlandView.changed(f.world, pos.x, pos.z)
                }
            }
        }
        SplitRecovery.clearSnapshot(f.server, f.slot)
    }

    /**
     * Der Weiße Horizont nach dem Schlag (1.13.0):
     * 1. **Totale**: Arius holt aus, die horizontale Schneide zieht einmal über den Tempel und verblasst.
     * 2. **Hoch über dem Tempel**: Blick die Naht entlang in das Massiv; schlanke, feine Frostschleier (nie fette
     *    Wolken) zeichnen die Schnittkante nach.
     * 3. **Spaltung von Berg UND Gipfelarena**: weite Totale von Fuß bis Horn — der Schnitt teilt Massiv, Weg,
     *    Gipfelarena, Ringmauer, Eiskrone und Horn sauber in zwei Hälften.
     * 4. **Nahblick auf die gespaltene Gipfelarena**: unmissverständlicher Blick über die geteilte Arena.
     * 5. **Goldene Yang-Wiederherstellung**: eine leuchtende goldene Yang-Welle steigt vom Fuß bis zum Gipfel auf und
     *    fügt jeden einzelnen Block exakt in seinen Originalzustand zurück; nichts bleibt zerstört (auch bei Absturz).
     * 6. **Whiteout** zurück in den Tempel → [kick].
     */
    private fun cutAftermath(post: CineScene, vantage: Vantage, a: AriusEntity, stand: Vec3d): Script = script {
        val foot = ArenaLayout.SPIRE.z - FarPlan.MASSIF_R
        val lookout = f.abs(Vec3d(ArenaLayout.CUT_X - 38.0, 0.0, foot - 34.0))
        val x = ArenaLayout.CUT_X
        val spireZ = ArenaLayout.SPIRE.z.toDouble()
        MusicCue.play(f, "horizon", 28)
        vantage.move(lookout)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.CLEAR, 8)
        val seamTo = f.abs(Vec3d(x, 0.0, foot + 6.0))
        // 1) Über dem Tempel folgt die Kamera dem senkrechten Schnitt bis exakt an den Bergfuß (26 Ticks, nahtlos in Shot 2).
        val gateZ = -32.0
        val plateauSpan = (foot - gateZ).coerceAtLeast(96.0)
        shown {
            for (p in f.audience()) dev.henny.hha.logic.boss.Cine.title(listOf(p), "hha_arius.feat.white_horizon", "hha_arius.feat.white_horizon.sub", 36)
            f.fxAll(FxKind.FLASH, stand, a = 0.24f, life = 6)
            f.fxAll(FxKind.HORIZON, f.abs(Vec3d(x, 0.0, gateZ)), Vec3d(0.0, 0.0, 1.0), a = plateauSpan.toFloat(), b = 4f, life = 28)
            f.farSound("white_line.release", seamTo, 3.0f, 0.65f)
        }
        post.line(
            f.abs(Vec3d(x - 18.0, 42.0, -82.0)), f.abs(Vec3d(x - 14.0, 56.0, -52.0)),
            f.abs(Vec3d(x, 12.0, -4.0)), f.abs(Vec3d(x, 82.0, foot + 56.0)), 26, 68.0, 62.0,
        )
        wait(26)
        // 2) Weite Totale von Fuß bis Horn: Die senkrechte Lichtklinge schneidet 1:1 synchron zu den Z-Scheiben durch den Berg.
        // Alle Kamera- und Blickpunkte liegen mit >20 Blöcken Sicherheitsabstand strikt vor/über dem Massiv.
        val mid = f.abs(Vec3d(x, 90.0, spireZ - 40.0))
        shown {
            f.farSound("cut.split", mid, 4.8f, 0.62f)
            // Tiefes Grollen unter dem Spalten: Fels und Eis geben nach.
            f.farVanilla(net.minecraft.sound.SoundEvents.AMBIENT_BASALT_DELTAS_ADDITIONS.value(), mid, 4.0f, 0.5f)
        }
        post.line(
            f.abs(Vec3d(x - 60.0, 134.0, foot - 158.0)), f.abs(Vec3d(x - 44.0, 156.0, foot - 138.0)),
            f.abs(Vec3d(x, 96.0, spireZ - 118.0)), f.abs(Vec3d(x, 162.0, spireZ - 58.0)), 56, 64.0, 58.0,
        )
        // Zuerst alle Blöcke der gesamten Schnitt-Ebene (inkl. Gipfelarena, Mauer, Krone & Horn) sichern und linear in Z spalten!
        // Gestaffeltes Fels- & Eisknirschen exakt an den Landmarken (Nordflanke, Schulter, Horn, Südflanke, Durchbruch).
        yieldAll(splitFull(animate = true, slices = 24, playSounds = false) { slice ->
            shown {
                when (slice) {
                    3 -> {
                        f.farSound("glacier.crack", mid, 3.4f, 0.68f)
                        f.farVanilla(net.minecraft.sound.SoundEvents.BLOCK_DEEPSLATE_BREAK, mid, 3.0f, 0.52f)
                    }
                    8 -> {
                        f.farSound("ridge.break", mid, 3.2f, 0.58f)
                        f.farVanilla(net.minecraft.sound.SoundEvents.BLOCK_BASALT_BREAK, mid, 3.2f, 0.48f)
                    }
                    12 -> {
                        f.farSound("glacier.crack", mid, 3.8f, 0.56f)
                        f.farVanilla(net.minecraft.sound.SoundEvents.BLOCK_GLASS_BREAK, mid, 2.6f, 0.46f)
                    }
                    17 -> {
                        f.farSound("ridge.break", mid, 3.0f, 0.66f)
                        f.farVanilla(net.minecraft.sound.SoundEvents.BLOCK_DEEPSLATE_BREAK, mid, 2.8f, 0.44f)
                    }
                    22 -> {
                        f.farSound("glacier.collapse", mid, 4.4f, 0.46f)
                    }
                }
            }
        })
        for (i in 24 until 56) {
            if (i % 14 == 0) f.calls.roomShake(lookout, 4.5f - i / 24f, 200f)
            shown {
                when (i) {
                    32 -> {
                        f.farVanilla(net.minecraft.sound.SoundEvents.BLOCK_BASALT_BREAK, mid, 2.2f, 0.40f)
                        f.farSound("seal.blizzard", mid, 2.4f, 0.55f)
                    }
                    44 -> f.farSound("glacier.crack", mid, 1.9f, 0.50f)
                }
            }
            yield(Unit)
        }
        // 3) Freier, hoher Überblick über die gespaltene Gipfelarena: Kamera und Blicklinie bleiben überall >12 Blöcke über
        //    Eiskrone, Ringmauer und zentralem Eishorn (Blickpunkt strikt im freien Luftraum vor dem Horn, nie darin).
        val summitY = FarPlan.SHOULDER_Y
        post.line(
            f.abs(Vec3d(x + 32.0, summitY + 56.0, spireZ - 88.0)), f.abs(Vec3d(x - 24.0, summitY + 60.0, spireZ - 86.0)),
            f.abs(Vec3d(x, summitY + 19.0, spireZ - 18.0)), f.abs(Vec3d(x, summitY + 22.0, spireZ - 16.0)), 44, 58.0, 55.0,
        )
        shown { f.sceneText("cut_after") }
        // Fortschritt still vergeben (frame=task, show_toast=false -> kein Achievement-Fanfaren-Sound über der Szene).
        for (id in f.cutSurvivors) f.server.playerManager.getPlayer(id)?.let { Advancements.grant(it, "under_the_line") }
        wait(6)
        shown { f.farSound("glacier.crack", mid, 1.1f, 0.44f) }
        wait(38)
        // 4) Whiteout: zurück in den Tempel.
        shown { f.farSound("seal.blizzard", lookout, 2.6f, 0.62f) }
        f.atmos(dev.henny.hha_arius.net.AtmosMode.BLIZZARD, 16)
        post.once { Cine.fade(it, 0xF2F6FA, 0.0, 1.0, 16) }
        wait(20)
        vantage.restore()
        wait(4)
        yieldAll(kick(post))
    }

    /**
     * Sammelt alle Nicht-Luft-Blöcke entlang der gesamten Schnittebene (x ≈ [ArenaLayout.CUT_X], von Bergfuß bis über
     * Gipfelarena, Ringmauer, Eiskrone und Horn), sichert sie absturzsicher über [SplitRecovery] und spaltet sie in
     * [slices] sauberen Scheiben von Norden nach Süden.
     */
    fun splitFull(animate: Boolean = true, slices: Int = 24, playSounds: Boolean = true, onSlice: ((Int) -> Unit)? = null): Script = script {
        val z0 = (ArenaLayout.SPIRE.z - FarPlan.MASSIF_R - 6).toInt()
        val z1 = (ArenaLayout.SPIRE.z + FarPlan.MASSIF_R + 6).toInt()
        val cutX = kotlin.math.floor(ArenaLayout.CUT_X).toInt()
        val maxY = ArenaLayout.SPIRE_TOP + 38
        val collected = ArrayList<Pair<BlockPos, net.minecraft.block.BlockState>>(48_000)
        val pos = BlockPos.Mutable()
        for (z in z0..z1) {
            for (dx in -8..8) {
                val x = cutX + dx
                if (FarPlan.inCore(x, z)) continue
                for (y in 0..maxY) {
                    if (!SplitRecovery.inCutPlane(x, y, z)) continue
                    pos.set(f.origin.x + x, f.origin.y + y, f.origin.z + z)
                    val st = f.world.getBlockState(pos)
                    if (!st.isAir) collected.add(pos.toImmutable() to st)
                }
            }
        }
        if (collected.isEmpty()) return@script
        // Zuerst dauerhaft auf Platte sichern (Absturzsicherheit mitten in der Szene!).
        SplitRecovery.saveSnapshot(f.world, f.slot, z0, z1, collected)
        activeSplit = collected
        splitActive = true
        if (animate) {
            val footStart = f.abs(Vec3d(ArenaLayout.CUT_X, 0.0, z0.toDouble()))
            val spanZ = (z1 - z0).toFloat().coerceAtLeast(180f)
            // Laufzeit deckt sowohl die Bergspaltung (slices) als auch die nachziehenden Frostschleier bis in Shot 3 ab.
            f.fxAll(FxKind.HORIZON, footStart, Vec3d(0.0, 0.0, 1.0), a = spanZ, b = 5f, life = slices + 76)
            if (playSounds) {
                f.ambient("cut.split", f.abs(Vec3d(ArenaLayout.CUT_X, 90.0, ArenaLayout.SPIRE.z.toDouble())), 4.8f, 0.62f)
                f.ambient("glacier.collapse", footStart, 4.4f, 0.46f)
            }
            f.calls.roomShake(footStart, 8f, 90f)
        }
        val zSpan = (z1 - z0 + 1).coerceAtLeast(1)
        if (slices <= 1) {
            for ((bp, _) in collected) f.edits.clear(bp)
            onSlice?.invoke(0)
        } else {
            // Lineare Z-Scheiben statt gleicher Blockzahl: so bleibt die physische Kluft 1:1 synchron zur Lichtklinge
            // (vorher eilte der dünne Nordfuß der Klinge um ~70 Blöcke voraus).
            val buckets = Array(slices) { ArrayList<BlockPos>() }
            for ((bp, _) in collected) {
                val relZ = bp.z - f.origin.z
                val b = (((relZ - z0) * slices) / zSpan).coerceIn(0, slices - 1)
                buckets[b].add(bp)
            }
            for (slice in 0 until slices) {
                for (bp in buckets[slice]) f.edits.clear(bp)
                onSlice?.invoke(slice)
                yield(Unit)
            }
        }
    }

    /**
     * Goldene Yang-Wiederherstellung: setzt alle in [activeSplit] gesicherten Originalblöcke über [ticks] Ticks von
     * unten nach oben exakt in ihren Ausgangszustand zurück und räumt das Absturz-Backup erst nach dem letzten Block ab.
     */
    fun restoreWithYang(ticks: Int): Script = script {
        val list = activeSplit
        if (list.isEmpty()) {
            splitActive = false
            SplitRecovery.clearSnapshot(f.server, f.slot)
            return@script
        }
        val byY = list.sortedBy { it.first.y }
        val step = maxOf(1, (byY.size + ticks - 1) / ticks)
        var idx = 0
        var t = 0
        while (idx < byY.size) {
            val end = minOf(byY.size, idx + step)
            val batch = ArrayList<BlockPos>(end - idx)
            for (k in idx until end) batch.add(byY[k].first)
            f.edits.revert(batch)
            for (k in idx until end) {
                val (p, st) = byY[k]
                if (f.world.getBlockState(p) != st) {
                    f.world.setBlockState(p, st, ArenaEdits.FLAGS)
                    dev.henny.hha_arius.arena.HighlandView.changed(f.world, p.x, p.z)
                }
            }
            if (t % 12 == 0) {
                val sample = byY[(idx + end) / 2].first
                val at = Vec3d.ofCenter(sample)
                shown { Vfx.shockwave(f.world, Vfx.Style.GOLD, at, 16f, 18, 2.0f) }
                f.farSound("resonance.chime", at, 4f, 0.8f + (t / ticks.toFloat()) * 0.45f)
            }
            idx = end
            t++
            yield(Unit)
        }
        activeSplit = emptyList()
        splitActive = false
        SplitRecovery.clearSnapshot(f.server, f.slot)
    }

    /** Kompatibilitäts-Helfer für Einzeltests (spaltet sofort ohne Animation). */
    fun split(animate: Boolean = true) {
        f.task(splitFull(animate = animate, slices = if (animate) 24 else 1))
    }

    /**
     * Übergang I → II, Teil 1: Er wirft alle aus dem Tempel (seit 1.10.0 neu inszeniert, Crix: „die Szene, wo er dich
     * aus dem Tempel haut, ist immer noch schlecht“). Läuft in der Kamera-Szene von [theCut], kein Schaden. Alles hängt
     * an der Torachse, damit Bildaufbau und Flugbahnen feststehen:
     * 1. **Aufbau** (aus dem Weiß, 40 Ticks): Über die Schultern der Spieler, die im Mittelgang vor dem Tor stehen, der
     *    Blick nach Norden auf ihn auf der Altarstufe. Der Wind sammelt sich, die Zeile „Raus.“, die Kamera drückt leise
     *    heran.
     * 2. **Windschritt** (8 Ticks): Schnitt halbhoch aus dem Gang; er reißt in einer Lichtspur vor sie.
     * 3. **Aufprallbild** (12 Ticks): von schräg oben hinter ihm – Blitz, Zeitlupe, die Musik reißt ab, alle hängen
     *    in der Luft (Treffer-Stopp).
     * 4. **Flug**: Jeder fliegt in lesbarem Bogen durch die Toröffnung (Bahn so gelegt, dass sie die Öffnung trifft, Scheitel
     *    unter dem Torsturz) auf seinen Landeplatz; die **Verfolgerkamera** hängt hinter dem eigenen Körper.
     * 5. **Aufprall und Aufstehen** ([dev.henny.hha_arius.client.fx.KickAnim], für alle sichtbar): Schnee stiebt, die Kamera
     *    bleibt tief beim Spieler, der Blick endet zum Tempel (nicht noch einmal auf den Berg). Arius kehrt außerhalb des
     *    Bildes auf die stehengebliebene Stufe zurück.
     */
    private fun kick(scene: CineScene): Script = script {
        val a = f.arius ?: return@script
        val players = f.players().filter { f.canSequence(it) }
        val gate = f.abs(Vec3d.of(ArenaLayout.GATE)).add(0.5, 0.0, 0.5)
        val stand = a.entityPos
        val y0 = f.floorY
        MusicCue.play(f, "kick", 20)
        // Der Whiteout klärt sich: Man sieht den Tritt und danach den gespaltenen Berg.
        f.atmos(dev.henny.hha_arius.net.AtmosMode.CLEAR, 24)
        // Startpunkte im Mittelgang (|x| <= 2,5, frei von Säulen) so, dass die gerade Flugbahn zum Landeplatz die
        // Toröffnung trifft (dort |x| <= 2): x(z) läuft linear vom Start zur Landung.
        val launchZ = ArenaLayout.GATE.z - 7.0
        fun launchOf(i: Int): Pair<Vec3d, Vec3d> {
            val land = ArenaLayout.LANDING[i % ArenaLayout.LANDING.size]
            val k = (ArenaLayout.GATE.z + 0.5 - launchZ) / (land.z - launchZ)
            val xg = (land.x * 0.3).coerceIn(-1.8, 1.8)
            val x0 = ((xg - k * land.x) / (1.0 - k)).coerceIn(-2.5, 2.5)
            return f.abs(Vec3d(x0, 0.0, launchZ - (i / 3) * 1.4)) to f.abs(land)
        }
        val plan = players.mapIndexed { i, p -> p to launchOf(i) }
        // Aus dem Weiß: alle stehen schon im Gang vor dem Tor, Blick nach Norden.
        for ((p, lp) in plan) {
            Hold.drop(p)
            p.teleport(f.world, lp.first.x, y0, lp.first.z, emptySet(), 180f, 0f, false)
        }
        val center = if (plan.isEmpty()) f.abs(Vec3d(0.5, 0.0, launchZ)) else plan.map { it.second.first }.fold(Vec3d.ZERO) { acc, v -> acc.add(v) }.multiply(1.0 / plan.size)
        val ground = Vec3d(center.x, y0, center.z)
        val north = flatDir(ground, stand)
        // 1) Aufbau über die Schultern: Arius steht ruhig mit gesenkter Klinge auf der Altarstufe (kein Schwert-Aufladen).
        scene.once { Cine.fade(it, 0xF2F6FA, 1.0, 0.0, 18) }
        f.face(a, ground)
        a.play(AriusPose.IDLE)
        val shoulder = ground.subtract(north.multiply(3.2)).add(1.4, 1.75, 0.0)
        scene.line(shoulder, ground.subtract(north.multiply(2.4)).add(1.1, 1.75, 0.0), stand.add(0.0, 1.5, 0.0), stand.add(0.0, 1.55, 0.0), 24, 62.0, 52.0)
        wait(8)
        shown { f.say("kick", 40) }
        wait(16)
        // 2) Windschritt direkt vor die Spieler, dann klare Ganzkörper-Einstellung für die Tritt-Ausholbewegung:
        //    Gewicht verlagern -> Standbein pflanzen -> Knie hochziehen -> gerader Stoßtritt (Schwert bleibt tief hinten).
        val front = ground.add(north.multiply(2.4))
        scene.line(front.add(north.multiply(4.6)).add(-1.8, 2.3, 0.0), front.add(-3.0, 1.35, 2.1), front.add(0.0, 1.0, 0.0), front.add(0.0, 1.05, 0.35), 6, 66.0, 58.0)
        a.play(AriusPose.LUNGE, 4)
        cue("arius.wind_step", stand, 2.6f, 1.15f)
        shown { f.fx(FxKind.DASH_STREAK, stand, front, life = 10) }
        for (i in 1..4) {
            f.place(a, stand.lerp(front, i / 4.0).let { Vec3d(it.x, stand.y + (y0 - stand.y) * (i / 4.0), it.z) })
            yield(Unit)
        }
        f.face(a, ground)
        wait(2)
        // Dreiviertel-Seitenansicht auf Arius' ganzen Körper (Standbein, anziehendes rechtes Knie, gesenkte Klinge hinten).
        scene.line(front.add(-3.0, 1.35, 2.1), front.add(-2.75, 1.25, 1.75), front.add(0.0, 1.05, 0.35), front.add(0.0, 1.05, 0.55), 16, 58.0, 50.0)
        a.play(AriusPose.KICK_PREP, 16)
        wait(5)
        cue("frost_step", front, 1.6f, 0.78f)
        wait(11)
        // 3) Aufprallbild: Treffer-Stopp, Arius hält das gestreckte Trittbein; alle hängen in der Luft; Schleuderanimation startet.
        val hitstop = 12
        val recover = 46
        for ((p, lp) in plan) if (f.canSequence(p)) {
            Hold.drop(p)
            Hold.allowLong(p, 90)
            val start = lp.first.add(0.0, 0.7, 0.0)
            Hold.freeze(p, 30, start)
            val fl = Ballistic.ticks(lp.second.y - start.y, 0.9)
            Entrance.storyFx(f, FxKind.KICK_FLING, start, a = hitstop.toFloat(), b = fl.toFloat(), life = hitstop + fl + recover, e1 = p)
        }
        MusicCue.duck(f, 0f, 14)
        val south = north.negate()
        shown {
            f.fxAll(FxKind.FLASH, front, a = 0.45f, life = 6)
            f.fxAll(FxKind.TIME_SLOW, front, a = 0.9f, life = 14)
            f.fx(FxKind.GALE, front.add(0.0, 1.0, 0.0), south.multiply(16.0), a = 1f, life = 16)
            Vfx.shockwave(f.world, Vfx.Style.HEAVEN, front, 7f, 16, 2.4f)
            f.fx(FxKind.CLEAR_WAVE, front, a = 9f, life = 16)
        }
        // Szenenklänge über die Kamera (sceneSound): Bis 1.14 trug ein Stinger plus cut.strike den Treffer, beides fiel mit
        // dem Umbau des Tritts weg; jetzt wieder ein schwerer Körper unter Tritt und Aufprall.
        f.sceneSound("hold.impact", front, 3.2f, 0.62f)
        f.sceneSound("arius.kick", front, 3.4f, 0.68f)
        f.sceneSound("cut.strike", front, 3.0f, 0.72f)
        f.sceneSound(net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, front, 2.4f, 0.6f)
        f.calls.hitShake(front, 5f, 30f)
        val above = front.add(north.multiply(2.8)).add(-2.0, 2.8, 0.0)
        scene.line(above, above.add(north.multiply(0.35)).add(0.0, 0.25, 0.0), front.add(0.0, 1.0, 0.8), ground.add(south.multiply(0.8)).add(0.0, 1.15, 0.0), hitstop, 58.0, 54.0)
        wait(hitstop)
        // 4) Flug durch die Toröffnung, jeder mit Verfolgerkamera hinter sich.
        var flight = 24
        val launched = ArrayList<ServerPlayerEntity>()
        for ((p, lp) in plan) {
            if (!f.canSequence(p)) continue
            val (from, landing) = lp
            val start = from.add(0.0, 0.7, 0.0)
            val v = Ballistic.velocity(start, landing, 0.9)
            flight = maxOf(flight, Ballistic.ticks(landing.y - start.y, 0.9))
            if (Hold.isHeld(p)) Hold.switchToLaunch(p, v, flight + 16, landing, landing) else Hold.launch(p, v, flight + 16, landing, landing)
            launched += p
        }
        MusicCue.play(f, "silence", 1)
        // Luftzug der Weggetretenen beim Abflug (an der Verfolgerkamera hörbar), dann der Windstoß durchs Tor.
        val whoosh = ground.add(0.0, 1.2, 0.0)
        f.sceneSound("arius.wind_step", whoosh, 2.6f, 0.55f)
        f.sceneSound(net.minecraft.sound.SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), whoosh, 2.2f, 0.7f)
        cue("arius.wind_step", gate, 3f, 0.6f)
        if (!Cine.muted) for (i in 0 until 40) {
            val gx = gate.x + rnd.nextInt(5) - 2
            val gy = gate.y + rnd.nextInt(8)
            f.world.spawnParticles(ParticleTypes.SNOWFLAKE, gx, gy + 0.5, gate.z, 3, 0.4, 0.4, 0.4, 0.15)
        }
        val landing0 = f.abs(ArenaLayout.LANDING[0])
        val lead = launched.firstOrNull()
        // Darunter (Fallback, falls die Verfolgerkamera nicht greift): von hinten durch das Tor.
        scene.line(ground.add(north.multiply(4.0)).add(0.0, 2.4, 0.0), gate.add(0.0, 2.6, -2.0), gate.add(0.0, 2.0, 0.0), landing0.add(0.0, 1.0, 0.0), flight, 74.0, 78.0, lookAt = lead, lookOffset = 1.0)
        val watchers = f.audience()
        CineSafety.chase(watchers.filter { it in launched }, flight + 2, 6.5, 1.6)
        if (lead != null) CineSafety.chase(watchers.filter { it !in launched }, flight + 2, 6.5, 1.6, target = lead)
        // Arius kehrt außerhalb des Bildes auf die stehengebliebene Stufe zurück (dort beginnt der Auftritt).
        f.place(a, f.abs(ArenaLayout.WATCH_SEAT))
        a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
        a.play(AriusPose.IDLE)
        val landed = HashSet<java.util.UUID>()
        fun landings(i: Int) {
            for (p in launched) {
                if (p.uuid in landed || i < 4) continue
                if (p.isOnGround || !Hold.isHeld(p)) {
                    landed.add(p.uuid)
                    shown {
                        f.fx(FxKind.CLEAR_WAVE, p.entityPos, a = 3.5f, life = 14)
                        f.world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.SNOW_BLOCK.defaultState), p.x, p.y + 0.2, p.z, 24, 0.6, 0.2, 0.6, 0.15)
                    }
                    landThud(p.entityPos)
                    // Der Blick endet zum Tempel: Dort geht die Szene weiter.
                    p.teleport(f.world, p.x, p.y, p.z, emptySet(), 180f, -4f, false)
                }
            }
        }
        for (i in 0 until flight + 2) {
            landings(i)
            yield(Unit)
        }
        // 5) Aufprall und Aufstehen, tief und nah am Spieler (kein Schwenk mehr zurück auf den Berg).
        MusicCue.play(f, "silence", 10)
        val landFocus = lead?.entityPos?.add(0.0, 0.7, 0.0) ?: landing0.add(0.0, 0.7, 0.0)
        scene.line(landFocus.add(3.8, 0.55, -3.0), landFocus.add(2.7, 0.75, -2.4), landFocus, landFocus.add(0.0, 0.45, 0.0), recover, 58.0, 50.0, lookAt = lead, lookOffset = 0.75)
        for (i in 0 until recover) {
            landings(flight + 2 + i)
            yield(Unit)
        }
        scene.home(12)
        wait(12)
    }

    /**
     * Übergang I → II, Teil 2: Nivaris' Auftritt an der Nadel (Sturm, Enthüllung, Abflug, Landung vor den Landeplätzen,
     * eigene Szene, überspringbar), dann ihr Name und Arius' Zeilen.
     */
    fun arrival(): Script = script {
        yieldAll(entrance(f))
        val d = f.nivaris ?: return@script
        val spot = d.entityPos
        d.play(NivarisPose.ROAR)
        f.ambient("nivaris.roar", spot, 5f, 0.85f)
        f.say("dragon.name", 50)
        val fresh = newcomers("scene.dragon")
        if (fresh.isNotEmpty()) f.sceneText("dragon", 70, fresh)
        markHeard("scene.dragon")
        wait(70)
        // Sie steht gespannt vor den Spielern bereit zum Abholen (der Ritt folgt in [toAerie]).
        d.play(NivarisPose.IDLE)
        val politeFresh = newcomers("dragon.polite")
        f.say(if (politeFresh.isNotEmpty()) "dragon.polite" else "dragon.polite_short", 80)
        markHeard("dragon.polite")
        wait(40)
        yieldAll(waitForFar())
        yieldAll(toAerie())
        yieldAll(glacier())
    }

    private fun toAerie(): Script = script {
        val d = f.nivaris ?: return@script
        val a = f.arius ?: return@script
        val aerie = f.abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5)
        val seat = f.abs(ArenaLayout.AERIE_SEAT)
        val finish = {
            for (p in f.audience()) {
                Hold.drop(p)
                if (p.vehicle === d) p.stopRiding()
            }
            CarryFrameS2C.send(f, CarryFrameS2C(d.id, emptyList(), 0f, emptyList()))
            RideSeats.unperch(f, a)
            RideSeats.clear(f)
            d.bodyRoll = 0f
            d.wingTuck = 0f
            d.flying = false
            f.place(d, aerie)
            f.face(d, aerie.add(10.0, 0.0, 0.0))
            d.play(NivarisPose.IDLE)
            f.place(a, seat)
            f.face(a, aerie)
            a.play(AriusPose.SEATED)
            a.bladeState = AriusBlade.NONE
            for ((i, p) in f.players().withIndex()) {
                if (p.squaredDistanceTo(aerie) > (ArenaLayout.AERIE_R + 6.0) * (ArenaLayout.AERIE_R + 6.0) || p.y > aerie.y + 6.0 || p.y < aerie.y - 4.0) {
                    val s = clearLanding(f.abs(ArenaLayout.AERIE_LANDING[i % ArenaLayout.AERIE_LANDING.size]))
                    p.teleport(f.world, s.x, s.y, s.z, emptySet(), 90f, 0f, false)
                }
                p.onLanding()
            }
        }
        val scene = CineScene(f) { finish() }
        yieldAll(scene.play(script { yieldAll(carry(scene, d, a, aerie, seat)) }))
        finish()
        f.atmos(dev.henny.hha_arius.net.AtmosMode.AERIE, 60)
    }

    private fun carry(scene: CineScene, d: NivarisEntity, a: AriusEntity, aerie: Vec3d, seat: Vec3d): Script = script {
        val start = d.entityPos
        val perch = a.entityPos
        val riders = f.players().take(4)
        val lead = riders.firstOrNull()
        val ram = lead?.entityPos ?: f.abs(ArenaLayout.LANDING[0])
        val lane = (ram.x - f.origin.x).coerceIn(-6.5, 6.5)
        val impact = Vec3d(f.origin.x + lane, start.y, ram.z + 9.15)
        val drop = aerie.add(62.0, 23.0, -16.0)
        val pickup = f.abs(Vec3d(-22.5, 16.5, -63.0))
        val pass = seat.add(7.2, -0.5, 5.2)
        val rail = DragonRail.carryRail(f, start, impact, pickup, drop, pass, aerie)
        val length = DragonRail.length(rail)
        val runIn = DragonRail.carryRunIn(f, start)
        fun at(q: Double) = DragonRail.at(rail, q)
        fun distance(p: Vec3d) = rail.indices.minBy { rail[it].squaredDistanceTo(p) } * DragonRail.STEP
        val hitQ = distance(impact)
        val pickupQ = distance(pickup)
        val dropQ = distance(drop)
        val passQ = distance(pass)
        val landQ = length - 18.0

        fun direction(q: Double): Vec3d = at(q + 1.0).subtract(at(q - 1.0)).normalize()
        fun spine(q: Double, settle: Double = 0.0): List<Vec3d> = DragonRail.carrySpine(rail, runIn, q, settle)
        fun ballistic(from: Vec3d, to: Vec3d, t: Int, duration: Int, gravity: Double): Vec3d =
            from.lerp(to, t.toDouble() / duration).add(0.0, gravity * t * (duration - t) / 2.0, 0.0)

        val ownsAnchor = HashSet<java.util.UUID>()
        fun frame(q: Double, grip: Float = 0f, roll: Float = 0f, settle: Double = 0.0, positions: List<Vec3d>? = null) {
            val pos = at(q)
            f.place(d, pos)
            f.face(d, pos.add(direction(q)))
            d.bodyRoll = roll
            val points = spine(q, settle).toMutableList()
            if (settle > 0.0) {
                val shoulder = points[NivarisShape.FORE_GLIED]
                val facing = flatDir(shoulder, positions?.firstOrNull() ?: aerie.add(58.0, 0.0, 0.0))
                val look = facing.rotateY((0.30 * sin(PI * settle)).toFloat())
                // A short glance across the arena settles on the lead player, with the jaw kept above the ice.
                for (i in 0 until NivarisShape.FORE_GLIED) {
                    val forward = NivarisShape.FORE_GLIED - i
                    val level = shoulder.add(look.multiply(forward * NivarisShape.SEGMENT))
                    val neckLift = (0.72 * sin(PI * settle) + 0.65 * settle) * forward / NivarisShape.FORE_GLIED
                    points[i] = Vec3d(level.x, points[i].y + neckLift, level.z)
                }
            }
            val actors = riders.mapIndexedNotNull { i, p ->
                if (!p.isAlive || p.isRemoved || p.entityWorld !== f.world) return@mapIndexedNotNull null
                val location = positions?.get(i) ?: return@mapIndexedNotNull null
                if (!Cine.muted && p.uuid in ownsAnchor) Hold.moveAnchor(p, location)
                CarryFrameS2C.Rider(p.id, location, i, grip)
            }
            CarryFrameS2C.send(f, CarryFrameS2C(d.id, points, roll, actors))
        }

        // Ground profile, then the same low shot closes on the snout and the impact.
        MusicCue.play(f, "carry", 40)
        d.play(NivarisPose.TAKEOFF, 12)
        d.wingTuck = 1f
        shown { f.say("dragon.carry", 60) }
        val hitSide = Vec3d(7.8, 2.45, 0.7)
        val impactFocus = ram.add(0.0, 1.2, 1.6)
        scene.line(ram.add(12.5, 3.1, 6.0), ram.add(hitSide), start.lerp(ram, 0.65).add(0.0, 1.6, 0.0), impactFocus, 32, 56.0, 53.0)
        wait(12)
        val chargeTicks = 20
        d.flying = true
        d.play(NivarisPose.DIVE, 5)
        for (t in 1..chargeTicks) {
            val u = t.toDouble() / chargeTicks
            frame(hitQ * u * u)
            yield(Unit)
        }

        val launch = riders.map { it.entityPos }
        for (p in riders) if (!Cine.muted) {
            Hold.drop(p)
            Hold.allowLong(p, 120)
            Hold.freeze(p, 112)
            ownsAnchor.add(p.uuid)
        }
        val stopTicks = 3
        f.farSound("nivaris.land", ram, 4f, 1.1f)
        f.calls.roomShake(ram, 3.5f, 38f)
        scene.rawLine(ram.add(hitSide), ram.add(hitSide), impactFocus, impactFocus, stopTicks, 53.0)
        repeat(stopTicks) {
            frame(hitQ, positions = launch)
            yield(Unit)
        }

        // The falling endpoint is the feet of the hanging pose on the actual rendered dorsal frame.
        // The same rail advances throughout the arc and determines the body's pose on the catch tick.
        val airTicks = 40
        val catchQ = hitQ + airTicks * 0.91
        val catchSpine = spine(catchQ)
        val catches = riders.indices.map { CarryGrip.at(catchSpine, it, 0f).feet }
        for (p in riders) shown {
            f.fxAll(FxKind.KICK_FLING, p.entityPos, a = -1f, b = airTicks.toFloat(), life = airTicks + 1, e1 = p)
        }
        d.play(NivarisPose.GLIDE)
        val catchLook = catches.firstOrNull() ?: at(catchQ)
        val catchCam = catchLook.add(7.5, 4.8, 3.1)
        val apexLook = if (launch.isNotEmpty()) ballistic(launch[0], catches[0], 18, airTicks, 0.08) else catchLook
        val apexCam = apexLook.add(8.0, 2.0, 3.5)
        val airCamStart = ram.add(hitSide)
        fun airCamAt(step: Int): Vec3d = if (step <= 18) {
            val u = (step / 18.0).coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
            airCamStart.lerp(apexCam, u)
        } else {
            val u = ((step - 18) / (airTicks - 18).toDouble()).coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
            apexCam.lerp(catchCam, u)
        }
        fun airLookAt(step: Int): Vec3d = if (launch.isNotEmpty()) {
            ballistic(launch[0], catches[0], step.coerceIn(0, airTicks), airTicks, 0.08).add(0.0, 0.9, 0.0)
        } else {
            impactFocus.lerp(catchLook, (step.toDouble() / airTicks).coerceIn(0.0, 1.0))
        }
        for (t in 1..airTicks) {
            if (t == 14) d.wingTuck = 0f
            val positions = launch.indices.map { ballistic(launch[it], catches[it], t, airTicks, 0.08) }
            frame(hitQ + t * 0.91, grip = ((t - airTicks + 5) / 5f).coerceIn(0f, 1f), positions = positions)
            // Drive post-ram flight camera on the same MotionClient timeline as CarryFrameS2C (zero unbuffered lookAt wobble).
            val fov = if (t <= 18) 53.0 + 2.0 * (t / 18.0) else 55.0 - 2.0 * ((t - 18) / (airTicks - 18).toDouble())
            scene.track(airCamAt(t), airCamAt(minOf(t + 1, airTicks)), airLookAt(t), airLookAt(minOf(t + 1, airTicks)), fov)
            yield(Unit)
        }
        cue("glacier.crack", catchLook, 2f, 1.35f)
        f.farSound("nivaris.wing", at(catchQ), 5f, 0.7f)
        val hangTicks = 15
        val hangEnd = catchQ + hangTicks * 0.91
        val hangLookEnd = CarryGrip.at(spine(hangEnd), 0, 0f).feet
        val hangCamEnd = hangLookEnd.add(11.0, 5.0, 4.0)
        for (t in 1..hangTicks) {
            if (t == 4) {
                a.play(AriusPose.IDLE)
                f.face(a, pickup)
            }
            val q = catchQ + t * 0.91
            val points = spine(q)
            val positions = riders.indices.map { i -> CarryGrip.at(points, i, 0f).feet }
            frame(q, grip = 1f, positions = positions)
            // Keep camera on the same MotionClient timeline as the spine/riders so there is zero relative wobble.
            val u0 = ((t - 1).toDouble() / hangTicks).let { it * it * (3.0 - 2.0 * it) }
            val u1 = (t.toDouble() / hangTicks).let { it * it * (3.0 - 2.0 * it) }
            val look0 = positions.firstOrNull()?.add(0.0, 0.85, 0.0) ?: CarryGrip.at(points, 0, 0f).feet.add(0.0, 0.85, 0.0)
            val look1 = CarryGrip.at(spine(minOf(hangEnd, q + 0.91)), 0, 0f).feet.add(0.0, 0.85, 0.0)
            scene.track(catchCam.lerp(hangCamEnd, u0), catchCam.lerp(hangCamEnd, u1), look0, look1, 53.0 + 6.0 * u0)
            yield(Unit)
        }
        ownsAnchor.clear()

        fun passengerPositions(q: Double, roll: Float = 0f) = spine(q).let { points ->
            riders.indices.map { CarryGrip.at(points, it, roll).feet }
        }
        fun ownFor(p: ServerPlayerEntity, ticks: Int) {
            if (!Cine.muted && p.isAlive && p.entityWorld === f.world) {
                Hold.drop(p)
                Hold.allowLong(p, ticks + 20)
                Hold.freeze(p, ticks)
                ownsAnchor.add(p.uuid)
            }
        }
        // Keep the actors' actual positions within tracking range of the camera and of one another.
        var q = hangEnd
        val cruise = 1.12
        val rollStart = dropQ - 18.0
        var ariusMounted = false
        var tuckSeated = false
        var boardFrom: Vec3d? = null
        val boardSpan = 20.0
        val boardEndQ = maxOf(hangEnd + boardSpan, pickupQ)
        val boardStartQ = boardEndQ - boardSpan
        val boardLand = RideHooks.perchFeet(spine(boardEndQ), 0f)
        val approachEnd = maxOf(q + 45.0, dropQ - 94.0)
        fun travelTrack(qNow: Double, qNext: Double, offset: Vec3d, fov: Double) {
            val look0 = at(qNow).subtract(direction(qNow).multiply(8.0)).add(0.0, 2.5, 0.0)
            val look1 = at(qNext).subtract(direction(qNext).multiply(8.0)).add(0.0, 2.5, 0.0)
            scene.track(look0.add(offset), look1.add(offset), look0, look1, fov)
        }
        val riderShotStart = distance(f.abs(Vec3d(-103.0, 40.0, -77.0)))
        val riderShotEnd = riderShotStart + 64.0
        val pickupShotEnd = minOf(riderShotStart - 6.0, boardEndQ + 14.0)
        val pickupCam0 = f.abs(Vec3d(-1.5, 14.5, -42.0))
        val pickupCam1 = f.abs(Vec3d(-13.0, 26.5, -44.0))
        val pickupLook0 = perch.add(-3.0, 7.5, -1.0)
        val pickupLook1 = boardLand.add(0.0, 1.4, 0.0)
        val totalTravelTicks = kotlin.math.ceil((rollStart - q) / cruise).toInt() + 40
        // Single uninterrupted Hold for the entire carry flight (eliminates periodic drop/freeze hitches every 65 ticks).
        for (p in riders) ownFor(p, totalTravelTicks)
        var travelTicks = 0
        var approachShot = false
        while (q < rollStart) {
            val qPrev = q
            q = minOf(rollStart, q + cruise)
            travelTicks++
            frame(q, grip = 1f, positions = passengerPositions(q))
            if (!ariusMounted && q >= boardStartQ) {
                if (boardFrom == null) {
                    a.play(AriusPose.LUNGE, 18)
                    boardFrom = perch
                    cue("arius.wind_step", perch, 2.8f, 1.05f)
                    shown { f.fxAll(FxKind.CLEAR_WAVE, perch.add(0.0, 0.3, 0.0), a = 4.5f, life = 14) }
                    shown { f.fxAll(FxKind.DASH_STREAK, perch.add(0.0, 1.0, 0.0), boardLand.add(0.0, 2.0, 0.0), life = 14) }
                }
                val u = ((q - boardStartQ) / boardSpan).coerceIn(0.0, 1.0)
                val target = RideHooks.perchFeet(spine(q), 0f)
                val arc = 4.0 * u * (1.0 - u) * 7.8
                val pos = boardFrom!!.lerp(target, u).add(0.0, arc, 0.0)
                f.place(a, pos)
                val turn = ((u - 0.45) / 0.55).coerceIn(0.0, 1.0)
                val aim = target.subtract(pos).let {
                    val flat = Vec3d(it.x, 0.0, it.z)
                    if (flat.lengthSquared() < 1e-4) direction(q) else flat.normalize()
                }.lerp(direction(q), turn * turn * (3.0 - 2.0 * turn))
                f.face(a, pos.add(aim))
                if (!tuckSeated && u >= 0.80) {
                    a.play(AriusPose.SEATED)
                    tuckSeated = true
                }
                if (u == 1.0) {
                    RideSeats.perch(f, a, d)
                    cue("frost_step", target, 2.0f, 1.1f)
                    shown { f.fxAll(FxKind.CLEAR_WAVE, target.add(0.0, 0.2, 0.0), a = 2.4f, life = 10) }
                    ariusMounted = true
                }
            }
            if (!approachShot && q >= approachEnd) {
                approachShot = true
                f.atmos(dev.henny.hha_arius.net.AtmosMode.AERIE, 50)
            }
            // Drive every camera segment of the carry flight on the exact same MotionClient timeline as the spine & riders.
            val qNext = minOf(rollStart, q + cruise)
            when {
                q < pickupShotEnd -> {
                    val span = (pickupShotEnd - hangEnd).coerceAtLeast(1.0)
                    val s0 = ((qPrev - hangEnd) / span).coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
                    val s1 = ((q - hangEnd) / span).coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
                    scene.track(pickupCam0.lerp(pickupCam1, s0), pickupCam0.lerp(pickupCam1, s1),
                        pickupLook0.lerp(pickupLook1, s0), pickupLook0.lerp(pickupLook1, s1), 66.0 - 10.0 * s0)
                }
                q in riderShotStart..<riderShotEnd -> {
                    val from = CarryGrip.at(spine(q), 0, 0f).feet
                    val to = CarryGrip.at(spine(qNext), 0, 0f).feet
                    val offset = Vec3d(3.2, 2.8, 9.0)
                    scene.track(from.add(offset), to.add(offset), from.add(0.0, 0.8, 0.0), to.add(0.0, 0.8, 0.0), 49.0)
                }
                q < approachEnd -> travelTrack(q, qNext, Vec3d(0.0, 12.0, 28.0), 65.0)
                else -> travelTrack(q, qNext, Vec3d(2.0, 12.0, 24.0), 62.0)
            }
            if (travelTicks % 30 == 0) cue("nivaris.wing", at(q), 4f, 0.75f)
            yield(Unit)
        }

        // South of the spine, both the roll and the throw share a sightline to the landing floor.
        val rollTicks = 42
        val releaseAt = 15
        val rollFrom = q
        val releaseQ = rollFrom + cruise * releaseAt
        val releaseRoll = RideSeats.roll(releaseAt / rollTicks.toDouble())
        val release = passengerPositions(releaseQ, releaseRoll)
        val releaseLead = release.firstOrNull() ?: at(releaseQ)
        val targets = riders.indices.map { clearLanding(f.abs(ArenaLayout.AERIE_LANDING[it])) }
        val fallTicks = 34
        val fallLead = targets.firstOrNull() ?: aerie
        fun rollAt(t: Int): Float = if (t >= rollTicks) 0f else RideSeats.roll(t / rollTicks.toDouble())
        fun throwSubject(t: Int): Vec3d {
            val distance = rollFrom + cruise * t
            if (t <= releaseAt) return CarryGrip.at(spine(distance), 0, rollAt(t)).feet
            return ballistic(releaseLead, fallLead, (t - releaseAt).coerceAtMost(fallTicks), fallTicks, 0.065)
        }
        fun throwCamera(subject: Vec3d, t: Int): Vec3d {
            val u = ((t - releaseAt).toDouble() / fallTicks).coerceIn(0.0, 1.0)
            return subject.add(3.4 + 1.1 * u, (fallLead.y + 1.75 - subject.y).coerceAtLeast(-0.45), 8.6 + 0.9 * u)
        }
        for (p in riders) ownFor(p, rollTicks + fallTicks + 42)
        for (t in 1..(releaseAt + fallTicks + 22)) {
            q = rollFrom + cruise * t
            val roll = rollAt(t)
            val subject = throwSubject(t)
            val next = throwSubject(t + 1)
            scene.track(throwCamera(subject, t), throwCamera(next, t + 1),
                subject.add(0.0, 0.85, 0.0), next.add(0.0, 0.85, 0.0), 52.0)
            if (t == 1) {
                // Die Rolle setzt ein: schwerer Flügelschlag und Luftzug um die Reiter (über die Szenenkamera hörbar).
                f.sceneSound("nivaris.wing", at(q), 4.5f, 0.72f)
                f.sceneSound("arius.wind_step", subject, 2.6f, 0.6f)
            }
            if (t == releaseAt) {
                f.sceneSound("nivaris.wing", at(q), 4.5f, 0.88f)
                f.sceneSound(net.minecraft.sound.SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), subject, 2.2f, 0.75f)
                for (p in riders) shown {
                    f.fxAll(FxKind.KICK_FLING, p.entityPos, a = 0f, b = (fallTicks - 6).toFloat(), life = fallTicks + 18, e1 = p)
                }
            }
            val falling = t - releaseAt
            val positions = if (falling <= 0) passengerPositions(q, roll)
                else release.indices.map { ballistic(release[it], targets[it], falling.coerceAtMost(fallTicks), fallTicks, 0.065) }
            val grip = (1f - falling / 5f).coerceIn(0f, 1f)
            frame(q, grip, roll, positions = positions)
            if (t == releaseAt + fallTicks) for (p in riders) {
                p.onLanding()
                landThud(p.entityPos)
            }
            yield(Unit)
        }

        // Arius steps off the low pass while the near side of the pedestal is still in frame.
        val dismountQ = maxOf(q, passQ - 2.0)
        d.wingTuck = 1f
        scene.line(seat.add(-11.0, 6.5, -3.0), seat.add(-8.5, 4.2, -1.0), seat.add(5.0, 1.9, 3.4),
            seat.add(0.0, 1.0, 0.0), ((dismountQ - q) / cruise).toInt() + 23, 62.0, 52.0)
        while (q < dismountQ) {
            q = minOf(dismountQ, q + cruise)
            frame(q, positions = targets)
            yield(Unit)
        }
        val dismount = RideHooks.perchFeet(spine(q), 0f)
        RideSeats.unperch(f, a)
        a.play(AriusPose.LAUNCH, 4)
        for (t in 1..14) {
            q = minOf(landQ, q + cruise)
            val u = t / 14.0
            f.place(a, dismount.lerp(seat, u).add(0.0, sin(u * PI) * 1.1, 0.0))
            f.face(a, aerie)
            frame(q, positions = targets)
            yield(Unit)
        }
        a.play(AriusPose.KNEEL)
        cue("hold.impact", seat, 2.2f, 0.82f)
        for (p in riders) Hold.drop(p)
        ownsAnchor.clear()
        fun landingProfile(from: Double, to: Double, fov: Double) {
            fun view(distance: Double): Pair<Vec3d, Vec3d> {
                val points = spine(distance)
                val focus = points[0].lerp(points[3], 0.53).add(0.0, 0.55, 0.0)
                val tangent = points[0].subtract(points[3])
                val forward = Vec3d(tangent.x, 0.0, tangent.z).normalize()
                val inside = Vec3d(forward.z, 0.0, -forward.x)
                return focus.add(inside.multiply(12.5)).add(forward.multiply(3.5)).add(0.0, 1.8, 0.0) to focus
            }
            val (startCam, startLook) = view(from)
            val (endCam, endLook) = view(to)
            scene.track(startCam, endCam, startLook, endLook, fov)
        }
        repeat(6) {
            q = minOf(landQ, q + cruise)
            frame(q, positions = targets)
            yield(Unit)
        }
        d.wingTuck = 0f
        a.play(AriusPose.SEATED)
        val landingSpeed = 1.18
        while (q < landQ) {
            q = minOf(landQ, q + landingSpeed)
            landingProfile(q, minOf(landQ, q + landingSpeed), 60.0)
            frame(q, positions = targets)
            yield(Unit)
        }

        // Brake into the same profile shot so the wings and forefeet stay in view.
        val brakeTicks = 30
        fun brakeDistance(t: Int): Double {
            val u = t.coerceAtMost(brakeTicks) / brakeTicks.toDouble()
            return landQ + (length - landQ) * (1.0 - (1.0 - u) * (1.0 - u))
        }
        d.play(NivarisPose.LAND, brakeTicks)
        f.farSound("nivaris.wing", at(landQ), 4.8f, 0.60f)
        for (t in 1..brakeTicks) {
            q = brakeDistance(t)
            landingProfile(q, brakeDistance(t + 1), 57.0)
            frame(q, positions = targets)
            yield(Unit)
        }
        d.flying = false
        d.play(NivarisPose.LIE_DOWN, 12)
        a.play(AriusPose.SEATED)
        f.farSound("nivaris.land", aerie, 5f, 0.8f)
        f.calls.roomShake(aerie, 4f, 60f)
        scene.once { Cine.title(it, "hha_arius.cine.aerie", "hha_arius.cine.aerie.sub", 55) }
        val finishCam = aerie.add(11.0, 3.7, -10.0)
        scene.rawLine(finishCam, aerie.add(13.5, 3.3, -8.0), aerie.add(3.8, 2.6, 0.0),
            aerie.add(5.5, 2.2, 0.0), 24, 53.0, 48.0)
        repeat(24) { t ->
            frame(length, settle = (t + 1) / 24.0, positions = targets)
            yield(Unit)
        }
        // Vor dem Rückweg zur Spielerkamera alle zu ihrem Kopf drehen: Die Kamera endet auf Solvane, nicht im Rücken.
        Entrance.faceAudience(f, spine(length, 1.0)[0])
        scene.home(18)
        repeat(18) {
            frame(length, settle = 1.0, positions = targets)
            yield(Unit)
        }
    }

    // --- II: Drachenhorst (früher Gletscherschwelle) ------------------------------------------------------------------

    fun glacier(): Script = script {
        f.setPhase(P.GLACIER)
        f.reviveAll(ArenaLayout.AERIE_LANDING)
        f.nivaris?.let { f.fxAll(FxKind.DRAGON_AURA, it.entityPos, life = 20 * 60 * 30, e1 = it) }
        f.arius?.let { a ->
            if (a.entityPos.squaredDistanceTo(f.abs(ArenaLayout.AERIE_SEAT)) > 4.0) {
                f.place(a, f.abs(ArenaLayout.AERIE_SEAT))
                f.face(a, f.arenaCenter)
                a.play(AriusPose.SEATED)
            }
        }
        f.dragonMax = BossConfig.health(760f, f.startCount)
        f.dragonHp = f.dragonMax
        f.dragonVulnerable = true
        f.ariusVulnerable = false
        f.music("music.glacier", 20)
        barrier()
        wait(40)
        var cycles = 0
        while (f.dragonHp > 0f) {
            runWhile(f.dragon.cycle()) { f.dragonHp > 0f }
            cycles++
            if (f.phaseAge % 600 < 20) barrier()
            // Seit 1.8.0 kein Plaudern mehr mitten im Kampf (unlesbar zwischen Ansagen und Treffern).
        }
        f.dragonVulnerable = false
        if (f.training == 2) {
            f.nivaris?.play(NivarisPose.EXHAUSTED)
            f.victory()
            return@script
        }
        yieldAll(collapse())
    }

    /** White Null: die Verbindung zwischen dir und ihm ist getrennt (nur Anzeige). */
    private fun barrier() {
        val a = f.arius ?: return
        Vfx.rune(f.world, Vfx.Style.STORM, Vec3d(a.x, a.y + 0.05, a.z), 2.4f, 640, a)
    }

    // --- Übergang II → III: Der Gletscher bricht, Verwandlung ----------------------------------------------------------

    /**
     * Übergang II → III seit 1.8.0 (Crix: „Die Cutscene, wie der Drache dich in die neue Arena bringt, ist lame … cooler
     * wäre, wenn man auf seinem Rücken ist, sich mit aller Kraft festhält und er dich weiter weg absetzt“):
     * 1. Szene: Arius pfeift auf seinem Felspfeiler, Solvane hebt erschöpft den Kopf und antwortet; sie streicht tief über
     *    die Spieler, Arius springt auf ihren Nacken. Titel „Halt dich fest!“.
     * 2. Ritt ohne Kamera (eigene Sicht, frei umsehen): alle sitzen auf ihrem Rücken; sie steigt aus dem Horst, fliegt dicht
     *    an der Nordflanke des Bergs entlang, taucht durch Sturm und Wolken und gleitet weit nach Osten aufs Feld.
     *    Drei Mal heißt es festhalten (Quick-Time-Events); wer scheitert, rutscht ab (Schaden), beim zweiten Mal fällt er
     *    und wird vom Lichtstrom aufgefangen.
     * 3. Szene: Landung, Absprung, Verwandlung.
     */
    fun collapse(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val flight = DragonRide(f)
        val intro = CineScene(f) { flight.settleIntro() }
        yieldAll(intro.play(flight.intro(intro)))
        yieldAll(flight.play())
        RideSeats.unperch(f, a)
        // Falls jemand mitten im Ritt neu verbunden hat: alle stehen auf dem Feld, niemand hängt mehr an ihr.
        for ((j, p) in f.players().withIndex()) {
            if (p.vehicle === d) p.stopRiding()
            if (p.squaredDistanceTo(f.abs(Vec3d.of(ArenaLayout.VAULT))) > (ArenaLayout.VAULT_R + 24.0) * (ArenaLayout.VAULT_R + 24.0)) {
                val s = f.abs(ArenaLayout.VAULT_LANDING[j % ArenaLayout.VAULT_LANDING.size])
                p.teleport(f.world, s.x, s.y + 0.2, s.z, emptySet(), 180f, 0f, false)
            }
            p.onLanding()
        }
        val scene = CineScene(f) {}
        yieldAll(scene.play(script { yieldAll(transformBody(scene)) }))
        yieldAll(vaultFight())
    }

    /** Wartet, bis die Fernlandschaft (mit dem Himmelsbalkon) fertig gebaut ist, und zeigt den Fortschritt. */
    private fun waitForFar(): Script = script {
        while (!f.farReady) {
            if (f.world.time % 10L == 0L) {
                val pct = (f.farProgress * 100).toInt()
                for (p in f.audience()) p.sendMessage(net.minecraft.text.Text.translatable("hha_arius.msg.forming_pct", pct), true)
            }
            yield(Unit)
        }
    }

    /** Die Verwandlung zur weißen Form, dann die Resonanz mit Nivaris (Konzept 5.4). Ohne Szene: [transform]. */
    fun transform(): Script = script {
        yieldAll(transformBody(null))
        yieldAll(vaultFight())
    }

    private fun transformBody(scene: CineScene?): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        // Phase ist hier noch COLLAPSE (Arenamitte = Horst); Kamera im Gewölbe um dessen Mitte klemmen.
        val vaultR = ArenaLayout.VAULT_R + 8.0
        fun clampToVault(pos: Vec3d, margin: Double = 2.0): Vec3d {
            val dx = pos.x - vault.x
            val dz = pos.z - vault.z
            val dist = sqrt(dx * dx + dz * dz)
            val r = vaultR - margin
            return if (dist <= r || dist < 1e-6) pos else Vec3d(vault.x + dx / dist * r, pos.y, vault.z + dz / dist * r)
        }
        val top = a.entityPos
        // 6a) Detail: er schlägt im Gewölbe auf, die Rüstung zerspringt. Tief, nah, langsam heran.
        val front = f.facing(a)
        val chest = vault.add(0.0, 1.1, 0.0)
        scene?.line(clampToVault(chest.add(front.multiply(3.4)).add(-0.8, -0.4, 0.0), 2.0), clampToVault(chest.add(front.multiply(2.5)).add(-0.6, -0.3, 0.0), 2.0),
            chest, chest.add(0.0, 0.3, 0.0), 40, 50.0, 40.0)
        a.play(AriusPose.TRANSFORM, AriusTimings.ARIUS_WINDUP[AriusPose.TRANSFORM])
        MusicCue.play(f, "transform", 16)
        MusicCue.sting(f, "phase")
        for (i in 1..10) {
            f.place(a, top.lerp(vault, (i / 10.0) * (i / 10.0)))
            yield(Unit)
        }
        cue("nivaris.land", vault, 3f, 1.4f)
        shown { f.fx(FxKind.FRAGMENTS, vault, a = 1f, life = 34, e1 = a) }
        cue("transform.shatter", vault, 4f)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.WHITE, 20)
        wait(30)
        a.form = AriusForm.WHITE
        a.eyeGlow = 100
        f.updateBars()
        a.bladeState = AriusBlade.NONE
        a.sheathed = false
        val side = Vec3d(-front.z, 0.0, front.x)
        val handShot = a.entityPos.add(0.0, 1.38, 0.0)
        val cameraFrom = clampToVault(handShot.add(front.multiply(4.3)).add(side.multiply(1.95)), 2.0)
        val cameraTo = clampToVault(handShot.add(front.multiply(3.8)).add(side.multiply(1.55)), 2.0)
        scene?.line(cameraFrom, cameraTo, handShot, handShot.add(0.0, 0.3, 0.0), 54, 53.0, 53.0)
        a.play(AriusPose.FORGE_ICE, 12)
        shown { f.sceneText("forge_ice", 54) }
        wait(20)
        Entrance.storyFx(f, FxKind.BLADE_FORM, handShot, life = 24, e1 = a)
        cue("core.form", vault, 2.5f, 1.5f)
        wait(18)
        a.bladeState = AriusBlade.ICE
        cue("resonance.chime", vault, 2f, 1.4f)
        wait(16)
        scene?.once { dev.henny.hha.logic.boss.Cine.title(it, "hha_arius.cine.heir", "hha_arius.cine.heir.sub", 70) }
        cue("transform.pulse", vault, 5f)
        shown { f.fx(FxKind.CLEAR_WAVE, vault, a = 12f, life = 22) }
        shown { Vfx.shockwave(f.world, Vfx.Style.HEAVEN, vault, 12f, 20, 1.5f) }
        for (pos in ArenaBuilder.snowAround(f.world, BlockPos.ofFloored(vault), 12)) f.edits.clear(pos)
        for (p in f.players()) if (p.squaredDistanceTo(vault) < 144.0) Fx.launchPlayer(p, Vec3d(p.x - vault.x, 0.0, p.z - vault.z).normalize().multiply(0.5).add(0.0, 0.25, 0.0))
        // Resonanz: Nivaris richtet sich hinter ihm auf.
        d.play(NivarisPose.RESONANCE)
        d.resonance = true
        d.eyeGlow = 100
        f.face(d, vault)
        shown { f.fx(FxKind.THREAD, vault, life = 40, e1 = a, e2 = d) }
        cue("resonance.chime", vault, 4f, 0.9f)
        wait(20)
        a.play(AriusPose.IDLE)
        d.play(NivarisPose.IDLE)
        // Rückweg zur Spielerkamera, bevor das Herzgewölbe beginnt.
        wait(22)
        scene?.home(18)
        wait(18)
    }

    // --- III: Herzgewölbe ---------------------------------------------------------------------------------------------------

    fun vaultFight(): Script = script {
        val a = f.arius ?: return@script
        f.setPhase(P.VAULT)
        f.reviveAll(ArenaLayout.VAULT_LANDING)
        f.ariusMax = BossConfig.health(640f, f.startCount)
        f.ariusHp = f.ariusMax
        f.dragonMax = BossConfig.health(500f, f.startCount)
        f.dragonHp = f.dragonMax
        f.dragonDowned = false
        f.ariusVulnerable = true
        f.dragonVulnerable = true
        a.form = AriusForm.WHITE
        // Er kämpft mit der Eisklinge; die echte Solvane ist der Drache (auch nach Überspringen, Abbruch, Training).
        a.bladeState = AriusBlade.ICE
        a.sheathed = false
        f.moves.resetStance()
        f.music("music.heart", 20)
        var whiteHoleDone = false
        var nextResonance = f.now + 260
        var resonanceIndex = 0
        f.ariusLeads = true
        markLead()
        while (f.ariusHp > 0f) {
            if (!f.dragonDowned && f.dragonHp <= 0f) downDragon()
            if (!whiteHoleDone && f.ariusHp <= f.ariusMax * 0.5f) {
                whiteHoleDone = true
                MusicCue.play(f, "white_hole", 30)
                yieldAll(WhiteHoleBlast(f).run()) // seit 1.11.0 mit Umkehr
                MusicCue.play(f, "heart", 40)
                continue
            }
            if (!f.dragonDowned && f.now >= nextResonance) {
                runWhile(resonance(resonanceIndex++)) { f.ariusHp > 0f }
                nextResonance = f.now + AriusTimings.RESONANCE_EVERY
                switchLead()
                continue
            }
            val dragonLed = !f.dragonDowned && !f.ariusLeads
            val turn = when {
                f.dragonDowned -> f.moves.turnWhiteSolo()
                f.ariusLeads -> f.moves.turnWhite()
                else -> f.dragon.turnVault()
            }
            val leader = Runner(turn)
            var support: Runner? = null
            var supportAt = f.now + if (f.ariusLeads) 34 else 12
            var interrupted = false
            while (f.ariusHp > 0f) {
                if (!f.dragonDowned && f.dragonHp <= 0f) {
                    downDragon()
                    if (dragonLed) {
                        interrupted = true
                        break
                    }
                }
                if (!leader.step()) break
                if (!f.dragonDowned && f.dragonHp <= 0f) {
                    downDragon()
                    if (dragonLed) {
                        interrupted = true
                        break
                    }
                }
                if (!f.dragonDowned) {
                    if ((support == null || support.done) && f.now >= supportAt) {
                        support = Runner(if (f.ariusLeads) f.dragon.support() else f.moves.support())
                        supportAt = f.now + if (f.ariusLeads) 100 else 42
                    }
                    support?.step()
                }
                checkTaktBreak()
                yield(Unit)
            }
            // Abgebrochene Züge/Begleitskripte laufen ihr finally nicht durch: Ansagen und Zustände sauber zurücksetzen.
            val supportDropped = support != null && !support.done
            if (interrupted || f.ariusHp <= 0f || supportDropped) {
                f.calls.reset()
                f.moves.clearActing()
                f.dragon.clearActing()
                f.inhaling = false
                f.nivaris?.throatGlow = 0
            }
            if (!f.dragonDowned) switchLead()
        }
        f.ariusVulnerable = false
        f.dragonVulnerable = false
        if (f.training == 3) {
            a.play(AriusPose.KNEEL)
            f.victory()
            return@script
        }
        yieldAll(chase())
        yieldAll(finale())
    }

    /**
     * Die Jagd (seit 1.9.0 eine echte Verfolgung, siehe [Hunt]): Zorn-Szene, Flucht über die Route den Berg hinauf, Arius
     * läuft und springt hinterher; die Ankunft auf dem Gipfel ist eine eigene Szene ([Hunt.arrive]).
     */
    private fun chase(): Script = script {
        val a = f.arius ?: return@script
        f.ariusVulnerable = false
        f.dragonVulnerable = false
        a.form = AriusForm.WHITE
        a.bladeState = AriusBlade.ICE
        val hunt = Hunt(f)
        yieldAll(hunt.run())
        f.chasing = false
        if (f.players().isEmpty()) return@script
        yieldAll(hunt.arrive())
        cue("resonance.chime", f.summit, 5f, 0.8f)
        wait(10)
    }

    private fun checkTaktBreak() {
        val threshold = 25f * (1f + 0.4f * (f.startCount - 1))
        if (f.ariusLeads && f.now < f.ariusOpenUntil && f.ariusOpenDamage >= threshold) {
            f.ariusOpenDamage = 0f
            breakTakt(dragonOpens = true)
        } else if (!f.ariusLeads && f.now < f.dragonOpenUntil && f.dragonOpenDamage >= threshold) {
            f.dragonOpenDamage = 0f
            breakTakt(dragonOpens = false)
        }
    }

    private fun breakTakt(dragonOpens: Boolean) {
        if (f.dragonDowned) return
        f.taktBroken = true
        if (dragonOpens) {
            f.nivaris?.play(NivarisPose.EXHAUSTED)
            f.openDragon(AriusTimings.TAKT_BREAK_OPEN)
        } else {
            f.arius?.play(AriusPose.STAGGER)
            f.openArius(AriusTimings.TAKT_BREAK_OPEN)
        }
        f.hint("hha_arius.hint.takt_break")
        f.ambient("lead.switch", f.arenaCenter, 2f, 0.7f)
        for (p in f.players()) Advancements.grant(p, "takt_break")
    }

    private fun switchLead() {
        f.ariusLeads = !f.ariusLeads
        f.taktBroken = false
        markLead()
    }

    private fun markLead() {
        val a = f.arius ?: return
        val d = f.nivaris
        a.lead = f.ariusLeads && !f.dragonDowned
        d?.lead = !f.ariusLeads && !f.dragonDowned
        a.eyeGlow = if (a.lead) 100 else 60
        d?.eyeGlow = if (d?.lead == true) 100 else 55
        val leader = if (f.ariusLeads) a else d ?: return
        if (!f.dragonDowned) {
            Vfx.rune(f.world, Vfx.Style.STORM, Vec3d(leader.x, f.floorY + 0.05, leader.z), if (leader === a) 0.9f else 2.0f, 50, leader)
            f.ambient("lead.switch", leader.entityPos, 1.2f)
        }
        f.updateBars()
    }

    private fun downDragon() {
        val d = f.nivaris ?: return
        f.dragonDowned = true
        f.dragonVulnerable = false
        f.inhaling = false
        d.throatGlow = 0
        f.dragon.clearActing()
        d.flying = false
        d.resonance = false
        d.lead = false
        f.place(d, f.abs(ArenaLayout.VAULT_REST))
        f.face(d, f.arenaCenter)
        d.play(NivarisPose.LIE_DOWN)
        f.ariusLeads = true
        markLead()
        f.hint("hha_arius.hint.dragon_down")
        f.updateBars()
    }

    // --- Resonanzangriffe -------------------------------------------------------------------------------------------------

    private suspend fun SequenceScope<Unit>.announceResonance() {
        val a = f.arius ?: return
        val d = f.nivaris ?: return
        f.fx(FxKind.THREAD, a.entityPos, life = AriusTimings.RESONANCE_WARN + 20, e1 = a, e2 = d)
        a.eyeGlow = 100
        d.eyeGlow = 100
        f.ambient("resonance.chime", a.entityPos, 3f)
        wait(AriusTimings.RESONANCE_WARN)
    }

    /**
     * Resonanzangriffe im Wechsel: echte Duo-Angriffe ([Duo]) und die gemeinsamen Feldangriffe (seit 1.9.0 kommt jeder
     * zweite Angriff als Duo).
     */
    fun resonance(kind: Int): Script = script {
        announceResonance()
        // Seit 1.10.0: vier von sechs Resonanzangriffen sind echte Duo-Angriffe (Rotation in Duo.next).
        when (kind % 6) {
            1 -> yieldAll(corridor())
            4 -> yieldAll(if (kind % 12 == 4) splitField() else crystalStorm())
            else -> yieldAll(duo.next())
        }
        markLead()
    }

    /** Die Resonanzangriffe einzeln (für die Sandbox, `/arius attack`). */
    enum class Resonance { CORRIDOR, CRYSTAL_STORM, SPLIT_FIELD, HARMONY, DRAGON_RIDE, CROSS_CUT, TWIN_FANG, GALE_CLEAVE, FROST_PRISON }

    /** Genau ein Resonanzangriff mit derselben Ansage wie in [resonance], ohne dessen Reihenfolge. */
    fun resonanceAttack(kind: Resonance): Script = script {
        announceResonance()
        yieldAll(
            when (kind) {
                Resonance.CORRIDOR -> corridor()
                Resonance.CRYSTAL_STORM -> crystalStorm()
                Resonance.SPLIT_FIELD -> splitField()
                Resonance.HARMONY -> duo.harmony()
                Resonance.DRAGON_RIDE -> duo.dragonRide()
                Resonance.CROSS_CUT -> duo.crossCut()
                Resonance.TWIN_FANG -> duo.twinFang()
                Resonance.GALE_CLEAVE -> duo.galeCleave()
                Resonance.FROST_PRISON -> duo.frostPrison()
            },
        )
        markLead()
    }

    private val duo = Duo(f)

    /**
     * R1: Frostkorridor und Schnitt. Seit 1.9.0 ohne Eiskämme (nichts versperrt mehr Wege): Der Atem zieht zwei
     * Frostbahnen, dann schneidet Arius längs durch den Korridor; sicher sind nur die goldenen Lücken.
     */
    private fun corridor(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val t = f.target() ?: return@script
        val c = f.arenaCenter
        val toward = flatDir(c, t.entityPos).let { if (it.lengthSquared() < 0.5) Vec3d(0.0, 0.0, 1.0) else it }
        val mid = f.clampToArena(t.entityPos, 6.0)
        val p0 = f.clampToArena(mid.subtract(toward.multiply(13.0)), 1.0)
        val p1 = f.clampToArena(mid.add(toward.multiply(13.0)), 1.0)
        val side = Vec3d(-toward.z, 0.0, toward.x)
        val left0 = p0.add(side.multiply(3.0)); val left1 = p1.add(side.multiply(3.0))
        val right0 = p0.subtract(side.multiply(3.0)); val right1 = p1.subtract(side.multiply(3.0))
        // Arius an das Korridorende.
        val stand = p0.subtract(toward.multiply(1.5))
        f.task(script { repeat(20) { if (!f.stepToward(a, stand, 0.6)) yield(Unit) }; f.face(a, p1) })
        yieldAll(f.dragon.hoverAt(mid.add(side.multiply(9.0)), mid, 24))
        val breathGroup = f.calls.boss(style = Vfx.Style.SOUL)
        breathGroup.line(Vec3d(left0.x, f.floorY, left0.z), Vec3d(left1.x, f.floorY, left1.z), 2.4f, 30)
        val last = breathGroup.line(Vec3d(right0.x, f.floorY, right0.z), Vec3d(right1.x, f.floorY, right1.z), 2.4f, 30)
        d.play(NivarisPose.INHALE, last.ticks)
        f.inhaling = true
        f.chokeDamage = 0f
        f.ambient("nivaris.inhale", d.entityPos, 3f)
        val threshold = 30f * (1f + 0.3f * (f.startCount - 1))
        for (i in 0 until last.ticks) {
            d.throatGlow = i * 100 / last.ticks
            if (f.chokeDamage >= threshold) {
                f.inhaling = false
                breathGroup.end()
                yieldAll(f.dragon.choke())
                a.play(AriusPose.STAGGER)
                f.openArius(AriusTimings.TAKT_BREAK_OPEN)
                for (p in f.players()) Advancements.grant(p, "takt_break")
                wait(AriusTimings.TAKT_BREAK_OPEN)
                return@script
            }
            yield(Unit)
        }
        f.inhaling = false
        d.throatGlow = 0
        d.play(NivarisPose.BREATH)
        f.ambient("nivaris.breath", d.entityPos, 3.5f)
        for (p in breathGroup.hits(f.players())) f.hurt(p, 12f, Damage.Kind.FROST_BREATH, d)
        for (lane in listOf(left0 to left1, right0 to right1)) {
            f.fx(FxKind.SEAM, Vec3d(lane.first.x, f.floorY + 0.1, lane.first.z), Vec3d(lane.second.x, f.floorY + 0.1, lane.second.z), a = 0.8f, life = 60)
            for (k in 0..6) f.shards.spike(Vec3d(lane.first.x, f.floorY, lane.first.z).lerp(Vec3d(lane.second.x, f.floorY, lane.second.z), k / 6.0), 1.2, 50, width = 0.5)
        }
        val gaps = listOf(0.33, 0.66)
        val cut = f.calls.boss()
        for (g in gaps) {
            val at = p0.lerp(p1, g)
            cut.safeLine(at.add(side.multiply(1.5)), at.add(side.multiply(7.0)), 2.6f, 40)
            cut.safeLine(at.subtract(side.multiply(1.5)), at.subtract(side.multiply(7.0)), 2.6f, 40)
        }
        val slash = cut.line(Vec3d(p0.x, f.floorY, p0.z), Vec3d(p1.x, f.floorY, p1.z), 5f, 40)
        f.face(a, p1)
        a.play(AriusPose.CUT_RELEASE, slash.ticks)
        f.fx(FxKind.EDGE, a.entityPos, life = slash.ticks, e1 = a)
        wait(slash.ticks)
        f.ambient("cut.strike", a.entityPos, 4f, 1.1f)
        for (p in f.players()) if (slash.contains(p) && !cut.isSafe(p)) f.hurt(p, 18f, Damage.Kind.NULL_CUT)
        Vfx.crescent(f.world, Vfx.Style.HEAVEN, a.entityPos.add(0.0, 1.2, 0.0), toward, 28f, 5f, 10)
        d.play(NivarisPose.HOVER)
        yieldAll(f.dragon.land(f.target()))
        a.play(AriusPose.STAGGER)
        f.openArius(40)
        wait(40)
    }

    /** R2: Kristallsturm. */
    private fun crystalStorm(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val c = f.arenaCenter
        val angle = rnd.nextDouble() * PI * 2
        val s = Vec3d(sin(angle), 0.0, cos(angle))
        val across = Vec3d(-s.z, 0.0, s.x)
        yieldAll(f.dragon.hoverAt(c.subtract(s.multiply(f.arenaRadius - 3)), c, 24))
        f.face(a, c.add(s))
        val throwT = AriusTimings.ARIUS_WINDUP[AriusPose.THROW_CRYSTALS]
        a.play(AriusPose.THROW_CRYSTALS, throwT)
        f.openArius(throwT + 36)
        val group = f.calls.boss()
        val lanes = ArrayList<Telegraph.Line>()
        val offsets = (0 until 6).map { (it - 2.5) * 5.0 }
        for (o in offsets) {
            val base = c.add(across.multiply(o))
            val from = f.clampToArena(base.subtract(s.multiply(f.arenaRadius)), 0.5)
            val to = f.clampToArena(base.add(s.multiply(f.arenaRadius)), 0.5)
            lanes.add(group.line(Vec3d(from.x, f.floorY, from.z), Vec3d(to.x, f.floorY, to.z), 2f, throwT + 36, Telegraph.Pace.PROJECTILE))
            f.fx(FxKind.CRYSTAL, base.add(0.0, 3.0, 0.0).subtract(s.multiply(2.0)), to.add(0.0, 1.0, 0.0), a = (throwT + 30).toFloat(), life = throwT + 40)
        }
        for (o in offsets.dropLast(1)) {
            val base = c.add(across.multiply(o + 2.5))
            group.safeLine(Vec3d(base.x, f.floorY, base.z).subtract(s.multiply(f.arenaRadius - 4)), Vec3d(base.x, f.floorY, base.z).add(s.multiply(f.arenaRadius - 4)), 2.2f, throwT + 36)
        }
        f.ambient("crystal.hang", c, 3f)
        val start = f.now
        val threshold = 25f * (1f + 0.4f * (f.startCount - 1))
        while (f.now - start < throwT + 36) {
            if (f.ariusOpenDamage >= threshold) {
                group.end()
                f.ambient("ridge.break", c, 3f, 1.4f)
                d.play(NivarisPose.EXHAUSTED)
                f.openDragon(AriusTimings.TAKT_BREAK_OPEN)
                for (p in f.players()) Advancements.grant(p, "takt_break")
                wait(AriusTimings.TAKT_BREAK_OPEN)
                return@script
            }
            if (f.now - start == (throwT + 16).toLong()) d.play(NivarisPose.WING_GUST, 20)
            yield(Unit)
        }
        f.ambient("nivaris.wing", d.entityPos, 4f, 0.6f)
        for (p in group.hits(f.players())) f.hurt(p, 12f, Damage.Kind.IMPACT, d)
        yieldAll(f.dragon.land(f.target()))
        f.openDragon(40)
        wait(40)
    }

    /** R3: Eisfeld spalten. */
    private fun splitField(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        val t = f.target() ?: return@script
        val center = f.clampToArena(t.entityPos, 8.0).let { Vec3d(it.x, f.floorY, it.z) }
        yieldAll(f.dragon.hoverAt(center.add(8.0, 0.0, 0.0), center, 20))
        // Einfrieren: Ansage-Kreis (noch kein Schaden; die Fläche ist danach gefroren).
        Vfx.rune(f.world, Vfx.Style.SOUL, center.add(0.0, 0.05, 0.0), 10f, 90)
        val freeze = Runner(f.dragon.freezeField(center, 10f, 30))
        while (freeze.step()) yield(Unit)
        if (f.taktBroken) {
            a.play(AriusPose.STAGGER)
            f.openArius(AriusTimings.TAKT_BREAK_OPEN)
            for (p in f.players()) Advancements.grant(p, "takt_break")
            wait(AriusTimings.TAKT_BREAK_OPEN)
            return@script
        }
        // Arius spaltet das Feld: eine Hälfte bricht, die andere ist sicher.
        val angle = rnd.nextDouble() * PI
        val axis = Vec3d(sin(angle), 0.0, cos(angle))
        val normal = Vec3d(-axis.z, 0.0, axis.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0)
        f.face(a, center)
        val group = f.calls.boss()
        val seam = group.line(center.subtract(axis.multiply(10.0)), center.add(axis.multiply(10.0)), 1.4f, AriusTimings.ARIUS_WINDUP[AriusPose.SPLIT_FIELD], Telegraph.Pace.HEAVY)
        a.play(AriusPose.SPLIT_FIELD, seam.ticks)
        wait(seam.ticks)
        f.ambient("cut.strike", center, 3.5f, 1.2f)
        for (p in seam.hits(f.players())) f.hurt(p, 12f)
        val half = group.cone(center, normal, 10f, 90f, 20, Telegraph.Pace.AREA)
        group.safe(center.subtract(normal.multiply(5.0)), 4f, 20)
        wait(half.ticks)
        f.ambient("ridge.break", center, 4f, 0.7f)
        for (p in half.hits(f.players())) f.hurt(p, 14f, Damage.Kind.FROST_BREATH, d)
        Vfx.shockwave(f.world, Vfx.Style.SOUL, center.add(normal.multiply(5.0)), 6f, 14, 1.4f)
        yieldAll(f.dragon.land(f.target()))
        a.play(AriusPose.STAGGER)
        f.openArius(30)
        wait(30)
    }

    // --- Das weiße Loch ----------------------------------------------------------------------------------------------------

    /**
     * Das weiße Loch (seit 1.9.0 neu, Crix: „er lädt eine Minute auf und nichts passiert“): kurz, sich steigernd, mit
     * Ausweg und großem Ende.
     * 1. **Sammeln** ([AriusTimings.CORE_BUILD], ≈ 5,5 s): Er springt in die Mitte und hebt die Klinge, über ihm öffnet sich
     *    das Loch. Der **Sog** wächst Sekunde um Sekunde, wer in den Kern gezogen wird, friert an und nimmt Schaden;
     *    Solvane kreist und speist den Kern. Pulse und Klang werden schneller und höher.
     * 2. **Ausweg**: Er ist dabei angreifbar. Wer genug Schaden in ihn legt, bricht seine Konzentration: Das Loch
     *    implodiert vorzeitig (kleiner Stoß), er und Solvane stehen lange offen.
     * 3. **Entladung** sonst: Der Sog reißt ab, drei goldene Bahnen zeigen den Weg ([AriusTimings.CORE_STRIKE] Ticks), dann
     *    explodiert der Kern über das ganze Feld. Danach stehen beide erschöpft offen.
     */
    fun whiteHole(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris?.takeIf { !f.dragonDowned }
        f.calls.reset()
        f.ariusVulnerable = false
        f.dragonVulnerable = false
        val c = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
        val core = c.add(0.0, 6.0, 0.0)
        val fresh = newcomers("scene.white_hole")
        if (fresh.isNotEmpty()) f.sceneText("white_hole", 70, fresh)
        markHeard("scene.white_hole")
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.white_hole", "hha_arius.feat.white_hole.sub", 50)
        // Arius springt in die Mitte und hebt die Klinge.
        val from = a.entityPos
        a.play(AriusPose.LAUNCH, 6)
        cue("arius.wind_step", from, 2.4f, 0.8f)
        for (i in 1..12) {
            f.place(a, from.lerp(c, i / 12.0).add(0.0, sin(i / 12.0 * PI) * 3.0, 0.0))
            yield(Unit)
        }
        a.play(AriusPose.CORE_RAISE)
        val build = AriusTimings.CORE_BUILD
        val strike = AriusTimings.CORE_STRIKE
        val total = build + strike
        f.fxAll(FxKind.WHITE_HOLE, core, a = 14f, b = total.toFloat(), life = total + 60)
        f.ambient("core.form", core, 5f)
        Vfx.skyMood(f.world, Vfx.Style.STORM, c, 0.6f, total, 60f)
        d?.let {
            it.flying = true
            it.resonance = true
            f.fx(FxKind.THREAD, core, life = build, e1 = a, e2 = it)
        }
        // Ab jetzt angreifbar: genug Schaden bricht seine Konzentration.
        f.ariusVulnerable = true
        val startHp = f.ariusHp
        val focus = 55f * (1f + 0.4f * (f.startCount - 1))
        val last = HashMap<java.util.UUID, Vec3d>()
        f.hint("hha_arius.hint.white_hole", net.minecraft.util.Formatting.GOLD)
        var broken = false
        for (i in 0 until build) {
            val k = i / build.toDouble()
            if (startHp - f.ariusHp >= focus) {
                broken = true
                break
            }
            // Solvane kreist und speist den Kern.
            if (d != null) {
                val ang = i / 40.0 * PI
                f.place(d, f.ringPoint(c, 12.0, ang).add(0.0, 7.0, 0.0))
                f.face(d, core)
                if (i == 8) d.play(NivarisPose.BREATH)
                if (i % 12 == 0) shown { Vfx.beam(f.world, Vfx.Style.SOUL, d.entityPos.add(0.0, 3.0, 0.0), core, 1.2f, 10) }
            }
            // Wachsender Sog: alle 2 Ticks zum Kern, Stärke steigt; im Kern friert man an.
            val pull = 0.03 + 0.13 * k
            for (p in f.players()) {
                val prev = last.put(p.uuid, p.entityPos)
                if (i % 2 != 0 || Hold.isHeld(p)) continue
                val dx = c.x - p.x
                val dz = c.z - p.z
                val dist = sqrt(dx * dx + dz * dz)
                if (dist > f.arenaRadius + 6.0 || dist < 0.5) continue
                val est = prev?.let { p.entityPos.subtract(it) } ?: Vec3d.ZERO
                val toward = Vec3d(dx / dist, 0.0, dz / dist)
                Fx.launchPlayer(p, Vec3d(est.x * 0.9 + toward.x * pull, est.y.coerceIn(-0.6, 0.6), est.z * 0.9 + toward.z * pull))
                if (dist < 3.6 && i % 10 == 0) {
                    f.hurt(p, 3f, Damage.Kind.PRESSURE)
                    dev.henny.hha_arius.gear.Frostbite.add(p, 12f)
                }
            }
            // Pulse, die schneller und höher werden.
            val every = (24 - (k * 16).toInt()).coerceAtLeast(6)
            if (i % every == 0) {
                f.ambient("core.pulse", core, 3f + 2f * k.toFloat(), 0.7f + 0.7f * k.toFloat())
                shown {
                    Vfx.nova(f.world, Vfx.Style.HEAVEN, core, (2.0 + 6.0 * k).toFloat(), 10)
                    f.fx(FxKind.REVERSE_WIND, core, a = (8.0 + 20.0 * k).toFloat(), life = every + 4)
                }
                f.calls.roomShake(c, (1.5 + 3.5 * k).toFloat(), 60f)
            }
            yield(Unit)
        }
        if (broken) {
            // Seine Konzentration bricht: das Loch implodiert vorzeitig.
            f.fxAll(FxKind.WHITE_HOLE, core, b = -1f, life = 1)
            f.ambient("core.collapse", core, 5f, 1.4f)
            f.ambient("transform.shatter", core, 4f, 0.8f)
            shown {
                f.fxAll(FxKind.FLASH, core, a = 0.3f, life = 8)
                Vfx.shockwave(f.world, Vfx.Style.HEAVEN, c, 8f, 16, 2f)
            }
            for (p in f.players()) if (p.squaredDistanceTo(c) < 7.0 * 7.0) {
                f.hurt(p, 6f, Damage.Kind.PRESSURE)
                Fx.launchPlayer(p, flatDir(c, p.entityPos).multiply(0.8).add(0.0, 0.4, 0.0))
            }
            f.hint("hha_arius.hint.white_hole_break", net.minecraft.util.Formatting.GOLD)
            for (p in f.players()) Advancements.grant(p, "takt_break")
            exhausted(a, d, AriusTimings.CORE_END_OPEN + 20)
            return@script
        }
        // Entladung: der Sog reißt ab, drei goldene Bahnen, dann explodiert der Kern über das ganze Feld.
        f.ariusVulnerable = false
        val base = rnd.nextDouble() * PI * 2
        val corridors = (0 until 3).map { base + it * 2 * PI / 3 }
        val group = f.calls.boss(pace = Telegraph.Pace.ROOM)
        for (ang in corridors) group.safeLine(f.ringPoint(c, 3.0, ang), f.ringPoint(c, f.arenaRadius + 1.0, ang), 3.4f, strike)
        val blast = group.ring(c, f.arenaRadius.toFloat() + 1f, strike, inner = 3f)
        a.play(AriusPose.BREAKTHROUGH, strike)
        f.ambient("iai.glint", core, 4f, 0.6f)
        f.ambient("cut.charge", core, 4f, 0.5f)
        val lift = a.entityPos
        for (i in 1..blast.ticks) {
            f.place(a, lift.lerp(core.subtract(0.0, 2.0, 0.0), (i / blast.ticks.toDouble()).let { it * it }))
            if (i % 6 == 0) f.ambient("core.pulse", core, 4f, 1.4f + i * 0.01f)
            yield(Unit)
        }
        f.fxAll(FxKind.WHITE_HOLE, core, b = -1f, life = 1)
        f.ambient("core.collapse", core, 6f)
        f.ambient("cut.strike", c, 5f, 0.6f)
        f.ambient("glacier.collapse", c, 5f, 0.7f)
        f.farVanilla(net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EXPLODE.value(), c, 5f, 0.5f)
        shown {
            f.fxAll(FxKind.FLASH, core, a = 0.55f, life = 12)
            f.fxAll(FxKind.TIME_SLOW, core, a = 0.6f, life = 14)
            Vfx.shockwave(f.world, Vfx.Style.HEAVEN, c, f.arenaRadius.toFloat() + 2f, 30, 3f)
            f.fx(FxKind.CLEAR_WAVE, c, a = f.arenaRadius.toFloat() + 2f, life = 30)
            f.fx(FxKind.FRONT, c.add(0.0, 0.1, 0.0), Vec3d(base, 3.0, 1.7), a = 2.4f, b = f.arenaRadius.toFloat() + 1f, life = 16)
            for (k in 0 until 6) f.fxAll(FxKind.DEBRIS_CLOUD, f.ringPoint(c, 8.0 + k * 3.0, base + k * 1.1), a = 6f, life = 40)
        }
        f.calls.roomShake(c, 8f, 80f)
        for (p in f.players()) {
            val dx = p.x - c.x
            val dz = p.z - c.z
            if (!blast.contains(p) || inCorridor(dx, dz, corridors, 1.7)) continue
            f.hurt(p, 24f, Damage.Kind.PRESSURE)
            Fx.launchPlayer(p, Vec3d(dx, 0.0, dz).normalize().multiply(1.3).add(0.0, 0.5, 0.0))
        }
        f.ariusVulnerable = true
        exhausted(a, d, AriusTimings.CORE_END_OPEN)
    }

    /** Nach dem weißen Loch: er fällt zu Boden und steht offen, Solvane landet erschöpft. */
    private suspend fun SequenceScope<Unit>.exhausted(a: AriusEntity, d: NivarisEntity?, ticks: Int) {
        val c = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
        val top = a.entityPos
        for (i in 1..6) {
            f.place(a, top.lerp(c, i / 6.0))
            yield(Unit)
        }
        f.place(a, c)
        f.ariusVulnerable = true
        a.play(AriusPose.STAGGER)
        f.openArius(ticks)
        if (d != null && !f.dragonDowned) {
            f.dragonVulnerable = true
            val ground = f.clampToArena(Vec3d(d.x, f.floorY, d.z), 4.0)
            f.place(d, ground)
            d.flying = false
            d.play(NivarisPose.EXHAUSTED)
            f.openDragon(ticks)
        }
        f.dragonVulnerable = !f.dragonDowned
        wait(ticks)
    }

    private fun inCorridor(dx: Double, dz: Double, corridors: List<Double>, half: Double): Boolean {
        val dist = sqrt(dx * dx + dz * dz)
        val ang = atan2(dx, dz)
        for (c in corridors) {
            var delta = ang - c
            while (delta > PI) delta -= 2 * PI
            while (delta < -PI) delta += 2 * PI
            if (abs(delta) < PI / 2 && abs(dist * sin(delta)) <= half) return true
        }
        return false
    }

    // --- Finale ------------------------------------------------------------------------------------------------------------

    /**
     * Das Finale (seit 1.9.0 ein echter Kampf, Crix: „ersetz das letzte Quick-Time-Event durch einen richtigen Kampf,
     * das Austausch-Ding ist Müll“): Kein Sturm, kein Drache. Arius mit eigener Leiste und seinem ganzen Moveset in drei
     * Haltungen, beginnend aus der Scheide (Iai). Unter 50 % zieht er ganz, die Wechsel werden schneller, und einmal
     * kommt der Letzte Horizont: ein Schnitt über die ganze Gipfelarena mit goldenen Lücken.
     */
    fun finale(): Script = script {
        val a = f.arius ?: return@script
        f.setPhase(P.FINALE)
        f.reviveAll(f.finaleSpots())
        f.ariusVulnerable = false
        f.dragonVulnerable = false
        val finaleTrack = if (f.onSummit) "summit" else "finale"
        f.music("music.$finaleTrack", 60)
        MusicCue.play(f, finaleTrack, 60)
        MusicCue.sting(f, "phase")
        f.nivaris?.let { d ->
            d.flying = false
            d.resonance = false
            d.lead = false
            if (f.onSummit) {
                // Wie beim Drachen-Reveal hoch oben um die Eisspitze gewunden; Entity bleibt nahe am Gipfel für volle Sichtbarkeit.
                f.place(d, f.arenaCenter.add(0.0, 2.0, 6.0))
                d.mode = NivarisMode.RETURN_COIL
                d.uncoil = 0f
                d.showNow()
                d.play(NivarisPose.IDLE)
                d.eyeGlow = 90
            } else {
                d.mode = NivarisMode.NORMAL
                f.place(d, f.abs(ArenaLayout.VAULT_REST))
                f.face(d, f.arenaCenter)
                d.play(NivarisPose.LIE_DOWN)
            }
        }
        a.lead = false
        a.form = AriusForm.WHITE
        // Solvane kehrt zurück: der Drache wird wieder zur Klinge in der Scheide (V5). Danach gibt es keinen Drachen mehr.
        yieldAll(bladeReturn(a))
        Vfx.skyMood(f.world, Vfx.Style.GOLD, f.arenaCenter, 0.25f, 400, 60f)
        a.sheathed = true
        a.play(AriusPose.IAI_STANCE)
        f.say("finale", 70)
        f.ariusMax = BossConfig.health(480f, f.startCount)
        f.ariusHp = f.ariusMax
        f.moves.resetStance(AriusMoves.Stance.SHEATH, 300)
        wait(50)
        f.ariusVulnerable = true
        f.updateBars()
        var desperate = false
        while (f.ariusHp > 0f) {
            if (!desperate && f.ariusHp <= f.ariusMax * 0.5f) {
                desperate = true
                yieldAll(secondWind(a))
                yieldAll(lastHorizon(a))
                continue
            }
            runWhile(f.moves.turnFinale(desperate)) { f.ariusHp > 0f }
        }
        f.ariusVulnerable = false
        f.calls.reset()
        yieldAll(ending())
    }

    /** Unter 50 %: Er zieht die Klinge ganz, die Augen flammen auf, ein Stoß drückt alle zurück (kein Schaden). */
    private fun secondWind(a: AriusEntity): Script = script {
        f.calls.reset()
        if (f.onSummit) MusicCue.play(f, "summit_final", 30)
        MusicCue.sting(f, "phase")
        a.sheathed = false
        a.play(AriusPose.CORE_RAISE)
        a.eyeGlow = 100
        f.say("finale.draw", 50)
        f.ambient("iai.draw", a.entityPos, 4f, 0.8f)
        f.ambient("transform.pulse", a.entityPos, 4f, 0.7f)
        f.fxAll(FxKind.FLASH, a.entityPos, a = 0.3f, life = 8)
        Vfx.shockwave(f.world, Vfx.Style.HEAVEN, a.entityPos, 10f, 18, 2.2f)
        f.fx(FxKind.CLEAR_WAVE, a.entityPos, a = 12f, life = 20)
        for (p in f.players()) if (p.squaredDistanceTo(a) < 8.0 * 8.0) Fx.launchPlayer(p, flatDir(a.entityPos, p.entityPos).multiply(0.9).add(0.0, 0.35, 0.0))
        f.moves.resetStance(AriusMoves.Stance.FLOW, 160)
        wait(24)
        a.play(AriusPose.IDLE)
    }

    fun horizonCharge(a: AriusEntity, dir: Vec3d, ticks: Int) {
        f.fxAll(FxKind.HORIZON, a.entityPos.add(0.0, 1.15, 0.0), dir, a = 4.5f, life = ticks, e1 = a)
        f.ambient("cut.charge", a.entityPos, 3f, 0.72f)
    }

    fun horizonImpact(at: Vec3d, dir: Vec3d, halfWidth: Float) {
        f.fxAll(FxKind.HORIZON, at.add(0.0, 1.15, 0.0), dir, a = halfWidth, b = 1f, life = 34)
        f.ambient("cut.strike", at, 4.5f, 0.78f)
        f.calls.roomShake(at, 3.2f, 52f)
    }

    /** One horizontal sweep. The marked belt resolves as the leading edge passes through it. */
    fun lastHorizon(a: AriusEntity, recovery: Int = 26): Script = script {
        val center = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
        val dir = flatDir(a.entityPos, center)
        val side = Vec3d(-dir.z, 0.0, dir.x)
        val reach = (f.arenaRadius - 1.0).coerceAtLeast(5.0)
        val from = center.subtract(side.multiply(reach))
        val to = center.add(side.multiply(reach))
        val belt = f.calls.boss(pace = Telegraph.Pace.ROOM).line(from, to, 3.4f, 50)
        a.sheathed = false
        a.play(if (AriusBlade.hasIce(a.bladeState)) AriusPose.FORGE_ICE else AriusPose.CUT_CHARGE)
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.last_horizon", "hha_arius.feat.last_horizon.sub", 30)
        horizonCharge(a, dir, belt.ticks)
        wait(belt.ticks - 6)
        a.play(AriusPose.SPIN_CUT, 6)
        wait(6)
        horizonImpact(center.subtract(dir.multiply(2.7)), dir, reach.toFloat())
        val hit = HashSet<java.util.UUID>()
        for (i in 0..6) {
            for (p in belt.hits(f.players())) {
                val along = p.entityPos.subtract(center).dotProduct(dir)
                if (kotlin.math.abs(along - (i - 2.7)) <= 1.0 && hit.add(p.uuid)) f.hurt(p, 11f, Damage.Kind.NULL_CUT)
            }
            yield(Unit)
        }
        a.play(AriusPose.STAGGER)
        if (recovery > 0) f.openArius(recovery)
        wait(recovery)
    }

    /**
     * Auftakt des Finales (V5): Solvane kehrt zurück. Auf dem Gipfel windet sie sich hoch oben um die Eisspitze (wie
     * beim Drachen-Reveal), löst sich dort oben in Licht auf und stürzt als leuchtender Strom herab in Arius' Scheide;
     * die temporäre Eisklinge zerfällt. Endzustand: kein Drache, [AriusBlade.HELD], Solvane in der Scheide ([settleReturn]).
     */
    private fun bladeReturn(a: AriusEntity, forcePlay: Boolean = false): Script = script {
        val d = f.nivaris
        if (d == null || (f.training != 0 && !forcePlay)) {
            settleReturn(a)
            return@script
        }
        val scene = CineScene(f) { settleReturn(a) }
        yieldAll(scene.play(script {
            if (f.onSummit) {
                f.atmos(dev.henny.hha_arius.net.AtmosMode.REVEAL, 20)
                d.mode = NivarisMode.RETURN_COIL
                d.uncoil = 0f
                d.flying = false
                d.showNow()
                d.resonance = true
                d.eyeGlow = 100
                val spireHead = f.abs(ReturnCoil.head)
                val spireMid = f.abs(ReturnCoil.points[ReturnCoil.points.size / 2])
                f.face(a, spireHead)
                a.sheathed = false
                a.play(AriusPose.IDLE)
                a.eyeGlow = 100
                val toSpire = flatDir(a.entityPos, spireHead)
                val side = Vec3d(-toSpire.z, 0.0, toSpire.x)
                // Establish the whole perch from beyond the crown. Keep the lens
                // outside the ice even as the framing moves toward the neck.
                val axis = f.abs(Vec3d.of(ArenaLayout.SPIRE)).add(0.5, -ArenaLayout.SPIRE.y.toDouble(), 0.5)
                val perchCenter = axis.add(0.0, ArenaLayout.SPIRE_TOP - 25.0, 0.0)
                scene.rawLine(
                    axis.add(34.0, ArenaLayout.SPIRE_TOP - 16.0, -48.0),
                    axis.add(25.0, ArenaLayout.SPIRE_TOP - 13.0, -43.0),
                    perchCenter, perchCenter.add(0.0, 3.0, 0.0), 42, 56.0, 51.0,
                )
                wait(8)
                shown { f.say("dragon.return", 50) }
                d.play(NivarisPose.ROAR, 10)
                Entrance.storyFx(f, FxKind.THREAD, a.entityPos, life = 56, e1 = a, e2 = d)
                cue("resonance.chime", a.entityPos, 3.5f, 0.8f)
                wait(12)
                cue("nivaris.inhale", spireHead, 4f, 0.65f)
                wait(22)
                // B) Totale auf die Eisspitze: Solvane zerfällt oben im Licht und verschwindet am Höhepunkt.
                val spireCam0 = axis.add(-47.0, ArenaLayout.SPIRE_TOP - 7.0, -37.0)
                val spireCam1 = axis.add(-37.0, ArenaLayout.SPIRE_TOP - 5.0, -31.0)
                scene.rawLine(spireCam0, spireCam1, perchCenter, perchCenter.add(0.0, 4.0, 0.0), 46, 51.0, 48.0)
                wait(20)
                d.fadeOut(25)
                Entrance.storyFx(f, FxKind.DRAGON_DISSOLVE, spireHead, spireMid, a = 8f, life = RETURN_PEAK + 34)
                Entrance.storyFx(f, FxKind.BLIZZARD, spireMid, a = 14f, life = 36)
                cue("transform.pulse", spireHead, 5f, 0.7f)
                cue("seal.blizzard", spireHead, 3f, 1.3f)
                wait(25)
                f.dismissNivaris()
                wait(10)
                // C) Der Strom stürzt von der Eisspitze herab in Arius' Scheide, die Eisklinge zerspringt.
                val chest = a.entityPos.add(0.0, 1.4, 0.0)
                val travel = RETURN_TRAVEL + 10
                scene.rawLine(
                    chest.add(side.multiply(5.9)).add(toSpire.multiply(-2.4)).add(0.0, 2.2, 0.0),
                    chest.add(side.multiply(3.2)).add(toSpire.multiply(-1.6)).add(0.0, 0.6, 0.0),
                    chest.add(0.0, 4.0, 0.0), chest.add(toSpire.multiply(-0.2)), travel + 26, 66.0, 50.0,
                )
                val sheath = a.entityPos.add(0.0, 1.55, 0.0).subtract(toSpire.multiply(0.25))
                Entrance.storyFx(f, FxKind.BLADE_STREAM, spireHead, sheath, a = travel.toFloat(), b = 1f, life = travel + 16, e1 = a)
                cue("nivaris.wing", spireHead, 4f, 1.4f)
                wait(8)
                Entrance.storyFx(f, FxKind.BLADE_SHATTER, a.entityPos.add(0.0, 1.0, 0.0), life = 24, e1 = a)
                a.bladeState = AriusBlade.NONE
                cue("transform.shatter", a.entityPos, 2.5f, 1.5f)
                wait(travel - 8)
                a.bladeState = AriusBlade.HELD
                a.sheathed = true
                shown { f.fx(FxKind.CLEAR_WAVE, a.entityPos, a = 8f, life = 16) }
                cue("iai.glint", a.entityPos, 3f, 0.9f)
                cue("resonance.chime", a.entityPos, 2.5f, 1.3f)
                wait(6)
                a.play(AriusPose.IAI_STANCE)
                wait(10)
                scene.home(18)
                wait(18)
            } else {
                val head = f.dragon.anchorPos(NivarisMoves.Zone.HEAD)
                val body = d.entityPos.add(0.0, 1.2, 0.0)
                f.face(a, head)
                a.sheathed = false
                a.play(AriusPose.IDLE)
                val toDragon = flatDir(a.entityPos, head)
                val side = Vec3d(-toDragon.z, 0.0, toDragon.x)
                val eye = a.entityPos.add(0.0, 1.7, 0.0)
                scene.line(f.clampToArena(eye.add(toDragon.multiply(-2.6)).add(side.multiply(1.1)).add(0.0, 0.3, 0.0), 2.0),
                    f.clampToArena(eye.add(toDragon.multiply(-2.0)).add(side.multiply(0.9)).add(0.0, 0.4, 0.0), 2.0),
                    head.lerp(eye, 0.3), head, 34 + RETURN_PEAK, 56.0, 50.0)
                wait(8)
                shown { f.say("dragon.return", 50) }
                d.fadeOut(26 + RETURN_PEAK - 1)
                d.resonance = true
                d.eyeGlow = 100
                a.eyeGlow = 100
                Entrance.storyFx(f, FxKind.THREAD, a.entityPos, life = 26 + RETURN_PEAK, e1 = a, e2 = d)
                cue("resonance.chime", a.entityPos, 3f, 0.8f)
                wait(26)
                Entrance.storyFx(f, FxKind.DRAGON_DISSOLVE, head, body, a = 5f, life = RETURN_PEAK + 30)
                cue("transform.pulse", head, 4f, 0.7f)
                cue("seal.blizzard", head, 2f, 1.4f)
                wait(RETURN_PEAK)
                f.dismissNivaris()
                val chest = a.entityPos.add(0.0, 1.4, 0.0)
                scene.line(f.clampToArena(chest.add(side.multiply(3.4)).add(toDragon.multiply(0.8)).add(0.0, 0.2, 0.0), 2.0),
                    f.clampToArena(chest.add(side.multiply(2.8)).add(toDragon.multiply(0.1)).add(0.0, 0.4, 0.0), 2.0),
                    chest.add(toDragon.multiply(1.6)).add(0.0, 0.8, 0.0), chest.add(toDragon.multiply(-0.2)), 56, 58.0, 50.0)
                val sheath = a.entityPos.add(0.0, 1.55, 0.0).subtract(toDragon.multiply(0.25))
                Entrance.storyFx(f, FxKind.BLADE_STREAM, head.add(0.0, 1.5, 0.0), sheath, a = RETURN_TRAVEL.toFloat(), b = 1f, life = RETURN_TRAVEL + 14, e1 = a)
                cue("nivaris.wing", head, 2f, 1.6f)
                wait(4)
                Entrance.storyFx(f, FxKind.BLADE_SHATTER, a.entityPos.add(0.0, 1.0, 0.0), life = 24, e1 = a)
                a.bladeState = AriusBlade.NONE
                cue("transform.shatter", a.entityPos, 2f, 1.5f)
                wait(RETURN_TRAVEL - 4)
                a.bladeState = AriusBlade.HELD
                a.sheathed = true
                cue("iai.glint", a.entityPos, 2.5f, 0.9f)
                cue("resonance.chime", a.entityPos, 2f, 1.3f)
                wait(6)
                a.play(AriusPose.IAI_STANCE)
                wait(56 - RETURN_TRAVEL - 6 - 18)
                scene.home(18)
                wait(18)
            }
        }))
        settleReturn(a)
    }

    /** Endzustand der Rückkehr (auch nach Überspringen, Abbruch oder im Training). */
    private fun settleReturn(a: AriusEntity) {
        f.dismissNivaris()
        a.bladeState = AriusBlade.HELD
        a.sheathed = true
        a.eyeGlow = 60
    }

    /**
     * Das Ende mit Kamera (überspringbar, kein Kampf mehr): Detail, wie er niederkniet; langsamer Halbkreis um ihn, während
     * er von der Waffe spricht; die weite Einstellung des (seit [bladeReturn] drachenlosen) Gewölbes, als er aufsteht;
     * Rückweg zur Spielerkamera. Die persönlichen Zeilen danach laufen ohne Kamera.
     */
    private fun ending(): Script = script {
        val a = f.arius ?: return@script
        f.music("music.finale", 0)
        MusicCue.play(f, "ending", 20)
        val scene = CineScene(f) {
            a.play(AriusPose.IDLE)
            a.bladeState = AriusBlade.HELD
            a.sheathed = true
        }
        yieldAll(scene.play(script {
            val base = a.entityPos
            val front = f.facing(a)
            val chest = base.add(0.0, 0.9, 0.0)
            // Detail: tief und nah, er sinkt aufs Knie.
            scene.line(chest.add(front.multiply(2.6)).add(-0.9, -0.3, 0.0), chest.add(front.multiply(2.0)).add(-0.7, -0.2, 0.0), chest, chest.add(0.0, -0.2, 0.0), 32, 48.0, 42.0)
            a.play(AriusPose.KNEEL)
            a.sheathed = false
            wait(30)
            // Halbkreis um ihn, während er spricht.
            val ang = Math.toDegrees(kotlin.math.atan2(front.z, front.x))
            scene.orbit(base, 3.4, 1.1, ang - 25.0, ang + 35.0, 96, 50.0, 46.0, lookAt = a, lookOffset = 1.0, radiusTo = 3.0, heightTo = 1.3)
            shown { f.say("end.weapon", 90) }
            wait(95)
            // Weite Einstellung: das leere Gewölbe, er steht auf; Solvane ist wieder Klinge (kein Drache mehr, V5).
            val rest = f.arenaCenter
            val focus = base.lerp(rest, 0.35).add(0.0, 1.5, 0.0)
            val away = flatDir(rest, base).let { if (it.lengthSquared() < 0.5) front else it }
            scene.line(f.clampToArena(base.add(away.multiply(9.0)).add(3.0, 3.0, 0.0), 2.0), f.clampToArena(base.add(away.multiply(12.0)).add(4.0, 6.5, 0.0), 2.0),
                focus, focus.add(0.0, 0.5, 0.0), 85, 60.0, 64.0)
            a.play(AriusPose.IDLE)
            a.bladeState = AriusBlade.HELD
            a.sheathed = true
            shown { f.say("end.take", 80) }
            f.victory()
            // Seit 1.12.0 entsteht das Rückkehrtor erst jetzt: Die Kamera schaut zu, wie es aus dem Fels wächst und zündet.
            if (f.onSummit) {
                val g = FarPlan.RETURN_GATES.first()
                val gx = (g.x0 + g.x1) / 2.0 + 0.5
                val gy = (g.y0 + g.y1) / 2.0
                val mid = rel(gx, gy, g.z + 0.5)
                wait(8)
                scene.line(rel(gx - 5.5, gy + 1.2, g.z + 10.5), rel(gx - 2.6, gy + 0.5, g.z + 7.5), mid, mid, 62, 60.0, 52.0)
                wait(62)
            } else wait(67)
            scene.home(18)
            wait(18)
        }))
        val s = state()
        val partner = f.target()
        if (partner != null) {
            val data = s.player(partner.uuid)
            val heaven = HhaSets.get(Identifier.of("hha", "heaven"))
            val hell = HhaSets.get(Identifier.of("hha", "hell"))
            when {
                heaven != null && heaven.state(partner).anyPiece && "end.heaven" !in data.heard -> {
                    f.say("end.heaven", 90, to = listOf(partner)); data.heard.add("end.heaven"); wait(95)
                }
                hell != null && hell.state(partner).anyPiece && "end.hell" !in data.heard -> {
                    f.say("end.hell", 80, to = listOf(partner)); data.heard.add("end.hell"); wait(85)
                }
            }
            s.save()
        }
        f.nivaris?.let { f.face(a, it.entityPos) }
        val firstTimers = f.audience().filter { "end.nivaris" !in s.player(it.uuid).heard }
        if (firstTimers.isNotEmpty()) {
            f.say("end.nivaris", 70, to = firstTimers)
            markHeard("end.nivaris", firstTimers)
        }
    }

    // --- Training ------------------------------------------------------------------------------------------------------

    fun trainingGlacier(): Script = script {
        yieldAll(waitForFar())
        ensureSplitImmediate()
        val a = f.arius ?: return@script
        f.place(a, f.abs(ArenaLayout.AERIE_SEAT))
        a.play(AriusPose.SEATED)
        // Abschnitt II: Solvane ist der Drache, er sitzt ohne Schwert.
        a.bladeState = AriusBlade.NONE
        a.sheathed = false
        for ((i, p) in f.players().withIndex()) {
            val s = f.abs(ArenaLayout.AERIE_LANDING[i % ArenaLayout.AERIE_LANDING.size])
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), 90f, 0f, false)
        }
        f.spawnNivaris(f.abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5), 90f)
        f.say("echo", 60)
        wait(60)
        yieldAll(glacier())
    }

    internal fun prepareVault() {
        val a = f.arius ?: return
        f.setPhase(P.VAULT)
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        f.place(a, vault)
        a.form = AriusForm.WHITE
        a.bladeState = AriusBlade.ICE
        a.sheathed = false
        a.play(AriusPose.IDLE)
        f.spawnNivaris(f.abs(ArenaLayout.VAULT_REST), 180f)
        f.nivaris?.resonance = true
        for ((i, p) in f.players().withIndex()) {
            val s = f.abs(ArenaLayout.VAULT_LANDING[i % ArenaLayout.VAULT_LANDING.size])
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), 180f, 0f, false)
        }
    }

    fun trainingVault(): Script = script {
        yieldAll(waitForFar())
        ensureSplitImmediate()
        prepareVault()
        f.say("echo", 60)
        wait(60)
        yieldAll(vaultFight())
    }

    fun trainingFinale(summit: Boolean = false): Script = script {
        yieldAll(waitForFar())
        if (summit) {
            restoreSplitInstant()
            prepareSummit(withDragon = false)
            f.dragonDowned = true
            f.say("echo", 60)
            wait(60)
            yieldAll(finale())
        } else {
            prepareVault()
            f.dragonDowned = true
            f.say("echo", 60)
            wait(60)
            yieldAll(finale())
        }
    }

    // --- Test command entry points (/arius test <target>) ---------------------------------------------------------------

    internal fun prepareSummit(withDragon: Boolean, blade: Int = AriusBlade.HELD, sheathed: Boolean = true) {
        val a = f.arius ?: return
        f.onSummit = true
        f.setPhase(P.FINALE)
        val center = f.arenaCenter
        val spots = f.finaleSpots()
        f.place(a, center)
        a.form = AriusForm.WHITE
        a.bladeState = blade
        a.sheathed = sheathed
        a.eyeGlow = 80
        a.play(if (sheathed) AriusPose.IAI_STANCE else AriusPose.IDLE)
        for ((i, p) in f.players().withIndex()) {
            val s = f.abs(spots[i % spots.size])
            val yaw = (Math.toDegrees(atan2(center.z - s.z, center.x - s.x)) - 90.0).toFloat()
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), yaw, 0f, false)
            p.onLanding()
        }
        f.target()?.let { f.face(a, it.entityPos) }
        if (withDragon) {
            f.spawnNivaris(center.add(0.0, 2.0, 6.0), 180f) { d ->
                d.mode = NivarisMode.RETURN_COIL
                d.uncoil = 0f
                d.flying = false
                d.resonance = true
                d.eyeGlow = 100
                d.showNow()
                d.play(NivarisPose.IDLE)
            }
        } else {
            f.dismissNivaris()
        }
    }

    fun testEnsureFarAndSplit(splitActive: Boolean): Script = script {
        yieldAll(waitForFar())
        if (splitActive) ensureSplitImmediate() else restoreSplitInstant()
    }

    fun testIntro(): Script = script {
        val a = f.arius ?: return@script
        yieldAll(waitForFar())
        restoreSplitInstant()
        f.ariusVulnerable = false
        yieldAll(establish(a))
        f.say("intro.seal", 60); wait(66)
        a.play(AriusPose.RISE); wait(24)
        f.say("intro.reason", 70); wait(76)
        f.say("intro.test", 80); wait(50)
        yieldAll(temple())
    }

    fun testPhase1(): Script = script {
        val a = f.arius ?: return@script
        restoreSplitInstant()
        a.play(AriusPose.IDLE)
        yieldAll(temple())
    }

    fun testWhiteHorizon(): Script = script {
        yieldAll(waitForFar())
        restoreSplitInstant()
        val a = f.arius ?: return@script
        f.setPhase(P.TEMPLE)
        f.place(a, f.abs(ArenaLayout.DAIS_FRONT))
        a.form = AriusForm.SERIOUS
        a.bladeState = AriusBlade.HELD
        a.sheathed = false
        a.eyeGlow = 85
        a.play(AriusPose.IDLE)
        yieldAll(theCut())
    }

    fun testKick(): Script = script {
        yieldAll(waitForFar())
        ensureSplitImmediate()
        val a = f.arius ?: return@script
        f.setPhase(P.CUT)
        f.place(a, f.abs(ArenaLayout.CUT_STAND))
        a.form = AriusForm.SERIOUS
        a.bladeState = AriusBlade.HELD
        a.sheathed = false
        a.eyeGlow = 100
        a.play(AriusPose.IDLE)
        val scene = CineScene(f) {
            f.place(a, f.abs(ArenaLayout.WATCH_SEAT))
            a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
            a.play(AriusPose.IDLE)
            for ((i, p) in f.players().withIndex()) {
                val land = f.abs(ArenaLayout.LANDING[i % ArenaLayout.LANDING.size])
                p.teleport(f.world, land.x, land.y, land.z, emptySet(), 180f, -4f, false)
                p.onLanding()
            }
        }
        yieldAll(scene.play(script { yieldAll(kick(scene)) }))
        f.victory()
    }

    fun testSolvaneReveal(): Script = script {
        yieldAll(waitForFar())
        ensureSplitImmediate()
        val a = f.arius ?: return@script
        f.setPhase(P.CUT)
        f.place(a, f.abs(ArenaLayout.WATCH_SEAT))
        a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
        a.form = AriusForm.DARK
        a.bladeState = AriusBlade.HELD
        a.sheathed = false
        a.play(AriusPose.IDLE)
        for ((i, p) in f.players().withIndex()) {
            val land = f.abs(ArenaLayout.LANDING[i % ArenaLayout.LANDING.size])
            p.teleport(f.world, land.x, land.y, land.z, emptySet(), 180f, -4f, false)
            p.onLanding()
        }
        yieldAll(arrival())
    }

    fun testPickupFlight(): Script = script {
        yieldAll(waitForFar())
        ensureSplitImmediate()
        val a = f.arius ?: return@script
        f.setPhase(P.CUT)
        for ((i, p) in f.players().withIndex()) {
            val land = f.abs(ArenaLayout.LANDING[i % ArenaLayout.LANDING.size])
            p.teleport(f.world, land.x, land.y, land.z, emptySet(), 0f, 0f, false)
            p.onLanding()
        }
        Entrance.landed(f, f.abs(Vec3d(0.5, 0.0, 2.0)))
        a.form = AriusForm.DARK
        a.bladeState = AriusBlade.NONE
        yieldAll(toAerie())
        f.victory()
    }

    private fun prepareAerieForRide() {
        val a = f.arius ?: return
        f.setPhase(P.GLACIER)
        f.place(a, f.abs(ArenaLayout.AERIE_SEAT))
        a.form = AriusForm.DARK
        a.bladeState = AriusBlade.NONE
        a.sheathed = false
        a.play(AriusPose.SEATED)
        for ((i, p) in f.players().withIndex()) {
            val s = clearLanding(f.abs(ArenaLayout.AERIE_LANDING[i % ArenaLayout.AERIE_LANDING.size]))
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), 90f, 0f, false)
            p.onLanding()
        }
        f.spawnNivaris(f.abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5), -90f)
        f.nivaris?.play(NivarisPose.EXHAUSTED)
    }

    fun testRideIntro(): Script = script {
        yieldAll(waitForFar())
        ensureSplitImmediate()
        prepareAerieForRide()
        val flight = DragonRide(f)
        val intro = CineScene(f) { flight.settleIntro() }
        yieldAll(intro.play(flight.intro(intro)))
        yieldAll(flight.play())
        f.victory()
    }

    fun testDragonRide(): Script = script {
        yieldAll(waitForFar())
        ensureSplitImmediate()
        prepareAerieForRide()
        f.setPhase(P.COLLAPSE)
        val flight = DragonRide(f)
        yieldAll(flight.play())
        f.victory()
    }

    fun testTransform(): Script = script {
        yieldAll(waitForFar())
        ensureSplitImmediate()
        val a = f.arius ?: return@script
        f.setPhase(P.COLLAPSE)
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        f.place(a, vault.add(0.0, 12.0, 0.0))
        a.form = AriusForm.DARK
        a.bladeState = AriusBlade.NONE
        a.sheathed = false
        a.play(AriusPose.STAB_GROUND)
        f.spawnNivaris(f.abs(ArenaLayout.VAULT_REST), 180f) { d ->
            d.flying = false
            d.play(NivarisPose.EXHAUSTED)
        }
        for ((i, p) in f.players().withIndex()) {
            val s = f.abs(ArenaLayout.VAULT_LANDING[i % ArenaLayout.VAULT_LANDING.size])
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), 180f, 0f, false)
            p.onLanding()
        }
        f.face(a, f.abs(ArenaLayout.VAULT_LANDING[0]))
        val scene = CineScene(f) {}
        yieldAll(scene.play(script { yieldAll(transformBody(scene)) }))
        f.victory()
    }

    fun testWhiteHole(): Script = script {
        yieldAll(waitForFar())
        ensureSplitImmediate()
        prepareVault()
        f.ariusMax = BossConfig.health(640f, f.startCount)
        f.ariusHp = f.ariusMax * 0.5f
        f.dragonMax = BossConfig.health(500f, f.startCount)
        f.dragonHp = f.dragonMax
        f.dragonDowned = false
        f.ariusVulnerable = true
        f.dragonVulnerable = true
        MusicCue.play(f, "white_hole", 20)
        yieldAll(WhiteHoleBlast(f).run())
        f.victory()
    }

    fun testPrepareForHunt(splitActive: Boolean): Script = script {
        yieldAll(waitForFar())
        if (splitActive) ensureSplitImmediate() else restoreSplitInstant()
        prepareVault()
        f.ariusMax = BossConfig.health(640f, f.startCount)
        f.ariusHp = f.ariusMax * 0.4f
        f.dragonMax = BossConfig.health(500f, f.startCount)
        f.dragonHp = f.dragonMax * 0.5f
        f.dragonDowned = false
        f.ariusVulnerable = false
        f.dragonVulnerable = false
    }

    fun testHuntArrive(): Script = script {
        yieldAll(testPrepareForHunt(splitActive = false))
        val hunt = Hunt(f)
        yieldAll(hunt.arrive())
        f.victory()
    }

    fun testBladeReturn(): Script = script {
        yieldAll(waitForFar())
        restoreSplitInstant()
        prepareSummit(withDragon = true, blade = AriusBlade.ICE, sheathed = false)
        val a = f.arius ?: return@script
        yieldAll(bladeReturn(a, forcePlay = true))
        f.victory()
    }

    fun testLastHorizon(): Script = script {
        yieldAll(waitForFar())
        restoreSplitInstant()
        prepareSummit(withDragon = false, blade = AriusBlade.HELD, sheathed = false)
        val a = f.arius ?: return@script
        f.ariusMax = BossConfig.health(480f, f.startCount)
        f.ariusHp = f.ariusMax * 0.45f
        f.ariusVulnerable = true
        f.music("music.summit", 20)
        yieldAll(lastHorizon(a))
        f.victory()
    }

    fun testClimax(): Script = script {
        yieldAll(waitForFar())
        restoreSplitInstant()
        prepareSummit(withDragon = false, blade = AriusBlade.HELD, sheathed = false)
        val a = f.arius ?: return@script
        f.ariusMax = BossConfig.health(480f, f.startCount)
        f.ariusHp = f.ariusMax * 0.45f
        f.ariusVulnerable = true
        f.moves.resetStance(AriusMoves.Stance.FLOW, 200)
        f.music("music.summit", 20)
        MusicCue.play(f, "summit_final", 20)
        f.moves.summit.debugJumpToLastStand()
        f.updateBars()
        while (f.ariusHp > 0f) {
            runWhile(f.moves.turnFinale(true)) { f.ariusHp > 0f }
        }
        f.ariusVulnerable = false
        f.calls.reset()
        yieldAll(ending())
    }

    fun testEnding(): Script = script {
        yieldAll(waitForFar())
        restoreSplitInstant()
        prepareSummit(withDragon = false, blade = AriusBlade.HELD, sheathed = true)
        yieldAll(ending())
    }

    private companion object {
        /** Ticks von der Auflösung des Drachen bis zu seinem Verschwinden (Höhepunkt des Lichts, wie DragonDissolve.PEAK). */
        const val RETURN_PEAK = 14

        /** Flugzeit des Lichtstroms vom Drachen in die Scheide. */
        const val RETURN_TRAVEL = 28
    }

    /**
     * Freier Boden nahe [at]: Im Horst stehen kleine Eisspitzen zwischen den Landeplätzen, ein Abwurf genau auf den Platz
     * landete sonst halb in einer davon. Gesucht wird spiralförmig bis 8 Blöcke weit eine Stelle, an der 2 Blöcke rundum
     * bis Kopfhöhe nichts im Weg steht.
     */
    private fun clearLanding(at: Vec3d): Vec3d {
        val pos = BlockPos.Mutable()
        fun free(c: Vec3d): Boolean {
            val bx = kotlin.math.floor(c.x).toInt()
            val by = kotlin.math.floor(c.y + 0.01).toInt()
            val bz = kotlin.math.floor(c.z).toInt()
            for (dx in -2..2) for (dz in -2..2) for (dy in 0..2) {
                pos.set(bx + dx, by + dy, bz + dz)
                if (!f.world.getBlockState(pos).getCollisionShape(f.world, pos).isEmpty) return false
            }
            pos.set(bx, by - 1, bz)
            return !f.world.getBlockState(pos).getCollisionShape(f.world, pos).isEmpty
        }
        if (free(at)) return at
        for (r in 1..8) for (k in 0 until 8 * r) {
            val a = 2.0 * PI * k / (8 * r)
            val c = at.add(kotlin.math.cos(a) * r, 0.0, kotlin.math.sin(a) * r)
            if (free(c)) return c
        }
        return at
    }

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }
}

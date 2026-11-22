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
        val pre = CineScene(f) {
            f.place(a, stand)
            a.play(AriusPose.CUT_CHARGE)
        }
        yieldAll(pre.play(script {
            // Sprung auf die Altarstufe: Halbtotale von der Westseite der Halle, der Blick folgt ihm.
            val start = a.entityPos
            pre.line(rel(-9.0, 1.4, -50.0), rel(-8.0, 2.2, -54.5), stand, stand, 14, 62.0, 56.0, lookAt = a, lookOffset = 1.3)
            cue("arius.wind_step", start, 2f, 0.7f)
            for (i in 1..12) {
                val k = i / 12.0
                f.place(a, start.lerp(stand, k).add(0.0, sin(k * PI) * 2.0, 0.0))
                yield(Unit)
            }
            a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
            a.play(AriusPose.CUT_CHARGE)
            f.fx(FxKind.REVERSE_WIND, stand.add(0.0, 2.0, 0.0), a = 30f, life = AriusTimings.CUT_WIND + 50)
            cue("cut.reverse_wind", stand, 4f)
            // Stille, Detail: Hände und Klinge, an denen sich der Wind sammelt; ganz langsam näher.
            pre.line(stand.add(-2.2, 0.75, 2.6), stand.add(-1.6, 0.85, 2.0), stand, stand, 30, 44.0, 36.0, lookAt = a, lookOffset = 1.15)
            wait(20)
            f.fx(FxKind.EDGE, stand, life = 70, e1 = a)
            cue("cut.charge", stand, 3f)
            // Rückweg zur Spielerkamera, fertig bevor die Ansage beginnt.
            pre.home(10)
            wait(AriusTimings.CUT_LINE_AT - 20)
        }))
        f.cutSurvivors.clear()
        f.cutSurvivors.addAll(f.players().map { it.uuid })
        // Seit 1.5.1 (nach Crix' Bild): Er lädt eine riesige Klinge aus Licht auf, hoch über sich; der Himmel hinter ihm
        // füllt sich mit Licht. Mit dem Schlag spaltet sie den Berg im Süden.
        f.fx(FxKind.GIANT_BLADE, stand, a = 38f, b = AriusTimings.CUT_LINE_TICKS.toFloat(), life = AriusTimings.CUT_LINE_TICKS + 22, e1 = a)
        for (p in f.audience()) dev.henny.hha.logic.boss.Cine.title(listOf(p), "hha_arius.feat.white_horizon", "hha_arius.feat.white_horizon.sub", 60)
        cue("cut.charge", stand, 5f, 0.6f)
        val group = f.calls.boss(pace = Telegraph.Pace.ROOM)
        val line = group.line(f.abs(ArenaLayout.CUT_FROM), f.abs(ArenaLayout.CUT_TO), ArenaLayout.CUT_WIDTH.toFloat(), AriusTimings.CUT_LINE_TICKS)
        wait(line.ticks)
        a.play(AriusPose.CUT_RELEASE, 1)
        f.ambient("cut.strike", stand, 5f)
        MusicCue.sting(f, "impact")
        for (p in line.hits(f.players())) {
            f.cutSurvivors.remove(p.uuid)
            f.hurt(p, 30f, Damage.Kind.NULL_CUT)
        }
        val vantage = Vantage(f)
        val post = CineScene(f) {
            vantage.restore()
        }
        yieldAll(post.play(script { yieldAll(cutAftermath(post, vantage, a, stand)) }))
        vantage.restore()
        f.atmos(dev.henny.hha_arius.net.AtmosMode.CLEAR, 30)
        yieldAll(arrival())
    }

    /**
     * Der Weiße Horizont nach dem Schlag:
     * 1. **Zeitlupe** nah an ihm, die Klinge am Ende des Schwungs; die Naht glimmt über den Boden davon.
     * 2. **Hoch über dem Tempel**: Blick nach Süden die Naht entlang, die aufs Massiv zurast.
     * 3. **Wolkenverhüllung (Totale von hoch außen)**: dichte, wirbelnde Schneewolken hüllen die gesamte Schnittlinie am
     *    Berg ein (ohne schwebende Block-Trümmer!), während dahinter das Massiv sauber gespalten wird.
     * 4. **Enthüllung durch die teilenden Wolken**: hohe, kollisionsfreie Fahrt von Nordosten — eine Druckwelle reißt
     *    die Schneewolken auseinander und gibt den Blick auf die tiefe Kluft frei.
     * 5. **Weite Panorama-Totale**: der gespaltene Berg unter offenem Himmel; dann rollt der Whiteout heran ([kick]).
     */
    private fun cutAftermath(post: CineScene, vantage: Vantage, a: AriusEntity, stand: Vec3d): Script = script {
        val foot = ArenaLayout.SPIRE.z - FarPlan.MASSIF_R
        val lookout = f.abs(Vec3d(ArenaLayout.CUT_X - 38.0, 0.0, foot - 34.0))
        val x = ArenaLayout.CUT_X
        val flankEnd = minOf(ArenaLayout.SPIRE.z - FarPlan.SHOULDER_R - 10.0, foot + 92.0)
        fun shroudClouds(life: Int) {
            shown {
                val steps = 11
                for (k in 0..steps) {
                    val z = foot - 4.0 + (flankEnd - (foot - 4.0)) * (k / steps.toDouble())
                    val top = runCatching { FarPlan.top(x.toInt(), z.toInt()) }.getOrDefault(0).coerceAtLeast(0)
                    val radius = (30f + (k % 3) * 7f).coerceAtMost(54f)
                    val at = f.abs(Vec3d(x + ((k % 3) - 1) * 4.0, top + 6.0, z))
                    f.fxAll(FxKind.DEBRIS_CLOUD, at, a = radius, b = 1f, life = life)
                }
            }
        }
        MusicCue.play(f, "horizon", 10)
        // 1) Zeitlupe auf ihm. Die Körper gehen jetzt schon an den Aussichtspunkt, das Bild zeigt noch die Altarstufe.
        vantage.move(lookout)
        shown {
            f.fxAll(FxKind.FLASH, stand, a = 0.55f, life = 10)
            f.fxAll(FxKind.TIME_SLOW, stand, a = 1f, life = 50)
        }
        f.calls.roomShake(lookout, 6f, 200f)
        val face = stand.add(0.0, 1.5, 0.0)
        post.line(stand.add(-2.4, 0.9, 3.0), stand.add(-2.8, 1.25, 3.6), face, face.add(0.0, -0.3, 2.0), 46, 46.0, 40.0)
        cue("cut.strike", stand, 3f, 0.5f)
        cue("transform.pulse", stand, 3f, 0.45f)
        shown { Vfx.crescent(f.world, Vfx.Style.HEAVEN, stand.add(0.0, 1.5, 0.0), Vec3d(0.0, 0.0, 1.0), 110f, 5f, 12) }
        val seamFrom = f.abs(ArenaLayout.CUT_FROM)
        val seamTo = f.abs(Vec3d(x, 0.0, foot + 6.0))
        f.fxAll(FxKind.SEAM, seamFrom.add(0.0, 0.2, 0.0), seamTo.add(0.0, 0.2, 0.0), a = 14f, life = 160)
        for (i in 0 until 44) {
            if (i % 4 == 0) shown { f.fxAll(FxKind.CLEAR_WAVE, seamFrom.lerp(seamTo, i / 120.0), a = 2.5f, life = 16) }
            if (i == 22) cue("cut.reverse_wind", stand, 2f, 0.5f)
            yield(Unit)
        }
        // 2) Die Zeit schnappt zurück: hoch über dem Tempel, Blick nach Süden die Naht entlang bis an den Berg.
        shown { f.fxAll(FxKind.FLASH, stand, a = 0.3f, life = 6) }
        f.farSound("cut.split", seamTo, 5f, 1.2f)
        MusicCue.sting(f, "phase")
        post.line(f.abs(Vec3d(x - 18.0, 42.0, -86.0)), f.abs(Vec3d(x - 14.0, 50.0, -60.0)),
            f.abs(Vec3d(x, 4.0, -10.0)), f.abs(Vec3d(x, 34.0, foot + 34.0)), 30, 70.0, 64.0)
        shown {
            for (k in 0 until 6) f.fxAll(FxKind.CLEAR_WAVE, seamFrom.lerp(seamTo, 0.3 + k * 0.12), a = 5f, life = 14)
        }
        wait(22)
        // Noch bevor der Schnitt den Berg teilt, quellen dichte Schneewolken entlang der gesamten Bergflanke auf.
        shroudClouds(life = 112)
        f.farSound("seal.blizzard", f.abs(Vec3d(x, 36.0, foot + 24.0)), 6f, 0.55f)
        wait(8)
        // 3) Verhüllung in Schneewolken (Totale hoch von Nordwesten, weit außerhalb des Massivs): hinter der Wolkenwand spaltet der Berg.
        val mid = f.abs(Vec3d(x, 36.0, foot + 36.0))
        f.farSound("glacier.collapse", mid, 8f, 0.5f)
        f.farSound("cut.split", mid, 8f, 0.7f)
        f.farVanilla(net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EXPLODE.value(), mid, 6f, 0.4f)
        f.farVanilla(net.minecraft.sound.SoundEvents.ENTITY_WARDEN_SONIC_BOOM, mid, 5f, 0.5f)
        post.line(f.abs(Vec3d(x - 42.0, 86.0, foot - 134.0)), f.abs(Vec3d(x - 34.0, 96.0, foot - 118.0)),
            f.abs(Vec3d(x, 34.0, foot + 34.0)), f.abs(Vec3d(x, 68.0, foot + 72.0)), 64, 58.0, 54.0)
        wait(16)
        // Jetzt, vollständig von der dichten Wolkenwand verdeckt, wird die Kluft sauber ausgehoben (keine schwebenden Blöcke!).
        split()
        for (i in 16 until 64) {
            if (i % 14 == 0) f.calls.roomShake(lookout, 5.5f - i / 22f, 200f)
            if (i == 26) {
                shroudClouds(life = 78)
                f.farVanilla(net.minecraft.sound.SoundEvents.BLOCK_GLASS_BREAK, mid, 5f, 0.5f)
            }
            if (i == 42) f.farSound("glacier.collapse", mid, 7f, 0.45f)
            yield(Unit)
        }
        // 4) Enthüllung: hohe, weite Kamerafahrt von Nordosten – die Schneewolken teilen sich und zeigen den sauberen Spalt.
        post.line(f.abs(Vec3d(x + 76.0, 108.0, foot - 108.0)), f.abs(Vec3d(x + 58.0, 118.0, foot - 92.0)),
            f.abs(Vec3d(x, 42.0, foot + 36.0)), f.abs(Vec3d(x, 66.0, foot + 68.0)), 58, 60.0, 56.0)
        shown {
            for (k in 0 until 6) {
                val z = foot + 6.0 + k * 14.0
                val top = runCatching { FarPlan.top(x.toInt(), z.toInt()) }.getOrDefault(0).coerceAtLeast(0)
                val at = f.abs(Vec3d(x, top + 2.0, z))
                f.fxAll(FxKind.CLEAR_WAVE, at, a = 22f, life = 28)
            }
        }
        f.farSound("cut.reverse_wind", mid, 5f, 0.65f)
        wait(58)
        // 5) Ganz weit und hoch: der gespaltene Berg klar unter dem Himmel.
        post.line(f.abs(Vec3d(x - 78.0, 134.0, foot - 158.0)), f.abs(Vec3d(x - 90.0, 148.0, foot - 174.0)),
            f.abs(Vec3d(x, 52.0, foot + 48.0)), f.abs(Vec3d(x, 72.0, foot + 64.0)), 54, 66.0, 70.0)
        shown {
            for (k in 0 until 5) {
                val z = foot + 8.0 + k * 16.0
                val top = runCatching { FarPlan.top(x.toInt(), z.toInt()) }.getOrDefault(0).coerceAtLeast(0)
                val at = f.abs(Vec3d(x, top.toDouble(), z))
                Vfx.shockwave(f.world, Vfx.Style.HEAVEN, at, 16f, 22, 2.2f)
            }
            f.sceneText("cut_after")
        }
        for (id in f.cutSurvivors) f.server.playerManager.getPlayer(id)?.let { Advancements.grant(it, "under_the_line") }
        wait(38)
        // 6) Whiteout: die Schneewolke rollt heran.
        f.farSound("seal.blizzard", lookout, 4f, 0.6f)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.BLIZZARD, 16)
        post.once { Cine.fade(it, 0xF2F6FA, 0.0, 1.0, 16) }
        wait(20)
        // Im Weiß: zurück in den Tempel.
        vantage.restore()
        wait(4)
        yieldAll(kick(post))
    }

    /**
     * Seit 1.5.1 (Crix: „den Tempel in zwei Hälften spalten macht keinen Sinn – wenn, dann wird ein Berg geteilt“): Der
     * Schnitt spaltet das Massiv im Süden der Länge nach. Entlang der Schnittebene (x ≈ [ArenaLayout.CUT_X]) entsteht
     * eine Kluft, oben breiter als unten, quer durch Berg und Horn; sichtbare Brocken stürzen hinein. Gebaut wird in
     * Scheiben über mehrere Ticks; am Kampfende stellt [ArenaEdits] alles wieder her.
     */
    fun split(animate: Boolean = true) {
        val z0 = (ArenaLayout.SPIRE.z - dev.henny.hha_arius.arena.FarPlan.MASSIF_R - 6).toInt()
        val z1 = (ArenaLayout.SPIRE.z + dev.henny.hha_arius.arena.FarPlan.MASSIF_R + 6).toInt()
        if (animate) {
            val from = f.abs(Vec3d(ArenaLayout.CUT_X, 0.2, ArenaLayout.CUT_TO.z))
            f.fx(FxKind.SEAM, from, f.abs(Vec3d(ArenaLayout.CUT_X, 0.2, z1.toDouble())), a = 220f, life = AriusTimings.CUT_SLIDE + 40)
            f.ambient("cut.split", f.abs(Vec3d(ArenaLayout.CUT_X, 40.0, ArenaLayout.SPIRE.z.toDouble())), 8f)
            f.ambient("glacier.collapse", from, 6f, 0.6f)
            f.calls.roomShake(from, 8f, 90f)
        }
        var z = z0
        f.task(script {
            while (z <= z1) {
                carve(z, minOf(z1, z + 5), animate)
                z += 6
                yield(Unit)
            }
        })
    }

    /** Kluft für die Scheibe z0..z1 (relativ): V-förmig, unten 3, oben bis 9 Blöcke breit (ohne schwebende Block-Trümmer). */
    private fun carve(z0: Int, z1: Int, @Suppress("UNUSED_PARAMETER") animate: Boolean) {
        val pos = BlockPos.Mutable()
        for (z in z0..z1) {
            for (dx in -6..6) {
                val x = kotlin.math.floor(ArenaLayout.CUT_X).toInt() + dx
                val top = dev.henny.hha_arius.arena.FarPlan.top(x, z)
                if (top < 0) continue
                val pathY = dev.henny.hha_arius.arena.FarPlan.pathAt(x, z)
                val dAxis = kotlin.math.hypot(x + 0.5 - (ArenaLayout.SPIRE.x + 0.5), z + 0.5 - (ArenaLayout.SPIRE.z + 0.5))
                for (y in 0..top + 1) {
                    // Seit 1.7.0: Der Weg überspannt die Kluft als Felsbrücke, und der Gipfel bleibt heil (Finale oben).
                    if (!pathY.isNaN() && y >= pathY - 6) continue
                    if (dAxis <= dev.henny.hha_arius.arena.FarPlan.SHOULDER_R + 8.0 && y >= dev.henny.hha_arius.arena.FarPlan.SHOULDER_Y - 14) continue
                    val hw = 1.5 + y / 50.0
                    if (kotlin.math.abs(x + 0.5 - ArenaLayout.CUT_X) > hw) continue
                    pos.set(f.origin.x + x, f.origin.y + y, f.origin.z + z)
                    if (f.world.getBlockState(pos).isAir) continue
                    f.edits.clear(pos)
                }
            }
        }
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
        // 1) Aufbau über die Schultern.
        scene.once { Cine.fade(it, 0xF2F6FA, 1.0, 0.0, 20) }
        f.face(a, ground)
        a.play(AriusPose.CUT_CHARGE)
        val shoulder = ground.subtract(north.multiply(3.2)).add(1.4, 1.75, 0.0)
        scene.line(shoulder, ground.subtract(north.multiply(2.2)).add(1.1, 1.8, 0.0), stand.add(0.0, 1.5, 0.0), stand.add(0.0, 1.6, 0.0), 40, 62.0, 48.0)
        cue("cut.reverse_wind", stand, 3.5f, 0.8f)
        shown { f.fx(FxKind.REVERSE_WIND, stand.add(0.0, 1.0, 0.0), a = 9f, life = 50) }
        wait(12)
        shown { f.say("kick", 40) }
        cue("cut.charge", stand, 2.5f, 1.4f)
        wait(28)
        // 2) Windschritt: Schnitt halbhoch aus dem Gang, er reißt in einer Lichtspur vor sie.
        val front = ground.add(north.multiply(2.6))
        scene.line(front.add(north.multiply(5.0)).add(-1.6, 2.6, 0.0), front.add(north.multiply(4.2)).add(-1.3, 2.3, 0.0), front.add(0.0, 1.0, 0.0), ground.add(0.0, 1.0, 0.0), 8, 70.0, 64.0)
        a.play(AriusPose.LUNGE, 4)
        cue("arius.wind_step", stand, 3f, 1.2f)
        shown { f.fx(FxKind.DASH_STREAK, stand, front, life = 10) }
        for (i in 1..4) {
            f.place(a, stand.lerp(front, i / 4.0).let { Vec3d(it.x, stand.y + (y0 - stand.y) * (i / 4.0), it.z) })
            yield(Unit)
        }
        f.face(a, ground)
        a.play(AriusPose.SPIN_CUT, 4)
        wait(4)
        // 3) Aufprallbild: Treffer-Stopp, alle hängen in der Luft; die Musik reißt ab; benutzerdefinierte Schleuderanimation startet.
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
        MusicCue.sting(f, "impact")
        val south = north.negate()
        shown {
            f.fxAll(FxKind.FLASH, front, a = 0.5f, life = 6)
            f.fxAll(FxKind.TIME_SLOW, front, a = 0.9f, life = 14)
            f.fx(FxKind.SLASH_ARC, front.add(0.0, 1.1, 0.0), south, a = 7f, b = 0.05f, life = 16)
            f.fx(FxKind.GALE, front.add(0.0, 1.0, 0.0), south.multiply(16.0), a = 1f, life = 16)
            Vfx.shockwave(f.world, Vfx.Style.HEAVEN, front, 7f, 16, 2.4f)
            f.fx(FxKind.CLEAR_WAVE, front, a = 9f, life = 16)
        }
        cue("hold.impact", front, 3f, 0.6f)
        cue("cut.strike", front, 4f, 0.7f)
        cue("arius.kick", front, 3f, 0.6f)
        f.calls.hitShake(front, 5f, 30f)
        val above = front.add(north.multiply(3.4)).add(-1.2, 4.2, 0.0)
        scene.line(above, above.add(north.multiply(0.4)).add(0.0, 0.3, 0.0), ground.add(0.0, 1.0, 0.0), ground.add(south.multiply(1.0)).add(0.0, 1.2, 0.0), hitstop, 58.0, 54.0)
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
                    cue("hold.impact", p.entityPos, 1.6f, 0.8f)
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
    private fun arrival(): Script = script {
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
        // Seit 1.10.0: Sie ist gekommen, um sie zu holen – sie senkt sich vor ihnen nieder (der Ritt folgt in [toAerie]).
        d.play(NivarisPose.LIE_DOWN)
        val politeFresh = newcomers("dragon.polite")
        f.say(if (politeFresh.isNotEmpty()) "dragon.polite" else "dragon.polite_short", 80)
        markHeard("dragon.polite")
        wait(40)
        yieldAll(waitForFar())
        yieldAll(toAerie())
        yieldAll(glacier())
    }

    /**
     * Seit 1.10.0 eine durchgehende Handlung (Crix: „sie landet vor dem Tempel und fliegt eine Sekunde später in ihre
     * Arena – das ergibt keinen Sinn“): Sie ist **gelandet, um sie zu holen**.
     * 1. Sie liegt flach vor den Spielern ([arrival]); Arius ruft von der Altarstufe „Bring sie in deinen Horst“.
     * 2. Ihr Lichtstrom hebt jeden Spieler auf ihren Rücken an seinen Dorn – sie liegt dabei still.
     * 3. Sie stößt sich ab, steigt über das Plateau und streicht über den Tempel: Arius springt auf ihren Nacken.
     * 4. Flug zum Horst, die Kamera fliegt neben ihr her; der dunkle Sturmhimmel des Horsts zieht auf.
     * 5. Ankunft: ein weiter Kreis über dem Kessel (nie enger als ihr Körper), schwere Landung in der Mitte, die Spieler
     *    springen ab, Arius auf seinen Felspfeiler. Titelkarte.
     */
    private fun toAerie(): Script = script {
        val d = f.nivaris ?: return@script
        val a = f.arius ?: return@script
        val aerie = f.abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5)
        val seat = f.abs(ArenaLayout.AERIE_SEAT)
        val finish = {
            for (p in f.audience()) if (p.vehicle === d) p.stopRiding()
            RideSeats.unperch(f, a)
            RideSeats.clear(f)
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
                    val s = f.abs(ArenaLayout.AERIE_LANDING[i % ArenaLayout.AERIE_LANDING.size])
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
        MusicCue.play(f, "carry", 40)
        // 1) Sie liegt flach vor ihnen; Arius ruft von der Stufe.
        d.flying = false
        d.play(NivarisPose.LIE_DOWN)
        val toward = f.facing(d)
        val sideD = Vec3d(-toward.z, 0.0, toward.x)
        val riders = f.players()
        val mid = if (riders.isEmpty()) start.add(toward.multiply(10.0)) else riders.map { it.entityPos }.fold(Vec3d.ZERO) { acc, v -> acc.add(v) }.multiply(1.0 / riders.size)
        // Halbtotale von der Seite: sie flach im Schnee, davor die Spieler.
        val look0 = start.lerp(mid, 0.4).add(0.0, 1.5, 0.0)
        scene.line(look0.add(sideD.multiply(20.0)).add(0.0, 4.5, 0.0), look0.add(sideD.multiply(16.0)).add(0.0, 3.6, 0.0), look0, look0.add(0.0, 0.5, 0.0), 70, 60.0, 54.0)
        shown { f.say("dragon.carry", 60) }
        cue("resonance.chime", perch, 3f, 1.1f)
        d.eyeGlow = 100
        wait(16)
        // 2) Der Lichtstrom hebt jeden an seinen Dorn auf ihrem Rücken.
        val mounted = HashSet<java.util.UUID>()
        RideSeats.assign(f)
        for ((i, p) in f.players().withIndex()) {
            if (i > 0) wait(5)
            if (!p.isAlive || p.isRemoved) continue
            mounted.add(p.uuid)
            shown { f.fx(FxKind.THREAD, p.entityPos, life = 24, e1 = p, e2 = d) }
            cue("resonance.chime", p.entityPos, 1.6f, 1.3f + i * 0.05f)
            p.startRiding(d, true, true)
        }
        wait(30)
        // 3) Sie stößt sich ab, streicht über den Tempel, Arius springt auf ihren Kopf. Seit 1.11.0 ist der ganze Flug eine
        //    einzige Bahn ([DragonRail]): über das Plateau, nach Westen, in weitem Bogen um den Kessel und von Westen herein.
        d.play(NivarisPose.TAKEOFF, 6)
        f.farSound("nivaris.wing", start, 5f, 0.7f)
        f.farSound("nivaris.roar", start, 5f, 0.9f)
        f.calls.roomShake(start, 3f, 50f)
        wait(6)
        d.flying = true
        d.play(NivarisPose.GLIDE)
        val toD = flatDir(perch, start)
        val pickup = perch.add(0.0, 16.0, 0.0).add(toD.multiply(6.0))
        val rail = DragonRail.path(
            start, toward.add(0.0, 0.3, 0.0),
            listOf(pickup, aerie.add(96.0, 36.0, -34.0), aerie.add(26.0, 30.0, -60.0), aerie.add(-46.0, 20.0, -36.0), aerie.add(-42.0, 8.0, 0.0), aerie),
        )
        val len = DragonRail.length(rail)
        fun pace(q: Double) = when {
            q < 40.0 -> 0.5 + 1.2 * (q / 40.0)
            q > len - 46.0 -> 0.55 + 1.15 * ((len - q) / 46.0).coerceIn(0.0, 1.0)
            else -> 1.7
        }
        // Dauer bis zu einer Stelle der Bahn (für die Kameraeinstellungen).
        fun ticksTo(q: Double): Int {
            var x = 0.0
            var n = 0
            while (x < q) { x += pace(x); n++ }
            return n
        }
        val total = ticksTo(len)
        var arrival = len
        run {
            var x = len
            while (x > 0.0 && DragonRail.at(rail, x).distanceTo(aerie) < 118.0) x -= 2.0
            arrival = x
        }
        val tArrive = ticksTo(arrival).coerceAtMost(total - 40)
        val sweep = (ticksTo(minOf(len, 90.0))).coerceAtMost(tArrive)
        val sweepDir = flatDir(start, pickup)
        val sideS = Vec3d(-sweepDir.z, 0.0, sweepDir.x)
        scene.line(start.add(sideS.multiply(26.0)).add(0.0, 6.0, 0.0).subtract(sweepDir.multiply(4.0)), pickup.add(sideS.multiply(28.0)).add(0.0, 10.0, 0.0),
            start.add(0.0, 3.0, 0.0), pickup, sweep, 70.0, 66.0, lookAt = d, lookOffset = 3.0)
        var q0 = 0.0
        var tick = 0
        var jumpAt = -1
        var ariusUp = false
        var stormed = false
        var landing = false
        while (q0 < len) {
            q0 = minOf(len, q0 + pace(q0))
            tick++
            val pos = DragonRail.at(rail, q0)
            val ahead = DragonRail.at(rail, q0 + 3.0)
            f.place(d, pos)
            if (ahead.squaredDistanceTo(pos) > 0.01) f.face(d, ahead)
            for (p in f.players()) if (p.uuid in mounted && p.vehicle !== d && p.isAlive) p.startRiding(d, true, true)
            // Arius springt ab, sobald sie über ihn streicht, und setzt sich auf ihren Kopf.
            if (!ariusUp && jumpAt < 0 && pos.distanceTo(perch) < 22.0) {
                jumpAt = tick
                a.play(AriusPose.LAUNCH, 6)
                cue("arius.wind_step", perch, 3f, 0.8f)
            }
            if (jumpAt > 0 && !ariusUp) {
                val kk = ((tick - jumpAt) / 10.0).coerceAtMost(1.0)
                f.place(a, perch.lerp(neckOf(d), kk).add(0.0, sin(kk * PI) * 3.0, 0.0))
                if (kk >= 1.0) {
                    ariusUp = true
                    RideSeats.perch(f, a, d)
                }
            }
            if (tick == sweep) {
                // Die Kamera fliegt neben ihr her bis an den Kessel.
                val from = pos
                val to = DragonRail.at(rail, arrival)
                val dir = flatDir(from, to)
                val side = Vec3d(-dir.z, 0.0, dir.x)
                scene.line(from.add(side.multiply(-24.0)).add(0.0, 9.0, 0.0).subtract(dir.multiply(10.0)), to.add(side.multiply(-30.0)).add(0.0, 13.0, 0.0).subtract(dir.multiply(24.0)),
                    from, to, (tArrive - sweep).coerceAtLeast(1), 70.0, 64.0, lookAt = d, lookOffset = 2.0)
            }
            if (tick == tArrive) {
                val ang = Math.toDegrees(atan2(pos.z - aerie.z, pos.x - aerie.x)) + 30.0
                scene.orbit(aerie, 62.0, 22.0, ang, ang + 95.0, (total - tArrive).coerceAtLeast(1) + 8, 72.0, 60.0, lookAt = d, lookOffset = 3.0, radiusTo = 40.0, heightTo = 8.0)
            }
            if (!stormed && tick > tArrive / 2) {
                stormed = true
                f.atmos(dev.henny.hha_arius.net.AtmosMode.AERIE, 80)
            }
            if (tick == tArrive / 3) f.farSound("nivaris.roar", pos, 5f, 0.85f)
            if (tick % 22 == 0) cue("nivaris.wing", pos, 4f, 0.75f)
            if (!landing && len - q0 < 30.0) {
                landing = true
                d.play(NivarisPose.LAND, 24)
            }
            yield(Unit)
        }
        if (!ariusUp) RideSeats.perch(f, a, d)
        d.flying = false
        f.place(d, aerie)
        f.face(d, aerie.add(10.0, 0.0, 0.0))
        f.farSound("nivaris.land", aerie, 5f, 0.8f)
        f.calls.roomShake(aerie, 6f, 80f)
        shown {
            Vfx.shockwave(f.world, Vfx.Style.SOUL, aerie, 30f, 26, 2.6f)
            f.fx(FxKind.CLEAR_WAVE, aerie, a = 34f, life = 28)
        }
        d.play(NivarisPose.IDLE)
        // 5) Sie wirft alle ab (seit 1.11.0 als eigener Moment, Crix: „man soll besser sehen, wie Solvane mich rauswirft“):
        //    Blick von der Seite auf ihren Rücken mit den Reitern, sie bäumt sich auf, ein Ruck, und alle fliegen im hohen
        //    Bogen über ihren Kopf auf die Kamera zu.
        val backLook = aerie.add(-6.0, 3.4, 0.0)
        scene.line(aerie.add(-9.0, 5.5, 19.0), aerie.add(-6.0, 6.0, 16.0), backLook, backLook.add(1.0, 0.8, 0.0), 32, 58.0, 50.0)
        wait(14)
        d.play(NivarisPose.ROAR, 12)
        d.eyeGlow = 100
        cue("nivaris.inhale", aerie, 4f, 0.7f)
        f.calls.roomShake(aerie, 3f, 60f)
        wait(16)
        f.farSound("nivaris.roar", aerie, 6f, 0.75f)
        f.calls.roomShake(aerie, 7f, 80f)
        MusicCue.sting(f, "roar")
        shown {
            f.fxAll(FxKind.FLASH, aerie, a = 0.25f, life = 5)
            Vfx.shockwave(f.world, Vfx.Style.SOUL, aerie.add(-6.0, 2.0, 0.0), 14f, 16, 1.8f)
        }
        d.play(NivarisPose.TAIL, 4)
        val thrown = ArrayList<ServerPlayerEntity>()
        var airtime = 26
        for ((j, p) in f.players().withIndex()) {
            if (p.vehicle === d) p.stopRiding()
            val s = f.abs(ArenaLayout.AERIE_LANDING[j % ArenaLayout.AERIE_LANDING.size])
            Hold.drop(p)
            Hold.allowLong(p, 90)
            val up = p.entityPos.add(0.0, 0.4, 0.0)
            if (p.isAlive) p.teleport(f.world, up.x, up.y, up.z, emptySet(), 90f, 0f, false)
            airtime = maxOf(airtime, Ballistic.ticks(s.y - up.y, 1.25))
            Hold.launch(p, Ballistic.velocity(up, s, 1.25), 80, s, s)
            thrown += p
            cue("hold.impact", up, 2f, 0.7f + j * 0.06f)
        }
        // Von weit vorn mit langer Brennweite: Sie bleibt im Bild, und die Reiter fliegen im ganzen Bogen auf die Kamera zu
        // und schlagen vor ihr auf (näher dran verließen sie das Bild schon nach wenigen Ticks).
        scene.line(aerie.add(80.0, 5.0, 6.0), aerie.add(77.0, 5.5, 6.0), aerie.add(16.0, 6.5, 0.0), aerie.add(36.0, 4.0, 0.0), airtime + 2, 44.0, 50.0)
        // Währenddessen springt Arius von ihrem Kopf auf seinen Pfeiler.
        RideSeats.unperch(f, a)
        val jumpFrom = a.entityPos
        a.play(AriusPose.LAUNCH, 6)
        cue("arius.wind_step", jumpFrom, 3f, 0.7f)
        for (i in 1..(airtime + 2)) {
            if (i <= 18) {
                val k = i / 18.0
                f.place(a, jumpFrom.lerp(seat, k).add(0.0, sin(k * PI) * 6.0, 0.0))
                f.face(a, aerie)
            }
            if (i == 18) {
                f.place(a, seat)
                a.play(AriusPose.SEATED)
            }
            yield(Unit)
        }
        if (airtime + 2 < 18) {
            f.place(a, seat)
            a.play(AriusPose.SEATED)
        }
        // Blick zurück: Sie steht über dem Kessel und brüllt, Titelkarte.
        val landed0 = f.abs(ArenaLayout.AERIE_LANDING[0])
        scene.line(landed0.add(-20.0, 3.2, 9.0), landed0.add(-25.0, 2.8, 7.0), aerie.add(2.0, 6.0, 0.0), aerie.add(2.0, 7.5, 0.0), 44, 62.0, 54.0)
        d.play(NivarisPose.ROAR, 8)
        f.farSound("nivaris.roar", aerie, 6f, 0.7f)
        scene.once { Cine.title(it, "hha_arius.cine.aerie", "hha_arius.cine.aerie.sub", 70) }
        wait(30)
        for (p in f.players()) p.onLanding()
        scene.home(18)
        wait(18)
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
        f.arius ?: return@script
        f.nivaris ?: return@script
        val intro = CineScene(f) {}
        yieldAll(intro.play(script { yieldAll(rideIntro(intro)) }))
        yieldAll(ride())
        val scene = CineScene(f) {}
        yieldAll(scene.play(script { yieldAll(transformBody(scene)) }))
        yieldAll(vaultFight())
    }

    private fun rideIntro(scene: CineScene): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        f.setPhase(P.COLLAPSE)
        f.calls.reset()
        f.music("music.transform", 10)
        MusicCue.play(f, "ride_intro", 10)
        // 1) Er pfeift auf dem Pfeiler; sie hebt den Kopf.
        a.sheathed = false
        a.play(AriusPose.WHISTLE)
        val face = a.entityPos.add(0.0, 1.8, 0.0)
        val toward = flatDir(a.entityPos, d.entityPos)
        val side = Vec3d(-toward.z, 0.0, toward.x)
        scene.line(face.add(toward.multiply(2.4)).add(side.multiply(1.0)).add(0.0, -0.4, 0.0), face.add(toward.multiply(1.8)).add(side.multiply(0.8)).add(0.0, -0.3, 0.0),
            face, d.entityPos.add(0.0, 4.0, 0.0), 36, 52.0, 60.0)
        shown { f.say("fall.together", 50) }
        cue("resonance.chime", a.entityPos, 3f)
        wait(18)
        f.fx(FxKind.THREAD, a.entityPos, life = 40, e1 = a, e2 = d)
        d.play(NivarisPose.ROAR, 8)
        cue("nivaris.roar", d.entityPos, 5f, 0.9f)
        a.eyeGlow = 100
        d.eyeGlow = 100
        wait(18)
        // 2) Sie steigt auf und streicht tief über die Spieler; Arius springt ab und landet auf ihrem Nacken.
        d.flying = true
        d.play(NivarisPose.GLIDE)
        val mid = f.players().map { it.entityPos }.fold(Vec3d.ZERO) { acc, v -> acc.add(v) }.multiply(1.0 / f.players().size.coerceAtLeast(1))
        val low = Vec3d(mid.x, f.floorY + 3.5, mid.z)
        val from = d.entityPos
        scene.line(low.add(-12.0, 1.5, 10.0), low.add(-8.0, 2.5, 6.0), from.add(0.0, 4.0, 0.0), low, 34, 66.0, 80.0, lookAt = d, lookOffset = 3.0)
        cue("nivaris.wing", from, 5f, 0.8f)
        val seat = a.entityPos
        val swoop = DragonRail.path(from, f.facing(d).add(0.0, 0.35, 0.0), listOf(low), 18.0)
        val swoopLen = DragonRail.length(swoop)
        for (i in 1..34) {
            val k = i / 34.0
            val at = DragonRail.at(swoop, swoopLen * (k * (2 - k)))
            f.place(d, at)
            val next = DragonRail.at(swoop, swoopLen * (k * (2 - k)) + 3.0)
            if (next.squaredDistanceTo(at) > 0.01) f.face(d, next)
            if (i >= 18) {
                val kk = (i - 18) / 16.0
                f.place(a, seat.lerp(neckOf(d), kk).add(0.0, sin(kk * PI) * 3.0, 0.0))
            }
            yield(Unit)
        }
        f.calls.roomShake(low, 4f, 40f)
        shown {
            Vfx.shockwave(f.world, Vfx.Style.SOUL, Vec3d(low.x, f.floorY, low.z), 12f, 18, 1.6f)
            for (p in f.players()) f.fx(FxKind.THREAD, p.entityPos, life = 30, e1 = p, e2 = d)
        }
        scene.once { Cine.title(it, "hha_arius.cine.hold_on", "hha_arius.cine.hold_on.sub", 50) }
        wait(10)
        scene.home(12)
        wait(12)
        // Aufsteigen: Jeder bekommt seinen festen Dorn, Spieler 1 den ersten, die anderen die direkt dahinter.
        RideSeats.assign(f)
        for (p in f.players()) {
            p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 600, 0, false, false, true))
            p.startRiding(d, true, true)
        }
    }

    /** Nackenansatz der Drachin (wo Arius steht), Weltkoordinaten. */
    private fun neckOf(d: NivarisEntity): Vec3d = d.entityPos.add(f.facing(d).multiply(1.2)).add(0.0, NivarisShape.SPINE_UP + 1.0, 0.0)

    /** Der Ritt selbst (Flugbahn, Griffe, Quick-Time-Events) steht seit 1.8.1 in [DragonRide] / [RideRoute]. */
    private fun ride(): Script = script {
        val a = f.arius ?: return@script
        val d = f.nivaris ?: return@script
        yieldAll(DragonRide(f).play(::neckOf))
        RideSeats.unperch(f, a)
        // Landung am Feld: absteigen, sanft fallen, die Gefallenen stehen schon dort.
        for ((j, p) in f.players().withIndex()) {
            if (p.vehicle === d) p.stopRiding()
            if (p.squaredDistanceTo(d) > 40.0 * 40.0) {
                val s = f.abs(ArenaLayout.VAULT_LANDING[j % ArenaLayout.VAULT_LANDING.size])
                p.teleport(f.world, s.x, s.y + 1.0, s.z, emptySet(), 180f, 0f, false)
            }
            p.onLanding()
        }
        val vault = f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        f.place(a, vault.add(0.0, 7.0, 0.0))
        f.place(d, f.abs(ArenaLayout.VAULT_REST))
        d.flying = false
        d.play(NivarisPose.EXHAUSTED)
        for (p in f.players()) p.onLanding()
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
        val top = a.entityPos
        // 6a) Detail: er schlägt im Gewölbe auf, die Rüstung zerspringt. Tief, nah, langsam heran.
        val front = f.facing(a)
        val chest = vault.add(0.0, 1.1, 0.0)
        scene?.line(f.clampToArena(chest.add(front.multiply(3.4)).add(-0.8, -0.4, 0.0), 2.0), f.clampToArena(chest.add(front.multiply(2.5)).add(-0.6, -0.3, 0.0), 2.0),
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
        val fresh = newcomers("scene.transform")
        if (fresh.isNotEmpty()) shown { f.sceneText("transform", 70, fresh) }
        markHeard("scene.transform")
        wait(30)
        a.form = AriusForm.WHITE
        a.eyeGlow = 100
        f.updateBars()
        scene?.once { dev.henny.hha.logic.boss.Cine.title(it, "hha_arius.cine.heir", "hha_arius.cine.heir.sub", 70) }
        // Solvane ist der Drache: in seiner Hand verdichtet sich eine Klinge aus weißem Eislicht (V5).
        Entrance.storyFx(f, FxKind.BLADE_FORM, a.entityPos.add(0.0, 1.0, 0.0), life = 24, e1 = a)
        cue("core.form", vault, 2f, 1.5f)
        // 6b) Seitenschwenk: die Kamera weicht zurück und dreht um ihn, Nivaris richtet sich hinter ihm auf.
        val baseAng = Math.toDegrees(kotlin.math.atan2(front.z, front.x))
        scene?.orbit(vault, 3.2, 1.0, baseAng + 18.0, baseAng + 62.0, 40, 52.0, 66.0, lookAt = a, lookOffset = 1.3, radiusTo = 9.5, heightTo = 3.2)
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
        wait(18)
        a.bladeState = AriusBlade.ICE
        wait(12)
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
            val turn = when {
                f.dragonDowned -> f.moves.turnWhiteSolo()
                f.ariusLeads -> f.moves.turnWhite()
                else -> f.dragon.turnVault()
            }
            val leader = Runner(turn)
            var support: Runner? = null
            var supportAt = f.now + 40
            while (f.ariusHp > 0f && leader.step()) {
                if (!f.dragonDowned && f.dragonHp <= 0f) downDragon()
                if (!f.dragonDowned) {
                    if ((support == null || support.done) && f.now >= supportAt) {
                        support = Runner(if (f.ariusLeads) f.dragon.support() else f.moves.support())
                        supportAt = f.now + 120
                    }
                    support?.step()
                }
                checkTaktBreak()
                yield(Unit)
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
                d.mode = NivarisMode.COIL
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

    /**
     * Der Letzte Horizont: Er springt an den Rand der Gipfelarena, über ihm wächst die Lichtklinge; drei Bahnen quer über
     * die Arena, dazwischen zwei goldene Lücken. Wer in einer Bahn steht, wird zerschnitten. Danach steht er lange offen.
     */
    fun lastHorizon(a: AriusEntity, cross: Boolean = false): Script = script {
        val c = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
        val r = f.arenaRadius
        val t = f.target()
        var axis = t?.let { flatDir(c, it.entityPos) }?.let { Vec3d(-it.z, 0.0, it.x) } ?: Vec3d(1.0, 0.0, 0.0)
        var normal = Vec3d(-axis.z, 0.0, axis.x)
        val edge = f.clampToArena(c.subtract(normal.multiply(r)), 1.0)
        val from = a.entityPos
        a.play(AriusPose.LAUNCH, 6)
        f.ambient("arius.wind_step", from, 2.6f, 0.7f)
        for (i in 1..12) {
            f.place(a, from.lerp(edge, i / 12.0).add(0.0, sin(i / 12.0 * PI) * 3.0, 0.0))
            yield(Unit)
        }
        f.face(a, c)
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.last_horizon", "hha_arius.feat.last_horizon.sub", 50)
        // Seit 1.11.0: In der letzten Phase kommt er über Kreuz, der zweite Schnitt quer zum ersten.
        for (swing in 0 until (if (cross) 2 else 1)) {
        a.play(AriusPose.CUT_CHARGE)
        val ticks = if (swing == 0) 56 else 40
        f.fxAll(FxKind.GIANT_BLADE, a.entityPos, a = 18f, b = ticks.toFloat(), life = ticks + 20, e1 = a)
        f.ambient("cut.charge", a.entityPos, 4f, 0.7f)
        f.ambient("cut.reverse_wind", a.entityPos, 3f, 0.9f)
        val group = f.calls.boss(pace = Telegraph.Pace.ROOM)
        val lanes = listOf(-0.62, 0.0, 0.62).map { o ->
            val m = c.add(axis.multiply(o * r))
            group.line(f.clampToArena(m.subtract(normal.multiply(r)), 0.3), f.clampToArena(m.add(normal.multiply(r)), 0.3), 3.6f, ticks)
        }
        for (o in listOf(-0.31, 0.31)) {
            val m = c.add(axis.multiply(o * r))
            group.safeLine(f.clampToArena(m.subtract(normal.multiply(r - 1.0)), 0.5), f.clampToArena(m.add(normal.multiply(r - 1.0)), 0.5), 3.0f, ticks)
        }
        wait(lanes.first().ticks - 6)
        a.play(AriusPose.CUT_RELEASE, 6)
        wait(6)
        f.ambient("cut.strike", c, 5f, 0.8f)
        f.ambient("arius.null_cut", c, 4f, 0.7f)
        f.fxAll(FxKind.FLASH, c, a = 0.4f, life = 8)
        f.fxAll(FxKind.TIME_SLOW, c, a = 0.7f, life = 16)
        f.calls.roomShake(c, 6f, 50f)
        val hit = lanes.flatMap { it.hits(f.players()) }.distinct()
        for (p in hit) if (!group.isSafe(p)) f.hurt(p, 18f, Damage.Kind.NULL_CUT)
        for (o in listOf(-0.62, 0.0, 0.62)) {
            val m = c.add(axis.multiply(o * r))
            f.fx(FxKind.SEAM, m.subtract(normal.multiply(r)).add(0.0, 0.2, 0.0), m.add(normal.multiply(r)).add(0.0, 0.2, 0.0), a = 6f, life = 24)
            Vfx.crescent(f.world, Vfx.Style.HEAVEN, m.add(0.0, 1.2, 0.0), normal, (r * 2).toFloat(), 3f, 10)
        }
        val turned = axis
        axis = normal
        normal = Vec3d(-turned.x, 0.0, -turned.z)
        if (cross && swing == 0) wait(10)
        }
        a.play(AriusPose.STAGGER)
        f.openArius(60)
        wait(60)
    }

    /**
     * Auftakt des Finales (V5): Solvane kehrt zurück. Auf dem Gipfel windet sie sich hoch oben um die Eisspitze (wie
     * beim Drachen-Reveal), löst sich dort oben in Licht auf und stürzt als leuchtender Strom herab in Arius' Scheide;
     * die temporäre Eisklinge zerfällt. Endzustand: kein Drache, [AriusBlade.HELD], Solvane in der Scheide ([settleReturn]).
     */
    private fun bladeReturn(a: AriusEntity): Script = script {
        val d = f.nivaris
        if (d == null || f.training != 0) {
            settleReturn(a)
            return@script
        }
        val scene = CineScene(f) { settleReturn(a) }
        yieldAll(scene.play(script {
            if (f.onSummit) {
                f.atmos(dev.henny.hha_arius.net.AtmosMode.REVEAL, 20)
                f.place(d, f.arenaCenter.add(0.0, 2.0, 6.0))
                d.mode = NivarisMode.COIL
                d.uncoil = 0f
                d.flying = false
                d.showNow()
                d.resonance = true
                d.eyeGlow = 100
                d.play(NivarisPose.IDLE)
                val spireHead = f.abs(EntrancePath.HEAD_RAISED)
                val spireMid = f.abs(EntrancePath.coil(0.62))
                f.face(a, spireHead)
                a.sheathed = false
                a.play(AriusPose.IDLE)
                a.eyeGlow = 100
                val toSpire = flatDir(a.entityPos, spireHead)
                val side = Vec3d(-toSpire.z, 0.0, toSpire.x)
                val eye = a.entityPos.add(0.0, 1.7, 0.0)
                // A) Tief hinter Arius' Schulter steil hinauf zur Eisspitze, um die Solvane gewunden thront (42 Ticks).
                scene.line(
                    eye.add(toSpire.multiply(-6.2)).add(side.multiply(2.4)).add(0.0, -0.2, 0.0),
                    eye.add(toSpire.multiply(-8.6)).add(side.multiply(3.2)).add(0.0, 1.2, 0.0),
                    a.entityPos.lerp(spireMid, 0.55), spireHead.add(0.0, -5.0, 0.0), 42, 66.0, 58.0,
                )
                wait(8)
                shown { f.say("dragon.return", 50) }
                d.play(NivarisPose.ROAR, 10)
                Entrance.storyFx(f, FxKind.THREAD, a.entityPos, life = 34 + RETURN_PEAK, e1 = a, e2 = d)
                cue("resonance.chime", a.entityPos, 3.5f, 0.8f)
                wait(12)
                // Von der untersten Windung an der Eisspitze bis hinauf zum Kopf löst sie sich in Licht auf.
                d.fadeOut(22 + RETURN_PEAK - 1)
                cue("nivaris.inhale", spireHead, 4f, 0.65f)
                wait(22)
                // B) Totale auf die Eisspitze: Solvane zerfällt oben im Licht und verschwindet am Höhepunkt.
                val spireCam0 = f.abs(Vec3d(ArenaLayout.SPIRE.x - 16.0, FarPlan.SHOULDER_Y + 12.0, ArenaLayout.SPIRE.z - 42.0))
                val spireCam1 = f.abs(Vec3d(ArenaLayout.SPIRE.x - 11.0, FarPlan.SHOULDER_Y + 18.0, ArenaLayout.SPIRE.z - 36.0))
                scene.line(spireCam0, spireCam1, spireMid.lerp(spireHead, 0.45), spireHead.add(0.0, -3.0, 0.0), RETURN_PEAK + 12, 62.0, 56.0)
                Entrance.storyFx(f, FxKind.DRAGON_DISSOLVE, spireHead, spireMid, a = 8f, life = RETURN_PEAK + 34)
                Entrance.storyFx(f, FxKind.BLIZZARD, spireMid, a = 14f, life = 36)
                cue("transform.pulse", spireHead, 5f, 0.7f)
                cue("seal.blizzard", spireHead, 3f, 1.3f)
                wait(RETURN_PEAK)
                f.dismissNivaris()
                wait(10)
                // C) Der Strom stürzt von der Eisspitze herab in Arius' Scheide, die Eisklinge zerspringt.
                val chest = a.entityPos.add(0.0, 1.4, 0.0)
                val travel = RETURN_TRAVEL + 10
                scene.line(
                    chest.add(side.multiply(5.4)).add(toSpire.multiply(-3.6)).add(0.0, 1.8, 0.0),
                    chest.add(side.multiply(3.2)).add(toSpire.multiply(-1.6)).add(0.0, 0.6, 0.0),
                    spireHead.lerp(chest, 0.48), chest.add(toSpire.multiply(-0.2)), travel + 26, 66.0, 50.0,
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
        split(animate = false)
        yieldAll(waitForFar())
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

    private fun prepareVault() {
        split(animate = false)
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
        prepareVault()
        f.say("echo", 60)
        wait(60)
        yieldAll(vaultFight())
    }

    fun trainingFinale(): Script = script {
        yieldAll(waitForFar())
        prepareVault()
        f.dragonDowned = true
        f.say("echo", 60)
        wait(60)
        yieldAll(finale())
    }

    private companion object {
        /** Ticks von der Auflösung des Drachen bis zu seinem Verschwinden (Höhepunkt des Lichts, wie DragonDissolve.PEAK). */
        const val RETURN_PEAK = 14

        /** Flugzeit des Lichtstroms vom Drachen in die Scheide. */
        const val RETURN_TRAVEL = 28
    }

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }
}

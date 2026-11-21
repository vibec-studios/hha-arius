package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.AriusEntities
import dev.henny.hha_arius.net.AriusNet
import dev.henny.hha_arius.net.FxKind
import dev.henny.hha_arius.net.SilenceS2C
import net.minecraft.entity.SpawnReason
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Das neue Gipfel-Finale (seit 1.10.0, Crix: „die letzte Phase auf dem Gipfel ist zu langweilig“). Läuft innerhalb von
 * [AriusMoves.turnFinale], abhängig vom Leben:
 *
 * 1. **Klingenabsichten** (ab 75 %): Zwei Nachbilder seiner Absicht – durchscheinend, aus Eislicht – lösen sich von ihm
 *    und kämpfen mit: abwechselnd stürmen sie aus der Flanke mit einem angesagten Hieb. Drei Treffer zerschlagen eines.
 * 2. (bei 50 % wie bisher: Zweiter Atem und Letzter Horizont – aus [Scenes.finale].)
 * 3. **Stille** (bei [FLOOR] des Lebens; tiefer kann man ihn vorher nicht schlagen): Der Sturm hört auf, die Musik bricht
 *    ab, die Welt wird schwarz-weiß. Er steckt die Klinge ein. Ein Duell aus einzelnen Iai-Zügen: Nur wenn die Klinge
 *    golden aufblitzt, kreuzt man sie – **Schleichen, Blocken/Solvane-Konter oder ein Schlag** genau im Blitz. Jedes
 *    gelungene Kreuzen lässt ihn zurücktaumeln; verfehlt man, trifft der Zug.
 * 4. **Letzter Zusammenprall**: Er stürmt auf alle zugleich, die Lichtklinge über ihm. Kreuzt mindestens die Hälfte der
 *    Gruppe den Schlag, zerbricht seine Haltung – Ende. Sonst noch zwei Duelle und ein neuer Anlauf (nach dem dritten
 *    Anlauf bricht seine Haltung trotzdem: kein Festhängen).
 *
 * Mehrspieler: Duelle rotieren durch die Kämpfenden; jeder in der Nähe darf mitkreuzen. Niemand ist gesperrt.
 */
class Summit(private val f: EirholmFight) {

    private val rnd = java.util.Random()

    /** 0 Duell, 1 Klingenabsichten, 2 Stille, 3 frei (er darf fallen). */
    var stage = 0
        private set

    private val echoes = ArrayList<Echo>()

    private class Echo(val e: AriusEntity) {
        var hits = 0
        var busy = false
    }

    // --- Kreuzen -------------------------------------------------------------------------------------------------------

    /** Offenes Kreuz-Fenster: bis zu diesem Tick (inklusive). */
    private var clashFrom = Long.MAX_VALUE
    private var clashUntil = Long.MIN_VALUE
    private val clashed = HashSet<UUID>()
    private val sneakSince = HashMap<UUID, Long>()
    private val wasSneaking = HashMap<UUID, Boolean>()

    private val active: Boolean get() = f.phase == EirholmFight.Phase.FINALE

    /** Schadensgrenze: vor der Stille nicht unter [FLOOR], in der Stille gar kein Schaden (nur Kreuzen). */
    fun limit(dmg: Float): Float {
        if (!active || stage >= 3) return dmg
        if (stage == 2) return 0f
        val floor = f.ariusMax * FLOOR
        return dmg.coerceAtMost((f.ariusHp - floor).coerceAtLeast(0f))
    }

    /** Treffer an Arius: in der Stille zählt er als Kreuzen (im Fenster) und macht nie Schaden. */
    fun onAriusHit(player: ServerPlayerEntity, projectile: Boolean): Boolean {
        if (!active || stage != 2) return false
        if (!projectile && windowOpen()) clashed.add(player.uuid)
        return true
    }

    private fun windowOpen(): Boolean = f.now in clashFrom..clashUntil

    /** Zählt die Kreuz-Eingaben dieses Ticks: Schleichen gedrückt, Gegenstand gehoben, Solvane-Konter, Handschuh-Parade. */
    private fun watchInputs() {
        for (p in f.players()) {
            val sneak = p.isSneaking
            val was = wasSneaking.put(p.uuid, sneak) ?: false
            if (sneak && !was) sneakSince[p.uuid] = f.now
            if (!windowOpen()) continue
            val pressed = (sneakSince[p.uuid] ?: Long.MIN_VALUE) >= clashFrom
            val raised = p.isUsingItem && p.itemUseTime <= 12 && p.activeItem.contains(net.minecraft.component.DataComponentTypes.BLOCKS_ATTACKS)
            val parry = dev.henny.hha_arius.gear.SolvaneCounter.active(p) || dev.henny.hha_arius.gear.art.GauntletCombat.parrying(p)
            if (pressed || raised || parry) clashed.add(p.uuid)
        }
    }

    // --- Ablauf --------------------------------------------------------------------------------------------------------

    /** Nächster besonderer Abschnitt (aus [AriusMoves.turnFinale]), sonst null. */
    fun next(): Script? {
        if (!active || f.ariusMax <= 0f) return null
        val frac = f.ariusHp / f.ariusMax
        if (stage == 0 && frac <= 0.75f) {
            stage = 1
            return summonEchoes()
        }
        if (stage == 1 && f.ariusHp <= f.ariusMax * FLOOR + 0.5f) {
            stage = 2
            return silence()
        }
        return null
    }

    // --- 1) Klingenabsichten -------------------------------------------------------------------------------------------

    private fun summonEchoes(): Script = script {
        val a = f.arius ?: return@script
        f.calls.reset()
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.blade_intent", "hha_arius.feat.blade_intent.sub", 45)
        a.play(AriusPose.CORE_RAISE)
        f.ambient("lead.switch", a.entityPos, 3f, 0.6f)
        f.ambient("transform.pulse", a.entityPos, 3f, 1.3f)
        f.fxAll(FxKind.FLASH, a.entityPos, a = 0.2f, life = 6)
        wait(10)
        for (side in listOf(1.0, -1.0)) {
            val e = AriusEntities.ARIUS.create(f.world, SpawnReason.EVENT) ?: continue
            val at = f.clampToArena(a.entityPos.add(Vec3d(-f.facing(a).z, 0.0, f.facing(a).x).multiply(2.5 * side)), 1.5)
            e.refreshPositionAndAngles(at.x, f.floorY, at.z, a.yaw, 0f)
            e.form = AriusForm.ECHO
            e.bladeState = AriusBlade.HELD
            e.eyeGlow = 100
            val echo = Echo(e)
            e.damageHandler = BossDamageHandler { _, source, _ -> onEchoHit(echo, source); if (echo.e.isRemoved) 0f else 1f }
            f.world.spawnEntity(e)
            echoes.add(echo)
            f.fx(FxKind.DASH_STREAK, a.entityPos, at, life = 10)
            Vfx.flare(f.world, Vfx.Style.HEAVEN, at.add(0.0, 1.2, 0.0), 1.4f, 8)
        }
        f.ambient("iai.glint", a.entityPos, 3f, 0.8f)
        a.play(AriusPose.IDLE)
        lastEcho = f.now
        f.task(script {
            while (stage == 1 && active && echoes.isNotEmpty() && !f.ended) {
                tickEchoes()
                yield(Unit)
            }
        })
        wait(10)
    }

    private var lastEcho = 0L
    private var echoTurn = 0

    private fun onEchoHit(echo: Echo, source: net.minecraft.entity.damage.DamageSource) {
        val p = source.attacker as? ServerPlayerEntity ?: return
        if (!f.isParticipant(p) || echo.e.isRemoved) return
        echo.hits++
        f.ambient("transform.shatter", echo.e.entityPos, 1.2f, 1.6f + echo.hits * 0.1f)
        Vfx.flare(f.world, Vfx.Style.HEAVEN, echo.e.entityPos.add(0.0, 1.2, 0.0), 0.8f, 4)
        if (echo.hits >= 3) shatter(echo)
    }

    private fun shatter(echo: Echo) {
        val at = echo.e.entityPos.add(0.0, 1.0, 0.0)
        f.ambient("transform.shatter", at, 2.4f, 0.9f)
        f.ambient("arius.parry", at, 1.6f, 1.4f)
        Vfx.nova(f.world, Vfx.Style.HEAVEN, at, 2.2f, 10)
        f.world.spawnParticles(net.minecraft.particle.ParticleTypes.SNOWFLAKE, at.x, at.y, at.z, 30, 0.4, 0.7, 0.4, 0.2)
        echo.e.discard()
        echoes.remove(echo)
        if (echoes.isEmpty()) f.hint("hha_arius.hint.blade_intent_gone", Formatting.GOLD)
    }

    /** Ein Nachbild greift an, wenn es dran ist (abwechselnd, ≈ alle 3 s), sonst kreist es. */
    private fun tickEchoes() {
        echoes.removeIf { it.e.isRemoved }
        if (echoes.isEmpty() || echoes.any { it.busy }) return
        if (f.now - lastEcho < ECHO_EVERY) return
        val echo = echoes[echoTurn++ % echoes.size]
        lastEcho = f.now
        echo.busy = true
        f.task(echoStrike(echo))
    }

    private fun echoStrike(echo: Echo): Script = script {
        try {
            val e = echo.e
            val pool = f.players().filter { !Hold.isHeld(it) && !Hold.inGrace(it) }
            val t = (if (pool.isEmpty()) null else pool[rnd.nextInt(pool.size)]) ?: return@script
            val a = f.arius
            // Aus der Flanke des Ziels (gegenüber Arius) heran.
            val ref = a?.entityPos ?: f.arenaCenter
            val out = flatDir(ref, t.entityPos)
            val side = Vec3d(-out.z, 0.0, out.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0)
            val stand = f.clampToArena(t.entityPos.add(side.multiply(4.0)).add(out.multiply(1.5)), 1.5).let { Vec3d(it.x, f.floorY, it.z) }
            val from = e.entityPos
            e.play(AriusPose.SIDESTEP)
            for (i in 1..6) {
                if (e.isRemoved) return@script
                f.place(e, from.lerp(stand, i / 6.0))
                f.face(e, t.entityPos)
                yield(Unit)
            }
            f.fx(FxKind.DASH_STREAK, from, stand, life = 8)
            if (e.isRemoved || !f.canSequence(t)) return@script
            f.face(e, t.entityPos)
            val dir = f.facing(e)
            val cone = f.calls.cone(stand, dir, 4.4f, 60f, 14, Telegraph.Pace.FOLLOW_UP)
            e.play(if (rnd.nextBoolean()) AriusPose.COMBO_1 else AriusPose.COMBO_2, cone.ticks)
            f.ambient("iai.glint", stand, 1.4f, 1.6f)
            wait(cone.ticks)
            if (e.isRemoved) return@script
            f.ambient("arius.slash", stand, 2f, 1.3f)
            f.fx(FxKind.SLASH_ARC, stand.add(0.0, 1.1, 0.0), dir, a = 4.4f, b = 0.2f, life = 10)
            for (p in cone.hits(f.players())) f.hurt(p, 7f, Damage.Kind.SOLVANE, e)
            wait(10)
            // Zurück an seine Seite.
            val home = a?.let { f.clampToArena(it.entityPos.add(Vec3d(-f.facing(it).z, 0.0, f.facing(it).x).multiply(if (echoes.indexOf(echo) == 0) 3.0 else -3.0)), 1.5) } ?: stand
            val back = e.entityPos
            e.play(AriusPose.IDLE)
            for (i in 1..8) {
                if (e.isRemoved) return@script
                f.place(e, back.lerp(Vec3d(home.x, f.floorY, home.z), i / 8.0))
                yield(Unit)
            }
        } finally {
            echo.busy = false
        }
    }

    private fun dismissEchoes() {
        for (e in echoes.toList()) shatter(e)
        echoes.clear()
    }

    /** Kampfende: Nachbilder entfernen, Bild zurück. */
    fun cleanup() {
        for (e in echoes) e.e.discard()
        echoes.clear()
        if (stage == 2) for (p in f.audience()) AriusNet.send(p, SilenceS2C(0f, 10))
    }

    // --- 3) Stille und 4) Letzter Zusammenprall -------------------------------------------------------------------------

    private fun silence(): Script = script {
        val a = f.arius ?: return@script
        f.calls.reset()
        dismissEchoes()
        a.play(AriusPose.IAI_STANCE)
        a.sheathed = true
        f.music(null, 30)
        MusicCue.play(f, "silence", 30)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.CLEAR, 50)
        for (p in f.audience()) {
            AriusNet.send(p, SilenceS2C(1f, 40))
            Cine.title(listOf(p), "hha_arius.feat.silence", "hha_arius.feat.silence.sub", 70)
        }
        f.say("finale.silence", 60)
        f.ambient("iai.glint", a.entityPos, 3f, 0.5f)
        try {
            wait(50)
            var finals = 0
            var duels = 2 + f.players().size.coerceAtMost(3)
            var turn = 0
            while (f.players().isNotEmpty()) {
                repeat(duels) {
                    val list = f.players()
                    if (list.isEmpty()) return@repeat
                    val t = list[turn++ % list.size]
                    yieldAll(duel(t))
                    wait(12)
                }
                finals++
                if (yieldAllBool(lastClash(finals >= 3))) break
                duels = 2
                f.say("finale.again", 50)
                wait(30)
            }
        } finally {
            clashFrom = Long.MAX_VALUE
            clashUntil = Long.MIN_VALUE
            for (p in f.audience()) AriusNet.send(p, SilenceS2C(0f, 30))
            f.atmos(dev.henny.hha_arius.net.AtmosMode.WHITE, 40)
        }
    }

    private var lastResult = false

    /** Hilfsweg, weil eine Sequenz keinen Wert zurückgeben kann: [lastClash] setzt [lastResult]. */
    private suspend fun SequenceScope<Unit>.yieldAllBool(s: Script): Boolean {
        yieldAll(s)
        return lastResult
    }

    /** Ein Iai-Duell gegen [t]: Ansage als Bahn, Blitz = Kreuz-Fenster, dann der Zug. */
    private fun duel(t: ServerPlayerEntity): Script = script {
        val a = f.arius ?: return@script
        if (!f.canSequence(t)) return@script
        // Er geht auf Abstand (Windschritt), Klinge in der Scheide.
        val out = flatDir(t.entityPos, a.entityPos).let { if (it.lengthSquared() < 0.5) Vec3d(1.0, 0.0, 0.0) else it }
        val stand = f.clampToArena(t.entityPos.add(out.multiply(7.5)), 1.5).let { Vec3d(it.x, f.floorY, it.z) }
        val from = a.entityPos
        a.sheathed = true
        a.play(AriusPose.SIDESTEP)
        for (i in 1..5) {
            f.place(a, from.lerp(stand, i / 5.0))
            f.face(a, t.entityPos)
            yield(Unit)
        }
        a.play(AriusPose.IAI_STANCE)
        f.face(a, t.entityPos)
        val dir = flatDir(stand, t.entityPos)
        val end = f.clampToArena(t.entityPos.add(dir.multiply(3.0)), 1.0).let { Vec3d(it.x, f.floorY, it.z) }
        val windup = 22 + rnd.nextInt(18)
        val line = f.calls.line(stand, end, 1.6f, windup, Telegraph.Pace.HEAVY, style = Vfx.Style.GOLD)
        f.ambient("iai.glint", stand, 1.2f, 0.6f)
        val strikeAt = f.now + line.ticks
        clashed.clear()
        clashFrom = strikeAt - WINDOW_BEFORE
        clashUntil = strikeAt + WINDOW_AFTER
        var flashed = false
        while (f.now < strikeAt) {
            watchInputs()
            if (!flashed && f.now >= clashFrom) {
                flashed = true
                // Der Blitz: goldener Schimmer an der Klinge, ein heller Ton.
                f.ambient("iai.glint", a.entityPos, 3f, 2.0f)
                Vfx.flare(f.world, Vfx.Style.GOLD, a.entityPos.add(0.0, 1.5, 0.0), 1.8f, WINDOW_BEFORE + 2)
                a.sheathed = false
                a.play(AriusPose.IAI_DRAW, WINDOW_BEFORE)
            }
            // Im Fenster stürmt er heran (kommt genau beim Schlag an).
            if (flashed) {
                val k = 1.0 - (strikeAt - f.now) / WINDOW_BEFORE.toDouble()
                f.place(a, stand.lerp(t.entityPos.subtract(dir.multiply(1.2)).let { Vec3d(it.x, f.floorY, it.z) }, k.coerceIn(0.0, 1.0)))
            }
            yield(Unit)
        }
        repeat(WINDOW_AFTER + 1) { watchInputs(); if (it < WINDOW_AFTER) yield(Unit) }
        val near = f.players().filter { it.uuid in clashed && it.squaredDistanceTo(a) < 6.0 * 6.0 }
        clashFrom = Long.MAX_VALUE
        clashUntil = Long.MIN_VALUE
        if (near.isNotEmpty()) {
            clash(a, near.first())
        } else {
            // Verfehlt: der Zug geht durch.
            f.ambient("iai.draw", a.entityPos, 3f)
            for (i in 1..3) {
                f.place(a, a.entityPos.lerp(end, i / 3.0))
                yield(Unit)
            }
            f.fx(FxKind.SEAM, stand.add(0.0, 1.1, 0.0), end.add(0.0, 1.1, 0.0), a = 1.2f, life = 14)
            for (p in line.hits(f.players())) f.hurt(p, capped(p, 10f), Damage.Kind.NULL_CUT)
            if (t.isAlive) t.sendMessage(Text.translatable("hha_arius.msg.clash_miss").formatted(Formatting.GRAY), true)
        }
        a.sheathed = true
        a.play(AriusPose.IAI_STANCE)
    }

    /** Die Klingen kreuzen sich: Funken, Zeitlupe, er taumelt zurück. */
    private fun clash(a: AriusEntity, by: ServerPlayerEntity) {
        val at = a.entityPos.lerp(by.entityPos, 0.5).add(0.0, 1.3, 0.0)
        f.ambient("arius.parry", at, 3f, 0.9f)
        f.ambient("solvane.parry", at, 2.4f, 0.7f)
        f.ambient("cut.strike", at, 1.6f, 1.8f)
        f.fxAll(FxKind.FLASH, at, a = 0.35f, life = 5)
        f.fxAll(FxKind.TIME_SLOW, at, a = 0.5f, life = 10)
        Vfx.flare(f.world, Vfx.Style.GOLD, at, 2.4f, 8)
        f.world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, at.x, at.y, at.z, 24, 0.3, 0.3, 0.3, 0.25)
        for (p in f.audience()) AriusNet.send(p, dev.henny.hha_arius.net.BeatS2C(a.id, at.x, at.y, at.z, 3.5f, if (p === by) 1.4f else 0f, 0f))
        f.calls.hitShake(at, 2.5f, 10f)
        val back = flatDir(by.entityPos, a.entityPos)
        f.place(a, f.clampToArena(a.entityPos.add(back.multiply(2.2)), 1.0).let { Vec3d(it.x, f.floorY, it.z) })
        a.play(AriusPose.STAGGER)
        Fx.launchPlayer(by, back.multiply(-0.35).add(0.0, 0.15, 0.0))
        by.sendMessage(Text.translatable("hha_arius.msg.clash").formatted(Formatting.GOLD), true)
    }

    /**
     * Letzter Zusammenprall: er springt an den Rand, die Lichtklinge wächst, dann stürmt er quer über den Gipfel auf
     * alle. Gelingt das Kreuzen mindestens der Hälfte (oder [mercy]), bricht seine Haltung und der Kampf endet.
     */
    private fun lastClash(mercy: Boolean): Script = script {
        val a = f.arius ?: return@script
        val c = f.arenaCenter.let { Vec3d(it.x, f.floorY, it.z) }
        val r = f.arenaRadius
        val t = f.target()
        val toT = t?.let { flatDir(c, it.entityPos) } ?: Vec3d(0.0, 0.0, 1.0)
        val edge = f.clampToArena(c.subtract(toT.multiply(r)), 1.5)
        val from = a.entityPos
        a.play(AriusPose.LAUNCH, 6)
        f.ambient("arius.wind_step", from, 2.6f, 0.7f)
        for (i in 1..12) {
            f.place(a, from.lerp(edge, i / 12.0).add(0.0, sin(i / 12.0 * PI) * 3.0, 0.0))
            yield(Unit)
        }
        f.place(a, edge)
        f.face(a, c)
        a.sheathed = false
        a.play(AriusPose.CUT_CHARGE)
        for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.last_clash", "hha_arius.feat.last_clash.sub", 50)
        val charge = 56
        f.fxAll(FxKind.GIANT_BLADE, a.entityPos, a = 14f, b = charge.toFloat(), life = charge + 16, e1 = a)
        f.ambient("cut.charge", a.entityPos, 4f, 0.6f)
        val group = f.calls.boss(pace = Telegraph.Pace.ROOM, style = Vfx.Style.GOLD)
        val sweep = group.ring(c, r.toFloat() + 1f, charge)
        val strikeAt = f.now + sweep.ticks
        clashed.clear()
        clashFrom = strikeAt - WINDOW_BEFORE - 2
        clashUntil = strikeAt + WINDOW_AFTER
        var flashed = false
        while (f.now < strikeAt) {
            watchInputs()
            if (!flashed && f.now >= clashFrom) {
                flashed = true
                f.ambient("iai.glint", c, 4f, 2.0f)
                f.fxAll(FxKind.FLASH, a.entityPos, a = 0.15f, life = 3)
                Vfx.flare(f.world, Vfx.Style.GOLD, a.entityPos.add(0.0, 2.0, 0.0), 3f, 10)
                a.play(AriusPose.CUT_RELEASE, WINDOW_BEFORE + 2)
            }
            yield(Unit)
        }
        repeat(WINDOW_AFTER + 1) { watchInputs(); if (it < WINDOW_AFTER) yield(Unit) }
        clashFrom = Long.MAX_VALUE
        clashUntil = Long.MIN_VALUE
        val everyone = f.players()
        val ok = everyone.filter { it.uuid in clashed }
        // Er stürmt quer über den Gipfel.
        val far = f.clampToArena(c.add(toT.multiply(r)), 1.5)
        f.fx(FxKind.DASH_STREAK, edge, far, life = 14)
        f.ambient("cut.strike", c, 5f, 0.7f)
        f.ambient("arius.null_cut", c, 4f, 0.6f)
        f.calls.roomShake(c, 6f, 50f)
        for (p in everyone) if (p !in ok) f.hurt(p, capped(p, 12f), Damage.Kind.NULL_CUT)
        val win = everyone.isNotEmpty() && (ok.size * 2 >= everyone.size || mercy)
        lastResult = win
        if (win) {
            val by = ok.firstOrNull() ?: everyone.first()
            val mid = c.add(0.0, 1.4, 0.0)
            f.place(a, Vec3d(c.x, f.floorY, c.z).add(toT.multiply(-1.5)))
            f.face(a, by.entityPos)
            f.ambient("arius.parry", mid, 4f, 0.6f)
            f.ambient("transform.shatter", mid, 4f, 0.7f)
            f.ambient("solvane.parry", mid, 3f, 0.5f)
            f.fxAll(FxKind.FLASH, mid, a = 0.6f, life = 14)
            f.fxAll(FxKind.TIME_SLOW, mid, a = 1f, life = 40)
            Vfx.shockwave(f.world, Vfx.Style.GOLD, Vec3d(c.x, f.floorY, c.z), r.toFloat(), 26, 2.4f)
            for (p in f.audience()) AriusNet.send(p, dev.henny.hha_arius.net.BeatS2C(a.id, mid.x, mid.y, mid.z, 6f, 1.8f, 0.6f))
            for (p in ok) p.sendMessage(Text.translatable("hha_arius.msg.clash_final").formatted(Formatting.GOLD), true)
            a.play(AriusPose.KNEEL)
            stage = 3
            wait(30)
            f.ariusHp = 0f
        } else {
            a.play(AriusPose.STAGGER)
            f.place(a, far)
            wait(20)
        }
    }

    /** Aus mehr als halbem Leben nie tödlich (Fairness des Duells). */
    private fun capped(p: ServerPlayerEntity, base: Float): Float {
        if (p.health < p.maxHealth * 0.5f) return base
        val room = (p.health + p.absorptionAmount - 2f) / BossConfig.damageFor(f.scaling.count)
        return base.coerceAtMost(room.coerceAtLeast(0f))
    }

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }

    @Suppress("unused")
    private fun dist(a: Vec3d, b: Vec3d) = sqrt(a.squaredDistanceTo(b))

    companion object {
        /** Bis hierhin kann man ihn vor der Stille schlagen (Anteil des Lebens). */
        const val FLOOR = 0.12f

        /** Kreuz-Fenster: so viele Ticks vor dem Schlag öffnet es (Blitz), so viele danach bleibt es offen. */
        const val WINDOW_BEFORE = 7
        const val WINDOW_AFTER = 2

        /** Abstand der Angriffe der Nachbilder. */
        const val ECHO_EVERY = 60L
    }
}

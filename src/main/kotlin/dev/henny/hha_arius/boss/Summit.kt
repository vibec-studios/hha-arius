package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.AriusEntities
import dev.henny.hha_arius.net.FxKind
import net.minecraft.entity.SpawnReason
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d

/** Gipfel-Finale: Lebensmarken, Klingenabsichten, die vier Wellen und zum Schluss „Der Sturm bricht“. */
class Summit(private val f: EirholmFight) {

    private val rnd = java.util.Random()

    /** 0 Duell, 1 Klingenabsichten und Wellen, 2 „Der Sturm bricht“ (unverwundbar), 3 er kniet offen. */
    var stage = 0
        private set

    /** Nächste noch nicht gespielte Welle (Index in [WAVES]). */
    private var wave = 0

    private val echoes = ArrayList<Echo>()
    private var echoTask = false

    /** Wie oft „Der Sturm bricht“ schon lief, und wann sein offenes Fenster endet. */
    private var standRound = 0
    private var standEnds = 0L
    private var nextCutTarget = 0

    private class Echo(val e: AriusEntity) {
        var hits = 0
        var busy = false
    }

    private val active: Boolean get() = f.phase == EirholmFight.Phase.FINALE

    /** Aktuelle Untergrenze: die nächste Welle, danach [FLOOR]. */
    private fun floorFrac(): Float = WAVES.getOrNull(wave) ?: FLOOR

    /** Schadensgrenze: bis zur nächsten Welle bzw. bis zum letzten Ausfall. */
    fun limit(dmg: Float): Float {
        if (!active || stage >= 3) return dmg
        if (stage == 2) return 0f
        val floor = f.ariusMax * (if (stage == 0) maxOf(floorFrac(), 0.75f) else floorFrac())
        return dmg.coerceAtMost((f.ariusHp - floor).coerceAtLeast(0f))
    }

    /** Skip the earlier waves in the client game test. */
    fun debugJumpToLastStand() {
        stage = 1
        wave = WAVES.size
        f.ariusHp = f.ariusMax * FLOOR
    }

    // --- Ablauf --------------------------------------------------------------------------------------------------------

    /** Nächster besonderer Abschnitt (aus [AriusMoves.turnFinale]), sonst null. */
    fun next(): Script? {
        if (!active || f.ariusMax <= 0f) return null
        val frac = f.ariusHp / f.ariusMax
        if (stage == 0 && frac <= 0.75f + 0.001f) {
            stage = 1
            return summonEchoes(2)
        }
        if (stage == 3 && f.now >= standEnds && f.ariusHp > 0f) {
            stage = 2
            return lastStand()
        }
        if (stage != 1) return null
        val mark = WAVES.getOrNull(wave)
        if (mark != null) {
            if (frac > mark + 0.002f) return null
            val index = wave++
            return script {
                yieldAll(playWave(index))
            }
        }
        if (f.ariusHp <= f.ariusMax * FLOOR + 0.5f) {
            stage = 2
            return lastStand()
        }
        return null
    }

    // --- Alles auf einmal ------------------------------------------------------------------------------------------------

    private fun playWave(index: Int): Script = script {
        val a = f.arius ?: return@script
        f.calls.reset()
        MusicCue.sting(f, "phase")
        when (index) {
            0 -> {
                for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.all_out", "hha_arius.feat.all_out.sub", 50)
                yieldAll(summonEchoes(3))
                yieldAll(bladeBreath())
            }
            1 -> {
                if (echoes.size < 2) yieldAll(summonEchoes(2))
                val first = f.target()
                if (first != null) yieldAll(f.moves.frozenStar(first))
                wait(8)
                val second = f.players().maxByOrNull { p -> first?.let { p.squaredDistanceTo(it) } ?: 0.0 }
                if (second != null) yieldAll(f.moves.frozenStar(second))
            }
            2 -> yieldAll(f.scenes.lastHorizon(a, cross = true))
            else -> {
                dismissEchoes()
                MusicCue.play(f, "white_hole", 20)
                yieldAll(WhiteHoleBlast(f).run(finale = true))
                MusicCue.play(f, if (f.onSummit) "summit_final" else "finale", 40)
                return@script
            }
        }
        if (index != 2) {
            a.play(AriusPose.STAGGER)
            f.openArius(WAVE_OPEN)
            wait(WAVE_OPEN)
        }
    }

    /** Drachenatem aus der Klinge: drei Kegel nacheinander auf das Ziel, jeder angesagt, dazwischen Zeit zum Ausweichen. */
    private fun bladeBreath(): Script = script {
        val a = f.arius ?: return@script
        for (k in 0 until 3) {
            var t = f.target() ?: break
            if (k == 0 && (a.squaredDistanceTo(t) > 12.0 * 12.0 || !a.canSee(t))) {
                var n = 0
                while (n++ < 24 && f.canSequence(t) && (a.squaredDistanceTo(t) > 10.5 * 10.5 || !a.canSee(t))) {
                    f.stepToward(a, t.entityPos, 0.36)
                    yield(Unit)
                }
                t = f.target() ?: break
            }
            if (a.squaredDistanceTo(t) > 12.0 * 12.0 || !a.canSee(t)) break
            f.face(a, t.entityPos)
            val from = a.entityPos
            val dir = flatDir(from, t.entityPos)
            val cone = f.calls.cone(from, dir, 12f, 44f, if (k == 0) 28 else 20, Telegraph.Pace.HEAVY, style = Vfx.Style.SOUL)
            a.play(AriusPose.NULL_CUT, cone.ticks)
            f.ambient("nivaris.inhale", from, 3f, 1.2f)
            wait(cone.ticks)
            f.ambient("nivaris.breath", from, 4f, 1.1f)
            f.fxAll(FxKind.DRAGON_BREATH, from, a = 12f, life = 22, e1 = a)
            for (p in cone.hits(f.players())) f.hurt(p, 9f, Damage.Kind.FROST_BREATH)
            wait(10)
        }
    }

    // --- 1) Klingenabsichten -------------------------------------------------------------------------------------------

    private fun summonEchoes(count: Int): Script = script {
        val a = f.arius ?: return@script
        f.calls.reset()
        if (echoes.isEmpty()) for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.blade_intent", "hha_arius.feat.blade_intent.sub", 45)
        a.play(AriusPose.CORE_RAISE)
        f.ambient("lead.switch", a.entityPos, 3f, 0.6f)
        f.ambient("transform.pulse", a.entityPos, 3f, 1.3f)
        f.fxAll(FxKind.FLASH, a.entityPos, a = 0.2f, life = 6)
        wait(10)
        var n = 0
        while (echoes.size < count && n++ < 4) {
            val side = if (echoes.size % 2 == 0) 1.0 else -1.0
            val at = f.clampToArena(a.entityPos.add(Vec3d(-f.facing(a).z, 0.0, f.facing(a).x).multiply(2.5 * side)), 1.5)
            val echo = spawnEcho(a, at) ?: break
            echoes.add(echo)
            f.fx(FxKind.DASH_STREAK, a.entityPos, at, life = 10)
            Vfx.flare(f.world, Vfx.Style.HEAVEN, at.add(0.0, 1.2, 0.0), 1.4f, 8)
        }
        f.ambient("iai.glint", a.entityPos, 3f, 0.8f)
        a.play(AriusPose.IDLE)
        lastEcho = f.now
        if (!echoTask) {
            echoTask = true
            f.task(script {
                try {
                    while (stage == 1 && active && echoes.isNotEmpty() && !f.ended) {
                        tickEchoes()
                        yield(Unit)
                    }
                } finally {
                    echoTask = false
                }
            })
        }
        wait(10)
    }

    private fun spawnEcho(a: AriusEntity, at: Vec3d): Echo? {
        val e = AriusEntities.ARIUS.create(f.world, SpawnReason.EVENT) ?: return null
        e.refreshPositionAndAngles(at.x, f.floorY, at.z, a.yaw, 0f)
        e.form = AriusForm.ECHO
        e.bladeState = AriusBlade.HELD
        e.eyeGlow = 100
        val echo = Echo(e)
        e.damageHandler = BossDamageHandler { _, source, _ -> onEchoHit(echo, source); if (echo.e.isRemoved) 0f else 1f }
        f.world.spawnEntity(e)
        return echo
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
        if (f.moves.countering || f.now < f.ariusOpenUntil || f.now - lastEcho < ECHO_EVERY) return
        val echo = echoes[echoTurn++ % echoes.size]
        lastEcho = f.now
        echo.busy = true
        f.task(echoStrike(echo))
    }

    private fun echoStrike(echo: Echo): Script = script {
        try {
            val e = echo.e
            val pool = f.combatTargets().filter { !f.calls.hasBossTelegraph(it) }
            val t = (if (pool.isEmpty()) null else pool[rnd.nextInt(pool.size)]) ?: return@script
            val a = f.arius
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

    fun cleanup() {
        for (echo in echoes) echo.e.discard()
        echoes.clear()
    }

    /**
     * Der Sturm bricht: Arius zieht den Sturm an sich, sein Stoß drückt alle zurück (kein Schaden). Dann drei Iai-Schnitte
     * quer über den Gipfel, jeder auf einer festen, angesagten Linie und reihum auf einen anderen Spieler. Wer zur Seite
     * tritt, bleibt heil. Der dritte Schnitt reißt den Sturm auf: Der Himmel wird klar, goldenes Licht fällt auf den Gipfel,
     * und Arius kniet erschöpft an seiner Klinge. In diesem Fenster kann man ihn fällen. Steht er wieder auf, beginnt es
     * von vorn. Niemand muss auf einen ausgefallenen Mitspieler warten.
     */
    private fun lastStand(): Script = script {
        val a = f.arius ?: return@script
        f.calls.reset()
        dismissEchoes()
        f.guardUntil = 0
        a.counterStance = false
        a.sheathed = false
        a.eyeGlow = 100
        val first = standRound++ == 0
        MusicCue.sting(f, "phase")
        a.play(AriusPose.CORE_RAISE)
        if (first) {
            for (p in f.audience()) Cine.title(listOf(p), "hha_arius.feat.storm_breaks", "hha_arius.feat.storm_breaks.sub", 52)
            f.say("finale.silence", 60)
        }
        f.fxAll(FxKind.REVERSE_WIND, a.entityPos, a = 26f, life = 40)
        f.ambient("cut.reverse_wind", a.entityPos, 4f, 0.7f)
        f.ambient("transform.pulse", a.entityPos, 4f, 0.6f)
        wait(if (first) 34 else 20)
        f.ambient("arius.stab_ground", a.entityPos, 3f, 0.8f)
        Vfx.shockwave(f.world, Vfx.Style.HEAVEN, a.entityPos, 11f, 16, 2f)
        f.fx(FxKind.CLEAR_WAVE, a.entityPos, a = 12f, life = 18)
        for (p in f.combatTargets()) if (p.squaredDistanceTo(a) < 7.5 * 7.5) {
            Fx.launchPlayer(p, flatDir(a.entityPos, p.entityPos).multiply(0.85).add(0.0, 0.32, 0.0))
        }
        wait(16)

        for (k in 0 until CUTS) {
            val t = cutTarget() ?: break
            yieldAll(stormCut(a, t, k))
        }

        // Der Sturm reißt auf.
        val at = a.entityPos
        val sky = Vec3d(f.arenaCenter.x, f.floorY, f.arenaCenter.z)
        a.sheathed = false
        a.play(AriusPose.STAB_GROUND, 8)
        f.ambient("arius.stab_ground", at, 4f, 0.62f)
        f.ambient("transform.shatter", at, 3f, 0.75f)
        f.ambient("resonance.chime", at, 4f, 0.6f)
        f.fxAll(FxKind.FLASH, at, a = 0.32f, life = 8)
        f.fxAll(FxKind.TIME_SLOW, at, a = 0.6f, life = 16)
        Vfx.shockwave(f.world, Vfx.Style.GOLD, at, 18f, 26, 2.6f)
        f.atmos(dev.henny.hha_arius.net.AtmosMode.REVEAL, 30)
        Vfx.skyMood(f.world, Vfx.Style.GOLD, sky, 0.35f, STAND_OPEN, 70f)
        Vfx.godRays(f.world, Vfx.Style.GOLD, sky.add(0.0, 46.0, 0.0), 1f, 46f, STAND_OPEN)
        stage = 3
        standEnds = f.now + STAND_OPEN
        f.openArius(STAND_OPEN)
        wait(8)
        a.play(AriusPose.KNEEL)
        a.eyeGlow = 30
        wait(STAND_OPEN - 8)
        // Nicht gefällt: Er steht wieder auf, der Sturm zieht zurück, und es geht von vorn los.
        if (!f.ended && f.ariusHp > 0f) {
            f.atmos(dev.henny.hha_arius.net.AtmosMode.VAULT, 40)
            a.eyeGlow = 100
            a.play(AriusPose.IDLE)
            wait(14)
        }
    }

    /** Reihum der nächste Spieler, den ein Schnitt erreichen kann. */
    private fun cutTarget(): ServerPlayerEntity? {
        val pool = f.combatTargets().ifEmpty { f.players() }
        if (pool.isEmpty()) return null
        return pool[nextCutTarget++ % pool.size]
    }

    private fun stormCut(a: AriusEntity, t: ServerPlayerEntity, k: Int): Script = script {
        a.sheathed = true
        a.play(AriusPose.IAI_STANCE)
        f.face(a, t.entityPos)
        wait(6)
        if (!f.canSequence(t)) return@script
        f.face(a, t.entityPos)
        val from = a.entityPos
        val dir = flatDir(from, t.entityPos)
        val end = cutEnd(a, from, dir)
        val line = f.calls.line(from, end, 1.6f, CUT_WINDUP[k], Telegraph.Pace.HEAVY)
        f.fx(FxKind.EDGE, from.add(0.0, 1.2, 0.0), life = line.ticks, e1 = a)
        f.ambient("iai.glint", from, 3f, 0.7f + k * 0.12f)
        f.ambient("cut.charge", from, 2.4f, 0.8f + k * 0.1f)
        a.play(AriusPose.LUNGE, line.ticks)
        wait(line.ticks)
        a.sheathed = false
        f.sound("arius.null_cut", from, 3.4f, 0.9f + k * 0.08f)
        f.sound("arius.thrust", from, 2.6f, 0.85f)
        for (p in line.hits(f.combatTargets())) f.hurt(p, if (k == CUTS - 1) 13f else 10f, Damage.Kind.NULL_CUT)
        for (i in 1..CUT_DASH) {
            f.place(a, from.lerp(end, i / CUT_DASH.toDouble()))
            f.face(a, end)
            yield(Unit)
        }
        f.fx(FxKind.DASH_STREAK, from, end, life = 12)
        f.fx(FxKind.SEAM, from.add(0.0, 0.2, 0.0), end.add(0.0, 0.2, 0.0), a = 3.4f, life = 26)
        Vfx.crescent(f.world, Vfx.Style.HEAVEN, from.lerp(end, 0.5).add(0.0, 1.1, 0.0), dir, from.distanceTo(end).toFloat() * 0.55f, 1.2f, 10)
        f.calls.hitShake(end, 2.2f, 26f)
        a.play(AriusPose.STAB_GROUND, 4)
        wait(if (k == CUTS - 1) 6 else CUT_REST)
    }

    /** Ende der Schnittlinie: bis zum Rand der Arena oder kurz vor einem Hindernis. */
    private fun cutEnd(a: AriusEntity, from: Vec3d, dir: Vec3d): Vec3d {
        val far = f.clampToArena(from.add(dir.multiply(f.arenaRadius * 2.0)), 1.6)
        val hit = f.world.raycast(net.minecraft.world.RaycastContext(
            from.add(0.0, 1.0, 0.0), Vec3d(far.x, from.y + 1.0, far.z),
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, a,
        ))
        val stop = if (hit.type == net.minecraft.util.hit.HitResult.Type.MISS) far else hit.pos.subtract(dir.multiply(1.1))
        return Vec3d(stop.x, from.y, stop.z)
    }

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }

    companion object {
        /** Bis hierhin kann man ihn vor dem letzten Ausfall schlagen (Anteil des Lebens). */
        const val FLOOR = 0.12f

        /** Lebensmarken der vier Wellen von „Alles auf einmal“. */
        val WAVES = floatArrayOf(0.44f, 0.34f, 0.25f, 0.17f).toList()

        /** Offenes Fenster nach einer Welle. */
        const val WAVE_OPEN = 50

        /** Abstand der Angriffe der Nachbilder. */
        const val ECHO_EVERY = 95L

        /** „Der Sturm bricht“: Schnitte, ihre Ansagezeiten, Dauer des Satzes und Pause, offenes Fenster danach. */
        const val CUTS = 3
        val CUT_WINDUP = intArrayOf(40, 34, 30)
        const val CUT_DASH = 5
        const val CUT_REST = 14
        const val STAND_OPEN = 220
    }
}

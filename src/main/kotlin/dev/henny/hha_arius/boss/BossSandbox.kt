package dev.henny.hha_arius.boss

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.net.AriusNet
import dev.henny.hha_arius.net.AtmosMode
import dev.henny.hha_arius.net.ClashCueS2C
import dev.henny.hha_arius.net.FxKind
import net.minecraft.command.CommandSource
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2

/** Die vier Schauplätze der Sandbox (`/arius sandbox <arena>`). */
enum class SandboxArena(val id: String) {
    TEMPLE("temple"),
    AERIE("aerie"),
    VAULT("vault"),
    SUMMIT("summit"),
    ;

    val nameKey: String get() = "hha_arius.sandbox.arena.$id"

    companion object {
        fun byId(id: String): SandboxArena? = entries.firstOrNull { it.id == id.lowercase() }
        fun ids(): List<String> = entries.map { it.id }
    }
}

/**
 * OP-Sandbox (`/arius sandbox`, `/arius attack`): Ein Testkampf an einem Schauplatz, in dem die Bosse nichts von sich aus
 * tun. Auf Befehl führt der passende Boss genau einen Angriff aus (mit denselben Vorbedingungen wie im Kampf: Form,
 * Klinge, Haltung, Führung, Flug), danach steht er wieder still. Leben laufen nie aus, es gibt keine Abschnittswechsel.
 * Der Kampfpfad selbst bleibt unberührt: Die Sandbox ersetzt nur die Regie ([EirholmFight.sandbox]).
 */
class BossSandbox(private val f: EirholmFight, start: SandboxArena) {

    /** Wer einen Angriff ausführt (bestimmt Vorbereitung und Aufräumen). */
    enum class Group(val key: String) {
        ARIUS("hha_arius.sandbox.group.arius"),
        SOLVANE_AIR("hha_arius.sandbox.group.solvane"),
        SOLVANE_GROUND("hha_arius.sandbox.group.solvane"),
        DUO("hha_arius.sandbox.group.duo"),
        FINALE("hha_arius.sandbox.group.finale"),
    }

    /** Ein Angriff: [make] baut das Skript gegen das Ziel. [stance] = Haltung, aus der er kommt (Pose vorher). */
    class Attack(val id: String, val group: Group, val stance: AriusMoves.Stance? = null, val make: (EirholmFight, ServerPlayerEntity) -> Script)

    enum class Result { OK, BUSY, PREPARING, UNKNOWN, NO_TARGET, NO_BOSS, NO_THROW }

    /** Aktueller Schauplatz (erst nach der Vorbereitung gültig, siehe [ready]). */
    var arena: SandboxArena = start
        private set

    /** Steht der Schauplatz (Bosse an ihrem Platz)? */
    var ready = false
        private set

    private var pending: SandboxArena? = start
    private var runner: Runner? = null

    /** Der laufende Angriff, sonst null. */
    var running: Attack? = null
        private set

    fun attacks(): List<Attack> = table(arena)

    /** Schauplatz wechseln (auch derselbe: setzt Bosse und Spieler zurück). Ein laufender Angriff wird abgebrochen. */
    fun switchTo(next: SandboxArena) {
        pending = next
        ready = false
    }

    /** Angriff [id] [count]-mal gegen den nächsten aktiven Spieler. */
    fun request(id: String, count: Int): Result {
        if (!ready || pending != null) return Result.PREPARING
        if (running != null) return Result.BUSY
        val attack = attacks().firstOrNull { it.id == id.lowercase() } ?: return Result.UNKNOWN
        val actor = actorOf(attack) ?: return Result.NO_BOSS
        if (nearestTo(actor.entityPos) == null) return Result.NO_TARGET
        if (attack.id == "grab_throw" && f.grabSource(actor.entityPos) == null) return Result.NO_THROW
        running = attack
        runner = Runner(script {
            for (i in 0 until count.coerceIn(1, 20)) {
                if (i > 0) wait(REPEAT_PAUSE)
                val who = actorOf(attack) ?: break
                val t = nearestTo(who.entityPos) ?: break
                f.duelTarget = t.uuid
                refill()
                yieldAll(wrap(attack, t))
            }
        })
        return Result.OK
    }

    /** Den laufenden Angriff abbrechen (z. B. „Der Sturm bricht“ ohne Antwort); Bosse gehen zurück in Ruhe. */
    fun cancel(): Boolean {
        if (running == null) return false
        abort()
        settle()
        return true
    }

    // --- Regie ---------------------------------------------------------------------------------------------------------

    /** Die Regie: Schauplatz vorbereiten, dann still stehen und nur befohlene Angriffe spielen. */
    fun director(): Script = script {
        while (!f.ended) {
            val next = pending
            if (next != null) {
                abort()
                if (next != SandboxArena.TEMPLE) {
                    while (!f.farReady && pending == next) {
                        if (f.world.time % 10L == 0L) {
                            val pct = (f.farProgress * 100).toInt()
                            for (p in f.audience()) p.sendMessage(Text.translatable("hha_arius.msg.forming_pct", pct).formatted(Formatting.AQUA), true)
                        }
                        yield(Unit)
                    }
                    if (pending != next) continue
                }
                pending = null
                arena = next
                prepare(next)
                ready = true
                for (p in f.audience()) {
                    p.sendMessage(Text.translatable("hha_arius.sandbox.ready", Text.translatable(next.nameKey)).formatted(Formatting.AQUA), false)
                }
            }
            val r = runner
            if (r != null && !r.step()) {
                runner = null
                running = null
                settle()
            }
            keepAlive()
            if (runner == null) idle()
            yield(Unit)
        }
    }

    /** Leben laufen nie aus (Spielerschaden bleibt echt). Unter 50 % wieder voll, damit nichts in den nächsten Abschnitt kippt. */
    private fun keepAlive() {
        if (f.ariusMax > 0f && f.ariusHp < f.ariusMax * 0.5f) f.ariusHp = f.ariusMax
        if (f.dragonMax > 0f && f.dragonHp < f.dragonMax * 0.5f) f.dragonHp = f.dragonMax
    }

    private fun refill() {
        f.ariusHp = f.ariusMax
        f.dragonHp = f.dragonMax
    }

    /** Ruhe: Arius blickt zum nächsten Spieler (nicht im Horst, dort sitzt er). Keine Schritte, kein Angriff. */
    private fun idle() {
        if (f.now % 10L != 0L || arena == SandboxArena.AERIE) return
        val a = f.arius ?: return
        if (f.moves.countering || f.moves.acting) return
        nearestTo(a.entityPos)?.let { f.face(a, it.entityPos) }
    }

    private fun nearestTo(pos: Vec3d): ServerPlayerEntity? = f.players().minByOrNull { it.squaredDistanceTo(pos) }

    private fun actorOf(attack: Attack) = when (attack.group) {
        Group.ARIUS, Group.FINALE -> f.arius
        Group.SOLVANE_AIR, Group.SOLVANE_GROUND -> f.nivaris
        Group.DUO -> f.arius?.takeIf { f.nivaris != null }
    }

    // --- Ein Angriff mit den Vorbedingungen des Kampfs -----------------------------------------------------------------

    private fun wrap(attack: Attack, t: ServerPlayerEntity): Script = script {
        when (attack.group) {
            Group.ARIUS -> {
                // Wie in turnTemple/turnWhite/turnFinale: erst warten, bis eine laufende Konter-Sequenz vorbei ist.
                waitUntil(220) { !f.moves.countering }
                lead(arius = true)
                val a = f.arius ?: return@script
                when (attack.stance) {
                    AriusMoves.Stance.HEAVEN -> { a.sheathed = false; a.play(AriusPose.CUT_CHARGE); wait(10) }
                    AriusMoves.Stance.SHEATH -> { a.sheathed = a.bladeState == AriusBlade.HELD; a.play(AriusPose.IAI_STANCE); wait(10) }
                    else -> Unit
                }
                f.moves.acting = true
                yieldAll(attack.make(f, t))
                f.moves.acting = false
            }
            Group.SOLVANE_AIR -> {
                lead(arius = false)
                val d = f.nivaris ?: return@script
                // Wie in airPhase: abheben, kurz kreisen, dann angreifen; landet sie nicht selbst, setzt sie danach auf.
                if (!d.flying) with(f.dragon) {
                    takeoff()
                    circle(20, 26.0, 12.0)
                }
                yieldAll(attack.make(f, t))
                if (f.nivaris?.flying == true) yieldAll(f.dragon.land(nearestTo(f.nivaris!!.entityPos)))
            }
            Group.SOLVANE_GROUND -> {
                lead(arius = false)
                if (f.nivaris?.flying == true) yieldAll(f.dragon.land(t))
                yieldAll(attack.make(f, t))
            }
            Group.DUO -> {
                yieldAll(attack.make(f, t))
                if (f.nivaris?.flying == true) yieldAll(f.dragon.land(nearestTo(f.nivaris!!.entityPos)))
            }
            Group.FINALE -> yieldAll(attack.make(f, t))
        }
        settle()
    }

    /** Führung im Herzgewölbe (Augenglühen, „▶“ an der Leiste, Erstickungsfolge) wie im Kampf. */
    private fun lead(arius: Boolean) {
        if (arena != SandboxArena.VAULT) return
        f.ariusLeads = arius
        f.arius?.let { it.lead = arius; it.eyeGlow = if (arius) 100 else 60 }
        f.nivaris?.let { it.lead = !arius; it.eyeGlow = if (!arius) 100 else 55 }
        f.updateBars()
    }

    /** Ruhezustand des Schauplatzes nach einem Angriff (oder Abbruch). */
    private fun settle() {
        f.moves.acting = false
        f.inhaling = false
        f.moves.summit.sandboxReset()
        val a = f.arius
        val d = f.nivaris
        d?.throatGlow = 0
        if (d != null && !d.flying) d.play(NivarisPose.IDLE)
        when (arena) {
            SandboxArena.TEMPLE -> {
                f.ariusVulnerable = true
                a?.let { it.sheathed = false; if (!f.moves.countering) it.play(AriusPose.IDLE) }
            }
            SandboxArena.AERIE -> {
                f.dragonVulnerable = true
            }
            SandboxArena.VAULT -> {
                f.ariusVulnerable = true
                f.dragonVulnerable = true
                d?.resonance = true
                a?.let { it.sheathed = false; if (!f.moves.countering) it.play(AriusPose.IDLE) }
            }
            SandboxArena.SUMMIT -> {
                f.ariusVulnerable = true
                a?.let {
                    // „Der Sturm bricht“ zerbricht die Klinge in Stufen: danach wieder Solvane in der Scheide.
                    if (it.bladeState != AriusBlade.NONE) it.bladeState = AriusBlade.HELD
                    it.sheathed = true
                    it.eyeGlow = 80
                    if (!f.moves.countering) it.play(AriusPose.IAI_STANCE)
                }
            }
        }
        f.updateBars()
    }

    /** Laufenden Angriff abbrechen. Abgebrochene Skripte laufen ihr finally nicht: Zustände von Hand zurücksetzen. */
    private fun abort() {
        val was = running
        runner = null
        running = null
        if (was == null) return
        f.calls.reset()
        f.moves.clearActing()
        f.dragon.clearActing()
        f.moves.cleanup()
        f.moves.summit.sandboxReset()
        f.inhaling = false
        f.nivaris?.throatGlow = 0
        f.guardUntil = 0
        f.arius?.counterStance = false
        f.arius?.let { if (it.bladeState == AriusBlade.NONE && arena != SandboxArena.AERIE) it.bladeState = idleBlade() }
        val everyone = f.audience()
        Cine.end(everyone)
        f.silence(0f, 10)
        for (p in everyone) {
            Hold.drop(p)
            AriusNet.send(p, ClashCueS2C(false, 16, 0, 0))
        }
        if (was.id == "storm_breaks") f.atmos(AtmosMode.VAULT, 30)
    }

    private fun idleBlade() = when (arena) {
        SandboxArena.AERIE -> AriusBlade.NONE
        SandboxArena.VAULT -> AriusBlade.ICE
        else -> AriusBlade.HELD
    }

    // --- Schauplätze ---------------------------------------------------------------------------------------------------

    private fun prepare(arena: SandboxArena) {
        f.chasing = false
        for (p in f.audience()) {
            Hold.drop(p)
            if (p.hasVehicle()) p.stopRiding()
        }
        Cine.end(f.audience())
        when (arena) {
            SandboxArena.TEMPLE -> temple()
            SandboxArena.AERIE -> aerie()
            SandboxArena.VAULT -> vault()
            SandboxArena.SUMMIT -> summit()
        }
        f.moves.acting = false
        f.updateBars()
    }

    private fun ariusOrSpawn(at: Vec3d): AriusEntity? {
        if (f.arius == null) f.spawnArius(at, 0f)
        return f.arius
    }

    private fun teleportAll(spots: List<Vec3d>, lookAt: Vec3d?) {
        for ((i, p) in f.players().withIndex()) {
            val s = f.abs(spots[i % spots.size])
            val yaw = lookAt?.let { (Math.toDegrees(atan2(it.z - s.z, it.x - s.x)) - 90.0).toFloat() } ?: 180f
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), yaw, 0f, false)
            p.velocity = Vec3d.ZERO
            p.fallDistance = 0.0
            p.onLanding()
        }
    }

    /** Wie [Scenes.temple] nach dem Sprung vom Altar: dunkle Form vor den Stufen. */
    private fun temple() {
        f.scenes.restoreSplitInstant()
        f.onSummit = false
        f.dismissNivaris()
        f.setPhase(EirholmFight.Phase.TEMPLE)
        val at = f.abs(ArenaLayout.DAIS_FRONT)
        val a = ariusOrSpawn(at) ?: return
        f.place(a, at)
        a.form = AriusForm.DARK
        a.bladeState = AriusBlade.HELD
        a.sheathed = false
        a.lead = false
        a.counterStance = false
        a.eyeGlow = 45
        a.play(AriusPose.IDLE)
        f.ariusMax = BossConfig.health(820f, f.startCount)
        f.ariusHp = f.ariusMax
        f.ariusVulnerable = true
        f.dragonVulnerable = false
        f.dragonDowned = true
        f.moves.resetStance()
        f.music("music.temple", 20)
        teleportAll(listOf(ArenaLayout.ARRIVAL), a.entityPos)
        f.face(a, f.abs(ArenaLayout.ARRIVAL))
    }

    /** Wie [Scenes.trainingGlacier] und [Scenes.glacier]: Solvane ruht in der Horstmitte, Arius sieht vom Felssitz zu. */
    private fun aerie() {
        f.scenes.ensureSplitImmediate()
        f.onSummit = false
        f.setPhase(EirholmFight.Phase.GLACIER)
        val seat = f.abs(ArenaLayout.AERIE_SEAT)
        val a = ariusOrSpawn(seat) ?: return
        f.place(a, seat)
        f.face(a, f.arenaCenter)
        a.form = AriusForm.DARK
        a.bladeState = AriusBlade.NONE
        a.sheathed = false
        a.lead = false
        a.counterStance = false
        a.play(AriusPose.SEATED)
        f.spawnNivaris(f.abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5), 90f)
        f.nivaris?.let { d ->
            d.flying = false
            d.play(NivarisPose.IDLE)
            f.fxAll(FxKind.DRAGON_AURA, d.entityPos, life = 20 * 60 * 30, e1 = d)
        }
        f.dragonMax = BossConfig.health(760f, f.startCount)
        f.dragonHp = f.dragonMax
        f.dragonDowned = false
        f.dragonVulnerable = true
        f.ariusVulnerable = false
        f.music("music.glacier", 20)
        MusicCue.play(f, "glacier", 20)
        for ((i, p) in f.players().withIndex()) {
            val s = f.abs(ArenaLayout.AERIE_LANDING[i % ArenaLayout.AERIE_LANDING.size])
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), 90f, 0f, false)
            p.velocity = Vec3d.ZERO
            p.fallDistance = 0.0
            p.onLanding()
        }
    }

    /** Wie [Scenes.trainingVault] und [Scenes.vaultFight]: weiße Form mit Eisklinge, Solvane ruht am Nordrand, Arius führt. */
    private fun vault() {
        f.scenes.ensureSplitImmediate()
        f.onSummit = false
        ariusOrSpawn(f.abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)) ?: return
        f.scenes.prepareVault()
        val a = f.arius ?: return
        a.lead = false
        a.counterStance = false
        f.ariusMax = BossConfig.health(640f, f.startCount)
        f.ariusHp = f.ariusMax
        f.dragonMax = BossConfig.health(500f, f.startCount)
        f.dragonHp = f.dragonMax
        f.dragonDowned = false
        f.ariusVulnerable = true
        f.dragonVulnerable = true
        f.nivaris?.let { d ->
            d.flying = false
            d.play(NivarisPose.IDLE)
            f.face(d, f.arenaCenter)
        }
        f.moves.resetStance()
        f.music("music.heart", 20)
        MusicCue.play(f, "heart", 20)
        lead(arius = true)
        f.target()?.let { f.face(a, it.entityPos) }
    }

    /** Wie [Scenes.trainingFinale] auf dem Gipfel: Solvane in der Scheide, Iai-Haltung, kein Drache. */
    private fun summit() {
        f.scenes.restoreSplitInstant()
        ariusOrSpawn(f.abs(f.summitCenterRel)) ?: return
        f.scenes.prepareSummit(withDragon = false)
        val a = f.arius ?: return
        a.lead = false
        a.counterStance = false
        f.dragonDowned = true
        f.dragonVulnerable = false
        f.ariusMax = BossConfig.health(480f, f.startCount)
        f.ariusHp = f.ariusMax
        f.ariusVulnerable = true
        f.moves.resetStance(AriusMoves.Stance.SHEATH, 300)
        f.music("music.summit", 60)
        MusicCue.play(f, "summit", 60)
    }

    // --- Angriffe je Schauplatz ----------------------------------------------------------------------------------------

    companion object {
        /** Pause zwischen Wiederholungen ([request] mit count > 1). */
        const val REPEAT_PAUSE = 20

        private val A = Group.ARIUS
        private val AIR = Group.SOLVANE_AIR
        private val GROUND = Group.SOLVANE_GROUND
        private val DUO = Group.DUO
        private val FIN = Group.FINALE
        private val HEAVEN = AriusMoves.Stance.HEAVEN
        private val SHEATH = AriusMoves.Stance.SHEATH

        /** Wer heilt, wird eingeholt: wartet bis zu 20 s, dass jemand trinkt oder isst, dann [AriusMoves.healPunish]. */
        private fun healPunish(f: EirholmFight): Script = script {
            for (p in f.players()) p.sendMessage(Text.translatable("hha_arius.sandbox.heal_wait").formatted(Formatting.GOLD), true)
            waitUntil(400) { f.moves.healTarget != null }
            if (f.moves.healTarget != null) yieldAll(f.moves.healPunish())
        }

        /** Klaue wie im Bodenzug: ist das Ziel weiter weg, geht sie erst heran. */
        private fun clawAt(f: EirholmFight, t: ServerPlayerEntity): Script = script {
            val d = f.nivaris ?: return@script
            var n = 0
            if (t.squaredDistanceTo(d) >= 49.0) {
                d.play(NivarisPose.IDLE)
                while (n++ < 26 && t.squaredDistanceTo(d) > 36.0) {
                    f.stepToward(d, t.entityPos, 0.3)
                    yield(Unit)
                }
                f.face(d, t.entityPos)
            }
            yieldAll(f.dragon.claw(t, null))
        }

        /** Tempel: Ariusʼ Züge aus [AriusMoves.turnTemple] (dunkle Form, alle drei Haltungen). */
        private val TEMPLE_ATTACKS = listOf(
            Attack("combo", A) { f, _ -> f.moves.combo(false, false) },
            Attack("chain", A) { f, _ -> f.moves.chain(false, 5) },
            Attack("feint", A) { f, _ -> f.moves.feint(false) },
            Attack("lunge", A) { f, t -> f.moves.lunge(t, false) },
            Attack("guard", A) { f, _ -> f.moves.guard(false, false) },
            Attack("windstep", A) { f, _ -> f.moves.stepChain(false) },
            Attack("frost_lotus", A) { f, _ -> f.moves.frostLotus(false) },
            Attack("ice_javelin", A) { f, _ -> f.moves.iceJavelin(false) },
            Attack("frost_thorns", A) { f, _ -> f.moves.frostThorns(false) },
            Attack("flying_blade", A) { f, _ -> f.moves.flyingBladeAttack(false) },
            Attack("frost_lane", A) { f, _ -> f.moves.frostLane(false) },
            Attack("frost_grip", A) { f, t -> f.moves.grip(t) },
            Attack("glacier_stab", A) { f, _ -> f.moves.stabGround() },
            Attack("heaven_cleave", A, HEAVEN) { f, _ -> f.moves.heavenCleave(false, false) },
            Attack("sky_leap", A, HEAVEN) { f, _ -> f.moves.skyLeap(false) },
            Attack("iai_draw", A, SHEATH) { f, _ -> f.moves.iaiDraw(false, 2) },
            Attack("iai_counter", A, SHEATH) { f, _ -> f.moves.iaiCounter(false) },
            Attack("heal_punish", A) { f, _ -> healPunish(f) },
        )

        /** Solvanes Luftangriffe aus der Luftphase ([NivarisMoves.airPhase]). */
        private val DRAGON_AIR = listOf(
            Attack("breath", AIR) { f, t -> f.dragon.breath(t) },
            Attack("dive", AIR) { f, t -> f.dragon.dive(t) },
            Attack("sky_plunge", AIR) { f, t -> f.dragon.skyPlunge(t) },
            Attack("ice_volley", AIR) { f, _ -> f.dragon.iceVolley() },
            Attack("body_sweep", AIR) { f, t -> f.dragon.bodySweep(t) },
            Attack("ice_cage", AIR) { f, t -> f.dragon.iceCage(t) },
            Attack("storm_spiral", AIR) { f, _ -> f.dragon.stormSpiral() },
        )

        /** Solvanes Bodenangriffe aus der Bodenphase ([NivarisMoves.groundPhase]). */
        private val DRAGON_GROUND = listOf(
            Attack("frost_roar", GROUND) { f, _ -> f.dragon.frostRoar() },
            Attack("coil_crush", GROUND) { f, t -> f.dragon.coilCrush(t) },
            Attack("glacier_charge", GROUND) { f, t -> f.dragon.glacierCharge(t) },
            Attack("grab_throw", GROUND) { f, t -> f.dragon.grabThrow(t) },
            Attack("tail", GROUND) { f, _ -> f.dragon.tail(null) },
            Attack("claw", GROUND) { f, t -> clawAt(f, t) },
            Attack("gust", GROUND) { f, t -> f.dragon.gust(t) },
        )

        /** Horst: alle Luft- und Bodenangriffe, der Winterzorn und die Landung. */
        private val AERIE_ATTACKS = DRAGON_AIR + listOf(
            Attack("winters_roar", AIR) { f, _ -> f.dragon.wintersRoar() },
            Attack("landing", AIR) { _, _ -> script { } },
        ) + DRAGON_GROUND

        /** Herzgewölbe: weiße Form ([AriusMoves.turnWhite]), Solvanes Gewölbezüge, Begleitung, Resonanz und weißes Loch. */
        private val VAULT_ATTACKS = listOf(
            Attack("combo", A) { f, _ -> f.moves.combo(true, false) },
            Attack("chain", A) { f, _ -> f.moves.chain(true, 6) },
            Attack("feint", A) { f, _ -> f.moves.feint(true) },
            Attack("lunge", A) { f, t -> f.moves.lunge(t, true) },
            Attack("guard", A) { f, _ -> f.moves.guard(true, false) },
            Attack("windstep", A) { f, _ -> f.moves.stepChain(true) },
            Attack("frost_lotus", A) { f, _ -> f.moves.frostLotus(true) },
            Attack("frost_thorns", A) { f, _ -> f.moves.frostThorns(true) },
            Attack("flying_blade", A) { f, _ -> f.moves.flyingBladeAttack(true) },
            Attack("null_cut", A) { f, t -> f.moves.nullCut(t) },
            Attack("frozen_star", A) { f, t -> f.moves.frozenStar(t) },
            Attack("zero_soul", A) { f, t -> f.moves.absoluteZeroSoul(t) },
            Attack("glacier_stab", A, HEAVEN) { f, _ -> f.moves.stabGround() },
            Attack("heaven_cleave", A, HEAVEN) { f, _ -> f.moves.heavenCleave(true, true) },
            Attack("sky_leap", A, HEAVEN) { f, _ -> f.moves.skyLeap(true) },
            Attack("arius_support", A) { f, _ -> f.moves.support() },
            Attack("heal_punish", A) { f, _ -> healPunish(f) },
        ) + DRAGON_AIR + DRAGON_GROUND + listOf(
            Attack("solvane_support", GROUND) { f, _ -> f.dragon.support() },
        ) + Scenes.Resonance.entries.map { kind ->
            Attack(kind.name.lowercase(), DUO) { f, _ -> f.scenes.resonanceAttack(kind) }
        } + listOf(
            Attack("white_hole", DUO) { f, _ ->
                script {
                    MusicCue.play(f, "white_hole", 30)
                    yieldAll(WhiteHoleBlast(f).run())
                    MusicCue.play(f, "heart", 40)
                }
            },
        )

        /** Gipfel: Duellzüge aus [AriusMoves.turnFinale], die Klingenabsichten, die vier Wellen und „Der Sturm bricht“. */
        private val SUMMIT_ATTACKS = listOf(
            Attack("combo", A) { f, _ -> f.moves.combo(false, true) },
            Attack("chain", A) { f, _ -> f.moves.chain(false, 4) },
            Attack("feint", A) { f, _ -> f.moves.feint(false) },
            Attack("lunge", A) { f, t -> f.moves.lunge(t, false) },
            Attack("guard", A) { f, _ -> f.moves.guard(false, false) },
            Attack("windstep", A) { f, _ -> f.moves.stepChain(false) },
            Attack("frost_lotus", A) { f, _ -> f.moves.frostLotus(false) },
            Attack("null_cut", A) { f, t -> f.moves.nullCut(t) },
            Attack("glacier_stab", A, HEAVEN) { f, _ -> f.moves.stabGround() },
            Attack("heaven_cleave", A, HEAVEN) { f, _ -> f.moves.heavenCleave(false, false) },
            Attack("sky_leap", A, HEAVEN) { f, _ -> f.moves.skyLeap(false) },
            Attack("iai_draw", A, SHEATH) { f, _ -> f.moves.iaiDraw(false, 2) },
            Attack("iai_counter", A, SHEATH) { f, _ -> f.moves.iaiCounter(false) },
            Attack("heal_punish", A) { f, _ -> healPunish(f) },
            Attack("blade_echoes", FIN) { f, _ -> f.moves.summit.sandboxEchoes(300) },
            Attack("all_out", FIN) { f, _ -> f.moves.summit.sandboxWave(0) },
            Attack("frozen_stars", FIN) { f, _ -> f.moves.summit.sandboxWave(1) },
            Attack("last_horizon", FIN) { f, _ -> f.moves.summit.sandboxWave(2) },
            Attack("white_hole", FIN) { f, _ -> f.moves.summit.sandboxWave(3) },
            Attack("storm_breaks", FIN) { f, _ ->
                script {
                    // Nach den drei Antworten kniet er offen, bis ihn jemand trifft; in der Sandbox höchstens 10 s.
                    val r = Runner(f.moves.summit.sandboxLastStand())
                    var open = 0
                    while (r.step()) {
                        if (f.moves.summit.stage >= 3 && ++open > 200) break
                        yield(Unit)
                    }
                    f.atmos(AtmosMode.VAULT, 30)
                }
            },
        )

        fun table(arena: SandboxArena): List<Attack> = when (arena) {
            SandboxArena.TEMPLE -> TEMPLE_ATTACKS
            SandboxArena.AERIE -> AERIE_ATTACKS
            SandboxArena.VAULT -> VAULT_ATTACKS
            SandboxArena.SUMMIT -> SUMMIT_ATTACKS
        }
    }
}

/** Befehle der Sandbox: `/arius sandbox <arena>|stop`, `/arius attack <id> [count]|list|stop` (registriert in [AriusCommands]). */
object SandboxCommands {

    private fun sandboxOf(player: ServerPlayerEntity): Pair<EirholmFight, BossSandbox>? {
        val f = Encounters.all().firstOrNull { !it.ended && it.isParticipant(player) && it.sandbox != null } ?: return null
        return f to f.sandbox!!
    }

    fun register(root: LiteralArgumentBuilder<ServerCommandSource>) {
        root.then(
            CommandManager.literal("sandbox")
                .then(CommandManager.literal("stop").executes { ctx -> stop(ctx.source) })
                .then(
                    CommandManager.argument("arena", StringArgumentType.word())
                        .suggests { _, b -> CommandSource.suggestMatching(SandboxArena.ids(), b) }
                        .executes { ctx -> open(ctx.source, StringArgumentType.getString(ctx, "arena")) },
                ),
        )
        root.then(
            CommandManager.literal("attack")
                .then(CommandManager.literal("list").executes { ctx -> list(ctx.source) })
                .then(CommandManager.literal("stop").executes { ctx -> cancel(ctx.source) })
                .then(
                    CommandManager.argument("id", StringArgumentType.word())
                        .suggests { ctx, b ->
                            val ids = ctx.source.player?.let { sandboxOf(it) }?.second?.attacks()?.map { it.id } ?: emptyList()
                            CommandSource.suggestMatching(ids, b)
                        }
                        .executes { ctx -> attack(ctx.source, StringArgumentType.getString(ctx, "id"), 1) }
                        .then(
                            CommandManager.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes { ctx ->
                                    attack(
                                        ctx.source, StringArgumentType.getString(ctx, "id"),
                                        IntegerArgumentType.getInteger(ctx, "count"),
                                    )
                                },
                        ),
                ),
        )
    }

    private fun open(source: ServerCommandSource, id: String): Int {
        val player = source.playerOrThrow
        val arena = SandboxArena.byId(id) ?: run {
            source.sendError(Text.translatable("hha_arius.sandbox.unknown_arena", id))
            return 0
        }
        val running = sandboxOf(player)
        if (running != null) {
            running.second.switchTo(arena)
            source.sendFeedback({ Text.translatable("hha_arius.sandbox.switched", Text.translatable(arena.nameKey)).formatted(Formatting.AQUA) }, true)
            return 1
        }
        // Gleicher Weg wie `/arius test`: ein Testkampf ohne Siegel und Beute, nur mit der Sandbox als Regie.
        if (!Encounters.requestTest(player, AriusTestTarget.INTRO)) return 0
        val f = Encounters.all().firstOrNull { !it.ended && it.isParticipant(player) && it.phase == EirholmFight.Phase.BUILD } ?: return 0
        f.sandbox = BossSandbox(f, arena)
        source.sendFeedback({ Text.translatable("hha_arius.sandbox.started", Text.translatable(arena.nameKey)).formatted(Formatting.AQUA) }, true)
        return 1
    }

    private fun stop(source: ServerCommandSource): Int {
        val player = source.playerOrThrow
        if (sandboxOf(player) == null || !Encounters.stopTest(player)) {
            source.sendFeedback({ Text.translatable("hha_arius.sandbox.none").formatted(Formatting.GRAY) }, false)
            return 0
        }
        source.sendFeedback({ Text.translatable("hha_arius.sandbox.stopped").formatted(Formatting.AQUA) }, true)
        return 1
    }

    private fun list(source: ServerCommandSource): Int {
        val sb = sandboxOf(source.playerOrThrow)?.second ?: run {
            source.sendFeedback({ Text.translatable("hha_arius.sandbox.none").formatted(Formatting.GRAY) }, false)
            return 0
        }
        val all = sb.attacks()
        source.sendFeedback({ Text.translatable("hha_arius.sandbox.list_header", Text.translatable(sb.arena.nameKey), all.size).formatted(Formatting.AQUA, Formatting.BOLD) }, false)
        for ((key, group) in all.groupBy { it.group.key }) {
            source.sendFeedback({
                Text.literal("  ").append(Text.translatable(key).formatted(Formatting.WHITE)).append(Text.literal(": ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(group.joinToString(", ") { it.id }).formatted(Formatting.GRAY))
            }, false)
        }
        return all.size
    }

    private fun cancel(source: ServerCommandSource): Int {
        val sb = sandboxOf(source.playerOrThrow)?.second ?: run {
            source.sendFeedback({ Text.translatable("hha_arius.sandbox.none").formatted(Formatting.GRAY) }, false)
            return 0
        }
        if (!sb.cancel()) {
            source.sendFeedback({ Text.translatable("hha_arius.sandbox.idle").formatted(Formatting.GRAY) }, false)
            return 0
        }
        source.sendFeedback({ Text.translatable("hha_arius.sandbox.cancelled").formatted(Formatting.AQUA) }, false)
        return 1
    }

    private fun attack(source: ServerCommandSource, id: String, count: Int): Int {
        val sb = sandboxOf(source.playerOrThrow)?.second ?: run {
            source.sendFeedback({ Text.translatable("hha_arius.sandbox.none").formatted(Formatting.GRAY) }, false)
            return 0
        }
        val arena = Text.translatable(sb.arena.nameKey)
        val busy = sb.running
        when (sb.request(id, count)) {
            BossSandbox.Result.OK -> {
                val a = sb.running ?: return 0
                source.sendFeedback({ Text.translatable("hha_arius.sandbox.attack", Text.translatable(a.group.key), a.id, count).formatted(Formatting.AQUA) }, false)
                return 1
            }
            BossSandbox.Result.BUSY -> source.sendError(Text.translatable("hha_arius.sandbox.busy", busy?.id ?: "?"))
            BossSandbox.Result.PREPARING -> source.sendError(Text.translatable("hha_arius.sandbox.preparing"))
            BossSandbox.Result.UNKNOWN -> source.sendError(Text.translatable("hha_arius.sandbox.unknown_attack", id, arena))
            BossSandbox.Result.NO_TARGET -> source.sendError(Text.translatable("hha_arius.sandbox.no_target"))
            BossSandbox.Result.NO_BOSS -> source.sendError(Text.translatable("hha_arius.sandbox.no_boss"))
            BossSandbox.Result.NO_THROW -> source.sendError(Text.translatable("hha_arius.sandbox.no_throw"))
        }
        return 0
    }
}

package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.net.FxKind
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.Entity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ariusʼ Moveset (Konzept 3.1, 5.2). Jede Attacke: Ansage über [Telegraph] (Form = Trefferfläche), Pose mit genau
 * derselben Ausholzeit, Treffer zur Trefferzeit, danach ein echtes Fenster (goldene Rune).
 *
 * Seit 1.9.0 nach dem Vorbild des letzten Bosses aus Black Myth: Wukong (Crix: „mehr Elemente vom Endboss“):
 * * **Haltungen** ([Stance]): fließend (Ketten, Finten), Himmelshaltung (Klinge hoch, schwere, verzögerte Hiebe) und
 *   Scheidenhaltung (Iai: schnelle Züge und Konter). Jeder Wechsel ist sichtbar und hörbar angesagt.
 * * **Perfekter Konter**: Wer in die Konterhaltung schlägt (oder blind auf ihn einhämmert), wird pariert, die Deckung
 *   bricht kurz, und es folgt eine echte Kombo (jeder Folgeschlag angesagt, ab dem zweiten ausweichbar).
 * * **Lange Ketten** mit wechselndem Rhythmus, **verzögerte** Schläge und **Finten** (angesagter Stoß, der nie kommt).
 * * **Heilen bestrafen**: Wer trinkt oder isst, den holt er mit einem Windschritt ein und unterbricht ihn.
 * * Keine Eiswände und keine Dornenreihen aus Blöcken mehr: Eis ist nur noch sichtbar ([Shards]), nichts versperrt Wege.
 */
class AriusMoves(private val f: EirholmFight) {

    private val rnd = java.util.Random()
    private var nextGuard = 0L
    private var nextLane = 0L
    private var nextGrip = 0L
    private var nextStab = 0L
    private var nextNull = 0L
    private var nextSidestep = 0L
    private var nextBlade = 0L
    private var nextThorns = 0L
    private var nextChain = 0L
    private var nextFeint = 0L
    private var nextReact = 0L
    private var nextHealPunish = 0L

    /** Die gerade allein fliegende Klinge (Item-Anzeige), falls ein Klingenflug läuft. */
    private var flyingBlade: net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity? = null

    /** Läuft gerade eine Attacke (dann keine Seitschritte/Pfeilschnitt-Posen dazwischen)? */
    var acting = false
        private set

    /** Läuft eine Konter-Sequenz (Parade und Bestrafung)? */
    var countering = false
        private set

    private val style = Vfx.Style.HEAVEN

    /** Seit 1.10.0: Abschnitte des Gipfel-Finales (Klingenabsichten, Stille, Letzter Zusammenprall). */
    val summit = Summit(f)

    // --- Haltungen (seit 1.9.0) ----------------------------------------------------------------------------------------

    enum class Stance { FLOW, HEAVEN, SHEATH }

    var stance = Stance.FLOW
        private set
    private var stanceUntil = 0L

    /** Neue Begegnung oder neuer Abschnitt: zurück in die fließende Haltung. */
    fun resetStance(start: Stance = Stance.FLOW, ticks: Int = 260) {
        stance = start
        stanceUntil = f.now + ticks
    }

    /** Darf die Klinge in die Scheide (nur Solvane selbst, nicht die Eisklinge der weißen Form)? */
    private fun canSheath(): Boolean = f.arius?.bladeState == AriusBlade.HELD

    /** Haltungswechsel mit Ansage: Pose, Rune, Klang, Aktionsleiste. */
    private fun shiftStance(next: Stance): Script = script {
        val a = f.arius ?: return@script
        stance = next
        stanceUntil = f.now + 200 + rnd.nextInt(120)
        f.target()?.let { f.face(a, it.entityPos) }
        when (next) {
            Stance.HEAVEN -> { a.sheathed = false; a.play(AriusPose.CUT_CHARGE) }
            Stance.SHEATH -> { a.sheathed = true; a.play(AriusPose.IAI_STANCE) }
            Stance.FLOW -> { a.sheathed = false; a.play(AriusPose.IDLE) }
        }
        f.sound("lead.switch", a.entityPos, 1.8f, if (next == Stance.HEAVEN) 0.7f else 1.2f)
        f.sound(if (next == Stance.SHEATH) "iai.glint" else "arius.guard", a.entityPos, 1.6f, 0.9f)
        Vfx.rune(f.world, if (next == Stance.HEAVEN) Vfx.Style.STORM else style, Vec3d(a.x, f.floorAt(a) + 0.05, a.z), 1.8f, 30, a)
        f.fx(FxKind.REVERSE_WIND, a.entityPos.add(0.0, 1.0, 0.0), a = 5f, life = 22)
        val key = "hha_arius.stance.${next.name.lowercase()}"
        for (p in f.players()) p.sendMessage(Text.translatable(key).formatted(if (next == Stance.HEAVEN) Formatting.GOLD else Formatting.AQUA), true)
        wait(10)
    }

    /** Läuft die Haltung ab? Dann wechseln (nie zweimal dieselbe). */
    private fun nextStance(allowSheath: Boolean): Stance? {
        if (f.now < stanceUntil) return null
        val options = Stance.entries.filter { it != stance && (allowSheath || it != Stance.SHEATH) }
        return options[rnd.nextInt(options.size)]
    }

    // --- Heilen bestrafen (seit 1.9.0) ---------------------------------------------------------------------------------

    /** Wen er gerade beim Heilen erwischt hat (wird beim nächsten freien Moment bestraft). */
    var healTarget: java.util.UUID? = null
        private set

    /** Vom Kampf-Tick: ein Spieler hat angefangen zu trinken/essen. */
    fun noticeHeal(player: ServerPlayerEntity) {
        if (healTarget != null || f.now < nextHealPunish || countering) return
        healTarget = player.uuid
    }

    /** Soll er laufende Beinarbeit abbrechen (Heilen bestrafen, Konter läuft)? */
    private fun interrupted(): Boolean = healTarget != null || countering

    private fun healing(p: ServerPlayerEntity): Boolean = p.isUsingItem && p.activeItem.contains(DataComponentTypes.CONSUMABLE)

    private fun interruptHeal(p: ServerPlayerEntity) {
        val stack = p.activeItem
        p.stopUsingItem()
        if (!stack.isEmpty) p.itemCooldownManager.set(stack, 40)
        p.sendMessage(Text.translatable("hha_arius.msg.heal_punish").formatted(Formatting.RED), true)
        f.sound("transform.shatter", p.entityPos, 1.4f, 1.6f)
        Vfx.flare(f.world, Vfx.Style.STORM, p.entityPos.add(0.0, 1.2, 0.0), 1.2f, 6)
    }

    /**
     * Wer heilt, wird eingeholt: kurze Ansage (eine Bahn auf ihn), Windschritt bis vor ihn, Hieb. Wer noch trinkt oder
     * isst, wird unterbrochen (der Gegenstand ist 2 s gesperrt). Wer rechtzeitig aufhört und ausweicht, lässt ihn ins
     * Leere laufen: dann steht er offen.
     */
    fun healPunish(): Script = script {
        val a = f.arius ?: return@script
        val id = healTarget ?: return@script
        healTarget = null
        nextHealPunish = f.now + 140
        val t = f.players().firstOrNull { it.uuid == id } ?: return@script
        if (!healing(t) || Hold.isHeld(t)) return@script
        acting = true
        try {
            f.face(a, t.entityPos)
            val from = a.entityPos
            val dir = flatDir(from, t.entityPos)
            val dist = sqrt(from.squaredDistanceTo(t.entityPos))
            val end = f.clampToArena(from.add(dir.multiply(minOf(dist + 2.0, 28.0))), 1.0)
            val stop = f.clampToArena(from.add(dir.multiply(maxOf(0.0, minOf(dist - 1.4, 28.0)))), 1.0)
            val line = f.calls.line(from, end, 2.4f, 10, Telegraph.Pace.QUICK)
            a.play(AriusPose.LUNGE, line.ticks)
            f.sound("arius.wind_step", from, 2.4f, 1.5f)
            f.sound("iai.glint", from, 1.6f, 1.4f)
            wait(line.ticks - 3)
            f.fx(FxKind.DASH_STREAK, from, stop, life = 12)
            for (i in 1..3) {
                f.place(a, Vec3d(from.x, f.floorY, from.z).lerp(Vec3d(stop.x, f.floorY, stop.z), i / 3.0))
                yield(Unit)
            }
            f.face(a, t.entityPos)
            f.sound("arius.slash", a.entityPos, 2.4f, 1.2f)
            slash(a.entityPos, f.facing(a), 3.6f)
            var caught = false
            for (p in line.hits(f.players())) {
                if (healing(p)) { interruptHeal(p); caught = true }
                f.hurt(p, 9f)
                dev.henny.hha.logic.Fx.launchPlayer(p, dir.multiply(0.5).add(0.0, 0.3, 0.0))
            }
            if (!caught) {
                f.openArius(26)
                a.play(AriusPose.STAGGER)
                wait(26)
                return@script
            }
            // Nachsetzen: ein angesagter Rückhandhieb.
            val c = f.calls.cone(a.entityPos, f.facing(a), 4.4f, 70f, 10, Telegraph.Pace.FOLLOW_UP)
            a.play(AriusPose.COMBO_2, c.ticks)
            wait(c.ticks)
            f.sound("arius.slash", a.entityPos, 2f, 0.95f)
            for (p in c.hits(f.players())) f.hurt(p, 8f)
            slash(a.entityPos, f.facing(a), 4.4f)
            f.openArius(20)
            wait(20)
        } finally {
            acting = false
        }
    }

    // --- Grundlagen -----------------------------------------------------------------------------------------------------

    private fun walkSpeed(white: Boolean, dist: Double) = when {
        dist > 10 -> if (white) 0.46 else 0.4
        dist > 6 -> if (white) 0.34 else 0.3
        else -> if (white) 0.27 else 0.23
    }

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else d.normalize()
    }

    private fun flatDist(a: Entity, b: Entity): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    /** Geht auf das Ziel zu, bis es näher als [range] ist (höchstens [maxTicks]); bricht ab, wenn jemand heilt. */
    private suspend fun SequenceScope<Unit>.approach(target: ServerPlayerEntity, range: Double, maxTicks: Int, white: Boolean) {
        val a = f.arius ?: return
        a.play(AriusPose.IDLE)
        var n = 0
        while (n++ < maxTicks && target.isAlive && flatDist(a, target) > range) {
            if (healTarget != null && !countering) {
                yieldAll(healPunish())
                a.play(AriusPose.IDLE)
            }
            // Seit 1.10.0: große Lücken schließt er mit einem Windschritt statt minutenlang zu gehen.
            if (n == 6 && flatDist(a, target) > range + 5.0) {
                val from = a.entityPos
                f.sound("arius.wind_step", from, 1.5f, 1.25f)
                a.play(AriusPose.SIDESTEP)
                repeat(5) { f.stepToward(a, target.entityPos, 0.95); yield(Unit) }
                f.fx(FxKind.DASH_STREAK, from, a.entityPos, life = 8)
                a.play(AriusPose.IDLE)
                continue
            }
            f.stepToward(a, target.entityPos, walkSpeed(white, flatDist(a, target)))
            yield(Unit)
        }
        f.face(a, target.entityPos)
    }

    /** Kurze Beinarbeit zwischen Angriffen: halber Kreis um das Ziel auf Abstand (wirkt souverän, nicht hektisch). */
    suspend fun SequenceScope<Unit>.footwork(ticks: Int, white: Boolean) {
        val a = f.arius ?: return
        val t = f.target() ?: return wait(ticks)
        val sign = if (rnd.nextBoolean()) 1.0 else -1.0
        if (stance == Stance.FLOW) a.play(AriusPose.IDLE)
        repeat(ticks) {
            if (interrupted()) {
                waitUntil(220) { !countering }
                return
            }
            val d = flatDist(a, t)
            val around = flatDir(t.entityPos, a.entityPos)
            val tangent = Vec3d(-around.z * sign, 0.0, around.x * sign)
            val want = t.entityPos.add(around.multiply(4.0)).add(tangent.multiply(1.5))
            f.stepToward(a, want, if (white) 0.16 else 0.12, faceIt = false)
            f.face(a, t.entityPos)
            if (d < 2.2) f.stepToward(a, a.entityPos.add(around.multiply(1.0)), 0.1, faceIt = false)
            yield(Unit)
        }
    }

    /**
     * Hiebspur (seit 1.2.2 hochwertiger): weiße Hauptsichel, darüber eine schmale, schnellere Sturmsichel als
     * Nachschwung, Frostfunken entlang der Schneide und ein kurzer Bodenkratzer, wo die Spitze aufsetzt.
     */
    private var slashTilt = 0

    private fun slash(at: Vec3d, dir: Vec3d, range: Float) {
        val mid = at.add(0.0, 1.1, 0.0)
        f.fx(FxKind.SLASH_ARC, mid, dir, a = range, b = listOf(0f, 0.35f, -0.3f)[slashTilt++ % 3].coerceAtLeast(0f), life = 12)
        Vfx.crescent(f.world, style, mid, dir, range, 2.6f, 8)
        Vfx.crescent(f.world, Vfx.Style.STORM, mid.add(0.0, 0.25, 0.0), dir, range * 1.15f, 1.1f, 5)
        val tip = at.add(dir.multiply(range * 0.8))
        f.world.spawnParticles(net.minecraft.particle.ParticleTypes.SNOWFLAKE, tip.x, tip.y + 0.9, tip.z, 10, range * 0.15, 0.3, range * 0.15, 0.12)
        f.world.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, tip.x, tip.y + 1.0, tip.z, 4, range * 0.12, 0.2, range * 0.12, 0.03)
        f.world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK, net.minecraft.block.Blocks.SNOW_BLOCK.defaultState), tip.x, f.floorY + 0.1, tip.z, 8, 0.4, 0.05, 0.4, 0.1)
    }

    // --- Zug wählen ----------------------------------------------------------------------------------------------------

    /** Ein Zug in Abschnitt I (Rauhreif-Form). */
    fun turnTemple(): Script = script {
        val a = f.arius ?: return@script
        if (countering) return@script waitUntil(220) { !countering }
        if (healTarget != null) {
            yieldAll(healPunish())
            return@script
        }
        val t = f.target() ?: return@script wait(10)
        val hpFrac = f.ariusHp / f.ariusMax
        val unlocked = hpFrac <= 0.7f
        val serious = hpFrac <= 0.4f
        // Ab 85 % wechselt er die Haltung.
        if (hpFrac <= 0.85f) nextStance(allowSheath = canSheath())?.let { yieldAll(shiftStance(it)) }
        val d = flatDist(a, t)
        val now = f.now
        acting = true
        try {
            when (stance) {
                Stance.HEAVEN -> yieldAll(heavenTurn(t, false, serious))
                Stance.SHEATH -> yieldAll(sheathTurn(t, false, serious))
                Stance.FLOW -> when {
                    f.adapt == EirholmFight.Adapt.ARROWS && d > 6 -> {
                        yieldAll(arrowWalk(t))
                        yieldAll(chain(false, if (serious) 6 else 4))
                    }
                    now >= nextChain && d < 10 && rnd.nextFloat() < 0.4f -> {
                        nextChain = now + 260
                        yieldAll(chain(false, if (serious) 5 + rnd.nextInt(3) else 4 + rnd.nextInt(2)))
                    }
                    now >= nextLotus && d < 9 && rnd.nextFloat() < 0.3f -> {
                        nextLotus = now + 420
                        yieldAll(frostLotus(false))
                    }
                    now >= nextFeint && d < 9 && rnd.nextFloat() < 0.35f -> {
                        nextFeint = now + 300
                        yieldAll(feint(false))
                    }
                    now >= nextGuard && d < 7 && rnd.nextFloat() < 0.35f -> {
                        nextGuard = now + 360
                        yieldAll(guard(false, serious))
                    }
                    now >= nextStepChain && d in 5.0..18.0 && rnd.nextFloat() < 0.35f -> {
                        nextStepChain = now + 300
                        yieldAll(stepChain(false))
                    }
                    now >= nextJavelin && d in 7.0..22.0 && rnd.nextFloat() < 0.4f -> {
                        nextJavelin = now + 340
                        yieldAll(iceJavelin(false))
                    }
                    unlocked && now >= nextThorns && d in 4.0..20.0 && rnd.nextFloat() < 0.4f -> {
                        nextThorns = now + 400
                        yieldAll(frostThorns(false))
                    }
                    unlocked && now >= nextBlade && d in 5.0..18.0 && rnd.nextFloat() < 0.35f -> {
                        nextBlade = now + 560
                        yieldAll(flyingBladeAttack(false))
                    }
                    unlocked && now >= nextLane && rnd.nextFloat() < 0.4f -> {
                        nextLane = now + 520
                        yieldAll(frostLane(serious))
                    }
                    unlocked && d in 4.0..10.0 && now >= nextGrip && evading(t) -> {
                        nextGrip = now + 360
                        yieldAll(grip(t))
                    }
                    unlocked && d > 9 && now >= nextStab -> {
                        nextStab = now + 320
                        yieldAll(stabGround())
                    }
                    d in 6.0..16.0 -> yieldAll(lunge(t, false))
                    else -> yieldAll(combo(false, serious))
                }
            }
        } finally {
            acting = false
        }
        yieldAll(script { footwork(if (serious) 5 else 9, false) })
    }

    /** Himmelshaltung: schwere, verzögerte Hiebe von oben, Himmelssprung, Gletscherstoß. */
    private fun heavenTurn(t: ServerPlayerEntity, white: Boolean, serious: Boolean): Script = script {
        val a = f.arius ?: return@script
        val d = flatDist(a, t)
        when {
            d > 12 && rnd.nextFloat() < 0.6f -> yieldAll(skyLeap(white))
            rnd.nextFloat() < 0.22f -> yieldAll(stabGround())
            else -> yieldAll(heavenCleave(white, serious))
        }
        if (stance == Stance.HEAVEN) a.play(AriusPose.CUT_CHARGE)
    }

    /** Scheidenhaltung: Iai-Züge mit wechselndem Zeitpunkt, Konterhaltung, Windschritt-Kette. */
    private fun sheathTurn(t: ServerPlayerEntity, white: Boolean, serious: Boolean): Script = script {
        val a = f.arius ?: return@script
        val d = flatDist(a, t)
        when {
            d < 6 && f.now >= nextGuard && rnd.nextFloat() < 0.45f -> {
                nextGuard = f.now + 260
                yieldAll(iaiCounter(white))
            }
            d in 5.0..18.0 && rnd.nextFloat() < 0.25f -> yieldAll(stepChain(white))
            else -> yieldAll(iaiDraw(white, if (serious) 3 else 2))
        }
        if (stance == Stance.SHEATH) {
            a.sheathed = true
            a.play(AriusPose.IAI_STANCE)
        }
    }

    /** Ein Zug als Taktgeber in Abschnitt III (weiße Form). */
    private var nextStar = 0L
    private var nextSoul = 0L

    /**
     * Feat „Der gefrorene Stern“: Er hebt die Hand, hoch über dem Ziel entsteht ein gleißender Stern, gefriert und
     * stürzt herab. Großer angesagter Kreis (r 9), 14 Grundschaden und starker Frostaufbau, danach ein Dornenring.
     */
    fun frozenStar(t: ServerPlayerEntity): Script = script {
        val a = f.arius ?: return@script
        f.face(a, t.entityPos)
        a.play(AriusPose.CORE_RAISE)
        val spot = Vec3d(t.x, f.floorY, t.z)
        val ticks = 60
        for (p in f.audience()) dev.henny.hha.logic.boss.Cine.title(listOf(p), "hha_arius.feat.frozen_star", "hha_arius.feat.frozen_star.sub", 50)
        f.fx(FxKind.FROZEN_STAR, spot, a = 9f, b = ticks.toFloat(), life = ticks + 40)
        f.ambient("core.form", spot.add(0.0, 30.0, 0.0), 5f, 0.6f)
        val ring = f.calls.ring(spot, 9f, ticks)
        wait(ring.ticks)
        for (p in ring.hits(f.players())) {
            if (f.hurt(p, 14f, Damage.Kind.IMPACT) > 0f) dev.henny.hha_arius.gear.Frostbite.add(p, 45f)
        }
        f.ambient("glacier.collapse", spot, 6f, 1.2f)
        Vfx.shockwave(f.world, Vfx.Style.HEAVEN, spot, 12f, 18, 2f)
        f.fx(FxKind.CLEAR_WAVE, spot, a = 14f, life = 20)
        f.calls.roomShake(spot, 6f, 40f)
        for (k in 0 until 12) {
            val ang = k / 12.0 * Math.PI * 2
            spike(spot.add(cos(ang) * 8.0, 0.0, sin(ang) * 8.0), 2 + rnd.nextInt(2), 40)
        }
        a.play(AriusPose.IDLE)
        wait(10)
    }

    /**
     * Feat „Absoluter Nullpunkt: Seele“: Er friert die Seele ein, nicht den Körper – Schilde und Rüstung helfen nicht.
     * Ein schmaler Kegel vor ihm; wer darin steht, erfriert sofort (voller Frostaufbau), dazu wenig Schaden.
     */
    fun absoluteZeroSoul(t: ServerPlayerEntity): Script = script {
        val a = f.arius ?: return@script
        f.face(a, t.entityPos)
        a.play(AriusPose.GUARD)
        for (p in f.audience()) dev.henny.hha.logic.boss.Cine.title(listOf(p), "hha_arius.feat.zero_soul", "hha_arius.feat.zero_soul.sub", 45)
        val from = a.entityPos
        val dir = flatDir(from, t.entityPos)
        val to = from.add(dir.multiply(14.0))
        val line = f.calls.line(from, to, 5f, 32, Telegraph.Pace.HEAVY)
        f.ambient("cut.charge", from, 3f, 0.5f)
        wait(line.ticks)
        a.play(AriusPose.IAI_DRAW, 1)
        f.fx(FxKind.SEAM, from.add(0.0, 0.2, 0.0), to.add(0.0, 0.2, 0.0), a = 4f, life = 20)
        Vfx.crescent(f.world, Vfx.Style.HEAVEN, from.add(0.0, 1.2, 0.0), dir, 14f, 2.4f, 8)
        for (p in line.hits(f.players())) {
            f.hurt(p, 4f, Damage.Kind.NULL_CUT)
            dev.henny.hha_arius.gear.Frostbite.add(p, 100f)
        }
        wait(12)
        a.play(AriusPose.IDLE)
    }

    fun turnWhite(): Script = script {
        val a = f.arius ?: return@script
        if (countering) return@script waitUntil(220) { !countering }
        if (healTarget != null) {
            yieldAll(healPunish())
            return@script
        }
        val t = f.target() ?: return@script wait(10)
        nextStance(allowSheath = false)?.let { yieldAll(shiftStance(it)) }
        val d = flatDist(a, t)
        val now = f.now
        acting = true
        try {
            if (stance == Stance.HEAVEN) {
                yieldAll(heavenTurn(t, true, true))
            } else when {
                now >= nextChain && d < 10 && rnd.nextFloat() < 0.4f -> {
                    nextChain = now + 240
                    yieldAll(chain(true, 5 + rnd.nextInt(3)))
                }
                now >= nextNull && rnd.nextFloat() < 0.35f -> {
                    nextNull = now + 300
                    yieldAll(nullCut(t))
                }
                // Seit 1.7.0 zwei seiner Feats als Angriffe der weißen Form
                now >= nextStar && rnd.nextFloat() < 0.3f -> {
                    nextStar = now + 520
                    yieldAll(frozenStar(t))
                }
                now >= nextSoul && rnd.nextFloat() < 0.3f -> {
                    nextSoul = now + 460
                    yieldAll(absoluteZeroSoul(t))
                }
                now >= nextFeint && d < 9 && rnd.nextFloat() < 0.35f -> {
                    nextFeint = now + 280
                    yieldAll(feint(true))
                }
                now >= nextBlade && rnd.nextFloat() < 0.4f -> {
                    nextBlade = now + 420
                    yieldAll(flyingBladeAttack(true))
                }
                now >= nextThorns && rnd.nextFloat() < 0.45f -> {
                    nextThorns = now + 320
                    yieldAll(frostThorns(true))
                }
                now >= nextGuard && d < 7 && rnd.nextFloat() < 0.35f -> {
                    nextGuard = now + 320
                    yieldAll(guard(true, false))
                }
                now >= nextStepChain && d in 5.0..18.0 && rnd.nextFloat() < 0.35f -> {
                    nextStepChain = now + 260
                    yieldAll(stepChain(true))
                }
                now >= nextSky && rnd.nextFloat() < 0.25f -> {
                    nextSky = now + 420
                    yieldAll(skyLeap(true))
                }
                now >= nextLotus && d < 9 && rnd.nextFloat() < 0.3f -> {
                    nextLotus = now + 400
                    yieldAll(frostLotus(true))
                }
                d > 6 -> yieldAll(lunge(t, true))
                else -> yieldAll(combo(true, false))
            }
        } finally {
            acting = false
        }
        yieldAll(script { footwork(4, true) })
    }

    /** Ein Zug ohne Nivaris (sie ist niedergerungen): weißes Moveset, etwas mehr Pausen. */
    fun turnWhiteSolo(): Script = script {
        yieldAll(turnWhite())
        yieldAll(script { footwork(6, true) })
    }

    /**
     * Ein Zug im Finale (seit 1.9.0 ein echter Kampf statt der drei Austausche): alle drei Haltungen, Iai-Züge aus der
     * Scheide, lange Ketten, Finten, Konter. [desperate] = unter 50 %: schnellere Wechsel, längere Ketten.
     */
    fun turnFinale(desperate: Boolean): Script = script {
        val a = f.arius ?: return@script
        if (countering) return@script waitUntil(220) { !countering }
        // Seit 1.10.0: die neuen Gipfel-Abschnitte (Klingenabsichten, Stille, Letzter Zusammenprall).
        summit.next()?.let { yieldAll(it); return@script }
        if (healTarget != null) {
            yieldAll(healPunish())
            return@script
        }
        val t = f.target() ?: return@script wait(10)
        nextStance(allowSheath = canSheath())?.let {
            yieldAll(shiftStance(it))
            if (desperate) stanceUntil -= 80
        }
        val d = flatDist(a, t)
        val now = f.now
        acting = true
        try {
            when (stance) {
                Stance.SHEATH -> yieldAll(sheathTurn(t, false, desperate))
                Stance.HEAVEN -> yieldAll(heavenTurn(t, false, desperate))
                Stance.FLOW -> when {
                    now >= nextChain && d < 10 && rnd.nextFloat() < 0.45f -> {
                        nextChain = now + 220
                        yieldAll(chain(false, if (desperate) 6 + rnd.nextInt(3) else 5 + rnd.nextInt(2)))
                    }
                    now >= nextFeint && d < 9 && rnd.nextFloat() < 0.4f -> {
                        nextFeint = now + 240
                        yieldAll(feint(false))
                    }
                    now >= nextGuard && d < 7 && rnd.nextFloat() < 0.35f -> {
                        nextGuard = now + 300
                        yieldAll(guard(false, desperate))
                    }
                    now >= nextNull && rnd.nextFloat() < 0.3f -> {
                        nextNull = now + 320
                        yieldAll(nullCut(t))
                    }
                    now >= nextStepChain && d in 5.0..18.0 && rnd.nextFloat() < 0.4f -> {
                        nextStepChain = now + 260
                        yieldAll(stepChain(false))
                    }
                    now >= nextLotus && d < 9 && rnd.nextFloat() < 0.3f -> {
                        nextLotus = now + 400
                        yieldAll(frostLotus(false))
                    }
                    d > 6 -> yieldAll(lunge(t, desperate))
                    else -> yieldAll(combo(false, true))
                }
            }
        } finally {
            acting = false
        }
        yieldAll(script { footwork(if (desperate) 3 else 6, false) })
    }

    /**
     * Begleitung, während Nivaris führt: ein einzelner Hieb gegen einen Spieler ohne laufende Boss-Ansage, sonst
     * Stellungswechsel in die Flanke. Wer heilt, wird auch jetzt eingeholt.
     */
    fun support(): Script = script {
        val a = f.arius ?: return@script
        if (countering) return@script waitUntil(220) { !countering }
        if (healTarget != null) {
            yieldAll(healPunish())
            return@script
        }
        val victim = f.players().firstOrNull { flatDist(a, it) < 4.2 && !f.calls.hasBossTelegraph(it) && !Hold.isHeld(it) && !Hold.inGrace(it) }
        acting = true
        try {
            if (victim != null) {
                f.face(a, victim.entityPos)
                val dir = f.facing(a)
                val cone = f.calls.cone(a.entityPos, dir, 4.2f, 50f, 14, players = listOf(victim))
                a.play(AriusPose.COMBO_1, cone.ticks)
                f.sound("arius.slash", a.entityPos, 1.6f)
                wait(cone.ticks)
                for (p in cone.hits(f.players())) f.hurt(p, 9f)
                slash(a.entityPos, dir, 4.2f)
                wait(6)
            } else {
                val t = f.target() ?: return@script wait(10)
                val dragon = f.nivaris
                val ref = dragon?.entityPos ?: f.arenaCenter
                val away = flatDir(ref, t.entityPos)
                val flank = f.clampToArena(t.entityPos.add(Vec3d(-away.z, 0.0, away.x).multiply(6.0)), 2.0)
                var n = 0
                while (n++ < 24 && !f.stepToward(a, flank, 0.34)) yield(Unit)
                f.face(a, t.entityPos)
                // Seit 1.10.0: aus der Flanke ein schneller Windschritt-Hieb, der den Ausweichweg vor Solvane abschneidet.
                if (!f.calls.hasBossTelegraph(t) && !Hold.isHeld(t) && !Hold.inGrace(t) && flatDist(a, t) in 3.0..12.0) {
                    yieldAll(lungeOnce(t, 12, Telegraph.Pace.FOLLOW_UP, last = false))
                    f.openArius(12)
                }
                wait(4)
            }
        } finally {
            acting = false
        }
    }

    // --- Seit 1.9.0: Wukong-Elemente ------------------------------------------------------------------------------------

    /**
     * Lange Kette: 4–8 Glieder aus Hieben links/rechts, Drehschnitt, Stoß, Windschritt und Aufwärtsschnitt in wechselndem
     * Rhythmus. Meist schnelle Folgeschläge, dazwischen ein **verzögerter** Schlag (lange Ansage, er hält die Klinge
     * oben), am Ende ein schwerer Abschluss und ein langes Fenster.
     */
    fun chain(white: Boolean, length: Int): Script = script {
        val a = f.arius ?: return@script
        var t = f.target() ?: return@script
        yieldAll(script { approach(t, 3.8, 50, white) })
        var last = -1
        for (k in 0 until length) {
            t = f.target() ?: break
            if (flatDist(a, t) > 5.5) {
                f.sound("arius.wind_step", a.entityPos, 1.4f, 1.3f)
                val from = a.entityPos
                repeat(5) { f.stepToward(a, t.entityPos, 0.8); yield(Unit) }
                f.fx(FxKind.DASH_STREAK, from, a.entityPos, life = 8)
            }
            f.face(a, t.entityPos)
            val dir = f.facing(a)
            val finisher = k == length - 1
            val delayed = !finisher && k > 0 && rnd.nextFloat() < 0.22f
            var link = if (finisher) 6 + rnd.nextInt(2) else rnd.nextInt(6)
            if (link == last) link = (link + 1) % 6
            last = link
            val windup = when {
                k == 0 -> 14
                finisher -> 18
                delayed -> 18 + rnd.nextInt(5)
                else -> 8 + rnd.nextInt(3)
            }
            val pace = if (k == 0 || delayed || finisher) Telegraph.Pace.HEAVY else Telegraph.Pace.FOLLOW_UP
            val dmg = (if (white) 8.5f else 7.5f) + if (finisher) 4f else 0f
            when (link) {
                0, 1 -> {
                    val c = f.calls.cone(a.entityPos, dir, 4.6f, 60f, windup, pace)
                    a.play(if (link == 0) AriusPose.COMBO_1 else AriusPose.COMBO_2, c.ticks)
                    wait(c.ticks - 2)
                    repeat(2) { f.stepToward(a, a.entityPos.add(dir), 0.3, faceIt = false); yield(Unit) }
                    f.sound("arius.slash", a.entityPos, 2f, 1.0f + k * 0.05f)
                    for (p in c.hits(f.players())) f.hurt(p, dmg)
                    slash(a.entityPos, dir, 4.6f)
                }
                2 -> {
                    val r = f.calls.ring(a.entityPos, 3.8f, windup, pace = pace)
                    a.play(AriusPose.SPIN_CUT, r.ticks)
                    wait(r.ticks)
                    f.sound("arius.slash", a.entityPos, 2f, 0.85f)
                    for (p in r.hits(f.players())) f.hurt(p, dmg)
                    Vfx.shockwave(f.world, style, a.entityPos, 3.8f, 10, 1.2f)
                    f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.0, 0.0), dir, a = 3.8f, b = 0f, life = 10)
                }
                3 -> {
                    val from = a.entityPos
                    val line = f.calls.line(from, f.clampToArena(from.add(dir.multiply(7.0)), 1.0), 2f, windup, pace)
                    a.play(AriusPose.COMBO_3, line.ticks)
                    wait(line.ticks - 2)
                    repeat(2) { f.stepToward(a, a.entityPos.add(dir), 0.5, faceIt = false); yield(Unit) }
                    f.sound("arius.thrust", a.entityPos, 2.2f)
                    for (p in line.hits(f.players())) f.hurt(p, dmg + 1f)
                    Vfx.crescent(f.world, style, a.entityPos.add(0.0, 1.2, 0.0), dir, 6f, 1.2f, 7)
                }
                4 -> {
                    val from = a.entityPos
                    val to = f.clampToArena(from.add(dir.multiply(7.5)), 1.0)
                    val line = f.calls.line(from, to, 2.2f, windup, pace)
                    a.play(AriusPose.LUNGE, line.ticks)
                    f.sound("arius.wind_step", from, 1.6f, 1.1f)
                    wait(line.ticks)
                    f.fx(FxKind.DASH_STREAK, from, to, life = 10)
                    for (p in line.hits(f.players())) f.hurt(p, dmg)
                    var n = 0
                    while (n++ < 6 && !f.stepToward(a, to, AriusTimings.LUNGE_SPEED, faceIt = false)) yield(Unit)
                    f.sound("arius.slash", a.entityPos, 2f, 1.15f)
                }
                5 -> {
                    val c = f.calls.cone(a.entityPos, dir, 3.8f, 70f, windup, pace)
                    a.play(AriusPose.LAUNCH, c.ticks)
                    wait(c.ticks)
                    f.sound("arius.slash", a.entityPos, 2f, 1.3f)
                    for (p in c.hits(f.players())) {
                        f.hurt(p, dmg)
                        dev.henny.hha.logic.Fx.launchPlayer(p, dir.multiply(0.25).add(0.0, 0.75, 0.0))
                    }
                    f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.0, 0.0), dir, a = 3.8f, b = 1f, life = 10)
                }
                6 -> {
                    // Abschluss: Gletscherstoß im Sprung auf das Ziel.
                    val spot = f.clampToArena(Vec3d(t.x, f.floorY, t.z), 1.0)
                    val ring = f.calls.ring(spot, 3.8f, windup, pace = Telegraph.Pace.HEAVY)
                    a.play(AriusPose.STAB_GROUND, ring.ticks)
                    val from = a.entityPos
                    for (i in 1..ring.ticks) {
                        val kk = i / ring.ticks.toDouble()
                        f.place(a, from.lerp(spot, kk * kk).add(0.0, sin(kk * Math.PI) * 2.2, 0.0))
                        yield(Unit)
                    }
                    f.place(a, spot)
                    f.sound("arius.stab_ground", spot, 2.6f, 0.9f)
                    f.calls.roomShake(spot, 3f, 24f)
                    Vfx.shockwave(f.world, style, spot, 4.5f, 12, 1.6f)
                    for (p in ring.hits(f.players())) {
                        f.hurt(p, dmg, Damage.Kind.IMPACT)
                        dev.henny.hha.logic.Fx.launchPlayer(p, flatDir(spot, p.entityPos).multiply(0.7).add(0.0, 0.45, 0.0))
                    }
                    for (q in 0 until 8) spike(spot.add(cos(q * Math.PI / 4) * 3.2, 0.0, sin(q * Math.PI / 4) * 3.2), 2, 30)
                }
                else -> {
                    // Abschluss: schwerer Rückwärtsschnitt nach einem Windschritt hinter das Ziel.
                    val behind = f.clampToArena(t.entityPos.add(flatDir(a.entityPos, t.entityPos).multiply(2.2)), 1.0)
                    f.sound("arius.wind_step", a.entityPos, 2f)
                    val from = a.entityPos
                    repeat(4) { f.stepToward(a, behind, 1.0, faceIt = false); yield(Unit) }
                    f.fx(FxKind.DASH_STREAK, from, a.entityPos, life = 10)
                    f.face(a, t.entityPos)
                    val d2 = f.facing(a)
                    val c = f.calls.cone(a.entityPos, d2, 4.4f, 80f, windup, Telegraph.Pace.HEAVY)
                    a.play(AriusPose.COMBO_4, c.ticks)
                    wait(c.ticks)
                    f.sound("arius.slash", a.entityPos, 2.4f, 0.8f)
                    for (p in c.hits(f.players())) f.hurt(p, dmg)
                    slash(a.entityPos, d2, 4.4f)
                }
            }
            if (delayed) f.sound("iai.glint", a.entityPos, 1.2f, 0.7f)
            wait(if (finisher) 0 else 1)
        }
        f.openArius(24)
        wait(22)
    }

    /**
     * Finte: Er holt angesagt zum Stoß aus – und bricht ihn halb ab. Seitschritt, und aus der Drehung kommt der echte
     * Schlag (Drehschnitt oder flacher Fegeschnitt), schnell angesagt. Wer früh ausweicht, läuft hinein.
     */
    fun feint(white: Boolean): Script = script {
        val a = f.arius ?: return@script
        val t = f.target() ?: return@script
        yieldAll(script { approach(t, 4.6, 40, white) })
        f.face(a, t.entityPos)
        val from = a.entityPos
        val dir = f.facing(a)
        val group = f.calls.boss()
        group.line(from, f.clampToArena(from.add(dir.multiply(7.0)), 1.0), 2f, 20, Telegraph.Pace.HEAVY)
        a.play(AriusPose.COMBO_3, 20)
        f.sound("arius.guard", from, 1.4f, 1.2f)
        wait(10)
        group.end()
        // Abgebrochen: Seitschritt aus der Linie.
        val sign = if (rnd.nextBoolean()) 1 else -1
        a.sideSign = sign
        a.play(AriusPose.SIDESTEP)
        f.sound("arius.wind_step", a.entityPos, 1.6f, 1.5f)
        val side = Vec3d(-dir.z * sign, 0.0, dir.x * sign)
        val goal = f.clampToArena(a.entityPos.add(side.multiply(2.2)).add(dir.multiply(1.0)), 1.0)
        repeat(5) { f.stepToward(a, goal, 0.5, faceIt = false); f.face(a, t.entityPos); yield(Unit) }
        if (rnd.nextBoolean()) {
            val ring = f.calls.ring(a.entityPos, 4f, 10, pace = Telegraph.Pace.FOLLOW_UP)
            a.play(AriusPose.SPIN_CUT, ring.ticks)
            wait(ring.ticks)
            f.sound("arius.slash", a.entityPos, 2.2f, 0.9f)
            for (p in ring.hits(f.players())) f.hurt(p, if (white) 11f else 10f)
            Vfx.shockwave(f.world, style, a.entityPos, 4f, 10, 1.2f)
            f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.0, 0.0), dir, a = 4f, b = 0f, life = 10)
        } else {
            val d2 = flatDir(a.entityPos, t.entityPos)
            val cone = f.calls.cone(a.entityPos, d2, 5.2f, 110f, 12, Telegraph.Pace.FOLLOW_UP)
            a.play(AriusPose.STRIP_SLASH, cone.ticks)
            wait(cone.ticks)
            f.sound("arius.slash", a.entityPos, 2.2f, 0.75f)
            for (p in cone.hits(f.players())) if (f.grounded(p)) f.hurt(p, if (white) 11f else 10f)
            Vfx.crescent(f.world, style, a.entityPos.add(0.0, 0.4, 0.0), d2, 5.2f, 3f, 8)
        }
        f.openArius(20)
        wait(18)
    }

    /**
     * Himmelshieb (Himmelshaltung): Die Klinge steht hoch über der Schulter; der Hieb kommt nach einem von drei
     * Zeitpunkten (die Ansage zeigt den wahren). Eine Schockwelle läuft die Linie entlang. Ernst: ein zweiter Hieb mit
     * dem umgekehrten Rhythmus.
     */
    fun heavenCleave(white: Boolean, serious: Boolean): Script = script {
        val a = f.arius ?: return@script
        var t = f.target() ?: return@script
        yieldAll(script { approach(t, 5.5, 40, white) })
        val delays = intArrayOf(12, 18, 26)
        var pick = rnd.nextInt(3)
        val swings = if (serious) 2 else 1
        for (k in 0 until swings) {
            t = f.target() ?: break
            f.face(a, t.entityPos)
            val from = a.entityPos
            val dir = f.facing(a)
            val to = f.clampToArena(from.add(dir.multiply(13.0)), 0.5)
            val windup = delays[pick]
            val group = f.calls.boss()
            val line = group.line(from, to, 3.2f, windup, Telegraph.Pace.HEAVY)
            val near = group.cone(from, dir, 3.6f, 80f, windup, Telegraph.Pace.HEAVY)
            a.play(AriusPose.CUT_CHARGE)
            f.sound("cut.charge", from, 1.6f, 1.3f)
            f.fx(FxKind.EDGE, from, life = windup, e1 = a)
            wait((windup - 6).coerceAtLeast(0))
            a.play(AriusPose.CUT_RELEASE, 6)
            wait(line.remaining.coerceAtLeast(0))
            f.sound("cut.strike", from, 2.6f, 1.2f)
            f.sound("arius.stab_ground", to, 2.2f, 0.7f)
            f.calls.roomShake(from, 4f, 26f)
            f.fx(FxKind.SEAM, from.add(0.0, 0.15, 0.0), to.add(0.0, 0.15, 0.0), a = 2.5f, life = 18)
            f.fx(FxKind.SLASH_ARC, from.add(dir.multiply(2.0)).add(0.0, 1.4, 0.0), dir, a = 5f, b = 1f, life = 12)
            for (i in 1..4) f.fx(FxKind.CLEAR_WAVE, from.lerp(to, i / 4.0), a = 2.5f, life = 12)
            val hit = (line.hits(f.players()) + near.hits(f.players())).distinct()
            for (p in hit) {
                f.hurt(p, if (white) 14f else 13f, Damage.Kind.IMPACT)
                dev.henny.hha_arius.gear.Frostbite.add(p, 15f)
            }
            for (i in 2..8 step 2) spike(from.lerp(to, i / 9.0).add(Vec3d(-dir.z, 0.0, dir.x).multiply(if (i % 4 == 0) 1.2 else -1.2)), 2, 26)
            // Der zweite Hieb kommt mit dem umgekehrten Rhythmus (früh nach spät, spät nach früh).
            pick = 2 - pick
            wait(if (k < swings - 1) 6 else 0)
        }
        a.play(AriusPose.STAGGER)
        f.openArius(24)
        wait(20)
    }

    /**
     * Iai-Zug aus der Scheidenhaltung: [count] Züge, jeder eine lange Bahn durch das Ziel; der Zeitpunkt wechselt
     * zwischen schnell und verzögert (die Ansage zeigt ihn), er gleitet durch die Bahn und steckt die Klinge zurück.
     */
    fun iaiDraw(white: Boolean, count: Int): Script = script {
        val a = f.arius ?: return@script
        for (k in 0 until count) {
            val t = f.target() ?: break
            f.face(a, t.entityPos)
            a.sheathed = canSheath()
            a.play(AriusPose.IAI_STANCE)
            val from = a.entityPos
            val dir = flatDir(from, t.entityPos)
            val dist = sqrt(from.squaredDistanceTo(t.entityPos))
            val to = f.clampToArena(from.add(dir.multiply(dist + 4.0)), 1.0)
            val windup = if (rnd.nextFloat() < 0.4f) 22 + rnd.nextInt(6) else 12 + rnd.nextInt(4)
            val line = f.calls.line(from, to, 1.8f, windup, Telegraph.Pace.HEAVY)
            f.sound("iai.glint", from, 2.2f, if (windup > 24) 0.7f else 1.3f)
            wait(line.ticks)
            a.sheathed = false
            a.play(AriusPose.IAI_DRAW, 1)
            f.sound("iai.draw", from, 3f)
            val struck = line.hits(f.players())
            for (i in 1..4) {
                f.place(a, Vec3d(from.x, f.floorY, from.z).lerp(Vec3d(to.x, f.floorY, to.z), i / 4.0))
                yield(Unit)
            }
            f.fx(FxKind.DASH_STREAK, from, to, life = 10)
            f.fx(FxKind.SEAM, from.add(0.0, 1.1, 0.0), to.add(0.0, 1.1, 0.0), a = 1.2f, life = 12)
            Vfx.crescent(f.world, Vfx.Style.HEAVEN, from.add(0.0, 1.1, 0.0), dir, sqrt(from.squaredDistanceTo(to)).toFloat(), 1.2f, 6)
            for (p in struck) f.hurt(p, if (white) 13f else 12f, Damage.Kind.NULL_CUT)
            wait(8)
            f.face(a, (f.target() ?: t).entityPos)
            if (canSheath()) a.sheathed = true
            a.play(AriusPose.IAI_STANCE)
            f.sound("arius.guard", a.entityPos, 1.2f, 1.5f)
            wait(3)
        }
        f.openArius(20)
        wait(18)
    }

    /**
     * Konter aus der Scheidenhaltung: Er wartet, die Hand am Griff. Wer zuschlägt, wird mit einem Iai-Zug pariert und
     * bestraft ([counter]). Wer nicht anbeißt, bekommt den Zug trotzdem – verzögert und weit.
     */
    fun iaiCounter(white: Boolean): Script = script {
        val a = f.arius ?: return@script
        f.target()?.let { f.face(a, it.entityPos) }
        a.sheathed = canSheath()
        a.play(AriusPose.IAI_STANCE)
        val ticks = if (white) AriusTimings.GUARD_TICKS_WHITE + 8 else AriusTimings.GUARD_TICKS + 8
        f.guardUntil = f.now + ticks
        f.guardWhite = white
        f.guardTriggered = false
        Vfx.rune(f.world, style, Vec3d(a.x, f.floorAt(a) + 0.05, a.z), 1.6f, ticks, a)
        f.sound("iai.glint", a.entityPos, 1.8f, 0.8f)
        waitUntil(ticks) { f.guardTriggered }
        f.guardUntil = 0
        if (f.guardTriggered) {
            waitUntil(220) { !countering }
            return@script
        }
        yieldAll(iaiDraw(white, 1))
    }

    // --- Seit 1.8.0: Angriffe in Abschnitt I ---------------------------------------------------------------------------

    private var nextLotus = 0L
    private var nextStepChain = 0L
    private var nextJavelin = 0L
    private var nextSky = 0L

    /**
     * Frostlotus: Er rammt die Klinge in den Boden, sechs Blütenblätter aus Frost schießen nacheinander sternförmig aus
     * ihm heraus (Linien, im Uhrzeigersinn), zum Schluss blüht die Mitte auf (Ring).
     */
    fun frostLotus(white: Boolean): Script = script {
        val a = f.arius ?: return@script
        val c = a.entityPos
        val base = rnd.nextDouble() * Math.PI * 2
        a.play(AriusPose.STAB_GROUND, 16)
        f.sound("arius.guard", c, 1.6f, 0.6f)
        val group = f.calls.boss()
        val petals = (0 until 6).map { k ->
            val ang = base + k * Math.PI / 3
            val dir = Vec3d(sin(ang), 0.0, cos(ang))
            val to = f.clampToArena(c.add(dir.multiply(11.0)), 0.5)
            Triple(group.line(c, to, 1.8f, 16 + k * 4, Telegraph.Pace.HEAVY), dir, to)
        }
        val bloom = group.ring(c, 3.2f, 16 + 6 * 4 + 6)
        var elapsed = 0
        for ((line, dir, to) in petals) {
            wait((line.ticks - elapsed).coerceAtLeast(0))
            elapsed = line.ticks
            f.sound("arius.frost_strip", c, 1.6f, 1.1f + elapsed * 0.004f)
            f.fx(FxKind.SEAM, c.add(0.0, 0.1, 0.0), to.add(0.0, 0.1, 0.0), a = 1.2f, life = 14)
            f.fx(FxKind.SLASH_ARC, c.add(dir.multiply(5.0)).add(0.0, 0.4, 0.0), dir, a = 4f, b = 0.9f, life = 10)
            for (p in line.hits(f.players())) f.hurt(p, if (white) 10f else 9f)
            spike(c.add(dir.multiply(4.0)), 1, 30)
            spike(c.add(dir.multiply(8.0)), 2, 30)
        }
        wait((bloom.ticks - elapsed).coerceAtLeast(0))
        f.sound("arius.stab_ground", c, 2.4f, 1.1f)
        Vfx.nova(f.world, style, c.add(0.0, 0.3, 0.0), 3.4f, 12)
        for (p in bloom.hits(f.players())) {
            f.hurt(p, 8f, Damage.Kind.IMPACT)
            dev.henny.hha.logic.Fx.launchPlayer(p, flatDir(c, p.entityPos).multiply(0.7).add(0.0, 0.5, 0.0))
        }
        f.openArius(24)
        wait(24)
    }

    /** Windschritt-Kette: drei blitzschnelle Schritte im Zickzack auf das Ziel zu, bei jedem ein Hieb. */
    fun stepChain(white: Boolean): Script = script {
        val a = f.arius ?: return@script
        if (stance == Stance.SHEATH) a.sheathed = false
        for (k in 0 until 3) {
            val t = f.target() ?: break
            val from = a.entityPos
            val toward = flatDir(from, t.entityPos)
            val side = Vec3d(-toward.z, 0.0, toward.x).multiply(if (k % 2 == 0) 1.0 else -1.0)
            val dist = sqrt(from.squaredDistanceTo(t.entityPos))
            val to = f.clampToArena(from.add(toward.multiply(minOf(dist + 1.5, 9.0))).add(side.multiply(2.0)), 1.0)
            val line = f.calls.line(from, to, 2f, if (k == 0) 16 else 9, if (k == 0) Telegraph.Pace.HEAVY else Telegraph.Pace.FOLLOW_UP)
            a.play(AriusPose.LUNGE, line.ticks)
            f.sound("arius.wind_step", from, 1.8f, 1.1f + k * 0.1f)
            wait(line.ticks)
            f.fx(FxKind.DASH_STREAK, from, to, life = 12)
            f.place(a, Vec3d(to.x, f.floorY, to.z))
            f.face(a, t.entityPos)
            f.sound("arius.slash", to, 2.2f, 1.0f + k * 0.12f)
            for (p in line.hits(f.players())) f.hurt(p, if (white) 9f else 8f)
            slash(to, flatDir(from, to), 3.6f)
            wait(2)
        }
        f.openArius(18)
        wait(16)
    }

    /** Eisspeerwurf: Er formt drei Eisspeere in der Luft und schleudert sie im Fächer (angesagte Bahnen). */
    fun iceJavelin(white: Boolean): Script = script {
        val a = f.arius ?: return@script
        val t = f.target() ?: return@script
        f.face(a, t.entityPos)
        val from = a.entityPos
        val dir = flatDir(from, t.entityPos)
        a.play(AriusPose.THROW_CRYSTALS, 18)
        f.sound("crystal.hang", from, 2f, 1.3f)
        val group = f.calls.boss()
        val lines = listOf(-0.32, 0.0, 0.32).map { off ->
            val d = Vec3d(dir.x * cos(off) - dir.z * sin(off), 0.0, dir.x * sin(off) + dir.z * cos(off))
            val to = f.clampToArena(from.add(d.multiply(20.0)), 0.5)
            f.fx(FxKind.CRYSTAL, from.add(0.0, 2.6, 0.0).add(d.multiply(0.8)), to.add(0.0, 1.0, 0.0), a = 22f, life = 30)
            group.line(from, to, 1.6f, 18, Telegraph.Pace.PROJECTILE) to d
        }
        wait(18)
        f.sound("arius.thrust", from, 2.4f, 1.2f)
        for ((line, _) in lines) for (p in line.hits(f.players())) {
            f.hurt(p, if (white) 10f else 9f)
            dev.henny.hha_arius.gear.Frostbite.add(p, 15f)
        }
        f.openArius(16)
        wait(12)
    }

    /** Himmelssprung: Er springt hoch in die Luft und stürzt mit der Klinge voran auf das Ziel; Eisdornenkranz. */
    fun skyLeap(white: Boolean): Script = script {
        val a = f.arius ?: return@script
        val t = f.target() ?: return@script
        a.sheathed = false
        val from = a.entityPos
        val spot = Vec3d(t.x, f.floorY, t.z)
        val ring = f.calls.ring(spot, 3.8f, 26, pace = Telegraph.Pace.HEAVY)
        a.play(AriusPose.LAUNCH, 8)
        f.sound("arius.wind_step", from, 2.4f, 0.7f)
        f.fx(FxKind.DASH_STREAK, from, from.add(0.0, 10.0, 0.0), life = 10)
        val apex = from.lerp(spot, 0.5).add(0.0, 10.0, 0.0)
        for (i in 1..13) {
            val k = i / 13.0
            f.place(a, from.lerp(apex, 1.0 - (1.0 - k) * (1.0 - k)))
            f.face(a, spot)
            yield(Unit)
        }
        a.play(AriusPose.STAB_GROUND, ring.remaining.coerceAtLeast(1))
        val top = a.entityPos
        val left = ring.remaining.coerceAtLeast(4)
        for (i in 1..left) {
            val k = i / left.toDouble()
            f.place(a, top.lerp(spot, k * k))
            yield(Unit)
        }
        f.place(a, spot)
        f.sound("arius.stab_ground", spot, 3f, 0.8f)
        f.calls.roomShake(spot, 4f, 30f)
        Vfx.shockwave(f.world, style, spot, 5f, 14, 1.6f)
        f.fx(FxKind.CLEAR_WAVE, spot, a = 7f, life = 16)
        for (p in ring.hits(f.players())) {
            f.hurt(p, if (white) 13f else 12f, Damage.Kind.IMPACT)
            dev.henny.hha.logic.Fx.launchPlayer(p, flatDir(spot, p.entityPos).multiply(0.8).add(0.0, 0.5, 0.0))
        }
        for (k in 0 until 10) {
            val ang = k / 10.0 * Math.PI * 2
            spike(spot.add(cos(ang) * 4.6, 0.0, sin(ang) * 4.6), 1 + rnd.nextInt(2), 50)
        }
        f.openArius(24)
        wait(20)
    }

    // --- Klingenflug (seit 1.2.2): Solvane fliegt allein, Arius tritt ----------------------------------------------------

    /**
     * Klingenflug: Arius lässt die Klinge los, sie wirbelt magisch durch die Luft (flach gelegt, dreht wie ein Rotor) und
     * fliegt zwei bzw. drei angesagte Bahnen auf Spieler. Gleichzeitig rückt Arius selbst nach und tritt: Drehtritt,
     * dann Fronttritt. Danach kehrt die Klinge in seine Hand zurück. Die Bahnen laufen als eigene Aufgabe neben seinen
     * Tritten.
     */
    fun flyingBladeAttack(white: Boolean): Script = script {
        val a = f.arius ?: return@script
        val t0 = f.target() ?: return@script
        val keep = a.bladeState
        a.sheathed = false
        f.face(a, t0.entityPos)
        a.play(AriusPose.ARROW_CUT, 8)
        wait(6)
        val blade = net.minecraft.entity.EntityType.ITEM_DISPLAY.create(f.world, net.minecraft.entity.SpawnReason.EVENT) ?: return@script
        val hand = a.entityPos.add(0.0, 1.4, 0.0).add(f.facing(a).multiply(0.6))
        blade.setItemStack(net.minecraft.item.ItemStack(dev.henny.hha_arius.gear.GearItems.SOLVANE))
        blade.refreshPositionAndAngles(hand.x, hand.y, hand.z, 0f, 0f)
        blade.setTeleportDuration(2)
        spinBlade(blade, 0f)
        f.world.spawnEntity(blade)
        flyingBlade = blade
        a.bladeState = AriusBlade.NONE
        f.sound("arius.wind_step", hand, 1.8f, 1.6f)
        Vfx.trail(f.world, style, blade, 0.5f, 60)
        var done = false
        val passes = if (white) 3 else 2
        f.task(script {
            try {
                var spin = 0f
                var pos = hand
                for (k in 0 until passes) {
                    val target = (if (k == 0) t0 else f.players().randomOrNull()) ?: break
                    val dir = flatDir(pos, target.entityPos)
                    val start = f.clampToArena(target.entityPos.subtract(dir.multiply(7.0)), 2.0).let { Vec3d(it.x, f.floorY + 1.3, it.z) }
                    val end = f.clampToArena(target.entityPos.add(dir.multiply(7.0)), 2.0).let { Vec3d(it.x, f.floorY + 1.3, it.z) }
                    for (i in 1..10) {
                        pos = pos.lerp(start.add(0.0, 1.5 * sin(i / 10.0 * Math.PI), 0.0), 0.35)
                        spin += 55f
                        blade.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f)
                        spinBlade(blade, spin)
                        yield(Unit)
                    }
                    val line = f.calls.line(Vec3d(start.x, f.floorY, start.z), Vec3d(end.x, f.floorY, end.z), 2.0f, if (white) 12 else 16, Telegraph.Pace.PROJECTILE)
                    for (i in 0 until line.ticks) {
                        spin += 70f
                        spinBlade(blade, spin)
                        yield(Unit)
                    }
                    f.sound("arius.slash", start, 2.2f, 1.5f)
                    val steps = 7
                    for (i in 1..steps) {
                        pos = start.lerp(end, i / steps.toDouble())
                        spin += 90f
                        blade.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f)
                        spinBlade(blade, spin)
                        if (i == steps / 2) for (p in line.hits(f.players())) {
                            f.hurt(p, if (white) 11f else 9f)
                            dev.henny.hha.logic.Fx.launchPlayer(p, flatDir(start, end).multiply(0.6).add(0.0, 0.3, 0.0))
                        }
                        yield(Unit)
                    }
                }
                val back = f.arius ?: return@script
                for (i in 1..8) {
                    val h = back.entityPos.add(0.0, 1.3, 0.0)
                    pos = pos.lerp(h, i / 8.0)
                    spin += 60f
                    blade.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0f, 0f)
                    spinBlade(blade, spin)
                    yield(Unit)
                }
            } finally {
                done = true
            }
        })
        try {
            for (kick in 0 until 2) {
                val t = f.target() ?: break
                yieldAll(script { approach(t, 2.8, 26, white) })
                f.face(a, t.entityPos)
                val dir = f.facing(a)
                val front = kick == 1
                val cone = f.calls.cone(a.entityPos, dir, if (front) 3.8f else 3.4f, if (front) 40f else 70f, if (front) 12 else 10, Telegraph.Pace.FOLLOW_UP)
                a.play(if (front) AriusPose.GATE_KICK else AriusPose.PARRY_KICK, cone.ticks)
                wait(cone.ticks)
                f.sound("arius.kick", a.entityPos, 2f, if (front) 0.85f else 1.05f)
                for (p in cone.hits(f.players())) {
                    f.hurt(p, if (front) 10f else 8f)
                    dev.henny.hha.logic.Fx.launchPlayer(p, dir.multiply(if (front) 0.9 else 0.6).add(0.0, if (front) 0.4 else 0.3, 0.0))
                }
                Vfx.shockwave(f.world, style, a.entityPos.add(dir.multiply(1.6)), if (front) 2.6f else 2.0f, 10, 0.8f)
                wait(if (front) 8 else 4)
            }
            waitUntil(90) { done }
        } finally {
            flyingBlade?.discard()
            flyingBlade = null
            f.arius?.bladeState = keep
            f.sound("arius.guard", a.entityPos, 1.4f, 1.4f)
        }
        f.openArius(16)
        wait(12)
    }

    // --- Frostdornen (seit 1.2.2, angelehnt an Dornenmagie eines Ritters aus Elden Ring) -----------------------------

    /**
     * Frostdornen: Arius reißt die Klinge über den Boden, eine Reihe Eisdornen bricht in schneller Folge von ihm bis
     * hinter das Ziel aus dem Boden (angesagte Linie). Wer getroffen wird, wird aufgespießt hochgeschleudert. In der
     * weißen Form folgt ein Dornenkranz um ihn selbst (angesagter Ring). Seit 1.9.0 sind die Dornen nur sichtbar
     * (Block-Anzeigen): sie versperren keinen Weg.
     */
    fun frostThorns(white: Boolean): Script = script {
        val a = f.arius ?: return@script
        val t = f.target() ?: return@script
        f.face(a, t.entityPos)
        val from = a.entityPos
        val dir = flatDir(from, t.entityPos)
        val dist = sqrt(from.squaredDistanceTo(t.entityPos))
        val to = f.clampToArena(from.add(dir.multiply(dist + 5.0)), 1.0)
        val line = f.calls.line(from, to, 2.4f, if (white) 14 else 18, Telegraph.Pace.HEAVY)
        a.play(AriusPose.STRIP_SLASH, line.ticks)
        wait(line.ticks)
        f.sound("arius.frost_strip", from, 2.5f, 0.7f)
        val len = sqrt(from.squaredDistanceTo(to))
        val steps = (len / 1.5).toInt().coerceAtLeast(3)
        var struck = false
        for (i in 1..steps) {
            val p = from.lerp(to, i / steps.toDouble())
            spike(p, 2 + rnd.nextInt(2), 34)
            spike(p.add(Vec3d(-dir.z, 0.0, dir.x).multiply(if (i % 2 == 0) 0.9 else -0.9)), 1 + rnd.nextInt(2), 34)
            if (i % 2 == 0) f.sound("arius.ice_wall", p, 1.3f, 1.3f + i * 0.02f)
            if (!struck && i >= steps / 2) {
                struck = true
                for (pl in line.hits(f.players())) {
                    f.hurt(pl, if (white) 12f else 10f)
                    dev.henny.hha.logic.Fx.launchPlayer(pl, dir.multiply(0.25).add(0.0, 0.85, 0.0))
                }
            }
            yield(Unit)
        }
        if (white) {
            val ring = f.calls.ring(a.entityPos, 4.8f, 12, pace = Telegraph.Pace.FOLLOW_UP)
            a.play(AriusPose.STAB_GROUND, ring.ticks)
            wait(ring.ticks)
            f.sound("arius.stab_ground", a.entityPos, 2.5f, 0.9f)
            for (k in 0 until 14) {
                val ang = k / 14.0 * 2 * Math.PI
                spike(a.entityPos.add(cos(ang) * 3.2, 0.0, sin(ang) * 3.2), 3, 24)
            }
            for (pl in ring.hits(f.players())) {
                f.hurt(pl, 8f)
                dev.henny.hha.logic.Fx.launchPlayer(pl, flatDir(a.entityPos, pl.entityPos).multiply(0.6).add(0.0, 0.7, 0.0))
            }
        }
        f.openArius(14)
        wait(12)
        f.sound("transform.shatter", a.entityPos, 1.5f, 1.6f)
        wait(2)
    }

    /** Ein Eisdorn (h Blöcke) auf der Lauffläche: nur sichtbar, keine Kollision, verschwindet nach [life] Ticks. */
    private fun spike(at: Vec3d, h: Int, life: Int) {
        f.shards.spike(Vec3d(at.x, f.floorY, at.z), h + 0.3 + rnd.nextDouble() * 0.6, life, width = 0.55 + rnd.nextDouble() * 0.3)
    }

    /** Klinge flach gelegt (Spitze nach vorn) und um die Hochachse gedreht: wirbelt wie ein Rotor. */
    private fun spinBlade(blade: net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity, deg: Float) {
        val spin = org.joml.Quaternionf().rotationY(Math.toRadians(deg.toDouble()).toFloat())
        val lay = org.joml.Quaternionf().rotationX((Math.PI / 2).toFloat())
        blade.setTransformation(net.minecraft.util.math.AffineTransformation(org.joml.Vector3f(), spin, org.joml.Vector3f(1.6f, 1.6f, 1.6f), lay))
        blade.setInterpolationDuration(1)
        blade.setStartInterpolation(0)
    }

    /** Aufräumen, falls der Kampf mitten im Klingenflug endet. */
    fun cleanup() {
        flyingBlade?.discard()
        flyingBlade = null
        summit.cleanup()
    }

    private fun evading(t: ServerPlayerEntity): Boolean {
        val fi = f.fighter(t) ?: return false
        val a = f.arius ?: return false
        if (flatDist(a, t) < 4) fi.lastNearArius = f.now
        return f.now - fi.lastNearArius > 120
    }

    // --- A1 / W1: Kombo -------------------------------------------------------------------------------------------------

    fun combo(white: Boolean, serious: Boolean): Script = script {
        val a = f.arius ?: return@script
        var t = f.target() ?: return@script
        a.sheathed = false
        yieldAll(script { approach(t, 3.6, 50, white) })
        if (f.adapt == EirholmFight.Adapt.BACK) yieldAll(spinCut())

        // Hieb 1
        f.face(a, t.entityPos)
        var dir = f.facing(a)
        val c1 = f.calls.cone(a.entityPos, dir, 4.5f, 55f, AriusTimings.ARIUS_WINDUP[AriusPose.COMBO_1])
        a.play(AriusPose.COMBO_1, c1.ticks)
        wait(c1.ticks - 3)
        f.sound("arius.slash", a.entityPos, 2f, 1.05f)
        repeat(3) { f.stepToward(a, a.entityPos.add(dir), 0.25, faceIt = false); yield(Unit) }
        for (p in c1.hits(f.players())) f.hurt(p, 10f)
        slash(a.entityPos, dir, 4.5f)

        // Hieb 2 (Folgehieb), gelegentlich verzögert
        t = f.target() ?: return@script
        f.face(a, t.entityPos)
        dir = f.facing(a)
        val delayed = serious && rnd.nextFloat() < 0.3f
        val c2 = f.calls.cone(a.entityPos, dir, 4.5f, 55f, if (delayed) 19 else AriusTimings.ARIUS_WINDUP[AriusPose.COMBO_2], if (delayed) Telegraph.Pace.HEAVY else Telegraph.Pace.FOLLOW_UP)
        a.play(AriusPose.COMBO_2, c2.ticks)
        wait(c2.ticks)
        f.sound("arius.slash", a.entityPos, 2f, 0.95f)
        for (p in c2.hits(f.players())) f.hurt(p, 10f)
        slash(a.entityPos, dir, 4.5f)

        // Ernst: statt des Stoßes direkt in den Windschritt.
        if (serious && rnd.nextFloat() < 0.35f && flatDist(a, t) > 3) {
            yieldAll(lunge(t, false))
            return@script
        }

        // Hieb 3: Stoß (oder Schildbrecher-Tritt)
        t = f.target() ?: return@script
        f.face(a, t.entityPos)
        dir = f.facing(a)
        if (f.adapt == EirholmFight.Adapt.SHIELD) {
            val kick = f.calls.cone(a.entityPos, dir, 3.2f, 40f, 14, Telegraph.Pace.FOLLOW_UP)
            a.play(AriusPose.PARRY_KICK, kick.ticks)
            wait(kick.ticks)
            f.sound("arius.kick", a.entityPos)
            for (p in kick.hits(f.players())) {
                f.hurt(p, 8f, Damage.Kind.IMPACT)
                breakShield(p)
            }
            f.openArius(AriusTimings.COMBO_OPEN)
            wait(AriusTimings.COMBO_OPEN)
            return@script
        }
        val from = a.entityPos
        val line = f.calls.line(from, from.add(dir.multiply(7.0)), 2f, AriusTimings.ARIUS_WINDUP[AriusPose.COMBO_3], Telegraph.Pace.HEAVY)
        a.play(AriusPose.COMBO_3, line.ticks)
        wait(line.ticks - 2)
        repeat(2) { f.stepToward(a, a.entityPos.add(dir), 0.5, faceIt = false); yield(Unit) }
        f.sound("arius.thrust", a.entityPos)
        for (p in line.hits(f.players())) f.hurt(p, 14f)
        Vfx.crescent(f.world, style, a.entityPos.add(0.0, 1.2, 0.0), dir, 6f, 1.2f, 7)

        if (!white) {
            f.openArius(AriusTimings.COMBO_OPEN)
            wait(AriusTimings.COMBO_OPEN)
            return@script
        }
        // W1: Windschritt hinter das Ziel, Rückwärtsschnitt.
        t = f.target() ?: return@script
        val behind = f.clampToArena(t.entityPos.add(flatDir(a.entityPos, t.entityPos).multiply(2.2)), 1.0)
        f.sound("arius.wind_step", a.entityPos, 2f)
        Vfx.trail(f.world, style, a, 0.5f, 10)
        repeat(5) { f.stepToward(a, behind, 0.9, faceIt = false); yield(Unit) }
        f.face(a, t.entityPos)
        dir = f.facing(a)
        val c4 = f.calls.cone(a.entityPos, dir, 4f, 70f, AriusTimings.ARIUS_WINDUP[AriusPose.COMBO_4], Telegraph.Pace.FOLLOW_UP)
        a.play(AriusPose.COMBO_4, c4.ticks)
        wait(c4.ticks)
        f.sound("arius.slash", a.entityPos, 2f, 1.15f)
        for (p in c4.hits(f.players())) f.hurt(p, 11f)
        slash(a.entityPos, dir, 4f)
        f.openArius(20)
        wait(18)
    }

    private fun breakShield(p: ServerPlayerEntity) {
        for (hand in Hand.entries) {
            val stack = p.getStackInHand(hand)
            if (stack.isOf(Items.SHIELD)) {
                p.itemCooldownManager.set(stack, 100)
                p.stopUsingItem()
            }
        }
    }

    private fun spinCut(): Script = script {
        val a = f.arius ?: return@script
        val ring = f.calls.ring(a.entityPos, 3.5f, AriusTimings.ARIUS_WINDUP[AriusPose.SPIN_CUT])
        a.play(AriusPose.SPIN_CUT, ring.ticks)
        wait(ring.ticks)
        f.sound("arius.slash", a.entityPos, 2f, 0.85f)
        for (p in ring.hits(f.players())) f.hurt(p, 10f)
        Vfx.shockwave(f.world, style, a.entityPos, 3.5f, 10, 1.2f)
        wait(6)
    }

    // --- A2 / W2: Windschritt ----------------------------------------------------------------------------------------

    fun lunge(target: ServerPlayerEntity, white: Boolean): Script = script {
        f.arius?.sheathed = false
        yieldAll(lungeOnce(target, AriusTimings.ARIUS_WINDUP[AriusPose.LUNGE], Telegraph.Pace.HEAVY, last = !white))
        if (white) {
            val t = f.target() ?: return@script
            yieldAll(lungeOnce(t, 12, Telegraph.Pace.FOLLOW_UP, last = true))
        }
    }

    private fun lungeOnce(target: ServerPlayerEntity, windup: Int, pace: Telegraph.Pace, last: Boolean): Script = script {
        val a = f.arius ?: return@script
        f.face(a, target.entityPos)
        val from = a.entityPos
        val dir = flatDir(from, target.entityPos)
        val dist = sqrt(from.squaredDistanceTo(target.entityPos))
        val to = f.clampToArena(from.add(dir.multiply(dist + 3.0)), 1.0)
        val line = f.calls.line(from, to, 2.2f, windup, pace)
        a.play(AriusPose.LUNGE, line.ticks)
        f.sound("arius.wind_step", from, 1.6f, 0.8f)
        wait(line.ticks)
        // Säule im Weg? Dann bleibt er dort hängen, sie bricht, er taumelt.
        val pillar = f.pillarOnPath(from, to, 1.8)
        val stop = pillar?.let { Vec3d(it.x + 0.5, f.floorY, it.z + 0.5).subtract(dir.multiply(1.6)) } ?: to
        Vfx.trail(f.world, style, a, 0.6f, 12)
        f.fx(FxKind.DASH_STREAK, from, stop, life = 14)
        for (p in line.hits(f.players())) {
            f.hurt(p, 16f)
            dev.henny.hha.logic.Fx.launchPlayer(p, dir.multiply(0.8).add(0.0, 0.35, 0.0))
        }
        var n = 0
        while (n++ < 14 && !f.stepToward(a, stop, AriusTimings.LUNGE_SPEED, faceIt = false)) yield(Unit)
        f.sound("arius.slash", a.entityPos, 2f, 1.1f)
        if (pillar != null) {
            f.shatterPillar(pillar)
            a.play(AriusPose.STAGGER)
            f.openArius(AriusTimings.LUNGE_PILLAR_STAGGER)
            wait(AriusTimings.LUNGE_PILLAR_STAGGER)
            return@script
        }
        if (last) {
            f.openArius(if (pace == Telegraph.Pace.FOLLOW_UP) 26 else AriusTimings.LUNGE_OPEN)
            wait(if (pace == Telegraph.Pace.FOLLOW_UP) 26 else AriusTimings.LUNGE_OPEN)
        } else {
            wait(2)
        }
    }

    // --- A3: Frostbahn → Streifenhieb (seit 1.9.0 statt der Frostwand) ------------------------------------------------

    /**
     * Frostbahn: Er legt die Hand auf den Boden, eine Bahn aus Raureif schießt quer durch das Feld (angesagt). Sie
     * versperrt nichts: Wer darin steht, wird kurz verlangsamt und friert an (Frostaufbau), solange sie glitzert. Dann
     * fegt der Streifenhieb über den Boden. Ersetzt die alte Frostwand (Crix: „Arius macht immer noch Eiswände“).
     */
    fun frostLane(serious: Boolean): Script = script {
        val a = f.arius ?: return@script
        val t = f.farthestFrom(a.entityPos) ?: return@script
        f.face(a, t.entityPos)
        a.sheathed = false
        val toT = flatDir(a.entityPos, t.entityPos)
        val side = Vec3d(-toT.z, 0.0, toT.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0)
        val mid = t.entityPos.add(side.multiply(2.5))
        val laneFrom = f.clampToArena(mid.subtract(toT.multiply(8.0)), 1.0).let { Vec3d(it.x, f.floorY, it.z) }
        val laneTo = f.clampToArena(mid.add(toT.multiply(8.0)), 1.0).let { Vec3d(it.x, f.floorY, it.z) }
        val lane = f.calls.line(laneFrom, laneTo, 2.6f, AriusTimings.ARIUS_WINDUP[AriusPose.WALL_CAST])
        a.play(AriusPose.WALL_CAST, lane.ticks)
        f.sound("arius.ice_wall", mid, 2f, 1.1f)
        wait(lane.ticks)
        f.sound("arius.frost_strip", mid, 2.2f, 0.9f)
        f.fx(FxKind.SEAM, laneFrom.add(0.0, 0.08, 0.0), laneTo.add(0.0, 0.08, 0.0), a = 0.6f, life = 90)
        val len = sqrt(laneFrom.squaredDistanceTo(laneTo))
        val n = (len / 3.0).toInt().coerceAtLeast(2)
        for (i in 0..n) f.fx(FxKind.FIELD, laneFrom.lerp(laneTo, i / n.toDouble()), a = 1.8f, life = 90)
        for (p in lane.hits(f.players())) {
            f.hurt(p, 6f, Damage.Kind.IMPACT)
            slowDown(p, 40)
        }
        // Die Bahn glitzert noch eine Weile: verlangsamt, baut Frost auf – blockiert aber nichts.
        f.task(script {
            repeat(9) {
                wait(10)
                for (p in f.players()) if (laneDist(p.entityPos, laneFrom, laneTo) <= 1.4 && kotlin.math.abs(p.y - f.floorY) < 1.5) {
                    slowDown(p, 20)
                    dev.henny.hha_arius.gear.Frostbite.add(p, 6f)
                }
            }
        })
        if (serious && rnd.nextBoolean()) {
            wait(AriusTimings.STRIP_DELAY)
            yieldAll(stabGround())
            return@script
        }
        wait(AriusTimings.STRIP_DELAY)
        val target = f.target() ?: t
        val dir = flatDir(a.entityPos, target.entityPos)
        val stripTo = f.clampToArena(a.entityPos.add(dir.multiply(16.0)), 0.5)
        f.fx(FxKind.SEAM, a.entityPos.add(0.0, 0.05, 0.0), stripTo.add(0.0, 0.05, 0.0), a = 0f, life = AriusTimings.STRIP_WINDUP)
        val strip = f.calls.line(a.entityPos, stripTo, 2.2f, AriusTimings.STRIP_WINDUP)
        f.sound("arius.frost_strip", a.entityPos, 1.8f)
        f.face(a, stripTo)
        a.play(AriusPose.STRIP_SLASH, strip.ticks)
        wait(strip.ticks)
        f.sound("arius.slash", a.entityPos, 2.2f, 0.8f)
        for (p in strip.hits(f.players())) f.hurt(p, 12f)
        Vfx.crescent(f.world, style, a.entityPos.add(0.0, 0.5, 0.0), dir, 16f, 3.2f, 10)
        f.openArius(AriusTimings.STRIP_OPEN)
        wait(AriusTimings.STRIP_OPEN)
    }

    private fun laneDist(p: Vec3d, a: Vec3d, b: Vec3d): Double {
        val vx = b.x - a.x
        val vz = b.z - a.z
        val len2 = vx * vx + vz * vz
        val t = if (len2 < 1e-9) 0.0 else (((p.x - a.x) * vx + (p.z - a.z) * vz) / len2).coerceIn(0.0, 1.0)
        val dx = p.x - (a.x + vx * t)
        val dz = p.z - (a.z + vz * t)
        return sqrt(dx * dx + dz * dz)
    }

    // --- A4 / W3: Konterhaltung ----------------------------------------------------------------------------------------

    /**
     * Konterhaltung (seit 1.9.0 statt „Parade und Tritt“): Er hebt die Klinge quer vor die Brust und wartet. Wer in
     * dieses Fenster schlägt, wird pariert und bekommt eine echte Kombo ([counter]). Wer nicht anbeißt, lässt ihn ins
     * Leere warten – dann taumelt er und steht offen.
     */
    fun guard(white: Boolean, serious: Boolean): Script = script {
        val a = f.arius ?: return@script
        f.target()?.let { f.face(a, it.entityPos) }
        a.sheathed = false
        val ticks = if (white) AriusTimings.GUARD_TICKS_WHITE else if (serious) AriusTimings.GUARD_TICKS_SERIOUS else AriusTimings.GUARD_TICKS
        a.play(AriusPose.GUARD)
        f.guardUntil = f.now + ticks
        f.guardWhite = white
        f.guardTriggered = false
        Vfx.rune(f.world, style, Vec3d(a.x, f.floorAt(a) + 0.05, a.z), 1.3f, ticks, a)
        f.sound("arius.guard", a.entityPos, 1.6f)
        waitUntil(ticks) { f.guardTriggered }
        f.guardUntil = 0
        if (f.guardTriggered) {
            waitUntil(220) { !countering }
            return@script
        }
        a.play(AriusPose.STAGGER)
        f.openArius(AriusTimings.GUARD_UNTOUCHED_OPEN)
        wait(AriusTimings.GUARD_UNTOUCHED_OPEN - 4)
    }

    /**
     * Perfekter Konter: Er fängt den Schlag mit der Klinge ab (Funken, Klang), die Deckung des Angreifers bricht kurz,
     * und es folgt eine echte Kombo. Der erste Gegenschlag trifft im Bruch, alle weiteren sind angesagt und ab dem zweiten
     * ausweichbar oder blockbar. Vom Treffer-Handler (Konterhaltung) oder als Reaktion auf blindes Einhämmern.
     */
    fun counter(player: ServerPlayerEntity, white: Boolean) {
        val a = f.arius ?: return
        if (countering) return
        f.face(a, player.entityPos)
        f.sound("arius.parry", a.entityPos, 2.8f, 1.15f)
        f.sound("solvane.parry", a.entityPos, 2f, 0.9f)
        Vfx.flare(f.world, Vfx.Style.STORM, a.entityPos.add(0.0, 1.3, 0.0).add(f.facing(a).multiply(0.8)), 1.8f, 8)
        f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.3, 0.0), flatDir(a.entityPos, player.entityPos), a = 2.2f, b = 0.9f, life = 8)
        f.calls.hitShake(player.entityPos, 2.5f, 8f)
        player.sendMessage(Text.translatable("hha_arius.msg.parried").formatted(Formatting.RED), true)
        countering = true
        a.play(AriusPose.GUARD)
        f.task(punish(player, white))
    }

    /** Antwort auf blindes Einhämmern außerhalb der Fenster: Seitschritt oder (häufiger, je ernster) perfekter Konter. */
    fun reactTo(player: ServerPlayerEntity, white: Boolean) {
        if (acting || countering || f.now < nextReact) return
        nextReact = f.now + 100
        val serious = f.ariusHp <= f.ariusMax * 0.6f || white || f.phase == EirholmFight.Phase.FINALE
        if (Hold.canGrab(player) && rnd.nextFloat() < (if (serious) 0.6f else 0.35f)) counter(player, white) else sidestepFrom(player)
    }

    // --- Konter-Bestrafung (seit 1.10.0, nach dem letzten Boss aus Black Myth: Wukong) ------------------------------------

    /** Wen er gerade verprügelt (nur dieser Spieler ist gesperrt). */
    var punishVictim: java.util.UUID? = null
        private set
    private var punishFreed: java.util.UUID? = null
    private var allyDamage = 0f

    /**
     * Vom Treffer-Handler: Schaden an Arius, während er jemanden bestraft. Genug davon von einem MITspieler bricht die
     * Bestrafung ab (Arius taumelt, das Opfer kommt frei) – der Koop-Moment.
     */
    fun onAllyHit(player: ServerPlayerEntity, dmg: Float) {
        val v = punishVictim ?: return
        if (player.uuid == v || punishFreed != null || dmg <= 0f) return
        allyDamage += dmg
        if (allyDamage >= 14f * (1f + 0.25f * (f.startCount - 2).coerceAtLeast(0))) punishFreed = player.uuid
    }

    /**
     * Die echte Bestrafung: Das Opfer ist gesperrt (keine Bewegung, kein Angriff, kein Benutzen), die eigene Kamera
     * zeigt beide von der Seite ([dev.henny.hha_arius.net.CineCamS2C]), und Arius prügelt sieben Treffer durch: Knauf
     * ins Gesicht, Knie und Fronttritt, Rückhandhieb, Drehtritt, Aufwärtsschnitt (hoch in die Luft), Abwärtstritt (zurück
     * in den Boden) und zum Schluss die Klinge daneben in den Boden – die Druckwelle schleudert das Opfer weg. Jeder
     * Treffer mit Trefferpause, Impact-Frame, Klang und Ruck. Zusammen ≈ 15 Grundschaden; aus vollem Leben nie tödlich.
     * Nur das Opfer ist gesperrt; Mitspieler können ihn mit genug Schaden herausschlagen ([onAllyHit]).
     */
    private fun punish(player: ServerPlayerEntity, white: Boolean): Script = script {
        val a = f.arius ?: run { countering = false; return@script }
        if (!Hold.canGrab(player) || !f.canSequence(player) || Hold.inGrace(player)) {
            yieldAll(punishClassic(player, white))
            return@script
        }
        punishVictim = player.uuid
        punishFreed = null
        allyDamage = 0f
        val healthy = player.health >= player.maxHealth * 0.5f
        var released = false
        try {
            Hold.allowLong(player, 150)
            Hold.freeze(player, 24)
            dev.henny.hha_arius.net.AriusNet.send(player, dev.henny.hha_arius.net.CineCamS2C(true, a.id, 140, 0))
            player.sendMessage(Text.translatable("hha_arius.msg.punished").formatted(Formatting.RED), true)
            for (p in f.players()) if (p !== player) {
                p.sendMessage(Text.translatable("hha_arius.msg.punish_ally", player.displayName ?: player.name).formatted(Formatting.GOLD), true)
            }
            val ground = Vec3d(player.x, player.y, player.z)
            var dir = flatDir(a.entityPos, ground)
            // Er tritt dicht heran.
            val close = ground.subtract(dir.multiply(1.5))
            a.sheathed = false
            for (i in 1..4) {
                if (!punishOn(player)) break
                f.place(a, a.entityPos.lerp(Vec3d(close.x, f.floorAt(a), close.z), i / 4.0))
                f.face(a, player.entityPos)
                yield(Unit)
            }
            // 1) Knauf ins Gesicht.
            if (!beat(player, AriusPose.POMMEL, 7, 1.5f, Damage.Kind.IMPACT, dir.multiply(0.5), 0.0, "hold.impact", 1.3f, 0.5f, healthy)) return@script
            // 2) Knie, dann Fronttritt.
            if (!beat(player, AriusPose.GATE_KICK, 9, 2f, Damage.Kind.IMPACT, dir.multiply(1.3), 0.0, "arius.kick", 0.9f, 0.8f, healthy)) return@script
            // 3) Rückhandhieb nach einem halben Schritt.
            follow(player, dir, 1.6)
            if (!beat(player, AriusPose.COMBO_2, 6, 2f, Damage.Kind.SOLVANE, dir.multiply(0.4), 0.0, "arius.slash", 1.15f, 0.6f, healthy)) return@script
            // 4) Drehtritt.
            if (!beat(player, AriusPose.PARRY_KICK, 8, 2f, Damage.Kind.IMPACT, dir.multiply(1.0).add(Vec3d(-dir.z, 0.0, dir.x).multiply(0.4)), 0.0, "arius.kick", 1.05f, 0.9f, healthy)) return@script
            dir = flatDir(a.entityPos, player.entityPos)
            // 5) Aufwärtsschnitt: hoch in die Luft.
            follow(player, dir, 1.5)
            if (!beat(player, AriusPose.LAUNCH, 7, 2f, Damage.Kind.SOLVANE, Vec3d.ZERO, 3.0, "arius.slash", 1.35f, 0.8f, healthy)) return@script
            // 6) Er springt nach und tritt von oben: zurück in den Boden.
            val air = player.entityPos.subtract(dir.multiply(1.2)).add(0.0, 0.6, 0.0)
            val from = a.entityPos
            Hold.extend(player, 30)
            a.play(AriusPose.AIR_KICK, 8)
            f.sound("arius.wind_step", from, 2f, 1.2f)
            for (i in 1..8) {
                if (!punishOn(player)) return@script
                val k = i / 8.0
                f.place(a, from.lerp(air, 1.0 - (1.0 - k) * (1.0 - k)))
                f.face(a, player.entityPos)
                yield(Unit)
            }
            if (!impact(player, 2.5f, Damage.Kind.IMPACT, "arius.kick", 0.75f, 1.2f, healthy, heavy = true)) return@script
            val down = Vec3d(player.x, ground.y, player.z)
            drive(player, down, 3)
            f.calls.roomShake(down, 3f, 20f)
            Vfx.shockwave(f.world, style, down, 3.2f, 10, 1.4f)
            f.world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK, net.minecraft.block.Blocks.SNOW_BLOCK.defaultState), down.x, down.y + 0.1, down.z, 30, 0.8, 0.1, 0.8, 0.15)
            val landAt = Vec3d(down.x, down.y, down.z).subtract(dir.multiply(1.6))
            val airFrom = a.entityPos
            for (i in 1..4) {
                f.place(a, airFrom.lerp(landAt, i / 4.0))
                yield(Unit)
            }
            // 7) Abschluss: Klinge neben ihm in den Boden, die Druckwelle schleudert ihn weg.
            if (!beat(player, AriusPose.STAB_GROUND, 10, 3f, Damage.Kind.IMPACT, Vec3d.ZERO, 0.0, "arius.stab_ground", 0.85f, 1.3f, healthy, heavy = true)) return@script
            f.calls.roomShake(a.entityPos, 4f, 24f)
            Vfx.shockwave(f.world, style, a.entityPos, 4.5f, 12, 1.6f)
            f.fx(FxKind.CLEAR_WAVE, a.entityPos, a = 5f, life = 14)
            if (f.canSequence(player) && Hold.isHeld(player)) {
                val away = flatDir(a.entityPos, player.entityPos)
                released = true
                Hold.switchToLaunch(player, away.multiply(0.95).add(0.0, 0.5, 0.0), 26, f.clampToArena(player.entityPos.add(away.multiply(4.5)), 1.5), f.landingSpot(player))
                dev.henny.hha_arius.net.AriusNet.send(player, dev.henny.hha_arius.net.CineCamS2C(true, a.id, 14, 0))
            }
            a.play(AriusPose.IDLE)
            f.openArius(18)
            wait(14)
        } finally {
            val freer = punishFreed
            if (freer != null && f.canSequence(player)) {
                // Ein Mitspieler hat ihn herausgeschlagen.
                f.arius?.let { ar ->
                    ar.play(AriusPose.STAGGER)
                    f.openArius(44)
                    f.sound("arius.parry", ar.entityPos, 2.4f, 0.7f)
                    f.sound("transform.shatter", ar.entityPos, 1.6f, 1.2f)
                    Vfx.flare(f.world, Vfx.Style.GOLD, ar.entityPos.add(0.0, 1.3, 0.0), 2f, 8)
                }
                val name = f.server.playerManager.getPlayer(freer)?.displayName ?: Text.literal("?")
                for (p in f.players()) p.sendMessage(Text.translatable("hha_arius.msg.punish_freed", name).formatted(Formatting.GOLD), true)
            }
            if (!released && Hold.isHeld(player)) Hold.release(player, 24)
            if (!released) dev.henny.hha_arius.net.AriusNet.send(player, dev.henny.hha_arius.net.CineCamS2C(false, -1, 0, 0))
            punishVictim = null
            punishFreed = null
            countering = false
        }
    }

    /** Läuft die Bestrafung noch (Opfer da und gehalten, keiner hat ihn herausgeschlagen)? */
    private fun punishOn(v: ServerPlayerEntity): Boolean =
        punishFreed == null && f.arius != null && f.canSequence(v) && Hold.isHeld(v) && v in f.players()

    /** Ein Schlag der Bestrafung: Pose mit genau [w] Ticks bis zum Treffer, dann Treffer, Wucht, Rückstoß. */
    private suspend fun SequenceScope<Unit>.beat(
        v: ServerPlayerEntity, pose: Int, w: Int, base: Float, kind: Damage.Kind, push: Vec3d, lift: Double,
        sound: String, pitch: Float, kick: Float, healthy: Boolean, heavy: Boolean = false,
    ): Boolean {
        val a = f.arius ?: return false
        Hold.extend(v, w + 24)
        f.face(a, v.entityPos)
        a.play(pose, w)
        for (i in 0 until w) {
            if (!punishOn(v)) return false
            f.face(a, v.entityPos)
            yield(Unit)
        }
        if (!impact(v, base, kind, sound, pitch, kick, healthy, heavy)) return false
        if (push.lengthSquared() > 1e-4 || lift > 0.0) {
            val goal = v.entityPos.add(push).add(0.0, lift, 0.0)
            drive(v, goal, if (lift > 0.0) 6 else 3)
        }
        wait(2)
        return punishOn(v)
    }

    /** Treffer selbst (Schaden über die Sequenz, aus vollem Leben gedeckelt), Funken, Trefferpause, Impact-Frame. */
    private fun impact(v: ServerPlayerEntity, base: Float, kind: Damage.Kind, sound: String, pitch: Float, kick: Float, healthy: Boolean, heavy: Boolean): Boolean {
        val a = f.arius ?: return false
        if (!punishOn(v)) return false
        var b = base
        if (healthy) {
            val room = (v.health + v.absorptionAmount - 2f) / BossConfig.damageFor(f.scaling.count)
            b = minOf(b, room.coerceAtLeast(0f))
        }
        if (b > 0.05f) f.hurtHeld(v, b, kind)
        val contact = v.entityPos.add(0.0, 1.1, 0.0)
        f.ambient(sound, contact, 2.4f, pitch)
        if (heavy) f.ambient("hold.impact", contact, 2.2f, 0.7f)
        Vfx.flare(f.world, if (kind == Damage.Kind.SOLVANE) Vfx.Style.HEAVEN else Vfx.Style.STORM, contact, if (heavy) 1.6f else 1.1f, 5)
        if (kind == Damage.Kind.SOLVANE) f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.1, 0.0), flatDir(a.entityPos, v.entityPos), a = 2.6f, b = 0.4f, life = 9)
        f.world.spawnParticles(net.minecraft.particle.ParticleTypes.SNOWFLAKE, contact.x, contact.y, contact.z, 10, 0.25, 0.3, 0.25, 0.12)
        f.calls.hitShake(contact, if (heavy) 3f else 2f, 8f)
        val stop = if (heavy) 3.2f else 2.2f
        for (p in f.audience()) {
            if (p.squaredDistanceTo(contact) > 64.0 * 64.0) continue
            val mine = p === v
            dev.henny.hha_arius.net.AriusNet.send(p, dev.henny.hha_arius.net.BeatS2C(a.id, contact.x, contact.y, contact.z, stop,
                if (mine) kick * 1.6f else 0f, if (mine) (if (heavy) 0.55f else 0.35f) else 0f))
        }
        return true
    }

    /** Schiebt das gehaltene Opfer weich zu [goal] (nur auf freie Stellen, in der Arena). */
    private suspend fun SequenceScope<Unit>.drive(v: ServerPlayerEntity, goal: Vec3d, ticks: Int) {
        val start = v.entityPos
        val clamped = f.clampToArena(goal, 1.0).let { Vec3d(it.x, goal.y, it.z) }
        for (i in 1..ticks.coerceAtLeast(1)) {
            if (!punishOn(v)) return
            val k = i / ticks.toDouble()
            val e = 1.0 - (1.0 - k) * (1.0 - k)
            val next = start.lerp(clamped, e)
            if (!f.passable(next)) break
            Hold.moveAnchor(v, next)
            yield(Unit)
        }
    }

    /** Arius rückt auf [dist] an das Opfer nach (Windschritt-Streifen). */
    private suspend fun SequenceScope<Unit>.follow(v: ServerPlayerEntity, dir: Vec3d, dist: Double) {
        val a = f.arius ?: return
        val to = v.entityPos.subtract(dir.multiply(dist)).let { Vec3d(it.x, f.floorAt(v), it.z) }
        val from = a.entityPos
        for (i in 1..2) {
            f.place(a, from.lerp(to, i / 2.0))
            f.face(a, v.entityPos)
            yield(Unit)
        }
        f.fx(FxKind.DASH_STREAK, from, a.entityPos, life = 6)
    }

    /** Alte Bestrafung (ohne Sperre), falls das Opfer gerade nicht gehalten werden darf. */
    private fun punishClassic(player: ServerPlayerEntity, white: Boolean): Script = script {
        val a = f.arius ?: run { countering = false; return@script }
        try {
            val held = Hold.canGrab(player)
            if (held) Hold.freeze(player, 12)
            // 1) Gegenschlag im Bruch der Deckung
            f.face(a, player.entityPos)
            a.sheathed = false
            a.play(AriusPose.COMBO_2, 8)
            wait(8)
            if (!f.canSequence(player)) return@script
            f.sound("arius.slash", player.entityPos, 2.4f, 1.2f)
            slash(a.entityPos, flatDir(a.entityPos, player.entityPos), 3.8f)
            if (Hold.isHeld(player)) f.hurtHeld(player, 7f, Damage.Kind.SOLVANE) else f.hurt(player, 7f)
            f.calls.hitShake(player.entityPos, 3f, 8f)
            wait(4)
            // 2)–n) Angesagte Folgeschläge auf den, der sich erwischen ließ (oder den Nächsten).
            val steps = if (white) 4 else 3
            for (k in 0 until steps) {
                val t = player.takeIf { f.canSequence(it) && it in f.players() } ?: f.nearestTo(a.entityPos) ?: break
                if (flatDist(a, t) > 4.5) repeat(4) { f.stepToward(a, t.entityPos, 0.7); yield(Unit) }
                f.face(a, t.entityPos)
                val dir = f.facing(a)
                when (k) {
                    0 -> {
                        val c = f.calls.cone(a.entityPos, dir, 4.6f, 65f, 10, Telegraph.Pace.FOLLOW_UP)
                        a.play(AriusPose.COMBO_1, c.ticks)
                        wait(c.ticks)
                        f.sound("arius.slash", a.entityPos, 2.2f, 1.05f)
                        for (p in c.hits(f.players())) f.hurt(p, 8f)
                        slash(a.entityPos, dir, 4.6f)
                    }
                    1 -> {
                        val r = f.calls.ring(a.entityPos, 3.8f, 11, pace = Telegraph.Pace.FOLLOW_UP)
                        a.play(AriusPose.SPIN_CUT, r.ticks)
                        wait(r.ticks)
                        f.sound("arius.slash", a.entityPos, 2.2f, 0.85f)
                        for (p in r.hits(f.players())) f.hurt(p, 8f)
                        Vfx.shockwave(f.world, style, a.entityPos, 3.8f, 10, 1.2f)
                    }
                    2 -> {
                        // Abschluss mit wechselndem Zeitpunkt: Stoß, früh oder spät.
                        val from = a.entityPos
                        val late = rnd.nextBoolean()
                        val line = f.calls.line(from, f.clampToArena(from.add(dir.multiply(7.5)), 1.0), 2.2f, if (late) 24 else 13, Telegraph.Pace.HEAVY)
                        a.play(AriusPose.COMBO_3, line.ticks)
                        if (late) f.sound("iai.glint", from, 1.4f, 0.7f)
                        wait(line.ticks - 2)
                        repeat(2) { f.stepToward(a, a.entityPos.add(dir), 0.5, faceIt = false); yield(Unit) }
                        f.sound("arius.thrust", a.entityPos, 2.4f)
                        for (p in line.hits(f.players())) {
                            f.hurt(p, 12f)
                            dev.henny.hha.logic.Fx.launchPlayer(p, dir.multiply(0.6).add(0.0, 0.3, 0.0))
                        }
                        Vfx.crescent(f.world, style, a.entityPos.add(0.0, 1.2, 0.0), dir, 7f, 1.4f, 7)
                    }
                    else -> {
                        // Weiße Form: Windschritt durch und Aufwärtsschnitt.
                        val from = a.entityPos
                        val to = f.clampToArena(from.add(dir.multiply(6.0)), 1.0)
                        val line = f.calls.line(from, to, 2.2f, 10, Telegraph.Pace.FOLLOW_UP)
                        a.play(AriusPose.LAUNCH, line.ticks)
                        wait(line.ticks)
                        f.fx(FxKind.DASH_STREAK, from, to, life = 10)
                        f.place(a, Vec3d(to.x, f.floorY, to.z))
                        f.sound("arius.slash", to, 2.2f, 1.3f)
                        for (p in line.hits(f.players())) {
                            f.hurt(p, 9f)
                            dev.henny.hha.logic.Fx.launchPlayer(p, Vec3d(0.0, 0.8, 0.0))
                        }
                    }
                }
                wait(2)
            }
            a.play(AriusPose.STAGGER)
            f.openArius(24)
            wait(10)
        } finally {
            countering = false
        }
    }

    // --- A5: Frostgriff ------------------------------------------------------------------------------------------------

    fun grip(target: ServerPlayerEntity): Script = script {
        val a = f.arius ?: return@script
        f.face(a, target.entityPos)
        val ring = f.calls.ring(target.entityPos, 2.2f, AriusTimings.ARIUS_WINDUP[AriusPose.GRIP_CAST])
        a.play(AriusPose.GRIP_CAST, ring.ticks)
        f.sound("arius.grip", target.entityPos, 1.6f, 0.9f)
        wait(ring.ticks)
        val caught = ring.hits(f.players()).firstOrNull { Hold.canGrab(it) && !Hold.inGrace(it) }
        if (caught == null) {
            a.play(AriusPose.STAGGER)
            f.openArius(AriusTimings.GRIP_OPEN)
            wait(AriusTimings.GRIP_OPEN)
            return@script
        }
        Hold.freeze(caught, 22)
        f.fx(FxKind.FROST_SHELL, caught.entityPos, life = 22, e1 = caught)
        f.sound("arius.grip", caught.entityPos, 2f, 1.2f)
        var n = 0
        while (n++ < 8 && !f.stepToward(a, caught.entityPos.subtract(flatDir(a.entityPos, caught.entityPos).multiply(1.4)), 0.9)) yield(Unit)
        f.face(a, caught.entityPos)
        a.play(AriusPose.POMMEL, 6)
        wait(6)
        if (Hold.isHeld(caught) && f.canSequence(caught)) {
            f.hurtHeld(caught, 10f)
        }
        if (Hold.isHeld(caught) && f.canSequence(caught)) {
            f.sound("hold.impact", caught.entityPos, 2f)
            val dir = flatDir(a.entityPos, caught.entityPos)
            Hold.switchToLaunch(caught, dir.multiply(0.8).add(0.0, 0.3, 0.0), 20, f.clampToArena(caught.entityPos.add(dir.multiply(4.0)), 1.5), f.landingSpot(caught))
        }
        f.openArius(AriusTimings.GRIP_OPEN)
        wait(AriusTimings.GRIP_OPEN)
    }

    // --- A6: Gletscherstoß ---------------------------------------------------------------------------------------------

    fun stabGround(): Script = script {
        val a = f.arius ?: return@script
        a.sheathed = false
        val center = a.entityPos
        val group = f.calls.boss()
        val windup = AriusTimings.ARIUS_WINDUP[AriusPose.STAB_GROUND]
        val bands = listOf(4.0f to windup, 8.0f to windup + 10, 12.0f to windup + 20)
        val shapes = bands.map { (r, t) -> group.ring(center, r + 0.8f, t, inner = r - 0.8f) }
        a.play(AriusPose.STAB_GROUND, windup)
        f.sound("arius.guard", center, 1.4f, 0.7f)
        var elapsed = 0
        for ((i, shape) in shapes.withIndex()) {
            wait(shape.ticks - elapsed)
            elapsed = shape.ticks
            if (i == 0) f.sound("arius.stab_ground", center, 2.4f)
            for (p in shape.hits(f.players())) if (f.grounded(p)) f.hurt(p, 11f, Damage.Kind.IMPACT)
            Vfx.shockwave(f.world, style, center, bands[i].first + 0.8f, 12, 1.4f)
        }
        f.openArius(AriusTimings.STAB_OPEN)
        wait(AriusTimings.STAB_OPEN)
    }

    // --- W4: Null-Schnitt ------------------------------------------------------------------------------------------------

    fun nullCut(target: ServerPlayerEntity): Script = script {
        val a = f.arius ?: return@script
        a.sheathed = false
        f.face(a, target.entityPos)
        val dir = flatDir(a.entityPos, target.entityPos)
        val to = f.clampToArena(a.entityPos.add(dir.multiply(24.0)), 0.5)
        val line = f.calls.line(a.entityPos, to, 1.4f, AriusTimings.ARIUS_WINDUP[AriusPose.NULL_CUT], Telegraph.Pace.HEAVY)
        a.play(AriusPose.NULL_CUT, line.ticks)
        f.sound("iai.glint", a.entityPos, 1.6f, 1.3f)
        wait(line.ticks)
        f.sound("arius.null_cut", a.entityPos, 2.4f)
        // Trennt die Verbindung zur Deckung: trifft auch hinter Säulen und Eiskämmen.
        for (p in line.hits(f.players())) f.hurt(p, 16f, Damage.Kind.NULL_CUT)
        f.fx(FxKind.SEAM, a.entityPos.add(0.0, 1.1, 0.0), to.add(0.0, 1.1, 0.0), a = 1.6f, life = 16)
        f.fx(FxKind.SLASH_ARC, a.entityPos.add(0.0, 1.1, 0.0), dir, a = 7f, b = 0f, life = 14)
        f.fx(FxKind.DASH_STREAK, a.entityPos, to, life = 10)
        Vfx.crescent(f.world, style, a.entityPos.add(0.0, 1.1, 0.0), dir, 24f, 1.0f, 8)
        wait(16)
    }

    // --- Anpassung: Pfeilschnitt -----------------------------------------------------------------------------------------

    private fun arrowWalk(target: ServerPlayerEntity): Script = script {
        val a = f.arius ?: return@script
        f.arrowCutUntil = f.now + 120
        var n = 0
        while (n++ < 120 && flatDist(a, target) > 4) {
            f.stepToward(a, target.entityPos, 0.17)
            yield(Unit)
        }
        f.arrowCutUntil = 0
    }

    /** Geschoss von vorn zerschneiden (Pfeilschnitt / Haltung). */
    fun cutProjectile(projectile: Entity?) {
        val a = f.arius ?: return
        projectile?.discard()
        if (!acting || a.pose == AriusPose.GUARD || a.pose == AriusPose.IDLE) {
            if (a.pose != AriusPose.GUARD && a.pose != AriusPose.IAI_STANCE) a.play(AriusPose.ARROW_CUT)
        }
        f.sound("arius.arrow_cut", a.entityPos, 1.6f, 1f + rnd.nextFloat() * 0.2f)
        Vfx.crescent(f.world, style, a.entityPos.add(0.0, 1.3, 0.0), f.facing(a), 1.6f, 1.2f, 5)
    }

    /** Seitschritt nach drei Treffern außerhalb eines Fensters (höchstens alle 4 s, nur zwischen Angriffen). */
    fun sidestepFrom(player: ServerPlayerEntity) {
        val a = f.arius ?: return
        if (acting || countering || f.now < nextSidestep) return
        nextSidestep = f.now + 80
        val away = flatDir(player.entityPos, a.entityPos)
        val sign = if (rnd.nextBoolean()) 1 else -1
        val side = Vec3d(-away.z * sign, 0.0, away.x * sign)
        a.sideSign = sign
        a.play(AriusPose.SIDESTEP)
        f.sound("arius.wind_step", a.entityPos, 1.2f, 1.3f)
        val goal = f.clampToArena(a.entityPos.add(side.multiply(2.2)), 1.0)
        f.task(script {
            repeat(6) { f.stepToward(a, goal, 0.4, faceIt = false); f.face(a, player.entityPos); yield(Unit) }
        })
    }
}

/** Kurz verlangsamt (Frostbahn). */
private fun slowDown(p: ServerPlayerEntity, ticks: Int) {
    p.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 1, false, true, true))
}

package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.net.FxKind
import net.minecraft.entity.Entity
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ariusʼ Moveset (Konzept 3.1, 5.2). Jede Attacke: Ansage über [Telegraph] (Form = Trefferfläche), Pose mit genau
 * derselben Ausholzeit, Treffer zur Trefferzeit, danach ein echtes Fenster (goldene Rune).
 */
class AriusMoves(private val f: EirholmFight) {

    private val rnd = java.util.Random()
    private var nextGuard = 0L
    private var nextWall = 0L
    private var nextGrip = 0L
    private var nextStab = 0L
    private var nextNull = 0L
    private var nextSidestep = 0L
    private var nextBlade = 0L
    private var nextThorns = 0L

    /** Die gerade allein fliegende Klinge (Item-Anzeige), falls ein Klingenflug läuft. */
    private var flyingBlade: net.minecraft.entity.decoration.DisplayEntity.ItemDisplayEntity? = null

    /** Läuft gerade eine Attacke (dann keine Seitschritte/Pfeilschnitt-Posen dazwischen)? */
    var acting = false
        private set

    /** Läuft eine Konter-Sequenz (Parade & Tritt, Luftkombination)? */
    var countering = false
        private set

    private val style = Vfx.Style.HEAVEN

    // --- Grundlagen -----------------------------------------------------------------------------------------------------

    private fun walkSpeed(white: Boolean, dist: Double) = when {
        dist > 10 -> if (white) 0.36 else 0.3
        else -> if (white) 0.26 else 0.2
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

    /** Geht auf das Ziel zu, bis es näher als [range] ist (höchstens [maxTicks]). */
    private suspend fun SequenceScope<Unit>.approach(target: ServerPlayerEntity, range: Double, maxTicks: Int, white: Boolean) {
        val a = f.arius ?: return
        a.play(AriusPose.IDLE)
        var n = 0
        while (n++ < maxTicks && target.isAlive && flatDist(a, target) > range) {
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
        a.play(AriusPose.IDLE)
        repeat(ticks) {
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
    private fun slash(at: Vec3d, dir: Vec3d, range: Float) {
        val mid = at.add(0.0, 1.1, 0.0)
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
        val t = f.target() ?: return@script wait(10)
        val hpFrac = f.ariusHp / f.ariusMax
        val unlocked = hpFrac <= 0.7f
        val serious = hpFrac <= 0.4f
        val d = flatDist(a, t)
        val now = f.now
        acting = true
        try {
            when {
                f.adapt == EirholmFight.Adapt.ARROWS && d > 6 -> {
                    yieldAll(arrowWalk(t))
                    yieldAll(combo(false, serious))
                }
                now >= nextGuard && d < 6 && rnd.nextFloat() < 0.3f -> {
                    nextGuard = now + 400
                    yieldAll(guard(false, serious))
                }
                serious && now >= nextThorns && d in 4.0..20.0 && rnd.nextFloat() < 0.4f -> {
                    nextThorns = now + 400
                    yieldAll(frostThorns(false))
                }
                unlocked && now >= nextBlade && d in 5.0..18.0 && rnd.nextFloat() < 0.35f -> {
                    nextBlade = now + 560
                    yieldAll(flyingBladeAttack(false))
                }
                unlocked && now >= nextWall && rnd.nextFloat() < 0.45f -> {
                    nextWall = now + 520
                    yieldAll(wallStrip(serious))
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
        } finally {
            acting = false
        }
        yieldAll(script { footwork(if (serious) 10 else 18, false) })
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
        val ticks = 70
        for (p in f.audience()) dev.henny.hha.logic.boss.Cine.title(listOf(p), "hha_arius.feat.frozen_star", "hha_arius.feat.frozen_star.sub", 50)
        f.fx(FxKind.FROZEN_STAR, spot, a = 9f, b = ticks.toFloat(), life = ticks + 6)
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
        val line = f.calls.line(from, to, 5f, 40, Telegraph.Pace.HEAVY)
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
        val t = f.target() ?: return@script wait(10)
        val d = flatDist(a, t)
        val now = f.now
        acting = true
        try {
            when {
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
                now >= nextBlade && rnd.nextFloat() < 0.4f -> {
                    nextBlade = now + 420
                    yieldAll(flyingBladeAttack(true))
                }
                now >= nextThorns && rnd.nextFloat() < 0.45f -> {
                    nextThorns = now + 320
                    yieldAll(frostThorns(true))
                }
                now >= nextGuard && d < 6 && rnd.nextFloat() < 0.3f -> {
                    nextGuard = now + 360
                    yieldAll(guard(true, false))
                }
                d > 6 -> yieldAll(lunge(t, true))
                else -> yieldAll(combo(true, false))
            }
        } finally {
            acting = false
        }
        yieldAll(script { footwork(8, true) })
    }

    /** Ein Zug ohne Nivaris (sie ist niedergerungen): weißes Moveset, etwas mehr Pausen. */
    fun turnWhiteSolo(): Script = script {
        yieldAll(turnWhite())
        yieldAll(script { footwork(10, true) })
    }

    /**
     * Begleitung, während Nivaris führt: ein einzelner Hieb gegen einen Spieler ohne laufende Boss-Ansage, sonst
     * Stellungswechsel in die Flanke.
     */
    fun support(): Script = script {
        val a = f.arius ?: return@script
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
                wait(12)
            } else {
                val t = f.target() ?: return@script wait(10)
                val dragon = f.nivaris
                val ref = dragon?.entityPos ?: f.arenaCenter
                val away = flatDir(ref, t.entityPos)
                val flank = f.clampToArena(t.entityPos.add(Vec3d(-away.z, 0.0, away.x).multiply(6.0)), 2.0)
                repeat(30) {
                    if (f.stepToward(a, flank, 0.24)) return@repeat
                    yield(Unit)
                }
                f.face(a, t.entityPos)
                wait(10)
            }
        } finally {
            acting = false
        }
    }

    // --- Klingenflug (seit 1.2.2): Solvane fliegt allein, Arius tritt ----------------------------------------------------

    /**
     * Klingenflug: Arius lässt die Klinge los, sie wirbelt magisch durch die Luft (flach gelegt, dreht wie ein Rotor) und
     * fliegt zwei bzw. drei angesagte Bahnen auf Spieler. Gleichzeitig rückt Arius selbst nach und tritt: Drehtritt,
     * dann Fronttritt, der weit zurückwirft. Danach kehrt die Klinge in seine Hand zurück. Die Bahnen laufen als eigene
     * Aufgabe neben seinen Tritten.
     */
    fun flyingBladeAttack(white: Boolean): Script = script {
        val a = f.arius ?: return@script
        val t0 = f.target() ?: return@script
        val keep = a.bladeState
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
                    // Steigen und Ausrichten über dem Ziel-Rand, wirbelnd; dann Ansage und Flug quer durch das Ziel.
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
                // Zurück in die Hand
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
        // Arius: nachrücken und treten, solange die Klinge fliegt.
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
                    dev.henny.hha.logic.Fx.launchPlayer(p, dir.multiply(if (front) 1.25 else 0.8).add(0.0, if (front) 0.45 else 0.3, 0.0))
                }
                shown0 { Vfx.shockwave(f.world, style, a.entityPos.add(dir.multiply(1.6)), if (front) 2.6f else 2.0f, 10, 0.8f) }
                wait(if (front) 14 else 8)
            }
            waitUntil(120) { done }
        } finally {
            flyingBlade?.discard()
            flyingBlade = null
            f.arius?.bladeState = keep
            f.sound("arius.guard", a.entityPos, 1.4f, 1.4f)
        }
        f.openArius(18)
        wait(18)
    }

    private inline fun shown0(block: () -> Unit) = block()

    // --- Frostdornen (seit 1.2.2, angelehnt an Dornenmagie eines Ritters aus Elden Ring) -----------------------------

    /**
     * Frostdornen: Arius reißt die Klinge über den Boden, eine Reihe Eisdornen bricht in schneller Folge von ihm bis
     * hinter das Ziel aus dem Boden (angesagte Linie). Wer getroffen wird, wird aufgespießt hochgeschleudert. In der
     * weißen Form folgt ein Dornenkranz um ihn selbst (angesagter Ring). Die Dornen stehen kurz und zersplittern dann.
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
        val placed = ArrayList<net.minecraft.util.math.BlockPos>()
        val len = sqrt(from.squaredDistanceTo(to))
        val steps = (len / 1.5).toInt().coerceAtLeast(3)
        var struck = false
        for (i in 1..steps) {
            val p = from.lerp(to, i / steps.toDouble())
            placed += spike(p.add(dir.multiply(0.0)), 2 + rnd.nextInt(2))
            placed += spike(p.add(Vec3d(-dir.z, 0.0, dir.x).multiply(if (i % 2 == 0) 0.9 else -0.9)), 1 + rnd.nextInt(2))
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
                placed += spike(a.entityPos.add(cos(ang) * 3.2, 0.0, sin(ang) * 3.2), 3)
            }
            for (pl in ring.hits(f.players())) {
                f.hurt(pl, 8f)
                dev.henny.hha.logic.Fx.launchPlayer(pl, flatDir(a.entityPos, pl.entityPos).multiply(0.6).add(0.0, 0.7, 0.0))
            }
        }
        wait(26)
        for (pos in placed) {
            if (rnd.nextInt(2) == 0) f.world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK, net.minecraft.block.Blocks.PACKED_ICE.defaultState), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 6, 0.3, 0.3, 0.3, 0.1)
        }
        if (placed.isNotEmpty()) f.sound("transform.shatter", a.entityPos, 1.5f, 1.6f)
        f.edits.revert(placed)
        wait(8)
    }

    /** Ein Eisdorn (h Blöcke, blaue Spitze) auf der Lauffläche, nur auf freiem Boden und nie in einem Spieler. */
    private fun spike(at: Vec3d, h: Int): List<net.minecraft.util.math.BlockPos> {
        val ground = Vec3d(at.x, f.floorY, at.z)
        if (!f.passable(ground)) return emptyList()
        if (f.players().any { it.squaredDistanceTo(ground.add(0.0, 1.0, 0.0)) < 1.2 }) return emptyList()
        val base = net.minecraft.util.math.BlockPos.ofFloored(ground)
        if (f.world.getBlockState(base.down()).isAir) return emptyList()
        val list = ArrayList<Pair<net.minecraft.util.math.BlockPos, net.minecraft.block.BlockState>>()
        for (y in 0 until h) {
            val pos = base.up(y)
            if (!f.world.getBlockState(pos).isAir) break
            list += pos to (if (y == h - 1) net.minecraft.block.Blocks.BLUE_ICE else net.minecraft.block.Blocks.PACKED_ICE).defaultState
        }
        return f.edits.fill(list)
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

        // Hieb 2 (Folgehieb)
        t = f.target() ?: return@script
        f.face(a, t.entityPos)
        dir = f.facing(a)
        val c2 = f.calls.cone(a.entityPos, dir, 4.5f, 55f, AriusTimings.ARIUS_WINDUP[AriusPose.COMBO_2], Telegraph.Pace.FOLLOW_UP)
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
        for (p in c4.hits(f.players())) f.hurt(p, 12f)
        slash(a.entityPos, dir, 4f)
        f.openArius(22)
        wait(22)
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
            wait(4)
        }
    }

    // --- A3: Frostwand → Froststreifen -------------------------------------------------------------------------------

    fun wallStrip(serious: Boolean): Script = script {
        val a = f.arius ?: return@script
        val t = f.farthestFrom(a.entityPos) ?: return@script
        f.face(a, t.entityPos)
        val toT = flatDir(a.entityPos, t.entityPos)
        val side = Vec3d(-toT.z, 0.0, toT.x).multiply(if (rnd.nextBoolean()) 1.0 else -1.0)
        val mid = t.entityPos.add(side.multiply(3.5))
        val wallFrom = f.clampToArena(mid.subtract(toT.multiply(7.0)), 1.0)
        val wallTo = f.clampToArena(mid.add(toT.multiply(7.0)), 1.0)
        val wall = f.calls.line(wallFrom, wallTo, 1.6f, AriusTimings.ARIUS_WINDUP[AriusPose.WALL_CAST])
        a.play(AriusPose.WALL_CAST, wall.ticks)
        f.sound("arius.ice_wall", mid, 2f, 0.8f)
        wait(wall.ticks)
        for (p in wall.hits(f.players())) {
            f.hurt(p, 6f, Damage.Kind.IMPACT)
            dev.henny.hha.logic.Fx.launchPlayer(p, side.multiply(-0.7).add(0.0, 0.3, 0.0))
        }
        f.raiseWall(wallFrom, wallTo, 3, AriusTimings.WALL_LIFE)
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

    // --- A4 / W3: Parierhaltung ----------------------------------------------------------------------------------------

    fun guard(white: Boolean, serious: Boolean): Script = script {
        val a = f.arius ?: return@script
        f.target()?.let { f.face(a, it.entityPos) }
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
            waitUntil(40) { !countering }
            return@script
        }
        a.play(AriusPose.STAGGER)
        f.openArius(AriusTimings.GUARD_UNTOUCHED_OPEN)
        wait(AriusTimings.GUARD_UNTOUCHED_OPEN)
    }

    /** Vom Treffer-Handler: Nahkampftreffer in der Haltung → Parade & Tritt bzw. Luftkombination. */
    fun counter(player: ServerPlayerEntity, white: Boolean) {
        val a = f.arius ?: return
        f.sound("arius.parry", a.entityPos, 2.4f, 1.1f)
        Vfx.flare(f.world, style, a.entityPos.add(0.0, 1.3, 0.0), 1.4f, 6)
        if (!Hold.canGrab(player)) {
            dev.henny.hha.logic.Fx.launchPlayer(player, flatDir(a.entityPos, player.entityPos).multiply(0.9).add(0.0, 0.3, 0.0))
            return
        }
        countering = true
        f.task(if (white) airCombo(player) else parryKick(player))
    }

    private fun parryKick(player: ServerPlayerEntity): Script = script {
        val a = f.arius ?: run { countering = false; return@script }
        try {
            Hold.freeze(player, 18)
            f.face(a, player.entityPos)
            a.play(AriusPose.PARRY_KICK, AriusTimings.ARIUS_WINDUP[AriusPose.PARRY_KICK])
            wait(AriusTimings.ARIUS_WINDUP[AriusPose.PARRY_KICK])
            if (!Hold.isHeld(player) || !f.canSequence(player)) return@script
            val dir = flatDir(a.entityPos, player.entityPos)
            f.hurtHeld(player, 8f)
            if (!Hold.isHeld(player) || !f.canSequence(player)) return@script
            f.sound("arius.kick", player.entityPos, 2.4f)
            f.sound("hold.impact", player.entityPos, 1.8f)
            f.calls.hitShake(player.entityPos, 3f, 8f)
            val landing = f.clampToArena(player.entityPos.add(dir.multiply(7.0)), 1.5)
            Hold.switchToLaunch(player, dir.multiply(1.15).add(0.0, 0.42, 0.0), 26, landing, f.landingSpot(player))
            wait(10)
        } finally {
            countering = false
        }
    }

    private fun airCombo(player: ServerPlayerEntity): Script = script {
        val a = f.arius ?: run { countering = false; return@script }
        try {
            Hold.freeze(player, 24)
            f.face(a, player.entityPos)
            a.play(AriusPose.LAUNCH, AriusTimings.ARIUS_WINDUP[AriusPose.LAUNCH])
            wait(AriusTimings.ARIUS_WINDUP[AriusPose.LAUNCH])
            if (!Hold.isHeld(player) || !f.canSequence(player)) return@script
            f.hurtHeld(player, 6f)
            if (!Hold.isHeld(player) || !f.canSequence(player)) return@script
            f.sound("arius.slash", player.entityPos, 2f, 1.25f)
            val start = player.entityPos
            val apex = start.add(0.0, 4.0, 0.0)
            Hold.freeze(player, 20, apex)
            player.teleport(f.world, apex.x, apex.y, apex.z, emptySet(), player.yaw, player.pitch, false)
            // Arius springt nach.
            val ground = a.entityPos
            a.play(AriusPose.AIR_KICK, AriusTimings.ARIUS_WINDUP[AriusPose.AIR_KICK])
            val ticks = AriusTimings.ARIUS_WINDUP[AriusPose.AIR_KICK]
            for (i in 1..ticks) {
                val k = i / ticks.toDouble()
                val pos = ground.lerp(apex.subtract(flatDir(ground, apex).multiply(1.2)), k).add(0.0, sin(k * Math.PI) * 0.8, 0.0)
                f.place(a, Vec3d(pos.x, ground.y + (apex.y - ground.y - 0.6) * k, pos.z))
                f.face(a, apex)
                yield(Unit)
            }
            if (!Hold.isHeld(player) || !f.canSequence(player)) return@script
            f.hurtHeld(player, 8f)
            if (!Hold.isHeld(player) || !f.canSequence(player)) return@script
            f.sound("arius.kick", apex, 2.4f, 0.8f)
            f.calls.hitShake(apex, 3f, 8f)
            val dir = flatDir(ground, apex)
            val landing = f.clampToArena(apex.add(dir.multiply(4.0)).let { Vec3d(it.x, f.floorY, it.z) }, 1.5)
            Hold.switchToLaunch(player, dir.multiply(0.7).add(0.0, -0.9, 0.0), 20, landing, f.landingSpot(player))
            for (i in 1..6) {
                f.place(a, a.entityPos.lerp(Vec3d(a.x, f.floorY, a.z), i / 6.0))
                yield(Unit)
            }
            wait(8)
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
            if (a.pose != AriusPose.GUARD) a.play(AriusPose.ARROW_CUT)
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

    @Suppress("unused")
    private fun ring(center: Vec3d, radius: Double, angle: Double) = Vec3d(center.x + sin(angle) * radius, center.y, center.z + cos(angle) * radius)
}

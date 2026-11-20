package dev.henny.hha_arius.boss

import dev.henny.hha.logic.boss.Cine
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.net.FxKind
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.decoration.Brightness
import net.minecraft.entity.decoration.DisplayEntity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.AffineTransformation
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Bausteine für die großen Momente (seit 1.9.0): Flugbahnen nach echter Spielerphysik, ein Aussichtspunkt, damit der
 * Server das ferne Gelände lädt, das die Kamera zeigt, und sichtbare Eisbrocken als Block-Anzeigen (keine Kollision,
 * keine Blockänderung, nichts versperrt einen Fluchtweg).
 */

/** Flugbahnen für Spieler: Vanilla-Physik (Schwerkraft 0,08, senkrecht ×0,98, waagerecht in der Luft ×0,91). */
object Ballistic {

    /** Ticks, bis ein mit [vy] gestarteter Körper nach dem Scheitel wieder auf Höhe [dy] (relativ) ankommt. */
    fun ticks(dy: Double, vy: Double): Int {
        var y = 0.0
        var v = vy
        var n = 0
        while (n < 100) {
            y += v
            v = (v - 0.08) * 0.98
            n++
            if (v < 0.0 && y <= dy) break
        }
        return n
    }

    /** Startgeschwindigkeit, mit der ein Spieler von [from] mit Steigen [vy] bei [to] landet. */
    fun velocity(from: Vec3d, to: Vec3d, vy: Double): Vec3d {
        val n = ticks(to.y - from.y, vy)
        val dx = to.x - from.x
        val dz = to.z - from.z
        val dist = sqrt(dx * dx + dz * dz)
        if (dist < 1e-3) return Vec3d(0.0, vy, 0.0)
        val reach = (1.0 - 0.91.pow(n)) / 0.09
        val h = dist / reach
        return Vec3d(dx / dist * h, vy, dz / dist * h)
    }
}

/**
 * Aussichtspunkt für Szenen mit fernem Gelände (Berg, Schnitt, Auftritt): Der Server schickt Chunks nur um den
 * Spieler, nicht um die Szenenkamera. Solange die Kamera weit weg filmt, stehen die Körper der Zuschauer unsichtbar an
 * einem Punkt zwischen Kamera und Ziel; [restore] bringt sie genau dorthin zurück, wo sie waren.
 */
class Vantage(private val f: EirholmFight) {

    private class Saved(val pos: Vec3d, val yaw: Float, val pitch: Float, val invisible: Boolean, val resist: Boolean)

    private val saved = LinkedHashMap<UUID, Saved>()

    val active: Boolean get() = saved.isNotEmpty()

    /** Stellt alle Zuschauer (unsichtbar, geschützt) auf festen Boden bei [at]. */
    fun move(at: Vec3d) {
        if (Cine.muted) return
        val spot = ground(f, at)
        for ((i, p) in f.audience().withIndex()) {
            if (Hold.isHeld(p) || p.isSpectator) continue
            if (p.uuid !in saved) {
                val invisible = !p.hasStatusEffect(StatusEffects.INVISIBILITY)
                val resist = !p.hasStatusEffect(StatusEffects.RESISTANCE)
                saved[p.uuid] = Saved(p.entityPos, p.yaw, p.pitch, invisible, resist)
                if (invisible) p.addStatusEffect(StatusEffectInstance(StatusEffects.INVISIBILITY, 20 * 60, 0, false, false, false))
                if (resist) p.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 20 * 60, 4, false, false, false))
            }
            val s = spot.add((i % 3 - 1) * 1.2, 0.0, (i / 3) * 1.2)
            p.teleport(f.world, s.x, s.y, s.z, emptySet(), p.yaw, p.pitch, false)
            p.onLanding()
        }
    }

    /** Alle zurück an ihren Platz (auch nach Überspringen oder Abbruch). */
    fun restore() {
        for ((id, s) in saved) {
            val p = f.server.playerManager.getPlayer(id) ?: continue
            if (p.entityWorld !== f.world) continue
            p.teleport(f.world, s.pos.x, s.pos.y, s.pos.z, emptySet(), s.yaw, s.pitch, false)
            p.onLanding()
            if (s.invisible) p.removeStatusEffect(StatusEffects.INVISIBILITY)
            if (s.resist) p.removeStatusEffect(StatusEffects.RESISTANCE)
        }
        saved.clear()
    }

    /** Wie [restore], aber an neue Plätze (je Zuschauer); Schutz und Unsichtbarkeit enden. */
    fun releaseTo(spot: (ServerPlayerEntity, Int) -> Vec3d) {
        var i = 0
        for ((id, s) in saved) {
            val p = f.server.playerManager.getPlayer(id) ?: continue
            if (p.entityWorld !== f.world) continue
            val to = spot(p, i++)
            p.teleport(f.world, to.x, to.y, to.z, emptySet(), s.yaw, s.pitch, false)
            p.onLanding()
            if (s.invisible) p.removeStatusEffect(StatusEffects.INVISIBILITY)
            if (s.resist) p.removeStatusEffect(StatusEffects.RESISTANCE)
        }
        saved.clear()
    }

    companion object {
        /** Fester Boden nahe [at] nach dem Plan der Fernlandschaft (absolut). */
        fun ground(f: EirholmFight, at: Vec3d): Vec3d {
            val rx = floor(at.x).toInt() - f.origin.x
            val rz = floor(at.z).toInt() - f.origin.z
            val top = runCatching { FarPlan.top(rx, rz) }.getOrDefault(-1)
            val y = f.origin.y + max(top + 1, 0).toDouble()
            val guess = Vec3d(floor(at.x) + 0.5, y, floor(at.z) + 0.5)
            return Hold.safeSpot(f.world, guess) ?: guess
        }
    }
}

/**
 * Sichtbares Eis als Block-Anzeigen: Dornen, Splitter, Schollen. Keine Kollision und keine Blockänderung (seit 1.9.0
 * keine Eiswände und keine Dornenreihen mehr, die Wege versperren). Wachsen und Schrumpfen laufen über die Interpolation
 * des Clients; der Kampf ruft jeden Tick [tick] und am Ende [clear].
 */
class Shards(private val f: EirholmFight) {

    private class Piece(
        val e: DisplayEntity.BlockDisplayEntity, val born: Long, val grow: Int, val until: Long,
        val size: Vector3f, val rot: Quaternionf,
    ) {
        var grown = false
        var shrinking = false
    }

    private val live = ArrayList<Piece>()

    /** Ein Eisdorn bei [at] (Fuß auf der Höhe von [at]), [h] hoch, steht [life] Ticks. */
    fun spike(at: Vec3d, h: Double, life: Int, width: Double = 0.7, tilt: Double = 0.2, state: BlockState = Blocks.BLUE_ICE.defaultState) {
        if (live.size > 220) return
        val rnd = java.util.concurrent.ThreadLocalRandom.current()
        val rot = Quaternionf().rotateY((rnd.nextDouble() * Math.PI * 2).toFloat()).rotateX(((rnd.nextDouble() - 0.5) * 2 * tilt).toFloat())
            .rotateZ(((rnd.nextDouble() - 0.5) * 2 * tilt).toFloat())
        spawn(state, at, Vector3f(width.toFloat(), h.toFloat(), width.toFloat()), rot, grow = 3, life = life, baseAnchored = true)
    }

    /** Ein Eisbrocken (Mitte bei [at]) beliebiger Größe; Drehung frei. */
    fun chunk(state: BlockState, at: Vec3d, size: Vector3f, rot: Quaternionf, life: Int, grow: Int = 0): DisplayEntity.BlockDisplayEntity? =
        spawn(state, at, size, rot, grow, life, baseAnchored = false)

    private fun spawn(state: BlockState, at: Vec3d, size: Vector3f, rot: Quaternionf, grow: Int, life: Int, baseAnchored: Boolean): DisplayEntity.BlockDisplayEntity? {
        val e = EntityType.BLOCK_DISPLAY.create(f.world, SpawnReason.EVENT) ?: return null
        e.setBlockState(state)
        e.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f)
        e.setViewRange(6f)
        e.setBrightness(Brightness(12, 15))
        e.setTeleportDuration(2)
        val start = if (grow > 0) Vector3f(size.x * 0.6f, if (baseAnchored) 0.05f else size.y * 0.1f, size.z * 0.6f) else size
        e.setTransformation(transform(start, rot, baseAnchored))
        f.world.spawnEntity(e)
        live.add(Piece(e, f.now, grow, f.now + life.coerceAtLeast(grow + 4), size, rot).also { it.grown = grow <= 0 })
        if (!baseAnchored) anchored.add(e.id)
        return e
    }

    /** Ids der mittig verankerten Brocken (Schollen); Dornen stehen auf ihrem Fuß. */
    private val anchored = HashSet<Int>()

    /** Neue Lage eines Brockens (Mitte [at], Drehung [rot]), weich über [ticks]. */
    fun move(e: DisplayEntity.BlockDisplayEntity, at: Vec3d, rot: Quaternionf, size: Vector3f, ticks: Int = 2) {
        if (e.isRemoved) return
        e.setTeleportDuration(ticks)
        e.refreshPositionAndAngles(at.x, at.y, at.z, 0f, 0f)
        e.setTransformation(transform(size, rot, e.id !in anchored))
        e.setInterpolationDuration(ticks)
        e.setStartInterpolation(0)
    }

    fun remove(e: DisplayEntity.BlockDisplayEntity) {
        e.discard()
        anchored.remove(e.id)
        live.removeIf { it.e === e }
    }

    fun tick() {
        if (live.isEmpty()) return
        val now = f.now
        val it = live.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.e.isRemoved) {
                anchored.remove(p.e.id)
                it.remove()
                continue
            }
            if (!p.grown && now >= p.born + 1) {
                p.grown = true
                p.e.setTransformation(transform(p.size, p.rot, p.e.id !in anchored))
                p.e.setInterpolationDuration(p.grow)
                p.e.setStartInterpolation(0)
            }
            if (!p.shrinking && now >= p.until - 4) {
                p.shrinking = true
                val small = Vector3f(p.size.x * 0.3f, if (p.e.id !in anchored) 0.02f else p.size.y * 0.1f, p.size.z * 0.3f)
                p.e.setTransformation(transform(small, p.rot, p.e.id !in anchored))
                p.e.setInterpolationDuration(4)
                p.e.setStartInterpolation(0)
            }
            if (now >= p.until) {
                if (p.e.id !in anchored) {
                    val c = p.e.entityPos
                    f.world.spawnParticles(
                        net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK, p.e.blockState),
                        c.x, c.y + p.size.y * 0.5, c.z, 6, 0.25, p.size.y * 0.3, 0.25, 0.08,
                    )
                }
                anchored.remove(p.e.id)
                p.e.discard()
                it.remove()
            }
        }
    }

    fun clear() {
        for (p in live) p.e.discard()
        live.clear()
        anchored.clear()
    }

    private companion object {
        /** Blockmodell (0..1) skaliert und gedreht; Dorn: Fuß mittig auf dem Ankerpunkt, Brocken: Mitte auf dem Ankerpunkt. */
        fun transform(size: Vector3f, rot: Quaternionf, foot: Boolean): AffineTransformation {
            val pivot = Vector3f(size.x * 0.5f, if (foot) 0f else size.y * 0.5f, size.z * 0.5f).rotate(Quaternionf(rot)).negate()
            return AffineTransformation(pivot, Quaternionf(rot), size, Quaternionf())
        }
    }
}

/**
 * Der Gletscher bricht (Weißer Horizont, seit 1.9.0): beidseits der Naht lösen sich riesige Schollen (Block-Anzeigen bis
 * 14 Blöcke) von der Bergflanke – der Riss läuft vom Fuß den Berg hinauf –, kippen nach außen, rutschen ab und versinken.
 * Bei jedem Bruch eine Staub- und Eissplitterwolke, ein tiefer Schlag. Die echte Kluft gräbt [Scenes.split].
 */
class GlacierCollapse(private val f: EirholmFight, private val cutX: Double, private val z0: Double, private val z1: Double) {

    private class Slab(
        val e: DisplayEntity.BlockDisplayEntity, val side: Double, val size: Vector3f, val delay: Int,
        val spin: Float, val yaw: Float, var pos: Vec3d, val floor: Double,
    ) {
        var vel = Vec3d.ZERO
        var angle = 0f
        var broke = false
        var gone = false
    }

    private val slabs = ArrayList<Slab>()

    /** Dauer, bis der Riss das obere Ende erreicht. */
    val crackTicks = 70

    /** Legt die Schollen an (absolut, auf der Flanke beidseits der Kluft). */
    fun build(count: Int = 26) {
        val rnd = java.util.Random(f.origin.asLong() xor 0x51A8L)
        for (k in 0 until count) {
            val u = (k + rnd.nextDouble() * 0.6) / count
            val zr = z0 + (z1 - z0) * u
            val side = if (k % 2 == 0) 1.0 else -1.0
            val w = 6f + rnd.nextFloat() * 8f
            val h = 4f + rnd.nextFloat() * 5f
            val d = 6f + rnd.nextFloat() * 8f
            val xr = cutX + side * (3.5 + w * 0.45)
            val top = runCatching { FarPlan.top(floor(xr).toInt(), floor(zr).toInt()) }.getOrDefault(-1)
            if (top < 2) continue
            val surface = f.origin.y + top + 1.0
            val center = Vec3d(f.origin.x + xr, surface - h * 0.35, f.origin.z + zr)
            val state = when {
                top > 120 -> if (rnd.nextInt(3) == 0) Blocks.BLUE_ICE.defaultState else Blocks.SNOW_BLOCK.defaultState
                top > 60 -> if (rnd.nextBoolean()) Blocks.PACKED_ICE.defaultState else Blocks.SNOW_BLOCK.defaultState
                else -> listOf(Blocks.PACKED_ICE, Blocks.SNOW_BLOCK, Blocks.STONE, Blocks.BLUE_ICE)[rnd.nextInt(4)].defaultState
            }
            val size = Vector3f(w, h, d)
            val yaw = (rnd.nextFloat() - 0.5f) * 0.5f
            val e = f.shards.chunk(state, center, size, Quaternionf().rotateY(yaw), life = 20 * 30) ?: continue
            slabs += Slab(e, side, size, (u * crackTicks).toInt(), 0.8f + rnd.nextFloat() * 1.6f, yaw, center, surface - 30.0)
        }
    }

    /** Ablauf: Riss läuft hinauf, Schollen brechen, kippen, rutschen und versinken. [sound] = Szenenklang (Lautstärke ab Kamera). */
    fun run(total: Int, sound: (String, Vec3d, Float, Float) -> Unit): Script = script {
        for (t in 0 until total) {
            for (s in slabs) {
                if (s.gone) continue
                val age = t - s.delay
                if (age < -10) continue
                if (age < 0) {
                    // Zittern kurz vor dem Bruch
                    if (t % 2 == 0) {
                        val j = Vec3d((Math.random() - 0.5) * 0.3, 0.0, (Math.random() - 0.5) * 0.3)
                        f.shards.move(s.e, s.pos.add(j), Quaternionf().rotateY(s.yaw), s.size)
                    }
                    continue
                }
                if (!s.broke) {
                    s.broke = true
                    if (!Cine.muted) {
                        Entrance.storyFx(f, FxKind.DEBRIS_CLOUD, s.pos.add(0.0, s.size.y * 0.4, 0.0), a = s.size.x * 0.9f + 4f, life = 70)
                        if (slabs.indexOf(s) % 3 == 0) sound("glacier.crack", s.pos, 3f, 0.5f + (Math.random() * 0.3).toFloat())
                        if (slabs.indexOf(s) % 4 == 0) sound("glacier.collapse", s.pos, 4f, 0.45f + (Math.random() * 0.2).toFloat())
                    }
                }
                // Nach außen kippen, abrutschen, beschleunigt versinken
                val k = (age / 50.0).coerceAtMost(1.6)
                s.angle += (s.spin * (0.4 + 1.6 * k)).toFloat()
                s.vel = Vec3d(s.side * (0.04 + 0.12 * k), -(0.015 + 0.09 * k * k), (Math.random() - 0.5) * 0.02)
                s.pos = s.pos.add(s.vel)
                if (t % 2 == 0) {
                    val rot = Quaternionf().rotateY(s.yaw).rotateZ((-s.side * Math.toRadians(s.angle.toDouble())).toFloat())
                    f.shards.move(s.e, s.pos, rot, s.size)
                }
                if (s.pos.y < s.floor) {
                    s.gone = true
                    if (!Cine.muted) Entrance.storyFx(f, FxKind.DEBRIS_CLOUD, s.pos.add(0.0, 12.0, 0.0), a = s.size.x + 6f, life = 60)
                    f.shards.remove(s.e)
                }
            }
            yield(Unit)
        }
        clear()
    }

    fun clear() {
        for (s in slabs) if (!s.gone) f.shards.remove(s.e)
        slabs.clear()
    }
}

/**
 * Szenenklang, der die Zuschauer erreicht, auch wenn die Szenenkamera weit vom Körper der Spieler entfernt ist (die
 * Lautstärke wächst mit der Entfernung, Minecraft dämpft linear bis 16 × Lautstärke). Beim Vorspulen still.
 */
fun EirholmFight.farSound(id: String, at: Vec3d, volume: Float, pitch: Float = 1f) {
    if (Cine.muted) return
    val event = soundEvent(id) ?: return
    val far = audience().maxOfOrNull { sqrt(it.squaredDistanceTo(at)) } ?: 0.0
    val v = max(volume.toDouble(), far / 15.0 + 0.5).toFloat()
    world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, v, pitch)
    layers(id, at, v, pitch)
}

/** Vanilla-Klang als Schicht unter eigenen Klängen (Aufprall, Bruch), Lautstärke wie [farSound]. */
fun EirholmFight.farVanilla(event: SoundEvent, at: Vec3d, volume: Float, pitch: Float = 1f) {
    if (Cine.muted) return
    val far = audience().maxOfOrNull { sqrt(it.squaredDistanceTo(at)) } ?: 0.0
    val v = max(volume.toDouble(), far / 15.0 + 0.5).toFloat()
    world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, v, pitch)
}

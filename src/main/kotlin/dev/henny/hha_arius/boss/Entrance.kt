package dev.henny.hha_arius.boss

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.CineSkip
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.net.AtmosMode
import dev.henny.hha_arius.net.FxKind
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Der Auftritt von Nivaris an der Nadel (Paket V2). Wird zwischen dem Tritt durchs Tor ([Scenes.gateKick]) und
 * [Scenes.glacier] abgespielt; Ende: Nivaris ist auf dem Plateau gelandet (Entity bei (0,5 | 0 | −4,5), Blick nach
 * Norden), die Kamera gehört wieder den Spielern. Brüllen und Arius' Zeilen danach bleiben in [Scenes.gateKick].
 *
 * Ablauf (≈ 34 s, überspringbar, keine Ansagen und kein Schaden):
 * 1. Arius hebt den Blick, eine Zeile – der Schneesturm verdichtet sich, die Musik setzt fast aus.
 * 2. Im Sturm an der Nadel: einzelne Windungen gleiten vorbei, dann der Kristallkamm von oben. Nie das Ganze.
 * 3. Die Wolken reißen auf: weite Einstellung vom Rand der Schlucht, Nadel und Körper, der Kopf hebt sich über den
 *    Gipfel. Nah am Kopf: Brüllen, die Augen glühen, Name als Titelkarte.
 * 4. Sie löst sich vom Fels ([NivarisMode.UNCOIL]): Kopf voran, der Körper gleitet Glied für Glied von der Helix in die
 *    Flugbahn ([EntrancePath]); zwei Seitenfahrten zeigen die Länge über der Schlucht.
 * 5. Gleitflug über das Plateau, schwere Landung vor den Spielern.
 *
 * Ohne fertige Fernlandschaft ([EirholmFight.farReady]) gleitet sie nur kurz heran (ohne Nadel), im Training
 * erscheint sie sofort gelandet.
 */
fun entrance(f: EirholmFight): Script = script {
    val spot = f.abs(Vec3d(0.5, 0.0, -4.5))
    if (f.training != 0) {
        Entrance.landed(f, spot)
        return@script
    }
    val scene = CineScene(f) { Entrance.landed(f, spot) }
    yieldAll(scene.play(if (f.farReady) Entrance.full(f, scene, spot) else Entrance.short(f, scene, spot)))
}

/**
 * Flugbahn des **Kopfgelenks** beim Lösen von der Nadel, relativ zum Instanz-Ursprung. Server (Entity, Kamera) und
 * Client (Körper in [NivarisMode.UNCOIL]) rechnen mit derselben Tabelle. Start: aufgerichtet über dem Gipfel, Blick
 * nach Norden; Sturz nach Nordosten über die Schlucht, weiter Bogen, über den Südwall auf das Plateau; die letzten
 * 18 Blöcke gerade und waagerecht nach Norden (dort übernimmt der normale Flug ohne Sprung).
 */
object EntrancePath {
    /**
     * Die Koordinaten des Auftritts sind für eine Nadel bei z = [DESIGN_SPIRE_Z] gezeichnet. [near] hängt sie an die
     * echte [ArenaLayout.SPIRE]: Punkte im Bereich der Nadel und der Schlucht (z ≥ 92) liegen fest relativ zur Nadel,
     * Punkte über dem Plateau (z ≤ 20, Landung) bleiben, dazwischen wird anteilig verschoben (die Bahn staucht sich).
     */
    private const val DESIGN_SPIRE_Z = 150.0

    fun near(x: Double, y: Double, z: Double): Vec3d {
        val k = ((z - 20.0) / 72.0).coerceIn(0.0, 1.0)
        return Vec3d(x, y, z + (ArenaLayout.SPIRE.z - DESIGN_SPIRE_Z) * k)
    }

    /** Kopfgelenk in der Windung, aufgerichtet über dem Gipfel ([ArenaLayout.SPIRE_TOP] = 185). */
    val HEAD_RAISED = near(0.5, 192.0, 146.5)

    /** Kopfgelenk in der Windung, ruhend auf der obersten Windung (vor der Enthüllung). */
    val HEAD_REST = near(3.5, 171.0, 144.0)

    private val WAYPOINTS = listOf(
        HEAD_RAISED, near(0.5, 194.0, 134.0), near(10.0, 182.0, 114.0), near(36.0, 150.0, 92.0), near(56.0, 110.0, 66.0),
        near(48.0, 70.0, 38.0), Vec3d(22.0, 40.0, 20.0), Vec3d(4.0, 26.0, 12.0), Vec3d(0.5, 22.0, 4.0), Vec3d(0.5, 21.0, -6.0),
    )

    /** Dauer des Lösens in Ticks. */
    const val TICKS = 220

    /** Abstand der Tabellenpunkte. */
    private const val STEP = 0.5

    private val table: Array<Vec3d>
    val LENGTH: Double

    init {
        // Catmull-Rom durch die Wegpunkte, danach in gleiche Bogenlängen umgerechnet.
        val ext = ArrayList<Vec3d>()
        ext.add(WAYPOINTS[0].multiply(2.0).subtract(WAYPOINTS[1]))
        ext.addAll(WAYPOINTS)
        ext.add(WAYPOINTS.last().multiply(2.0).subtract(WAYPOINTS[WAYPOINTS.size - 2]))
        val raw = ArrayList<Vec3d>()
        for (i in 1 until ext.size - 2) {
            for (j in 0 until 24) raw.add(catmull(ext[i - 1], ext[i], ext[i + 1], ext[i + 2], j / 24.0))
        }
        raw.add(WAYPOINTS.last())
        val out = ArrayList<Vec3d>()
        out.add(raw[0])
        var carry = 0.0
        var total = 0.0
        for (i in 1 until raw.size) {
            val a = raw[i - 1]
            val b = raw[i]
            val len = a.distanceTo(b)
            var s = STEP - carry
            while (s <= len) {
                out.add(a.lerp(b, s / len))
                s += STEP
            }
            carry = len - (s - STEP)
            total += len
        }
        if (out.last().squaredDistanceTo(raw.last()) > 1e-6) out.add(raw.last())
        table = out.toTypedArray()
        LENGTH = (table.size - 1) * STEP
    }

    private fun catmull(p0: Vec3d, p1: Vec3d, p2: Vec3d, p3: Vec3d, t: Double): Vec3d {
        val t2 = t * t
        val t3 = t2 * t
        fun c(a: Double, b: Double, c: Double, d: Double) = 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
        return Vec3d(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y), c(p0.z, p1.z, p2.z, p3.z))
    }

    /** Punkt nach [u] Blöcken Weg (relativ); vor dem Start bzw. nach dem Ende geklemmt. */
    fun at(u: Double): Vec3d {
        val k = (u / STEP).coerceIn(0.0, (table.size - 1).toDouble())
        val i = k.toInt().coerceAtMost(table.size - 2)
        return table[i].lerp(table[i + 1], k - i)
    }

    /** Flugrichtung bei [u] (normiert). */
    fun tangent(u: Double): Vec3d {
        val a = at(u - 1.0)
        val b = at(u + 1.0)
        val d = b.subtract(a)
        return if (d.lengthSquared() < 1e-9) Vec3d(0.0, 0.0, -1.0) else d.normalize()
    }

    /** Weg nach [tick] Ticks: langsames Lösen vom Fels, schneller Sturz über der Schlucht, ruhiges Einschweben. */
    fun distance(tick: Double): Double {
        val x = (tick / TICKS).coerceIn(0.0, 1.0)
        return LENGTH * (x - sin(2 * PI * x) / (2 * PI) * 0.6)
    }

    /** Kopfgelenk vor dem Entity-Punkt in der Gleitpose (siehe [NivarisShape.head] GLIDE, Flug). */
    val GLIDE: NivarisShape.Head get() = NivarisShape.head(NivarisPose.GLIDE, true)

    /** Entity-Punkt (relativ) passend zum Kopfgelenk bei [u] in der Gleitpose. */
    fun entityAt(u: Double): Vec3d {
        val head = at(u)
        val t = tangent(u)
        val flat = Vec3d(t.x, 0.0, t.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, -1.0) else it.normalize() }
        return head.subtract(flat.multiply(GLIDE.fwd)).subtract(0.0, GLIDE.up, 0.0)
    }

    /**
     * Stellvertreterpunkt der Entity, solange der Körper an der Nadel ist (relativ). Liegt in Trackingweite der Spieler
     * auf dem Plateau und in den dauerhaft geladenen Chunks; der Renderer zeichnet den Körper in COIL/UNCOIL nicht an
     * der Entity, sondern an seinem wahren Ort (Helix bzw. [at]).
     */
    val PROXY = Vec3d(0.5, 24.0, 40.0)

    /** Darf die Entity dem Weg selbst folgen (nah genug an den Spielern, in geladenen Chunks)? */
    fun followable(rel: Vec3d): Boolean = kotlin.math.abs(rel.x) <= 50.0 && rel.z in -40.0..50.0 && rel.y <= 70.0
}

/** Drehbuch des Auftritts. */
internal object Entrance {

    private fun rel(f: EirholmFight, x: Double, y: Double, z: Double) = f.abs(Vec3d(x, y, z))

    /** Wie [rel], aber im Bereich der Nadel/Schlucht relativ zu [ArenaLayout.SPIRE] (siehe [EntrancePath.near]). */
    private fun near(f: EirholmFight, x: Double, y: Double, z: Double) = f.abs(EntrancePath.near(x, y, z))

    /** Punkt relativ zur Achse der Nadel ([ArenaLayout.SPIRE], Mitte des Fußblocks), Höhe relativ zur Lauffläche. */
    private fun spire(f: EirholmFight, dx: Double, y: Double, dz: Double) =
        f.abs(Vec3d(ArenaLayout.SPIRE.x + 0.5 + dx, y, ArenaLayout.SPIRE.z + 0.5 + dz))

    /** Endzustand (Training, Überspringen ohne Nadel, Abbruch): gelandet am Kampfplatz. */
    fun landed(f: EirholmFight, spot: Vec3d) {
        val d = f.nivaris ?: run {
            f.spawnNivaris(spot, 180f)
            f.nivaris
        } ?: return
        d.mode = NivarisMode.NORMAL
        d.uncoil = 0f
        d.flying = false
        f.place(d, spot)
        f.face(d, spot.add(0.0, 0.0, -10.0))
        if (d.pose == NivarisPose.LIE_DOWN || d.pose == NivarisPose.GLIDE) d.play(NivarisPose.IDLE)
    }

    /** Zuschauer-Klang: laut genug, dass er die Spieler (am Plateau) erreicht, gehört wird er an der Kamera. */
    private fun sound(f: EirholmFight, id: String, at: Vec3d, volume: Float, pitch: Float = 1f) {
        if (Cine.muted) return
        val event = f.soundEvent(id) ?: return
        val far = f.audience().maxOfOrNull { sqrt(it.squaredDistanceTo(at)) } ?: 0.0
        val v = max(volume.toDouble(), far / 15.0 + 0.5).toFloat()
        f.world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, v, pitch)
    }

    private fun fx(f: EirholmFight, kind: FxKind, pos: Vec3d, a: Float, life: Int) {
        if (!Cine.muted) f.fx(kind, pos, a = a, life = life)
    }

    /** Spieler, die einen Effekt nahe der Kamera sehen sollen, bekommen ihn unabhängig von der Entfernung. */
    private fun fxAll(f: EirholmFight, kind: FxKind, pos: Vec3d, a: Float, life: Int) {
        if (Cine.muted) return
        val payload = dev.henny.hha_arius.net.FxS2C(kind.ordinal, pos.x, pos.y, pos.z, 0.0, 0.0, 0.0, a, 0f, life, -1, -1)
        for (p in f.audience()) dev.henny.hha_arius.net.AriusNet.send(p, payload)
    }

    /** Punkt auf der Helix (absolut), siehe [ArenaLayout.coil]. */
    private fun coilAt(f: EirholmFight, s: Double): Vec3d = f.abs(ArenaLayout.coil(s))

    /**
     * Kamerawinkel (Grad, Konvention von Cine.orbit: x = cos, z = sin) für den Helixwinkel bei [s]. Muss zu
     * [ArenaLayout.coil] passen (1½ Umläufe: Winkel = s · 1,5 · 2π, x = sin, z = cos).
     */
    private fun orbitDeg(s: Double): Double = Math.toDegrees(PI / 2 - s * COIL_TURNS * 2 * PI)

    private const val COIL_TURNS = 1.5

    /** Bring die Entity an die Nadel (Stellvertreterpunkt), gewunden, Kopf ruhend. */
    private fun coil(f: EirholmFight): NivarisEntity? {
        val proxy = f.abs(EntrancePath.PROXY)
        f.spawnNivaris(proxy, 180f)
        val d = f.nivaris ?: return null
        d.mode = NivarisMode.COIL
        d.uncoil = 0f
        d.flying = false
        d.eyeGlow = 8
        d.throatGlow = 0
        d.play(NivarisPose.LIE_DOWN)
        return d
    }

    fun full(f: EirholmFight, scene: CineScene, spot: Vec3d): Script = script {
        val d = coil(f) ?: return@script
        val a = f.arius
        f.atmos(AtmosMode.BLIZZARD, 50)
        f.music("music.entrance", 40)
        // Aus dem Weiß des Sturms in die erste Einstellung.
        scene.once { Cine.fade(it, 0xF2F6FA, 0.7, 0.0, 24) }

        // 1) Arius hebt den Blick (50 Ticks).
        if (a != null) {
            val face = a.entityPos.add(0.0, 1.35, 0.0)
            val front = f.facing(a)
            scene.shot {
                Cine.camera(it, face.add(front.multiply(2.6)).add(0.35, -0.15, 0.0), face.add(front.multiply(1.9)).add(0.25, -0.1, 0.0),
                    face, face.add(0.0, 0.1, 0.0), 50, 58.0, 48.0)
            }
        }
        wait(14)
        if (!Cine.muted) f.say("dragon.wake", 64)
        wait(36)

        // 2) Sturm: Blick vom Südrand des Plateaus in das Weiß, langsame Fahrt nach vorn (70 Ticks).
        val stormFrom = rel(f, 1.5, 8.0, 18.0)
        val stormTo = rel(f, 1.0, 11.0, 34.0)
        scene.shot { Cine.camera(it, stormFrom, stormTo, spire(f, 0.0, 60.0, 0.0), spire(f, 0.0, 110.0, 0.0), 70, 70.0, 56.0) }
        sound(f, "seal.blizzard", stormTo, 2.5f, 0.7f)
        fx(f, FxKind.BLIZZARD, stormTo, 10f, 70)
        wait(70)

        // 3) Nah im Sturm an der Nadel: eine Windung gleitet vorbei (60 Ticks). Kamera kreist gegen die Laufrichtung.
        val axis = f.abs(Vec3d.of(ArenaLayout.SPIRE)).add(0.5, 0.0, 0.5)
        run {
            val s0 = 0.80
            val s1 = 0.71
            val c = coilAt(f, s0)
            val r = sqrt((c.x - axis.x) * (c.x - axis.x) + (c.z - axis.z) * (c.z - axis.z)) + 6.5
            val center = Vec3d(axis.x, c.y, axis.z)
            val drop = coilAt(f, s1).y - c.y
            scene.shot {
                Cine.orbit(it, center, r, 1.5, orbitDeg(s0) + 14.0, orbitDeg(s1) + 6.0, 60, 52.0, 46.0, null, 0.6, r - 1.0, 1.5 + drop)
            }
            sound(f, "seal.blizzard", c, 3f, 0.55f)
            fxAll(f, FxKind.BLIZZARD, c, 9f, 60)
        }
        d.eyeGlow = 14
        wait(60)

        // 4) Der Kristallkamm von oben: eine tiefere Windung zieht unter der Kamera durch (60 Ticks). Weit weg vom
        //    ruhenden Kopf, damit man nie das Ganze sieht.
        run {
            val s0 = 0.66
            val s1 = 0.58
            val c = coilAt(f, s0)
            val r = sqrt((c.x - axis.x) * (c.x - axis.x) + (c.z - axis.z) * (c.z - axis.z)) + 3.0
            val drop = coilAt(f, s1).y - c.y
            scene.shot {
                Cine.orbit(it, Vec3d(axis.x, c.y, axis.z), r, 6.0, orbitDeg(s0) - 10.0, orbitDeg(s1) - 18.0, 60, 48.0, 44.0, null, -3.0, r + 1.0, 6.0 + drop)
            }
            sound(f, "nivaris.wing", c, 2f, 0.45f)
            fxAll(f, FxKind.BLIZZARD, c, 8f, 60)
        }
        wait(60)

        // 5) Die Wolken reißen auf: weite Einstellung vom Rand der Schlucht (110 Ticks), 70 Blöcke vor der Nadel auf
        //    halber Höhe ihres oberen Drittels. Die Kamera weicht zurück und steigt; das obere Drittel (y 110..185) füllt
        //    das Bild (Start: y 110 bei −0,6, Kopf über dem Gipfel bei +0,95 der Bildhöhe), der Kopf hebt sich hinein.
        f.atmos(AtmosMode.REVEAL, 70)
        val revealFrom = spire(f, -34.0, 118.0, -70.0)
        val revealTo = spire(f, -44.0, 132.0, -80.0)
        scene.shot {
            Cine.camera(it, revealFrom, revealTo, spire(f, 0.0, 138.0, 0.0), spire(f, 0.0, 152.0, 0.0), 110, 64.0, 70.0)
        }
        sound(f, "glacier.crack", near(f, -10.0, 40.0, 80.0), 3f, 0.5f)
        wait(30)
        d.play(NivarisPose.IDLE)
        d.eyeGlow = 40
        sound(f, "nivaris.inhale", revealTo, 2f, 0.55f)
        wait(80)

        // 6) Nah am Kopf über dem Gipfel: Brüllen, Augen glühen, Name (60 Ticks).
        val head = f.abs(EntrancePath.HEAD_RAISED)
        scene.shot {
            Cine.camera(it, near(f, -15.0, 181.0, 127.0), near(f, -11.0, 184.5, 131.0), head.add(0.0, -1.5, -2.0), head.add(0.0, 0.5, -1.5), 60, 62.0, 54.0)
        }
        d.play(NivarisPose.ROAR)
        wait(AriusTimings.NIVARIS_WINDUP[NivarisPose.ROAR])
        d.eyeGlow = 100
        sound(f, "nivaris.roar", near(f, -11.0, 184.5, 131.0), 4f, 0.8f)
        scene.once { Cine.title(it, "entity.hha_arius.nivaris", "hha_arius.cine.nivaris", 70) }
        wait(60 - AriusTimings.NIVARIS_WINDUP[NivarisPose.ROAR])

        // 7) Sie löst sich vom Fels und gleitet über die Schlucht (220 Ticks, drei Einstellungen).
        d.mode = NivarisMode.UNCOIL
        d.flying = true
        d.play(NivarisPose.GLIDE)
        d.eyeGlow = 70
        fun look(t: Int, back: Double): Vec3d {
            val u = EntrancePath.distance(t.toDouble())
            return f.abs(EntrancePath.at(u).multiply(0.7).add(EntrancePath.at(u - back).multiply(0.3)))
        }
        for (t in 0..EntrancePath.TICKS) {
            when (t) {
                0 -> scene.shot { Cine.camera(it, near(f, -62.0, 172.0, 116.0), near(f, -66.0, 158.0, 98.0), look(0, 25.0), look(85, 25.0), 85, 66.0, 74.0) }
                85 -> scene.shot { Cine.camera(it, near(f, -14.0, 128.0, 98.0), near(f, -22.0, 76.0, 48.0), look(85, 30.0), look(165, 30.0), 80, 76.0, 80.0) }
                165 -> scene.line(rel(f, -7.0, 5.0, -25.0), rel(f, -4.0, 4.0, -21.0), look(165, 20.0), spot.add(0.0, 4.0, -2.0), 105, 74.0, 66.0)
            }
            when (t) {
                4 -> sound(f, "nivaris.wing", head, 6f, 0.5f)
                40 -> sound(f, "nivaris.wing", near(f, -62.0, 168.0, 110.0), 3f, 0.6f)
                100 -> sound(f, "nivaris.wing", near(f, -16.0, 110.0, 84.0), 3f, 0.65f)
                150 -> sound(f, "seal.blizzard", near(f, -22.0, 80.0, 52.0), 2f, 1.2f)
                180 -> sound(f, "nivaris.wing", rel(f, -5.0, 6.0, -23.0), 3f, 0.7f)
            }
            val u = EntrancePath.distance(t.toDouble())
            d.uncoil = (u / EntrancePath.LENGTH).toFloat()
            follow(f, d, u)
            if (t < EntrancePath.TICKS) yield(Unit)
        }

        // 8) Übergabe an den normalen Flug: Entity steht genau am Ende des Wegs; Gleitflug und schwere Landung.
        val end = f.abs(EntrancePath.entityAt(EntrancePath.LENGTH))
        f.place(d, end)
        f.face(d, end.add(0.0, 0.0, -10.0))
        d.mode = NivarisMode.NORMAL
        d.uncoil = 0f
        yieldAll(glideAndLand(f, d, end, spot, 30))
        f.atmos(AtmosMode.SNOW, 80)
        // Rückweg zur Spielerkamera: von der Landung (laufende Einstellung) in die Augen der Spieler.
        scene.home(18)
        wait(18)
    }

    /** Ohne Fernlandschaft: Gleitflug von Süden heran (wie früher), mit Kamera. */
    fun short(f: EirholmFight, scene: CineScene, spot: Vec3d): Script = script {
        val start = spot.add(0.0, 18.0, 30.0)
        f.spawnNivaris(start, 180f)
        val d = f.nivaris ?: return@script
        d.mode = NivarisMode.NORMAL
        d.flying = true
        d.play(NivarisPose.GLIDE)
        f.music("music.entrance", 30)
        scene.line(rel(f, -8.0, 5.0, -26.0), rel(f, -5.0, 4.0, -22.0), start.add(0.0, 2.0, 0.0), spot.add(0.0, 4.0, -2.0), 60, 72.0, 64.0)
        sound(f, "nivaris.wing", rel(f, -5.0, 6.0, -23.0), 3f, 0.7f)
        yieldAll(glideAndLand(f, d, start, spot, 40))
        scene.home(18)
        wait(18)
    }

    private fun glideAndLand(f: EirholmFight, d: NivarisEntity, from: Vec3d, spot: Vec3d, ticks: Int): Script = script {
        d.flying = true
        d.play(NivarisPose.LAND, ticks)
        for (i in 1..ticks) {
            val k = i / ticks.toDouble()
            // Erst gleiten, zuletzt steil herab (Gewicht).
            val xz = from.lerp(spot, 1 - (1 - k) * (1 - k))
            val y = from.y + (spot.y - from.y) * (k * k * (3 - 2 * k))
            f.place(d, Vec3d(xz.x, y, xz.z))
            f.face(d, spot.add(0.0, 0.0, -10.0))
            yield(Unit)
        }
        f.place(d, spot)
        d.flying = false
        if (!Cine.muted) {
            f.ambient("nivaris.land", spot, 4f)
            Vfx.shockwave(f.world, Vfx.Style.SOUL, spot, 7f, 18, 1.2f)
            f.fx(FxKind.CLEAR_WAVE, spot, a = 10f, life = 18)
        }
    }

    /** Entity während des Lösens: folgt dem Weg, sobald er in Reichweite ist, sonst am Stellvertreterpunkt. */
    private fun follow(f: EirholmFight, d: NivarisEntity, u: Double) {
        val e = EntrancePath.entityAt(u)
        val pos = if (u > EntrancePath.GLIDE.fwd && EntrancePath.followable(e)) e else EntrancePath.PROXY
        f.place(d, f.abs(pos))
        val t = EntrancePath.tangent(u)
        val yaw = (Math.toDegrees(atan2(t.z, t.x)) - 90.0).toFloat()
        d.yaw = yaw
        d.headYaw = yaw
        d.bodyYaw = yaw
    }
}

/**
 * Eine Cutscene des Kampfes mit HHAs Cutscene-System ([Cine]): Start/Ende für alle Teilnehmer, Nachzügler steigen
 * mit der laufenden Einstellung ein, Überspringen per Abstimmung ([CineSkip]: spult das Drehbuch in einem Tick vor,
 * die Spielzustände stimmen danach), und ein Wächter setzt alles zurück, wenn der Kampf mitten in der Szene endet, das
 * Drehbuch verworfen wird oder der Server stoppt ([onAbort] stellt den Spielzustand her).
 */
class CineScene(private val f: EirholmFight, private val onAbort: () -> Unit) {

    private val skip = CineSkip()
    private val started = LinkedHashMap<UUID, ServerPlayerEntity>()
    private var lastShot: ((List<ServerPlayerEntity>) -> Unit)? = null
    private var lastStep = 0
    private var lastWatchers = -1
    var playing = false
        private set

    /** Wer gerade zusieht (online, gestartet). */
    private fun watchers(): List<ServerPlayerEntity> = f.audience().filter { it.uuid in started }

    /** Eine Einstellung (Kamera, Blende, Titel …) an alle Zuschauer; Nachzügler bekommen die letzte Kamera nachgereicht. */
    fun shot(send: (List<ServerPlayerEntity>) -> Unit) {
        val viewers = watchers()
        if (viewers.isNotEmpty()) send(viewers)
        lastShot = send
    }

    // --- Kamera mit Gedächtnis: wo die Kamera gerade ist, für den Rückweg zur Spielerkamera -------------------------

    /** Lage der laufenden Einstellung nach `age` Ticks: Position, Blickpunkt, Sichtfeld (wie CinePlayer, weich). */
    private var camAt: ((Int) -> Triple<Vec3d, Vec3d, Double>)? = null
    private var camStart = 0

    private fun ease(t: Double): Double = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    /** Gerade Kamerafahrt (siehe [Cine.camera]); merkt sich den Verlauf für [home]. */
    fun line(
        from: Vec3d, to: Vec3d, lookFrom: Vec3d, lookTo: Vec3d, ticks: Int, fovFrom: Double = 70.0, fovTo: Double = fovFrom,
        lookAt: net.minecraft.entity.Entity? = null, lookOffset: Double = 1.6,
    ) {
        shot { Cine.camera(it, from, to, lookFrom, lookTo, ticks, fovFrom, fovTo, lookAt, lookOffset) }
        camStart = f.server.ticks
        camAt = { age ->
            val k = ease(age / ticks.coerceAtLeast(1).toDouble())
            Triple(from.lerp(to, k), lookAt?.takeIf { !it.isRemoved }?.entityPos?.add(0.0, lookOffset, 0.0) ?: lookFrom.lerp(lookTo, k), fovFrom + (fovTo - fovFrom) * k)
        }
    }

    /** Kamerakreis (siehe [Cine.orbit], Winkel in Grad, x = cos, z = sin); merkt sich den Verlauf für [home]. */
    fun orbit(
        center: Vec3d, radius: Double, height: Double, angleFrom: Double, angleTo: Double, ticks: Int,
        fovFrom: Double = 70.0, fovTo: Double = fovFrom, lookAt: net.minecraft.entity.Entity? = null, lookOffset: Double = 1.6,
        radiusTo: Double = radius, heightTo: Double = height,
    ) {
        shot { Cine.orbit(it, center, radius, height, angleFrom, angleTo, ticks, fovFrom, fovTo, lookAt, lookOffset, radiusTo, heightTo) }
        camStart = f.server.ticks
        camAt = { age ->
            val k = ease(age / ticks.coerceAtLeast(1).toDouble())
            val ang = Math.toRadians(angleFrom + (angleTo - angleFrom) * k)
            val r = radius + (radiusTo - radius) * k
            val pos = center.add(cos(ang) * r, height + (heightTo - height) * k, sin(ang) * r)
            Triple(pos, lookAt?.takeIf { !it.isRemoved }?.entityPos?.add(0.0, lookOffset, 0.0) ?: center.add(0.0, lookOffset, 0.0), fovFrom + (fovTo - fovFrom) * k)
        }
    }

    /**
     * Sauberer Rückweg zur Spielerkamera: Jeder Zuschauer bekommt eine eigene Fahrt von dort, wo die Kamera gerade ist, zu
     * seiner Augenhöhe und Blickrichtung (Eingaben sind während der Szene gesperrt, beides steht also fest); danach endet
     * die Szene ohne Sprung. Nachzügler bekommen ihre eigene Fahrt. Das Drehbuch wartet danach selbst [ticks].
     */
    fun home(ticks: Int = 16) {
        val now = camAt?.invoke(f.server.ticks - camStart)
        shot { viewers ->
            for (p in viewers) {
                val eye = p.eyePos
                val dir = p.rotationVector
                val end = eye.add(dir.multiply(8.0))
                val from = now?.first ?: eye.subtract(dir.multiply(3.0)).add(0.0, 0.6, 0.0)
                Cine.camera(listOf(p), from, eye, now?.second ?: end, end, ticks, now?.third ?: 70.0, 70.0)
            }
        }
        camAt = null
    }

    /** Einmalige Sendung (Blende, Titel) an alle Zuschauer, ohne sie Nachzüglern nachzureichen. */
    fun once(send: (List<ServerPlayerEntity>) -> Unit) {
        val viewers = watchers()
        if (viewers.isNotEmpty()) send(viewers)
    }

    private fun join(players: List<ServerPlayerEntity>) {
        val fresh = players.filter { it.uuid !in started || started[it.uuid] !== it }
        if (fresh.isEmpty()) return
        for (p in fresh) started[p.uuid] = p
        Cine.start(fresh)
        if (!Cine.muted) lastShot?.invoke(fresh)
    }

    fun play(inner: Script): Script = script {
        register()
        active.add(this@CineScene)
        playing = true
        lastStep = f.server.ticks
        join(f.audience())
        val runner = Runner(inner)
        try {
            while (true) {
                lastStep = f.server.ticks
                join(f.audience())
                if (skip.pending) skip.fastForward(watchers()) { runner.step() }
                if (!runner.step()) break
                yield(Unit)
            }
        } finally {
            finish()
        }
    }

    private fun finish() {
        if (playing) Cine.end((started.values + f.audience()).distinctBy { it.uuid })
        playing = false
        active.remove(this)
        skip.reset()
        lastShot = null
    }

    /** Kampf vorbei, Drehbuch verworfen oder Serverstopp: Clients komplett zurücksetzen und den Endzustand herstellen. */
    fun abort() {
        if (!playing) return
        playing = false
        active.remove(this)
        skip.reset()
        Cine.reset((started.values + f.audience()).distinctBy { it.uuid })
        if (!f.ended) runCatching(onAbort)
    }

    private fun wants(player: ServerPlayerEntity) = playing && player.uuid in started

    private fun vote(player: ServerPlayerEntity): Boolean {
        if (!wants(player)) return false
        skip.vote(player, watchers())
        return true
    }

    private fun check(ticks: Int) {
        if (f.ended || ticks - lastStep > 40) {
            abort()
            return
        }
        // Jemand ist gegangen oder dazugekommen: Stimmen neu zählen (nicht jeden Tick, das sendet die Anzeige).
        val viewers = watchers()
        if (viewers.size != lastWatchers) {
            lastWatchers = viewers.size
            skip.recheck(viewers)
        }
    }

    companion object {
        private val active = LinkedHashSet<CineScene>()
        private var registered = false

        private fun register() {
            if (registered) return
            registered = true
            Cine.onSkip { player -> active.firstOrNull { it.wants(player) }?.vote(player) == true }
            ServerTickEvents.END_SERVER_TICK.register { server -> for (s in active.toList()) s.check(server.ticks) }
            ServerLifecycleEvents.SERVER_STOPPING.register { _ -> for (s in active.toList()) s.abort() }
        }
    }
}

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
 * Ablauf (≈ 38 s, überspringbar, keine Ansagen und kein Schaden):
 * 1. Arius steht noch und hebt den Blick, eine Zeile. Dann hebt er Solvane in den Himmel und lässt los
 *    ([Entrance.release], Paket V5): Die Klinge löst sich in einen Strom aus weißem Licht und Eiskristallen auf, der zur
 *    Nadel zieht – Solvane *ist* der Drache. Er setzt sich ohne Schwert auf [ArenaLayout.WATCH_SEAT].
 * 2. Schon während des Abflugs verdichtet sich der Schneesturm, die Windungen erscheinen an der Nadel. Im Sturm: einzelne Windungen gleiten vorbei, dann der Kristallkamm von oben. Nie das Ganze.
 * 3. Die Wolken reißen auf: weite Einstellung vom Rand der Schlucht, Nadel und Körper, der Kopf hebt sich über den
 *    Gipfel. Nah am Kopf: Brüllen, die Augen glühen, Name als Titelkarte.
 * 4. Sie löst sich vom Fels ([NivarisMode.UNCOIL]): Kopf voran, der Körper gleitet Glied für Glied von der Helix in die
 *    Flugbahn ([EntrancePath]); zwei Seitenfahrten zeigen die Länge über der Schlucht.
 * 5. Gleitflug über das Plateau, schwere Landung vor den Spielern.
 *
 * Ohne fertige Fernlandschaft ([EirholmFight.farReady]) lässt er Solvane ebenso los, der Strom zieht über das
 * Plateau, und wo er ankommt, gleitet sie heran (ohne Nadel). Im Training erscheint sie sofort gelandet.
 */
fun entrance(f: EirholmFight): Script = script {
    val spot = f.abs(Vec3d(0.5, 0.0, 2.0))
    if (f.training != 0) {
        Entrance.landed(f, spot)
        return@script
    }
    // Seit 1.9.0: Während die Kamera am Berg filmt, stehen die Körper unsichtbar zwischen Plateau und Berg, damit der
    // Server das Gelände lädt (Crix: „man sieht den Berg kaum“).
    val vantage = Vantage(f)
    val scene = CineScene(f) { vantage.restore(); Entrance.landed(f, spot) }
    yieldAll(scene.play(if (f.farReady) Entrance.full(f, scene, spot, vantage) else Entrance.short(f, scene, spot)))
    vantage.restore()
}

/**
 * Flugbahn des **Kopfgelenks** beim Lösen von der Nadel, relativ zum Instanz-Ursprung. Server (Entity, Kamera) und
 * Client (Körper in [NivarisMode.UNCOIL]) rechnen mit derselben Tabelle. Start: aufgerichtet über dem Gipfel, Blick
 * nach Norden; Sturz westlich des Schnitts über die freien Hochland-Schneefelder (weit über Krone, Massiv,
 * Ritt-Hindernissen und Eisspitzen); die letzten Blöcke gerade nach Norden in die Landung.
 */
object EntrancePath {
    private const val DESIGN_SPIRE_Z = 150.0
    internal val AXIS = Vec3d(ArenaLayout.SPIRE.x + 0.5, 0.0, ArenaLayout.SPIRE.z + 0.5)

    fun near(x: Double, y: Double, z: Double): Vec3d {
        val k = ((z - 20.0) / 72.0).coerceIn(0.0, 1.0)
        // Seit 1.5.1 auch in der Höhe: der Gipfel liegt [ArenaLayout.SPIRE_TOP] − 185 höher als im Entwurf.
        return Vec3d(x, y + (ArenaLayout.SPIRE_TOP - 185.0) * k, z + (ArenaLayout.SPIRE.z - DESIGN_SPIRE_Z) * k)
    }

    /** Kopfgelenk in der Windung, aufgerichtet über dem Gipfel (stolzer S-Bogen statt gestrecktem Senkrechthals). */
    val HEAD_RAISED: Vec3d = Vec3d(AXIS.x - 1.5, 221.5, AXIS.z - 10.5)

    /** Kopfgelenk in der Windung, ruhend entlang der oberen Nordost-Schulter (gleiche Rückenseite wie aufgerichtet). */
    val HEAD_REST: Vec3d = Vec3d(AXIS.x + 6.5, 212.5, AXIS.z - 10.5)

    private val WAYPOINTS = listOf(
        HEAD_RAISED,
        Vec3d(-12.0, 232.0, 222.0),    // hebt knickfrei nach vorn-oben über die Spitze ab
        Vec3d(-42.0, 204.0, 194.0),    // steiler Sturz hinaus in den offenen Westhimmel (weit abseits der Spalte)
        Vec3d(-84.0, 148.0, 156.0),    // weiter, schneller Sturzflug hoch über das offene Westtal
        Vec3d(-96.0, 102.0, 112.0),    // weite Außenkurve weit westlich & über der Eisnadel (-65, 85, 92)
        Vec3d(-76.0, 56.0, 72.0),      // kräftiges Eindrehen zurück nach Osten im offenen Tal nördlich der Nadel
        Vec3d(-32.0, 24.0, 46.0),      // dynamischer Tiefflug durch die breite obeliskfreie Südwest-Lücke
        Vec3d(-10.0, 10.0, 22.0),      // flacher Bogen über das Plateau Richtung Spieler
        Vec3d(-2.0, 6.0, 12.0),        // Eindrehen auf die Nordachse
        Vec3d(0.5, 4.4, 5.5),          // nahtlos vor der Landung bei (0.5, 0, 2.0)
    )

    private const val COIL_SAMPLES = 360

    /** Tastet die echte Eisoberfläche der Nadel bei Winkel [angle] und Höhe [y] ab, damit Bauch und Klauen direkt am Eis anliegen. */
    private fun spireRim(angle: Double, y: Double): Double {
        var best = 0.0
        for (da in doubleArrayOf(-0.08, 0.0, 0.08)) {
            val a = angle + da
            val dx = sin(a)
            val dz = cos(a)
            var r = 24.0
            while (r > best) {
                val qx = AXIS.x + dx * r
                val qz = AXIS.z + dz * r
                val top = runCatching { dev.henny.hha_arius.arena.FarPlan.spireTop(kotlin.math.floor(qx).toInt(), kotlin.math.floor(qz).toInt()) }.getOrDefault(Int.MIN_VALUE)
                if (top + 1 >= y - 1.0) { best = r; break }
                r -= 0.25
            }
        }
        return best
    }

    /**
     * Weite 1½-Windungen (wie 1.11.0 in Höhe y 179..205), aber bündig an der echten Eiskontur von [dev.henny.hha_arius.arena.FarPlan.spireTop]
     * und so gedreht, dass die Schulter an der Ostflanke nach Norden (−z) zeigt — kein 180°-Hals-Umschlag und kein Luftspalt unter den Klauen.
     */
    private val coilTable: Array<Vec3d> by lazy {
        val rawR = DoubleArray(COIL_SAMPLES + 1)
        val ys = DoubleArray(COIL_SAMPLES + 1)
        val angs = DoubleArray(COIL_SAMPLES + 1)
        val dy = ArenaLayout.SPIRE_TOP - 185.0
        for (i in 0..COIL_SAMPLES) {
            val s = i.toDouble() / COIL_SAMPLES
            val y = 144.0 + dy + (170.0 - 144.0) * s
            // Endwinkel bei s = 1: 2,55*π (Ostflanke, Vorwärts-Tangente zeigt nach Nord-Nordwest direkt in HEAD_REST/HEAD_RAISED).
            val a = (s * 1.5 - 0.225) * 2.0 * PI
            ys[i] = y
            angs[i] = a
            rawR[i] = spireRim(a, y)
        }
        Array(COIL_SAMPLES + 1) { i ->
            var rim = rawR[i]
            for (j in maxOf(0, i - 6)..minOf(COIL_SAMPLES, i + 6)) rim = max(rim, rawR[j])
            // Körperradius ~0,9 Blöcke: +1,15 hält den Bauch eng (≈ 0,25 Blöcke) am Eis, sodass die Klauen das Eis greifen.
            val r = max(rim + 1.15, ArenaLayout.spireRadius(ys[i]) + 1.4)
            Vec3d(AXIS.x + sin(angs[i]) * r, ys[i], AXIS.z + cos(angs[i]) * r)
        }
    }

    /** Punkt der Windung bei [s] ∈ [0, 1] (0 = Schwanz unten, 1 = Schulter oben). */
    fun coil(s: Double): Vec3d {
        val k = s.coerceIn(0.0, 1.0) * COIL_SAMPLES
        val i = k.toInt().coerceIn(0, COIL_SAMPLES - 1)
        return coilTable[i].lerp(coilTable[i + 1], k - i)
    }

    /** Waagerechte Außenrichtung weg von der Nadelachse (Rücken nach außen, Bauch zur Nadel). */
    fun coilOutward(p: Vec3d): Vec3d {
        val v = Vec3d(p.x - AXIS.x, 0.0, p.z - AXIS.z)
        return if (v.lengthSquared() < 1e-6) Vec3d(-1.0, 0.0, 0.0) else v.normalize()
    }

    /** Dauer des Lösens in Ticks (zügiger, dynamischer Sturzflug mit S-Kurve). */
    const val TICKS = 216

    /** Schnitte der drei Kamerafahrten im Lösen (anteilig zu [TICKS]). */
    const val CUT_1 = 76
    const val CUT_2 = 152

    /** Tick [t] eines früheren 220-Tick-Ablaufs auf die heutige Dauer umgerechnet. */
    fun scaled(t: Int): Int = t * TICKS / 220

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
    fun followable(rel: Vec3d): Boolean = kotlin.math.abs(rel.x) <= 70.0 && rel.z in -40.0..100.0 && rel.y <= 90.0
}

/** Drehbuch des Auftritts. */
internal object Entrance {

    private fun rel(f: EirholmFight, x: Double, y: Double, z: Double) = f.abs(Vec3d(x, y, z))

    /** Wie [rel], aber im Bereich der Nadel/Schlucht relativ zu [ArenaLayout.SPIRE] (siehe [EntrancePath.near]). */
    private fun near(f: EirholmFight, x: Double, y: Double, z: Double) = f.abs(EntrancePath.near(x, y, z))

    /** Punkt relativ zur Achse der Nadel ([ArenaLayout.SPIRE], Mitte des Fußblocks), Höhe relativ zur Lauffläche. */
    private fun spire(f: EirholmFight, dx: Double, y: Double, dz: Double) =
        f.abs(Vec3d(ArenaLayout.SPIRE.x + 0.5 + dx, y + (ArenaLayout.SPIRE_TOP - 185.0) * (y / 185.0).coerceIn(0.0, 1.0), ArenaLayout.SPIRE.z + 0.5 + dz))

    /** Endzustand (Training, Überspringen ohne Nadel, Abbruch): gelandet am Kampfplatz, Arius sitzt ohne Klinge. */
    fun landed(f: EirholmFight, spot: Vec3d) {
        seatWithoutBlade(f)
        val d = f.nivaris ?: run {
            f.spawnNivaris(spot, 180f)
            f.nivaris
        } ?: return
        d.mode = NivarisMode.NORMAL
        d.uncoil = 0f
        d.flying = false
        d.shade = 0f
        d.showNow()
        f.place(d, spot)
        f.face(d, spot.add(0.0, 0.0, -10.0))
        if (d.pose == NivarisPose.LIE_DOWN || d.pose == NivarisPose.GLIDE) d.play(NivarisPose.IDLE)
    }

    /** Arius sitzt ohne Schwert auf [ArenaLayout.WATCH_SEAT] (Solvane ist der Drache), Blick nach Süden. */
    fun seatWithoutBlade(f: EirholmFight) {
        val a = f.arius ?: return
        a.bladeState = AriusBlade.NONE
        a.sheathed = false
        if (a.pose == AriusPose.SEATED) return
        f.place(a, f.abs(ArenaLayout.WATCH_SEAT))
        a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
        a.play(AriusPose.SEATED)
    }

    /** Dauer von [release] in Ticks. */
    const val RELEASE_TICKS = 84

    /** Ticks nach Beginn von [release], bis der Lichtstrom am Ziel ankommt (nach dem Ende des Drehbuchs). */
    const val RELEASE_ARRIVAL = 94

    /** Länge der Sturm-Einstellung: Ankunft des Stroms nach 10 Ticks, dann entsteht die Silhouette im Weiß. */
    private const val STORM_TICKS = 70

    /** Ticks, in denen der Körper nach der Ankunft des Stroms Glied für Glied vom Kopf aus entsteht ([NivarisEntity.fadeIn]). */
    private const val FORM_TICKS = 60

    private const val FORM_TICKS_SHORT = 30

    /** Zeitpunkt des Loslassens in [release]. */
    private const val RELEASE_AT = 34

    /**
     * Arius entlässt Solvane (≈ 4 s, zwei Einstellungen): Er hebt die Klinge senkrecht in den Himmel, Licht sammelt sich
     * an der Schneide, die Zeile; dann löst sich die Klinge in einen Strom aus Licht und Eiskristallen auf, der steil
     * aufsteigt und in weitem Bogen zu [target] zieht (Ankunft nach [RELEASE_ARRIVAL] Ticks). Er senkt den Arm und setzt
     * sich ohne Schwert. Endzustand: [AriusBlade.NONE], SEATED. Kein Schaden, nur Darstellung.
     */
    fun release(f: EirholmFight, scene: CineScene, target: Vec3d, stormAhead: Boolean = false): Script = script {
        val a = f.arius ?: return@script
        val seat = f.abs(ArenaLayout.WATCH_SEAT)
        if (a.entityPos.squaredDistanceTo(seat) > 4.0) f.place(a, seat)
        a.yaw = 0f; a.headYaw = 0f; a.bodyYaw = 0f
        a.sheathed = false
        a.bladeState = AriusBlade.HELD
        val base = a.entityPos
        // Blick nach Süden. Kamerapunkte für WATCH_SEAT geprüft: vor ihm (z ≥ −62) über der unteren Stufe frei, die Säule
        // bei (−4 | −61) bleibt rechts außen; östlich über den Altarstufen ist bis zur Schnittkante Platz.
        val east = Vec3d(1.0, 0.0, 0.0)
        val south = Vec3d(0.0, 0.0, 1.0)
        // A) Von vorn, tief: er hebt Solvane in den Himmel (34 Ticks).
        scene.line(base.add(east.multiply(0.9)).add(south.multiply(3.4)).add(0.0, 0.7, 0.0), base.add(east.multiply(0.7)).add(south.multiply(2.9)).add(0.0, 0.5, 0.0),
            base.add(0.0, 1.3, 0.0), base.add(0.0, 2.6, 0.0), RELEASE_AT, 60.0, 54.0)
        a.play(AriusPose.CORE_RAISE)
        wait(10)
        if (!Cine.muted) f.say("dragon.release", 70)
        wait(2)
        storyFx(f, FxKind.EDGE, base, life = RELEASE_AT - 12 + 4, e1 = a)
        sound(f, "core.form", base, 1.6f, 1.35f)
        wait(RELEASE_AT - 12)

        // B) Loslassen: die Klinge wird zum Lichtstrom. Seitlich, etwas höher; der Blick folgt dem Strom in den Himmel.
        a.bladeState = AriusBlade.NONE
        val tip = base.add(0.0, 2.9, 0.0).add(east.multiply(-0.4))
        val travel = RELEASE_ARRIVAL - RELEASE_AT
        storyFx(f, FxKind.BLADE_STREAM, tip, target, a = travel.toFloat(), b = 0f, life = travel + 20, e1 = a)
        sound(f, "transform.pulse", base, 2.2f, 1.5f)
        sound(f, "resonance.chime", base, 2f, 1.15f)
        val skyward = tip.add(0.0, 18.0, 0.0).lerp(target, 0.12)
        scene.line(base.add(east.multiply(3.6)).add(0.0, 1.7, -0.2), base.add(east.multiply(4.6)).add(south.multiply(1.2)).add(0.0, 2.6, 0.0),
            tip, skyward, RELEASE_TICKS - RELEASE_AT, 62.0, 72.0)
        wait(14)
        // Er senkt den Arm und setzt sich.
        a.play(AriusPose.SEATED)
        // Der Strom hat die Hand verlassen. Der Nebel steht zehn Ticks vor dem Schnitt.
        if (stormAhead) f.atmos(AtmosMode.BLIZZARD, 26)
        wait(RELEASE_TICKS - RELEASE_AT - 14)
    }

    /**
     * Dreht alle Zuschauer an ihrem Platz zu [target] (Blickrichtung und leichte Neigung), bevor eine Szene mit
     * [CineScene.home] in ihre Augen zurückfährt; so endet der Rückweg auf dem, was die Szene gezeigt hat.
     */
    fun faceAudience(f: EirholmFight, target: Vec3d) {
        for (p in f.audience()) {
            if (p.isSpectator || p.entityWorld !== f.world) continue
            val dx = target.x - p.x
            val dz = target.z - p.z
            val flat = sqrt(dx * dx + dz * dz)
            if (flat < 0.5) continue
            val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
            val pitch = (-Math.toDegrees(atan2(target.y - p.eyeY, flat))).toFloat().coerceIn(-25f, 25f)
            p.teleport(f.world, p.x, p.y, p.z, emptySet(), yaw, pitch, false)
        }
    }

    /** Zuschauer-Klang: laut genug, dass er die Spieler (am Plateau) erreicht, gehört wird er an der Kamera. */
    private fun sound(f: EirholmFight, id: String, at: Vec3d, volume: Float, pitch: Float = 1f) {
        if (Cine.muted) return
        val event = f.soundEvent(id) ?: return
        val far = f.audience().maxOfOrNull { sqrt(it.squaredDistanceTo(at)) } ?: 0.0
        val v = max(volume.toDouble(), far / 15.0 + 0.5).toFloat()
        f.world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, v, pitch)
        f.layers(id, at, v, pitch)
    }

    private fun fx(f: EirholmFight, kind: FxKind, pos: Vec3d, a: Float, life: Int) {
        if (!Cine.muted) f.fx(kind, pos, a = a, life = life)
    }

    /** Spieler, die einen Effekt nahe der Kamera sehen sollen, bekommen ihn unabhängig von der Entfernung. */
    private fun fxAll(f: EirholmFight, kind: FxKind, pos: Vec3d, a: Float, life: Int) = storyFx(f, kind, pos, a = a, life = life)

    /**
     * Szenen-Effekt an alle Zuschauer, unabhängig von der Entfernung (die Kamera kann weit weg sein); beim Überspringen
     * ([Cine.muted]) still.
     */
    fun storyFx(
        f: EirholmFight, kind: FxKind, pos: Vec3d, dir: Vec3d = Vec3d.ZERO, a: Float = 0f, b: Float = 0f, life: Int = 20,
        e1: net.minecraft.entity.Entity? = null, e2: net.minecraft.entity.Entity? = null,
    ) {
        if (Cine.muted) return
        val payload = dev.henny.hha_arius.net.FxS2C(kind.ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life, e1?.id ?: -1, e2?.id ?: -1)
        for (p in f.audience()) dev.henny.hha_arius.net.AriusNet.send(p, payload)
    }

    /** Punkt auf der Helix (absolut), siehe [ArenaLayout.coil]. */
    private fun coilAt(f: EirholmFight, s: Double): Vec3d = f.abs(EntrancePath.coil(s))

    /** Bring die Entity an die Nadel (Stellvertreterpunkt), bereits im COIL-Modus und verborgen vor dem ersten Spawn-Paket. */
    private fun coil(f: EirholmFight, spawnPos: Vec3d = f.abs(EntrancePath.PROXY)): NivarisEntity? {
        f.spawnNivaris(spawnPos, 180f) { d ->
            d.mode = NivarisMode.COIL
            d.uncoil = 0f
            d.flying = false
            d.eyeGlow = 8
            d.throatGlow = 0
            // Natürliche 1.11.0-Silhouette im Schneesturm (kein künstlicher Blaustich).
            d.shade = 0f
            d.hideNow()
            d.play(NivarisPose.LIE_DOWN)
        }
        return f.nivaris
    }

    /** Aussichtspunkt für den Auftritt: auf halbem Weg zwischen den Landeplätzen und der Nadel, westlich der Kluft. */
    private fun lookout(f: EirholmFight): Vec3d {
        val land = ArenaLayout.LANDING[0]
        return f.abs(Vec3d(ArenaLayout.CUT_X - 24.0, 0.0, (land.z + ArenaLayout.SPIRE.z) / 2.0))
    }

    fun full(f: EirholmFight, scene: CineScene, spot: Vec3d, vantage: Vantage): Script = script {
        val a = f.arius
        // Noch kein Sturm: leichter Schnee, die Nadel steht leer im Dunst (Solvane ist noch Klinge).
        f.atmos(AtmosMode.SNOW, 40)
        f.music("music.entrance", 40)
        // Aus dem Weiß des Schnees in die erste Einstellung.
        scene.once { Cine.fade(it, 0xF2F6FA, 0.7, 0.0, 24) }

        // 1) Arius steht und hebt den Blick (50 Ticks).
        if (a != null) {
            val face = a.entityPos.add(0.0, 1.65, 0.0)
            val front = f.facing(a)
            scene.line(
                face.add(front.multiply(2.6)).add(0.35, -0.15, 0.0), face.add(front.multiply(1.9)).add(0.25, -0.1, 0.0),
                    face, face.add(0.0, 0.1, 0.0), 50, 58.0, 48.0,
            )
        }
        wait(14)
        if (!Cine.muted) f.say("dragon.wake", 64)
        wait(36)

        // 1b) Er lässt Solvane los: der Lichtstrom zieht zur Nadel; der Schneesturm baut sich schon im Abflug voll auf.
        val arrival = f.abs(EntrancePath.HEAD_REST)
        yieldAll(release(f, scene, arrival, stormAhead = true))
        // Zuschauer hinter die Südost-Flanke des Gipfels (außerhalb aller Kamerablicke, aber Nadel & Drache voll geladen).
        val summitTop = f.abs(Vec3d(ArenaLayout.SPIRE.x + 28.0, dev.henny.hha_arius.arena.FarPlan.SHOULDER_Y - 14.0, ArenaLayout.SPIRE.z + 42.0))
        vantage.move(summitTop)

        val axis = f.abs(Vec3d(ArenaLayout.SPIRE.x + 0.5, 0.0, ArenaLayout.SPIRE.z + 0.5))
        val head = f.abs(EntrancePath.HEAD_RAISED)

        // 2) Sturm-Silhouette an der Nadel (70 Ticks): BLIZZARD steht bereits aus release(stormAhead = true).
        //    Im idealen Nebelbereich (11..17 Blöcke vor Kopf & oberer Windung, über der Gipfelkrone), sodass Solvanes
        //    dunkle Drachen-Silhouette klar im wirbelnden Schneesturm entsteht und ihre Augen kurz aufglimmen.
        val stormFrom = f.abs(Vec3d(EntrancePath.AXIS.x + 17.0, 212.0, EntrancePath.AXIS.z - 22.0))
        val stormTo = f.abs(Vec3d(EntrancePath.AXIS.x + 13.5, 214.0, EntrancePath.AXIS.z - 17.5))
        val stormLookFrom = f.abs(Vec3d(EntrancePath.AXIS.x + 3.0, 206.0, EntrancePath.AXIS.z - 5.0))
        val stormLookTo = arrival.add(-1.5, -2.0, 1.5)
        scene.line(stormFrom, stormTo, stormLookFrom, stormLookTo, STORM_TICKS, 66.0, 58.0)
        f.atmos(AtmosMode.BLIZZARD, 10)
        sound(f, "seal.blizzard", stormTo, 2.5f, 0.7f)
        wait(RELEASE_ARRIVAL - RELEASE_TICKS)
        sound(f, "nivaris.inhale", arrival, 2f, 0.45f)
        fxAll(f, FxKind.BLIZZARD, arrival, 14f, 60)
        // Die Entity steht in dieser Einstellung an der Nadel, nicht beim Plateau-Stellvertreter: Der Client zeichnet sie
        // nur, wenn ihr Abschnitt in Sichtweite der Kamera liegt.
        var proxy = if (!Cine.muted) arrival else f.abs(EntrancePath.PROXY)
        val d = coil(f, proxy) ?: return@script
        d.shade = 1f
        d.eyeGlow = 0
        d.fadeIn(36)
        val stormLeft = STORM_TICKS - (RELEASE_ARRIVAL - RELEASE_TICKS)
        val glintAt = (stormLeft - 20).coerceAtLeast(1)
        wait(glintAt)
        d.eyeGlow = 35
        wait(stormLeft - glintAt)

        // 3) Der Sturm lichtet sich: Farbe kehrt sofort zurück; freie Fahrt an der Windung entlang (sicher innerhalb der Krone).
        fun outside(s: Double, out: Double, up: Double): Vec3d {
            val c = coilAt(f, s)
            val radial = EntrancePath.coilOutward(EntrancePath.coil(s))
            return c.add(radial.multiply(out)).add(0.0, up, 0.0)
        }
        f.atmos(AtmosMode.SNOW, 22)
        d.shade = 0f
        scene.line(outside(0.62, 15.0, 3.0), outside(0.74, 15.0, 5.0), coilAt(f, 0.64), coilAt(f, 0.76), 56, 56.0, 50.0)
        sound(f, "seal.blizzard", coilAt(f, 0.68), 3f, 0.55f)
        fxAll(f, FxKind.BLIZZARD, coilAt(f, 0.68), 9f, 56)
        d.eyeGlow = 18
        wait(56)

        // 4) Kristallkamm & Schultern von schräg oben: zeigt Windung und Klauen bündig am blauen Eis der Nadel (52 Ticks).
        scene.line(outside(0.82, 13.0, 4.5), outside(0.92, 12.5, 6.0), coilAt(f, 0.84), coilAt(f, 0.94), 52, 54.0, 48.0)
        sound(f, "nivaris.wing", coilAt(f, 0.87), 2f, 0.45f)
        fxAll(f, FxKind.BLIZZARD, coilAt(f, 0.87), 8f, 52)
        wait(52)

        // 5) Weite Totale über Massiv, Nadel und Windungen (hoch und sicher frei über allen Hängen); der Kopf hebt sich.
        f.atmos(AtmosMode.REVEAL, 34)
        val revealFrom = spire(f, -68.0, 172.0, -148.0)
        val revealTo = spire(f, -80.0, 178.0, -158.0)
        scene.line(revealFrom, revealTo, spire(f, 0.0, 136.0, 0.0), spire(f, 0.0, 158.0, 0.0), 104, 58.0, 54.0)
        sound(f, "glacier.crack", near(f, -10.0, 40.0, 80.0), 3f, 0.5f)
        wait(28)
        d.play(NivarisPose.IDLE)
        d.eyeGlow = 45
        sound(f, "nivaris.inhale", revealTo, 2f, 0.55f)
        wait(76)

        // 6) Nah am Kopf über dem Gipfel (hoch über der Krone): Brüllen, Augen glühen, Name (60 Ticks).
        scene.line(head.add(-13.0, -2.5, -14.0), head.add(-10.0, -0.5, -11.0), head.add(0.0, -1.2, -1.0), head.add(0.0, 0.4, -1.0), 60, 58.0, 50.0)
        d.play(NivarisPose.ROAR)
        val windup = AriusTimings.NIVARIS_WINDUP[NivarisPose.ROAR]
        wait(windup)
        d.eyeGlow = 100
        sound(f, "nivaris.roar", head, 4f, 0.8f)
        scene.once { Cine.title(it, "entity.hha_arius.nivaris", "hha_arius.cine.nivaris", 70) }
        MusicCue.sting(f, "reveal")
        wait(60 - windup)

        // 7) Sie löst sich vom Fels und stürzt in einer weiten, dynamischen S-Kurve durch den freien Westhimmel zum Plateau.
        f.atmos(AtmosMode.REVEAL, 20)
        d.mode = NivarisMode.UNCOIL
        d.wingTuck = 0f
        d.flying = true
        d.play(NivarisPose.GLIDE)
        d.eyeGlow = 70
        val exit = EntrancePath.tangent(EntrancePath.LENGTH)
        val end = f.abs(EntrancePath.entityAt(EntrancePath.LENGTH))
        val landCamMid = rel(f, -13.0, 6.2, -10.0)
        val landFocusMid = end.add(0.0, 2.5, -1.5)
        fun look(t: Int, back: Double): Vec3d {
            val u = EntrancePath.distance(t.toDouble())
            return f.abs(EntrancePath.at(u).multiply(0.7).add(EntrancePath.at(u - back).multiply(0.3)))
        }
        var followRoute = false
        val cut1 = EntrancePath.CUT_1
        val cut2 = EntrancePath.CUT_2
        val diveCam0 = axis.add(-52.0, 234.0, -52.0)
        val diveCam1 = axis.add(-78.0, 192.0, -86.0)
        val swoopCam0 = rel(f, -64.0, 72.0, 56.0)
        val swoopCam1 = rel(f, -44.0, 34.0, 36.0)
        val rimCam0 = rel(f, -22.0, 13.5, 2.0)
        for (t in 0..EntrancePath.TICKS) {
            when (t) {
                cut1 -> {
                    val midValley = f.abs(Vec3d(-72.0, 10.0, 110.0))
                    vantage.move(midValley)
                    proxy = midValley
                    followRoute = true
                }
                cut2 -> {
                    vantage.restore()
                    proxy = f.abs(EntrancePath.PROXY)
                }
            }
            fun camera(tick: Int): Vec3d = when {
                tick < cut1 -> {
                    val u = tick.toDouble() / cut1
                    val s = u * u * (3.0 - 2.0 * u)
                    diveCam0.lerp(diveCam1, s)
                }
                tick < cut2 -> {
                    val u = (tick - cut1).toDouble() / (cut2 - cut1)
                    val s = u * u * (3.0 - 2.0 * u)
                    swoopCam0.lerp(swoopCam1, s)
                }
                else -> {
                    val u = (tick - cut2).toDouble() / (EntrancePath.TICKS - cut2)
                    val s = u * u * (3.0 - 2.0 * u)
                    rimCam0.lerp(landCamMid, s)
                }
            }
            val next = if (t == cut1 - 1 || t == cut2 - 1) t else minOf(t + 1, EntrancePath.TICKS)
            val back = if (t < cut1) 14.0 else 10.0
            val focus0 = if (t == EntrancePath.TICKS) landFocusMid else look(t, back)
            val focus1 = if (next == EntrancePath.TICKS) landFocusMid else look(next, back)
            val fov = when {
                t < cut1 -> 62.0 + 6.0 * (t.toDouble() / cut1)
                t < cut2 -> 64.0
                else -> 60.0
            }
            scene.track(camera(t), camera(next), focus0, focus1, fov)
            when (t) {
                EntrancePath.scaled(4) -> sound(f, "nivaris.wing", head, 6f, 0.5f)
                EntrancePath.scaled(40) -> sound(f, "nivaris.wing", axis.add(-52.0, 200.0, -54.0), 3f, 0.6f)
                EntrancePath.scaled(100) -> sound(f, "nivaris.wing", rel(f, -72.0, 80.0, 112.0), 3.5f, 0.62f)
                EntrancePath.scaled(150) -> sound(f, "seal.blizzard", rel(f, -40.0, 32.0, 62.0), 2.5f, 1.15f)
                EntrancePath.scaled(180) -> sound(f, "nivaris.wing", rel(f, -14.0, 10.0, 6.0), 3.5f, 0.7f)
            }
            val u = EntrancePath.distance(t.toDouble())
            d.uncoil = (u / EntrancePath.LENGTH).toFloat()
            // Während des Sturzflugs wandert die Entity mit dem aktuellen Blickpunkt (Gipfel -> Tal -> Plateau), damit sie
            // in jeder Einstellung in Sicht- und Trackingweite der Kamera bleibt.
            follow(f, d, u, proxy, allowEntityMove = followRoute, freeRange = t in cut1 until cut2)
            yield(Unit)
        }

        // 8) Nahtlose Landeeinstellung im 3/4-Profil: direkte Fortsetzung aus UNCOIL, Bremsen mit Schwingen, festes Aufsetzen ohne Rutschen.
        f.place(d, end)
        f.face(d, end.add(exit))
        d.mode = NivarisMode.NORMAL
        val landCamClose = rel(f, -9.5, 4.0, -9.8)
        scene.rawLine(landCamMid, landCamClose, landFocusMid, spot.add(0.0, 2.0, -3.4), 36, 60.0, 58.0)
        yieldAll(glideAndLand(f, d, end, spot, 36))
        d.play(NivarisPose.LIE_DOWN, 14)
        scene.rawLine(landCamClose, rel(f, -8.0, 3.4, -11.5), spot.add(0.0, 2.0, -3.4),
            spot.add(0.0, 2.3, -5.0), 22, 58.0, 53.0)
        wait(22)
        d.uncoil = 0f
        d.showNow()
        f.atmos(AtmosMode.SNOW, 80)
        // Die Spieler stehen nördlich von ihr mit dem Blick zum Tempel: vor dem Rückweg zu ihnen drehen (Crix: „wenn sie
        // landet, muss man sich erst 180 Grad umdrehen“), damit die Kamera auf ihr endet.
        faceAudience(f, spot.add(0.0, 2.6, -4.0))
        scene.home(18)
        wait(18)
    }

    /** Ohne Fernlandschaft: Gleitflug von Süden heran (wie früher), mit Kamera. */
    fun short(f: EirholmFight, scene: CineScene, spot: Vec3d): Script = script {
        val start = spot.add(0.0, 18.0, 30.0)
        f.music("music.entrance", 30)
        // Er lässt Solvane los; wo der Strom ankommt, nimmt sie Gestalt an.
        yieldAll(release(f, scene, start.add(0.0, 2.0, 0.0)))
        wait(RELEASE_ARRIVAL - RELEASE_TICKS)
        fxAll(f, FxKind.BLIZZARD, start, 8f, 40)
        f.spawnNivaris(start, 180f)
        val d = f.nivaris ?: return@script
        d.mode = NivarisMode.NORMAL
        d.flying = true
        d.hideNow()
        d.fadeIn(FORM_TICKS_SHORT)
        d.play(NivarisPose.GLIDE)
        scene.line(rel(f, -10.0, 6.5, -24.0), rel(f, -8.0, 4.8, -19.0), start.add(0.0, 2.0, 0.0), spot.add(0.0, 3.8, -1.5), 52, 70.0, 60.0)
        sound(f, "nivaris.wing", rel(f, -8.0, 6.0, -21.0), 3f, 0.7f)
        yieldAll(glideAndLand(f, d, start, spot, 40))
        wait(12)
        d.showNow()
        faceAudience(f, spot.add(0.0, 2.6, -4.0))
        scene.home(18)
        wait(18)
    }

    private fun glideAndLand(f: EirholmFight, d: NivarisEntity, from: Vec3d, spot: Vec3d, ticks: Int): Script = script {
        d.flying = true
        d.play(NivarisPose.LAND, ticks)
        for (i in 1..ticks) {
            val k = i / ticks.toDouble()
            // Gleichmäßig auf 0 abbremsen (Anfangstempo schließt nahtlos an UNCOIL an, am Aufsetzpunkt Geschwindigkeit = 0).
            val xzFrac = 1.0 - (1.0 - k) * (1.0 - k)
            val xz = from.lerp(spot, xzFrac)
            val y = from.y + (spot.y - from.y) * (k * k * (3.0 - 2.0 * k))
            f.place(d, Vec3d(xz.x, y, xz.z))
            f.face(d, spot.add(0.0, 0.0, -10.0))
            if (i == ticks / 2) sound(f, "nivaris.wing", Vec3d(xz.x, y, xz.z), 3.5f, 0.65f)
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

    /** Entity während des Lösens: folgt dem Weg in Kamera-/Zuschauer-Reichweite (nie außerhalb der Trackingweite). */
    private fun follow(
        f: EirholmFight, d: NivarisEntity, u: Double, proxy: Vec3d,
        allowEntityMove: Boolean = true, freeRange: Boolean = false,
    ) {
        val e = EntrancePath.entityAt(u)
        f.place(d, if (allowEntityMove && u > EntrancePath.GLIDE.fwd && (freeRange || EntrancePath.followable(e))) f.abs(e) else proxy)
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

    /** Wie tief liegt [p] unter dem Gelände (Fernlandschaft samt Berg & Umgebung ±3 Blöcke, plus Abstand)? 0 = frei. */
    private fun below(p: Vec3d, margin: Double = 4.5): Double {
        val rx = kotlin.math.floor(p.x).toInt() - f.origin.x
        val rz = kotlin.math.floor(p.z).toInt() - f.origin.z
        if (rx !in dev.henny.hha_arius.arena.FarPlan.X_MIN..dev.henny.hha_arius.arena.FarPlan.X_MAX ||
            rz !in dev.henny.hha_arius.arena.FarPlan.Z_MIN..dev.henny.hha_arius.arena.FarPlan.Z_MAX) return 0.0
        var highest = Int.MIN_VALUE
        for (dx in intArrayOf(-3, 0, 3)) for (dz in intArrayOf(-3, 0, 3)) {
            val sx = rx + dx
            val sz = rz + dz
            if (sx !in dev.henny.hha_arius.arena.FarPlan.X_MIN..dev.henny.hha_arius.arena.FarPlan.X_MAX ||
                sz !in dev.henny.hha_arius.arena.FarPlan.Z_MIN..dev.henny.hha_arius.arena.FarPlan.Z_MAX) continue
            val t = max(dev.henny.hha_arius.arena.FarPlan.top(sx, sz), dev.henny.hha_arius.arena.FarPlan.terrain(sx, sz))
            if (t > highest) highest = t
        }
        if (highest <= 0) return 0.0                                 // flaches Feld: die Szenen planen selbst über dem Boden
        val top = f.origin.y + highest + 1.0
        return (top + margin + 2.0 - p.y).coerceAtLeast(0.0)
    }

    /** Direkte Kamerafahrt ohne Gelände-Anhebung (für geprüfte Höhlen- und Schlucht-Kameras), merkt den Verlauf für [home]. */
    fun rawLine(
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

    /** Laufende Anhebung der Folgekamera ([track]) über Gelände; steigt sofort, sinkt langsam (kein Springen). */
    private var trackLift = 0.0
    private var trackTick = Int.MIN_VALUE

    /** Wie hoch muss [p] mindestens, damit die Kamera samt ±1,2-Block-Nahebene frei von Fels und Eis bleibt? */
    private fun liftOutOf(p: Vec3d): Double {
        var up = 0.0
        while (up <= 34.0) {
            val q = p.add(0.0, up, 0.0)
            var clear = true
            for (dx in doubleArrayOf(-1.2, 0.0, 1.2)) {
                for (dz in doubleArrayOf(-1.2, 0.0, 1.2)) {
                    if (CineSafety.solid(f.world, f.origin, q.add(dx, 0.0, dz)) ||
                        CineSafety.solid(f.world, f.origin, q.add(dx, -1.0, dz))) {
                        clear = false
                        break
                    }
                }
                if (!clear) break
            }
            if (clear) return up
            up += 1.0
        }
        return up
    }

    /**
     * Advance one sample of a moving shot. Each packet covers the interval to the next sample.
     * Seit 1.15.2 mit Geländeschutz (Crix: „die Kamera geht teilweise immer noch durch den Berg“): Läuft die Fahrt durch
     * Fels oder Eis, wird sie weich darüber angehoben.
     */
    fun track(from: Vec3d, to: Vec3d, lookFrom: Vec3d, lookTo: Vec3d, fov: Double) {
        if (Cine.muted) return
        val now = f.server.ticks
        if (now - trackTick > 2) trackLift = 0.0
        trackTick = now
        val liftFrom = trackLift
        val liftTo = maxOf(liftOutOf(to), trackLift - 0.35, 0.0)
        trackLift = liftTo
        // Die HHA-Einstellung bleibt für Sichtfeld, Nachzügler und Clients ohne Abtast-Strom; die Lage selbst spielt der
        // Client gepuffert auf der Zeitachse der Darsteller ab ([TrackCamS2C]), ohne Anhalten zwischen den Ticks.
        rawLine(from.add(0.0, liftFrom, 0.0), to.add(0.0, liftTo, 0.0), lookFrom, lookTo, 1, fov)
        CineSafety.track(watchers(), now, from.add(0.0, liftFrom, 0.0), lookFrom)
    }

    /** Gerade Kamerafahrt (siehe [Cine.camera]); merkt sich den Verlauf für [home]. */
    fun line(
        from: Vec3d, to: Vec3d, lookFrom: Vec3d, lookTo: Vec3d, ticks: Int, fovFrom: Double = 70.0, fovTo: Double = fovFrom,
        lookAt: net.minecraft.entity.Entity? = null, lookOffset: Double = 1.6,
    ) {
        // Seit 1.5.1: Kamera nie im Berg (Crix: beim Auftritt war die Kamera im Massiv). Die ganze Fahrt wird angehoben.
        var lift = 0.0
        for (i in 0..16) lift = maxOf(lift, below(from.lerp(to, i / 16.0)))
        val b0 = from.add(0.0, lift, 0.0)
        val b1 = to.add(0.0, lift, 0.0)
        // Seit 1.10.0: freie Sicht auf den Blickpunkt über die ganze Fahrt ([CineSafety]); sonst höher bzw. näher heran.
        val target = lookAt?.takeIf { !it.isRemoved }?.entityPos?.add(0.0, lookOffset, 0.0)
        fun focus(k: Double): Vec3d = target ?: lookFrom.lerp(lookTo, k)
        fun moved(fix: CineSafety.Fix, b: Vec3d, k: Double): Vec3d {
            val c = focus(k)
            return c.add(b.add(0.0, fix.lift, 0.0).subtract(c).multiply(fix.scale))
        }
        val fix = CineSafety.pick(f.world, f.origin) { fx ->
            val c0 = moved(fx, b0, 0.0)
            val c1 = moved(fx, b1, 1.0)
            (0..12).map { i -> val k = ease(i / 12.0); c0.lerp(c1, k) to focus(k) }
        }
        val a0 = moved(fix, b0, 0.0)
        val a1 = moved(fix, b1, 1.0)
        shot { Cine.camera(it, a0, a1, lookFrom, lookTo, ticks, fovFrom, fovTo, lookAt, lookOffset) }
        camStart = f.server.ticks
        camAt = { age ->
            val k = ease(age / ticks.coerceAtLeast(1).toDouble())
            Triple(a0.lerp(a1, k), lookAt?.takeIf { !it.isRemoved }?.entityPos?.add(0.0, lookOffset, 0.0) ?: lookFrom.lerp(lookTo, k), fovFrom + (fovTo - fovFrom) * k)
        }
    }

    /** Kamerakreis (siehe [Cine.orbit], Winkel in Grad, x = cos, z = sin); merkt sich den Verlauf für [home]. */
    fun orbit(
        center: Vec3d, radius: Double, height: Double, angleFrom: Double, angleTo: Double, ticks: Int,
        fovFrom: Double = 70.0, fovTo: Double = fovFrom, lookAt: net.minecraft.entity.Entity? = null, lookOffset: Double = 1.6,
        radiusTo: Double = radius, heightTo: Double = height,
    ) {
        var lift = 0.0
        for (i in 0..16) {
            val k = i / 16.0
            val ang = Math.toRadians(angleFrom + (angleTo - angleFrom) * k)
            val r = radius + (radiusTo - radius) * k
            lift = maxOf(lift, below(center.add(cos(ang) * r, height + (heightTo - height) * k, sin(ang) * r)))
        }
        // Seit 1.10.0: freie Sicht auf den Blickpunkt entlang des ganzen Kreises ([CineSafety]).
        val focus = lookAt?.takeIf { !it.isRemoved }?.entityPos?.add(0.0, lookOffset, 0.0) ?: center.add(0.0, lookOffset, 0.0)
        val fix = CineSafety.pick(f.world, f.origin) { fx ->
            (0..12).map { i ->
                val k = ease(i / 12.0)
                val ang = Math.toRadians(angleFrom + (angleTo - angleFrom) * k)
                val r = (radius + (radiusTo - radius) * k) * fx.scale
                center.add(cos(ang) * r, height + lift + fx.lift + (heightTo - height) * k, sin(ang) * r) to focus
            }
        }
        val radius = radius * fix.scale
        val radiusTo = radiusTo * fix.scale
        val h0 = height + lift + fix.lift
        val h1 = heightTo + lift + fix.lift
        shot { Cine.orbit(it, center, radius, h0, angleFrom, angleTo, ticks, fovFrom, fovTo, lookAt, lookOffset, radiusTo, h1) }
        camStart = f.server.ticks
        camAt = { age ->
            val k = ease(age / ticks.coerceAtLeast(1).toDouble())
            val ang = Math.toRadians(angleFrom + (angleTo - angleFrom) * k)
            val r = radius + (radiusTo - radius) * k
            val pos = center.add(cos(ang) * r, h0 + (h1 - h0) * k, sin(ang) * r)
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

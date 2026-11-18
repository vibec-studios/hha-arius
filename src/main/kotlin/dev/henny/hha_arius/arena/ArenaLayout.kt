package dev.henny.hha_arius.arena

import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Feste Geometrie des Heiligtums von Eirholm, **relativ zum Instanz-Ursprung** `O` (siehe [origin]). Kampf und
 * Arena-Bau ([ArenaBuilder]) lesen dieselben Werte. y = 0 ist die **Lauffläche** (Oberkante des Bodens; die
 * Bodenblöcke liegen bei y = −1). +z = Süden, −z = Norden.
 *
 * ```
 *                 N (−z)
 *        ┌───── Tempel (Mitte T, R 18) ─────┐   Altarstufen im Norden, Tor im Süden (z = −32)
 *        └──────────────┬───────────────────┘
 *                       │  Landeplätze (z ≈ −24)
 *        ( Gletscherplateau, Mitte P = (0,0,0), R 40 )
 *                       │  Gletscherwand/Rand R 40–48, im Süden höher
 *                 S (+z)
 *   36 Blöcke darunter: Herzgewölbe (Mitte V = (0,−36,0), R 28, Decke bei y = −16)
 * ```
 */
object ArenaLayout {

    /** Abstand der Instanzen auf der X-Achse. */
    const val SLOT_SPACING = 4096

    /** Höhe der Lauffläche in der Arena-Dimension. */
    const val BASE_Y = 160

    /** Instanz-Ursprung (Lauffläche über der Plateaumitte). */
    fun origin(slot: Int): BlockPos = BlockPos(slot * SLOT_SPACING, BASE_Y, 0)

    // --- Tempel ---------------------------------------------------------------------------------------------------

    /** Tempelmitte (Lauffläche). */
    val TEMPLE = BlockPos(0, 0, -50)

    /** Innenradius der Halle. Mauer: R 18..20, Höhe 12, darüber Kuppel bis y ≈ 24. */
    const val TEMPLE_R = 18
    const val TEMPLE_WALL_H = 12
    const val TEMPLE_TOP = 25

    /** Altarstufen: Nordteil der Halle (z = −68 .. −62), zwei Stufen hoch. */
    val DAIS = BlockPos(0, 2, -65)

    /** Sitzplatz von Arius auf den Altarstufen (Intro). Blick nach Süden. */
    val SEAT = Vec3d(0.5, 2.0, -64.5)

    /** Landepunkt vor den Altarstufen: Hier springt Arius zu Beginn von Abschnitt I vom Sitz auf den Hallenboden. */
    val DAIS_FRONT = Vec3d(0.5, 0.0, -57.5)

    /** Tor im Süden: Mitte, Breite 7, Höhe 9. */
    val GATE = BlockPos(0, 0, -32)
    const val GATE_HALF_WIDTH = 3
    const val GATE_HEIGHT = 9

    /** Ankunftspunkt der Spieler im Tempel (innen vor dem Tor, Blick nach Norden). */
    val ARRIVAL = Vec3d(0.5, 0.0, -36.5)

    /** Eissäulen der Halle (zerbrechliche Deckung), Mittelpunkte auf Laufhöhe. Mittelgang |x| ≤ 3 bleibt frei. */
    val TEMPLE_PILLARS: List<BlockPos> = (0 until 10).map { i ->
        val a = (i + 0.5) / 10.0 * 2.0 * PI
        BlockPos(TEMPLE.x + Math.round(sin(a) * 12.0).toInt(), 0, TEMPLE.z + Math.round(cos(a) * 12.0).toInt())
    }

    /** Säulen: Radius (im Quadrat ±1 → 3 × 3) und Höhe bis unter die Decke. */
    const val PILLAR_HALF = 1
    const val PILLAR_H = 11

    /**
     * Der Schnitt: senkrechte Ebene **x = [CUT_X]** (relativ). Alles im Tempel mit x ≥ [CUT_X] + 0,5 (Blöcke mit
     * x ≥ 2) und y ≥ 0 gehört zur Osthälfte, die abrutscht. Die Ansage-Linie läuft von [CUT_FROM] nach [CUT_TO].
     */
    const val CUT_X = 1.5
    val CUT_FROM = Vec3d(CUT_X, 0.0, -66.0)
    val CUT_TO = Vec3d(CUT_X, 0.0, 52.0)
    const val CUT_WIDTH = 3.0

    /** Arius steht für den Schnitt auf der Altarstufe. */
    val CUT_STAND = Vec3d(1.5, 2.0, -63.5)

    /** Arius' Platz als Zuschauer in Abschnitt II: auf der stehengebliebenen Westhälfte der Altarstufen. */
    val WATCH_SEAT = Vec3d(-6.5, 2.0, -64.5)

    /** Landeplätze nach dem Tritt durchs Tor (frei von Eiskämmen, außerhalb jeder Startansage). */
    val LANDING: List<Vec3d> = listOf(Vec3d(0.5, 0.0, -22.5), Vec3d(-5.5, 0.0, -20.5), Vec3d(6.5, 0.0, -20.5),
        Vec3d(-2.5, 0.0, -17.5), Vec3d(3.5, 0.0, -17.5), Vec3d(0.5, 0.0, -14.5))

    // --- Plateau ----------------------------------------------------------------------------------------------------

    /** Plateaumitte = Ursprung. Begehbar bis [PLATEAU_R]; dahinter Rand/Gletscherwand bis [RIM_R]. */
    val PLATEAU = BlockPos(0, 0, 0)
    const val PLATEAU_R = 40
    const val RIM_R = 48
    const val RIM_H = 12

    /** Im Süden (Winkel um +z, ±50°) ist die Gletscherwand höher; der Schnitt öffnet dort eine Kluft bei x ≈ [CUT_X]. */
    const val SOUTH_WALL_H = 26

    /** Eisdicke des Plateaus (Lauffläche bis zur Gewölbedecke). */
    const val ICE_BOTTOM = -16

    /** Landepunkte für Nivaris in Abschnitt II (zufällige Auswahl, mindestens 8 Blöcke vom Ziel). */
    val DRAGON_SPOTS: List<Vec3d> = (0 until 8).map { i ->
        val a = i / 8.0 * 2.0 * PI
        Vec3d(sin(a) * 22.0 + 0.5, 0.0, cos(a) * 22.0 + 0.5)
    }

    /** Einsturz: Scheibe um die Plateaumitte, in drei Ringen (innen → außen). */
    const val COLLAPSE_R = 14
    val COLLAPSE_RINGS = doubleArrayOf(6.0, 10.0, 14.0)

    // --- Himmelsbalkon (Abschnitt III und Finale, seit 1.2.2) --------------------------------------------------------

    /**
     * Schauplatz von Abschnitt III und Finale (seit 1.4.1): ein weites, ebenes Schneefeld im offenen Hochland östlich
     * des Plateaus (wie die offene Gipfelarena eines Wukong-Bosses), Blick auf Horn und Gletscher, mit Kristallsäulen.
     * Eben gezogen in [FarPlan.top] (Terrasse), Säulen und Zuschauerhügel aus FarPlan.balcony.
     * Der Name `VAULT` bleibt im Code (früher das Herzgewölbe unter dem Plateau).
     */
    val VAULT = BlockPos(70, 0, -10)
    const val VAULT_R = 34

    /** Kein Deckengewölbe: Obergrenze nur für den Drachenkörper. */
    const val VAULT_CEIL = 80

    /** Eiskristall-Säulen auf dem Balkon (Deckung gegen Solvane, nicht gegen den Null-Schnitt). */
    val VAULT_PILLARS: List<BlockPos> = (0 until 8).map { i ->
        val a = (i + 0.5) / 8.0 * 2.0 * PI
        BlockPos(VAULT.x + Math.round(sin(a) * 17.0).toInt(), VAULT.y, VAULT.z + Math.round(cos(a) * 17.0).toInt())
    }

    /** Solvanes Liegeplatz im Finale (Nordrand des Balkons, Blick zur Nadel). */
    val VAULT_REST = Vec3d(VAULT.x + 0.5, VAULT.y.toDouble(), VAULT.z - 21.5)

    /** Landepunkte auf dem Balkon (nach dem Aufstieg im Lichtstrom). */
    val VAULT_LANDING: List<Vec3d> = listOf(Vec3d(-3.5, 0.0, 6.5), Vec3d(-9.5, 0.0, 4.5), Vec3d(2.5, 0.0, 4.5),
        Vec3d(-6.5, 0.0, 10.5), Vec3d(-0.5, 0.0, 10.5), Vec3d(-3.5, 0.0, 14.5)).map { Vec3d(VAULT.x + it.x, VAULT.y + it.y, VAULT.z + it.z) }

    // --- Fernlandschaft und die Nadel (Auftritt von Nivaris) --------------------------------------------------------

    /**
     * Die Nadel: riesiger, steil aufragender, spitzer Eisberg im Süden hinter der Schlucht. Fußpunkt (Mitte) auf
     * Schluchtgrund-Höhe, Spitze [SPIRE_TOP] Blöcke über der Lauffläche (absolut ≈ 345, Dimension bis 383).
     * Blickachse: vom Plateau (z ≈ −20) genau nach Süden über die Kluft im Südwall. Abstand zu den Landeplätzen ≈ 150
     * Blöcke: die Spitze ist ab etwa 10 Chunks Sichtweite zu sehen. Kameras und Flugbahn des Auftritts hängen an
     * diesem Punkt ([dev.henny.hha_arius.boss.EntrancePath.near]).
     */
    val SPIRE = BlockPos(0, -40, 128)
    const val SPIRE_TOP = 185
    const val SPIRE_BASE_R = 38.0

    /** Radius der Nadel auf Höhe y (relativ zur Lauffläche), steil und spitz zulaufend. */
    fun spireRadius(y: Double): Double {
        val t = ((y - SPIRE.y) / (SPIRE_TOP - SPIRE.y)).coerceIn(0.0, 1.0)
        return (SPIRE_BASE_R * Math.pow(1.0 - t, 1.35)).coerceAtLeast(0.0)
    }

    /** Schlucht zwischen Plateau und Nadel: von z [CANYON_Z_FROM] bis [CANYON_Z_TO], Grund bei y [CANYON_FLOOR]. */
    const val CANYON_Z_FROM = 56
    const val CANYON_Z_TO = 92
    const val CANYON_FLOOR = -70

    /** Länge des Drachenkörpers (Kopf bis Schwanzspitze) in Blöcken und Zahl der Glieder (Vertrag Modell/Renderer). */
    const val NIVARIS_LENGTH = 96.0
    const val NIVARIS_SEGMENTS = 40

    /**
     * Windung um die Nadel (Auftritt): Punkt auf der Helix bei s = 0 (Schwanzspitze, unten) … 1 (Hals unter dem Gipfel),
     * 1½ Umläufe um das obere, schlanke Drittel zwischen y 144 und y 170 – genau so viel, wie der 96-Block-Körper füllt
     * (Abstand zur Felswand ≥ 1,6 Blöcke). Der Kopf richtet sich knapp über dem Gipfel (y 185) auf. Für die Wucht soll
     * die weite Kameraeinstellung das obere Drittel der Nadel füllen, nicht den ganzen Berg.
     */
    fun coil(s: Double): Vec3d {
        val y = 144.0 + (170.0 - 144.0) * s
        val r = spireRadius(y) * 1.2 + 2.5 + 2.2
        val a = s * 1.5 * 2 * PI
        return Vec3d(SPIRE.x + 0.5 + sin(a) * r, y, SPIRE.z + 0.5 + cos(a) * r)
    }

    // --- Sonstiges ----------------------------------------------------------------------------------------------------

    /** Zuschauerpunkt (k. o., Nachzügler) in Abschnitt I/II: kleine Galerie auf der Westmauer des Tempels. */
    val WATCH_UPPER = Vec3d(-21.5, 13.0, -50.5)

    /** Zuschauerpunkt in Abschnitt III: kleiner Eisvorsprung westlich neben dem Balkon (erhöht). */
    val WATCH_VAULT = Vec3d(VAULT.x + 0.5, VAULT.y + 6.0, VAULT.z + 30.5)

    /**
     * Begrenzung des gesamten Bauvolumens (für Neubau/Aufräumen), relativ. Der Tempel liegt am Nordrand des Plateaus
     * (z −70..−30); dort entfällt der Plateaurand, stattdessen steht hinter dem Tempel eine Gletscherklippe bis z −78.
     */
    const val BOUND_XZ = 52
    const val BOUND_Z_MIN = -80
    const val BOUND_Y_MIN = -40
    const val BOUND_Y_MAX = 30

    /** Fernlandschaft (Nadel, Schlucht, Gletscherzüge) reicht weiter: bis z [FAR_Z_MAX], x ±[FAR_X], y [FAR_Y_MIN]..[SPIRE_TOP]. */
    const val FAR_X = 140
    const val FAR_Z_MAX = 200
    const val FAR_Y_MIN = -80

    fun at(origin: BlockPos, rel: BlockPos): BlockPos = origin.add(rel)
    fun at(origin: BlockPos, rel: Vec3d): Vec3d = Vec3d(origin.x + rel.x, origin.y + rel.y, origin.z + rel.z)
}

package dev.henny.hha_arius.gear.art

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.Vec3d
import kotlin.math.abs

/**
 * Gemeinsamer Vertrag von Server und Client für Arius' Kampfhandschuhe und Solvanes Waffentechnik (docs/AUSRUESTUNG.md).
 * Server: Paket gear.art (Logik). Client: Paket gear.client.art (Eingabe, Rad, HUD, Posen, Darstellung).
 */

/** Modus der Handschuhe, gespeichert am Stapel (Komponente `hha_arius:gauntlet_mode`), damit alle ihn sehen. */
enum class GauntletMode { MELEE, MAGIC;

    companion object {
        fun of(raw: Int?): GauntletMode = if (raw == 1) MAGIC else MELEE
    }
}

enum class School { WIND, ICE, YANG }

/** Wie eine Technik gewirkt wird. */
enum class CastKind {
    /** Rechtsklick drücken = sofort. */
    TAP,

    /** Halten = aufladen (Mindest-/Höchstladung), loslassen = wirken. Zu kurz = verpufft ohne Kosten. */
    CHARGE,

    /** Halten = Vorschau des Bauwerks, loslassen = setzen. Ungültiger Ort = nichts passiert, keine Kosten. */
    PLACE,

    /** Halten = aufrechterhalten (kostet je Sekunde), loslassen = Ende. */
    CHANNEL,

    /** Drücken = an, erneut drücken / Schleichen = aus. */
    TOGGLE,
}

enum class Role { ATTACK, DEFENSE, MOVEMENT, CONTROL, CONSTRUCT, TRANSPORT, UTILITY }

/**
 * Die Techniken des Magiemodus, je Schule höchstens fünf (Rad: erst Schule, dann Technik). [key] ist der Name in
 * Config (`hha_arius.spell_<key>_*`), Sprache (`hha_arius.spell.<key>`) und HUD-Sprite (`hud/art/<key>`).
 */
enum class Spell(val school: School, val kind: CastKind, val role: Role, val key: String) {
    WIND_BLADE(School.WIND, CastKind.TAP, Role.ATTACK, "wind_blade"),
    WIND_LEAP(School.WIND, CastKind.TAP, Role.MOVEMENT, "wind_leap"),
    GALE(School.WIND, CastKind.CHARGE, Role.DEFENSE, "gale"),
    /** Seit 1.7.0 statt Windzug: fünf Windklingen suchen sich die nächsten Gegner. */
    BLADE_STORM(School.WIND, CastKind.TAP, Role.ATTACK, "blade_storm"),

    FROST_SHARDS(School.ICE, CastKind.TAP, Role.ATTACK, "frost_shards"),
    ICE_WALL(School.ICE, CastKind.PLACE, Role.DEFENSE, "ice_wall"),
    FROST_FIELD(School.ICE, CastKind.PLACE, Role.CONTROL, "frost_field"),
    ICE_ARMOR(School.ICE, CastKind.TAP, Role.DEFENSE, "ice_armor"),

    YANG_ORB(School.YANG, CastKind.CHARGE, Role.ATTACK, "yang_orb"),
    YANG_SHIELD(School.YANG, CastKind.CHANNEL, Role.DEFENSE, "yang_shield"),
    YANG_PLATFORM(School.YANG, CastKind.PLACE, Role.CONSTRUCT, "yang_platform"),
    YANG_BRIDGE(School.YANG, CastKind.PLACE, Role.CONSTRUCT, "yang_bridge"),
    YANG_GLIDE(School.YANG, CastKind.TOGGLE, Role.TRANSPORT, "yang_glide"),

    /** Seit 1.3.1: wandernder Wirbelsturm, saugt Gegner ein und hebt sie hoch. */
    TORNADO(School.WIND, CastKind.TAP, Role.CONTROL, "tornado"),

    /** Seit 1.3.1: aufgeladener riesiger Eisspeer, durchschlägt alles, zerbirst am Ende in eine Frostexplosion. */
    GLACIER_LANCE(School.ICE, CastKind.CHARGE, Role.ATTACK, "glacier_lance"),

    /** Seit 1.7.0: schließt das anvisierte Ziel in einen Eiskäfig (3,5 s gebannt, Frostaufbau). */
    ICE_PRISON(School.ICE, CastKind.TAP, Role.CONTROL, "ice_prison"),

    /** Seit 1.7.0 (Crix): „Full-Pot“ – Stärke II, Schnelligkeit II, Feuerresistenz, Regeneration II. */
    FULL_POT(School.YANG, CastKind.TAP, Role.UTILITY, "full_pot"),

    /** Seit 1.7.0 (Crix): „Tank-Mode“ – Schildkrötenmeister mit Resistenz IV. */
    TANK_MODE(School.YANG, CastKind.TAP, Role.DEFENSE, "tank_mode");

    companion object {
        fun of(raw: Int): Spell? = entries.getOrNull(raw)
        /** Seit 1.8.0 (Crix): Trank-Techniken stehen nicht mehr bei Yang, sondern in einer eigenen Leiste über dem Rad. */
        val BUFFS: List<Spell> get() = listOf(FULL_POT, TANK_MODE)
        fun bySchool(school: School): List<Spell> = entries.filter { it.school == school && it !in BUFFS }
    }
}

/**
 * Nahkampf der Handschuhe. Zeiten in Ticks: Ausholen [windup] (Treffer genau bei t = windup), Erholung [recover]
 * (gesamt = windup + recover). Schaden = [mult] × Grundschaden. Reichweite ab Auge, [halfAngle] in Grad um den Blick,
 * [maxTargets] Ziele. [slow] = Bewegungsfaktor während der Aktion. Kein Heranziehen, kein Festhalten.
 */
enum class Move(
    val windup: Int, val recover: Int, val mult: Float, val reach: Double, val halfAngle: Double, val maxTargets: Int,
    val knock: Double, val lift: Double, val gainKey: String, val slow: Double, val anim: Anim,
) {
    /** Gerade mit links: schnell, kurz. */
    JAB(2, 5, 0.6f, 2.9, 32.0, 1, 0.12, 0.0, "melee_gain_jab", 0.75, Anim.JAB),

    /** Gerade mit rechts. */
    CROSS(3, 6, 0.65f, 3.0, 32.0, 1, 0.18, 0.0, "melee_gain_cross", 0.7, Anim.CROSS),

    /** Körperhaken mit links: Abschluss der Schlagfolge. */
    HOOK(4, 9, 0.9f, 2.7, 42.0, 1, 0.25, 0.08, "melee_gain_hook", 0.6, Anim.HOOK),

    /** Seitlicher Tritt (rechtes Bein): Raum schaffen. Aus der Schlagfolge heraus schneller (windup 4). */
    SIDE_KICK(6, 10, 1.0f, 3.4, 30.0, 2, 0.75, 0.1, "melee_gain_kick", 0.4, Anim.SIDE_KICK),

    /** Drehtritt nach dem Haken: einmal um die eigene Achse, trifft im Bogen vorn. */
    SPIN_KICK(8, 14, 1.6f, 3.4, 75.0, 3, 0.95, 0.25, "melee_gain_spin", 0.5, Anim.SPIN_KICK),

    /** Handflächen-Parade: [windup] = Fenster; trifft im Fenster ein Nahkampfschlag, wird er abgefangen. */
    PARRY(6, 16, 0f, 0.0, 0.0, 0, 0.0, 0.0, "melee_gain_parry", 0.5, Anim.PARRY),

    /** Konterstoß nach erfolgreicher Parade (automatisch, nur wenn der Angreifer in Reichweite und vorn ist). */
    COUNTER(2, 8, 1.2f, 3.0, 40.0, 1, 0.5, 0.1, "melee_gain_parry", 0.6, Anim.COUNTER),

    /** Aufwärtshaken rechts (4. Schlag nach dem Haken): schleudert leichte Gegner hoch, Windspirale. */
    UPPERCUT(4, 9, 1.15f, 2.9, 40.0, 1, 0.15, 0.75, "melee_gain_uppercut", 0.55, Anim.UPPERCUT),

    /** Axttritt (Tritt nach dem Aufwärtshaken): Sprung, Ferse kracht herab, Frost-Schockwelle trifft im Halbkreis vorn. */
    AXE_KICK(9, 14, 1.7f, 3.6, 80.0, 3, 0.7, 0.0, "melee_gain_axe", 0.3, Anim.AXE_KICK),

    // Seit 1.6.5 nach Dryleaf Dane (Elden Ring, Shadow of the Erdtree): Kampfkunst mit Sprung- und Drehtritten.
    /** Im Sprint: Flugtritt – Sprung nach vorn, das Bein voraus, trifft alles auf der Bahn. */
    FLYING_KICK(5, 10, 1.4f, 4.4, 40.0, 3, 0.95, 0.15, "melee_gain_kick", 1.0, Anim.FLYING_KICK),

    /** In der Luft (Angriff): Sturztritt schräg nach unten, Frost-Schockwelle beim Aufprall. */
    DIVE_KICK(5, 12, 1.6f, 3.8, 120.0, 4, 0.6, 0.15, "melee_gain_axe", 1.0, Anim.DIVE_KICK),

    /** In der Luft (Tritt): Dryleaf-Wirbelwind – Drehtritt einmal herum, Windsichel, trifft alle im Kreis. */
    WHIRL_KICK(6, 12, 1.3f, 3.6, 180.0, 5, 0.85, 0.35, "melee_gain_spin", 1.0, Anim.WHIRL_KICK),

    /** Seit 1.7.0, Schleichen + Angriff: Schlaghagel – fünf blitzschnelle Schläge hintereinander (je 0,45 ×). */
    FLURRY(3, 12, 0.36f, 3.0, 40.0, 2, 0.08, 0.0, "melee_gain_jab", 0.5, Anim.JAB),

    /** Seit 1.7.0, Sprint + Rechtsklick: Handflächenstoß mit Windexplosion, schleudert alles vor dir weit weg. */
    PALM_BLAST(7, 14, 1.5f, 3.4, 70.0, 4, 1.7, 0.45, "melee_gain_parry", 0.4, Anim.COUNTER),

    // Seit 1.8.0 (Crix: „mehr verschiedene Kicks, aktuell gibt es gefühlt nur Schläge“)
    /** Zweiter Tritt der Trittkette: Halbkreistritt mit dem Spann, hoch und weit. */
    ROUNDHOUSE(6, 12, 1.25f, 3.5, 55.0, 2, 0.85, 0.2, "melee_gain_kick", 0.5, Anim.ROUNDHOUSE),

    /** Nach dem Kniestoß: Fußfeger – tief geduckt einmal herum, holt alles im Kreis von den Füßen. */
    LOW_SWEEP(5, 12, 0.9f, 3.2, 180.0, 4, 0.2, 0.55, "melee_gain_spin", 0.4, Anim.LOW_SWEEP),

    /** Nach dem Jab: Kniestoß aus der Nähe, schnell, hebt leichte Gegner an. */
    KNEE(3, 8, 0.85f, 2.4, 40.0, 1, 0.15, 0.25, "melee_gain_jab", 0.6, Anim.KNEE);

    val total: Int get() = windup + recover
}

/**
 * Animationen, die der Server an alle sendet ([ArtAnimS2C]). Für Schläge/Tritte ist `param` die tatsächliche
 * Ausholzeit (Treffer bei t = param), sonst je Eintrag beschrieben. Schleifen (`loop`) laufen bis zur nächsten Anim
 * oder [NONE]. Zusammengesetzte `param` (V3):
 * * [ART_CHARGE], [ART_RELEASE]: `(waagerecht ? 1 : 0) + 2 * round(Ladung * 100)`, Ladung 0..1 (Haltung: 0);
 * * [CAST_CHARGE]: `Schule + 4 * maximale Ladeticks` (Spell-Config `charge_max`);
 * * übrige Zauber-Anims: `param` = Schule (0..2).
 * Lesen über [ArtParam].
 */
enum class Anim(val loop: Boolean = false) {
    NONE,
    JAB, CROSS, HOOK, SIDE_KICK, SPIN_KICK,

    /** Beide Handflächen vorn; Fenster = param Ticks, danach Erholung. */
    PARRY,

    /** Handflächenstoß rechts direkt nach einer gelungenen Parade. */
    COUNTER,

    /** Moduswechsel (8 Ticks): Hände schließen sich zur Faust bzw. öffnen sich. param = neuer Modus (0/1). */
    MODE_SWITCH,

    /** Kurzes Wirken mit einer Hand (6 Ticks). param = Schule. */
    CAST_TAP,

    /** Aufladen mit beiden Händen (Schleife). param = Schule + 4 × Höchstladung (Ticks). Ladung = Alter / Höchstladung. */
    CAST_CHARGE(true),

    /** Freigabe nach dem Aufladen (8 Ticks, Stoß mit beiden Händen). param = Schule. */
    CAST_RELEASE,

    /** Zielen für ein Bauwerk (Schleife): eine Hand zeigt auf den Ort. param = Schule. */
    PLACE_AIM(true),

    /** Yang-Schild (Schleife): beide Handflächen vorn, Lichtscheibe davor. */
    SHIELD(true),

    /** Lichtgleiter (Schleife): Stand auf einer Lichtscheibe, Arme zum Gleichgewicht. */
    GLIDE(true),

    /** Solvane: Haltung des Klaren Schnitts (Schleife), Energie sammelt sich an der Schneide. param = 1 waagerecht (+ 2 × 0). */
    ART_CHARGE(true),

    /** Solvane: der Schnitt (20 Ticks, Treffer bei Tick 3, dann Erholung). param = (1 waagerecht) + 2 × Ladung in %. */
    ART_RELEASE,

    /** Weißes Loch (Setfähigkeit): Hände führen zusammen (Wirken 20 Ticks), dann 10 Ticks Halten. */
    WHITE_HOLE,

    /** Aufwärtshaken rechts (param = Ausholzeit). */
    UPPERCUT,

    /** Axttritt: Sprung, Bein hoch über den Kopf, Ferse herab (param = Ausholzeit). */
    AXE_KICK,

    /** Flugtritt (param = Ausholzeit). */
    FLYING_KICK,

    /** Sturztritt aus der Luft (param = Ausholzeit). */
    DIVE_KICK,

    /** Wirbeltritt in der Luft (param = Ausholzeit). */
    WHIRL_KICK,

    /** Seit 1.8.0: Halbkreistritt (param = Ausholzeit). */
    ROUNDHOUSE,

    /** Seit 1.8.0: Fußfeger, tief geduckt einmal herum (param = Ausholzeit). */
    LOW_SWEEP,

    /** Seit 1.8.0: Kniestoß (param = Ausholzeit). */
    KNEE;

    companion object {
        fun of(raw: Int): Anim = entries.getOrElse(raw) { NONE }
    }
}

/**
 * Kombos der Handschuhe (seit 1.8.1, Crix: „die Kombos funktionieren nicht richtig, weil sie Tasten nutzen, die man
 * schon belegt hat“): Eine Kombo ist **nur** die Reihenfolge von Links- (L) und Rechtsklicks (R) innerhalb des
 * Verbindungsfensters, keine Auswahl, kein Schleichen, kein Sprint. Wer eine Folge vollständig trifft, verwandelt den
 * letzten Schlag in ihren Finisher (mehr Schaden, eigener Effekt, mehr Resonanz). Die Folgen sind präfixfrei; jeder
 * Anfang einer Folge hat seinen eigenen Schlag ([ComboTree]).
 */
enum class Combo(val key: String, val keys: String, val finisher: Move) {
    STORM("storm", "LLR", Move.FLYING_KICK),
    GLACIER_FIST("glacier_fist", "LLLL", Move.UPPERCUT),
    GLACIER("glacier", "LLLR", Move.AXE_KICK),
    HEARTBREAKER("heartbreaker", "LRL", Move.PALM_BLAST),
    AVALANCHE("avalanche", "LRR", Move.LOW_SWEEP),
    TAIL("tail", "RRR", Move.SPIN_KICK),
    LOTUS("lotus", "RLR", Move.WHIRL_KICK),
    HAILSTORM("hailstorm", "RLL", Move.FLURRY);

    companion object {
        fun of(raw: Int): Combo? = entries.getOrNull(raw)
        fun finishedBy(seq: String): Combo? = entries.firstOrNull { it.keys == seq }
    }
}

/** Welcher Schlag auf eine L/R-Folge kommt (Anfänge der Kombos und deren Finisher). */
object ComboTree {
    private val steps = mapOf(
        "L" to Move.JAB, "LL" to Move.CROSS, "LLL" to Move.HOOK, "LR" to Move.KNEE,
        "R" to Move.SIDE_KICK, "RR" to Move.ROUNDHOUSE, "RL" to Move.CROSS,
    )

    /** Schlag für die Folge [seq] oder null, wenn sie weder Anfang noch ganze Kombo ist. */
    fun move(seq: String): Move? = Combo.finishedBy(seq)?.finisher ?: steps[seq]

    /** Kombos, die mit [seq] beginnen (noch nicht vollständig). */
    fun continuations(seq: String): List<Combo> = Combo.entries.filter { it.keys.length > seq.length && it.keys.startsWith(seq) }
}

/** Packen und Lesen der zusammengesetzten Anim-Parameter (siehe [Anim]). */
object ArtParam {
    fun art(horizontal: Boolean, charge: Float): Int = (if (horizontal) 1 else 0) + 2 * Math.round(charge.coerceIn(0f, 1f) * 100f)
    fun cast(school: Int, chargeMax: Int): Int = school + 4 * chargeMax.coerceAtLeast(0)

    fun horizontal(param: Int): Boolean = (param and 1) == 1
    fun charge(param: Int): Float = ((param shr 1) / 100f).coerceIn(0f, 1f)
    fun school(param: Int): Int = param and 3
    fun chargeMax(param: Int): Int = param shr 2

    /** Ladestand 0..1 einer laufenden CAST_CHARGE-Animation (ohne Höchstladung im param: 30 Ticks). */
    fun castCharge(param: Int, age: Float): Float = (age / (chargeMax(param).takeIf { it > 0 } ?: 30)).coerceIn(0f, 1f)
}

/** Zeiten, die Client-Darstellung und Server teilen. */
object ArtTimings {
    const val MODE_SWITCH = 8
    const val CAST_TAP = 6
    const val CAST_RELEASE = 8
    const val ART_RELEASE = 20
    const val ART_HIT = 3

    /** Seit 1.8.1: der Riss – so viele Ticks nach dem Schnitt reißt die Naht auf und trifft ein zweites Mal. */
    const val ART_SPLIT = 12
    const val WHITE_HOLE = 30
    const val WHITE_HOLE_CAST = 20

    /** Klarer Schnitt: Mindest- und Volladung (Ticks); danach höchstens [ART_HOLD_MAX] weiter halten. */
    const val ART_CHARGE_MIN = 16
    const val ART_CHARGE_FULL = 40
    const val ART_HOLD_MAX = 80

    /** Nach einem Itemwechsel sind Handschuh- und Solvane-Aktionen so lange gesperrt (Ziehen). */
    const val DRAW = 8

    /** Klarer Schnitt: Länge und halbe Breite der Schnittebene bei Mindest- bzw. Volladung (Blöcke). Fest, damit die
     *  Zielvorschau im Client exakt der Server-Geometrie entspricht. */
    const val ART_LENGTH_MIN = 6.0
    const val ART_LENGTH_MAX = 12.0
    const val ART_HALF_MIN = 1.5
    const val ART_HALF_MAX = 2.5

    /** Ladung 0..1 aus gehaltenen Ticks. */
    fun artCharge(heldTicks: Float): Float =
        ((heldTicks - ART_CHARGE_MIN) / (ART_CHARGE_FULL - ART_CHARGE_MIN)).coerceIn(0f, 1f)

    fun artLength(charge: Float): Double = ART_LENGTH_MIN + (ART_LENGTH_MAX - ART_LENGTH_MIN) * charge
    fun artHalf(charge: Float): Double = ART_HALF_MIN + (ART_HALF_MAX - ART_HALF_MIN) * charge

    /** Yang-Schild: deckt vorn ±[SHIELD_HALF_ANGLE] Grad ab, Scheibe in [SHIELD_DISTANCE] Blöcken vor der Brust. */
    const val SHIELD_HALF_ANGLE = 60.0
    const val SHIELD_DISTANCE = 1.1
    const val SHIELD_RADIUS = 1.3
}

/**
 * Die Schnittebene des Klaren Schnitts. Server (Treffer, Blöcke) und Client (Zielvorschau) rechnen dieselbe Geometrie:
 * Ursprung vor der Brust, [forward] = Blickrichtung, [span] = Breite der Ebene (senkrecht: „oben“ quer zum Blick,
 * waagerecht: rechts), [normal] = Dicke-Richtung. Länge [length], halbe Breite [half].
 */
class CutPlane(val origin: Vec3d, val forward: Vec3d, val span: Vec3d, val normal: Vec3d, val length: Double, val half: Double) {

    /** Liegt ein Punkt (mit Toleranz [r]) im Schnitt-Quader? */
    fun contains(p: Vec3d, r: Double, thickness: Double = 0.6): Boolean {
        val d = p.subtract(origin)
        val along = d.dotProduct(forward)
        if (along < -r || along > length + r) return false
        if (abs(d.dotProduct(span)) > half + r) return false
        return abs(d.dotProduct(normal)) <= thickness + r * 0.7
    }

    companion object {
        /** Länge/halbe Breite bei Ladung 0..1 (Startwerte; Server liest dieselben Zahlen aus der Config). */
        fun of(player: PlayerEntity, length: Double, half: Double, horizontal: Boolean): CutPlane {
            val eye = player.eyePos
            val forward = player.rotationVector.normalize()
            val worldUp = Vec3d(0.0, 1.0, 0.0)
            var right = forward.crossProduct(worldUp)
            if (right.lengthSquared() < 1.0e-4) {
                val yaw = Math.toRadians(player.yaw.toDouble())
                right = Vec3d(-Math.cos(yaw), 0.0, -Math.sin(yaw))
            }
            right = right.normalize()
            val up = right.crossProduct(forward).normalize()
            val span = if (horizontal) right else up
            val normal = forward.crossProduct(span).normalize()
            val origin = eye.add(0.0, -0.35, 0.0).add(forward.multiply(0.6))
            return CutPlane(origin, forward, span, normal, length, half)
        }
    }
}

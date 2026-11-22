package dev.henny.hha_arius.boss

/**
 * Animationszustände von Arius. Die Werte gehen über die Leitung (DataTracker), also nur hinten anfügen.
 *
 * Vertrag mit dem Modell (`client/model/AriusModel.kt`): Eine Angriffspose läuft ab dem Posenwechsel (t = 0) so ab:
 * Ausholhaltung erreichen (weich, höchstens 8 Ticks) → halten → **der Schlag landet genau bei t = windup**
 * ([AriusEntity.windup], vom Server gesetzt; 0 heißt „Standardwert aus [AriusTimings]“) → Nachlauf → zurück nach IDLE
 * bei t = windup + [AriusTimings.recover] der Pose. Haltungen (GUARD, IAI_STANCE, CUT_CHARGE, CORE_RAISE, SEATED,
 * STAGGER, KNEEL) werden gehalten, bis der Server die Pose wechselt.
 */
object AriusPose {
    /** Ruhige Kampfhaltung, Solvane locker tief in der rechten Hand. Gehen kommt über walkAmount dazu. */
    const val IDLE = 0

    /** Sitzt auf Stufen, Solvane quer über den Knien (Intro, Abschnitt II als Zuschauer). */
    const val SEATED = 1

    /** Steht aus dem Sitzen auf und zieht dabei Solvane (30 Ticks). */
    const val RISE = 2

    /** Kombo Hieb 1: rechts oben → links unten. */
    const val COMBO_1 = 3

    /** Kombo Hieb 2: links → rechts, Rückhand. */
    const val COMBO_2 = 4

    /** Kombo Stoß 3: tiefer Ausfallschritt, Klinge gerade nach vorn; bleibt überstreckt (offen). */
    const val COMBO_3 = 5

    /** Weiße Form, Hieb 4: Rückwärtsschnitt nach dem Windschritt hinter das Ziel. */
    const val COMBO_4 = 6

    /** Windschritt: tief geduckt mit Klinge hinten (windup), Sprint, dann Schlittern mit Hand am Boden (Erholung). */
    const val LUNGE = 7

    /** Frostwand: Hocke, linke Handfläche auf den Boden, langsam aufrichten. */
    const val WALL_CAST = 8

    /** Streifenhieb: flacher, weiter Sichelhieb knapp über dem Boden. */
    const val STRIP_SLASH = 9

    /** Parierhaltung: Solvane waagerecht vor der Brust, Gewicht hinten. Gehalten. */
    const val GUARD = 10

    /** Parade & Tritt: Klinge lenkt ab (0–5), Eindrehen, Drehtritt mit rechts (Treffer bei 10), Fuß wieder ab bei 16. */
    const val PARRY_KICK = 11

    /** Frostgriff: zwei Finger der linken Hand zeigen auf das Ziel, Schwert gesenkt. */
    const val GRIP_CAST = 12

    /** Knaufschlag: Schritt heran, Knauf von oben in die Schulter des Ziels (Treffer bei windup). */
    const val POMMEL = 13

    /** Gletscherstoß: Solvane über dem Kopf, Spitze nach unten, dann in den Boden; bleibt stecken (offen), herausziehen. */
    const val STAB_GROUND = 14

    /** Seitschritt: kurzer Schritt seitlich (Richtung über [AriusEntity.sideSign]), 6 Ticks. */
    const val SIDESTEP = 15

    /** Pfeilschnitt: kurzer, schneller Schnitt vor dem Körper (8 Ticks), Gehen läuft weiter. */
    const val ARROW_CUT = 16

    /** Der Schnitt: Klinge hoch über der rechten Schulter, Körper verdreht, gehalten (Aufladen). */
    const val CUT_CHARGE = 17

    /** Der Schnitt: senkrechter Hieb von oben (landet bei windup), Klinge tief am Boden, gehalten. */
    const val CUT_RELEASE = 18

    /** Starker Fronttritt (Übergang durchs Tor): Parade, Knie hoch, Tritt bei windup. */
    const val GATE_KICK = 19

    /** Offen: Klinge gesenkt, Schultern atmen schwer. Gehalten. */
    const val STAGGER = 20

    /** Verwandlung: landet auf einem Knie (0–10), steht langsam auf, Arme leicht ausgebreitet (bis 40), Ruhe. */
    const val TRANSFORM = 21

    /** Aufwärtsschnitt (Luftkombination Teil 1, Treffer bei windup). */
    const val LAUNCH = 22

    /** Luftkombination Teil 2: Sprung, ein Abwärtstritt (Treffer bei windup), Landung in der Hocke. */
    const val AIR_KICK = 23

    /** Null-Schnitt: tiefer Stand, blitzschneller waagerechter Schnitt (Treffer bei windup). */
    const val NULL_CUT = 24

    /** Weißes Loch: Solvane senkrecht in den Himmel gestreckt, gehalten. */
    const val CORE_RAISE = 25

    /** Durchbruch: aus der Luft, Klinge über dem Kopf, Sturz-Schnitt (Treffer bei windup), Landung tief. */
    const val BREAKTHROUGH = 26

    /** Iai-Haltung: Solvane in der Scheide auf dem Rücken, rechte Hand am Griff über der Schulter, tief. Gehalten. */
    const val IAI_STANCE = 27

    /** Iai-Zug: Ziehen und Schneiden in einem Zug (bei windup), gleitet vorbei, Endhaltung mit gestreckter Klinge. */
    const val IAI_DRAW = 28

    /** Niederlage: kniet, Solvane mit der Spitze in den Boden, Hand am Griff. Gehalten. */
    const val KNEEL = 29

    /** Kristallsturm: wirft Eiskristalle mit der linken Hand nach oben (bei windup). */
    const val THROW_CRYSTALS = 30

    /** Eisfeld spalten: schneller senkrechter Hieb von oben (bei windup). */
    const val SPLIT_FIELD = 31

    /** Pfiff: zwei Finger an den Lippen (20 Ticks), Blick nach oben. */
    const val WHISTLE = 32

    /** Drehschnitt: 360°-Drehung mit gestreckter Klinge (Treffer bei windup). */
    const val SPIN_CUT = 33

    const val MACE_CATCH = 34
    const val MACE_SLAM = 35
    const val FORGE_ICE = 36

    const val COUNT = 37
}

/** Formen von Arius ([AriusEntity.form]). */
object AriusForm {
    /** Rauhreif-Form: Eirholm-Rüstung unter dem offenen Mantel. */
    const val DARK = 0

    /** Abschnitt I ab 40 %: Mantel abgelegt, Rüstung frei. */
    const val SERIOUS = 1

    /** Weiße Form: weißes Gewand, stahlblaue Schärpe, keine Rüstungsplatten. */
    const val WHITE = 2

    /** Seit 1.10.0: Klingenabsicht – durchscheinendes Nachbild aus Eislicht (Gipfel-Finale). */
    const val ECHO = 3
}

/** Animationszustände von Nivaris. Nur hinten anfügen. Gleicher Ablaufvertrag wie [AriusPose]. */
object NivarisPose {
    /** Steht, atmet, Schwanz pendelt. Gehen über walkAmount. */
    const val IDLE = 0

    /** Schwebt auf der Stelle (Flügelschlag-Zyklus), bei [NivarisEntity.flying]. */
    const val HOVER = 1

    /** Gleitet mit ausgebreiteten Schwingen (Vorwärtsflug). */
    const val GLIDE = 2

    /** Einatmen: Hals weit zurück, Brust/Kehle glühen (Leuchten über [NivarisEntity.throatGlow]). */
    const val INHALE = 3

    /** Atem: Hals nach vorn, Maul weit offen, gehalten bis Posenwechsel. */
    const val BREATH = 4

    /** Sturzflug: Schwingen angelegt, Kopf voraus. */
    const val DIVE = 5

    /** Schwere Landung (Aufprall bei windup), Schwingen gespreizt nach unten. */
    const val LAND = 6

    /** Klauenhieb: rechter Vorderlauf hoch und quer (Treffer bei windup). */
    const val CLAW = 7

    /** Schwanzfeger: Körper verdreht, Schwanz fegt im Halbkreis (Treffer bei windup). */
    const val TAIL = 8

    /** Flügelstoß: beide Schwingen weit vor, gewaltiger Schlag nach vorn (bei windup). */
    const val WING_GUST = 9

    /** Griff: Kopf/Klauen packen nach unten und heben einen Brocken (bis windup). */
    const val GRAB = 10

    /** Wurf: schleudert den Brocken (bei windup). */
    const val THROW = 11

    /** Brüllen: Kopf hoch, Maul offen. */
    const val ROAR = 12

    /** Zu Boden gebracht: liegt auf dem Bauch, Kopf flach am Boden, Schwingen schlaff. Gehalten. */
    const val DOWNED = 13

    /** Erschöpft: Kopf tief, schweres Atmen. Gehalten. */
    const val EXHAUSTED = 14

    /** Abflug: in die Hocke, kräftiger Flügelschlag nach unten (bei windup). */
    const val TAKEOFF = 15

    /** Resonanz: Schwingen senkrecht gespreizt, Kopf hoch, Leuchtlinien in den Membranen. Gehalten. */
    const val RESONANCE = 16

    /** Liegt ruhig zusammengerollt an der Wand (Finale). Gehalten. */
    const val LIE_DOWN = 17

    /** Sturz in den Gletscher: senkrecht nach unten, Schwingen angelegt. */
    const val CRASH_DIVE = 18

    /** Eisfeld: Atem schräg nach unten in einen Kreis (bei windup). */
    const val FREEZE_FIELD = 19

    const val COUNT = 20
}

/**
 * Darstellungszustand des Schlangenkörpers ([NivarisEntity.mode]). Nur hinten anfügen (geht über den DataTracker).
 * Der Server schaltet ihn im Auftritt ([entrance]); die Clients zeichnen danach, Nachzügler bekommen ihn mit dem
 * DataTracker automatisch.
 */
object NivarisMode {
    /** Kampf: Kopf folgt der Entity, der Körper der mitgeschriebenen Spur (Flug) bzw. liegt in Bögen (Boden). */
    const val NORMAL = 0

    /** Um die Nadel gewunden (Helix [dev.henny.hha_arius.arena.ArenaLayout.coil]), Kopf über dem Gipfel. */
    const val COIL = 1

    /** Löst sich von der Nadel: [NivarisEntity.uncoil] 0..1 ist der Weg entlang [EntrancePath]. */
    const val UNCOIL = 2

    /** Close perch on the spire for the return to the blade. */
    const val RETURN_COIL = 3
}

/**
 * Gemeinsame Maße des Schlangenkörpers für Server (Anker, Trefferzonen) und Client (Renderer). Alle Längen in Blöcken.
 *
 * Der Entity-Punkt ist der Bodenpunkt unter dem **Rumpf vorn** (Glieder 2–4, Trefferbox 4,5 × 3,6); die Rumpfachse
 * liegt [SPINE_UP] darüber. Der Kopf hängt nicht an einem festen Körper, sondern sitzt je Pose an einem Gelenkpunkt
 * vor/über dem Rumpf ([head]); Hals und Körper folgen ihm (siehe client/model/nivaris/NivarisModel.kt).
 */
object NivarisShape {
    /** Renderer-Faktor: 1 Modelleinheit = SCALE/16 Blöcke, ein Glied (16 Einheiten) = 2,4 Blöcke. */
    const val SCALE = 2.4f

    /** Bogenlänge je Glied. */
    const val SEGMENT = 2.4

    const val SEGMENTS = dev.henny.hha_arius.arena.ArenaLayout.NIVARIS_SEGMENTS

    /** Höhe der Rumpfachse über dem Entity-Punkt (Mitte der Trefferbox). */
    const val SPINE_UP = 1.8

    /** Schädelmitte (Modell: (0, −2,5, −9) im Kopfteil) liegt so weit vor bzw. über dem Kopfgelenk. */
    const val HEAD_CENTER = 9.0 * SCALE / 16.0
    const val HEAD_CENTER_UP = 2.5 * SCALE / 16.0

    /** Kehlnaht (Modell: (0, 4,5, −1) im Kopfteil) unter dem Kopfgelenk. */
    const val THROAT_DOWN = 4.5 * SCALE / 16.0

    /** Herzkern (Modell: (0, 7,5, 8) in Glied 3) unter der Rumpfachse; Glied 3 liegt etwa über dem Entity-Punkt. */
    const val HEART_DOWN = 7.5 * SCALE / 16.0 * 1.1

    /** Gliedteile: 0 = Hals, 3 = Herzenserbe, jedes zweite (bis 37) mit Kristallplatte. */
    const val HEART_GLIED = 3
    const val WING_GLIED = 2
    const val FORE_GLIED = 3
    const val HIND_GLIED = 17

    /** Kopfgelenk relativ zur Entity: [fwd] entlang der Blickrichtung, [up] über dem Entity-Punkt, [pitch] + = Nase runter. */
    class Head(val fwd: Double, val up: Double, val pitch: Double)

    private fun h(fwd: Double, up: Double, pitch: Double) = Head(fwd, up, pitch)

    private val GROUND = Array(NivarisPose.COUNT) { h(3.0, 5.4, 0.2) }.also {
        it[NivarisPose.INHALE] = h(1.6, 6.8, -0.45)
        it[NivarisPose.BREATH] = h(4.0, 4.4, 0.5)
        it[NivarisPose.LAND] = h(3.2, 4.6, 0.3)
        it[NivarisPose.TAIL] = h(2.8, 5.0, 0.2)
        it[NivarisPose.WING_GUST] = h(2.8, 5.8, 0.0)
        it[NivarisPose.GRAB] = h(4.6, 1.6, 1.0)
        it[NivarisPose.THROW] = h(3.2, 5.6, 0.1)
        it[NivarisPose.ROAR] = h(2.2, 7.2, -0.75)
        it[NivarisPose.DOWNED] = h(5.0, 0.9, 0.05)
        it[NivarisPose.EXHAUSTED] = h(4.6, 1.2, 0.25)
        it[NivarisPose.TAKEOFF] = h(3.4, 5.6, 0.05)
        it[NivarisPose.RESONANCE] = h(2.0, 7.6, -0.55)
        it[NivarisPose.LIE_DOWN] = h(3.6, 1.1, 0.1)
        it[NivarisPose.FREEZE_FIELD] = h(3.8, 5.0, 0.6)
    }

    private val AIR = Array(NivarisPose.COUNT) { h(5.6, 2.6, 0.15) }.also {
        it[NivarisPose.GLIDE] = h(6.4, 1.9, 0.05)
        it[NivarisPose.INHALE] = h(4.2, 4.0, -0.4)
        it[NivarisPose.BREATH] = h(6.2, 1.4, 0.6)
        it[NivarisPose.DIVE] = h(6.6, 0.8, 0.55)
        it[NivarisPose.CRASH_DIVE] = h(3.0, -3.5, 1.35)
        it[NivarisPose.LAND] = h(5.2, 2.4, 0.3)
        it[NivarisPose.DOWNED] = h(4.5, 1.0, 0.3)
        it[NivarisPose.RESONANCE] = h(4.0, 5.5, -0.5)
        it[NivarisPose.FREEZE_FIELD] = h(5.6, 2.0, 0.7)
        it[NivarisPose.WING_GUST] = h(5.0, 3.0, 0.0)
        it[NivarisPose.ROAR] = h(4.6, 4.6, -0.7)
    }

    /** Gehaltene Endhaltung des Kopfgelenks (Anker, Trefferbox). Das Modell blendet weich dorthin. */
    fun head(pose: Int, flying: Boolean): Head = (if (flying) AIR else GROUND).getOrElse(pose) { if (flying) AIR[0] else GROUND[0] }

    /** Schädelmitte (vor, hoch) relativ zur Entity. */
    fun headCenter(pose: Int, flying: Boolean): Pair<Double, Double> {
        val k = head(pose, flying)
        val c = kotlin.math.cos(k.pitch)
        val s = kotlin.math.sin(k.pitch)
        return (k.fwd + c * HEAD_CENTER + s * HEAD_CENTER_UP) to (k.up - s * HEAD_CENTER + c * HEAD_CENTER_UP)
    }

    /** Mitte zwischen Kehlnaht (unter dem Kopfgelenk) und Herzkern (Bauch von Glied 3, etwa über dem Entity-Punkt). */
    fun throat(pose: Int, flying: Boolean): Pair<Double, Double> {
        val k = head(pose, flying)
        val throatFwd = k.fwd - kotlin.math.sin(k.pitch) * THROAT_DOWN
        val throatUp = k.up - kotlin.math.cos(k.pitch) * THROAT_DOWN
        return (throatFwd + 0.0) * 0.5 to (throatUp + SPINE_UP - HEART_DOWN) * 0.5
    }

    /**
     * Verjüngung des Querschnitts (x und y) je Glied (0 = Hals … 39); die Länge bleibt, damit der Bogenabstand stimmt.
     * Gleiche Tabelle wie `taper(i)` in tools/assets/nivaris_model.py (docs/NIVARIS_SERPENT.md).
     */
    fun taper(i: Int): Float = when {
        i <= 0 -> 1f
        i < 5 -> floatArrayOf(0.96f, 1.04f, 1.1f, 1.12f)[i - 1]
        else -> (1.12 - 0.8 * Math.pow(((i - 5) / (SEGMENTS - 1.0 - 5.0)).coerceIn(0.0, 1.0), 1.25)).toFloat()
    }

    /** Welches Teil auf Glied i sitzt (wie `glied_part` in nivaris_model.py). */
    fun gliedPart(i: Int): String = when {
        i == 0 -> "neck"
        i == HEART_GLIED -> "segment_heart"
        i % 2 == 0 && i < SEGMENTS - 2 -> "segment_dorsal"
        else -> "segment"
    }
}

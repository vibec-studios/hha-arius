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

    const val COUNT = 34
}

/** Formen von Arius ([AriusEntity.form]). */
object AriusForm {
    /** Rauhreif-Form: Eirholm-Rüstung unter dem offenen Mantel. */
    const val DARK = 0

    /** Abschnitt I ab 40 %: Mantel abgelegt, Rüstung frei. */
    const val SERIOUS = 1

    /** Weiße Form: weißes Gewand, stahlblaue Schärpe, keine Rüstungsplatten. */
    const val WHITE = 2
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

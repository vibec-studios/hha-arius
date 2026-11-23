package dev.henny.hha_arius.boss

/**
 * Alle Zeiten des Kampfes in Ticks (Balancing-Startwerte, siehe docs/KONZEPT.md). Kampf und Modell lesen dieselben
 * Werte: [windup] = Ticks vom Posenwechsel bis zum Treffer, [recover] = Ticks nach dem Treffer bis zurück in IDLE.
 * Der Server kann je Angriff ein anderes Ausholen setzen ([AriusEntity.windup]); das Modell richtet den Schlag dann
 * danach aus.
 */
object AriusTimings {

    /** Standard-Ausholen je [AriusPose] (Index = Pose). 0 = keine Angriffspose. */
    @JvmField
    val ARIUS_WINDUP = IntArray(AriusPose.COUNT).also {
        it[AriusPose.RISE] = 30
        it[AriusPose.COMBO_1] = 14
        it[AriusPose.COMBO_2] = 12
        it[AriusPose.COMBO_3] = 16
        it[AriusPose.COMBO_4] = 12
        it[AriusPose.LUNGE] = 16
        it[AriusPose.WALL_CAST] = 16
        it[AriusPose.STRIP_SLASH] = 20
        it[AriusPose.PARRY_KICK] = 10
        it[AriusPose.GRIP_CAST] = 18
        it[AriusPose.POMMEL] = 10
        it[AriusPose.STAB_GROUND] = 20
        it[AriusPose.SIDESTEP] = 6
        it[AriusPose.ARROW_CUT] = 4
        it[AriusPose.CUT_RELEASE] = 6
        it[AriusPose.GATE_KICK] = 14
        it[AriusPose.TRANSFORM] = 40
        it[AriusPose.LAUNCH] = 10
        it[AriusPose.AIR_KICK] = 12
        it[AriusPose.NULL_CUT] = 16
        it[AriusPose.BREAKTHROUGH] = 26
        it[AriusPose.IAI_DRAW] = 4
        it[AriusPose.THROW_CRYSTALS] = 14
        it[AriusPose.SPLIT_FIELD] = 30
        it[AriusPose.WHISTLE] = 12
        it[AriusPose.MACE_CATCH] = 5
        it[AriusPose.MACE_SLAM] = 4
        it[AriusPose.FORGE_ICE] = 12
        it[AriusPose.SPIN_CUT] = 14
        it[AriusPose.KICK_PREP] = 16
    }

    /** Nachlauf je [AriusPose] nach dem Treffer, bis IDLE (Haltungen: 0 = gehalten). */
    @JvmField
    val ARIUS_RECOVER = IntArray(AriusPose.COUNT).also {
        it[AriusPose.RISE] = 6
        it[AriusPose.COMBO_1] = 8
        it[AriusPose.COMBO_2] = 8
        it[AriusPose.COMBO_3] = 26
        it[AriusPose.COMBO_4] = 22
        it[AriusPose.LUNGE] = 30
        it[AriusPose.WALL_CAST] = 12
        it[AriusPose.STRIP_SLASH] = 20
        it[AriusPose.PARRY_KICK] = 8
        it[AriusPose.GRIP_CAST] = 20
        it[AriusPose.POMMEL] = 10
        it[AriusPose.STAB_GROUND] = 40
        it[AriusPose.SIDESTEP] = 6
        it[AriusPose.ARROW_CUT] = 4
        it[AriusPose.GATE_KICK] = 14
        it[AriusPose.LAUNCH] = 6
        it[AriusPose.AIR_KICK] = 12
        it[AriusPose.NULL_CUT] = 16
        it[AriusPose.BREAKTHROUGH] = 30
        it[AriusPose.IAI_DRAW] = 50
        it[AriusPose.THROW_CRYSTALS] = 12
        it[AriusPose.SPLIT_FIELD] = 30
        it[AriusPose.WHISTLE] = 10
        it[AriusPose.SPIN_CUT] = 12
        it[AriusPose.KICK_PREP] = 20
    }

    /** Standard-Ausholen je [NivarisPose]. */
    @JvmField
    val NIVARIS_WINDUP = IntArray(NivarisPose.COUNT).also {
        it[NivarisPose.INHALE] = 30
        it[NivarisPose.DIVE] = 26
        it[NivarisPose.LAND] = 24
        it[NivarisPose.CLAW] = 14
        it[NivarisPose.TAIL] = 18
        it[NivarisPose.WING_GUST] = 20
        it[NivarisPose.GRAB] = 12
        it[NivarisPose.THROW] = 24
        it[NivarisPose.ROAR] = 10
        it[NivarisPose.TAKEOFF] = 12
        it[NivarisPose.CRASH_DIVE] = 30
        it[NivarisPose.FREEZE_FIELD] = 30
    }

    /** Nachlauf je [NivarisPose]. */
    @JvmField
    val NIVARIS_RECOVER = IntArray(NivarisPose.COUNT).also {
        it[NivarisPose.LAND] = 14
        it[NivarisPose.CLAW] = 10
        it[NivarisPose.TAIL] = 12
        it[NivarisPose.WING_GUST] = 14
        it[NivarisPose.THROW] = 16
        it[NivarisPose.ROAR] = 26
        it[NivarisPose.TAKEOFF] = 10
        it[NivarisPose.FREEZE_FIELD] = 14
    }

    // --- Kampf: Abschnitt I --------------------------------------------------------------------------------------

    const val COMBO_OPEN = 22
    const val LUNGE_OPEN = 24
    const val LUNGE_PILLAR_STAGGER = 40
    const val LUNGE_SPEED = 1.4
    const val GUARD_TICKS = 28
    const val GUARD_TICKS_SERIOUS = 24
    const val GUARD_TICKS_WHITE = 22
    const val GUARD_UNTOUCHED_OPEN = 22

    /** Seit 1.11.0: So lange nach dem sichtbaren Beginn greift die Konterhaltung noch nicht. */
    const val COUNTER_GRACE = 5

    /** Seit 1.11.0: Dauer der sichtbaren Reaktions-Haltung gegen blindes Einhämmern (inklusive [COUNTER_GRACE]). */
    const val REACT_STANCE = 22
    const val GRIP_OPEN = 18
    const val STAB_OPEN = 24
    const val STRIP_DELAY = 6
    const val STRIP_WINDUP = 18
    const val STRIP_OPEN = 18

    // --- Sequenzen ------------------------------------------------------------------------------------------------

    const val HOLD_MAX = 40
    const val HOLD_GRACE = 30
    const val HOLD_LOCKOUT = 60

    // --- Der Schnitt ------------------------------------------------------------------------------------------------

    const val CUT_WIND = 40
    const val CUT_LINE_AT = 30
    const val CUT_LINE_TICKS = 60
    const val CUT_SILENCE = 14
    const val CUT_SLIDE = 46

    // --- Nivaris ------------------------------------------------------------------------------------------------------

    const val AIR_MAX = 320 // seit 1.5.1 länger in der Luft (Kang-Jin Loong)
    const val AIR_MAX_VAULT = 180
    const val DOWNED_TICKS = 90
    const val CHOKE_STAGGER = 70
    const val EXHAUSTED_TICKS = 40
    const val MAX_RIDGES = 3
    const val MAX_RIDGES_VAULT = 2

    // --- Abschnitt III -----------------------------------------------------------------------------------------------

    const val RESONANCE_WARN = 14
    const val RESONANCE_EVERY = 220 // seit 1.10.0 noch öfter Duo-Angriffe (1.8.0: 320, davor 400)
    const val TAKT_BREAK_OPEN = 50
    /** Weißes Loch (seit 1.9.0 kurz und sich steigernd): Sammeln mit wachsendem Sog, dann die angesagte Entladung. */
    const val CORE_BUILD = 110
    const val CORE_STRIKE = 40
    const val CORE_END_OPEN = 100

}

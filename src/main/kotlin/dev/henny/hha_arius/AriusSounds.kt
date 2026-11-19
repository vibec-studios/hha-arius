package dev.henny.hha_arius

import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.sound.SoundEvent

/**
 * Alle Klänge von HHA Arius (IDs siehe docs/SPEC_TEAM.md). Die OGGs und sounds.json erzeugen
 * tools/assets/audio/arius_music.py und arius_sounds.py; alles ist synthetisiert, keine fremden Samples.
 *
 * Musik-Events haben eine feste Reichweite ([MUSIC_RANGE]), damit eine Schleife, die an einer Position im Raum
 * gestartet wird, in der ganzen Arena gleich laut bleibt. Effekte nutzen die normale, lautstärkeabhängige Reichweite.
 */
object AriusSounds {

    /** Feste Reichweite der Musik in Blöcken: deckt die größte Arena (Gletscherschwelle, R 46) mit Rand ab. */
    const val MUSIC_RANGE = 128f

    // Musik (Stereo, nahtlose Schleifen)
    val MUSIC_TEMPLE = music("music.temple")
    val MUSIC_GLACIER = music("music.glacier")
    val MUSIC_HEART = music("music.heart")
    val MUSIC_FINALE = music("music.finale")
    val MUSIC_ENTRANCE = music("music.entrance")
    val MUSIC_TRANSFORM = music("music.transform")

    // Arius
    val ARIUS_SLASH = register("arius.slash")
    val ARIUS_THRUST = register("arius.thrust")
    val ARIUS_PARRY = register("arius.parry")
    val ARIUS_KICK = register("arius.kick")
    val ARIUS_WIND_STEP = register("arius.wind_step")
    val ARIUS_GUARD = register("arius.guard")
    val ARIUS_ICE_WALL = register("arius.ice_wall")
    val ARIUS_FROST_STRIP = register("arius.frost_strip")
    val ARIUS_STAB_GROUND = register("arius.stab_ground")
    val ARIUS_GRIP = register("arius.grip")
    val ARIUS_ARROW_CUT = register("arius.arrow_cut")
    val ARIUS_NULL_CUT = register("arius.null_cut")

    // Der Schnitt
    val CUT_REVERSE_WIND = register("cut.reverse_wind")
    val CUT_CHARGE = register("cut.charge")
    val CUT_STRIKE = register("cut.strike")
    val CUT_SPLIT = register("cut.split")

    // Nivaris
    val NIVARIS_ROAR = register("nivaris.roar")
    // Seit 1.8.0: tiefe Schichten aus Vanilla-Klängen (siehe EirholmFight.layers)
    val NIVARIS_ROAR_BODY = register("nivaris.roar_body")
    val NIVARIS_ROAR_LOW = register("nivaris.roar_low")
    val NIVARIS_WING_BODY = register("nivaris.wing_body")
    val NIVARIS_BREATH_BODY = register("nivaris.breath_body")
    val NIVARIS_INHALE_BODY = register("nivaris.inhale_body")
    val NIVARIS_HIT_BODY = register("nivaris.hit_body")
    val NIVARIS_INHALE = register("nivaris.inhale")
    val NIVARIS_BREATH = register("nivaris.breath")
    val NIVARIS_WING = register("nivaris.wing")
    val NIVARIS_LAND = register("nivaris.land")
    val NIVARIS_CLAW = register("nivaris.claw")
    val NIVARIS_TAIL = register("nivaris.tail")
    val NIVARIS_CHOKE = register("nivaris.choke")
    val NIVARIS_CRASH = register("nivaris.crash")

    // Welt
    val GLACIER_CRACK = register("glacier.crack")
    val GLACIER_COLLAPSE = register("glacier.collapse")
    val RIDGE_RISE = register("ridge.rise")
    val RIDGE_BREAK = register("ridge.break")
    val CRYSTAL_HANG = register("crystal.hang")
    val SEAL_BLIZZARD = register("seal.blizzard")

    // Signaturen
    val TRANSFORM_SHATTER = register("transform.shatter")
    val TRANSFORM_PULSE = register("transform.pulse")
    val RESONANCE_CHIME = register("resonance.chime")
    val CORE_FORM = register("core.form")
    val CORE_PULSE = register("core.pulse")
    val CORE_COLLAPSE = register("core.collapse")
    val IAI_GLINT = register("iai.glint")
    val IAI_DRAW = register("iai.draw")
    val HOLD_IMPACT = register("hold.impact")
    val LEAD_SWITCH = register("lead.switch")

    // Ausrüstung
    val SOLVANE_PARRY = register("solvane.parry")
    val WHITE_LINE_CHARGE = register("white_line.charge")
    val WHITE_LINE_RELEASE = register("white_line.release")
    val FROST_STEP = register("frost_step")
    val WHITE_CORE = register("white_core")
    val WINGSCALE_FLAP = register("wingscale.flap")

    private fun register(path: String): SoundEvent {
        val id = HhaArius.id(path)
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id))
    }

    private fun music(path: String): SoundEvent {
        val id = HhaArius.id(path)
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id, MUSIC_RANGE))
    }

    /** Lädt das Objekt (und damit alle Registrierungen); aus dem Mod-Initializer aufrufen. */
    fun init() {}
}

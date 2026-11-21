package dev.henny.hha_arius.client.fx

import dev.henny.hha_arius.boss.MusicCueS2C
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.MovingSoundInstance
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier
import net.minecraft.util.math.random.Random

/**
 * Adaptive Musikregie (seit 1.10.0, Crix: „die Musik soll sich je Phase, Cutscene und QTE ändern“).
 *
 * Jeder **Zustand** ([STATES]) besteht aus einem Grundstück und optional einer zweiten Schicht, je mit Lautstärke und
 * Tonhöhe. Beim Wechsel laufen Stücke, die beide Zustände teilen, ohne Neustart weiter – nur Lautstärke und Tonhöhe
 * gleiten (z. B. Ritt → QTE: der Ritt duckt sich, die Spannungsschicht kommt dazu). Alles andere wird in der verlangten
 * Zeit überblendet. **Stinger** ([STINGS]) sind kurze Akzente (Klänge der Kampfmusik-Palette, eigene Tonhöhe) und
 * ducken die Musik kurz; **Ducking** senkt die Musik für dramatische Stille.
 *
 * Befehle kommen als [MusicCueS2C] (dev.henny.hha_arius.boss.MusicCue) oder wie bisher als Stück-ID
 * (EirholmFight.music → [handle]); die Stück-ID wird dem passenden Zustand zugeordnet. Vanilla-Musik pausiert, solange
 * etwas läuft.
 */
object AriusMusic {

    private class Spec(val track: String?, val volume: Float = 1f, val pitch: Float = 1f, val layer: String? = null, val layerVolume: Float = 0f, val layerPitch: Float = 1f)

    private fun m(name: String) = "hha_arius:music.$name"

    private val STATES: Map<String, Spec> = mapOf(
        "silence" to Spec(null),
        "temple" to Spec(m("temple")),
        // Ernst (Arius unter 40 %): das Tempelthema bleibt, darunter der Herzschlag der Jagd.
        "temple_serious" to Spec(m("temple"), 1f, 1f, m("hunt"), 0.45f, 0.94f),
        // Der Weiße Horizont lädt: nur Liegeton und Herzschlag, tief.
        "cut" to Spec(null, layer = m("hunt"), layerVolume = 0.75f, layerPitch = 0.8f),
        // Nach dem Schlag: die Verwandlungsmusik, gedehnt und dunkel.
        "horizon" to Spec(m("transform"), 0.95f, 0.82f),
        "kick" to Spec(null, layer = m("qte"), layerVolume = 0.35f, layerPitch = 0.75f),
        "entrance" to Spec(m("entrance")),
        "reveal" to Spec(m("entrance"), 1f, 1f),
        "carry" to Spec(m("ride"), 0.8f, 0.92f),
        "glacier" to Spec(m("glacier")),
        "glacier_open" to Spec(m("glacier"), 0.7f, 1f, m("qte"), 0.25f, 0.58f),
        "ride_intro" to Spec(m("transform"), 0.9f, 1f),
        "ride" to Spec(m("ride")),
        "qte" to Spec(m("ride"), 0.55f, 1f, m("qte"), 0.9f, 1f),
        "storm" to Spec(m("ride"), 0.85f, 0.97f, m("hunt"), 0.4f, 1.1f),
        "dive" to Spec(m("ride"), 1f, 1.06f),
        "transform" to Spec(m("transform")),
        "heart" to Spec(m("heart")),
        "white_hole" to Spec(m("heart"), 0.8f, 0.9f, m("hunt"), 0.45f, 0.85f),
        "finale" to Spec(m("finale")),
        "ending" to Spec(m("finale"), 0.85f, 0.96f),
        "victory" to Spec(m("transform"), 0.9f, 1.06f),
        "defeat" to Spec(m("finale"), 0.6f, 0.84f),
        "hunt" to Spec(m("hunt")),
        // Jagd, Arius ganz nah: der Herzschlag schneller, die Spannungsschicht dazu.
        "hunt_close" to Spec(m("hunt"), 1f, 1.08f, m("qte"), 0.3f, 0.66f),
        "summit" to Spec(m("summit")),
        "summit_final" to Spec(m("summit"), 1f, 1.05f, m("hunt"), 0.25f, 1.2f),
    )

    /** Stinger: Klänge (ID, Tonhöhe, Lautstärke), Ducking-Tiefe. */
    private class Sting(val sounds: List<Triple<String, Float, Float>>, val duck: Float)

    private val STINGS: Map<String, Sting> = mapOf(
        "phase" to Sting(listOf(Triple("hha_arius:transform.pulse", 0.7f, 0.9f), Triple("hha_arius:cut.strike", 0.55f, 0.6f)), 0.25f),
        "impact" to Sting(listOf(Triple("hha_arius:cut.strike", 0.5f, 0.8f)), 0.35f),
        "reveal" to Sting(listOf(Triple("hha_arius:resonance.chime", 0.75f, 0.8f), Triple("hha_arius:core.form", 0.8f, 0.5f)), 0.5f),
        "roar" to Sting(listOf(Triple("hha_arius:nivaris.roar_low", 0.8f, 0.7f)), 0.4f),
        "qte_ok" to Sting(listOf(Triple("hha_arius:resonance.chime", 1.45f, 0.55f)), 0.8f),
        "qte_fail" to Sting(listOf(Triple("hha_arius:hold.impact", 0.7f, 0.8f), Triple("hha_arius:glacier.crack", 1.2f, 0.5f)), 0.4f),
        "parry" to Sting(listOf(Triple("hha_arius:solvane.parry", 0.85f, 0.7f)), 0.6f),
        "punish" to Sting(listOf(Triple("hha_arius:white_line.release", 0.8f, 0.7f)), 0.45f),
        "fall" to Sting(listOf(Triple("hha_arius:transform.shatter", 0.6f, 0.6f)), 0.3f),
    )

    private class Loop(val id: String, event: SoundEvent, vol: Float, pitch0: Float) :
        MovingSoundInstance(event, SoundCategory.MUSIC, Random.create()) {
        var targetVolume = vol
        var targetPitch = pitch0
        var fadeTicks = 20
        var gone = false

        init {
            repeat = true
            repeatDelay = 0
            relative = true
            attenuationType = SoundInstance.AttenuationType.NONE
            volume = 0.001f
            pitch = pitch0
        }

        override fun tick() {
            val want = (targetVolume * duckNow).coerceIn(0f, 1f)
            val step = 1f / fadeTicks.coerceAtLeast(1)
            volume = if (volume < want) (volume + step).coerceAtMost(want) else (volume - step).coerceAtLeast(want)
            pitch += (targetPitch - pitch).coerceIn(-0.01f, 0.01f)
            if (gone && volume <= 0.002f) setDone()
        }

        fun fadeOut(ticks: Int) {
            gone = true
            targetVolume = 0f
            fadeTicks = ticks.coerceAtLeast(1)
        }
    }

    private val loops = ArrayList<Loop>()
    private var state = ""

    private var duckNow = 1f
    private var duckLevel = 1f
    private var duckLeft = 0

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(MusicCueS2C.ID) { p, _ ->
            when (p.kind) {
                0 -> setState(p.name, p.ticks)
                1 -> sting(p.name)
                2 -> duck(p.level, p.ticks)
            }
        }
    }

    /** Alte Schnittstelle: Stück-ID aus EirholmFight.music ("" = Musik aus). */
    fun handle(event: String, fade: Int) {
        if (event.isEmpty()) {
            setState("silence", fade)
            state = ""
            return
        }
        val name = event.substringAfter(':').removePrefix("music.")
        val mapped = when (name) {
            "temple" -> "temple"
            "glacier" -> "glacier"
            "heart" -> "heart"
            "finale" -> "finale"
            "entrance" -> "entrance"
            "transform" -> "transform"
            "hunt", "ride", "summit" -> name
            else -> null
        }
        // Der Server schickt das Grundstück oft zusammen mit einem genaueren Zustand: teilen beide das Stück, bleibt es.
        val cur = STATES[state]
        if (mapped != null && cur != null && cur.track == STATES[mapped]?.track && state != "silence") return
        if (mapped != null) setState(mapped, fade) else playRaw(event, fade)
    }

    private fun setState(name: String, fade: Int) {
        val spec = STATES[name] ?: return
        state = name
        val f = fade.coerceAtLeast(1)
        val want = listOfNotNull(
            spec.track?.let { Triple(it, spec.volume, spec.pitch) },
            spec.layer?.let { Triple(it, spec.layerVolume, spec.layerPitch) },
        )
        val keep = HashSet<Loop>()
        for ((id, vol, pitch) in want) {
            val running = loops.firstOrNull { it.id == id && !it.gone && it !in keep }
            if (running != null) {
                running.targetVolume = vol
                running.targetPitch = pitch
                running.fadeTicks = f
                keep += running
            } else {
                val loop = Loop(id, SoundEvent.of(Identifier.of(id)), vol, pitch)
                loop.fadeTicks = f
                loops += loop
                keep += loop
                MinecraftClient.getInstance().soundManager.play(loop)
            }
        }
        for (l in loops) if (l !in keep) l.fadeOut(f)
        loops.removeIf { it.isDone }
    }

    private fun playRaw(event: String, fade: Int) {
        state = ""
        for (l in loops) l.fadeOut(fade)
        val loop = Loop(event, SoundEvent.of(Identifier.of(event)), 1f, 1f)
        loop.fadeTicks = fade.coerceAtLeast(1)
        loops += loop
        MinecraftClient.getInstance().soundManager.play(loop)
    }

    private fun sting(name: String) {
        val s = STINGS[name] ?: return
        val mc = MinecraftClient.getInstance()
        for ((id, pitch, vol) in s.sounds) {
            mc.soundManager.play(PositionedSoundInstance(Identifier.of(id), SoundCategory.MUSIC, vol, pitch, Random.create(), false, 0, SoundInstance.AttenuationType.NONE, 0.0, 0.0, 0.0, true))
        }
        duck(s.duck, 16)
    }

    private fun duck(level: Float, ticks: Int) {
        duckLevel = minOf(if (duckLeft > 0) duckLevel else 1f, level.coerceIn(0f, 1f))
        duckLeft = maxOf(duckLeft, ticks)
    }

    fun tick() {
        if (duckLeft > 0) {
            duckLeft--
            duckNow += (duckLevel - duckNow) * 0.35f
        } else {
            duckNow += (1f - duckNow) * 0.06f
        }
        loops.removeIf { it.isDone }
        if (loops.isNotEmpty()) MinecraftClient.getInstance().musicTracker.stop()
    }

    fun clear() {
        for (l in loops) l.fadeOut(1)
        loops.clear()
        state = ""
        duckLeft = 0
        duckNow = 1f
    }
}

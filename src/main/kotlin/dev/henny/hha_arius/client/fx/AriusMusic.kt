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
import kotlin.math.PI
import kotlin.math.sin

/**
 * Adaptive Musikregie (seit 1.10.0, Crix: „die Musik soll sich je Phase, Cutscene und QTE ändern“).
 *
 * Jeder **Zustand** ([STATES]) besteht aus einem Grundstück und optional einer zweiten Schicht, je mit eigener
 * Lautstärke. Beim Wechsel laufen Stücke, die beide Zustände teilen, ohne Neustart weiter; alles andere wird in der
 * verlangten Zeit überblendet. **Stinger** ([STINGS]) sind kurze Akzente und ducken die Musik kurz; **Ducking** senkt
 * die Musik für dramatische Stille.
 *
 * Seit 1.11.0 (Crix: „die Musik ist manchmal komisch und verändert sich weird“) sind die Ursachen behoben:
 * * **Keine Tonhöhen mehr auf Musik.** Zustände desselben Stücks hatten eigene Tonhöhen, beim Wechsel glitt das Stück
 *   hörbar auf und ab, und Schichten liefen gegeneinander verstimmt. Unterschiede entstehen jetzt nur über Lautstärke.
 * * **Kein Doppelstart.** Kam ein Stück zurück, während es noch ausblendete, startete es ein zweites Mal von vorn und lief
 *   versetzt über sich selbst. Jetzt blendet das laufende wieder ein.
 * * **Kein Flattern.** Verwandte Zustände (Jagd / Jagd nah …) wechseln höchstens alle [DWELL] Ticks; der letzte Wunsch gilt.
 * * **Gleichmäßige Überblendung** (gleiche Leistung statt linear) und nie kürzer als [MIN_FADE] Ticks (kein Knacken).
 * * Die alte Schnittstelle ([handle]) stört keinen genaueren Zustand mehr, der dasselbe Stück nutzt.
 *
 * Befehle kommen als [MusicCueS2C] (dev.henny.hha_arius.boss.MusicCue) oder wie bisher als Stück-ID
 * (EirholmFight.music → [handle]). Vanilla-Musik pausiert, solange etwas läuft.
 */
object AriusMusic {

    private class Spec(val track: String?, val volume: Float = 1f, val layer: String? = null, val layerVolume: Float = 0f)

    private fun m(name: String) = "hha_arius:music.$name"

    private val STATES: Map<String, Spec> = mapOf(
        "silence" to Spec(null),
        "temple" to Spec(m("temple")),
        // Ernst (Arius unter 40 %): das Tempelthema bleibt, darunter der Herzschlag der Jagd.
        "temple_serious" to Spec(m("temple"), 1f, m("hunt"), 0.32f),
        // Der Weiße Horizont lädt: nur Liegeton und Herzschlag.
        "cut" to Spec(null, layer = m("hunt"), layerVolume = 0.6f),
        // Nach dem Schlag: die Verwandlungsmusik.
        "horizon" to Spec(m("transform"), 0.9f),
        "kick" to Spec(null, layer = m("qte"), layerVolume = 0.3f),
        "entrance" to Spec(m("entrance")),
        "reveal" to Spec(m("entrance")),
        "carry" to Spec(m("ride"), 0.8f),
        "glacier" to Spec(m("glacier")),
        "glacier_open" to Spec(m("glacier"), 0.72f, m("qte"), 0.2f),
        "ride_intro" to Spec(m("transform"), 0.9f),
        "ride" to Spec(m("ride")),
        "qte" to Spec(m("ride"), 0.6f, m("qte"), 0.75f),
        "storm" to Spec(m("ride"), 0.88f, m("hunt"), 0.3f),
        "dive" to Spec(m("ride")),
        "transform" to Spec(m("transform")),
        "heart" to Spec(m("heart")),
        "white_hole" to Spec(m("heart"), 0.8f, m("hunt"), 0.35f),
        "finale" to Spec(m("finale")),
        "ending" to Spec(m("finale"), 0.85f),
        "victory" to Spec(m("transform"), 0.9f),
        "defeat" to Spec(m("finale"), 0.6f),
        "hunt" to Spec(m("hunt")),
        // Jagd, Arius ganz nah: die Spannungsschicht kommt dazu.
        "hunt_close" to Spec(m("hunt"), 1f, m("qte"), 0.24f),
        "summit" to Spec(m("summit")),
        "summit_final" to Spec(m("summit"), 1f, m("hunt"), 0.2f),
    )

    /** Verwandte Zustände: Sie dürfen nicht im Sekundentakt hin und her springen. */
    private val FAMILY: Map<String, Int> = mapOf(
        "hunt" to 1, "hunt_close" to 1,
        "glacier" to 2, "glacier_open" to 2,
        "heart" to 3, "white_hole" to 3,
        "temple" to 4, "temple_serious" to 4,
        "summit" to 5, "summit_final" to 5,
    )

    /** Mindestabstand zwischen zwei Wechseln innerhalb einer Familie (Ticks). */
    private const val DWELL = 80

    /** Kürzeste Überblendung (Ticks): Ein Sprung der Lautstärke von einem Tick knackt hörbar. */
    private const val MIN_FADE = 4

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

    private class Loop(val id: String, event: SoundEvent, vol: Float) :
        MovingSoundInstance(event, SoundCategory.MUSIC, Random.create()) {
        var targetVolume = vol
        var fadeTicks = 20
        var gone = false

        /** Überblend-Fortschritt 0..1; hörbar als Viertelsinus (gleiche Leistung beim Kreuzen zweier Stücke). */
        private var level = 0f

        init {
            repeat = true
            repeatDelay = 0
            relative = true
            attenuationType = SoundInstance.AttenuationType.NONE
            volume = 0.001f
            pitch = 1f
        }

        override fun tick() {
            val step = 1f / fadeTicks.coerceAtLeast(1)
            val want = if (gone) 0f else 1f
            level = if (level < want) (level + step).coerceAtMost(want) else (level - step).coerceAtLeast(want)
            val shape = sin(level * PI.toFloat() / 2f)
            // Zielwechsel innerhalb eines laufenden Stücks gleiten ebenfalls über die Überblendzeit.
            shownTarget += (targetVolume - shownTarget).coerceIn(-step, step)
            volume = (shownTarget * shape * duckNow).coerceIn(0f, 1f).coerceAtLeast(0.0005f)
            if (gone && level <= 0f) setDone()
        }

        private var shownTarget = vol

        fun fadeOut(ticks: Int) {
            gone = true
            fadeTicks = ticks.coerceAtLeast(MIN_FADE)
        }

        /** Holt ein ausblendendes Stück zurück, ohne es neu zu starten. */
        fun revive(vol: Float, ticks: Int) {
            gone = false
            targetVolume = vol
            fadeTicks = ticks.coerceAtLeast(MIN_FADE)
        }
    }

    private val loops = ArrayList<Loop>()
    private var state = ""

    private var clock = 0L
    private var changedAt = -1000L
    private var pendingName: String? = null
    private var pendingFade = 0

    private var duckNow = 1f
    private var duckLevel = 1f
    private var duckLeft = 0

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(MusicCueS2C.ID) { p, _ ->
            when (p.kind) {
                0 -> request(p.name, p.ticks)
                1 -> sting(p.name)
                2 -> duck(p.level, p.ticks)
            }
        }
    }

    /** Alte Schnittstelle: Stück-ID aus EirholmFight.music ("" = Musik aus). */
    fun handle(event: String, fade: Int) {
        if (event.isEmpty()) {
            request("silence", fade)
            return
        }
        val name = event.substringAfter(':').removePrefix("music.")
        val mapped = when (name) {
            "temple", "glacier", "heart", "finale", "entrance", "transform", "hunt", "ride", "summit" -> name
            else -> null
        }
        // Der Server schickt das Grundstück oft zusammen mit einem genaueren Zustand: teilen beide das Stück, bleibt es.
        val wanted = pendingName ?: state
        val cur = STATES[wanted]
        if (mapped != null && cur != null && wanted != "silence" && cur.track == STATES[mapped]?.track) return
        if (mapped != null) request(mapped, fade.coerceAtLeast(12)) else playRaw(event, fade)
    }

    /** Wunsch nach einem Zustand: innerhalb einer Familie erst nach der Mindestzeit, sonst sofort. */
    private fun request(name: String, fade: Int) {
        if (name !in STATES) return
        if (name == state) {
            pendingName = null
            return
        }
        val a = FAMILY[state]
        if (a != null && a == FAMILY[name] && clock - changedAt < DWELL) {
            pendingName = name
            pendingFade = fade
            return
        }
        pendingName = null
        setState(name, fade)
    }

    private fun setState(name: String, fade: Int) {
        val spec = STATES[name] ?: return
        state = name
        changedAt = clock
        val f = fade.coerceAtLeast(MIN_FADE)
        val want = listOfNotNull(
            spec.track?.let { it to spec.volume },
            spec.layer?.let { it to spec.layerVolume },
        )
        val keep = HashSet<Loop>()
        for ((id, vol) in want) {
            // Auch ein gerade ausblendendes Stück zählt: Es läuft weiter, statt ein zweites Mal zu starten.
            val running = loops.filter { it.id == id && it !in keep && !it.isDone }.let { same -> same.firstOrNull { !it.gone } ?: same.firstOrNull() }
            if (running != null) {
                running.revive(vol, f)
                keep += running
            } else {
                val loop = Loop(id, SoundEvent.of(Identifier.of(id)), vol)
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
        pendingName = null
        changedAt = clock
        val f = fade.coerceAtLeast(MIN_FADE)
        val same = loops.firstOrNull { it.id == event && !it.isDone }
        for (l in loops) if (l !== same) l.fadeOut(f)
        if (same != null) {
            same.revive(1f, f)
            return
        }
        val loop = Loop(event, SoundEvent.of(Identifier.of(event)), 1f)
        loop.fadeTicks = f
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
        clock++
        pendingName?.let { name ->
            if (clock - changedAt >= DWELL) {
                pendingName = null
                if (name != state) setState(name, pendingFade)
            }
        }
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
        for (l in loops) l.fadeOut(MIN_FADE)
        loops.clear()
        state = ""
        pendingName = null
        changedAt = -1000L
        duckLeft = 0
        duckNow = 1f
    }
}

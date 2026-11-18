package dev.henny.hha_arius.gear.client.art

import dev.henny.hha_arius.gear.art.Anim
import dev.henny.hha_arius.gear.art.ArtParam
import kotlin.math.sin

/**
 * Fingerhaltung der offenen Handschuhe (seit 1.6.4, Crix: „gib den Fingern Animationen“). Liefert in [out]:
 * [0..3] Krümmung je Finger (0 gestreckt … ~1,3 Faust), [4] Spreizung (Bogenmaß), [5] Daumen.
 * * Ruhe im Zaubermodus: locker leicht gekrümmt, die Finger spielen langsam nacheinander (Welle), atmende Spreizung.
 * * Aufladen: Klaue, die sich mit der Ladung schließt und vor Energie zittert, weit gespreizt.
 * * Wirken/Freigabe: Finger schnellen auf (überstreckt) und fallen locker zurück.
 * * Zielen (Bauwerk): die führende Hand zeigt mit dem Zeigefinger, die anderen sind eingerollt.
 * * Schild: flach und weit gespreizt. Moduswechsel: aus der Faust auf bzw. zur Faust.
 */
object FingerAnim {

    private fun mix(a: Float, b: Float, k: Float) = a + (b - a) * k.coerceIn(0f, 1f)

    fun pose(anim: ArtAnims.State?, td: Float, time: Float, lead: Boolean, out: FloatArray) {
        // Ruhe: Welle über die Finger
        for (i in 0 until 4) out[i] = 0.24f + 0.05f * sin(time * 0.07f + i * 0.75f)
        out[4] = 0.1f + 0.04f * sin(time * 0.06f)
        out[5] = 0.35f + 0.05f * sin(time * 0.09f + 1f)
        if (anim == null) return
        val a = anim.age(td)
        when (anim.anim) {
            Anim.CAST_CHARGE -> {
                val c = ArtParam.castCharge(anim.param, a)
                // Seit 1.7.0 ruhig: die Finger legen sich weich um die Kugel, kein Zittern.
                for (i in 0 until 4) out[i] = 0.35f + 0.25f * c + 0.03f * i
                out[4] = 0.22f + 0.08f * c
                out[5] = 0.45f
            }
            Anim.CAST_TAP, Anim.CAST_RELEASE -> {
                val dur = if (anim.anim == Anim.CAST_TAP) 6f else 8f
                val k = a / dur
                val c = if (k < 0.3f) mix(0.75f, -0.3f, k / 0.3f) else mix(-0.3f, 0.24f, (k - 0.3f) / 0.7f)
                if (anim.anim == Anim.CAST_RELEASE || lead) for (i in 0 until 4) out[i] = c + 0.04f * i
                out[4] = if (k < 0.3f) mix(0.1f, 0.38f, k / 0.3f) else mix(0.38f, 0.1f, (k - 0.3f) / 0.7f)
                out[5] = mix(0.8f, 0.1f, k * 2f)
            }
            Anim.PLACE_AIM -> if (lead) {
                out[0] = -0.05f
                out[1] = 1.15f; out[2] = 1.2f; out[3] = 1.25f
                out[4] = 0.04f
                out[5] = 0.9f
            }
            Anim.SHIELD -> {
                for (i in 0 until 4) out[i] = 0.04f
                out[4] = 0.32f
                out[5] = 0.05f
            }
            Anim.MODE_SWITCH -> {
                val k = a / 8f
                val toMagic = anim.param == 1
                for (i in 0 until 4) out[i] = if (toMagic) mix(1.3f, out[i], k + i * 0.08f) else mix(out[i], 1.3f, k + i * 0.08f)
            }
            Anim.WHITE_HOLE -> {
                for (i in 0 until 4) out[i] = 0.5f
                out[4] = 0.28f
                out[5] = 0.6f
            }
            Anim.GLIDE -> {
                for (i in 0 until 4) out[i] = 0.08f
                out[4] = 0.3f
            }
            else -> {}
        }
    }
}

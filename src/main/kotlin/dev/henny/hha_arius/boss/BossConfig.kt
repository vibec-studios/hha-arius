package dev.henny.hha_arius.boss

import dev.henny.hha.api.HhaAddonContext

/**
 * Config-Schlüssel des Kampfes (über HHA: `/hha list|set|get`, Gruppe „Addon: hha_arius“). Werte erst zur Laufzeit
 * lesen: HHA lädt die Config nach den Addon-Entrypoints.
 *
 * Seit 1.10.0 skalieren Leben **und** Schaden der Bosse mit der Zahl der kämpfenden Spieler (Leben deutlich, Schaden
 * mild), und [EirholmFight] rechnet beides neu, sobald jemand dazukommt, geht oder k. o. ist ([PlayerScaling]).
 */
object BossConfig {

    private var context: HhaAddonContext? = null

    fun register(context: HhaAddonContext) {
        this.context = context
        context.registerNumber("boss_damage_scale", 1.0)
        context.registerNumber("boss_damage_per_player", 0.15)
        context.registerNumber("boss_health_scale", 1.0)
        context.registerNumber("boss_health_per_player", 0.6)
        context.registerNumber("boss_max_instances", 4.0)
        context.registerNumber("boss_build_budget", 12000.0)
    }

    private fun num(key: String, fallback: Double): Double = context?.let { runCatching { it.number(key) }.getOrNull() } ?: fallback

    fun damageScale(): Float = num("boss_damage_scale", 1.0).toFloat().coerceIn(0.05f, 10f)

    /** Schadensfaktor der Bosse für [players] kämpfende Spieler: 1 + Anteil × (Spieler − 1), höchstens 6 zählen. */
    fun damageFor(players: Int): Float {
        val per = num("boss_damage_per_player", 0.15).toFloat().coerceIn(0f, 2f)
        return damageScale() * (1f + per * (players.coerceIn(1, 6) - 1))
    }

    /** Faktor des Lebens für [players] Spieler (ohne Grundwert). */
    fun healthFactor(players: Int): Float {
        val per = num("boss_health_per_player", 0.6).toFloat().coerceIn(0f, 5f)
        return num("boss_health_scale", 1.0).toFloat().coerceIn(0.05f, 20f) * (1f + per * (players.coerceIn(1, 6) - 1))
    }

    /** Leben eines Abschnitts: Grundwert × Skalierung × (1 + Anteil × (Spieler − 1)), höchstens 6 Spieler zählen. */
    fun health(base: Float, players: Int): Float = base * healthFactor(players)

    fun maxInstances(): Int = num("boss_max_instances", 4.0).toInt().coerceIn(1, 16)

    fun buildBudget(): Int = num("boss_build_budget", 12000.0).toInt().coerceIn(1000, 100000)
}

/**
 * Live-Skalierung (seit 1.10.0): Die Szenen setzen das Leben eines Abschnitts mit [BossConfig.health] für
 * [EirholmFight.startCount] Spieler. Ändert sich die Zahl der Kämpfenden (Beitritt, Disconnect, k. o.) und bleibt
 * [STABLE] Ticks so, wird das Maximum von Arius und Solvane umgerechnet und der **Anteil** des Lebens beibehalten –
 * wer geht und wiederkommt, heilt den Boss also nicht (und schadet ihm nicht). Die Schadensskalierung folgt sofort.
 */
class PlayerScaling(private val f: EirholmFight) {

    /** Spielerzahl, für die die aktuellen Maxima gelten. */
    private var scaledFor = 0
    private var lastAriusMax = -1f
    private var lastDragonMax = -1f
    private var pending = 0
    private var pendingSince = 0L

    /** Für den Schaden: aktuell kämpfende Spieler (mindestens 1). */
    var count = 1
        private set

    fun tick(active: Int) {
        count = active.coerceAtLeast(1)
        // Eine Szene hat neue Maxima gesetzt: die gelten für startCount.
        if (f.ariusMax != lastAriusMax || f.dragonMax != lastDragonMax) {
            scaledFor = f.startCount
            lastAriusMax = f.ariusMax
            lastDragonMax = f.dragonMax
        }
        if (f.now % 10L != 0L) return
        val want = active.coerceIn(1, 6)
        if (want == scaledFor || scaledFor == 0 || active == 0) {
            pending = 0
            return
        }
        if (want != pending) {
            pending = want
            pendingSince = f.now
            return
        }
        if (f.now - pendingSince < STABLE) return
        val k = BossConfig.healthFactor(want) / BossConfig.healthFactor(scaledFor)
        if (k.isFinite() && k > 0f) {
            val aFrac = if (f.ariusMax > 0f) f.ariusHp / f.ariusMax else 1f
            val dFrac = if (f.dragonMax > 0f) f.dragonHp / f.dragonMax else 1f
            f.ariusMax *= k
            f.ariusHp = (aFrac * f.ariusMax).coerceAtMost(f.ariusMax)
            f.dragonMax *= k
            f.dragonHp = (dFrac * f.dragonMax).coerceAtMost(f.dragonMax)
        }
        lastAriusMax = f.ariusMax
        lastDragonMax = f.dragonMax
        scaledFor = want
        pending = 0
        f.rescaleCount(want)
        f.updateBars()
    }

    companion object {
        const val STABLE = 60L
    }
}

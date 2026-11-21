package dev.henny.hha_arius.arena

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bauplan einer Spalte der Fernlandschaft (seit 1.10.0 aus [FarBuild] herausgelöst, damit der Selbsttest des Jagdwegs
 * ([RouteCheck]) genau denselben Plan prüft, der gebaut wird). Reine Rechnung, keine Welt.
 *
 * Reihenfolge: Gelände (mit Höhlenhauben) → Haut → Weg am Boden (Pfad, Steinbrücke, Hängebrücke) → Höhle
 * → Brücke der zweiten Ebene darüber → Gipfel, Horst, Feld, Rückkehrtore, Hochland.
 */
internal object FarColumns {

    fun plan(x: Int, z: Int, p: ColumnPlan) {
        val nat = FarPlan.terrain(x, z)
        val hit = HuntRoute.hit(x, z)
        val ri = if (hit >= 0) HuntRoute.hitIndex(hit) else -1
        val lat = if (hit >= 0) HuntRoute.hitLateral(hit) else 99.0
        val onPath = ri >= 0 && lat <= HuntRoute.HALF_W
        val kind = if (ri >= 0) HuntRoute.kind(ri) else -1
        val py = if (ri >= 0) HuntRoute.y(ri) else 0.0
        val f = floor(py).toInt()
        val cave = if (ri >= 0 && kind == HuntRoute.TUNNEL) HuntRoute.caveOf(ri) else null
        val roofed = kind == HuntRoute.TUNNEL && nat > f + 5
        // Oberkante der Haut
        val surface = when {
            !onPath -> nat
            kind == HuntRoute.BRIDGE -> if (nat >= f) f else nat
            kind == HuntRoute.ROPE -> HuntRoute.deckBlock(ri).let { db -> if (nat >= db) db - 1 else nat }
            roofed -> nat
            else -> f
        }
        // Haut: bis knapp unter die tiefste Nachbar-Oberkante, am Massiv/Horn 12 dick, sonst 8.
        var lowest = surface
        var slope = 0
        for (v in intArrayOf(FarPlan.top(x + 1, z), FarPlan.top(x - 1, z), FarPlan.top(x, z + 1), FarPlan.top(x, z - 1))) {
            if (v < lowest) lowest = v
            slope = max(slope, abs(surface - v))
        }
        val massive = FarPlan.massifTop(x, z) != Int.MIN_VALUE || FarPlan.spireTop(x, z) != Int.MIN_VALUE
        val skin = if (massive) 12 else 8
        // Entlang der Schnittebene (±14) ganz massiv bis zum Boden, damit die Kluft nach dem Schnitt Fels zeigt.
        val solidBand = abs(x + 0.5 - ArenaLayout.CUT_X) <= 14.0
        var bottom = if (solidBand) -1 else max(-1, min(surface - skin, lowest - 1))
        // Am Weg (Pfad, Tunnel und deren Wände): fest bis 3 unter die Wegfläche, damit Hangschnitte und Tunnelwände nie hohl sind.
        if (ri >= 0 && !HuntRoute.isBridge(ri)) bottom = max(-1, min(bottom, f - 3))
        val spire = FarPlan.spireTop(x, z).let { it != Int.MIN_VALUE && it >= FarPlan.massifTop(x, z) }
        for (y in bottom..surface) p.set(y, FarPlan.material(x, y, z, surface, slope, spire))

        if (onPath) when (kind) {
            HuntRoute.BRIDGE -> bridge(p, x, z, ri, lat, nat, f, -1)
            HuntRoute.ROPE -> rope(p, ri, lat, nat, -1)
            else -> path(p, x, z, ri, lat, nat, f, roofed)
        }
        if (cave != null && (roofed || !onPath)) carve(p, x, z, ri, lat, nat, f, cave, Int.MAX_VALUE)
        // Zweite Boden-Ebene: Höhle einer anderen Windung in derselben Spalte (Nebengang unter dem Hang darüber).
        val sec = HuntRoute.groundSecond(x, z)
        if (sec >= 0) {
            val si = HuntRoute.hitIndex(sec)
            val sc = if (HuntRoute.kind(si) == HuntRoute.TUNNEL) HuntRoute.caveOf(si) else null
            if (sc != null) {
                val sf = floor(HuntRoute.y(si)).toInt()
                val cap = if (ri >= 0 && f > sf) f - 2 else Int.MAX_VALUE
                carve(p, x, z, si, HuntRoute.hitLateral(sec), nat, sf, sc, cap)
            }
        }
        // Zweite Ebene: eine Brücke hoch über diesem Weg. Unter ihr bleibt der Weg frei (6 Blöcke über seiner Fläche).
        val oh = HuntRoute.overhead(x, z)
        if (oh >= 0 && HuntRoute.hitLateral(oh) <= HuntRoute.HALF_W) {
            val bi = HuntRoute.hitIndex(oh)
            val clear = max(surface, if (onPath) f + 1 else surface) + 6
            for (y in surface + 1 until clear) if (p.get(y) == ColumnPlan.KEEP) p.set(y, FarPlan.AIR)
            val deck = if (HuntRoute.kind(bi) == HuntRoute.ROPE) {
                rope(p, bi, HuntRoute.hitLateral(oh), surface, clear)
                HuntRoute.deckBlock(bi)
            } else {
                floor(HuntRoute.y(bi)).toInt().also { bridge(p, x, z, bi, HuntRoute.hitLateral(oh), surface, it, clear) }
            }
            // Ragt Gelände über das Deck (Hang neben dem Weg darunter), wird es auf Deckhöhe abgetragen.
            for (y in deck + 5..surface) p.set(y, FarPlan.AIR)
        }
        // Gipfelschulter: Runenboden, Krone aus Eisspitzen, Mauer mit dem Tor der Klinge.
        if (abs(x - ArenaLayout.SPIRE.x) <= FarPlan.SHOULDER_R + 15 && abs(z - ArenaLayout.SPIRE.z) <= FarPlan.SHOULDER_R + 15) {
            if (surface == FarPlan.SHOULDER_Y.toInt() && !onPath) FarPlan.summitFloor(x, z).let { if (it >= 0) p.set(surface, it) }
            for (y in FarPlan.SHOULDER_Y.toInt() + 1..FarPlan.SHOULDER_Y.toInt() + 36) {
                val id = FarPlan.crown(x, y, z).let { if (it >= 0) it else FarPlan.summitWall(x, y, z) }
                if (id >= 0) p.set(y, id)
            }
        }
        // Drachenhorst: Boden mit Runen, Gebeine, Kristallsäulen, Felspfeiler.
        if (abs(x - ArenaLayout.AERIE.x) <= ArenaLayout.AERIE_RIM + 2 && abs(z - ArenaLayout.AERIE.z) <= ArenaLayout.AERIE_RIM + 2) {
            FarPlan.aerieFloor(x, z).let { if (it >= 0) p.set(-1, it) }
            for (y in 0..64) {
                val id = FarPlan.aerie(x, y, z)
                if (id >= 0) p.set(y, id)
            }
        }
        // Feld von Abschnitt III: zugefrorener See, Kristalle, Pfeilerkreis, große Klinge, Zuschauerhügel.
        if (abs(x - ArenaLayout.VAULT.x) <= ArenaLayout.VAULT_R + 12 && abs(z - ArenaLayout.VAULT.z) <= ArenaLayout.VAULT_R + 12) {
            val floorId = FarPlan.fieldFloor(x, z)
            if (floorId >= 0 && !onPath) p.set(-1, floorId)
            for (y in ArenaLayout.VAULT.y - 1..ArenaLayout.VAULT.y + 40) {
                val id = FarPlan.balcony(x, y, z)
                if (id >= 0) p.set(y, id)
            }
        }
        // Rückkehrtore (seit 1.10.0): Rahmen aus Raureif-Torstein, innen Luft (das Leuchten setzt erst der Sieg).
        val plinth = FarPlan.gatePlinth(x, z)
        if (plinth) {
            for (y in 0..max(surface, 0) + 2) p.set(y, FarPlan.AIR)
            p.set(-1, if (abs(x - 310) <= 3 && abs(z - 75) <= 1) FarPlan.CHISELED else FarPlan.STONEBRICK)
        }
        // Offenes Hochland: Eisspitzen, Frostwälder, Findlinge, Seen, Ruinen.
        if (surface <= 6 && !onPath && !plinth) {
            if (surface <= -1) FarPlan.iceSpike(x, z)?.let { sp ->
                for (y in 0..sp[0]) p.set(y, if (sp[1] == 1 && y < sp[0] - 1) FarPlan.BLUE else FarPlan.PACKED)
            }
            Highland.write(x, z, surface, p)
        }
        // Dauerhafte Felsbögen und Eisgrate entlang Solvanes Ritt-Route (QTE-Hindernisse als fester Teil der Dimension).
        RideLandmarks.write(x, z, surface, p)
    }

    /** Wegfläche (Pfad und Tunnel): Hangschnitt oder Damm, Schneeschichten als Rampe, Pfosten, Tunneldecke. */
    private fun path(p: ColumnPlan, x: Int, z: Int, ri: Int, lat: Double, nat: Int, f: Int, roofed: Boolean) {
        // Damm unter der Wegfläche: hoch aufgeschüttet als Mauerwerk, sonst Fels.
        for (y in max(-1, nat + 1) until f) p.set(y, if (f - nat > 4) (if (lat > HuntRoute.HALF_W - 1.0) FarPlan.STONEBRICK else FarPlan.STONE) else FarPlan.material(x, y, z, f, 2, false))
        p.set(f, if (roofed) FarPlan.ANDESITE else FarPlan.SNOW)
        val post = HuntRoute.postAt(x, z) >= 0 && !roofed
        val layers = HuntRoute.snowLayers(HuntRoute.y(ri))
        if (post) {
            p.set(f + 1, FarPlan.WALL)
            p.set(f + 2, FarPlan.WALL)
            p.set(f + 3, FarPlan.SOUL_LANTERN)
        } else {
            p.set(f + 1, FarPlan.SNOW_LAYER + layers - 1)
        }
        if (roofed) {
            // Tunnel: 5 hoch frei (ausdrücklich Luft, auch gegen Reste), Decke 3 dick, darüber die Haut des Berges.
            for (y in f + 2..f + 5) p.set(y, FarPlan.AIR)
            if (HuntRoute.lampAt(x, z) >= 0) p.set(f + 5, FarPlan.SOUL_HANG)
            for (y in f + 6..f + 8) p.set(y, FarPlan.material(x, y, z, nat, 6, false))
        } else {
            // Offener Weg: Kopffreiheit ausdrücklich frei (Reste älterer Fassungen, Brückenteile darüber).
            for (y in (if (post) f + 4 else f + 2)..f + 4) p.set(y, FarPlan.AIR)
        }
    }

    /**
     * Steinbrücke: Deck mit weißer Mittellinie, Brüstung mit Laternen, Bögen und Pfeiler bis zum Talgrund. [floorClear]
     * ≥ 0: zweite Ebene, unterhalb davon nichts setzen (dort verläuft ein Weg).
     */
    private fun bridge(p: ColumnPlan, x: Int, z: Int, ri: Int, lat: Double, nat: Int, f: Int, floorClear: Int) {
        val s = HuntRoute.arc(ri) - HuntRoute.spanStart(ri)
        val len = HuntRoute.spanEnd(ri) - HuntRoute.spanStart(ri)
        val deckId = if (lat < 0.7) FarPlan.CALCITE else if (lat > HuntRoute.HALF_W - 1.0) FarPlan.DEEPBRICK else FarPlan.STONEBRICK
        if (nat >= f) {
            // Brückenkopf im Hang: Deck auf dem Fels
            p.set(f, deckId)
        } else {
            val span = HuntRoute.SPAN
            val q = (((s - 1.5) % span) + span) % span / span
            val qq = 2 * q - 1
            val depth = (1.0 + (span * 0.5 - 1.5) * (1.0 - sqrt(max(0.0, 1.0 - qq * qq)))).toInt()
            val pillar = (s % span) < 3.0 && s > 6.0 && len - s > 6.0
            val archBottom = max(nat + 1, f - depth)
            for (y in max(nat + 1, floorClear) until f) {
                p.set(y, when {
                    pillar -> if (y - nat <= 2) FarPlan.DEEPBRICK else if (Math.floorMod(y, 9) == 0) FarPlan.CHISELED else FarPlan.STONEBRICK
                    y >= archBottom -> FarPlan.STONEBRICK
                    else -> FarPlan.AIR
                })
            }
            p.set(f, deckId)
        }
        val edge = lat > HuntRoute.HALF_W - 1.0
        when {
            HuntRoute.postAt(x, z) >= 0 && edge -> {
                p.set(f + 1, FarPlan.WALL)
                p.set(f + 2, FarPlan.SOUL_LANTERN)
            }
            edge -> p.set(f + 1, FarPlan.BRICKWALL)
            else -> p.set(f + 1, FarPlan.AIR)
        }
        for (y in f + 2..f + 4) if (p.get(y) != FarPlan.SOUL_LANTERN) p.set(y, FarPlan.AIR)
    }

    /**
     * Hängebrücke (seit 1.10.0): Deck aus unsichtbaren Schichtblöcken (die Planken, Seile und das Schwingen zeichnet der
     * Client), Geländer aus Barrieren (zwei hoch, niemand fällt seitlich hinunter), darunter Luft bis zum Talgrund. An
     * den Köpfen stehen Ankerpfosten aus Fichtenstämmen.
     */
    private fun rope(p: ColumnPlan, ri: Int, lat: Double, nat: Int, floorClear: Int) {
        val db = HuntRoute.deckBlock(ri)
        for (y in maxOf(nat + 1, floorClear, -1) until db) p.set(y, FarPlan.AIR)
        val span = HuntRoute.spanOf(ri)
        val u = if (span != null) minOf(HuntRoute.arc(ri) - span.a0, span.a1 - HuntRoute.arc(ri)) else 99.0
        // Brückenkopf (2 Blöcke): Steinplatte über die ganze Wegbreite, Ankerpfosten außen neben dem Deck.
        if (u < 2.0 && HuntRoute.deckLayers(ri) >= 7 && lat > HuntRoute.ROPE_HALF) {
            p.set(db, FarPlan.STONEBRICK)
            val post = lat > HuntRoute.ROPE_RAIL && lat <= HuntRoute.ROPE_RAIL + 1.0 && u < 1.2
            p.set(db + 1, if (post) FarPlan.LOG else FarPlan.AIR)
            p.set(db + 2, if (post) FarPlan.LOG else FarPlan.AIR)
            p.set(db + 3, if (post) FarPlan.FENCE else FarPlan.AIR)
            p.set(db + 4, FarPlan.AIR)
            return
        }
        val head = u < 2.0
        when {
            lat <= HuntRoute.ROPE_HALF -> {
                p.set(db, FarPlan.DECK + HuntRoute.deckLayers(ri) - 1)
                for (y in db + 1..db + 4) p.set(y, FarPlan.AIR)
            }
            lat <= HuntRoute.ROPE_RAIL -> {
                if (head) {
                    p.set(db, FarPlan.AIR)
                    for (y in db + 1..db + 3) p.set(y, FarPlan.AIR)
                } else {
                    p.set(db, FarPlan.AIR)
                    p.set(db + 1, FarPlan.BARRIER)
                    p.set(db + 2, FarPlan.BARRIER)
                    p.set(db + 3, FarPlan.AIR)
                }
                for (y in db + 4..db + 5) p.set(y, FarPlan.AIR)
            }
            else -> for (y in db..db + 4) p.set(y, FarPlan.AIR)
        }
    }

    // --- Höhlen ---------------------------------------------------------------------------------------------------

    private fun wall(t: HuntRoute.Theme, x: Int, y: Int, z: Int): Int {
        val n = FarPlan.vnoise(x + y * 3, z - y * 2, 4, 1401)
        val r = FarPlan.h32(x, y, z, 1403)
        return when (t) {
            HuntRoute.Theme.DARK -> if (abs(n - 0.5) < 0.05) FarPlan.BLUE else if (r < 0.55) FarPlan.DEEP else FarPlan.TUFF
            HuntRoute.Theme.CRAWL -> if (n > 0.7) FarPlan.PACKED else if (r < 0.5) FarPlan.STONE else FarPlan.ANDESITE
            HuntRoute.Theme.CRYSTAL -> if (n > 0.62) FarPlan.BLUE else if (n < 0.3) FarPlan.CALCITE else FarPlan.PACKED
            HuntRoute.Theme.FALL -> if (n > 0.55) FarPlan.PACKED else if (r < 0.2) FarPlan.BLUE else FarPlan.STONE
            HuntRoute.Theme.LAKE -> if (n > 0.66) FarPlan.PACKED else if (r < 0.15) FarPlan.CALCITE else FarPlan.STONE
            HuntRoute.Theme.WIND -> if (Math.floorMod(y + (x + z) / 5, 5) == 0) FarPlan.PACKED else FarPlan.STONE
        }
    }

    /** Ist (x, z) nah an einem Fenster/Riss der Höhle (Bogenlänge u ab Eingang)? */
    private fun window(c: HuntRoute.Cave, u: Double): Boolean {
        for (w in c.windows) if (abs(u - w) < 1.05) return true
        return false
    }

    /**
     * Höhle über einem Wegpunkt (Hauptgang, Wände, Nebengang auf der Bergseite, Fenster und Eiswand auf der Talseite,
     * Kuppel mit See). Schreibt von der Wegfläche bis drei Blöcke über die Decke; darüber bleibt die Haube.
     */
    private fun carve(plan: ColumnPlan, x: Int, z: Int, i: Int, lat: Double, nat: Int, f: Int, c: HuntRoute.Cave, cap: Int) {
        // Nie über [cap] schreiben (dort liegt der Weg einer höheren Windung).
        val p = object {
            fun set(y: Int, id: Int) { if (y <= cap) plan.set(y, id) }
        }
        val t = c.theme
        val u = c.u(i)
        val out = HuntRoute.outward(i, x, z)
        val ceil = f + t.height + 1
        val top = max(nat, ceil + 2)
        val layers = HuntRoute.snowLayers(HuntRoute.y(i))
        val joint = u <= c.joint || u >= c.length - c.joint
        fun solid(from: Int, to: Int) { for (y in from..to) p.set(y, wall(t, x, y, z)) }
        fun lowPassage() {
            p.set(f, wall(t, x, f, z))
            p.set(f + 1, FarPlan.SNOW_LAYER + layers - 1)
            p.set(f + 2, FarPlan.AIR)
            p.set(f + 3, FarPlan.AIR)
            solid(f + 4, top)
        }
        val h = FarPlan.h32(x, f, z, 1409)
        when {
            lat <= t.lane -> {
                // Hauptgang
                p.set(f, if (t == HuntRoute.Theme.DARK) FarPlan.DEEP else FarPlan.STONE)
                p.set(f + 1, FarPlan.SNOW_LAYER + layers - 1)
                for (y in f + 2 until ceil) p.set(y, FarPlan.AIR)
                solid(ceil, top)
                when (t) {
                    HuntRoute.Theme.DARK -> if (h < 0.06) p.set(ceil - 1, FarPlan.LIGHT)
                    HuntRoute.Theme.CRAWL -> if (h < 0.2) p.set(ceil, FarPlan.ICE)
                    HuntRoute.Theme.CRYSTAL -> {
                        if (lat > 1.8 && h < 0.16) p.set(ceil - 1, FarPlan.END_ROD_DOWN)
                        if (h in 0.5..0.56) p.set(ceil - 2, FarPlan.LIGHT + 2)
                        if (lat > 3.0 && h > 0.8) for (y in f + 1..f + 1 + ((h - 0.8) * 15).toInt()) p.set(y, FarPlan.BLUE)
                    }
                    HuntRoute.Theme.FALL -> {
                        if (out && lat > 3.0 && u in 9.0..16.0) for (y in f + 1 until ceil) p.set(y, if (FarPlan.h32(x, y, z, 1411) < 0.5) FarPlan.BLUE else FarPlan.PACKED)
                        if (h < 0.05) p.set(ceil - 1, FarPlan.LIGHT + 1)
                    }
                    HuntRoute.Theme.LAKE -> if (h < 0.05) p.set(ceil - 1, FarPlan.LIGHT + 1)
                    HuntRoute.Theme.WIND -> Unit
                }
                if (t.lamps && HuntRoute.lampAt(x, z) >= 0) p.set(ceil - 1, FarPlan.SOUL_HANG)
            }
            !out -> when {
                // Bergseite: Trennwand, Nebengang (Umweg um den Geröllpfropfen), dahinter Fels.
                lat <= 4.5 -> if (joint) lowPassage() else solid(f, top)
                lat <= 6.5 -> lowPassage()
                else -> solid(f, top)
            }
            else -> {
                // Talseite
                val lakeHere = c.lakeFrom >= 0 && u in c.lakeFrom..c.lakeTo
                val iceHere = c.iceFrom >= 0 && u in c.iceFrom..c.iceTo
                when {
                    lakeHere -> {
                        p.set(f, if (h < 0.3) FarPlan.BLUE else FarPlan.PACKED)
                        val dome = ceil - 1 - (((lat - t.lane) / (HuntRoute.ENVELOPE - t.lane)).let { it * it } * 4.0).toInt()
                        val pillar = FarPlan.h32(x, 0, z, 1413) < 0.035 && lat > t.lane + 1.0
                        for (y in f + 1..dome) p.set(y, if (pillar) FarPlan.PACKED else FarPlan.AIR)
                        if (!pillar && h in 0.4..0.43) p.set(f + 1, FarPlan.LIGHT + 1)
                        solid(dome + 1, top)
                    }
                    iceHere && lat <= 4.5 -> {
                        p.set(f, FarPlan.PACKED)
                        for (y in f + 1 until ceil) p.set(y, FarPlan.ICE)
                        solid(ceil, ceil + 2)
                    }
                    iceHere -> Unit
                    lat <= 5.5 || nat >= ceil -> {
                        solid(f, top)
                        if (t == HuntRoute.Theme.CRYSTAL && h < 0.08 && lat <= 4.6) p.set(f + 2 + (h * 50).toInt() % (t.height - 1), FarPlan.SEA_LANTERN)
                        if (t == HuntRoute.Theme.FALL && u in 9.0..16.0) for (y in f + 1 until ceil) p.set(y, if (FarPlan.h32(x, y, z, 1411) < 0.5) FarPlan.BLUE else FarPlan.PACKED)
                    }
                }
                // Fenster/Risse nach draußen (Solvanes Auge, ihr Atem). In der Kriechpassage nur ein Schlitz.
                if (!lakeHere && !iceHere && window(c, u)) {
                    if (t == HuntRoute.Theme.CRAWL) {
                        p.set(f + 3, FarPlan.AIR)
                    } else {
                        p.set(f + 3, if (lat <= t.lane + 1.2) FarPlan.BARRIER else FarPlan.AIR)
                        p.set(f + 4, FarPlan.AIR)
                    }
                }
            }
        }
    }
}

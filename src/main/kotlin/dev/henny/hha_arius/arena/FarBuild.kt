package dev.henny.hha_arius.arena

import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.Heightmap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bauplan einer Spalte der Fernlandschaft (relativ, y −1 .. [MAX_Y]): je Höhe eine Block-Id aus [FarPlan], [KEEP]
 * (nicht anfassen) oder [FarPlan.AIR].
 */
internal class ColumnPlan {
    val ids = IntArray(MAX_Y + 2)
    var hi = -2
        private set

    fun reset() {
        java.util.Arrays.fill(ids, KEEP)
        hi = -2
    }

    fun set(y: Int, id: Int) {
        if (y < -1 || y > MAX_Y) return
        ids[y + 1] = id
        if (y > hi) hi = y
    }

    fun get(y: Int): Int = if (y < -1 || y > MAX_Y) KEEP else ids[y + 1]

    companion object {
        const val KEEP = -1
        val MAX_Y = ArenaLayout.SPIRE_TOP + 40
    }
}

/**
 * Baut die Fernlandschaft einer Instanz Chunk für Chunk (Nadel und Sichtachse zuerst, dann Horst, Feld, Berge der Jagd
 * und das übrige Hochland), mit Budget je Tick, ohne Nachbar-Updates. Läuft nach dem Kernbau im Hintergrund weiter,
 * auch während des Tempelkampfs. Unter dem Kern (ArenaPlan-Bereich) wird nur unterhalb des tiefsten Planblocks gebaut.
 *
 * Seit 1.8.1 bekommt jede Spalte einen vollständigen Plan: Was ältere Fassungen über der neuen Oberkante hinterlassen
 * haben (alter Spiralweg, alte Nebengipfel, der alte Horst, das alte Feld), wird bis zur Höhenkarte abgeräumt; so ist
 * kein Tunnel und kein Weg mehr von alten Blöcken verstopft.
 */
class FarBuild(private val world: ServerWorld, private val origin: BlockPos) {

    private val chunks: List<ChunkPos>
    private var chunkIndex = 0
    private var columnInChunk = 0
    private val pos = BlockPos.Mutable()
    private val core = ByteArray(ArenaPlan.NY)
    private val plan = ColumnPlan()

    var isDone = false
        private set

    val progress: Float get() = if (isDone) 1f else chunkIndex.toFloat() / chunks.size.coerceAtLeast(1)

    init {
        val list = ArrayList<ChunkPos>()
        val minCx = (origin.x + FarPlan.X_MIN) shr 4
        val maxCx = (origin.x + FarPlan.X_MAX) shr 4
        val minCz = (origin.z + FarPlan.Z_MIN) shr 4
        val maxCz = (origin.z + FarPlan.Z_MAX) shr 4
        for (cx in minCx..maxCx) for (cz in minCz..maxCz) list.add(ChunkPos(cx, cz))
        // Reihenfolge nach Bedarf im Kampf: Nadel/Sichtachse (Auftritt), Horst (Abschnitt II), Feld (III), Berge (Jagd).
        fun near(c: ChunkPos, x: Int, z: Int, r: Int) = abs(c.startX + 8 - (origin.x + x)) <= r && abs(c.startZ + 8 - (origin.z + z)) <= r
        fun rank(c: ChunkPos): Double {
            val dx = (c.startX + 8 - origin.x).toDouble()
            val dz = (c.startZ + 8 - (origin.z + 90)).toDouble()
            val axis = abs(dx) * 1.6 + abs(dz) * 0.6
            return when {
                abs(dx) <= ArenaLayout.FAR_X && c.startZ + 8 - origin.z in -150..ArenaLayout.FAR_Z_MAX -> axis
                near(c, ArenaLayout.AERIE.x, ArenaLayout.AERIE.z, ArenaLayout.AERIE_RIM + 16) -> 100_000.0 + axis
                near(c, ArenaLayout.VAULT.x, ArenaLayout.VAULT.z, ArenaLayout.VAULT_R + 24) -> 200_000.0 + axis
                HuntRoute.MOUNTS.any { near(c, it.x.toInt(), it.z.toInt(), it.r.toInt() + 8) } -> 300_000.0 + axis
                else -> 400_000.0 + axis
            }
        }
        chunks = list.sortedBy { rank(it) }
    }

    /** Setzt höchstens [budget] Blöcke. true = fertig. */
    fun step(budget: Int): Boolean {
        if (isDone) return true
        var sets = 0
        var columns = 0
        while (sets < budget && columns < 2048) {
            if (chunkIndex >= chunks.size) {
                isDone = true
                return true
            }
            val c = chunks[chunkIndex]
            val lx = columnInChunk and 15
            val lz = columnInChunk shr 4
            val ax = c.startX + lx
            val az = c.startZ + lz
            val x = ax - origin.x
            val z = az - origin.z
            if (x in FarPlan.X_MIN..FarPlan.X_MAX && z in FarPlan.Z_MIN..FarPlan.Z_MAX) {
                sets += if (FarPlan.inCore(x, z)) coreColumn(x, z, ax, az) else column(x, z, ax, az)
            }
            columns++
            columnInChunk++
            if (columnInChunk >= 256) {
                columnInChunk = 0
                chunkIndex++
            }
        }
        return false
    }

    /** Unter dem Kern: nur Eisspitzen (Biom-Merkmal) entfernen, wo der Bauplan Luft vorsieht. */
    private fun coreColumn(x: Int, z: Int, ax: Int, az: Int): Int {
        val chunk = world.getChunk(ax shr 4, az shr 4)
        ArenaPlan.column(x, z, core)
        var sets = 0
        for (i in core.indices) {
            if (core[i] != ArenaPlan.AIR) continue
            val y = ArenaPlan.Y_MIN + i
            if (y < 0) continue
            pos.set(ax, origin.y + y, az)
            val st = chunk.getBlockState(pos)
            if (st.isOf(Blocks.PACKED_ICE) || st.isOf(Blocks.ICE)) {
                world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                sets++
            }
        }
        // Über dem Kern (Bauvolumen bis Y_MAX) liegt nichts von der Fernlandschaft: Reste älterer Fassungen abräumen.
        val hm = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, ax and 15, az and 15) - origin.y
        for (y in ArenaPlan.Y_MAX + 1..hm) {
            pos.set(ax, origin.y + y, az)
            if (!chunk.getBlockState(pos).isAir) {
                world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                sets++
            }
        }
        return sets
    }

    private fun column(x: Int, z: Int, ax: Int, az: Int): Int {
        val p = plan
        p.reset()
        val nat = FarPlan.terrain(x, z)
        val hit = HuntRoute.hit(x, z)
        val ri = if (hit >= 0) HuntRoute.hitIndex(hit) else -1
        val lat = if (hit >= 0) HuntRoute.hitLateral(hit) else 99.0
        val onPath = ri >= 0 && lat <= HuntRoute.HALF_W
        val kind = if (ri >= 0) HuntRoute.kind(ri) else -1
        val py = if (ri >= 0) HuntRoute.y(ri) else 0.0
        val f = floor(py).toInt()
        val roofed = kind == HuntRoute.TUNNEL && nat > f + 5
        // Oberkante der Haut
        val surface = when {
            !onPath -> nat
            kind == HuntRoute.BRIDGE -> if (nat >= f) f else nat
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
        if (ri >= 0 && kind != HuntRoute.BRIDGE) bottom = max(-1, min(bottom, f - 3))
        val spire = FarPlan.spireTop(x, z).let { it != Int.MIN_VALUE && it >= FarPlan.massifTop(x, z) }
        for (y in bottom..surface) p.set(y, FarPlan.material(x, y, z, surface, slope, spire))

        if (onPath) when (kind) {
            HuntRoute.BRIDGE -> bridge(p, x, z, ri, lat, nat, f)
            else -> path(p, x, z, ri, lat, nat, f, roofed)
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
        // Offenes Hochland: Eisspitzen, Frostwälder, Findlinge, Seen, Ruinen.
        if (surface <= 6 && !onPath) {
            if (surface <= -1) FarPlan.iceSpike(x, z)?.let { sp ->
                for (y in 0..sp[0]) p.set(y, if (sp[1] == 1 && y < sp[0] - 1) FarPlan.BLUE else FarPlan.PACKED)
            }
            Highland.write(x, z, surface, p)
        }
        return apply(ax, az)
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
        }
    }

    /** Brücke: Steindeck mit weißer Mittellinie, Brüstung mit Laternen, Bögen und Pfeiler bis zum Talgrund. */
    private fun bridge(p: ColumnPlan, x: Int, z: Int, ri: Int, lat: Double, nat: Int, f: Int) {
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
            for (y in nat + 1 until f) {
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
    }

    /** Plan in die Welt schreiben (nur Unterschiede); über dem Plan bis zur Höhenkarte alles abräumen. */
    private fun apply(ax: Int, az: Int): Int {
        val chunk = world.getChunk(ax shr 4, az shr 4)
        val p = plan
        var sets = 0
        for (y in -1..p.hi) {
            val id = p.get(y)
            if (id == ColumnPlan.KEEP) continue
            val state = FarPlan.state(id)
            pos.set(ax, origin.y + y, az)
            if (chunk.getBlockState(pos) != state) {
                world.setBlockState(pos, state, ArenaBuilder.FLAGS)
                sets++
            }
        }
        val hm = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, ax and 15, az and 15) - origin.y
        for (y in max(p.hi + 1, 0)..min(hm, ColumnPlan.MAX_Y + 40)) {
            pos.set(ax, origin.y + y, az)
            if (!chunk.getBlockState(pos).isAir) {
                world.setBlockState(pos, Blocks.AIR.defaultState, ArenaBuilder.FLAGS)
                sets++
            }
        }
        return sets
    }
}

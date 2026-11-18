package dev.henny.hha_arius.gear.art

import net.minecraft.block.Block
import net.minecraft.util.math.BlockPos
import java.util.UUID

/**
 * Die Yang-Brücke als steinerne Bogenbrücke (seit 1.4.2, nach dem Vorbild einer mittelalterlichen Bogenbrücke, Crix):
 * Fahrbahn aus weißen Ziegeln über die ganze Breite, Brüstung aus Mauerstücken an beiden Rändern mit Lichtpfosten,
 * darunter Bögen zwischen Pfeilern (alle [SPAN] Blöcke ein 2 Blöcke breiter Pfeiler bis zum Boden, die Fahrbahn wird
 * zu den Pfeilern hin dicker). Länge ([MIN_LENGTH]..[MAX_LENGTH]) und Breite ([MIN_WIDTH]..[MAX_WIDTH], ungerade)
 * stellt der Spieler beim Zielen mit dem Mausrad ein (Schleichen: Breite).
 */
object BridgeShape {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 50
    const val MIN_WIDTH = 3
    const val MAX_WIDTH = 9
    const val DEFAULT_LENGTH = 16
    const val DEFAULT_WIDTH = 5
    private const val SPAN = 8
    private const val PILLAR_DEPTH = 24

    private val dims = HashMap<UUID, IntArray>()

    fun length(id: UUID): Int = dims[id]?.get(0) ?: DEFAULT_LENGTH
    fun width(id: UUID): Int = dims[id]?.get(1) ?: DEFAULT_WIDTH

    fun set(id: UUID, length: Int, width: Int) {
        val w = width.coerceIn(MIN_WIDTH, MAX_WIDTH).let { if (it % 2 == 0) it + 1 else it }.coerceAtMost(MAX_WIDTH)
        dims[id] = intArrayOf(length.coerceIn(MIN_LENGTH, MAX_LENGTH), w)
    }

    /** Für das Netz: Länge und Breite in einem Wert. */
    fun pack(length: Int, width: Int): Int = length * 16 + width
    fun unpack(id: UUID, v: Int) = set(id, v / 16, v % 16)

    fun forget(id: UUID) {
        dims.remove(id)
    }

    /** Teile der Brücke je Bauweise; [order] = Verzögerung (Wachsen vom Spieler weg). */
    class Plan(val parts: List<Triple<BlockPos, Block, Int>>)

    /**
     * [path] = Mittellinie der Fahrbahn (aus [Placement]), [side] = Querrichtung (Einheitsschritt), [width] ungerade.
     * [free] prüft, ob an einer Stelle gebaut werden darf (Luft, Rechte).
     */
    fun plan(path: List<BlockPos>, side: BlockPos, width: Int, free: (BlockPos) -> Boolean): Plan {
        val out = ArrayList<Triple<BlockPos, Block, Int>>()
        val seen = HashSet<BlockPos>()
        val half = width / 2
        fun add(p: BlockPos, b: Block, order: Int) {
            if (seen.add(p) && free(p)) out += Triple(p, b, order)
        }
        val n = path.size
        // Pfeiler: alle SPAN Blöcke (nicht direkt am Anfang), dazu einer am Ende, wenn die Brücke lang genug ist.
        val pillars = HashSet<Int>()
        var i = SPAN
        while (i < n - 2) {
            pillars += i
            pillars += i + 1
            i += SPAN
        }
        for ((k, c) in path.withIndex()) {
            val order = k / 2
            for (w in -half..half) {
                val p = c.add(side.x * w, 0, side.z * w)
                add(p, Constructs.YANG_BRICKS, order)
                // Brüstung an den Rändern, alle 6 Blöcke ein Lichtpfosten
                if (w == -half || w == half) {
                    add(p.up(), Constructs.YANG_WALL, order + 1)
                    if (k % 6 == 3) add(p.up(2), Constructs.YANG, order + 2)
                }
            }
            // Bögen: zu den Pfeilern hin wird die Fahrbahn dicker (Bogenzwickel), in Spannweitenmitte nur eine Lage.
            val d = pillars.minOfOrNull { kotlin.math.abs(it - k) } ?: Int.MAX_VALUE
            if (d != Int.MAX_VALUE) {
                val t = (1.0 - d / (SPAN / 2.0)).coerceIn(0.0, 1.0)
                val hang = (t * t * 4.0).toInt()
                for (h in 1..hang) for (w in -half..half) add(c.add(side.x * w, -h, side.z * w), Constructs.YANG_BRICKS, order + h)
            }
            if (k in pillars) {
                for (w in -half..half) {
                    var y = 1
                    while (y <= PILLAR_DEPTH) {
                        val p = c.add(side.x * w, -y, side.z * w)
                        if (!free(p) && p !in seen) break
                        add(p, Constructs.YANG_BRICKS, order + 2 + y / 2)
                        y++
                    }
                }
            }
        }
        return Plan(out)
    }
}

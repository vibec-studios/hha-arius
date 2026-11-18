package dev.henny.hha_arius.arena

import dev.henny.hha_arius.arena.ArenaPlan.NY
import dev.henny.hha_arius.arena.ArenaPlan.X_MAX
import dev.henny.hha_arius.arena.ArenaPlan.X_MIN
import dev.henny.hha_arius.arena.ArenaPlan.Y_MAX
import dev.henny.hha_arius.arena.ArenaPlan.Y_MIN
import dev.henny.hha_arius.arena.ArenaPlan.Z_MAX
import dev.henny.hha_arius.arena.ArenaPlan.Z_MIN
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.SnowBlock
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Baut eine Instanz des Heiligtums in Schritten (für den 5-s-Schneesturm des Siegels). Mit [clear] wird das ganze
 * Bauvolumen ([ArenaLayout.BOUND_XZ] usw.) dabei geleert: Jede Position bekommt ihren Planblock oder Luft. Ohne [clear]
 * werden nur die Planblöcke gesetzt. Positionen, die schon stimmen, kosten nichts vom Budget (nur ein Achtel für das
 * Nachsehen). Deterministisch, ohne Nachbar-Updates (Flags 2 | 16).
 */
class ArenaBuild(private val world: ServerWorld, private val origin: BlockPos, private val clear: Boolean) {

    private val nz = Z_MAX - Z_MIN + 1
    private val columns = (X_MAX - X_MIN + 1) * nz
    private val total = columns.toLong() * NY
    private val col = ByteArray(NY)
    private val pos = BlockPos.Mutable()
    private var colIndex = 0
    private var yIndex = 0
    private var haveColumn = false
    private var scanned = 0L

    /** Gesetzte Blöcke bisher (nur zur Anzeige/Diagnose). */
    var placed = 0L
        private set

    var isDone = false
        private set

    val progress: Float
        get() = if (isDone) 1f else (scanned.toDouble() / total).toFloat().coerceIn(0f, 1f)

    /** Setzt höchstens [budget] Blöcke. true = fertig. */
    fun step(budget: Int): Boolean {
        if (isDone) return true
        var sets = 0
        var checks = 0
        var iterations = 0
        val maxChecks = maxOf(64, budget) * 8
        val maxIterations = maxOf(64, budget) * 24
        while (sets < budget && checks < maxChecks && iterations++ < maxIterations) {
            if (!haveColumn) {
                if (colIndex >= columns) {
                    isDone = true
                    return true
                }
                ArenaPlan.column(X_MIN + colIndex / nz, Z_MIN + colIndex % nz, col)
                haveColumn = true
                yIndex = 0
            }
            val id = col[yIndex]
            if (id != ArenaPlan.AIR || clear) {
                val target = ArenaBuilder.state(id)
                pos.set(origin.x + X_MIN + colIndex / nz, origin.y + Y_MIN + yIndex, origin.z + Z_MIN + colIndex % nz)
                checks++
                if (world.getBlockState(pos) != target) {
                    world.setBlockState(pos, target, ArenaBuilder.FLAGS)
                    sets++
                    placed++
                }
            }
            scanned++
            yIndex++
            if (yIndex >= NY) {
                haveColumn = false
                colIndex++
            }
        }
        if (!haveColumn && colIndex >= columns) isDone = true
        return isDone
    }
}

/**
 * Arena-Bau und Zerstörungsdaten für den Kampf. Alle Ein- und Ausgaben sind **absolute** Positionen; die Geometrie
 * selbst steht relativ zum Instanz-Ursprung in [ArenaPlan] (Vorschau: `tools/assets/arena_preview.py`).
 */
object ArenaBuilder {

    /** Block.NOTIFY_LISTENERS | Block.FORCE_STATE: keine Nachbar-Updates, nichts fällt oder bricht nach. */
    const val FLAGS = Block.NOTIFY_LISTENERS or Block.FORCE_STATE

    private val STATES: Array<BlockState> by lazy {
        arrayOf(
            Blocks.AIR.defaultState,
            Blocks.PACKED_ICE.defaultState,
            Blocks.BLUE_ICE.defaultState,
            Blocks.SNOW_BLOCK.defaultState,
            Blocks.SNOW.defaultState.with(SnowBlock.LAYERS, 1),
            Blocks.SNOW.defaultState.with(SnowBlock.LAYERS, 2),
            Blocks.DEEPSLATE_TILES.defaultState,
            Blocks.POLISHED_DEEPSLATE.defaultState,
            Blocks.CHISELED_DEEPSLATE.defaultState,
            Blocks.DEEPSLATE_BRICKS.defaultState,
            Blocks.COBBLED_DEEPSLATE.defaultState,
            Blocks.DEEPSLATE.defaultState,
            Blocks.TUFF.defaultState,
            Blocks.CALCITE.defaultState,
            Blocks.QUARTZ_BRICKS.defaultState,
            Blocks.QUARTZ_PILLAR.defaultState,
            Blocks.CHISELED_QUARTZ_BLOCK.defaultState,
            Blocks.GOLD_BLOCK.defaultState,
            Blocks.SEA_LANTERN.defaultState,
            Blocks.LIGHT_BLUE_STAINED_GLASS.defaultState,
            Blocks.SMOOTH_QUARTZ.defaultState,
            Blocks.STONE.defaultState,
        )
    }

    internal fun state(id: Byte): BlockState = STATES[id.toInt()]

    /** Planblock an einer absoluten Position (Luft außerhalb des Plans). */
    fun planState(origin: BlockPos, pos: BlockPos): BlockState {
        val x = pos.x - origin.x
        val y = pos.y - origin.y
        val z = pos.z - origin.z
        if (x !in X_MIN..X_MAX || z !in Z_MIN..Z_MAX || y !in Y_MIN..Y_MAX) return Blocks.AIR.defaultState
        val col = ByteArray(NY)
        ArenaPlan.column(x, z, col)
        return state(col[y - Y_MIN])
    }

    private fun isSnowLayer(s: BlockState) = s.isOf(Blocks.SNOW)

    /**
     * Alle Nicht-Luft-Blöcke des Tempels mit x ≥ origin.x + 2 und y ≥ origin.y (der Boden bleibt), aus der aktuellen
     * Welt gelesen (gebrochene Säulen fehlen also). Plan: rund 3 000 Blöcke, sicher unter 6 000.
     */
    fun templeEastHalf(world: ServerWorld, origin: BlockPos): List<Pair<BlockPos, BlockState>> {
        val out = ArrayList<Pair<BlockPos, BlockState>>(3500)
        val tz = ArenaLayout.TEMPLE.z
        val p = BlockPos.Mutable()
        for (y in 0..Y_MAX) {
            for (x in 2..24) {
                for (z in tz - 24..tz + 24) {
                    val dz = z - tz
                    if (x * x + dz * dz > 23 * 23 || !ArenaPlan.isGround(x, z)) continue
                    p.set(origin.x + x, origin.y + y, origin.z + z)
                    val s = world.getBlockState(p)
                    if (!s.isAir) out.add(p.toImmutable() to s)
                }
            }
        }
        return out
    }

    /**
     * Trümmerhaufen nach dem Sturz der Osthälfte: ein Hügel östlich vor der Halle und einzelne Brocken auf dem
     * Osthallenboden (Mittelgang und Torweg bleiben frei). Nur dort, wo gerade Luft (oder eine Schneeschicht) ist und
     * etwas darunter trägt. Reihenfolge von unten nach oben.
     */
    fun cutRubble(world: ServerWorld, origin: BlockPos): Map<BlockPos, BlockState> {
        val out = LinkedHashMap<BlockPos, BlockState>(1400)
        val tz = ArenaLayout.TEMPLE.z
        val p = BlockPos.Mutable()
        val below = BlockPos.Mutable()
        for (y in 0..8) {
            for (x in 5..40) {
                for (z in tz - 22..tz + 22) {
                    if (y >= ArenaPlan.rubbleHeight(x, z)) continue
                    p.set(origin.x + x, origin.y + y, origin.z + z)
                    val cur = world.getBlockState(p)
                    if (!cur.isAir && !isSnowLayer(cur)) continue
                    below.set(p.x, p.y - 1, p.z)
                    val support = out.containsKey(below) || world.getBlockState(below).let { !it.isAir && !isSnowLayer(it) }
                    if (!support) continue
                    out[p.toImmutable()] = state(ArenaPlan.rubbleBlock(x, y, z))
                }
            }
        }
        return out
    }

    /** Blöcke der Südwand, die für die Kluft entlang der Schnittebene (x ≈ CUT_X) verschwinden. Von oben nach unten. */
    fun southCleft(world: ServerWorld, origin: BlockPos): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        val p = BlockPos.Mutable()
        for (y in Y_MAX downTo 0) {
            for (x in -3..5) {
                for (z in 38..Z_MAX) {
                    if (!ArenaPlan.inCleft(x, y, z)) continue
                    p.set(origin.x + x, origin.y + y, origin.z + z)
                    if (!world.getBlockState(p).isAir) out.add(p.toImmutable())
                }
            }
        }
        return out
    }

    /**
     * Blöcke einer Säule (Tempel: 3 × 3 × [ArenaLayout.PILLAR_H] ab Laufhöhe; Gewölbe: 3 × 3 vom Boden bis unter die
     * Kuppel), die gerade nicht Luft sind. [pillarCenter] ist der absolute Mittelpunkt auf Laufhöhe.
     */
    fun pillarBlocks(world: ServerWorld, origin: BlockPos, pillarCenter: BlockPos): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        val rel = pillarCenter.subtract(origin)
        val p = BlockPos.Mutable()
        // Seit 1.4.2 Steinklingen: Parierstange reicht ±4 quer, Knauf bis 14 über den Boden.
        val h = 4
        for (ox in -h..h) for (oz in -h..h) {
            val x = rel.x + ox
            val z = rel.z + oz
            val top = rel.y + 14
            for (y in rel.y..top) {
                p.set(origin.x + x, origin.y + y, origin.z + z)
                if (!world.getBlockState(p).isAir) out.add(p.toImmutable())
            }
        }
        return out
    }

    /**
     * Schnee auf der Lauffläche im Umkreis [radius] um [center] (absolut, Laufhöhe): Schneeschichten in den Höhen
     * center.y − 1 .. center.y + 1 und Schneeblöcke ab center.y (Schneeblöcke im Boden bleiben).
     */
    fun snowAround(world: ServerWorld, center: BlockPos, radius: Int): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        val p = BlockPos.Mutable()
        val r2 = radius * radius
        for (dx in -radius..radius) for (dz in -radius..radius) {
            if (dx * dx + dz * dz > r2) continue
            for (dy in -1..1) {
                p.set(center.x + dx, center.y + dy, center.z + dz)
                val s = world.getBlockState(p)
                if (s.isOf(Blocks.SNOW) || (dy >= 0 && s.isOf(Blocks.SNOW_BLOCK))) out.add(p.toImmutable())
            }
        }
        return out
    }
}

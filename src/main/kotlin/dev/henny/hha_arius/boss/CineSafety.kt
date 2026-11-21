package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.FarPlan
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.floor

/**
 * Verfolgerkamera (seit 1.10.0): [ticks] lang folgt die Kamera des Zuschauers [target] (−1 = sich selbst) von hinten
 * ([dist] Blöcke hinter der Flugrichtung, [height] darüber, [side] seitlich), weich gefedert und kollisionssicher.
 * ticks ≤ 0 beendet sie sofort.
 */
data class ChaseCamS2C(val target: Int, val ticks: Int, val dist: Float, val height: Float, val side: Float) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ChaseCamS2C>(HhaArius.id("chase_cam"))
        val CODEC: PacketCodec<RegistryByteBuf, ChaseCamS2C> = PacketCodec.of(
            { v, buf -> buf.writeVarInt(v.target + 1); buf.writeVarInt(v.ticks); buf.writeFloat(v.dist); buf.writeFloat(v.height); buf.writeFloat(v.side) },
            { buf -> ChaseCamS2C(buf.readVarInt() - 1, buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat()) },
        )
    }
}

/**
 * Kollisionssichere Szenenkameras (seit 1.10.0, Crix: „die Kamera ist halb im Riss“, „halb im Berg“). Jede Fahrt
 * ([CineScene.line], [CineScene.orbit]) wird vor dem Senden an neun Punkten geprüft: Steht die Kamera in festem
 * Block oder ist die Sicht von ihr zum Blickpunkt verdeckt (bis 3 Blöcke vor dem Ziel, das selbst an einer Felswand
 * liegen darf), probiert [pick] der Reihe nach näher heran, höher, beides – und nimmt die erste ganz freie Fahrt (sonst
 * die mit der besten Sicht). Ungeladene Chunks zählen über die Höhenformel der Fernlandschaft ([FarPlan.top]); es wird
 * dafür nichts nachgeladen. Auf dem Client fängt dev.henny.hha_arius.client.fx.CineClient den Rest ab.
 */
object CineSafety {

    fun init() {
        PayloadTypeRegistry.playS2C().register(ChaseCamS2C.ID, ChaseCamS2C.CODEC)
        MusicCue.register()
    }

    /** Verfolgerkamera für [players] (jeder folgt sich selbst, wenn [target] null ist). */
    fun chase(players: Collection<ServerPlayerEntity>, ticks: Int, dist: Double, height: Double, side: Double = 0.0, target: net.minecraft.entity.Entity? = null) {
        val payload = ChaseCamS2C(target?.id ?: -1, ticks, dist.toFloat(), height.toFloat(), side.toFloat())
        for (p in players) if (!p.isDisconnected && ServerPlayNetworking.canSend(p, ChaseCamS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    /** Ist [p] fest (Block mit Kollisionsform bzw. unter der Fernlandschaft, wenn der Chunk nicht geladen ist)? */
    fun solid(world: ServerWorld, origin: BlockPos, p: Vec3d): Boolean {
        val bx = floor(p.x).toInt()
        val by = floor(p.y).toInt()
        val bz = floor(p.z).toInt()
        if (world.chunkManager.isChunkLoaded(bx shr 4, bz shr 4)) {
            val pos = BlockPos(bx, by, bz)
            val st = world.getBlockState(pos)
            return !st.isAir && !st.getCollisionShape(world, pos).isEmpty
        }
        val rx = bx - origin.x
        val rz = bz - origin.z
        if (rx !in FarPlan.X_MIN..FarPlan.X_MAX || rz !in FarPlan.Z_MIN..FarPlan.Z_MAX) return false
        val top = runCatching { FarPlan.top(rx, rz) }.getOrDefault(-1)
        return top >= 0 && by <= origin.y + top
    }

    /**
     * Sichtgüte einer Kameraposition: 0 = in festem Block, sonst der Anteil des Wegs von der Kamera zum Blickpunkt
     * (ohne die letzten 3 Blöcke), der frei ist (1 = ganz frei). Prüft in halben Blöcken, dazu 0,6 Blöcke Rand um die
     * Kamera (Nahebene).
     */
    fun clearance(world: ServerWorld, origin: BlockPos, cam: Vec3d, focus: Vec3d): Double {
        for (o in MARGIN) if (solid(world, origin, cam.add(o))) return 0.0
        val d = focus.subtract(cam)
        val len = d.length() - 3.0
        if (len <= 0.0) return 1.0
        val dir = d.normalize()
        val total = len + 3.0
        var s = 0.5
        while (s < len) {
            if (solid(world, origin, cam.add(dir.multiply(s)))) {
                // Treffer kurz vor dem Ziel (Ziel liegt an/in einer Wand, z. B. die Kluft während sie entsteht): man
                // sieht die Oberfläche am Ziel – das zählt als frei.
                return if (total - s < maxOf(8.0, total * 0.15)) 1.0 else s / total
            }
            s += 0.5
        }
        return 1.0
    }

    private val MARGIN = listOf(
        Vec3d.ZERO, Vec3d(0.6, 0.0, 0.0), Vec3d(-0.6, 0.0, 0.0), Vec3d(0.0, 0.6, 0.0), Vec3d(0.0, -0.6, 0.0), Vec3d(0.0, 0.0, 0.6), Vec3d(0.0, 0.0, -0.6),
    )

    /** Ein Kandidat: näher heran (Faktor zum Blickpunkt) und/oder höher. */
    class Fix(val scale: Double, val lift: Double)

    private val FIXES = listOf(
        Fix(1.0, 0.0), Fix(1.0, 3.0), Fix(0.85, 0.0), Fix(0.85, 3.0), Fix(1.0, 6.0), Fix(0.7, 2.0), Fix(1.0, 10.0),
        Fix(0.7, 6.0), Fix(0.55, 2.0), Fix(1.0, 16.0), Fix(0.55, 8.0), Fix(0.4, 4.0), Fix(1.0, 24.0), Fix(0.4, 12.0),
    )

    /**
     * Wählt die erste Korrektur, bei der alle Stichproben ([samples] liefert je Korrektur Paare Kamera → Blickpunkt)
     * frei sind; sonst die mit der besten schlechtesten Sicht.
     */
    fun pick(world: ServerWorld, origin: BlockPos, samples: (Fix) -> List<Pair<Vec3d, Vec3d>>): Fix {
        var best = FIXES[0]
        var bestScore = -1.0
        for (fix in FIXES) {
            var worst = 1.0
            for ((cam, focus) in samples(fix)) {
                worst = minOf(worst, clearance(world, origin, cam, focus))
                if (worst <= bestScore) break
            }
            if (worst >= 1.0) return fix
            if (worst > bestScore) {
                bestScore = worst
                best = fix
            }
        }
        return best
    }
}

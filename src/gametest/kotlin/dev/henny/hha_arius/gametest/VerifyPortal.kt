package dev.henny.hha_arius.gametest

import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.boss.Encounters
import dev.henny.hha_arius.gear.SealRitual
import dev.henny.hha_arius.portal.PortalBlocks
import dev.henny.hha_arius.portal.Portals
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos

class VerifyPortal : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) = VerifyKit.run(context, "portal") {
        val corner = server { srv ->
            val p = player(srv)
            val w = srv.overworld
            val c = p.blockPos.up(5)
            for (x in -1..2) for (y in -1..3) {
                val block = if (x in 0..1 && y in 0..2) Blocks.AIR else PortalBlocks.FRAME
                w.setBlockState(c.add(x, y, 0), block.defaultState)
            }
            for (z in -6..1) for (x in -2..3) w.setBlockState(c.add(x, -1, z), if (z == 0 && x in -1..2) PortalBlocks.FRAME.defaultState else Blocks.SNOW_BLOCK.defaultState)
            check(Portals.ignite(w, c.down(), p))
            p.teleport(w, c.x + 1.0, c.y.toDouble(), c.z - 4.0, emptySet(), 0f, 0f, false)
            c
        }
        tick(15)
        shot("gate")
        server { srv ->
            val p = player(srv)
            check(SealRitual.findSeal(p) == null)
            val w = srv.overworld
            p.teleport(w, corner.x + 0.5, corner.y.toDouble(), corner.z + 0.5, emptySet(), 0f, 0f, false)
        }
        check(waitUntil(80, 1) { server { Encounters.isParticipant(player(it)) } }) { "Empty-handed player could not enter" }
        server {
            val f = checkNotNull(fight())
            val origin = ArenaLayout.origin(f.slot)
            val inside = BlockPos.ofFloored(Portals.returnGateNear(origin))
            val frame = inside.west(2)
            check(!PortalBlocks.isFrame(f.world.getBlockState(frame)) && !PortalBlocks.isGate(f.world.getBlockState(inside)))
            Portals.lightReturn(f.world, origin)
            check(PortalBlocks.isFrame(f.world.getBlockState(frame)) && PortalBlocks.isGate(f.world.getBlockState(inside)))
            Portals.extinguishReturn(f.world, origin)
            check(f.world.getBlockState(frame).isAir && f.world.getBlockState(inside).isAir)
        }
        cmd("hhaarius stop")
        log("Portal entry without a seal, victory gate creation and slot reuse passed")
    }
}

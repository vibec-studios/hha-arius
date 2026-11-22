package dev.henny.hha_arius.gametest

import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.boss.Encounters
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.SealRitual
import dev.henny.hha_arius.portal.PortalBlocks
import dev.henny.hha_arius.portal.Portals
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.block.Blocks
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
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
            // Das Siegel geht ins Tor über: ein zweites Anzünden verbraucht nichts.
            val seal = ItemStack(GearItems.EIRHOLM_SEAL, 2)
            p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL)
            check(Portals.ignite(w, c.down(), p, seal) && seal.count == 1) { "Lighting must take exactly one seal" }
            check(!Portals.ignite(w, c.down(), p, seal) && seal.count == 1) { "A burning gate must not take a second seal" }
            p.teleport(w, c.x + 1.0, c.y.toDouble(), c.z - 4.0, emptySet(), 0f, 0f, false)
            c
        }
        tick(15)
        shot("gate")
        server { srv ->
            val p = player(srv)
            val w = srv.overworld
            p.teleport(w, corner.x + 1.0, corner.y + 0.5, corner.z - 2.3, emptySet(), 0f, 4f, false)
        }
        tick(8)
        shot("gate_close")
        server { srv ->
            val p = player(srv)
            val w = srv.overworld
            p.teleport(w, corner.x + 3.2, corner.y + 0.5, corner.z - 3.6, emptySet(), 34f, 6f, false)
        }
        tick(8)
        shot("gate_angle")
        server { srv ->
            val p = player(srv)
            p.inventory.clear()
            p.inventory.insertStack(ItemStack(Items.BREAD))
            val w = srv.overworld
            p.teleport(w, corner.x + 0.5, corner.y.toDouble(), corner.z + 0.5, emptySet(), 0f, 0f, false)
        }
        check(waitUntil(80, 1) { server { Encounters.isParticipant(player(it)) } }) { "Empty-handed player could not enter" }
        server { srv ->
            check(!PortalBlocks.isGate(srv.overworld.getBlockState(corner))) { "The gate must go dark while its seal is staked" }
            check(SealRitual.findSeal(player(srv)) == null) { "The stake must not appear in the inventory" }
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
        tick(10)
        server { srv ->
            check(PortalBlocks.isGate(srv.overworld.getBlockState(corner))) { "A lost fight must relight the gate" }
            check(SealRitual.findSeal(player(srv)) == null) { "The seal must return to the gate, not the inventory" }
            // Rahmen brechen: genau ein Siegel fällt heraus.
            srv.overworld.breakBlock(corner.add(-1, 1, 0), false)
        }
        tick(5)
        val drops = server { srv ->
            srv.overworld.getEntitiesByClass(net.minecraft.entity.ItemEntity::class.java, net.minecraft.util.math.Box(corner).expand(6.0)) { it.stack.isOf(GearItems.EIRHOLM_SEAL) }.sumOf { it.stack.count }
        }
        check(drops == 1) { "Breaking a burning gate must drop exactly one seal, got $drops" }
        log("Portal entry without a seal, victory gate creation and slot reuse passed")
    }
}

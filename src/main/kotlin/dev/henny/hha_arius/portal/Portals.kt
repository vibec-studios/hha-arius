package dev.henny.hha_arius.portal

import com.google.gson.Gson
import com.mojang.serialization.Codec
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.arena.FarPlan
import dev.henny.hha_arius.boss.Advancements
import dev.henny.hha_arius.boss.AriusState
import dev.henny.hha_arius.boss.Encounters
import dev.henny.hha_arius.net.HuntFx
import dev.henny.hha_arius.net.HuntNet
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.item.ItemStack
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import net.minecraft.world.World
import java.util.UUID

/**
 * Das Tor nach Eirholm (seit 1.10.0, Crix: „ein eigenes Portal, und nach dem Sieg nicht sofort rausfliegen“).
 *
 * * **Bauen:** Rahmen aus Raureif-Torstein wie ein Netherportal (innen 2–21 breit, 3–21 hoch, senkrecht, Ecken
 *   beliebig). Mit dem **Siegel von Eirholm** einen Rahmenstein benutzen: das Tor füllt sich (das Siegel bleibt).
 * * **Hindurch:** Wer zwei Sekunden im Tor steht, bricht auf. Gemerkt wird ein Platz **vor** dem Tor (Blick vom Tor weg) als Rückkehrpunkt.
 * * **Zurück:** Nach dem Sieg bleiben alle in Eirholm und können sich umsehen ([Encounters.stayAfterVictory]); die
 *   Rückkehrtore auf dem Gipfel und am Feld leuchten auf. Durch sie geht es an den gemerkten Platz (sonst an den
 *   Rückkehrpunkt des Kampfes, sonst an den Weltspawn); erst dort wird die Beute ausgezahlt.
 */
object Portals {

    /** Ticks im Tor bis zum Aufbruch (Netherportal: 80). */
    private const val CHARGE = 40

    private class Stand(var ticks: Int, var last: Long)

    private val standing = HashMap<UUID, Stand>()

    /** Wer gerade durch ein Tor gekommen ist, muss es erst verlassen, bevor es wieder wirkt. */
    private val needsExit = HashSet<UUID>()

    fun init() {
        PortalBlocks.register()
        ServerTickEvents.END_SERVER_TICK.register { server -> tick(server) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> standing.remove(handler.player.uuid) }
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register { player, origin, _ ->
            standing.remove(player.uuid)
            needsExit.add(player.uuid)
            // Wer Eirholm auf anderem Weg verlässt (Niederlage, Abbruch, Tod), braucht den gemerkten Platz nicht mehr.
            if (origin.registryKey == Encounters.WORLD) player.entityWorld.server?.let { homes(it).take(player.uuid) }
        }
    }

    fun isActivator(stack: ItemStack): Boolean =
        runCatching { stack.isOf(dev.henny.hha_arius.gear.GearItems.EIRHOLM_SEAL) }.getOrDefault(false)

    // --- Rahmen erkennen und entzünden -------------------------------------------------------------------------------

    /** Innenraum eines Rahmens: untere linke Ecke, Achse, Breite, Höhe. */
    class Frame(val corner: BlockPos, val axis: Direction.Axis, val width: Int, val height: Int) {
        val along: Direction = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        fun cells(): List<BlockPos> = (0 until width).flatMap { w -> (0 until height).map { h -> corner.offset(along, w).up(h) } }
    }

    private fun open(state: BlockState): Boolean = state.isAir || state.isOf(Blocks.SNOW) || state.isOf(Blocks.FIRE) || PortalBlocks.isGate(state)

    /** Sucht einen gültigen Rahmen, zu dem der Rahmenstein [clicked] gehört. */
    fun findFrame(world: World, clicked: BlockPos): Frame? {
        for (axis in arrayOf(Direction.Axis.X, Direction.Axis.Z)) {
            val along = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
            for (d in arrayOf(along, along.opposite, Direction.UP, Direction.DOWN)) {
                val start = clicked.offset(d)
                if (!open(world.getBlockState(start))) continue
                frameFrom(world, start, axis)?.let { return it }
            }
        }
        return null
    }

    private fun frameFrom(world: World, start: BlockPos, axis: Direction.Axis): Frame? {
        val along = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        var p = start
        // nach unten bis auf den Rahmen
        var n = 0
        while (open(world.getBlockState(p.down()))) {
            p = p.down()
            if (++n > 21) return null
        }
        if (!PortalBlocks.isFrame(world.getBlockState(p.down()))) return null
        // nach links bis an den Rahmen
        n = 0
        while (open(world.getBlockState(p.offset(along.opposite)))) {
            p = p.offset(along.opposite)
            if (++n > 21 || !PortalBlocks.isFrame(world.getBlockState(p.down()))) return null
        }
        if (!PortalBlocks.isFrame(world.getBlockState(p.offset(along.opposite)))) return null
        val corner = p
        var width = 0
        while (width <= 21 && open(world.getBlockState(corner.offset(along, width)))) {
            if (!PortalBlocks.isFrame(world.getBlockState(corner.offset(along, width).down()))) return null
            width++
        }
        if (width !in 2..21 || !PortalBlocks.isFrame(world.getBlockState(corner.offset(along, width)))) return null
        var height = 0
        rows@ while (height <= 21) {
            val left = corner.up(height).offset(along.opposite)
            val right = corner.up(height).offset(along, width)
            for (w in 0 until width) if (!open(world.getBlockState(corner.offset(along, w).up(height)))) break@rows
            if (!PortalBlocks.isFrame(world.getBlockState(left)) || !PortalBlocks.isFrame(world.getBlockState(right))) return null
            height++
        }
        if (height !in 3..21) return null
        for (w in 0 until width) if (!PortalBlocks.isFrame(world.getBlockState(corner.offset(along, w).up(height)))) return null
        return Frame(corner, axis, width, height)
    }

    /** Siegel auf einen Rahmenstein: Tor füllen. */
    fun ignite(world: ServerWorld, clicked: BlockPos, player: ServerPlayerEntity): Boolean {
        val frame = findFrame(world, clicked)
        if (frame == null) {
            player.sendMessage(Text.translatable("hha_arius.portal.no_frame").formatted(Formatting.GRAY), true)
            return false
        }
        if (world.registryKey == Encounters.WORLD) {
            player.sendMessage(Text.translatable("hha_arius.portal.inside").formatted(Formatting.GRAY), true)
            return false
        }
        fill(world, frame)
        Advancements.grant(player, "rime_gate")
        return true
    }

    private fun fill(world: ServerWorld, frame: Frame) {
        val state = PortalBlocks.PORTAL.defaultState.with(EirholmGateBlock.AXIS, frame.axis)
        for (c in frame.cells()) {
            val along = if (frame.axis == Direction.Axis.X) c.x - frame.corner.x else c.z - frame.corner.z
            val panel = (along and 1) + 2 * ((c.y - frame.corner.y) and 1)
            world.setBlockState(c, state.with(EirholmGateBlock.PANEL, panel), 2 or 16)
        }
        val mid = Vec3d.ofCenter(frame.corner).add(Vec3d.of(frame.along.vector).multiply((frame.width - 1) / 2.0)).add(0.0, (frame.height - 1) / 2.0, 0.0)
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, 0.9f, 1.4f)
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 1.2f, 0.7f)
        world.spawnParticles(ParticleTypes.SNOWFLAKE, mid.x, mid.y, mid.z, 120, frame.width * 0.4, frame.height * 0.4, 0.4, 0.08)
        world.spawnParticles(ParticleTypes.END_ROD, mid.x, mid.y, mid.z, 40, frame.width * 0.3, frame.height * 0.3, 0.3, 0.05)
    }

    // --- Rückkehrtore in Eirholm --------------------------------------------------------------------------------------

    /** Nach dem Sieg: die Rückkehrtore der Instanz leuchten auf (gesetzt ohne ArenaEdits, sie bleiben stehen). */
    fun lightReturn(world: ServerWorld, origin: BlockPos) {
        for (g in FarPlan.RETURN_GATES) {
            for (x in g.x0 - 1..g.x1 + 1) for (y in g.y0 - 1..g.y1 + 1) {
                if (g.frame(x, y, g.z)) world.setBlockState(origin.add(x, y, g.z), PortalBlocks.FRAME.defaultState, 2 or 16)
            }
            fill(world, Frame(origin.add(g.x0, g.y0, g.z), Direction.Axis.X, g.x1 - g.x0 + 1, g.y1 - g.y0 + 1))
        }
    }

    /** Entfernt auch Rahmen aus alten Instanzen und stellt die begehbare Schwelle wieder her. */
    fun extinguishReturn(world: ServerWorld, origin: BlockPos) {
        for (g in FarPlan.RETURN_GATES) for (x in g.x0 - 1..g.x1 + 1) for (y in g.y0 - 1..g.y1 + 1) {
            val pos = origin.add(x, y, g.z)
            val state = world.getBlockState(pos)
            if (PortalBlocks.isGate(state) || PortalBlocks.isFrame(state)) {
                val floor = if (g.y0 == 0) Blocks.CHISELED_STONE_BRICKS else Blocks.SNOW_BLOCK
                world.setBlockState(pos, (if (y == g.y0 - 1) floor else Blocks.AIR).defaultState, 2 or 16)
            }
        }
    }

    // --- Im Tor ---------------------------------------------------------------------------------------------------

    /** Spieler steht im Tor (einmal je Tick und Block, Vanilla ruft das für jede berührte Zelle). */
    fun inside(player: ServerPlayerEntity, world: ServerWorld, pos: BlockPos, state: BlockState) {
        if (player.uuid in needsExit || player.isSpectator) return
        val now = world.time
        val s = standing.getOrPut(player.uuid) { Stand(0, now - 1) }
        if (s.last == now) return
        s.ticks = if (s.last == now - 1) s.ticks + 1 else 1
        s.last = now
        if (s.ticks == 1) {
            world.playSound(null, player.x, player.y, player.z, SoundEvents.BLOCK_PORTAL_TRIGGER, SoundCategory.BLOCKS, 0.5f, 1.6f)
            HuntNet.fx(player, HuntFx.PORTAL_CHARGE, player.entityPos, a = CHARGE.toFloat(), life = CHARGE + 10)
        }
        if (s.ticks < CHARGE) return
        standing.remove(player.uuid)
        if (world.registryKey == Encounters.WORLD) travelBack(player, world) else travelIn(player, world, pos, state)
    }

    private fun travelIn(player: ServerPlayerEntity, world: ServerWorld, pos: BlockPos, state: BlockState) {
        val front = frontOf(world, pos, state, player)
        if (Encounters.isParticipant(player)) {
            // Die Gruppe formt sich schon (jemand anderes ging voran): Rückkehrpunkt vor dem Tor merken.
            homes(world.server ?: return).put(player, world, front.first, front.second)
            player.sendMessage(Text.translatable("hha_arius.msg.forming").formatted(Formatting.AQUA), true)
            needsExit.add(player.uuid)
            return
        }
        needsExit.add(player.uuid)
        world.playSound(null, player.x, player.y, player.z, SoundEvents.BLOCK_PORTAL_TRAVEL, SoundCategory.BLOCKS, 0.4f, 1.5f)
        if (Encounters.requestStart(player, ItemStack.EMPTY)) {
            homes(world.server ?: return).put(player, world, front.first, front.second)
            // Mitgenommene in der Nähe des Tors kehren ebenfalls vor das Tor zurück.
            for (other in world.players) if (other !== player && other.squaredDistanceTo(player) <= 12.0 * 12.0 && Encounters.isParticipant(other)) {
                homes(world.server ?: return).put(other, world, front.first, front.second)
            }
            Advancements.grant(player, "seal")
            HuntNet.fx(player, HuntFx.FADE, player.entityPos, a = 8f, b = 30f, life = 50, color = 0xFFF2F6FA.toInt())
        }
    }

    private fun travelBack(player: ServerPlayerEntity, world: ServerWorld) {
        val slot = Encounters.slotOf(player.x)
        val fight = Encounters.all().firstOrNull { it.slot == slot && !it.ended && it.isParticipant(player) }
        if (fight != null) return
        sendBack(player)
    }

    /** Bringt den Spieler aus Eirholm an seinen gemerkten Platz vor dem Tor (sonst Rückkehrpunkt des Kampfes). */
    fun sendBack(player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        Encounters.leaveExplorer(player)
        needsExit.add(player.uuid)
        val home = homes(server).take(player.uuid)
        val world = home?.let { server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(it.dim))) }
        HuntNet.fx(player, HuntFx.FADE, player.entityPos, a = 2f, b = 24f, life = 30, color = 0xFFF2F6FA.toInt())
        if (home != null && world != null && world.registryKey != Encounters.WORLD) {
            Encounters.discardPearlsIn(player, Encounters.WORLD)
            player.onLanding()
            player.teleport(world, home.x, home.y, home.z, emptySet(), home.yaw, 0f, false)
        } else {
            AriusState.sendHome(player)
        }
        player.entityWorld.playSound(null, player.x, player.y, player.z, SoundEvents.BLOCK_PORTAL_TRAVEL, SoundCategory.PLAYERS, 0.3f, 1.6f)
        Encounters.deliverPending(player)
    }

    /** Platz vor dem Tor auf der Seite, von der der Spieler kam, und Blick vom Tor weg. */
    private fun frontOf(world: ServerWorld, pos: BlockPos, state: BlockState, player: ServerPlayerEntity): Pair<Vec3d, Float> {
        val axis = if (state.contains(EirholmGateBlock.AXIS)) state.get(EirholmGateBlock.AXIS) else Direction.Axis.X
        // Normale des Tors: bei Achse X zeigt sie nach z.
        var bottom = pos
        while (PortalBlocks.isGate(world.getBlockState(bottom.down()))) bottom = bottom.down()
        val side = if (axis == Direction.Axis.X) (if (player.z >= pos.z + 0.5) 1 else -1) else (if (player.x >= pos.x + 0.5) 1 else -1)
        val dir = if (axis == Direction.Axis.X) Direction.SOUTH else Direction.EAST
        for (k in intArrayOf(side, -side)) {
            for (dist in 1..3) {
                val feet = bottom.offset(dir, k * dist)
                for (dy in 0..2) {
                    val f = feet.up(dy)
                    if (world.getBlockState(f).getCollisionShape(world, f).isEmpty && world.getBlockState(f.up()).getCollisionShape(world, f.up()).isEmpty &&
                        !world.getBlockState(f.down()).getCollisionShape(world, f.down()).isEmpty && !PortalBlocks.isGate(world.getBlockState(f))
                    ) {
                        val v = Vec3d(f.x + 0.5, f.y.toDouble(), f.z + 0.5)
                        val dx = (dir.offsetX * k).toDouble()
                        val dz = (dir.offsetZ * k).toDouble()
                        val yaw = (Math.toDegrees(kotlin.math.atan2(dz, dx)) - 90.0).toFloat()
                        return v to yaw
                    }
                }
            }
        }
        return player.entityPos to player.yaw
    }

    private fun tick(server: MinecraftServer) {
        if (needsExit.isEmpty()) return
        if (server.ticks % 5 != 0) return
        val it = needsExit.iterator()
        while (it.hasNext()) {
            val id = it.next()
            val p = server.playerManager.getPlayer(id)
            if (p == null) {
                it.remove()
                continue
            }
            val w = p.entityWorld
            val feet = p.blockPos
            if (!PortalBlocks.isGate(w.getBlockState(feet)) && !PortalBlocks.isGate(w.getBlockState(feet.up()))) it.remove()
        }
    }

    // --- Gemerkte Rückkehrpunkte -------------------------------------------------------------------------------------

    class Home(var dim: String = "minecraft:overworld", var x: Double = 0.0, var y: Double = 64.0, var z: Double = 0.0, var yaw: Float = 0f)

    class Data(var homes: MutableMap<String, Home> = HashMap())

    class HomeState(var data: Data = Data()) : PersistentState() {
        fun put(player: ServerPlayerEntity, world: World, pos: Vec3d, yaw: Float) {
            @Suppress("SENSELESS_COMPARISON")
            if (data.homes == null) data.homes = HashMap()
            data.homes[player.uuid.toString()] = Home(world.registryKey.value.toString(), pos.x, pos.y, pos.z, yaw)
            markDirty()
        }

        fun take(id: UUID): Home? {
            @Suppress("SENSELESS_COMPARISON")
            if (data.homes == null) data.homes = HashMap()
            val h = data.homes.remove(id.toString())
            if (h != null) markDirty()
            return h
        }
    }

    private val GSON = Gson()
    private val CODEC: Codec<HomeState> = Codec.STRING.xmap(
        { HomeState(runCatching { GSON.fromJson(it, Data::class.java) }.getOrNull() ?: Data()) },
        { GSON.toJson(it.data) },
    )
    private val TYPE = PersistentStateType("hha_arius_gates", { HomeState() }, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

    fun homes(server: MinecraftServer): HomeState = server.overworld.persistentStateManager.getOrCreate(TYPE)

    /** Absoluter Mittelpunkt des ersten Rückkehrtors einer Instanz (Hinweis im Chat). */
    fun returnGateNear(origin: BlockPos): Vec3d = FarPlan.RETURN_GATES.first().let { Vec3d(origin.x + it.x0 + 1.5, origin.y + it.y0.toDouble(), origin.z + it.z + 0.5) }

}

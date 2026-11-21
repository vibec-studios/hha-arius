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
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.item.ItemStack
import net.minecraft.particle.BlockStateParticleEffect
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
 *   beliebig). Mit dem **Siegel von Eirholm** einen Rahmenstein benutzen: das Siegel geht **in das Tor** über, ein
 *   brennendes Tor ist ein eingesetztes Siegel. Bricht der Rahmen, fällt das Siegel wieder heraus.
 * * **Hindurch** (seit 1.12.0 ohne Siegel im Inventar): Wer zwei Sekunden im Tor steht, bricht auf; das Tor erlischt, sein
 *   Siegel ist der Einsatz der Begegnung. Niederlage oder Abbruch: Das Tor brennt wieder (ist der Rahmen fort, kommt das
 *   Siegel ins Inventar). Sieg: Das Siegel ist verbraucht, für den nächsten Versuch braucht es ein neues. Gemerkt wird
 *   ein Platz **vor** dem Tor (Blick vom Tor weg) als Rückkehrpunkt.
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

    /** Tor, dessen Siegel gerade als Einsatz in einer Begegnung steckt (Begegnungs-ID → Lage des erloschenen Tors). */
    private class Taken(val dim: RegistryKey<World>, val corner: BlockPos, val axis: Direction.Axis)

    private val taken = HashMap<String, Taken>()

    /** In diesem Tick zerfallene Torzellen je Dimension: Jede zusammenhängende Gruppe gibt genau ein Siegel zurück. */
    private val collapsed = HashMap<RegistryKey<World>, HashSet<BlockPos>>()

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
        fun middle(): Vec3d = Vec3d.ofCenter(corner).add(Vec3d.of(along.vector).multiply((width - 1) / 2.0)).add(0.0, (height - 1) / 2.0, 0.0)
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
        // Vanilla reicht beim Berühren eine veränderliche Position herein; der Rahmen darf sie nicht festhalten.
        var p = start.toImmutable()
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

    /** Siegel auf einen Rahmenstein: Das Siegel geht in das Tor über und füllt es. */
    fun ignite(world: ServerWorld, clicked: BlockPos, player: ServerPlayerEntity, seal: ItemStack): Boolean {
        val frame = findFrame(world, clicked)
        if (frame == null) {
            player.sendMessage(Text.translatable("hha_arius.portal.no_frame").formatted(Formatting.GRAY), true)
            return false
        }
        if (world.registryKey == Encounters.WORLD) {
            player.sendMessage(Text.translatable("hha_arius.portal.inside").formatted(Formatting.GRAY), true)
            return false
        }
        if (frame.cells().all { PortalBlocks.isGate(world.getBlockState(it)) }) {
            player.sendMessage(Text.translatable("hha_arius.portal.already_lit").formatted(Formatting.GRAY), true)
            return false
        }
        seal.decrementUnlessCreative(1, player)
        fill(world, frame)
        Advancements.grant(player, "rime_gate")
        return true
    }

    /** Löscht das Tor, ohne dass die Nachbarn reagieren (sein Siegel ist jetzt der Einsatz, es fällt also nichts heraus). */
    private fun closeGate(world: ServerWorld, frame: Frame) {
        for (c in frame.cells()) if (PortalBlocks.isGate(world.getBlockState(c))) world.setBlockState(c, Blocks.AIR.defaultState, 2 or 16)
        val mid = frame.middle()
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 1.1f, 0.7f)
        world.spawnParticles(ParticleTypes.SNOWFLAKE, mid.x, mid.y, mid.z, 60, frame.width * 0.35, frame.height * 0.35, 0.3, 0.04)
    }

    /**
     * Niederlage oder Abbruch: Das Tor dieser Begegnung brennt wieder, sein Siegel bleibt eingesetzt. False, wenn der
     * Rahmen fort ist oder dort inzwischen ein anderes Siegel brennt: Dann gehört der Einsatz ins Inventar.
     */
    fun relight(encounterId: String, server: MinecraftServer): Boolean {
        val t = taken.remove(encounterId) ?: return false
        val world = server.getWorld(t.dim) ?: return false
        val frame = frameFrom(world, t.corner, t.axis) ?: return false
        if (frame.corner != t.corner || frame.cells().any { PortalBlocks.isGate(world.getBlockState(it)) }) return false
        fill(world, frame)
        return true
    }

    fun forgetTaken(encounterId: String) {
        taken.remove(encounterId)
    }

    /** Eine Torzelle ist zerfallen, weil ihr Rahmen brach (in Eirholm gibt es kein eingesetztes Siegel). */
    fun gateCollapsed(world: ServerWorld, pos: BlockPos) {
        if (world.registryKey == Encounters.WORLD) return
        collapsed.getOrPut(world.registryKey) { HashSet() }.add(pos.toImmutable())
    }

    private fun refundCollapsed(server: MinecraftServer) {
        if (collapsed.isEmpty()) return
        for ((dim, cells) in collapsed) {
            val world = server.getWorld(dim) ?: continue
            val open = HashSet(cells)
            while (open.isNotEmpty()) {
                val first = open.first()
                open.remove(first)
                val queue = ArrayDeque(listOf(first))
                while (queue.isNotEmpty()) {
                    val c = queue.removeFirst()
                    for (dir in Direction.entries) if (open.remove(c.offset(dir))) queue.addLast(c.offset(dir))
                }
                Block.dropStack(world, first, ItemStack(dev.henny.hha_arius.gear.GearItems.EIRHOLM_SEAL))
                world.playSound(null, first, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.BLOCKS, 1f, 0.7f)
            }
        }
        collapsed.clear()
    }

    private fun fill(world: ServerWorld, frame: Frame) {
        val state = PortalBlocks.PORTAL.defaultState.with(EirholmGateBlock.AXIS, frame.axis)
        for (c in frame.cells()) {
            val along = if (frame.axis == Direction.Axis.X) c.x - frame.corner.x else c.z - frame.corner.z
            val panel = (along and 1) + 2 * ((c.y - frame.corner.y) and 1)
            world.setBlockState(c, state.with(EirholmGateBlock.PANEL, panel), 2 or 16)
        }
        val mid = frame.middle()
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, 0.9f, 1.4f)
        world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 1.2f, 0.7f)
        world.spawnParticles(ParticleTypes.SNOWFLAKE, mid.x, mid.y, mid.z, 120, frame.width * 0.4, frame.height * 0.4, 0.4, 0.08)
        world.spawnParticles(ParticleTypes.END_ROD, mid.x, mid.y, mid.z, 40, frame.width * 0.3, frame.height * 0.3, 0.3, 0.05)
    }

    // --- Rückkehrtore in Eirholm --------------------------------------------------------------------------------------

    /**
     * Stellt die Rückkehrtore fertig hin (gesetzt ohne ArenaEdits, sie bleiben stehen). Schon stehende Tore bleiben
     * unberührt, also auch der Abschluss, falls [raiseReturn] unterbrochen wurde.
     */
    fun lightReturn(world: ServerWorld, origin: BlockPos) {
        for (g in FarPlan.RETURN_GATES) {
            val frame = Frame(origin.add(g.x0, g.y0, g.z), Direction.Axis.X, g.x1 - g.x0 + 1, g.y1 - g.y0 + 1)
            if (frame.cells().all { PortalBlocks.isGate(world.getBlockState(it)) }) continue
            for (x in g.x0 - 1..g.x1 + 1) for (y in g.y0 - 1..g.y1 + 1) {
                if (g.frame(x, y, g.z)) world.setBlockState(origin.add(x, y, g.z), PortalBlocks.FRAME.defaultState, 2 or 16)
            }
            fill(world, frame)
        }
    }

    /**
     * Seit 1.12.0 steht in der Arena vor dem Sieg kein Tor. Nach dem Sieg wächst der Rahmen Reihe für Reihe aus dem Boden
     * ([RISE_DELAY] Ticks Vorlauf für die Kamera, je Reihe [RISE_ROW] Ticks), dann zündet das Tor.
     */
    fun raiseReturn(world: ServerWorld, origin: BlockPos): Sequence<Unit> = sequence {
        repeat(RISE_DELAY) { yield(Unit) }
        val rows = FarPlan.RETURN_GATES.maxOf { it.y1 - it.y0 + 3 }
        for (row in 0 until rows) {
            for (g in FarPlan.RETURN_GATES) {
                val y = g.y0 - 1 + row
                if (y > g.y1 + 1) continue
                for (x in g.x0 - 1..g.x1 + 1) {
                    if (!g.frame(x, y, g.z)) continue
                    val pos = origin.add(x, y, g.z)
                    world.setBlockState(pos, PortalBlocks.FRAME.defaultState, 2 or 16)
                    world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, PortalBlocks.FRAME.defaultState), pos.x + 0.5, pos.y + 0.2, pos.z + 0.5, 8, 0.45, 0.15, 0.45, 0.08)
                    world.spawnParticles(ParticleTypes.SNOWFLAKE, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 2, 0.4, 0.3, 0.4, 0.02)
                }
                val mid = Vec3d(origin.x + (g.x0 + g.x1) / 2.0 + 0.5, origin.y + y + 0.5, origin.z + g.z + 0.5)
                world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BLOCK_DEEPSLATE_BRICKS_PLACE, SoundCategory.BLOCKS, 2.2f, 0.55f + row * 0.05f)
                if (row % 2 == 0) world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.BLOCKS, 1.6f, 0.6f + row * 0.08f)
            }
            repeat(RISE_ROW) { yield(Unit) }
        }
        lightReturn(world, origin)
        for (g in FarPlan.RETURN_GATES) {
            val mid = Vec3d(origin.x + (g.x0 + g.x1) / 2.0 + 0.5, origin.y + (g.y0 + g.y1) / 2.0 + 0.5, origin.z + g.z + 0.5)
            world.spawnParticles(ParticleTypes.END_ROD, mid.x, mid.y, mid.z, 70, 1.3, 1.7, 0.5, 0.09)
            world.playSound(null, mid.x, mid.y, mid.z, SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, 3f, 1.2f)
        }
    }

    /** Vorlauf bis zur ersten Reihe, Ticks je Reihe (siehe [raiseReturn], die Szene in `Scenes.ending` richtet sich danach). */
    const val RISE_DELAY = 18
    const val RISE_ROW = 5

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
        val axis = if (state.contains(EirholmGateBlock.AXIS)) state.get(EirholmGateBlock.AXIS) else Direction.Axis.X
        // Das brennende Tor ist das Siegel: Ohne vollständigen Rahmen gibt es keinen Einsatz und damit keinen Aufbruch.
        val frame = frameFrom(world, pos, axis)
        if (frame == null) {
            player.sendMessage(Text.translatable("hha_arius.portal.no_frame").formatted(Formatting.GRAY), true)
            return
        }
        world.playSound(null, player.x, player.y, player.z, SoundEvents.BLOCK_PORTAL_TRAVEL, SoundCategory.BLOCKS, 0.4f, 1.5f)
        if (Encounters.requestStart(player, ItemStack(dev.henny.hha_arius.gear.GearItems.EIRHOLM_SEAL))) {
            Encounters.all().firstOrNull { !it.ended && it.isParticipant(player) }?.let { taken[it.encounterId] = Taken(world.registryKey, frame.corner, frame.axis) }
            closeGate(world, frame)
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
        refundCollapsed(server)
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

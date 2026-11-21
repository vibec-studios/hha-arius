package dev.henny.hha_arius.portal

import dev.henny.hha_arius.HhaArius
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockRenderType
import net.minecraft.block.BlockState
import net.minecraft.block.ShapeContext
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityCollisionHandler
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.BlockItem
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.state.StateManager
import net.minecraft.state.property.IntProperty
import net.minecraft.state.property.Properties
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.util.shape.VoxelShape
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.BlockView
import net.minecraft.world.World
import net.minecraft.world.WorldView
import net.minecraft.world.tick.ScheduledTickView

/**
 * Blöcke der Welt-Überarbeitung 1.10.0:
 * * **Raureif-Torstein** ([FRAME]): Rahmen des Tors nach Eirholm (wie ein Netherportal, 2–21 breit, 3–21 hoch, Ecken
 *   egal). Mit dem Siegel von Eirholm angeklickt, füllt sich der Rahmen mit dem Tor.
 * * **Tor nach Eirholm** ([PORTAL]): wirbelndes Frostlicht. Wer zwei Sekunden darin steht, bricht nach Eirholm auf; in Eirholm selbst führt es nach dem Sieg zurück ([Portals]).
 * * **Hängebrückendeck** ([DECK]): unsichtbare, stabile Kollision (1–8 Schichten wie Schnee) unter den Planken, die der
 *   Client schwingend zeichnet.
 */
object PortalBlocks {

    lateinit var FRAME: Block private set
    lateinit var PORTAL: Block private set
    lateinit var DECK: Block private set
    lateinit var FRAME_ITEM: Item private set

    private var done = false

    fun register() {
        if (done) return
        done = true
        FRAME = block("rime_gate_stone", AbstractBlock.Settings.create().strength(8f, 1200f).requiresTool().luminance { 3 }.sounds(BlockSoundGroup.DEEPSLATE_BRICKS)) { RimeFrameBlock(it) }
        PORTAL = block(
            "eirholm_gate",
            AbstractBlock.Settings.create().noCollision().strength(-1f, 3600000f).luminance { 12 }.sounds(BlockSoundGroup.GLASS)
                .pistonBehavior(PistonBehavior.BLOCK).dropsNothing().nonOpaque().replaceable().allowsSpawning { _, _, _, _ -> false },
        ) { EirholmGateBlock(it) }
        DECK = block(
            "rope_deck",
            AbstractBlock.Settings.create().strength(-1f, 3600000f).dropsNothing().nonOpaque().pistonBehavior(PistonBehavior.BLOCK)
                .sounds(BlockSoundGroup.WOOD).allowsSpawning { _, _, _, _ -> false }.solidBlock { _, _, _ -> false }
                .suffocates { _, _, _ -> false }.blockVision { _, _, _ -> false },
        ) { DeckBlock(it) }
        val itemId = HhaArius.id("rime_gate_stone")
        val itemKey = RegistryKey.of(RegistryKeys.ITEM, itemId)
        FRAME_ITEM = Registry.register(Registries.ITEM, itemKey, BlockItem(FRAME, Item.Settings().registryKey(itemKey).useBlockPrefixedTranslationKey()))
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, HhaArius.id("eirholm"))).register { it.add(FRAME_ITEM) }
    }

    private fun block(name: String, settings: AbstractBlock.Settings, make: (AbstractBlock.Settings) -> Block): Block {
        val id = HhaArius.id(name)
        return Registry.register(Registries.BLOCK, id, make(settings.registryKey(RegistryKey.of(RegistryKeys.BLOCK, id))))
    }

    fun isFrame(state: BlockState): Boolean = done && state.isOf(FRAME)
    fun isGate(state: BlockState): Boolean = done && state.isOf(PORTAL)
    fun isDeck(state: BlockState): Boolean = done && state.isOf(DECK)
}

/** Raureif-Torstein: Benutzen mit dem Siegel von Eirholm entzündet das Tor im Rahmen. */
class RimeFrameBlock(settings: Settings) : Block(settings) {
    override fun onUseWithItem(stack: ItemStack, state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hand: Hand, hit: BlockHitResult): ActionResult {
        if (!Portals.isActivator(stack)) return ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION
        if (world !is ServerWorld || player !is ServerPlayerEntity) return ActionResult.SUCCESS
        return if (Portals.ignite(world, pos, player)) ActionResult.SUCCESS else ActionResult.FAIL
    }
}

/** Das Tor selbst: keine Kollision, dünn in Achsrichtung, zerfällt, wenn der Rahmen bricht. */
class EirholmGateBlock(settings: Settings) : Block(settings) {

    init {
        defaultState = stateManager.defaultState.with(AXIS, Direction.Axis.X).with(PANEL, 4)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(AXIS, PANEL)
    }

    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape =
        if (state.get(AXIS) == Direction.Axis.X) X_SHAPE else Z_SHAPE

    override fun getCollisionShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = VoxelShapes.empty()

    override fun getStateForNeighborUpdate(
        state: BlockState, world: WorldView, tickView: ScheduledTickView, pos: BlockPos, direction: Direction,
        neighborPos: BlockPos, neighborState: BlockState, random: Random,
    ): BlockState {
        val axis = state.get(AXIS)
        val inPlane = direction.axis == Direction.Axis.Y || direction.axis == axis
        if (inPlane && !PortalBlocks.isGate(neighborState) && !PortalBlocks.isFrame(neighborState)) return net.minecraft.block.Blocks.AIR.defaultState
        return state
    }

    override fun onEntityCollision(state: BlockState, world: World, pos: BlockPos, entity: Entity, handler: EntityCollisionHandler, intersects: Boolean) {
        if (world is ServerWorld && entity is ServerPlayerEntity) Portals.inside(entity, world, pos, state)
    }

    override fun randomDisplayTick(state: BlockState, world: World, pos: BlockPos, random: Random) {
        if (random.nextInt(90) == 0) {
            world.playSoundClient(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SoundEvents.BLOCK_PORTAL_AMBIENT, SoundCategory.BLOCKS, 0.35f, 1.5f + random.nextFloat() * 0.3f, false)
        }
        if (random.nextInt(40) == 0) {
            world.playSoundClient(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.BLOCKS, 0.4f, 0.6f + random.nextFloat() * 0.3f, false)
        }
        val x = state.get(AXIS) == Direction.Axis.X
        repeat(2) {
            val px = pos.x + if (x) random.nextDouble() else 0.5 + (random.nextDouble() - 0.5) * 0.3
            val pz = pos.z + if (x) 0.5 + (random.nextDouble() - 0.5) * 0.3 else random.nextDouble()
            val py = pos.y + random.nextDouble()
            val out = (random.nextDouble() - 0.5) * 0.12
            world.addParticleClient(ParticleTypes.SNOWFLAKE, px, py, pz, if (x) 0.0 else out, 0.02, if (x) out else 0.0)
        }
        if (random.nextInt(3) == 0) world.addParticleClient(ParticleTypes.END_ROD, pos.x + random.nextDouble(), pos.y + random.nextDouble(), pos.z + random.nextDouble(), 0.0, 0.01, 0.0)
    }

    companion object {
        val AXIS = Properties.HORIZONTAL_AXIS
        val PANEL: IntProperty = IntProperty.of("panel", 0, 4)
        private val X_SHAPE: VoxelShape = createCuboidShape(0.0, 0.0, 6.0, 16.0, 16.0, 10.0)
        private val Z_SHAPE: VoxelShape = createCuboidShape(6.0, 0.0, 0.0, 10.0, 16.0, 16.0)
    }
}

/**
 * Deck einer Hängebrücke: unsichtbar, nicht anwählbar, Kollision wie eine Schneeschicht mit [LAYERS] × 2/16 Höhe
 * (8 = voller Block). Wird nur vom Bau der Fernlandschaft gesetzt; in der Arena kann niemand abbauen.
 */
class DeckBlock(settings: Settings) : Block(settings) {

    init {
        defaultState = stateManager.defaultState.with(LAYERS, 8)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(LAYERS)
    }

    override fun getRenderType(state: BlockState): BlockRenderType = BlockRenderType.INVISIBLE

    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = VoxelShapes.empty()

    override fun getCollisionShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = SHAPES[state.get(LAYERS)]

    override fun getCameraCollisionShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = VoxelShapes.empty()

    companion object {
        val LAYERS: IntProperty = Properties.LAYERS
        private val SHAPES: Array<VoxelShape> = Array(9) { n -> if (n == 0) VoxelShapes.empty() else createCuboidShape(0.0, 0.0, 0.0, 16.0, n * 2.0, 16.0) }
    }
}

package dev.henny.hha_arius.gear.art

import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.boss.Encounters
import dev.henny.hha_arius.gear.GearCombat
import dev.henny.hha_arius.gear.GearConfig
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.GearSounds
import dev.henny.hha_arius.gear.GearTasks
import dev.henny.hha_arius.gear.GearVfx
import dev.henny.hha_arius.gear.Resonance
import dev.henny.hha_arius.net.FxKind
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributeModifier
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.registry.tag.BlockTags
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.abs

/**
 * Solvane – „Klarer Schnitt“ (Waffentechnik, Technik-Taste mit Solvane in der Haupthand):
 * 1. Taste halten: Haltung (stark verlangsamt), Energie sammelt sich an der Schneide (Mindestladung 0,8 s, voll 2 s).
 * 2. Blick richtet die Schnittebene aus; Schleichen beim Loslassen = waagerechter statt senkrechter Schnitt. Der eigene
 *    Client zeigt die Ebene als Vorschau (gleiche Geometrie, [CutPlane]).
 * 3. Loslassen: ein flacher, klar begrenzter Schnitt (Länge 6–12, Breite 3–5 Blöcke je nach Ladung), keine Explosion.
 * 4. Erholung 1 s (keine Angriffe, langsam), Abklingzeit 12 s, danach Nachhall für andere große Techniken.
 *
 * Blöcke (Config `clear_cut_blocks`): Es werden echte, vollständige Blöcke abgebaut – höchstens `clear_cut_block_max`,
 * nur die, deren Mitte in der Schnittebene liegt, Härte 0..`clear_cut_max_hardness` (Holz, Blätter, Erde, Stein,
 * Tiefenschiefer; nie Obsidian, Grundgestein oder Unzerstörbares), keine Blöcke mit Block-Entity (Truhen, Öfen,
 * Schilder …, also nie ein Behälter samt Inhalt), keine zweiteiligen Blöcke (Türen, hohe Pflanzen), nur wo der Spieler
 * abbauen darf (Abenteuermodus, Spawnschutz, Weltgrenze, Schutz-Mods über Fabrics PlayerBlockBreakEvents), nie in der
 * Eirholm-Arena. Drops genau wie beim Abbau mit Solvane: Was ein Schwert nicht ernten darf (z. B. Stein ohne
 * Spitzhacke), wird zerstört, ohne etwas fallen zu lassen; jeder Block wird genau einmal abgebaut (keine Duplikation).
 * Die zwei auseinandergleitenden Hälften sind nur Darstellung im Client ([ArtCutS2C]); die Welt enthält danach Luft.
 */
object SolvaneArt {

    private class State(val start: Long)

    private val charging = HashMap<UUID, State>()
    private val cooldownUntil = HashMap<UUID, Long>()
    private val SLOW_ID = HhaArius.id("clear_cut_stance")

    fun init() {
        GearTasks.everyTick { tickAll() }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            charging.remove(handler.player.uuid)
            removeSlow(handler.player)
        }
    }

    fun cooldownLeft(player: ServerPlayerEntity): Int = ((cooldownUntil[player.uuid] ?: 0L) - GearTasks.now).coerceIn(0, 100000).toInt()

    private fun holds(player: ServerPlayerEntity) = player.mainHandStack.isOf(GearItems.SOLVANE)

    fun press(player: ServerPlayerEntity) {
        if (!GearConfig.on("clear_cut") || !holds(player) || player.isSpectator) return
        if (player.uuid in charging || Focus.drawing(player) || !Focus.isFree(player)) return
        val left = cooldownLeft(player)
        if (left > 0) {
            GearCombat.actionbar(player, "hha_arius.msg.art.cooldown", net.minecraft.text.Text.translatable("hha_arius.art.clear_cut"), String.format("%.1f", left / 20.0))
            return
        }
        if (Focus.inReverb(player)) return
        val cost = GearConfig.float("clear_cut_cost")
        if (Resonance.get(player) < cost) {
            GearCombat.actionbar(player, "hha_arius.msg.need_resonance", cost.toInt(), Resonance.get(player).toInt())
            return
        }
        if (player.isUsingItem) player.stopUsingItem()
        charging[player.uuid] = State(GearTasks.now)
        Focus.begin(player, Focus.Busy.ART, 0) { stop(player, cancelled = true) }
        applySlow(player, -0.6)
        ArtNet.anim(player, Anim.ART_CHARGE, -1, if (player.isSneaking) 1 else 0)
        GearSounds.play(player.entityWorld, player.entityPos, "clear_cut.charge", SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), 0.7f, 1.5f)
    }

    fun cancel(player: ServerPlayerEntity) {
        if (player.uuid in charging) Focus.cancel(player)
    }

    private fun stop(player: ServerPlayerEntity, cancelled: Boolean) {
        if (charging.remove(player.uuid) == null) return
        removeSlow(player)
        if (cancelled) ArtNet.anim(player, Anim.NONE, 0)
    }

    fun release(player: ServerPlayerEntity, horizontal: Boolean) {
        val s = charging[player.uuid] ?: return
        val held = (GearTasks.now - s.start).toFloat()
        if (held < ArtTimings.ART_CHARGE_MIN) {
            // Zu früh losgelassen: verpufft, keine Kosten.
            Focus.cancel(player)
            GearCombat.actionbar(player, "hha_arius.msg.art.fizzle")
            return
        }
        val cost = GearConfig.float("clear_cut_cost")
        if (!Resonance.spend(player, cost)) {
            Focus.cancel(player)
            GearCombat.actionbar(player, "hha_arius.msg.need_resonance", cost.toInt(), Resonance.get(player).toInt())
            return
        }
        charging.remove(player.uuid)
        val charge = ArtTimings.artCharge(held)
        val recover = GearConfig.ticks("clear_cut_recover")
        Focus.begin(player, Focus.Busy.ART, GearTasks.now + ArtTimings.ART_HIT + recover) { removeSlow(player) }
        applySlow(player, -0.5)
        cooldownUntil[player.uuid] = GearTasks.now + GearConfig.ticks("clear_cut_cooldown")
        SpellCasting.markDirty(player)
        player.itemCooldownManager.set(GearItems.CLEAR_CUT_GROUP, GearConfig.ticks("clear_cut_cooldown"))
        ArtNet.anim(player, Anim.ART_RELEASE, ArtTimings.ART_RELEASE, if (horizontal) 1 else 0)
        // Ebene im Moment des Loslassens festhalten; der Treffer kommt mit dem Schwung (ART_HIT).
        val plane = CutPlane.of(player, ArtTimings.artLength(charge), ArtTimings.artHalf(charge), horizontal)
        val world = player.entityWorld
        GearTasks.later(ArtTimings.ART_HIT) {
            if (GearCombat.stillThere(player, world)) cut(player, world, plane, charge)
        }
        GearTasks.later(ArtTimings.ART_HIT + recover) {
            removeSlow(player)
            Focus.end(player, Focus.Busy.ART)
            Focus.reverb(player)
        }
    }

    private fun tickAll() {
        if (charging.isEmpty()) return
        for ((id, s) in charging.entries.toList()) {
            val player = GearCombat.player(id) ?: continue
            if (!holds(player)) {
                Focus.cancel(player)
                continue
            }
            // Zu lange gehalten: löst von selbst aus.
            if (GearTasks.now - s.start > ArtTimings.ART_CHARGE_FULL + ArtTimings.ART_HOLD_MAX) release(player, player.isSneaking)
        }
    }

    // ------------------------------------------------------------------------------------------------- Schnitt

    private fun cut(player: ServerPlayerEntity, world: ServerWorld, plane: CutPlane, charge: Float) {
        val damage = (GearConfig.num("clear_cut_damage") + (GearConfig.num("clear_cut_damage_full") - GearConfig.num("clear_cut_damage")) * charge).toFloat()
        val reach = plane.length + plane.half + 2
        var any = false
        for (e in world.getOtherEntities(player, Box.of(plane.origin, reach * 2, reach * 2, reach * 2))) {
            val r = maxOf(e.width, e.height) / 2.0
            if (!plane.contains(e.boundingBox.center, r)) continue
            when {
                e is ProjectileEntity && GearCombat.isHostileProjectile(player, e) -> GearCombat.discardProjectile(world, e)
                GearCombat.canHit(player, e) -> {
                    val t = e as LivingEntity
                    t.timeUntilRegen = 0
                    if (t.damage(world, world.damageSources.playerAttack(player), damage)) {
                        any = true
                        if (!GearCombat.isHeavy(t)) {
                            val side = if (e.boundingBox.center.subtract(plane.origin).dotProduct(plane.normal) >= 0) 1.0 else -1.0
                            GearCombat.push(t, t.entityPos.subtract(plane.normal.multiply(side)), 0.5, 0.15)
                        }
                    }
                }
            }
        }
        if (any) Resonance.markCombat(player)
        ArtFx.send(world, FxKind.CUT_PLANE, plane.origin, plane.forward.multiply(plane.length), plane.half.toFloat(),
            if (abs(plane.span.y) < 0.5) 1f else 0f, 16)
        GearVfx.crescent(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.1, 0.0), plane.forward, 3f, 2.4f, 6)
        GearSounds.play(world, plane.origin, "clear_cut.release", SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.7f)
        if (GearConfig.on("clear_cut_blocks")) cutBlocks(player, world, plane)
    }

    private fun cutBlocks(player: ServerPlayerEntity, world: ServerWorld, plane: CutPlane) {
        if (world.registryKey == ArenaDimension.WORLD || Encounters.isParticipant(player)) return
        if (!player.canModifyBlocks()) return
        val max = GearConfig.ticks("clear_cut_block_max")
        if (max <= 0) return
        val maxHardness = GearConfig.float("clear_cut_max_hardness")
        val tool = player.mainHandStack
        // Kandidaten: Blöcke, deren Mitte in der Ebene liegt (Dicke ±0,55), sortiert nach Abstand vom Spieler.
        val candidates = LinkedHashSet<BlockPos>()
        var a = 0.5
        while (a <= plane.length) {
            var b = -plane.half
            while (b <= plane.half) {
                val p = plane.origin.add(plane.forward.multiply(a)).add(plane.span.multiply(b))
                val pos = BlockPos.ofFloored(p)
                if (abs(Vec3d.ofCenter(pos).subtract(plane.origin).dotProduct(plane.normal)) <= 0.55) candidates.add(pos)
                b += 0.5
            }
            a += 0.5
        }
        val removed = ArrayList<Pair<BlockPos, BlockState>>()
        for (pos in candidates) {
            if (removed.size >= max) break
            if (!world.isChunkLoaded(pos)) continue
            val state = world.getBlockState(pos)
            if (!cuttable(world, player, pos, state, maxHardness)) continue
            if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(world, player, pos, state, null)) continue
            val harvest = !state.isToolRequired || tool.isSuitableFor(state)
            if (!world.removeBlock(pos, false)) continue
            if (harvest) Block.dropStacks(state, world, pos, null, player, tool)
            state.block.onBroken(world, pos, state)
            PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(world, player, pos, state, null)
            removed.add(pos.toImmutable() to state)
        }
        if (removed.isEmpty()) return
        val n = plane.normal
        val axis = when {
            abs(n.x) >= abs(n.y) && abs(n.x) >= abs(n.z) -> 0
            abs(n.y) >= abs(n.z) -> 1
            else -> 2
        }
        val payload = ArtCutS2C(LongArray(removed.size) { removed[it].first.asLong() }, IntArray(removed.size) { Block.getRawIdFromState(removed[it].second) }, axis)
        val near = PlayerLookup.around(world, plane.origin, 96.0)
        for (p in near) if (ServerPlayNetworking.canSend(p, ArtCutS2C.ID)) ServerPlayNetworking.send(p, payload)
    }

    private fun cuttable(world: ServerWorld, player: ServerPlayerEntity, pos: BlockPos, state: BlockState, maxHardness: Float): Boolean {
        if (state.isAir || !state.fluidState.isEmpty && state.isLiquid) return false
        if (state.hasBlockEntity()) return false
        if (state.contains(Properties.DOUBLE_BLOCK_HALF) || state.isIn(BlockTags.DOORS) || state.isIn(BlockTags.BEDS)) return false
        if (state.isIn(BlockTags.WITHER_IMMUNE) || state.isIn(BlockTags.DRAGON_IMMUNE)) return false
        if (Constructs.isConstruct(state)) return true
        val hardness = state.getHardness(world, pos)
        if (hardness < 0f || hardness > maxHardness) return false
        return world.canEntityModifyAt(player, pos) && !player.isBlockBreakingRestricted(world, pos, player.interactionManager.gameMode)
    }

    // ------------------------------------------------------------------------------------------------- Helfer

    private fun applySlow(player: ServerPlayerEntity, amount: Double) {
        val inst = player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED) ?: return
        inst.removeModifier(SLOW_ID)
        inst.addTemporaryModifier(EntityAttributeModifier(SLOW_ID, amount, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
    }

    private fun removeSlow(player: ServerPlayerEntity) {
        player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED)?.removeModifier(SLOW_ID)
    }
}

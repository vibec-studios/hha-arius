package dev.henny.hha_arius.gear.art

import com.mojang.serialization.Codec
import dev.henny.hha.api.event.HhaEvents
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaDimension
import dev.henny.hha_arius.boss.Encounters
import dev.henny.hha_arius.gear.FrostIce
import dev.henny.hha_arius.gear.GearCombat
import dev.henny.hha_arius.gear.GearConfig
import dev.henny.hha_arius.gear.GearItems
import dev.henny.hha_arius.gear.GearSounds
import dev.henny.hha_arius.gear.GearTasks
import dev.henny.hha_arius.gear.GearVfx
import dev.henny.hha_arius.gear.Resonance
import dev.henny.hha_arius.net.FxKind
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.block.Block
import net.minecraft.entity.ExperienceOrbEntity
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.EntityAttributeModifier
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Magiemodus der Handschuhe (Server). Auswahl über das Rad ([ArtAction.SELECT], dauerhaft in der Spielerdatei), Wirken
 * mit Rechtsklick außerhalb des Rads, je nach [CastKind]. Kosten in Frostresonanz werden erst beim tatsächlichen Wirken
 * abgezogen; Abbruch (Linksklick, Itemwechsel, Moduswechsel, Tod, Weltwechsel) kostet nichts. Jede Technik hat ihre
 * eigene Abklingzeit am Spieler (nicht am Stapel): Exemplare tauschen hilft nicht.
 */
object SpellCasting {

    /** Auswahl in der Spielerdatei: gewählt | vorige << 8. */
    val SELECTION: AttachmentType<Int> = AttachmentRegistry.create(HhaArius.id("art_selection")) { builder ->
        builder.persistent(Codec.INT).copyOnDeath().initializer { Spell.WIND_BLADE.ordinal or (Spell.YANG_PLATFORM.ordinal shl 8) }
    }

    private class State {
        val cooldownUntil = LongArray(Spell.entries.size)
        var active: Spell? = null
        var start = 0L
        var glideStart = 0L
        var leapAirborne = false
        var noFall = false
        var absorbGranted = 0f
        var absorbUntil = 0L
        var dirty = true
        var lastSync = 0L
    }

    private val states = HashMap<UUID, State>()
    private val platforms = HashMap<UUID, ArrayDeque<List<BlockPos>>>()
    private val ABSORB_ID = HhaArius.id("ice_armor")

    private fun st(player: ServerPlayerEntity) = states.getOrPut(player.uuid) { State() }

    fun init() {
        GearTasks.everyTick { tickAll() }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, amount ->
            if (entity !is ServerPlayerEntity) return@register true
            if (source.isIn(DamageTypeTags.IS_FALL) && states[entity.uuid]?.noFall == true) return@register false
            !shieldBlocks(entity, source, amount)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> st(handler.player).dirty = true }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            endAbsorb(handler.player)
            states.remove(handler.player.uuid)
        }
        HhaEvents.PLAYER_TICK.register { _, player -> tickPlayer(player) }
    }

    // ------------------------------------------------------------------------------------------------- Auswahl

    fun selected(player: ServerPlayerEntity): Spell = Spell.of(player.getAttachedOrCreate(SELECTION) and 0xFF) ?: Spell.WIND_BLADE

    fun previous(player: ServerPlayerEntity): Spell = Spell.of((player.getAttachedOrCreate(SELECTION) shr 8) and 0xFF) ?: Spell.YANG_PLATFORM

    fun select(player: ServerPlayerEntity, spell: Spell) {
        val current = selected(player)
        if (current == spell) return
        if (st(player).active != null) Focus.cancel(player)
        player.setAttached(SELECTION, spell.ordinal or (current.ordinal shl 8))
        st(player).dirty = true
        GearSounds.play(player.entityWorld, player.entityPos, "art.select", SoundEvents.UI_BUTTON_CLICK.value(), 0.25f, 1.6f)
    }

    /** Kosten der bereiten Technik für die Marke am Resonanzbalken (-1 = nichts bereit). */
    fun costMark(player: ServerPlayerEntity): Int {
        val main = player.mainHandStack
        return when {
            main.isOf(GearItems.SOLVANE) && GearConfig.on("clear_cut") -> GearConfig.ticks("clear_cut_cost")
            GauntletsItem.isGauntlets(main) && GauntletsItem.mode(main) == GauntletMode.MAGIC -> cost(selected(player), 0f).toInt()
            else -> -1
        }
    }

    private fun num(spell: Spell, field: String): Double = GearConfig.num("spell_${spell.key}_$field")

    fun cost(spell: Spell, charge: Float): Float {
        val base = num(spell, "cost").toFloat()
        if (spell.kind != CastKind.CHARGE) return base
        val full = num(spell, "cost_full").toFloat()
        return base + (full - base) * charge
    }

    private fun cooldownLeft(player: ServerPlayerEntity, spell: Spell): Long =
        (st(player).cooldownUntil[spell.ordinal] - GearTasks.now).coerceAtLeast(0)

    private fun setCooldown(player: ServerPlayerEntity, spell: Spell, ticks: Int) {
        val s = st(player)
        s.cooldownUntil[spell.ordinal] = GearTasks.now + ArtTest.cd(player, ticks)
        s.dirty = true
    }

    fun holdsMagic(player: ServerPlayerEntity): Boolean {
        val stack = player.mainHandStack
        return GauntletsItem.isGauntlets(stack) && GauntletsItem.mode(stack) == GauntletMode.MAGIC
    }

    /** Bauwerke und Gleiter sind im Eirholm-Kampf aus (Config `art_constructs_in_arena`). */
    private fun constructsAllowed(player: ServerPlayerEntity): Boolean {
        if (GearConfig.on("art_constructs_in_arena") || ArtTest.on(player)) return true
        return player.entityWorld.registryKey != ArenaDimension.WORLD && !Encounters.isParticipant(player)
    }

    // ------------------------------------------------------------------------------------------------- Eingaben

    fun press(player: ServerPlayerEntity) {
        if (!GearConfig.on("gauntlets") || !holdsMagic(player) || player.isSpectator) return
        val s = st(player)
        if (s.active == Spell.YANG_GLIDE) {
            Focus.cancel(player)
            return
        }
        if (s.active != null || Focus.drawing(player) || !Focus.isFree(player)) return
        val spell = selected(player)
        val left = cooldownLeft(player, spell)
        if (left > 0) {
            GearCombat.actionbar(player, "hha_arius.msg.art.cooldown", spellName(spell), String.format("%.1f", left / 20.0))
            return
        }
        if ((spell.kind == CastKind.PLACE && spell != Spell.FROST_FIELD || spell == Spell.YANG_GLIDE) && !constructsAllowed(player)) {
            GearCombat.actionbar(player, "hha_arius.msg.art.not_here")
            return
        }
        val need = cost(spell, 0f)
        if (Resonance.get(player) < need) {
            GearCombat.actionbar(player, "hha_arius.msg.need_resonance", need.toInt(), Resonance.get(player).toInt())
            return
        }
        val world = player.entityWorld
        val school = spell.school.ordinal
        when (spell.kind) {
            CastKind.TAP -> {
                if (!tap(player, world, spell)) return
                Resonance.spend(player, need)
                setCooldown(player, spell, num(spell, "cooldown").toInt())
                ArtNet.anim(player, Anim.CAST_TAP, ArtTimings.CAST_TAP, school)
            }
            CastKind.CHARGE -> begin(player, s, spell, Focus.Busy.CHARGE, Anim.CAST_CHARGE)
            CastKind.PLACE -> begin(player, s, spell, Focus.Busy.AIM, Anim.PLACE_AIM)
            CastKind.CHANNEL -> {
                Resonance.spend(player, need)
                begin(player, s, spell, Focus.Busy.CHANNEL, Anim.SHIELD)
                GearSounds.play(world, player.entityPos, "art.shield_up", SoundEvents.BLOCK_BEACON_ACTIVATE, 0.5f, 1.8f)
            }
            CastKind.TOGGLE -> {
                Resonance.spend(player, need)
                begin(player, s, spell, Focus.Busy.GLIDE, Anim.GLIDE)
                s.glideStart = GearTasks.now
                s.dirty = true
                GearSounds.play(world, player.entityPos, "art.glide", SoundEvents.ITEM_ELYTRA_FLYING, 0.3f, 1.6f)
            }
        }
    }

    private fun begin(player: ServerPlayerEntity, s: State, spell: Spell, busy: Focus.Busy, anim: Anim) {
        s.active = spell
        s.start = GearTasks.now
        Focus.begin(player, busy, 0) { stopActive(player, cancelled = true) }
        ArtNet.anim(player, anim, -1,
            if (anim == Anim.CAST_CHARGE) ArtParam.cast(spell.school.ordinal, num(spell, "charge_max").toInt().coerceAtLeast(1)) else spell.school.ordinal)
    }

    fun release(player: ServerPlayerEntity) {
        val s = states[player.uuid] ?: return
        val spell = s.active ?: return
        when (spell.kind) {
            CastKind.CHARGE -> releaseCharge(player, s, spell)
            CastKind.PLACE -> releasePlace(player, s, spell)
            CastKind.CHANNEL -> {
                stopActive(player, cancelled = false)
                Focus.end(player)
            }
            else -> {}
        }
    }

    fun cancel(player: ServerPlayerEntity) {
        if (states[player.uuid]?.active != null) Focus.cancel(player)
    }

    /** Ende der laufenden Technik. cancelled = von außen abgebrochen. */
    private fun stopActive(player: ServerPlayerEntity, cancelled: Boolean) {
        val s = states[player.uuid] ?: return
        val spell = s.active ?: return
        s.active = null
        when (spell) {
            Spell.YANG_SHIELD -> setCooldown(player, spell, num(spell, "cooldown").toInt())
            Spell.YANG_GLIDE -> {
                setCooldown(player, spell, num(spell, "cooldown").toInt())
                s.dirty = true
                if (!player.isOnGround) {
                    player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 40, 0, false, false, true))
                }
            }
            else -> if (cancelled) setCooldown(player, spell, 10)
        }
        ArtNet.anim(player, Anim.NONE, 0)
    }

    private fun releaseCharge(player: ServerPlayerEntity, s: State, spell: Spell) {
        val held = (GearTasks.now - s.start).toInt()
        val min = num(spell, "charge_min").toInt()
        val max = num(spell, "charge_max").toInt().coerceAtLeast(min + 1)
        s.active = null
        Focus.end(player)
        if (held < min) {
            ArtNet.anim(player, Anim.NONE, 0)
            setCooldown(player, spell, 10)
            GearCombat.actionbar(player, "hha_arius.msg.art.fizzle")
            return
        }
        val charge = ((held - min).toFloat() / (max - min)).coerceIn(0f, 1f)
        val c = cost(spell, charge)
        if (!Resonance.spend(player, c)) {
            ArtNet.anim(player, Anim.NONE, 0)
            GearCombat.actionbar(player, "hha_arius.msg.need_resonance", c.toInt(), Resonance.get(player).toInt())
            return
        }
        ArtNet.anim(player, Anim.CAST_RELEASE, ArtTimings.CAST_RELEASE, spell.school.ordinal)
        setCooldown(player, spell, num(spell, "cooldown").toInt())
        if (charge >= 0.99f) Focus.reverb(player)
        val world = player.entityWorld
        when (spell) {
            Spell.GALE -> gale(player, world, charge)
            Spell.YANG_ORB -> yangOrb(player, world, charge)
            Spell.GLACIER_LANCE -> glacierLance(player, world, charge)
            else -> {}
        }
    }

    private fun releasePlace(player: ServerPlayerEntity, s: State, spell: Spell) {
        s.active = null
        Focus.end(player)
        val world = player.entityWorld
        val target = Placement.target(world, player, spell)
        // Brücke: Kosten wachsen mit der Größe (16 × 3 = Grundkosten, bis zum Fünffachen).
        val need = cost(spell, 0f) * if (spell == Spell.YANG_BRIDGE) {
            (BridgeShape.length(player.uuid) * BridgeShape.width(player.uuid) / 48f).coerceIn(1f, 5f)
        } else 1f
        val allowed = target?.cells?.filter { canBuild(player, world, it) }
        if (target == null || (spell != Spell.FROST_FIELD && allowed.isNullOrEmpty())) {
            ArtNet.anim(player, Anim.NONE, 0)
            GearCombat.actionbar(player, "hha_arius.msg.art.no_target")
            return
        }
        if (!Resonance.spend(player, need)) {
            ArtNet.anim(player, Anim.NONE, 0)
            GearCombat.actionbar(player, "hha_arius.msg.need_resonance", need.toInt(), Resonance.get(player).toInt())
            return
        }
        setCooldown(player, spell, num(spell, "cooldown").toInt())
        ArtNet.anim(player, Anim.CAST_RELEASE, ArtTimings.CAST_RELEASE, spell.school.ordinal)
        when (spell) {
            Spell.ICE_WALL -> build(world, allowed!!, Constructs.FROST, num(spell, "ticks").toInt(), stagger = { p -> p.y - allowed.minOf { it.y } })
            Spell.YANG_PLATFORM -> {
                build(world, allowed!!, Constructs.YANG, num(spell, "ticks").toInt()) { 0 }
                val list = platforms.getOrPut(player.uuid) { ArrayDeque() }
                list.addLast(allowed)
                while (list.size > num(spell, "max").toInt().coerceAtLeast(1)) clear(world, list.removeFirst())
            }
            Spell.YANG_BRIDGE -> {
                // Seit 1.4.2 eine steinerne Bogenbrücke in Yang-Weiß ([BridgeShape]): Fahrbahn über die eingestellte
                // Breite, Brüstung mit Lichtpfosten, Bögen und Pfeiler bis zum Boden; Länge/Breite per Mausrad.
                val path = Placement.bridgePath(world, player) ?: allowed!!
                val ticks = num(spell, "ticks").toInt()
                val side = Placement.bridgeSide(player)
                val width = BridgeShape.width(player.uuid)
                val plan = BridgeShape.plan(path, side, width) { canBuild(player, world, it) }
                for ((pos, block, order) in plan.parts) {
                    val place = {
                        if (world.getBlockState(pos).isAir) {
                            world.setBlockState(pos, block.defaultState)
                            world.scheduleBlockTick(pos, block, ticks)
                        }
                    }
                    if (order <= 0) place() else GearTasks.later(order, place)
                }
                val a0 = Vec3d.ofCenter(path.first()).add(0.0, 0.5, 0.0)
                val a1 = Vec3d.ofCenter(path.last()).add(0.0, 0.5, 0.0)
                ArtFx.send(world, FxKind.BRIDGE, a0, a1.subtract(a0), a = width.toFloat(), life = ticks)
            }
            Spell.FROST_FIELD -> frostField(player, world, target.center)
            else -> {}
        }
    }

    private fun canBuild(player: ServerPlayerEntity, world: ServerWorld, pos: BlockPos): Boolean =
        player.canModifyBlocks() && world.canEntityModifyAt(player, pos) && world.getBlockState(pos).isAir

    /** Setzt Bau-Blöcke (nur in Luft) mit eigenem Ablauf-Tick. [stagger] = Verzögerung je Block (Wachsen). */
    private fun build(world: ServerWorld, cells: List<BlockPos>, block: Block, life: Int, stagger: (BlockPos) -> Int) {
        for (pos in cells) {
            val place = {
                if (world.getBlockState(pos).isAir) {
                    world.setBlockState(pos, block.defaultState)
                    world.scheduleBlockTick(pos, block, life)
                }
            }
            val delay = stagger(pos)
            if (delay <= 0) place() else GearTasks.later(delay) { place() }
        }
        val c = cells.first()
        val sound = if (block === Constructs.FROST) SoundEvents.BLOCK_GLASS_PLACE else SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME
        world.playSound(null, c, sound, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.3f)
    }

    private fun clear(world: ServerWorld, cells: List<BlockPos>) {
        for (pos in cells) if (world.isChunkLoaded(pos) && Constructs.isConstruct(world.getBlockState(pos))) world.removeBlock(pos, false)
    }

    // ------------------------------------------------------------------------------------------------- Ticks

    private fun tickAll() {
        // Selbst-Zustand schicken (gebündelt, höchstens alle 4 Ticks).
        for ((id, s) in states) {
            if (!s.dirty || GearTasks.now - s.lastSync < 4) continue
            val player = GearCombat.player(id) ?: continue
            sync(player, s)
        }
    }

    private fun tickPlayer(player: ServerPlayerEntity) {
        val s = states[player.uuid] ?: return
        if (s.noFall && player.isOnGround && GearTasks.now - s.start > 3) s.noFall = false
        if (s.leapAirborne && player.isOnGround) s.leapAirborne = false
        if (s.absorbUntil in 1..GearTasks.now) endAbsorb(player)
        val spell = s.active ?: return
        if (!holdsMagic(player)) {
            Focus.cancel(player)
            return
        }
        val age = GearTasks.now - s.start
        when (spell.kind) {
            CastKind.CHARGE -> if (age > num(spell, "charge_max") + 40) release(player)
            CastKind.PLACE -> if (age > 200) Focus.cancel(player)
            CastKind.CHANNEL -> {
                val drain = num(spell, "drain").toFloat() / 20f
                if (age > num(spell, "max_ticks") || !Resonance.spend(player, drain)) {
                    stopActive(player, cancelled = false)
                    Focus.end(player)
                }
            }
            CastKind.TOGGLE -> tickGlide(player, s, age)
            else -> {}
        }
    }

    private fun tickGlide(player: ServerPlayerEntity, s: State, age: Long) {
        player.fallDistance = 0.0
        val drain = num(Spell.YANG_GLIDE, "drain").toFloat() / 20f
        val stop = age > num(Spell.YANG_GLIDE, "max_ticks") || player.isSneaking || player.isTouchingWater ||
            (age > 6 && player.isOnGround) || !Resonance.spend(player, drain) || !constructsAllowed(player)
        if (stop) {
            stopActive(player, cancelled = false)
            Focus.end(player)
        }
    }

    fun isGliding(player: ServerPlayerEntity): Boolean = states[player.uuid]?.active == Spell.YANG_GLIDE

    private fun sync(player: ServerPlayerEntity, s: State) {
        s.dirty = false
        s.lastSync = GearTasks.now
        if (!ServerPlayNetworking.canSend(player, ArtSelfS2C.ID)) return
        val now = GearTasks.now
        val cds = IntArray(Spell.entries.size) { (s.cooldownUntil[it] - now).coerceIn(0, 100000).toInt() }
        ServerPlayNetworking.send(
            player,
            ArtSelfS2C(
                selected(player).ordinal, previous(player).ordinal, cds,
                SolvaneArt.cooldownLeft(player), WhiteHole.cooldownLeft(player), s.active == Spell.YANG_GLIDE,
                IntArray(Spell.entries.size) { cost(Spell.entries[it], 0f).toInt() },
            ),
        )
    }

    fun markDirty(player: ServerPlayerEntity) {
        st(player).dirty = true
    }

    // ------------------------------------------------------------------------------------------------- Techniken

    private fun eyeStart(player: ServerPlayerEntity): Vec3d = player.eyePos.add(0.0, -0.2, 0.0).add(player.rotationVector.multiply(0.5))

    private fun damage(player: ServerPlayerEntity, target: LivingEntity, amount: Float): Boolean {
        val world = player.entityWorld
        val ok = target.damage(world, world.damageSources.indirectMagic(player, player), amount)
        if (ok) Resonance.markCombat(player)
        return ok
    }

    /** Sofort-Techniken. false = nichts passiert (keine Kosten). */
    private fun tap(player: ServerPlayerEntity, world: ServerWorld, spell: Spell): Boolean {
        when (spell) {
            Spell.WIND_BLADE -> {
                val dmg = num(spell, "damage").toFloat()
                Bolts.launch(Bolts.Bolt(player.uuid, world, eyeStart(player), player.rotationVector.multiply(1.6), num(spell, "range"), 0.6, 1, School.WIND,
                    onHit = { p, t, _ ->
                        if (damage(p, t, dmg) && !GearCombat.isHeavy(t)) GearCombat.push(t, p.entityPos, 0.3, 0.1)
                    }))
                GearSounds.play(world, player.entityPos, "art.wind_blade", SoundEvents.ENTITY_BREEZE_SHOOT, 0.7f, 1.4f)
            }
            Spell.TORNADO -> tornado(player, world)
            Spell.WIND_LEAP -> {
                val s = st(player)
                if (s.leapAirborne && !player.isOnGround) return false
                val look = player.rotationVector
                val flat = Vec3d(look.x, 0.0, look.z).let { if (it.lengthSquared() < 1e-6) it else it.normalize() }
                val f = num(spell, "forward")
                Fx.launchPlayer(player, Vec3d(flat.x * f, num(spell, "up"), flat.z * f))
                player.fallDistance = 0.0
                s.noFall = true
                s.leapAirborne = !player.isOnGround
                s.start = GearTasks.now
                GearVfx.trail(world, Vfx.Style.STORM, player, 0.4f, 12)
                GearVfx.shockwave(world, Vfx.Style.STORM, player.entityPos, 2.2f, 8, 0.6f)
                GearSounds.play(world, player.entityPos, "art.wind_leap", SoundEvents.ENTITY_BREEZE_JUMP, 0.8f, 1.2f)
            }
            Spell.WIND_PULL -> {
                val r = num(spell, "radius")
                val box = Box.of(player.entityPos, r * 2, r * 2, r * 2)
                val items = world.getEntitiesByClass(ItemEntity::class.java, box) { it.squaredDistanceTo(player) <= r * r } +
                    world.getEntitiesByClass(ExperienceOrbEntity::class.java, box) { it.squaredDistanceTo(player) <= r * r }
                for (i in 0 until 14) {
                    GearTasks.later(i + 1) {
                        if (!GearCombat.stillThere(player, world)) return@later
                        for (e in items) {
                            if (e.isRemoved) continue
                            val d = player.entityPos.add(0.0, 0.5, 0.0).subtract(e.entityPos)
                            if (d.lengthSquared() < 0.4) continue
                            e.velocity = d.normalize().multiply(0.45)
                            e.velocityDirty = true
                        }
                    }
                }
                if (player.isOnFire) player.extinguish()
                ArtFx.send(world, FxKind.PULL, player.entityPos, a = r.toFloat(), life = 14, e1 = player.id)
                GearSounds.play(world, player.entityPos, "art.wind_pull", SoundEvents.ENTITY_BREEZE_INHALE, 0.7f, 1.3f)
            }
            Spell.FROST_SHARDS -> {
                val dmg = num(spell, "damage").toFloat()
                val slow = num(spell, "slow_ticks").toInt()
                val look = player.rotationVector
                for (k in -1..1) {
                    val a = Math.toRadians(7.0 * k)
                    val dir = Vec3d(look.x * cos(a) - look.z * sin(a), look.y, look.x * sin(a) + look.z * cos(a)).normalize()
                    Bolts.launch(Bolts.Bolt(player.uuid, world, eyeStart(player), dir.multiply(1.4), num(spell, "range"), 0.35, 0, School.ICE,
                        onHit = { p, t, _ ->
                            if (damage(p, t, dmg)) t.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, slow, 0, false, true, true), p)
                        }))
                }
                GearSounds.play(world, player.entityPos, "art.frost_shards", SoundEvents.BLOCK_GLASS_BREAK, 0.5f, 1.7f)
            }
            Spell.ICE_ARMOR -> {
                val absorb = num(spell, "absorb").toFloat()
                val s = st(player)
                endAbsorb(player)
                val inst = player.getAttributeInstance(EntityAttributes.MAX_ABSORPTION) ?: return false
                inst.removeModifier(ABSORB_ID)
                inst.addTemporaryModifier(EntityAttributeModifier(ABSORB_ID, absorb.toDouble(), EntityAttributeModifier.Operation.ADD_VALUE))
                player.absorptionAmount = maxOf(player.absorptionAmount, absorb)
                s.absorbGranted = absorb
                s.absorbUntil = GearTasks.now + num(spell, "ticks").toLong()
                ArtFx.send(world, FxKind.FROST_SHELL, player.entityPos, life = 20, e1 = player.id)
                GearSounds.play(world, player.entityPos, "art.ice_armor", SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.4f)
            }
            else -> return false
        }
        return true
    }

    private fun endAbsorb(player: ServerPlayerEntity) {
        val s = states[player.uuid] ?: return
        if (s.absorbUntil == 0L) return
        s.absorbUntil = 0
        player.getAttributeInstance(EntityAttributes.MAX_ABSORPTION)?.removeModifier(ABSORB_ID)
        // Was vom Eispanzer übrig ist, schmilzt; andere Absorption (z. B. Goldapfel) bleibt bis zur Obergrenze.
        player.absorptionAmount = player.absorptionAmount.coerceAtMost(player.maxAbsorption)
        s.absorbGranted = 0f
    }

    private fun gale(player: ServerPlayerEntity, world: ServerWorld, charge: Float) {
        val spell = Spell.GALE
        val range = num(spell, "range")
        val look = player.rotationVector.normalize()
        val eye = player.eyePos
        val dmg = (num(spell, "damage") * (0.5 + 0.5 * charge)).toFloat()
        val knock = num(spell, "knock") * (0.4 + 0.6 * charge)
        for (e in world.getOtherEntities(player, player.boundingBox.expand(range))) {
            val to = e.boundingBox.center.subtract(eye)
            if (to.length() > range || to.normalize().dotProduct(look) < cos(Math.toRadians(45.0))) continue
            val los = world.raycast(RaycastContext(eye, e.boundingBox.center, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player))
            if (los.type == HitResult.Type.BLOCK) continue
            when {
                e is ProjectileEntity && GearCombat.isHostileProjectile(player, e) -> GearCombat.discardProjectile(world, e)
                GearCombat.canHit(player, e) -> {
                    val t = e as LivingEntity
                    damage(player, t, dmg)
                    if (!GearCombat.isHeavy(t)) GearCombat.push(t, player.entityPos, if (t is ServerPlayerEntity) knock * 0.6 else knock, 0.2 + 0.2 * charge)
                }
            }
        }
        ArtFx.send(world, FxKind.GALE, eye.add(look.multiply(0.6)).add(0.0, -0.3, 0.0), look.multiply(range), a = charge, life = 12)
        world.spawnParticles(ParticleTypes.GUST, eye.x + look.x * 2, eye.y + look.y * 2, eye.z + look.z * 2, 3, 0.6, 0.4, 0.6, 0.0)
        GearSounds.play(world, player.entityPos, "art.gale", SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST.value(), 0.9f, 1.0f)
    }

    /**
     * Wirbelsturm: zieht aus der Hand geradeaus über den Boden (0,22 Blöcke je Tick), saugt Wesen im Radius an, hebt sie
     * hoch und dreht sie, kleiner Schaden alle 10 Ticks; Geschosse im Wirbel werden zerrissen.
     */
    private fun tornado(player: ServerPlayerEntity, world: ServerWorld) {
        val spell = Spell.TORNADO
        val ticks = num(spell, "ticks").toInt()
        val r = num(spell, "radius")
        val dmg = num(spell, "damage").toFloat()
        val look = player.rotationVector.let { Vec3d(it.x, 0.0, it.z) }.let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        var pos = player.entityPos.add(look.multiply(2.5))
        ArtFx.send(world, FxKind.TORNADO, pos, look.multiply(0.22), a = r.toFloat(), life = ticks)
        GearSounds.play(world, pos, "art.gale", SoundEvents.ENTITY_BREEZE_CHARGE, 1f, 0.7f)
        for (t in 1..ticks) {
            GearTasks.later(t) {
                if (!GearCombat.stillThere(player, world)) return@later
                // Bodenhöhe folgen
                var ground = BlockPos.ofFloored(pos.x, pos.y + 1.0, pos.z)
                repeat(4) { if (world.getBlockState(ground.down()).isAir) ground = ground.down() }
                repeat(4) { if (!world.getBlockState(ground).isAir) ground = ground.up() }
                pos = Vec3d(pos.x + look.x * 0.22, ground.y.toDouble(), pos.z + look.z * 0.22)
                for (e in world.getOtherEntities(player, Box.of(pos.add(0.0, 2.0, 0.0), r * 2, 5.0, r * 2))) {
                    if (e is ProjectileEntity && GearCombat.isHostileProjectile(player, e)) { GearCombat.discardProjectile(world, e); continue }
                    if (!GearCombat.canHit(player, e)) continue
                    val target = e as LivingEntity
                    val to = pos.subtract(target.entityPos).let { Vec3d(it.x, 0.0, it.z) }
                    val d = to.length()
                    if (d > r) continue
                    if (!GearCombat.isHeavy(target)) {
                        val swirl = Vec3d(-to.z, 0.0, to.x).normalize()
                        target.velocity = target.velocity.multiply(0.6).add(to.normalize().multiply(0.12)).add(swirl.multiply(0.25)).add(0.0, 0.16, 0.0)
                        target.velocityDirty = true
                        if (target is ServerPlayerEntity) Fx.launchPlayer(target, target.velocity)
                    }
                    if (t % 10 == 0) damage(player, target, dmg)
                }
                if (t % 20 == 0) GearSounds.play(world, pos, "art.gale", SoundEvents.ENTITY_BREEZE_IDLE_GROUND, 0.7f, 0.8f)
            }
        }
    }

    /**
     * Gletscherlanze: ein riesiger, aufgeladener Eisspeer, durchschlägt alle Ziele auf der Bahn; am Ende zerbirst er in
     * eine Frostexplosion (Radius 3–5, halber Schaden) und streut Eissplitter.
     */
    private fun glacierLance(player: ServerPlayerEntity, world: ServerWorld, charge: Float) {
        val spell = Spell.GLACIER_LANCE
        val dmg = (num(spell, "damage") + (num(spell, "damage_full") - num(spell, "damage")) * charge).toFloat()
        val burst = 3.0 + 2.0 * charge
        Bolts.launch(Bolts.Bolt(player.uuid, world, eyeStart(player), player.rotationVector.multiply(1.9), num(spell, "range"), 0.7 + 0.35 * charge, 99, School.ICE,
            onHit = { p, t, _ ->
                if (damage(p, t, dmg)) t.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2, false, true, true), p)
            },
            onEnd = { p, at ->
                if (p != null) {
                    for (e in world.getOtherEntities(p, Box.of(at, burst * 2, burst * 2, burst * 2))) {
                        if (e.squaredDistanceTo(at) > burst * burst || !GearCombat.canHit(p, e)) continue
                        damage(p, e as LivingEntity, dmg * 0.5f)
                    }
                }
                GearVfx.nova(world, Vfx.Style.STORM, at, burst.toFloat(), 12)
                GearVfx.shockwave(world, Vfx.Style.STORM, at, burst.toFloat() + 1f, 12, 1f)
                world.spawnParticles(net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.PACKED_ICE.defaultState), at.x, at.y, at.z, 40, burst * 0.3, 0.6, burst * 0.3, 0.2)
                GearSounds.play(world, at, "art.yang_burst", SoundEvents.BLOCK_GLASS_BREAK, 1.2f, 0.7f)
            }))
        GearSounds.play(world, player.entityPos, "art.wind_blade", SoundEvents.ITEM_TRIDENT_THROW.value(), 1f, 0.6f)
    }

    private fun yangOrb(player: ServerPlayerEntity, world: ServerWorld, charge: Float) {
        val spell = Spell.YANG_ORB
        val dmg = (num(spell, "damage") + (num(spell, "damage_full") - num(spell, "damage")) * charge).toFloat()
        val radius = 2.0 + charge
        Bolts.launch(Bolts.Bolt(player.uuid, world, eyeStart(player), player.rotationVector.multiply(1.2), num(spell, "range"), 0.45 + 0.2 * charge, 0, School.YANG,
            onHit = { _, _, _ -> },
            onEnd = { p, at ->
                if (p != null) {
                    for (e in world.getOtherEntities(p, Box.of(at, radius * 2, radius * 2, radius * 2))) {
                        if (e.squaredDistanceTo(at) > radius * radius || !GearCombat.canHit(p, e)) continue
                        damage(p, e as LivingEntity, dmg)
                    }
                }
                GearVfx.nova(world, Vfx.Style.HEAVEN, at, radius.toFloat(), 10)
                GearSounds.play(world, at, "art.yang_burst", SoundEvents.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 1.5f)
            }))
        GearSounds.play(world, player.entityPos, "art.yang_orb", SoundEvents.BLOCK_BEACON_POWER_SELECT, 0.5f, 1.8f)
    }

    private fun frostField(player: ServerPlayerEntity, world: ServerWorld, center: Vec3d) {
        val spell = Spell.FROST_FIELD
        val r = Placement.FIELD_RADIUS
        val ticks = num(spell, "ticks").toInt()
        ArtFx.send(world, FxKind.FIELD, center, a = r.toFloat(), life = ticks)
        // Wasser in der Fläche friert zu brüchigem Eis (setzt sich selbst zurück, siehe FrostIce).
        val base = BlockPos.ofFloored(center).down()
        val ri = r.toInt()
        for (dx in -ri..ri) for (dz in -ri..ri) for (dy in -1..1) {
            if (dx * dx + dz * dz > r * r) continue
            FrostIce.place(world, base.add(dx, dy, dz))
        }
        var t = 0
        while (t < ticks) {
            GearTasks.later(t + 1) {
                if (player.isRemoved) return@later
                for (e in world.getOtherEntities(player, Box.of(center, r * 2, 3.0, r * 2))) {
                    if (e.squaredDistanceTo(center) > r * r + 1 || !GearCombat.canHit(player, e)) continue
                    (e as LivingEntity).addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 25, 1, false, true, true), player)
                }
            }
            t += 10
        }
        GearSounds.play(world, center, "art.frost_field", SoundEvents.BLOCK_POWDER_SNOW_PLACE, 1f, 0.8f)
    }

    /**
     * Yang-Schild: nur Treffer von vorn (±60°). Jeder abgefangene Treffer kostet Resonanz (2 je Schadenspunkt); reicht
     * sie nicht, bricht der Schild und der Treffer geht durch. Explosionen und „ignoriert Schilde“ gehen immer durch.
     */
    private fun shieldBlocks(player: ServerPlayerEntity, source: DamageSource, amount: Float): Boolean {
        val s = states[player.uuid] ?: return false
        if (s.active != Spell.YANG_SHIELD) return false
        if (source.isIn(DamageTypeTags.BYPASSES_SHIELD) || source.isIn(DamageTypeTags.IS_EXPLOSION)) return false
        val from = source.position ?: return false
        val look = player.rotationVector
        val dir = from.subtract(player.eyePos)
        val flat = Vec3d(dir.x, 0.0, dir.z)
        val lookFlat = Vec3d(look.x, 0.0, look.z)
        if (flat.lengthSquared() < 1e-6 || lookFlat.lengthSquared() < 1e-6) return false
        if (flat.normalize().dotProduct(lookFlat.normalize()) < cos(Math.toRadians(ArtTimings.SHIELD_HALF_ANGLE))) return false
        val price = amount * 2f
        val world = player.entityWorld
        if (!Resonance.spend(player, price)) {
            stopActive(player, cancelled = false)
            Focus.end(player)
            GearSounds.play(world, player.entityPos, "art.shield_break", SoundEvents.BLOCK_GLASS_BREAK, 1f, 0.8f)
            return false
        }
        val direct = source.source
        if (direct is ProjectileEntity) GearCombat.discardProjectile(world, direct)
        GearSounds.play(world, player.entityPos, "art.shield_hit", SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, 1f, 1.4f)
        return true
    }

    fun spellName(spell: Spell): net.minecraft.text.Text = net.minecraft.text.Text.translatable("hha_arius.spell.${spell.key}")
}

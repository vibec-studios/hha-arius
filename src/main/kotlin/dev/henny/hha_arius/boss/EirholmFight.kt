package dev.henny.hha_arius.boss

import dev.henny.hha.entity.boss.PuppetEntity
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha_arius.AriusEntities
import dev.henny.hha_arius.HhaArius
import dev.henny.hha_arius.arena.ArenaBuild
import dev.henny.hha_arius.arena.ArenaLayout
import dev.henny.hha_arius.gear.GearRewards
import dev.henny.hha_arius.net.AriusNet
import dev.henny.hha_arius.net.FxKind
import dev.henny.hha_arius.net.FxS2C
import dev.henny.hha_arius.net.LineS2C
import dev.henny.hha_arius.net.MusicS2C
import net.minecraft.entity.Entity
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Eine Begegnung im Heiligtum von Eirholm (eine Arena-Instanz). Hält Teilnehmer, Leben, Leisten, Ansagen
 * ([Telegraph]), Blockänderungen ([ArenaEdits]) und den Ablauf. Die Angriffe stehen in [AriusMoves] und [NivarisMoves],
 * die großen Momente in [Scenes].
 *
 * @param training 0 = echter Kampf, 1–4 = Training des Abschnitts (Tempel, Gletscher, Herz, Finale)
 */
class EirholmFight(
    val world: ServerWorld,
    val slot: Int,
    summoner: ServerPlayerEntity,
    invited: Collection<ServerPlayerEntity>,
    val training: Int,
    /** 0 = Arena steht schon, 1 = bauen, 2 = leeren und neu bauen. */
    private val buildMode: Int,
    /** Das eingesetzte Siegel (leer im Training/bei Admin-Start): Sieg verbraucht es, sonst kommt es zurück. */
    private var sealStake: net.minecraft.item.ItemStack = net.minecraft.item.ItemStack.EMPTY,
) {
    val origin: BlockPos = ArenaLayout.origin(slot)

    /** Eindeutige Kennung dieser Begegnung (für den gespeicherten Einsatz). */
    val encounterId: String = UUID.randomUUID().toString()
    val server: net.minecraft.server.MinecraftServer = world.server!!
    val summonerId: UUID = summoner.uuid

    // --- Teilnehmer ---------------------------------------------------------------------------------------------------

    class Fighter(val id: UUID) {
        var ko = false
        val dealt = ArrayDeque<Pair<Long, Float>>()
        val hitsOutside = ArrayDeque<Long>()
        val arrowHits = ArrayDeque<Long>()
        val backHits = ArrayDeque<Long>()
        val blocked = ArrayDeque<Long>()
        var lastNearArius = 0L
        var totalDamage = 0f
    }

    val fighters = LinkedHashMap<UUID, Fighter>()

    init {
        fighters[summoner.uuid] = Fighter(summoner.uuid)
        for (p in invited) fighters.putIfAbsent(p.uuid, Fighter(p.uuid))
    }

    /** Wer beim Start dabei war (für die Lebensskalierung). */
    var startCount = 1
        private set

    /** Seit 1.10.0: Spielerzahl hat sich dauerhaft geändert (siehe [PlayerScaling]). */
    fun rescaleCount(n: Int) {
        startCount = n.coerceIn(1, 6)
    }

    /** Seit 1.10.0: Leben und Schaden folgen der Zahl der Kämpfenden. */
    val scaling = PlayerScaling(this)

    fun fighter(player: ServerPlayerEntity): Fighter? = fighters[player.uuid]

    fun isParticipant(player: ServerPlayerEntity): Boolean = player.uuid in fighters

    /** Kämpfende Spieler: online, in der Arena, lebendig, nicht k. o., kein Zuschauer. */
    fun players(): List<ServerPlayerEntity> = fighters.values.mapNotNull { f ->
        if (f.ko) return@mapNotNull null
        val p = server.playerManager.getPlayer(f.id) ?: return@mapNotNull null
        if (p.entityWorld !== world || !p.isAlive || p.isSpectator) null else p
    }

    /** Alle, die die Arena sehen (auch k. o.): Untertitel, Musik, Leisten. */
    fun audience(): List<ServerPlayerEntity> = fighters.keys.mapNotNull { id ->
        server.playerManager.getPlayer(id)?.takeIf { it.entityWorld === world }
    }

    // --- Zustand ---------------------------------------------------------------------------------------------------------

    object Phase {
        const val BUILD = 0
        const val INTRO = 1
        const val TEMPLE = 2
        const val CUT = 3
        const val GLACIER = 4
        const val COLLAPSE = 5
        const val VAULT = 6
        const val FINALE = 7
        const val END = 8
    }

    /** Seit 1.7.0: Arius jagt die Spieler den Spiralberg hinauf (Supermodus, keine Enderperlen). */
    var chasing = false

    /** Seit 1.7.0: das Finale spielt auf der Gipfelschulter des Massivs. */
    var onSummit = false

    /** Mitte der Gipfelschulter (absolut, Laufhöhe). */
    val summit: Vec3d
        get() = abs(Vec3d(ArenaLayout.SPIRE.x + 0.5, dev.henny.hha_arius.arena.FarPlan.SHOULDER_Y + 1.0, ArenaLayout.SPIRE.z + 0.5))

    /**
     * Landeplätze im Finale: auf dem Feld oder auf dem Gipfel. Seit 1.9.0 am Rand der Gipfelarena auf der Seite, von der
     * die Jagd ankommt (letzter Punkt von [dev.henny.hha_arius.arena.FarPlan.huntWaypoints]), nicht mitten darin.
     */
    fun finaleSpots(): List<Vec3d> {
        if (!onSummit) return ArenaLayout.VAULT_LANDING
        val c = summitCenterRel
        val gate = dev.henny.hha_arius.arena.FarPlan.huntWaypoints().lastOrNull()?.let { Vec3d(it.x + 0.5, c.y, it.z + 0.5) }
        val toGate = gate?.subtract(c)?.let { Vec3d(it.x, 0.0, it.z) }?.takeIf { it.lengthSquared() > 1e-4 }?.normalize() ?: Vec3d(0.0, 0.0, 1.0)
        val side = Vec3d(-toGate.z, 0.0, toGate.x)
        return (0 until 6).map { i -> c.add(toGate.multiply(9.0 + (i / 3) * 1.6)).add(side.multiply(((i % 3) - 1) * 2.2)) }
    }

    /** Mitte der Gipfelarena (relativ, Laufhöhe): nördlich des Horns auf der Gipfelschulter. */
    val summitCenterRel: Vec3d
        get() = Vec3d(ArenaLayout.SPIRE.x + 0.5, dev.henny.hha_arius.arena.FarPlan.SHOULDER_Y + 1.0, ArenaLayout.SPIRE.z + 0.5 - 19.0)

    var phase = Phase.BUILD
        private set
    var phaseAge = 0
        private set
    var ended = false
        private set
    private var build: ArenaBuild? = null

    /** Fernlandschaft (Nadel, Schlucht, Gletscher): baut im Hintergrund weiter, bis sie steht. */
    private var far: dev.henny.hha_arius.arena.FarBuild? = null

    /** Steht die Fernlandschaft (für den Auftritt von Nivaris an der Nadel)? */
    val farReady: Boolean get() = far == null
    val farProgress: Float get() = far?.progress ?: 1f
    private var noPlayers = 0

    val calls: Telegraph.Fight = Telegraph.open(world, "arius", Vfx.Style.HEAVEN) { players() }
    val edits = ArenaEdits(world)

    var arius: AriusEntity? = null
        private set
    var nivaris: NivarisEntity? = null
        private set

    /** Unsichtbare Kopf-Trefferbox, folgt dem Kopf. */
    var nivarisHead: NivarisHeadEntity? = null
        private set

    // Leben je Abschnitt (der Kampf verwaltet sie, nicht die Entities).
    var ariusHp = 1f
    var ariusMax = 1f
    var dragonHp = 1f
    var dragonMax = 1f
    var dragonDowned = false

    /** Treffer zählen: Arius bzw. Nivaris kann gerade Schaden nehmen. */
    var ariusVulnerable = false
    var dragonVulnerable = false

    // Fenster (goldene Rune): bis zu diesem Welt-Tick offen.
    var ariusOpenUntil = 0L
    var dragonOpenUntil = 0L

    /** Schaden in Ariusʼ/Nivarisʼ laufendem Fenster (Taktbruch). */
    var ariusOpenDamage = 0f
    var dragonOpenDamage = 0f

    /** Haltungen: Ende in Welt-Ticks; Parierhaltung löst bei Nahkampf die Sequenz aus. */
    var guardUntil = 0L
    var guardWhite = false
    var guardTriggered = false
    var arrowCutUntil = 0L

    /** Atemholen: Schaden an Kehle/Kopf während des Einatmens. */
    var inhaling = false
    var chokeDamage = 0f

    /** Abschnitt III: wer führt. */
    var ariusLeads = true
    var taktBroken = false

    /** Seit wann das Duellziel feststeht. */
    var duelTarget: UUID? = null
    private var announcedTarget: UUID? = null

    /** Anpassung („Er liest dich“): aktive Art und bis wann; Sperre danach. */
    enum class Adapt { NONE, ARROWS, BACK, SHIELD }

    var adapt = Adapt.NONE
    var adaptUntil = 0L
    var adaptBlockedUntil = 0L

    /** Hat irgendwer in der Gruppe k. o. gegeben? */
    var anyKo = false

    /** Hat jemand den Schnitt ohne Treffer überstanden (je Spieler). */
    val cutSurvivors = HashSet<UUID>()

    /** Eiskämme (Nivaris' Atem), in Reihenfolge des Entstehens. */
    class Ridge(val blocks: List<BlockPos>, val from: Vec3d, val to: Vec3d)

    val ridges = ArrayList<Ridge>()

    /** Säulen (Mitte, absolut), die schon zerbrochen sind. */
    val brokenPillars = HashSet<BlockPos>()

    private var director: Runner? = null
    private val tasks = ArrayList<Runner>()

    val moves = AriusMoves(this)

    /** Sichtbares Eis ohne Kollision (Dornen, Splitter, Gletscherschollen), siehe [Shards]. */
    val shards = Shards(this)
    val dragon = NivarisMoves(this)
    val scenes = Scenes(this)

    private val ariusBar = ServerBossBar(Text.translatable("hha_arius.bar.arius"), BossBar.Color.WHITE, BossBar.Style.NOTCHED_10)
    private val dragonBar = ServerBossBar(Text.translatable("hha_arius.bar.nivaris"), BossBar.Color.BLUE, BossBar.Style.NOTCHED_10)

    private val forced = ArrayList<ChunkPos>()

    private var lastCombatLine = -100_000L

    val now: Long get() = world.time

    // --- Geometrie -----------------------------------------------------------------------------------------------------

    fun abs(rel: Vec3d): Vec3d = ArenaLayout.at(origin, rel)
    fun abs(rel: BlockPos): BlockPos = ArenaLayout.at(origin, rel)

    /** Laufhöhe des aktuellen Schauplatzes. */
    val floorY: Double
        get() = if (onSummit) summit.y else if (phase >= Phase.VAULT || (phase == Phase.COLLAPSE && phaseAge > 60)) origin.y + ArenaLayout.VAULT.y.toDouble() else origin.y.toDouble()

    val arenaCenter: Vec3d
        get() = when {
            phase <= Phase.CUT -> abs(Vec3d.of(ArenaLayout.TEMPLE)).add(0.5, 0.0, 0.5)
            // Seit 1.8.0 kämpft Solvane in ihrem eigenen Horst im Westen (Crix: größere Arena für den Drachen).
            phase <= Phase.COLLAPSE -> abs(Vec3d.of(ArenaLayout.AERIE)).add(0.5, 0.0, 0.5)
            onSummit -> abs(summitCenterRel)
            else -> abs(Vec3d.of(ArenaLayout.VAULT)).add(0.5, 0.0, 0.5)
        }

    val arenaRadius: Double
        get() = when {
            phase <= Phase.CUT -> ArenaLayout.TEMPLE_R + 10.0 // offenes Klingengrab (seit 1.4.2)
            phase == Phase.GLACIER -> ArenaLayout.AERIE_R - 3.0 // Drachenhorst (seit 1.8.0)
            phase <= Phase.COLLAPSE -> 600.0 // Ritt auf Solvanes Rücken
            chasing -> 600.0
            onSummit -> 13.0 // Gipfelschulter nördlich des Horns (seit 1.8.0 größer, Schulter r 34)
            else -> ArenaLayout.VAULT_R + 8.0 // offenes Feld (seit 1.4.2; wächst mit der Arena)
        }

    /** Hält einen Punkt in der laufenden Arena. */
    fun clampToArena(pos: Vec3d, margin: Double = 1.0): Vec3d {
        val c = arenaCenter
        val dx = pos.x - c.x
        val dz = pos.z - c.z
        val d = sqrt(dx * dx + dz * dz)
        val r = arenaRadius - margin
        return if (d <= r || d < 1e-6) pos else Vec3d(c.x + dx / d * r, pos.y, c.z + dz / d * r)
    }

    // --- Start ---------------------------------------------------------------------------------------------------------

    fun begin() {
        forceChunks(true)
        build = if (buildMode > 0) ArenaBuild(world, origin, buildMode == 2) else null
        run {
            val st = AriusState.get(server)
            @Suppress("SENSELESS_COMPARISON")
            if (st.data.farBuilt == null) st.data.farBuilt = HashMap()
            if (st.data.farBuilt[slot.toString()] != dev.henny.hha_arius.arena.FarPlan.VERSION) far = dev.henny.hha_arius.arena.FarBuild(world, origin)
        }
        for (p in invitedPlayers()) {
            p.sendMessage(Text.translatable("hha_arius.msg.forming").formatted(Formatting.AQUA), true)
        }
        if (training == 0) {
            val state = AriusState.get(server)
            state.data.dirtySlots.add(slot)
            state.save()
        }
    }

    private fun invitedPlayers(): List<ServerPlayerEntity> =
        fighters.keys.mapNotNull { server.playerManager.getPlayer(it) }

    private fun forceChunks(on: Boolean) {
        if (on) {
            val minX = (origin.x - ArenaLayout.BOUND_XZ) shr 4
            val maxX = (origin.x + ArenaLayout.BOUND_XZ) shr 4
            val minZ = (origin.z + ArenaLayout.BOUND_Z_MIN) shr 4
            val maxZ = (origin.z + ArenaLayout.BOUND_XZ) shr 4
            for (x in minX..maxX) for (z in minZ..maxZ) {
                if (world.setChunkForced(x, z, true)) forced.add(ChunkPos(x, z))
            }
        } else {
            for (c in forced) world.setChunkForced(c.x, c.z, false)
            forced.clear()
        }
    }

    /** Arena fertig: alle hinein. */
    private fun enter() {
        val list = invitedPlayers()
        startCount = list.size.coerceIn(1, 6)
        val arrival = abs(ArenaLayout.ARRIVAL)
        for ((i, p) in list.withIndex()) {
            AriusState.rememberHome(p)
            Encounters.arriving.add(p.uuid)
            val offset = Vec3d(((i % 3) - 1) * 1.5, 0.0, (i / 3) * 1.5)
            p.teleport(world, arrival.x + offset.x, arrival.y, arrival.z + offset.z, emptySet(), 180f, 0f, false)
            p.onLanding()
            Encounters.arriving.remove(p.uuid)
        }
        spawnArius(abs(ArenaLayout.SEAT), 0f)
        arius?.play(AriusPose.SEATED)
        arius?.sheathed = false
        when (training) {
            0, 1 -> startDirector(scenes.intro())
            2 -> startDirector(scenes.trainingGlacier())
            3 -> startDirector(scenes.trainingVault())
            else -> startDirector(scenes.trainingFinale())
        }
    }

    fun startDirector(script: Script) {
        director = Runner(script)
    }

    fun task(script: Script) {
        tasks.add(Runner(script))
    }

    fun setPhase(next: Int) {
        // Grundstimmung je Abschnitt; Szenen können danach eigene Stimmungen setzen.
        val mood = when (next) {
            Phase.INTRO, Phase.TEMPLE, Phase.CUT -> dev.henny.hha_arius.net.AtmosMode.CLEAR
            Phase.GLACIER -> dev.henny.hha_arius.net.AtmosMode.AERIE
            Phase.VAULT, Phase.FINALE -> dev.henny.hha_arius.net.AtmosMode.VAULT
            else -> -2
        }
        if (mood >= 0 && mood != currentAtmos) atmos(mood, 80)
        phase = next
        phaseAge = 0
        calls.reset()
        guardUntil = 0
        arrowCutUntil = 0
        inhaling = false
        updateBars()
    }

    // --- Entities ------------------------------------------------------------------------------------------------------

    fun spawnArius(pos: Vec3d, yaw: Float) {
        arius?.discard()
        val e = AriusEntities.ARIUS.create(world, SpawnReason.EVENT) ?: return
        e.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0f)
        e.headYaw = yaw
        e.bodyYaw = yaw
        e.damageHandler = BossDamageHandler { boss, source, amount -> onBossDamaged(boss, source, amount) }
        world.spawnEntity(e)
        arius = e
    }

    fun spawnNivaris(pos: Vec3d, yaw: Float) {
        nivaris?.discard()
        val e = AriusEntities.NIVARIS.create(world, SpawnReason.EVENT) ?: return
        e.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0f)
        e.headYaw = yaw
        e.bodyYaw = yaw
        e.damageHandler = BossDamageHandler { boss, source, amount -> onBossDamaged(boss, source, amount) }
        world.spawnEntity(e)
        nivaris = e
        nivarisHead?.discard()
        val head = AriusEntities.NIVARIS_HEAD.create(world, SpawnReason.EVENT) ?: return
        head.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0f)
        head.damageHandler = BossDamageHandler { _, source, amount -> onBossDamaged(head, source, amount) }
        world.spawnEntity(head)
        nivarisHead = head
    }

    /**
     * Der Drache ist fort (Finale: Solvane kehrt als Klinge in die Scheide zurück). Entfernt Körper und Kopf-Trefferbox
     * ohne Ersatz durch [ensureEntities].
     */
    fun dismissNivaris() {
        nivaris?.discard()
        nivaris = null
        nivarisHead?.discard()
        nivarisHead = null
    }

    /** Kopf-Trefferbox an den Kopf setzen. */
    private fun followHead() {
        val d = nivaris ?: return
        val h = nivarisHead ?: return
        if (h.isRemoved) {
            nivarisHead = null
            return
        }
        val p = dragon.anchorPos(NivarisMoves.Zone.HEAD)
        place(h, Vec3d(p.x, p.y - 0.95, p.z))
        h.health = h.maxHealth
    }

    /** Verwaiste oder zerstörte Darsteller ersetzen (z. B. nach /kill). */
    private fun ensureEntities() {
        arius?.let {
            if (it.isRemoved) {
                val pos = it.entityPos; val pose = it.pose; val form = it.form; val blade = it.bladeState; val sheathed = it.sheathed
                spawnArius(pos, it.yaw); arius?.form = form; arius?.bladeState = blade; arius?.sheathed = sheathed; arius?.play(pose)
            }
        }
        nivaris?.let { if (it.isRemoved) { val pos = it.entityPos; val fly = it.flying; spawnNivaris(pos, it.yaw); nivaris?.flying = fly } }
        arius?.let { it.health = it.maxHealth }
        nivaris?.let { it.health = it.maxHealth }
        followHead()
    }

    // --- Tick ----------------------------------------------------------------------------------------------------------

    fun tick() {
        if (ended) return
        calls.during { tickInner() }
    }

    private fun tickInner() {
        phaseAge++
        val b = build
        if (phase == Phase.BUILD && b == null) {
            setPhase(Phase.INTRO)
            enter()
            return
        }
        if (phase == Phase.BUILD && b != null) {
            val done = b.step(BossConfig.buildBudget())
            if (world.time % 10L == 0L) {
                val pct = (b.progress * 100).toInt()
                for (p in invitedPlayers()) p.sendMessage(Text.translatable("hha_arius.msg.forming_pct", pct).formatted(Formatting.AQUA), true)
            }
            if (done) {
                build = null
                setPhase(Phase.INTRO)
                enter()
            }
            return
        }
        far?.let { fb ->
            if (fb.step(BossConfig.buildBudget() / 3)) {
                far = null
                val st = AriusState.get(server)
                st.data.farBuilt[slot.toString()] = dev.henny.hha_arius.arena.FarPlan.VERSION
                st.save()
            }
        }
        ensureEntities()
        pruneFighters()
        val active = players()
        if (active.isEmpty()) {
            if (++noPlayers > 400) {
                defeat(silent = true)
                return
            }
        } else {
            noPlayers = 0
        }
        updateDuelTarget(active)
        if (phase >= Phase.TEMPLE && phase < Phase.END) scaling.tick(active.size)
        director?.step()
        if (ended) return
        // Aus einer Momentaufnahme ausführen: Tasks dürfen neue Tasks anlegen oder den Kampf beenden.
        if (tasks.isNotEmpty()) {
            for (runner in tasks.toList()) {
                if (ended) return
                if (!runner.step()) tasks.remove(runner)
            }
        }
        if (ended) return
        shards.tick()
        watchHealing(active)
        arius?.let { if (it.open && now >= ariusOpenUntil) it.open = false }
        if (world.time % 5L == 0L) updateBars()
        // In der Jagd hält der Sturm die Spieler auf der Route (Hunt.keepOnRoute), nicht der Arenaboden.
        if (!chasing) keepInArena(active)
    }

    /**
     * Seit 1.9.0 (Wukong): Wer mitten im Kampf trinkt oder isst, den bemerkt Arius und bestraft ihn ([AriusMoves.healPunish]).
     * Nur in den Abschnitten, in denen er selbst kämpft, und nicht in Szenen.
     */
    private fun watchHealing(active: List<ServerPlayerEntity>) {
        if (!ariusVulnerable || chasing) return
        if (phase != Phase.TEMPLE && phase != Phase.VAULT && phase != Phase.FINALE) return
        for (p in active) {
            if (!p.isUsingItem || p.itemUseTime !in 1..8) continue
            if (!p.activeItem.contains(net.minecraft.component.DataComponentTypes.CONSUMABLE)) continue
            val a = arius ?: return
            if (p.squaredDistanceTo(a) > 30.0 * 30.0) continue
            moves.noticeHeal(p)
        }
    }

    /** Wer aus der Arena fällt (z. B. über den Rand), wird zurückgesetzt. */
    private fun keepInArena(active: List<ServerPlayerEntity>) {
        for (p in active) {
            if (p.y < floorY - 12 && phase != Phase.COLLAPSE) {
                val spot = landingSpot(p)
                p.teleport(world, spot.x, spot.y, spot.z, emptySet(), p.yaw, p.pitch, false)
                p.onLanding()
            }
        }
    }

    fun landingSpot(player: ServerPlayerEntity): Vec3d {
        val list = if (phase >= Phase.VAULT) ArenaLayout.VAULT_LANDING else if (phase >= Phase.GLACIER) ArenaLayout.AERIE_LANDING else listOf(ArenaLayout.ARRIVAL)
        val i = fighters.keys.indexOf(player.uuid).coerceAtLeast(0)
        return abs(list[i % list.size])
    }

    private fun pruneFighters() {
        // Spieler, die die Welt verlassen haben, bleiben Teilnehmer (Rückkehr möglich), zählen aber nicht als aktiv.
    }

    private fun updateDuelTarget(active: List<ServerPlayerEntity>) {
        if (active.isEmpty()) {
            duelTarget = null
            return
        }
        val current = duelTarget?.let { id -> active.firstOrNull { it.uuid == id } }
        if (current != null && world.time % 20L != 0L) return
        val since = now - 200
        val best = active.maxByOrNull { p ->
            val f = fighters[p.uuid] ?: return@maxByOrNull 0.0
            f.dealt.removeIf { it.first < since }
            f.dealt.sumOf { it.second.toDouble() } - (arius?.squaredDistanceTo(p) ?: 0.0) * 0.001
        } ?: return
        duelTarget = best.uuid
    }

    /** Duellziel, nur zwischen zwei Angriffen wechseln (die Angriffe rufen das zu Beginn ab). */
    fun target(): ServerPlayerEntity? {
        val list = players()
        val chosen = list.firstOrNull { it.uuid == duelTarget } ?: list.minByOrNull { p -> arius?.squaredDistanceTo(p) ?: 0.0 }
        if (chosen != null && chosen.uuid != announcedTarget && list.size > 1) {
            announcedTarget = chosen.uuid
            chosen.sendMessage(Text.translatable("hha_arius.msg.fixed").formatted(Formatting.AQUA), true)
        }
        return chosen
    }

    fun nearestTo(pos: Vec3d): ServerPlayerEntity? = players().minByOrNull { it.squaredDistanceTo(pos) }
    fun farthestFrom(pos: Vec3d): ServerPlayerEntity? = players().maxByOrNull { it.squaredDistanceTo(pos) }

    // --- Leisten ---------------------------------------------------------------------------------------------------

    fun updateBars() {
        val viewers = audience()
        val showArius = phase in Phase.TEMPLE..Phase.CUT || phase == Phase.VAULT || phase == Phase.FINALE
        val showDragon = phase == Phase.GLACIER || (phase == Phase.VAULT && !dragonDowned)
        sync(ariusBar, viewers, showArius)
        sync(dragonBar, viewers, showDragon)
        ariusBar.percent = (ariusHp / ariusMax).coerceIn(0f, 1f)
        dragonBar.percent = (dragonHp / dragonMax).coerceIn(0f, 1f)
        val lead = phase == Phase.VAULT && !dragonDowned
        ariusBar.name = Text.literal(if (lead && ariusLeads) "▶ " else "").append(Text.translatable(if (arius?.form == AriusForm.WHITE) "hha_arius.bar.arius_heir" else "hha_arius.bar.arius"))
        dragonBar.name = Text.literal(if (lead && !ariusLeads) "▶ " else "").append(Text.translatable("hha_arius.bar.nivaris"))
        ariusBar.color = if (arius?.form == AriusForm.WHITE) BossBar.Color.WHITE else BossBar.Color.BLUE
    }

    private fun sync(bar: ServerBossBar, viewers: List<ServerPlayerEntity>, visible: Boolean) {
        val want = if (visible) viewers.toSet() else emptySet()
        for (p in bar.players.toList()) if (p !in want) bar.removePlayer(p)
        for (p in want) if (p !in bar.players) bar.addPlayer(p)
    }

    // --- Treffer am Boss ---------------------------------------------------------------------------------------------

    private fun attackerOf(source: DamageSource): ServerPlayerEntity? {
        val a = source.attacker
        return if (a is ServerPlayerEntity && a.uuid in fighters) a else null
    }

    fun onBossDamaged(boss: PuppetEntity, source: DamageSource, amount: Float): Float {
        val player = attackerOf(source) ?: return 0f
        if (fighters[player.uuid]?.ko == true || Hold.isHeld(player)) return 0f
        val projectile = source.source is ProjectileEntity
        return when {
            boss === arius -> hitArius(player, source, amount, projectile)
            boss === nivaris -> hitDragon(player, source, amount, projectile, if (projectile) null else DragonAim.takeZone(player))
            boss === nivarisHead -> hitDragon(player, source, amount, projectile, NivarisMoves.Zone.HEAD)
            else -> 0f
        }
    }

    private fun hitArius(player: ServerPlayerEntity, source: DamageSource, amount: Float, projectile: Boolean): Float {
        val a = arius ?: return 0f
        if (phase == Phase.GLACIER || phase == Phase.CUT || phase == Phase.COLLAPSE) {
            player.sendMessage(Text.translatable("hha_arius.msg.watching").formatted(Formatting.GRAY), true)
            return 0f
        }
        if (!ariusVulnerable) return 0f
        // Seit 1.10.0: Stille-Duell auf dem Gipfel (Klingenkreuzen statt Schaden) und Befreien aus der Konter-Bestrafung.
        if (moves.summit.onAriusHit(player, projectile)) return 0f
        val f = fighters[player.uuid]
        val front = isInFront(a, source.position ?: player.entityPos)
        // Konterhaltung: Nahkampf wird pariert und mit einer Kombo bestraft (seit 1.9.0, Wukong).
        if (!projectile && now < guardUntil && !guardTriggered && player.squaredDistanceTo(a) < 5.0 * 5.0) {
            guardTriggered = true
            moves.counter(player, guardWhite)
            return 0f
        }
        // Pfeilschnitt: von vorn kommende Geschosse zerschneidet er (White Null: die Bahn ist getrennt).
        if (projectile && front && (now < guardUntil || now < arrowCutUntil)) {
            moves.cutProjectile(source.source)
            return 0f
        }
        val open = now < ariusOpenUntil
        // Während der Konter-Kombo ist er mit dem Opfer beschäftigt: Mitspieler treffen ihn voll (seit 1.10.0, Befreien).
        var dmg = amount * (if (open) 1.5f else if (moves.countering) 0.9f else 0.7f)
        if (open) ariusOpenDamage += dmg
        dmg = moves.summit.limit(dmg)
        dmg = min(dmg, ariusHp + 1f)
        ariusHp -= dmg
        record(player, dmg)
        moves.onAllyHit(player, dmg)
        if (f != null) {
            if (projectile) push(f.arrowHits, 400)
            if (!projectile && !front) push(f.backHits, 300)
            if (!open) {
                push(f.hitsOutside, 40)
                if (f.hitsOutside.size >= 3) {
                    f.hitsOutside.clear()
                    if (!projectile && player.squaredDistanceTo(a) < 5.0 * 5.0) moves.reactTo(player, a.form == AriusForm.WHITE) else moves.sidestepFrom(player)
                }
            }
        }
        checkAdapt(f)
        return dmg
    }

    private fun hitDragon(player: ServerPlayerEntity, source: DamageSource, amount: Float, projectile: Boolean, forced: NivarisMoves.Zone?): Float {
        val d = nivaris ?: return 0f
        if (!dragonVulnerable || dragonDowned && phase == Phase.VAULT) return 0f
        val zone = forced ?: dragon.zoneOf(player, source, projectile)
        val zoneFactor = when (zone) {
            NivarisMoves.Zone.HEAD -> if (d.pose == NivarisPose.DOWNED) 2f else if (!d.flying) 1.5f else 1f
            NivarisMoves.Zone.THROAT -> 1.2f
            NivarisMoves.Zone.BODY -> 1f
            NivarisMoves.Zone.TAIL -> 0.5f
        }
        val open = now < dragonOpenUntil
        var dmg = amount * zoneFactor * (if (open) 1.5f else 1f)
        if (inhaling && (zone == NivarisMoves.Zone.THROAT || zone == NivarisMoves.Zone.HEAD)) chokeDamage += dmg
        if (open) dragonOpenDamage += dmg
        dmg = min(dmg, dragonHp + 1f)
        dragonHp -= dmg
        record(player, dmg)
        return dmg
    }

    private fun record(player: ServerPlayerEntity, dmg: Float) {
        val f = fighters[player.uuid] ?: return
        f.dealt.addLast(now to dmg)
        f.totalDamage += dmg
    }

    private fun push(queue: ArrayDeque<Long>, window: Int) {
        queue.addLast(now)
        while (queue.isNotEmpty() && queue.first() < now - window) queue.removeFirst()
    }

    private fun isInFront(e: Entity, from: Vec3d): Boolean {
        val yaw = Math.toRadians(e.yaw.toDouble())
        val fx = -sin(yaw)
        val fz = cos(yaw)
        val dx = from.x - e.x
        val dz = from.z - e.z
        return dx * fx + dz * fz > 0.0
    }

    /** Schildblock eines Spielers gegen Arius zählen (Anpassung „Schildmauer“). */
    fun onShieldBlock(player: ServerPlayerEntity) {
        val f = fighters[player.uuid] ?: return
        push(f.blocked, 400)
        checkAdapt(f)
    }

    private fun checkAdapt(f: Fighter?) {
        if (f == null || phase != Phase.TEMPLE || ariusHp > ariusMax * 0.4f) return
        if (adapt != Adapt.NONE && now < adaptUntil) return
        if (adapt != Adapt.NONE) {
            adapt = Adapt.NONE
            adaptBlockedUntil = now + 1200
        }
        if (now < adaptBlockedUntil) return
        val next = when {
            f.arrowHits.size >= 8 -> Adapt.ARROWS
            f.backHits.size >= 5 -> Adapt.BACK
            f.blocked.size >= 6 -> Adapt.SHIELD
            else -> return
        }
        adapt = next
        adaptUntil = now + 600
        f.arrowHits.clear(); f.backHits.clear(); f.blocked.clear()
        for (p in players()) p.sendMessage(Text.translatable("hha_arius.adapt.${next.name.lowercase()}").formatted(Formatting.AQUA), true)
        if (next == Adapt.ARROWS) arrowCutUntil = now + 120
    }

    // --- Treffer an Spielern -------------------------------------------------------------------------------------------

    /** Schaden an einem Teilnehmer. Gehaltene Spieler und Spieler in der Schonfrist bleiben verschont. */
    fun hurt(player: ServerPlayerEntity, base: Float, kind: Damage.Kind = Damage.Kind.SOLVANE, attacker: Entity? = arius): Float {
        if (player.uuid !in fighters || fighters[player.uuid]?.ko == true || player.entityWorld !== world) return 0f
        if (Hold.isHeld(player) || Hold.inGrace(player)) return 0f
        // Seit 1.6.5: Bodenangriffe treffen niemanden, der hoch über der Arena ist (Crix: „hitten dich auch 100 Blöcke über ihm“).
        if (!chasing && player.y - floorY > 12.0) return 0f
        if (phase == Phase.CUT && kind == Damage.Kind.NULL_CUT) cutSurvivors.remove(player.uuid)
        val dealt = Damage.hit(player, attacker, kind, base, BossConfig.damageFor(scaling.count))
        // Frostaufbau wie in Elden Ring (Erfrierung): Atem baut am stärksten auf, Schläge und Aufpralle weniger.
        if (dealt > 0f) {
            val factor = when (kind) {
                Damage.Kind.FROST_BREATH -> 3.0f
                Damage.Kind.PRESSURE -> 2.0f
                Damage.Kind.SOLVANE -> 1.6f
                Damage.Kind.IMPACT -> 1.3f
                Damage.Kind.NULL_CUT -> 0f
            }
            dev.henny.hha_arius.gear.Frostbite.add(player, base * factor)
        }
        return dealt
    }

    /** Treffer, der zu einer Sequenz gehört (darf den gehaltenen Spieler treffen). */
    fun hurtHeld(player: ServerPlayerEntity, base: Float, kind: Damage.Kind = Damage.Kind.IMPACT): Float {
        if (!canSequence(player)) return 0f
        return Hold.hitting { Damage.hit(player, arius, kind, base, BossConfig.damageFor(scaling.count)) }
    }

    /** Darf eine laufende Sequenz diesen Spieler weiter führen (nicht k. o., noch in dieser Arena, Kampf läuft)? */
    fun canSequence(player: ServerPlayerEntity): Boolean =
        !ended && player.isAlive && !player.isRemoved && player.entityWorld === world && fighters[player.uuid]?.ko == false

    fun grounded(player: ServerPlayerEntity): Boolean = player.isOnGround || player.y <= floorY + 0.4

    // --- Fenster ---------------------------------------------------------------------------------------------------------

    fun openArius(ticks: Int) {
        val a = arius ?: return
        ariusOpenUntil = now + ticks
        ariusOpenDamage = 0f
        a.open = true
        Vfx.rune(world, Vfx.Style.GOLD, Vec3d(a.x, floorAt(a) + 0.05, a.z), 1.6f, ticks, a)
    }

    fun openDragon(ticks: Int) {
        val d = nivaris ?: return
        dragonOpenUntil = now + ticks
        dragonOpenDamage = 0f
        Vfx.rune(world, Vfx.Style.GOLD, Vec3d(d.x, floorAt(d) + 0.05, d.z), 3.2f, ticks, d)
    }

    fun floorAt(e: Entity): Double = if (e.y < floorY + 3 && e.y > floorY - 3) e.y else floorY

    // --- Bewegung --------------------------------------------------------------------------------------------------------

    fun face(e: Entity, at: Vec3d) {
        val dx = at.x - e.x
        val dz = at.z - e.z
        if (dx * dx + dz * dz < 1e-4) return
        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        e.yaw = yaw
        e.headYaw = yaw
        if (e is PuppetEntity) e.bodyYaw = yaw
    }

    /** Richtung des Blicks (waagerecht, normiert). */
    fun facing(e: Entity): Vec3d {
        val yaw = Math.toRadians(e.yaw.toDouble())
        return Vec3d(-sin(yaw), 0.0, cos(yaw))
    }

    /** Setzt die Position (Darsteller ohne Physik). */
    fun place(e: Entity, pos: Vec3d) {
        e.setPosition(pos.x, pos.y, pos.z)
    }

    /**
     * Ein Schritt in Richtung [to] auf der Laufhöhe [y] mit [speed] Blöcken je Tick; weicht Säulen seitlich aus.
     * Gibt true zurück, wenn das Ziel erreicht ist.
     */
    fun stepToward(e: Entity, to: Vec3d, speed: Double, y: Double = floorY, faceIt: Boolean = true): Boolean {
        val dx = to.x - e.x
        val dz = to.z - e.z
        val d = sqrt(dx * dx + dz * dz)
        if (d <= speed) {
            if (passable(Vec3d(to.x, y, to.z))) place(e, Vec3d(to.x, y, to.z))
            return true
        }
        val base = atan2(dz, dx)
        for (off in doubleArrayOf(0.0, 0.6, -0.6, 1.2, -1.2, 1.7, -1.7)) {
            val a = base + off
            val next = Vec3d(e.x + cos(a) * speed, y, e.z + sin(a) * speed)
            if (passable(next)) {
                place(e, clampToArena(next, 0.5))
                if (faceIt) face(e, if (off == 0.0) to else next)
                return false
            }
        }
        return false
    }

    /** Zwei Blöcke Luft an dieser Stelle? */
    fun passable(pos: Vec3d): Boolean {
        val feet = BlockPos.ofFloored(pos.x, pos.y + 0.1, pos.z)
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty &&
            world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty
    }

    // --- Effekte, Töne, Texte -------------------------------------------------------------------------------------------

    fun fx(kind: FxKind, pos: Vec3d, dir: Vec3d = Vec3d.ZERO, a: Float = 0f, b: Float = 0f, life: Int = 20, e1: Entity? = null, e2: Entity? = null) {
        AriusNet.near(world, pos, FxS2C(kind.ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life, e1?.id ?: -1, e2?.id ?: -1))
    }

    /** Effekt an alle Zuschauer unabhängig von der Entfernung (Auren, lange Begleiteffekte). */
    fun fxAll(kind: FxKind, pos: Vec3d, dir: Vec3d = Vec3d.ZERO, a: Float = 0f, b: Float = 0f, life: Int = 20, e1: Entity? = null, e2: Entity? = null) {
        val payload = FxS2C(kind.ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life, e1?.id ?: -1, e2?.id ?: -1)
        for (p in audience()) AriusNet.send(p, payload)
    }

    fun soundEvent(id: String): SoundEvent? = Registries.SOUND_EVENT.get(HhaArius.id(id))

    /** Kampfklang mit Telegraph-Budget (höchstens zwei je Tick). */
    fun sound(id: String, at: Vec3d, volume: Float = 2.5f, pitch: Float = 1f) {
        val event = soundEvent(id) ?: return
        calls.sound(event, at, volume, pitch)
        layers(id, at, volume, pitch)
    }

    /** Klang außerhalb des Budgets (Szenen, Umgebung). */
    fun ambient(id: String, at: Vec3d, volume: Float = 3f, pitch: Float = 1f) {
        val event = soundEvent(id) ?: return
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)
        layers(id, at, volume, pitch)
    }

    /**
     * Seit 1.8.0 (Crix: „gib Solvane Sounds, wenn sie brüllt, ein richtiges Brüllen“): Drachenklänge bekommen tiefe
     * Schichten aus Vanilla-Klängen (Enderdrache, Wärter, Verwüster, Brise), gemeinsam mit dem eigenen Klang abgespielt.
     */
    fun layers(id: String, at: Vec3d, volume: Float, pitch: Float) {
        val extra = when (id) {
            "nivaris.roar" -> listOf("nivaris.roar_body", "nivaris.roar_low")
            "nivaris.wing" -> listOf("nivaris.wing_body")
            "nivaris.breath" -> listOf("nivaris.breath_body")
            "nivaris.inhale" -> listOf("nivaris.inhale_body")
            "nivaris.land", "nivaris.crash", "nivaris.tail", "nivaris.claw" -> listOf("nivaris.hit_body")
            else -> return
        }
        for (x in extra) {
            val e = soundEvent(x) ?: continue
            world.playSound(null, at.x, at.y, at.z, e, SoundCategory.HOSTILE, volume * 1.2f, pitch)
        }
    }

    /** Laufende Kampfmusik (für Wiedereinsteiger). */
    private var currentMusic = ""

    /** Laufende Wetter-/Lichtstimmung (für Wiedereinsteiger), siehe [dev.henny.hha_arius.net.AtmosMode]. */
    private var currentAtmos = dev.henny.hha_arius.net.AtmosMode.CLEAR

    /** Wetter und Licht für alle Teilnehmer überblenden. */
    fun atmos(mode: Int, ticks: Int = 60) {
        currentAtmos = mode
        val payload = dev.henny.hha_arius.net.AtmosS2C(mode, ticks)
        for (p in audience()) AriusNet.send(p, payload)
    }

    fun music(id: String?, fade: Int = 20) {
        currentMusic = id?.let { HhaArius.id(it).toString() } ?: ""
        val payload = MusicS2C(currentMusic, fade)
        for (p in audience()) AriusNet.send(p, payload)
    }

    /**
     * Zeile von Arius (Untertitel + optionale Stimme). [combat] = mitten im Kampf: dann höchstens alle 45 s und nicht,
     * solange für jemanden eine Boss-Ansage läuft.
     */
    fun say(id: String, ticks: Int = 80, combat: Boolean = false, to: Collection<ServerPlayerEntity> = audience()): Boolean {
        if (combat) {
            if (now - lastCombatLine < 900) return false
            if (players().any { calls.hasBossTelegraph(it) }) return false
            lastCombatLine = now
        }
        val payload = LineS2C(1, "hha_arius.line.$id", id, ticks)
        for (p in to) AriusNet.send(p, payload)
        return true
    }

    fun sceneText(id: String, ticks: Int = 70, to: Collection<ServerPlayerEntity> = audience()) {
        val payload = LineS2C(0, "hha_arius.scene.$id", "", ticks)
        for (p in to) AriusNet.send(p, payload)
    }

    fun hint(key: String, color: Formatting = Formatting.AQUA) {
        for (p in players()) p.sendMessage(Text.translatable(key).formatted(color), true)
    }

    // --- k. o., Niederlage, Sieg ---------------------------------------------------------------------------------------

    /** Statt zu sterben: k. o. (Totem wirkt normal). Gibt true zurück, wenn der Tod verhindert wurde. */
    fun tryKnockOut(player: ServerPlayerEntity): Boolean {
        val f = fighters[player.uuid] ?: return false
        if (player.mainHandStack.isOf(Items.TOTEM_OF_UNDYING) || player.offHandStack.isOf(Items.TOTEM_OF_UNDYING)) return false
        Hold.drop(player)
        player.health = max(6f, player.maxHealth * 0.3f)
        player.extinguish()
        player.setFrozenTicks(0)
        player.addStatusEffect(StatusEffectInstance(StatusEffects.REGENERATION, 200, 1))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 100, 4))
        if (training == 0) {
            f.ko = true
            anyKo = true
            val watch = abs(if (phase >= Phase.VAULT) ArenaLayout.WATCH_VAULT else if (phase >= Phase.GLACIER) ArenaLayout.AERIE_WATCH else ArenaLayout.WATCH_UPPER)
            player.teleport(world, watch.x, watch.y, watch.z, emptySet(), player.yaw, player.pitch, false)
            player.onLanding()
            player.sendMessage(Text.translatable("hha_arius.msg.ko").formatted(Formatting.GRAY), false)
            if (players().isEmpty()) defeat(silent = false)
        } else {
            val spot = landingSpot(player)
            player.teleport(world, spot.x, spot.y, spot.z, emptySet(), player.yaw, player.pitch, false)
        }
        return true
    }

    /** Neuer Abschnitt: wer k. o. war, steigt wieder ein. */
    fun reviveAll(spots: List<Vec3d>) {
        var i = 0
        for (f in fighters.values) {
            if (!f.ko) continue
            val p = server.playerManager.getPlayer(f.id) ?: continue
            if (p.entityWorld !== world) continue
            f.ko = false
            val spot = abs(spots[i++ % spots.size])
            p.teleport(world, spot.x, spot.y, spot.z, emptySet(), p.yaw, p.pitch, false)
            p.onLanding()
            p.sendMessage(Text.translatable("hha_arius.msg.rejoin").formatted(Formatting.AQUA), true)
        }
    }

    /** Ein Spieler betritt die Arena-Welt während des Kampfes (Nachzügler, Rückkehr nach Disconnect). */
    fun onJoin(player: ServerPlayerEntity) {
        val f = fighters.getOrPut(player.uuid) { Fighter(player.uuid) }
        Hold.drop(player)
        if (phase == Phase.BUILD) return
        f.ko = true
        val watch = abs(if (phase >= Phase.VAULT) ArenaLayout.WATCH_VAULT else if (phase >= Phase.GLACIER) ArenaLayout.AERIE_WATCH else ArenaLayout.WATCH_UPPER)
        player.teleport(world, watch.x, watch.y, watch.z, emptySet(), player.yaw, player.pitch, false)
        player.sendMessage(Text.translatable("hha_arius.msg.latejoin").formatted(Formatting.AQUA), false)
        if (currentMusic.isNotEmpty()) AriusNet.send(player, MusicS2C(currentMusic, 20))
        AriusNet.send(player, dev.henny.hha_arius.net.AtmosS2C(currentAtmos, 1))
        MusicCue.rejoin(this, player)
    }

    fun onLeave(player: ServerPlayerEntity) {
        Hold.drop(player)
        AriusNet.send(player, MusicS2C("", 20))
        AriusNet.send(player, dev.henny.hha_arius.net.AtmosS2C(-1, 1))
        ariusBar.removePlayer(player)
        dragonBar.removePlayer(player)
        if (players().isEmpty() && phase != Phase.BUILD && !ended) {
            // Der letzte aktive Spieler ist weg: kurz warten (Rückkehr möglich), dann bricht tickInner ab.
        }
    }

    fun defeat(silent: Boolean) {
        if (ended) return
        if (!silent) say("defeat", 90)
        val everyone = audience()
        finish(win = false)
        for (p in everyone) {
            p.sendMessage(Text.translatable("hha_arius.msg.defeat").formatted(Formatting.GRAY), false)
            AriusState.sendHome(p)
        }
    }

    /** Sieg: Beute, Fortschritte, Siegel verbrauchen, nach 20 s nach Hause. */
    fun victory() {
        if (ended || training != 0) {
            if (training != 0) {
                hint("hha_arius.msg.training_done")
                task(script {
                    wait(100)
                    val everyone = audience()
                    finish(win = false)
                    for (p in everyone) AriusState.sendHome(p)
                })
            }
            return
        }
        // Das Siegel ist verbraucht. Jeder Teilnehmer bekommt jetzt einen gespeicherten Beuteanspruch; ausgezahlt wird
        // erst am Heimatort (oder beim nächsten Einloggen), damit nichts im Aufräumen der Arena verloren geht.
        val flawless = !anyKo
        val state = AriusState.get(server)
        // Verbraucht ist das Siegel erst, wenn der Beuteanspruch dieser Begegnung beim Beschwörer zugestellt ist: Dann
        // löscht die Zustellung den Einsatzvermerk im selben Schreibvorgang (Spielerdatei). Rollt ein Absturz den Sieg
        // zurück, erstattet das Einloggen den Einsatz.
        state.markWon(encounterId)
        sealStake = net.minecraft.item.ItemStack.EMPTY
        for (id in fighters.keys) {
            val data = state.player(id)
            data.pendingRewards.add(AriusState.Claim(firstWin = data.wins == 0, flawless = flawless, encounter = encounterId))
            data.wins++
        }
        state.save()
        for (p in audience()) {
            Advancements.grant(p, "white_blade")
            if (flawless) Advancements.grant(p, "no_storm")
        }
        // Seit 1.10.0: Niemand wird hinausgeworfen. Die Rückkehrtore leuchten, nach 20 s räumt der Kampf auf und alle
        // dürfen bleiben und sich umsehen; die Beute gibt es beim Durchschreiten eines Rückkehrtors.
        dev.henny.hha_arius.portal.Portals.lightReturn(world, origin)
        task(script {
            wait(400)
            val everyone = audience()
            finish(win = true)
            for (p in everyone) Encounters.stayAfterVictory(p, slot)
        })
    }

    /** Aufräumen: Leisten, Darsteller, Musik, Blöcke, Chunks. */
    fun finish(win: Boolean) {
        if (ended) return
        ended = true
        phase = Phase.END
        music(null, 40)
        for (p in audience()) Hold.drop(p)
        ariusBar.clearPlayers()
        dragonBar.clearPlayers()
        moves.cleanup()
        shards.clear()
        arius?.discard()
        nivaris?.discard()
        nivarisHead?.discard()
        calls.reset()
        tasks.clear()
        director = null
        edits.restore()
        // Liegengebliebene Items/Geschosse in der Arena entfernen.
        val box = Box(
            (origin.x - ArenaLayout.BOUND_XZ).toDouble(), (origin.y + ArenaLayout.BOUND_Y_MIN).toDouble(), (origin.z + ArenaLayout.BOUND_Z_MIN).toDouble(),
            // bis über den Himmelsbalkon (Abschnitt III spielt hoch an der Nadel)
            (origin.x + ArenaLayout.BOUND_XZ).toDouble(), (origin.y + ArenaLayout.VAULT.y + 30).toDouble(), (origin.z + ArenaLayout.VAULT.z + ArenaLayout.VAULT_R + 8).toDouble(),
        )
        for (e in world.getOtherEntities(null, box) { it !is PlayerEntity }) e.discard()
        forceChunks(false)
        if (training == 0) {
            val state = AriusState.get(server)
            state.data.dirtySlots.remove(slot)
            // Niederlage oder Abbruch: Einsatz zurück (online ins Inventar, sonst beim nächsten Einloggen).
            // Niederlage oder Abbruch: Einsatz zurück. Online: Siegel ins Inventar und Vermerk löschen, beides in der
            // Spielerdatei (also gemeinsam gespeichert). Offline: Der Vermerk bleibt, das Einloggen erstattet.
            if (!win && !sealStake.isEmpty) {
                val owner = server.playerManager.getPlayer(summonerId)
                if (owner != null) {
                    val ledger = Ledgers.of(owner)
                    if (ledger.stake == encounterId) {
                        // Vermerk löschen und Siegel ins Fach: beides Spielerdatei. Das Fach füllt das Inventar, sobald Platz ist.
                        ledger.stake = ""
                        Ledgers.deposit(owner, listOf(sealStake.copy()))
                        Ledgers.flush(owner)
                    }
                }
                sealStake = net.minecraft.item.ItemStack.EMPTY
            }
            state.save()
        }
        Encounters.onFinished(this)
        HhaArius.LOGGER.info("Eirholm instance {} finished (win={})", slot, win)
    }

    /** Verbrauchter Ort für Hilfsrechnungen. */
    fun ringPoint(center: Vec3d, radius: Double, angle: Double): Vec3d =
        Vec3d(center.x + sin(angle) * radius, center.y, center.z + cos(angle) * radius)

    companion object {
        fun id(path: String): Identifier = HhaArius.id(path)
    }
}

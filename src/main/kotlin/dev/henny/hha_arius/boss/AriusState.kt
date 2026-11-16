package dev.henny.hha_arius.boss

import com.google.gson.Gson
import com.mojang.serialization.Codec
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import net.minecraft.world.World
import java.util.UUID

/**
 * Dauerhafter Zustand (in der Oberwelt gespeichert): Rückkehrpunkte, was ein Spieler schon gesehen/gehört hat, Siege,
 * und welche Arena-Slots nach einem Absturz „schmutzig“ sind und neu gebaut werden müssen.
 */
class AriusState(var data: Data = Data()) : PersistentState() {

    class Home(var dim: String = "minecraft:overworld", var x: Double = 0.0, var y: Double = 64.0, var z: Double = 0.0, var yaw: Float = 0f)

    class PlayerData(
        var seenIntro: Boolean = false,
        var wins: Int = 0,
        var heard: MutableSet<String> = HashSet(),
        var home: Home? = null,
        /** Beim nächsten Einloggen nach Hause bringen (Kampf lief nicht mehr). */
        var sendHome: Boolean = false,
        var againIndex: Int = 0,
        var rumorIndex: Int = 0,
        /**
         * Ansprüche (Beute eines Siegs, nicht zustellbares Siegel), je mit eindeutiger ID. Ausgezahlt wird nur, wenn die
         * ID nicht schon im [Ledger] des Spielers quittiert ist; entfernt wird ein Anspruch erst, wenn die Quittung
         * nachweislich mit der Spielerdatei gespeichert war (siehe [dev.henny.hha_arius.boss.Encounters.deliverPending]).
         */
        var pendingRewards: MutableList<Claim> = ArrayList(),
    )

    /** Ein Anspruch: [kind] `loot` (Beute eines Siegs) oder `seal` (zurückzugebendes Siegel). */
    class Claim(
        var id: String = UUID.randomUUID().toString(),
        var kind: String = "loot",
        var firstWin: Boolean = false,
        var flawless: Boolean = false,
    )

    class Data(
        var players: MutableMap<String, PlayerData> = HashMap(),
        var dirtySlots: MutableSet<Int> = HashSet(),
        /** Gewonnene Begegnungen (IDs, neueste zuletzt): ein dort eingesetztes Siegel ist verbraucht. */
        var wonEncounters: MutableList<String> = ArrayList(),
    )

    fun player(id: UUID): PlayerData = data.players.getOrPut(id.toString()) { PlayerData() }.also {
        // Gson kann Felder als null hinterlassen, wenn die Datei von einer älteren Version stammt.
        @Suppress("SENSELESS_COMPARISON")
        if (it.heard == null) it.heard = HashSet()
        @Suppress("SENSELESS_COMPARISON")
        if (it.pendingRewards == null) it.pendingRewards = ArrayList()
    }

    fun save() = markDirty()

    fun isWon(encounter: String): Boolean {
        @Suppress("SENSELESS_COMPARISON")
        if (data.wonEncounters == null) data.wonEncounters = ArrayList()
        return encounter in data.wonEncounters
    }

    fun markWon(encounter: String) {
        isWon(encounter)
        data.wonEncounters.add(encounter)
        while (data.wonEncounters.size > 500) data.wonEncounters.removeAt(0)
        save()
    }

    companion object {
        private val GSON = Gson()
        private val CODEC: Codec<AriusState> = Codec.STRING.xmap(
            { AriusState(runCatching { GSON.fromJson(it, Data::class.java) }.getOrNull() ?: Data()) },
            { GSON.toJson(it.data) },
        )
        private val TYPE = PersistentStateType("hha_arius", { AriusState() }, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

        fun get(server: MinecraftServer): AriusState = server.overworld.persistentStateManager.getOrCreate(TYPE)

        fun rememberHome(player: ServerPlayerEntity) {
            val state = get(player.entityWorld.server ?: return)
            val p = state.player(player.uuid)
            p.home = Home(player.entityWorld.registryKey.value.toString(), player.x, player.y, player.z, player.yaw)
            p.sendHome = false
            state.save()
        }

        /**
         * Rückweg für jemanden, der nicht über das Siegel in die Arena kam (Nachzügler, fremder Teleport): die Welt, aus
         * der er kam, an einer sicheren Stelle (Oberfläche am Weltursprung). Die genaue Herkunft ist nicht mehr bekannt.
         */
        fun rememberFallbackHome(player: ServerPlayerEntity, @Suppress("UNUSED_PARAMETER") from: ServerWorld) {
            val server = player.entityWorld.server ?: return
            val state = get(server)
            // Die Herkunft ist unbekannt (fremder Teleport): sicher ist nur der Welt-Spawn, nie z. B. das Netherdach.
            val spawn = server.spawnPoint
            val world = server.getWorld(spawn.dimension) ?: server.overworld
            val near = spawn.pos.toBottomCenterPos()
            val safe = Hold.safeSpot(world, near) ?: world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, spawn.pos).toBottomCenterPos()
            state.player(player.uuid).home = Home(world.registryKey.value.toString(), safe.x, safe.y, safe.z, spawn.yaw)
            state.save()
        }

        /** Bringt den Spieler an seinen gemerkten Ort (sonst Weltspawn der Oberwelt). */
        fun sendHome(player: ServerPlayerEntity) {
            val server = player.entityWorld.server ?: return
            val state = get(server)
            val home = state.player(player.uuid).home
            val key: RegistryKey<World> = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(home?.dim ?: "minecraft:overworld"))
            val world: ServerWorld = server.getWorld(key) ?: server.overworld
            val pos = if (home != null && world.registryKey == key) Vec3d(home.x, home.y, home.z)
            else world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, BlockPos.ORIGIN).toBottomCenterPos()
            player.onLanding()
            player.teleport(world, pos.x, pos.y, pos.z, emptySet(), home?.yaw ?: player.yaw, player.pitch, false)
            state.player(player.uuid).sendHome = false
            state.save()
        }
    }
}

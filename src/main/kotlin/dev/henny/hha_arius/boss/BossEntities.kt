package dev.henny.hha_arius.boss

import dev.henny.hha.entity.boss.PuppetEntity
import net.minecraft.entity.EntityType
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.mob.MobEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.World
import kotlin.math.hypot

/**
 * Wer einen Boss steuert, entscheidet über Treffer. Gibt den tatsächlich anzurechnenden Schaden zurück (0 = abgewehrt).
 * Ohne Handler (verwaister Boss nach Absturz o. Ä.) verschwindet der Boss beim ersten Treffer.
 */
fun interface BossDamageHandler {
    fun onDamaged(boss: PuppetEntity, source: DamageSource, amount: Float): Float
}

/**
 * Gemeinsame Basis: Darsteller ohne KI (HHA [PuppetEntity]), Pose + Ausholzeit über den DataTracker, auf dem Client
 * Posenwechsel-Zeitpunkt und Gehen für weiche Übergänge.
 */
abstract class EirholmPuppet(type: EntityType<out EirholmPuppet>, world: World) : PuppetEntity(type, world) {

    /** Vom Kampf gesetzt; siehe [BossDamageHandler]. */
    @Transient
    var damageHandler: BossDamageHandler? = null

    // --- nur Client ------------------------------------------------------------------------------------------------
    var previousPose = 0
        private set
    var poseChangedAge = 0
        private set
    private var lastPoseSeen = 0
    var walkPhase = 0f
        private set
    var walkAmount = 0f
        private set
    private var seenX = 0.0
    private var seenZ = 0.0
    private var seen = false

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(poseData(), 0)
        builder.add(windupData(), 0)
        builder.add(serialData(), 0)
    }

    protected abstract fun poseData(): TrackedData<Int>
    protected abstract fun windupData(): TrackedData<Int>

    /** Zähler je [play]; muss nach Pose und Ausholzeit registriert sein (höhere ID = später im Paket). */
    protected abstract fun serialData(): TrackedData<Int>

    /** Aktuelle Animationspose (siehe [AriusPose] bzw. [NivarisPose]). */
    var pose: Int
        get() = dataTracker.get(poseData())
        set(value) = dataTracker.set(poseData(), value)

    /** Ticks vom Posenwechsel bis zum Treffer; 0 = Standardwert aus [AriusTimings]. */
    var windup: Int
        get() = dataTracker.get(windupData())
        set(value) = dataTracker.set(windupData(), value)

    /**
     * Setzt Pose und Ausholzeit gemeinsam und startet die Pose (auch dieselbe) von vorn: Das Modell rechnet ab
     * [poseChangedAge].
     */
    fun play(pose: Int, windup: Int = 0) {
        this.windup = windup
        this.pose = pose
        dataTracker.set(serialData(), dataTracker.get(serialData()) + 1)
    }

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (data == serialData() && entityWorld.isClient) {
            previousPose = lastPoseSeen
            lastPoseSeen = pose
            poseChangedAge = age
        }
    }

    override fun tick() {
        super.tick()
        if (!entityWorld.isClient) {
            MotionSync.track(this)
            return
        }
        // Seit 1.10.0: gepufferte Server-Lage übernehmen (flüssig bei jeder FPS, siehe [MotionSync]).
        MotionSync.clientHook?.invoke(this)
        val moved = if (seen) hypot(x - seenX, z - seenZ) else 0.0
        seenX = x
        seenZ = z
        seen = true
        val pace = if (moved > 1.2) 0.0 else moved
        walkPhase += (pace * 1.3).toFloat()
        walkAmount += ((pace / 0.22).toFloat().coerceAtMost(1f) - walkAmount) * 0.25f
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val handler = damageHandler ?: run {
            discard()
            return false
        }
        val allowed = handler.onDamaged(this, source, amount)
        if (allowed <= 0f) return false
        // Leben verwaltet der Kampf selbst (Leisten je Abschnitt); hier nur Treffer-Rückmeldung (Rotblitz, i-Frames).
        val ok = super.damage(world, source, 0.0001f)
        timeUntilRegen = 0
        return ok
    }

    override fun isInvulnerableTo(world: ServerWorld, source: DamageSource): Boolean = false
}

/** Arius. Siehe [AriusPose] und [AriusForm]. */
class AriusEntity(type: EntityType<out AriusEntity>, world: World) : EirholmPuppet(type, world) {

    override fun poseData(): TrackedData<Int> = POSE
    override fun windupData(): TrackedData<Int> = WINDUP
    override fun serialData(): TrackedData<Int> = SERIAL

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(FORM, AriusForm.DARK)
        builder.add(SHEATHED, false)
        builder.add(EYE_GLOW, 40)
        builder.add(SIDE, 1)
        builder.add(LEAD, false)
        builder.add(OPEN, false)
        builder.add(BLADE, AriusBlade.HELD)
        builder.add(COUNTER, false)
    }

    /** Seit 1.11.0: Konterhaltung aktiv (wer jetzt zuschlägt, wird pariert). Der Client zeigt das unübersehbar an. */
    var counterStance: Boolean
        get() = dataTracker.get(COUNTER)
        set(value) = dataTracker.set(COUNTER, value)

    /** [AriusForm]. */
    var form: Int
        get() = dataTracker.get(FORM)
        set(value) = dataTracker.set(FORM, value)

    /** Solvane steckt in der Scheide auf dem Rücken (Iai, Zuschauer, Ende). */
    var sheathed: Boolean
        get() = dataTracker.get(SHEATHED)
        set(value) = dataTracker.set(SHEATHED, value)

    /** Leuchtstärke der Augen 0..100 (das goldene Auge führt, das blaue folgt). */
    var eyeGlow: Int
        get() = dataTracker.get(EYE_GLOW)
        set(value) = dataTracker.set(EYE_GLOW, value.coerceIn(0, 100))

    /** Richtung des Seitschritts: +1 rechts, −1 links. */
    var sideSign: Int
        get() = dataTracker.get(SIDE)
        set(value) = dataTracker.set(SIDE, if (value < 0) -1 else 1)

    /** Führt gerade den Takt (Abschnitt III): Augen heller. */
    var lead: Boolean
        get() = dataTracker.get(LEAD)
        set(value) = dataTracker.set(LEAD, value)

    /** Steht offen (goldenes Fenster): Modell darf die Haltung lockern. */
    var open: Boolean
        get() = dataTracker.get(OPEN)
        set(value) = dataTracker.set(OPEN, value)

    /**
     * Wo Solvane gerade ist, siehe [AriusBlade]: in Hand bzw. Scheide (wie bisher), als Drache unterwegs (keine Klinge,
     * leere Scheide) oder ersetzt durch eine Klinge aus weißem Eislicht. Läuft über den DataTracker, Nachzügler und alle
     * Mitspieler sehen also denselben Stand.
     */
    var bladeState: Int
        get() = dataTracker.get(BLADE)
        set(value) = dataTracker.set(BLADE, value.coerceIn(AriusBlade.HELD, AriusBlade.ICE_BROKEN))

    companion object {
        private val POSE: TrackedData<Int> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val WINDUP: TrackedData<Int> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val SERIAL: TrackedData<Int> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val FORM: TrackedData<Int> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val SHEATHED: TrackedData<Boolean> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val EYE_GLOW: TrackedData<Int> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val SIDE: TrackedData<Int> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val LEAD: TrackedData<Boolean> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val OPEN: TrackedData<Boolean> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val BLADE: TrackedData<Int> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val COUNTER: TrackedData<Boolean> = DataTracker.registerData(AriusEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        /** Trefferbox 0,7 × 1,95 (185 cm). Leben verwaltet der Kampf; das Attribut ist nur für die Anzeige. */
        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 1000.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 128.0)
    }
}

/**
 * Zustand von Arius' Schwert ([AriusEntity.bladeState]). Solvane wurde aus dem Herzen eines uralten weißen Drachen
 * geschmiedet und kann dessen Gestalt annehmen: Der Drache des Kampfes *ist* die Klinge.
 */
object AriusBlade {
    /** Solvane in der Hand oder in der Scheide ([AriusEntity.sheathed]), wie bisher. */
    const val HELD = 0

    /** Solvane ist als Drache unterwegs: keine Klinge, die Scheide ist leer (Abschnitt II). */
    const val NONE = 1

    /** Eine Klinge aus weißem Eislicht in der Hand, die Scheide ist leer (Abschnitt III). */
    const val ICE = 2

    const val ICE_CRACKED = 3
    const val ICE_BROKEN = 4

    fun iceStack(blade: Int = ICE) = net.minecraft.item.ItemStack(net.minecraft.item.Items.BLUE_ICE).apply {
        set(net.minecraft.component.DataComponentTypes.ITEM_MODEL, dev.henny.hha_arius.HhaArius.id(when (blade) {
            ICE_CRACKED -> "ice_blade_cracked"
            ICE_BROKEN -> "ice_blade_broken"
            else -> "ice_blade"
        }))
    }
}

/** Nivaris, die Alte vom Eirgletscher. Siehe [NivarisPose]. */
class NivarisEntity(type: EntityType<out NivarisEntity>, world: World) : EirholmPuppet(type, world) {

    /** Seit 1.11.0: tatsächliche Flugrichtung (aus [EirholmFight.place]), damit die nächste Bahn ohne Knick anschließt. */
    var travelDir: net.minecraft.util.math.Vec3d? = null
    var travelTick = -100L

    override fun shouldRender(distance: Double): Boolean = distance < 512.0 * 512.0

    // --- Ritt (seit 1.8.0): bis zu acht Spieler sitzen hintereinander auf ihrem Rücken ---------------------------------

    override fun canAddPassenger(passenger: net.minecraft.entity.Entity): Boolean = passengerList.size < 8

    override fun getPassengerAttachmentPos(passenger: net.minecraft.entity.Entity, dimensions: net.minecraft.entity.EntityDimensions, scaleFactor: Float): net.minecraft.util.math.Vec3d {
        // Seit 1.10.0: Jeder Reiter hängt an der Kristallplatte eines Rückenglieds (Glied 4, 6, 8 …), waagerecht im
        // Fahrtwind dahinter. Der Server rechnet mit gestrecktem Körper; der Client setzt ihn an den echten Dorn des
        // gezeichneten Körpers ([RideHooks.riderPos], client/fx/RideClient).
        return RideHooks.offsetFor(this, passenger).rotateY(-bodyYaw * net.minecraft.util.math.MathHelper.RADIANS_PER_DEGREE)
    }

    override fun updatePassengerPosition(passenger: net.minecraft.entity.Entity, positionUpdater: net.minecraft.entity.Entity.PositionUpdater) {
        val at = if (entityWorld.isClient) RideHooks.riderPos?.invoke(this, passenger) else null
        if (at != null) positionUpdater.accept(passenger, at.x, at.y, at.z) else super.updatePassengerPosition(passenger, positionUpdater)
    }

    override fun poseData(): TrackedData<Int> = POSE
    override fun windupData(): TrackedData<Int> = WINDUP
    override fun serialData(): TrackedData<Int> = SERIAL

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(FLYING, false)
        builder.add(RESONANCE, false)
        builder.add(THROAT_GLOW, 0)
        builder.add(EYE_GLOW, 50)
        builder.add(LEAD, false)
        builder.add(MODE, NivarisMode.NORMAL)
        builder.add(UNCOIL, 0f)
        builder.add(VEIL, 0f)
        builder.add(BODY_ROLL, 0f)
        builder.add(WING_TUCK, 0f)
    }

    /** Zusätzliche Längsrolle des Körpers in Bogenmaß (Fassrolle beim Abwurf der Reiter). */
    var bodyRoll: Float
        get() = dataTracker.get(BODY_ROLL)
        set(value) = dataTracker.set(BODY_ROLL, value)

    /**
     * Seit 1.12.0: Flügel angelegt 0..1 (sie zieht sie im Ritt an, um durch enge Felsen zu schießen). Der Client blendet
     * weich dorthin ([wingTuckAt]).
     */
    var wingTuck: Float
        get() = dataTracker.get(WING_TUCK)
        set(value) = dataTracker.set(WING_TUCK, value.coerceIn(0f, 1f))

    private var lastTuck = 0f
    private var seenTuck = 0f

    /** Angelegte Flügel zwischen den Ticks (Client). */
    fun wingTuckAt(tickDelta: Float): Float = lastTuck + (seenTuck - lastTuck) * tickDelta

    /**
     * Seit 1.12.0 (Crix: „für andere bleibt man auf dem Drachen sitzen“): Vanilla schickt die Mitfahrerliste nicht an
     * Spieler, deren eigener Reitstatus sich gerade geändert hat. Steigen mehrere im selben Tick ab, behielt jeder alle
     * nach ihm Abgestiegenen auf seinem Client für immer auf ihrem Rücken. Daher geht die Liste hier nach jeder
     * Änderung an alle, die sie sehen.
     */
    private var sentRiders: List<Int> = emptyList()

    private fun syncRiders() {
        val now = passengerList.map { it.id }
        if (now == sentRiders) return
        sentRiders = now
        val packet = net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket(this)
        for (p in net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(this)) p.networkHandler.sendPacket(packet)
    }

    /** Darstellung des Schlangenkörpers, siehe [NivarisMode] (Windung um die Nadel im Auftritt). */
    var mode: Int
        get() = dataTracker.get(MODE)
        set(value) = dataTracker.set(MODE, value)

    /** Fortschritt beim Lösen von der Nadel 0..1 (nur in [NivarisMode.UNCOIL]), Weg entlang [EntrancePath]. */
    var uncoil: Float
        get() = dataTracker.get(UNCOIL)
        set(value) = dataTracker.set(UNCOIL, value.coerceIn(0f, 1f))

    /**
     * Schleier 0..1: wie weit sich der Körper in Licht und Eiskristalle aufgelöst hat (0 = ganz da, 1 = fort). Glied für
     * Glied von der Schwanzspitze zum Kopf; beim Entstehen läuft derselbe Wert rückwärts, der Kopf erscheint also zuerst.
     * Nur Darstellung (Trefferbox und Mechanik bleiben); der Server führt ihn mit [fadeIn]/[fadeOut] in eigenem Takt,
     * Nachzügler bekommen den laufenden Wert über das TrackedData.
     */
    var veil: Float
        get() = dataTracker.get(VEIL)
        private set(value) = dataTracker.set(VEIL, value.coerceIn(0f, 1f))

    /** Nur Server: Änderung des Schleiers je Tick (0 = steht). */
    private var veilRate = 0f

    /** Sofort ganz da (Überspringen, Abbruch, Training). */
    fun showNow() { veilRate = 0f; veil = 0f }

    /** Sofort fort (vor dem Entstehen). */
    fun hideNow() { veilRate = 0f; veil = 1f }

    /** Entsteht vom Kopf aus in [ticks] Ticks (vom aktuellen Schleier aus). */
    fun fadeIn(ticks: Int) { veilRate = -1f / ticks.coerceAtLeast(1) }

    /** Löst sich von der Schwanzspitze aus in [ticks] Ticks auf. */
    fun fadeOut(ticks: Int) { veilRate = 1f / ticks.coerceAtLeast(1) }

    // --- nur Client: Fortschritt des Vor-Ticks für weiches Zeichnen zwischen den Ticks ---
    var lastUncoil = 0f
        private set
    private var seenUncoil = 0f
    private var lastVeil = Float.NaN
    private var seenVeil = Float.NaN
    private var lastRoll = 0f
    private var seenRoll = 0f

    /**
     * Fortschritt des Lösens zwischen den Ticks (Client). Seit 1.10.0 aus den Tick-Werten [lastUncoil] → [seenUncoil]
     * (vorher bis zum *live* empfangenen Wert: kam ein Paket zwischen zwei Ticks an, sprang der Körper vor und im
     * nächsten Tick wieder zurück – das sichtbare Ruckeln beim Flug über die Schlucht).
     */
    fun uncoilAt(tickDelta: Float): Float = lastUncoil + (seenUncoil - lastUncoil) * tickDelta

    /** Längsrolle zwischen den Ticks (Client). */
    fun rollAt(tickDelta: Float): Float = lastRoll + (seenRoll - lastRoll) * tickDelta

    /** Client: Lösen-Fortschritt aus dem Bewegungspuffer ([MotionSync]) für diesen Tick. */
    fun smoothUncoil(value: Float) {
        lastUncoil = seenUncoil
        seenUncoil = value
        if (kotlin.math.abs(seenUncoil - lastUncoil) > 0.05f) lastUncoil = seenUncoil
        smoothedUncoil = true
    }

    private var smoothedUncoil = false

    /** Schleier zwischen den Ticks (Client). Vor dem ersten Tick (frisch geladen) der Wert des Servers. */
    fun veilAt(tickDelta: Float): Float {
        if (seenVeil.isNaN()) return veil
        return lastVeil + (seenVeil - lastVeil) * tickDelta
    }

    override fun tick() {
        super.tick()
        if (entityWorld.isClient) {
            // Der Bewegungspuffer setzt den Wert selbst (nach super.tick); sonst wie bisher aus dem TrackedData.
            if (!smoothedUncoil) {
                lastUncoil = seenUncoil
                seenUncoil = uncoil
            }
            smoothedUncoil = false
            // Sprung (Nachzügler, Überspringen): nicht über den ganzen Weg gleiten.
            if (kotlin.math.abs(seenUncoil - lastUncoil) > 0.05f) lastUncoil = seenUncoil
            lastVeil = if (seenVeil.isNaN()) veil else seenVeil
            seenVeil = veil
            // Größter regulärer Schritt 1/20 je Tick; größere Sprünge (Überspringen) sofort.
            if (kotlin.math.abs(seenVeil - lastVeil) > 0.12f) lastVeil = seenVeil
            lastRoll = seenRoll
            seenRoll = bodyRoll
            if (kotlin.math.abs(seenRoll - lastRoll) > 1.2f) lastRoll = seenRoll
            lastTuck = seenTuck
            seenTuck += (wingTuck - seenTuck).coerceIn(-0.09f, 0.09f)
        } else {
            syncRiders()
            if (veilRate != 0f) {
                val v = (veil + veilRate).coerceIn(0f, 1f)
                veil = v
                if (v <= 0f || v >= 1f) veilRate = 0f
            }
        }
    }

    /** Fliegt (Flügelschlag-Zyklus, Beine angezogen). */
    var flying: Boolean
        get() = dataTracker.get(FLYING)
        set(value) = dataTracker.set(FLYING, value)

    /** Resonanz mit Arius: weiße Linien in den Schwingenmembranen leuchten (Leuchtmaske „resonance“). */
    var resonance: Boolean
        get() = dataTracker.get(RESONANCE)
        set(value) = dataTracker.set(RESONANCE, value)

    /** Glühen von Kehle und Herzkern 0..100 (Atemholen = Schwachpunkt). */
    var throatGlow: Int
        get() = dataTracker.get(THROAT_GLOW)
        set(value) = dataTracker.set(THROAT_GLOW, value.coerceIn(0, 100))

    /** Augen 0..100. */
    var eyeGlow: Int
        get() = dataTracker.get(EYE_GLOW)
        set(value) = dataTracker.set(EYE_GLOW, value.coerceIn(0, 100))

    /** Führt gerade den Takt (Abschnitt III). */
    var lead: Boolean
        get() = dataTracker.get(LEAD)
        set(value) = dataTracker.set(LEAD, value)

    companion object {
        private val POSE: TrackedData<Int> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val WINDUP: TrackedData<Int> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val SERIAL: TrackedData<Int> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val FLYING: TrackedData<Boolean> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val RESONANCE: TrackedData<Boolean> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val THROAT_GLOW: TrackedData<Int> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val EYE_GLOW: TrackedData<Int> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val LEAD: TrackedData<Boolean> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val MODE: TrackedData<Int> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val UNCOIL: TrackedData<Float> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.FLOAT)
        private val VEIL: TrackedData<Float> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.FLOAT)
        private val BODY_ROLL: TrackedData<Float> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.FLOAT)
        private val WING_TUCK: TrackedData<Float> = DataTracker.registerData(NivarisEntity::class.java, TrackedDataHandlerRegistry.FLOAT)

        /**
         * Trefferbox 4,5 × 3,6: der Rumpf vorn (Glieder 2–4). Der 96 Blöcke lange Körper ist nur Darstellung und
         * blockiert nichts; Kopf/Schwanz werden über Trefferzonen bzw. die Kopf-Trefferbox zugeordnet.
         */
        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 1000.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 128.0)
    }
}

/**
 * Unsichtbare Trefferbox für Nivaris' Kopf. Der Kopf des Schlangendrachen sitzt 3–8 Blöcke vor bzw. über dem Rumpf
 * ([NivarisShape.headCenter]), also außerhalb der Rumpf-Box; der Kampf setzt diese Box jeden Tick an den Kopf und
 * wertet Treffer darauf als Kopftreffer.
 */
class NivarisHeadEntity(type: EntityType<out NivarisHeadEntity>, world: World) : EirholmPuppet(type, world) {

    init {
        isInvisible = true
    }

    override fun poseData(): TrackedData<Int> = POSE
    override fun windupData(): TrackedData<Int> = WINDUP
    override fun serialData(): TrackedData<Int> = SERIAL

    companion object {
        private val POSE: TrackedData<Int> = DataTracker.registerData(NivarisHeadEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val WINDUP: TrackedData<Int> = DataTracker.registerData(NivarisHeadEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val SERIAL: TrackedData<Int> = DataTracker.registerData(NivarisHeadEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 1000.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
    }
}

package com.truempg.app.obd

import android.content.Context
import com.truempg.app.DeviceInfo
import com.truempg.app.Readings
import com.truempg.app.UiState
import com.truempg.app.data.Settings
import com.truempg.app.data.Trip
import com.truempg.app.data.TripStore
import com.truempg.app.data.VehicleProfile
import com.truempg.app.data.VehicleStore
import com.truempg.app.obd.ObdMath.MpgMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide single source of truth. Owns the ObdManager, capability
 * discovery, the universal MPG engine, poll loop, trip lifecycle, and
 * auto-reconnect. Both the foreground service and the UI talk to this.
 */
object ObdRepository {

    private lateinit var app: Context
    private lateinit var obd: ObdManager
    private lateinit var tripStore: TripStore
    private lateinit var vehicleStore: VehicleStore
    private lateinit var settings: Settings
    @Volatile private var inited = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null

    private var tripStartMs = 0L
    private var currentAddress: String? = null
    private var currentProfile: VehicleProfile? = null
    @Volatile private var wantConnected = false
    private var lastPersistMs = 0L

    val state = MutableStateFlow(UiState())

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        app = context.applicationContext
        obd = ObdManager(app)
        tripStore = TripStore(app)
        vehicleStore = VehicleStore(app)
        settings = Settings(app)
        val active = settings.loadActiveTrip()
        if (active != null) tripStartMs = active.startedAt
        state.value = UiState(
            trips = tripStore.load(),
            ve = settings.ve,
            distanceUnit = settings.distanceUnit,
            economyUnit = settings.economyUnit,
            autoConnect = settings.autoConnect,
            savedAdapter = settings.savedAdapterAddress,
            tripActive = active != null,
            tripMiles = active?.miles ?: 0.0,
            tripGallons = active?.gallons ?: 0.0,
        )
        inited = true
    }

    // ---- settings / units / fuel model ----
    fun setVe(v: Double) {
        val ve = v.coerceIn(0.3, 1.3)
        settings.ve = ve
        state.update { it.copy(ve = ve) }
    }

    fun setAutoConnect(enabled: Boolean) {
        settings.autoConnect = enabled
        state.update { it.copy(autoConnect = enabled) }
    }

    fun setDistanceUnit(u: String) {
        settings.distanceUnit = u
        state.update { it.copy(distanceUnit = u) }
    }

    fun setEconomyUnit(u: String) {
        settings.economyUnit = u
        state.update { it.copy(economyUnit = u) }
    }

    fun setDisplacement(liters: Double) {
        val d = liters.coerceIn(0.5, 10.0)
        currentProfile = currentProfile?.copy(displacementL = d)?.also { vehicleStore.save(it) }
        state.update { it.copy(displacementL = d) }
    }

    fun setMethodOverride(method: MpgMethod?) {
        currentProfile = currentProfile?.copy(methodOverride = method)?.also { vehicleStore.save(it) }
        val supported = state.value.supportedPids
        val resolved = ObdMath.selectMethod(supported, method)
        state.update { it.copy(methodOverride = method, mpgMethod = resolved) }
    }

    // ---- device discovery ----
    fun refreshDevices() {
        if (!obd.bluetoothSupported()) {
            state.update { it.copy(bluetoothReady = false, status = "No Bluetooth adapter") }; return
        }
        if (!obd.bluetoothEnabled()) {
            state.update { it.copy(bluetoothReady = false, status = "Turn on Bluetooth") }; return
        }
        val list = obd.pairedObdDevices().mapNotNull { d ->
            val addr = try { d.address } catch (e: SecurityException) { null } ?: return@mapNotNull null
            DeviceInfo(try { d.name } catch (e: SecurityException) { null } ?: "(unknown)", addr)
        }
        state.update {
            it.copy(
                bluetoothReady = true, devices = list,
                status = if (list.isEmpty())
                    "No paired devices — pair the adapter in Settings first"
                else "${list.size} paired device(s)"
            )
        }
    }

    // ---- connect / auto-connect ----
    fun connect(address: String) {
        currentAddress = address
        wantConnected = true
        settings.savedAdapterAddress = address
        state.update { it.copy(connecting = true, savedAdapter = address, status = "Connecting…") }
        scope.launch { doConnect(address) }
    }

    fun autoConnectSaved() {
        if (!settings.autoConnect) return
        val addr = settings.savedAdapterAddress ?: return
        if (state.value.connected || state.value.connecting) return
        connect(addr)
    }

    private suspend fun doConnect(address: String) {
        val device = obd.deviceFor(address)
        if (device == null) {
            state.update { it.copy(connecting = false, connected = false, status = "Adapter not found") }
            return
        }
        obd.preferredProtocol = settings.lastProtocol   // fast reconnect hint
        val res = obd.connect(device)
        if (res.isSuccess) {
            state.update { it.copy(connecting = false, connected = true, status = "Connected — identifying vehicle…") }
            identifyVehicle(address)
            startPolling()
        } else {
            state.update {
                it.copy(connecting = false, connected = false,
                    status = "Connect failed: ${res.exceptionOrNull()?.message}")
            }
            if (wantConnected) scheduleReconnect()
        }
    }

    /** Verified protocol + VIN + capability discovery -> per-vehicle profile. */
    private suspend fun identifyVehicle(address: String) {
        val proto = obd.workingProtocol            // protocol that actually returned data
        if (!proto.isNullOrBlank()) settings.lastProtocol = proto

        val vin = try { obd.readVin() } catch (e: Exception) { null }
        val existing = vehicleStore.find(vin ?: address)
        val id = existing?.id ?: (vin ?: address)

        val profile: VehicleProfile
        // (Re)discover when new OR when a prior (broken) profile has no PIDs.
        if (existing == null || existing.supportedPids.isEmpty()) {
            val pids = try { obd.querySupportedPids() } catch (e: Exception) { emptySet() }
            val fuel = try { obd.queryFuelType() } catch (e: Exception) { null }
                ?: existing?.fuelType ?: ObdMath.FUEL_GASOLINE
            val label = ObdMath.vehicleLabel(vin).takeIf { it != "Vehicle" }
                ?: existing?.label ?: "Vehicle"
            profile = (existing ?: VehicleProfile(id = id)).copy(
                id = id,
                vin = vin ?: existing?.vin,
                label = label,
                protocol = proto ?: existing?.protocol,
                supportedPids = pids,
                fuelType = fuel,
            )
            vehicleStore.save(profile)
        } else {
            profile = if (!proto.isNullOrBlank() && existing.protocol != proto)
                existing.copy(protocol = proto).also { vehicleStore.save(it) }
            else existing
        }
        currentProfile = profile

        val method = ObdMath.selectMethod(profile.supportedPids, profile.methodOverride)
        state.update {
            it.copy(
                status = "Connected", vin = profile.vin, vehicleLabel = profile.label,
                protocol = profile.protocol, supportedPids = profile.supportedPids,
                fuelType = profile.fuelType, displacementL = profile.displacementL,
                mpgMethod = method, methodOverride = profile.methodOverride,
            )
        }
    }

    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel(); reconnectJob = null
        pollJob?.cancel(); pollJob = null
        autoSaveActiveTrip()
        obd.close()
        state.update { it.copy(connected = false, connecting = false, status = "Disconnected") }
    }

    // ---- polling ----
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var lastNs = System.nanoTime()
            while (isActive) {
                val reading = try { pollOnce() } catch (e: Exception) { null }
                if (reading == null || !obd.isConnected) { onDrop(); break }
                val now = System.nanoTime()
                val dtHours = (now - lastNs) / 1e9 / 3600.0
                lastNs = now
                applyReading(reading, dtHours)
                delay(600)
            }
        }
    }

    private data class Raw(
        val rpm: Double, val mph: Double, val mapk: Double, val iat: Double,
        val lam: Double, val coolant: Double?, val throttle: Double?, val load: Double?,
        val fuel: Double?, val volts: Double?, val maf: Double?, val fuelRate: Double?,
    )

    private suspend fun pollOnce(): Raw {
        val known = currentProfile?.supportedPids ?: emptySet()
        // If discovery came up empty, fall back to querying the core set so the
        // vehicle still works instead of showing nothing.
        val all = known.isEmpty()
        suspend fun q(pid: Int): List<Int>? =
            if (all || pid in known) obd.queryPid(pid) else null

        val rpm = ObdMath.rpm(q(0x0C) ?: emptyList()) ?: 0.0
        val kph = ObdMath.speedKph(q(0x0D) ?: emptyList()) ?: 0.0
        val mapk = ObdMath.mapKpa(q(0x0B) ?: emptyList()) ?: 0.0
        val iat = ObdMath.iatC(q(0x0F) ?: emptyList()) ?: 20.0
        val lam = ObdMath.lambda(q(0x44) ?: emptyList()) ?: 1.0
        val coolant = ObdMath.coolantC(q(0x05) ?: emptyList())
        val throttle = ObdMath.throttlePct(q(0x11) ?: emptyList())
        val load = ObdMath.engineLoadPct(q(0x04) ?: emptyList())
        val fuel = ObdMath.fuelLevelPct(q(0x2F) ?: emptyList())
        val volts = ObdMath.moduleVolts(q(0x42) ?: emptyList())
        val maf = ObdMath.mafGps(q(0x10) ?: emptyList())
        val fuelRate = ObdMath.fuelRateLph(q(0x5E) ?: emptyList())
        return Raw(rpm, kph / ObdMath.KPH_PER_MPH, mapk, iat, lam,
            coolant, throttle, load, fuel, volts, maf, fuelRate)
    }

    private fun applyReading(r: Raw, dtHours: Double) {
        val snap = state.value
        val diesel = ObdMath.isDiesel(snap.fuelType)
        val gph = when (snap.mpgMethod) {
            MpgMethod.FUEL_RATE -> ObdMath.gphFromFuelRate(r.fuelRate ?: 0.0)
            MpgMethod.MAF -> ObdMath.gphFromMaf(r.maf ?: 0.0, r.lam, diesel)
            MpgMethod.SPEED_DENSITY ->
                ObdMath.gphSpeedDensity(r.rpm, r.mapk, r.iat, r.lam, snap.ve, snap.displacementL, diesel)
            MpgMethod.NONE -> 0.0
        }
        val inst = ObdMath.instMpg(r.mph, gph)

        var active = snap.tripActive
        if (!active && r.mph > 0.5) {
            active = true
            tripStartMs = System.currentTimeMillis()
        }
        val addMiles = if (active) r.mph * dtHours else 0.0
        val addGals = if (active) gph * dtHours else 0.0

        state.update { st ->
            st.copy(
                readings = Readings(r.rpm, r.mph, r.mapk, r.iat, r.coolant, r.throttle,
                    r.load, r.fuel, r.volts, r.maf, r.fuelRate, r.lam, inst),
                tripActive = active,
                tripMiles = st.tripMiles + addMiles,
                tripGallons = st.tripGallons + addGals,
                tripElapsedSec = if (active) (System.currentTimeMillis() - tripStartMs) / 1000
                else st.tripElapsedSec,
            )
        }
        maybePersistActiveTrip()
    }

    private fun maybePersistActiveTrip() {
        val s = state.value
        val now = System.currentTimeMillis()
        if (s.tripActive && now - lastPersistMs > 5000) {
            lastPersistMs = now
            settings.saveActiveTrip(tripStartMs, s.tripMiles, s.tripGallons)
        }
    }

    // ---- disconnect / auto-reconnect ----
    private fun onDrop() {
        obd.close()
        state.update { it.copy(connected = false, status = "Connection lost — reconnecting…") }
        if (wantConnected) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val addr = currentAddress ?: return@launch
            var attempts = 0
            while (wantConnected && attempts < 10) {
                attempts++
                state.update { it.copy(status = "Reconnecting… (try $attempts)") }
                delay(4000)
                val device = obd.deviceFor(addr) ?: continue
                obd.preferredProtocol = settings.lastProtocol
                val res = obd.connect(device)
                if (res.isSuccess) {
                    state.update { it.copy(connected = true, connecting = false, status = "Reconnected") }
                    startPolling()
                    return@launch
                }
            }
            autoSaveActiveTrip()
            wantConnected = false
            state.update { it.copy(connected = false, status = "Adapter offline — trip saved") }
        }
    }

    // ---- trip lifecycle ----
    fun startTrip() {
        tripStartMs = System.currentTimeMillis()
        settings.saveActiveTrip(tripStartMs, 0.0, 0.0)
        state.update { it.copy(tripActive = true, tripMiles = 0.0, tripGallons = 0.0, tripElapsedSec = 0) }
    }

    fun stopTrip() = autoSaveActiveTrip()

    private fun autoSaveActiveTrip() {
        val s = state.value
        if (s.tripActive && (s.tripMiles > 0.0 || s.tripGallons > 0.0)) {
            tripStore.add(
                Trip(
                    startedAt = tripStartMs,
                    durationSec = (System.currentTimeMillis() - tripStartMs) / 1000,
                    miles = s.tripMiles,
                    gallons = s.tripGallons,
                )
            )
        }
        settings.clearActiveTrip()
        state.update {
            it.copy(tripActive = false, tripMiles = 0.0, tripGallons = 0.0,
                tripElapsedSec = 0, trips = tripStore.load())
        }
    }

    fun clearTripHistory() {
        tripStore.clear()
        state.update { it.copy(trips = emptyList()) }
    }

    // ---- DTCs ----
    fun readDtcs() {
        scope.launch {
            state.update { it.copy(dtcMessage = "Reading…") }
            val codes = try { obd.readDtcs() } catch (e: Exception) { emptyList() }
            state.update {
                it.copy(dtcs = codes,
                    dtcMessage = if (codes.isEmpty()) "No stored codes" else "${codes.size} code(s)")
            }
        }
    }

    fun clearDtcs() {
        scope.launch {
            state.update { it.copy(dtcMessage = "Clearing…") }
            val ok = try { obd.clearDtcs() } catch (e: Exception) { false }
            state.update {
                it.copy(dtcs = if (ok) emptyList() else it.dtcs,
                    dtcMessage = if (ok) "Cleared. (Real faults will return.)" else "Clear failed")
            }
        }
    }
}

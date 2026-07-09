package com.truempg.app.obd

import android.content.Context
import com.truempg.app.DeviceInfo
import com.truempg.app.Readings
import com.truempg.app.UiState
import com.truempg.app.data.Settings
import com.truempg.app.data.Trip
import com.truempg.app.data.TripStore
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
 * Process-wide single source of truth. Both the foreground [service.ObdService]
 * and the UI's MainViewModel talk to this. Owns the ObdManager, the poll loop,
 * trip lifecycle (auto-start when moving, auto-save when the adapter drops for
 * good), and auto-reconnect that resumes an in-progress trip.
 */
object ObdRepository {

    private lateinit var app: Context
    private lateinit var obd: ObdManager
    private lateinit var tripStore: TripStore
    private lateinit var settings: Settings
    @Volatile private var inited = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null

    private var tripStartMs = 0L
    private var currentAddress: String? = null
    @Volatile private var wantConnected = false
    private var lastPersistMs = 0L

    val state = MutableStateFlow(UiState())

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        app = context.applicationContext
        obd = ObdManager(app)
        tripStore = TripStore(app)
        settings = Settings(app)
        val active = settings.loadActiveTrip()
        if (active != null) tripStartMs = active.startedAt
        state.value = UiState(
            trips = tripStore.load(),
            ve = settings.ve,
            autoConnect = settings.autoConnect,
            savedAdapter = settings.savedAdapterAddress,
            tripActive = active != null,
            tripMiles = active?.miles ?: 0.0,
            tripGallons = active?.gallons ?: 0.0,
        )
        inited = true
    }

    // ---- settings ----
    fun setVe(v: Double) {
        val ve = v.coerceIn(0.3, 1.3)
        settings.ve = ve
        state.update { it.copy(ve = ve) }
    }

    fun setAutoConnect(enabled: Boolean) {
        settings.autoConnect = enabled
        state.update { it.copy(autoConnect = enabled) }
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
                    "No paired devices — pair the MX+ in Settings first"
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

    /** Called on ACL_CONNECTED / service auto-start: connect the saved adapter. */
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
        val res = obd.connect(device)
        if (res.isSuccess) {
            state.update { it.copy(connecting = false, connected = true, status = "Connected") }
            startPolling()
        } else {
            state.update {
                it.copy(connecting = false, connected = false,
                    status = "Connect failed: ${res.exceptionOrNull()?.message}")
            }
            if (wantConnected) scheduleReconnect()
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
        val lam: Double, val coolant: Double?, val throttle: Double?,
        val load: Double?, val fuel: Double?, val volts: Double?,
    )

    private suspend fun pollOnce(): Raw {
        val rpm = ObdMath.rpm(obd.queryPid(0x0C) ?: emptyList()) ?: 0.0
        val kph = ObdMath.speedKph(obd.queryPid(0x0D) ?: emptyList()) ?: 0.0
        val mapk = ObdMath.mapKpa(obd.queryPid(0x0B) ?: emptyList()) ?: 0.0
        val iat = ObdMath.iatC(obd.queryPid(0x0F) ?: emptyList()) ?: 20.0
        val lam = ObdMath.lambda(obd.queryPid(0x44) ?: emptyList()) ?: 1.0
        val coolant = ObdMath.coolantC(obd.queryPid(0x05) ?: emptyList())
        val throttle = ObdMath.throttlePct(obd.queryPid(0x11) ?: emptyList())
        val load = ObdMath.engineLoadPct(obd.queryPid(0x04) ?: emptyList())
        val fuel = ObdMath.fuelLevelPct(obd.queryPid(0x2F) ?: emptyList())
        val volts = ObdMath.moduleVolts(obd.queryPid(0x42) ?: emptyList())
        return Raw(rpm, kph / ObdMath.KPH_PER_MPH, mapk, iat, lam, coolant, throttle, load, fuel, volts)
    }

    private fun applyReading(r: Raw, dtHours: Double) {
        val snap = state.value
        val gph = ObdMath.gph(r.rpm, r.mapk, r.iat, r.lam, snap.ve)
        val inst = ObdMath.instMpg(r.mph, gph)

        // Auto-start a trip as soon as the truck is moving.
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
                    r.load, r.fuel, r.volts, r.lam, inst),
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

    /**
     * Retry for a while. A transient socket blip reconnects and the active trip
     * keeps accumulating. If retries are exhausted (truck turned off), save the
     * trip and give up.
     */
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
                val res = obd.connect(device)
                if (res.isSuccess) {
                    state.update { it.copy(connected = true, connecting = false, status = "Reconnected") }
                    startPolling()
                    return@launch
                }
            }
            // Gave up -> adapter is off (truck off). Save the trip.
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

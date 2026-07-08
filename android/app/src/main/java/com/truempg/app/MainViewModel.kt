package com.truempg.app

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.truempg.app.data.Trip
import com.truempg.app.data.TripStore
import com.truempg.app.obd.ObdManager
import com.truempg.app.obd.ObdMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceInfo(val name: String, val address: String)

data class Readings(
    val rpm: Double = 0.0,
    val mph: Double = 0.0,
    val mapKpa: Double = 0.0,
    val iatC: Double = 0.0,
    val coolantC: Double? = null,
    val throttlePct: Double? = null,
    val loadPct: Double? = null,
    val fuelLevelPct: Double? = null,
    val volts: Double? = null,
    val lambda: Double = 1.0,
    val instMpg: Double = 0.0,
)

data class UiState(
    val bluetoothReady: Boolean = true,
    val devices: List<DeviceInfo> = emptyList(),
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Not connected",
    val readings: Readings = Readings(),
    val ve: Double = 0.85,
    val tripActive: Boolean = false,
    val tripMiles: Double = 0.0,
    val tripGallons: Double = 0.0,
    val tripElapsedSec: Long = 0L,
    val trips: List<Trip> = emptyList(),
    val dtcs: List<String> = emptyList(),
    val dtcMessage: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val obd = ObdManager(app)
    private val tripStore = TripStore(app)
    private val deviceMap = HashMap<String, BluetoothDevice>()

    private val _state = MutableStateFlow(UiState(trips = tripStore.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var tripStartMs = 0L

    fun setVe(v: Double) = _state.update { it.copy(ve = v.coerceIn(0.3, 1.3)) }

    fun refreshDevices() {
        if (!obd.bluetoothSupported()) {
            _state.update { it.copy(bluetoothReady = false, status = "No Bluetooth adapter") }
            return
        }
        if (!obd.bluetoothEnabled()) {
            _state.update { it.copy(bluetoothReady = false, status = "Turn on Bluetooth") }
            return
        }
        deviceMap.clear()
        val list = obd.pairedObdDevices().mapNotNull { d ->
            val addr = d.address ?: return@mapNotNull null
            deviceMap[addr] = d
            DeviceInfo(d.name ?: "(unknown)", addr)
        }
        _state.update {
            it.copy(
                bluetoothReady = true,
                devices = list,
                status = if (list.isEmpty()) "No paired devices — pair the MX+ in Settings first"
                else "${list.size} paired device(s)"
            )
        }
    }

    fun connect(address: String) {
        val device = deviceMap[address] ?: return
        _state.update { it.copy(connecting = true, status = "Connecting to ${device.name}…") }
        viewModelScope.launch {
            val res = obd.connect(device)
            if (res.isSuccess) {
                _state.update { it.copy(connecting = false, connected = true, status = "Connected") }
                startPolling()
            } else {
                _state.update {
                    it.copy(connecting = false, connected = false,
                        status = "Connect failed: ${res.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel(); pollJob = null
        obd.close()
        _state.update { it.copy(connected = false, status = "Disconnected") }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var lastNs = System.nanoTime()
            while (true) {
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

                val ve = _state.value.ve
                val mph = kph / ObdMath.KPH_PER_MPH
                val gph = ObdMath.gph(rpm, mapk, iat, lam, ve)
                val inst = ObdMath.instMpg(mph, gph)

                val now = System.nanoTime()
                val dtHours = (now - lastNs) / 1e9 / 3600.0
                lastNs = now

                _state.update { st ->
                    val active = st.tripActive
                    val miles = st.tripMiles + if (active) mph * dtHours else 0.0
                    val gals = st.tripGallons + if (active) gph * dtHours else 0.0
                    st.copy(
                        readings = Readings(rpm, mph, mapk, iat, coolant, throttle,
                            load, fuel, volts, lam, inst),
                        tripMiles = miles,
                        tripGallons = gals,
                        tripElapsedSec = if (active) (System.currentTimeMillis() - tripStartMs) / 1000 else st.tripElapsedSec,
                    )
                }
                delay(600)
            }
        }
    }

    fun startTrip() {
        tripStartMs = System.currentTimeMillis()
        _state.update { it.copy(tripActive = true, tripMiles = 0.0, tripGallons = 0.0, tripElapsedSec = 0) }
    }

    fun stopTrip() {
        val s = _state.value
        if (s.tripActive && (s.tripMiles > 0.0 || s.tripGallons > 0.0)) {
            val trip = Trip(
                startedAt = tripStartMs,
                durationSec = (System.currentTimeMillis() - tripStartMs) / 1000,
                miles = s.tripMiles,
                gallons = s.tripGallons,
            )
            tripStore.add(trip)
        }
        _state.update { it.copy(tripActive = false, trips = tripStore.load()) }
    }

    fun clearTripHistory() {
        tripStore.clear()
        _state.update { it.copy(trips = emptyList()) }
    }

    fun readDtcs() {
        viewModelScope.launch {
            _state.update { it.copy(dtcMessage = "Reading…") }
            val codes = obd.readDtcs()
            _state.update {
                it.copy(dtcs = codes,
                    dtcMessage = if (codes.isEmpty()) "No stored codes" else "${codes.size} code(s)")
            }
        }
    }

    fun clearDtcs() {
        viewModelScope.launch {
            _state.update { it.copy(dtcMessage = "Clearing…") }
            val ok = obd.clearDtcs()
            _state.update {
                it.copy(dtcs = if (ok) emptyList() else it.dtcs,
                    dtcMessage = if (ok) "Cleared. (Real faults will return.)" else "Clear failed")
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        obd.close()
        super.onCleared()
    }
}

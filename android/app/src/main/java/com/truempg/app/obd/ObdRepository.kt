package com.truempg.app.obd

import android.content.Context
import com.truempg.app.DeviceInfo
import com.truempg.app.Readings
import com.truempg.app.UiState
import com.truempg.app.data.Settings
import com.truempg.app.data.Trip
import com.truempg.app.data.TripStore
import com.truempg.app.data.BatteryStore
import com.truempg.app.data.BlackBoxLogger
import com.truempg.app.data.CalProfile
import com.truempg.app.data.CalibrationStore
import com.truempg.app.data.CrankReading
import com.truempg.app.data.Fillup
import com.truempg.app.data.FillupStore
import com.truempg.app.data.MaintenanceStore
import com.truempg.app.data.VehicleProfile
import com.truempg.app.data.VehicleStore
import com.truempg.app.obd.ObdMath.MpgMethod
import com.truempg.app.service.Alerts
import com.truempg.app.widget.MpgWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

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
    private lateinit var calStore: CalibrationStore
    private lateinit var fillupStore: FillupStore
    private lateinit var blackBox: BlackBoxLogger
    private lateinit var batteryStore: BatteryStore
    private lateinit var maintStore: MaintenanceStore
    private lateinit var settings: Settings
    private val maintNotified = HashSet<String>()
    @Volatile private var inited = false

    // Phase 5: calibration + fuel accounting
    @Volatile private var activeFactor = 1.0        // multiplies estimated fuel flow
    private var sinceGal = 0.0                       // fuel used since last fill-up
    private var sinceMiles = 0.0

    // Phase 6: battery-health capture window (lowest volts shortly after connect)
    private var captureUntilMs = 0L
    private var captureMin = Double.MAX_VALUE
    private var batteryCaptured = true

    // Phase 7: driving coach, boost, performance timers
    private var baroKpa = 101.3
    private var peakBoostKpa = 0.0
    private var coachActiveSec = 0.0
    private var coachIdleSec = 0.0
    private var coachHighLoadSec = 0.0
    private var coachIdleGal = 0.0
    private var coachHardAccels = 0
    private var prevMph = 0.0
    private var accelArmed = false
    private var launchActive = false
    private var launchStartMs = 0L
    private var launchDist = 0.0
    private var run060 = 0.0
    private var runQuarter = 0.0
    private var best060 = 0.0
    private var bestQuarter = 0.0
    private var bestQuarterMph = 0.0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null

    private var tripStartMs = 0L
    private var currentAddress: String? = null
    private var currentProfile: VehicleProfile? = null
    @Volatile private var wantConnected = false
    private var lastPersistMs = 0L

    // Phase 4: alerts + live-everything scan
    private val firedAlerts = HashSet<String>()          // once per condition per trip
    private var knownDtcs: Set<String> = emptySet()
    private var lastDtcCheckMs = 0L
    private var scanCursor = 0
    private var lastScanMs = 0L
    private val liveMap = HashMap<Int, Double>()
    private val corePids = setOf(0x0C, 0x0D, 0x0B, 0x0F, 0x44, 0x05, 0x11, 0x04, 0x2F, 0x42, 0x10, 0x5E)

    val state = MutableStateFlow(UiState())

    @Synchronized
    fun init(context: Context) {
        if (inited) return
        app = context.applicationContext
        obd = ObdManager(app)
        tripStore = TripStore(app)
        vehicleStore = VehicleStore(app)
        calStore = CalibrationStore(app)
        fillupStore = FillupStore(app)
        blackBox = BlackBoxLogger(app)
        batteryStore = BatteryStore(app)
        maintStore = MaintenanceStore(app)
        settings = Settings(app)
        sinceGal = settings.galSinceFillup
        sinceMiles = settings.milesSinceFillup
        val active = settings.loadActiveTrip()
        if (active != null) tripStartMs = active.startedAt
        state.value = UiState(
            trips = tripStore.load(),
            ve = settings.ve,
            distanceUnit = settings.distanceUnit,
            economyUnit = settings.economyUnit,
            tempUnit = settings.tempUnit,
            alertsEnabled = settings.alertsEnabled,
            coolantMaxF = settings.coolantMaxF,
            lowFuelPct = settings.lowFuelPct,
            galSinceFillup = sinceGal,
            milesSinceFillup = sinceMiles,
            lastPricePerGal = settings.lastPricePerGal,
            blackBoxEnabled = settings.blackBoxEnabled,
            logNames = blackBox.listLogs().map { it.name },
            batteryReadings = batteryStore.load().take(30),
            batteryAvg = batteryStore.recentAverage(),
            keepScreenOn = settings.keepScreenOn,
            knownVehicles = vehicleStore.loadAll().map { it.label },
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

    fun setTempUnit(u: String) {
        settings.tempUnit = u
        state.update { it.copy(tempUnit = u) }
    }

    fun setAlertsEnabled(enabled: Boolean) {
        settings.alertsEnabled = enabled
        state.update { it.copy(alertsEnabled = enabled) }
    }

    fun setCoolantMaxF(f: Double) {
        val v = f.coerceIn(180.0, 280.0)
        settings.coolantMaxF = v
        state.update { it.copy(coolantMaxF = v) }
    }

    fun setLowFuelPct(pct: Double) {
        val v = pct.coerceIn(1.0, 50.0)
        settings.lowFuelPct = v
        state.update { it.copy(lowFuelPct = v) }
    }

    /** Re-read readiness monitors, freeze-frame DTC, and stored codes on demand. */
    fun readDiagnostics() {
        scope.launch { refreshDiagnostics(alertOnNew = false) }
    }

    private suspend fun refreshDiagnostics(alertOnNew: Boolean) {
        val ready = try { obd.readReadiness() } catch (e: Exception) { null }
        val freeze = try { obd.readFreezeDtc() } catch (e: Exception) { null }
        val codes = try { obd.readDtcs() } catch (e: Exception) { emptyList() }
        val set = codes.toSet()
        val newOnes = if (alertOnNew) set - knownDtcs else emptySet()
        knownDtcs = set
        state.update {
            it.copy(readiness = ready, freezeDtc = freeze, dtcs = codes,
                dtcMessage = if (codes.isEmpty()) "No stored codes" else "${codes.size} code(s)")
        }
        if (alertOnNew && settings.alertsEnabled) {
            for (c in newOnes) Alerts.post(app, ("dtc$c").hashCode(),
                "New trouble code: $c", DtcDescriptions.describe(c) ?: "New DTC detected.")
        }
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

        baroKpa = (try { obd.queryPid(0x33)?.getOrNull(0)?.toDouble() } catch (e: Exception) { null }) ?: 101.3
        val method = ObdMath.selectMethod(profile.supportedPids, profile.methodOverride)
        state.update {
            it.copy(
                status = "Connected", vin = profile.vin, vehicleLabel = profile.label,
                protocol = profile.protocol, supportedPids = profile.supportedPids,
                fuelType = profile.fuelType, displacementL = profile.displacementL,
                mpgMethod = method, methodOverride = profile.methodOverride,
                isTurbo = profile.turbo,
            )
        }
        firedAlerts.clear()
        loadCalibration(profile.id)
        state.update {
            it.copy(
                odometer = maintStore.odometer(profile.id),
                maintenance = maintStore.items(profile.id),
                trips = tripsForCurrent(),
                knownVehicles = vehicleStore.loadAll().map { v -> v.label },
            )
        }
        updateWidget(null, "Connected · ${profile.label}")
        refreshDiagnostics(alertOnNew = false)   // seed readiness/freeze/DTCs (no alert)
    }

    private fun tripsForCurrent(): List<Trip> {
        val id = currentProfile?.id
        val all = tripStore.load()
        return if (id == null) all else all.filter { it.vehicleId == id || it.vehicleId.isEmpty() }
    }

    private fun updateWidget(mpg: Double?, status: String) {
        if (mpg != null) settings.widgetMpg = mpg
        settings.widgetStatus = status
        try { MpgWidget.refresh(app) } catch (e: Exception) {}
    }

    // ---- Phase 5: calibration profiles + fill-up wizard ----
    private fun loadCalibration(vehicleId: String) {
        val profs = calStore.profiles(vehicleId)
        val activeName = calStore.activeName(vehicleId)
        activeFactor = (profs.firstOrNull { it.name == activeName } ?: profs.first()).factor
        val fills = fillupStore.loadFor(vehicleId)
        state.update {
            it.copy(
                calProfiles = profs.map { p -> p.name },
                activeCalName = activeName,
                activeFactor = activeFactor,
                fillups = fills.take(20),
                monthlyFuelCost = monthlySpend(fills),
            )
        }
    }

    private fun monthlySpend(fills: List<Fillup>): Double {
        val now = java.util.Calendar.getInstance()
        val ym = now.get(java.util.Calendar.YEAR) * 100 + now.get(java.util.Calendar.MONTH)
        return fills.filter {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = it.timestamp }
            c.get(java.util.Calendar.YEAR) * 100 + c.get(java.util.Calendar.MONTH) == ym
        }.sumOf { it.cost }
    }

    /** Log a pump fill-up: auto-calibrate the active profile and record cost. */
    fun logFillup(gallons: Double, pricePerGal: Double) {
        scope.launch {
            val vehicleId = currentProfile?.id ?: return@launch
            val logged = sinceGal
            val miles = sinceMiles
            if (logged > 0.05 && gallons > 0.05) {
                val newFactor = (activeFactor * (gallons / logged)).coerceIn(0.3, 3.0)
                activeFactor = newFactor
                calStore.upsert(vehicleId, CalProfile(state.value.activeCalName, newFactor))
            }
            fillupStore.add(
                Fillup(vehicleId, System.currentTimeMillis(), gallons, pricePerGal, miles, logged)
            )
            settings.lastPricePerGal = pricePerGal
            sinceGal = 0.0; sinceMiles = 0.0
            settings.galSinceFillup = 0.0; settings.milesSinceFillup = 0.0
            val fills = fillupStore.loadFor(vehicleId)
            state.update {
                it.copy(
                    activeFactor = activeFactor,
                    calProfiles = calStore.profiles(vehicleId).map { p -> p.name },
                    galSinceFillup = 0.0, milesSinceFillup = 0.0,
                    lastPricePerGal = pricePerGal,
                    fillups = fills.take(20),
                    monthlyFuelCost = monthlySpend(fills),
                )
            }
        }
    }

    fun setCalProfile(name: String) {
        val vehicleId = currentProfile?.id ?: return
        calStore.setActive(vehicleId, name)
        activeFactor = calStore.active(vehicleId).factor
        state.update { it.copy(activeCalName = name, activeFactor = activeFactor) }
    }

    fun addCalProfile(name: String) {
        val vehicleId = currentProfile?.id ?: return
        if (name.isBlank()) return
        calStore.upsert(vehicleId, CalProfile(name, 1.0))
        calStore.setActive(vehicleId, name)
        activeFactor = 1.0
        state.update {
            it.copy(
                calProfiles = calStore.profiles(vehicleId).map { p -> p.name },
                activeCalName = name, activeFactor = 1.0,
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
        updateWidget(null, "Disconnected")
    }

    // ---- Phase 8: maintenance + polish ----
    fun setKeepScreenOn(on: Boolean) {
        settings.keepScreenOn = on
        state.update { it.copy(keepScreenOn = on) }
    }

    fun markServiceDone(name: String) {
        val id = currentProfile?.id ?: return
        maintStore.markDone(id, name)
        maintNotified.remove(name)
        state.update { it.copy(maintenance = maintStore.items(id)) }
    }

    fun addServiceItem(name: String, intervalMiles: Int) {
        val id = currentProfile?.id ?: return
        if (name.isBlank() || intervalMiles <= 0) return
        maintStore.addItem(id, name, intervalMiles)
        state.update { it.copy(maintenance = maintStore.items(id)) }
    }

    fun setOdometer(miles: Double) {
        val id = currentProfile?.id ?: return
        maintStore.setOdometer(id, miles)
        state.update { it.copy(odometer = maintStore.odometer(id), maintenance = maintStore.items(id)) }
    }

    /** Phase 9: experimental manufacturer mode-22 read. Decoding is unverified. */
    fun queryEnhanced(pidHex: String) {
        val clean = pidHex.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        if (clean.length < 2) {
            state.update { it.copy(enhancedResult = "Enter a hex PID, e.g. 1E1C") }; return
        }
        scope.launch {
            state.update { it.copy(enhancedResult = "Querying 22 $clean …") }
            val bytes = try { obd.mode22(clean) } catch (e: Exception) { null }
            val text = if (bytes == null) {
                "22 $clean → no data (unsupported PID, wrong module, or gateway-blocked)"
            } else {
                val hex = bytes.joinToString(" ") { "%02X".format(it) }
                val sb = StringBuilder("22 $clean → raw: $hex")
                if (bytes.size >= 2) {
                    val u16 = bytes[0] * 256 + bytes[1]
                    sb.append("  | uint16: $u16  | /10: ${"%.1f".format(u16 / 10.0)}")
                    sb.append("  | temp(A-40): ${bytes[0] - 40}")
                }
                sb.toString()
            }
            state.update { it.copy(enhancedResult = text) }
        }
    }

    // ---- polling ----
    private fun startPolling() {
        pollJob?.cancel()
        // Arm battery-health capture: record the lowest volts in the next ~8s.
        captureUntilMs = System.currentTimeMillis() + 8000
        captureMin = Double.MAX_VALUE
        batteryCaptured = false
        pollJob = scope.launch {
            var lastNs = System.nanoTime()
            while (isActive) {
                val reading = try { pollOnce() } catch (e: Exception) { null }
                if (reading == null || !obd.isConnected) { onDrop(); break }
                val now = System.nanoTime()
                val dtHours = (now - lastNs) / 1e9 / 3600.0
                lastNs = now
                applyReading(reading, dtHours)
                updateLivePids(reading)
                checkAlerts(reading)
                maybeCheckDtcs()
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
        val rawGph = when (snap.mpgMethod) {
            MpgMethod.FUEL_RATE -> ObdMath.gphFromFuelRate(r.fuelRate ?: 0.0)
            MpgMethod.MAF -> ObdMath.gphFromMaf(r.maf ?: 0.0, r.lam, diesel)
            MpgMethod.SPEED_DENSITY ->
                ObdMath.gphSpeedDensity(r.rpm, r.mapk, r.iat, r.lam, snap.ve, snap.displacementL, diesel)
            MpgMethod.NONE -> 0.0
        }
        val gph = rawGph * activeFactor          // apply active calibration profile
        val inst = ObdMath.instMpg(r.mph, gph)

        // Fuel accounting since last fill-up: all fuel burned, in a trip or not.
        sinceGal += gph * dtHours
        sinceMiles += r.mph * dtHours

        var active = snap.tripActive
        if (!active && r.mph > 0.5) {
            active = true
            tripStartMs = System.currentTimeMillis()
            firedAlerts.clear()                  // new trip -> alerts fire again
            resetCoach()
            if (settings.blackBoxEnabled) blackBox.start(tripStartMs)
        }
        val addMiles = if (active) r.mph * dtHours else 0.0
        val addGals = if (active) gph * dtHours else 0.0

        // ---- Phase 7: boost / coach / performance timers ----
        val dtSec = dtHours * 3600.0
        val boostKpa = (r.mapk - baroKpa).coerceAtLeast(0.0)
        var turbo = snap.isTurbo
        if (!turbo && r.mapk > baroKpa + 10.0) { turbo = true; markTurbo() }
        if (active) {
            peakBoostKpa = maxOf(peakBoostKpa, boostKpa)
            coachActiveSec += dtSec
            if (r.rpm > 300 && r.mph < 1.0) { coachIdleSec += dtSec; coachIdleGal += gph * dtHours }
            r.load?.let { if (it > 75.0) coachHighLoadSec += dtSec }
            val accel = if (dtSec > 0.05) (r.mph - prevMph) / dtSec else 0.0
            if (accel > 7.0 && !accelArmed) { coachHardAccels++; accelArmed = true }
            if (accel < 3.0) accelArmed = false
        }
        val nowMs = System.currentTimeMillis()
        if (!launchActive && prevMph < 1.0 && r.mph >= 1.0) {
            launchActive = true; launchStartMs = nowMs; launchDist = 0.0; run060 = 0.0; runQuarter = 0.0
        }
        if (launchActive) {
            launchDist += r.mph * dtHours
            val el = (nowMs - launchStartMs) / 1000.0
            if (run060 == 0.0 && r.mph >= 60.0) {
                run060 = el; if (best060 == 0.0 || el < best060) best060 = el
            }
            if (runQuarter == 0.0 && launchDist >= 0.25) {
                runQuarter = el
                if (bestQuarter == 0.0 || el < bestQuarter) { bestQuarter = el; bestQuarterMph = r.mph }
            }
            if (r.mph < 1.0) launchActive = false
        }
        prevMph = r.mph

        val idlePct = if (coachActiveSec > 0) coachIdleSec / coachActiveSec * 100.0 else 0.0
        val highPct = if (coachActiveSec > 0) coachHighLoadSec / coachActiveSec * 100.0 else 0.0
        val score = (100.0 - idlePct * 0.5 - coachHardAccels * 3.0 - highPct * 0.2)
            .coerceIn(0.0, 100.0).toInt()
        val tripGal = snap.tripGallons + addGals
        val idleFuelPct = if (tripGal > 1e-6) coachIdleGal / tripGal * 100.0 else 0.0
        val tip = when {
            idleFuelPct > 15 -> "${idleFuelPct.roundToInt()}% of fuel burned at idle — shut off when parked."
            coachHardAccels >= 5 -> "$coachHardAccels hard accelerations — ease into the throttle for MPG."
            highPct > 40 -> "High load ${highPct.roundToInt()}% of the drive — anticipate and coast more."
            active -> "Smooth so far — nice work."
            else -> ""
        }

        state.update { st ->
            st.copy(
                readings = Readings(r.rpm, r.mph, r.mapk, r.iat, r.coolant, r.throttle,
                    r.load, r.fuel, r.volts, r.maf, r.fuelRate, r.lam, inst),
                tripActive = active,
                tripMiles = st.tripMiles + addMiles,
                tripGallons = st.tripGallons + addGals,
                tripElapsedSec = if (active) (System.currentTimeMillis() - tripStartMs) / 1000
                else st.tripElapsedSec,
                galSinceFillup = sinceGal,
                milesSinceFillup = sinceMiles,
                isTurbo = turbo,
                boostPsi = boostKpa * 0.145038,
                peakBoostPsi = peakBoostKpa * 0.145038,
                coachScore = score,
                coachHardAccels = coachHardAccels,
                coachIdlePct = idlePct,
                coachHighLoadPct = highPct,
                coachTip = tip,
                best060Sec = best060,
                bestQuarterSec = bestQuarter,
                bestQuarterMph = bestQuarterMph,
            )
        }
        if (blackBox.active && active) {
            val tSec = (System.currentTimeMillis() - tripStartMs) / 1000.0
            blackBox.log(tSec, r.mph, r.rpm, r.mapk, r.iat, r.coolant, r.throttle,
                r.load, r.fuel, r.volts, r.lam, r.maf, r.fuelRate, inst)
        }
        captureBattery(r)
        maybePersistActiveTrip()
    }

    private fun captureBattery(r: Raw) {
        if (batteryCaptured) return
        val v = r.volts ?: return
        val now = System.currentTimeMillis()
        if (now <= captureUntilMs) {
            captureMin = minOf(captureMin, v)
        } else {
            batteryCaptured = true
            val crankV = if (captureMin < Double.MAX_VALUE) captureMin else v
            batteryStore.add(CrankReading(now, crankV))
            state.update {
                it.copy(batteryReadings = batteryStore.load().take(30),
                    batteryAvg = batteryStore.recentAverage())
            }
            if (settings.alertsEnabled && crankV < 11.8)
                Alerts.post(app, "battery".hashCode(), "Weak battery",
                    "Start voltage ${"%.1f".format(crankV)} V is low — battery/charging may be failing.")
        }
    }

    private fun markTurbo() {
        val p = currentProfile ?: return
        if (!p.turbo) {
            currentProfile = p.copy(turbo = true)
            vehicleStore.save(currentProfile!!)
        }
    }

    private fun resetCoach() {
        coachActiveSec = 0.0; coachIdleSec = 0.0; coachHighLoadSec = 0.0
        coachIdleGal = 0.0; coachHardAccels = 0; peakBoostKpa = 0.0; accelArmed = false
    }

    private fun maybePersistActiveTrip() {
        val now = System.currentTimeMillis()
        if (now - lastPersistMs > 5000) {
            lastPersistMs = now
            val s = state.value
            if (s.tripActive) settings.saveActiveTrip(tripStartMs, s.tripMiles, s.tripGallons)
            settings.galSinceFillup = sinceGal      // survives across trips / reboot
            settings.milesSinceFillup = sinceMiles
        }
    }

    // ---- Phase 4: live-everything scan, alerts, periodic DTC poll ----
    private suspend fun updateLivePids(r: Raw) {
        // Fold this cycle's core reads in directly.
        liveMap[0x0C] = r.rpm
        liveMap[0x0D] = r.mph * ObdMath.KPH_PER_MPH
        liveMap[0x0B] = r.mapk
        liveMap[0x0F] = r.iat
        liveMap[0x44] = r.lam
        r.coolant?.let { liveMap[0x05] = it }
        r.throttle?.let { liveMap[0x11] = it }
        r.load?.let { liveMap[0x04] = it }
        r.fuel?.let { liveMap[0x2F] = it }
        r.volts?.let { liveMap[0x42] = it }
        r.maf?.let { liveMap[0x10] = it }
        r.fuelRate?.let { liveMap[0x5E] = it }
        // Rotate through the OTHER supported PIDs, but throttled so the core
        // gauges/MPG stay responsive (extra reads share the serial link).
        val now = System.currentTimeMillis()
        if (now - lastScanMs > 1500) {
            lastScanMs = now
            val extras = (currentProfile?.supportedPids ?: emptySet())
                .filter { it in PidRegistry.byPid && it !in corePids }.sorted()
            if (extras.isNotEmpty()) {
                repeat(minOf(5, extras.size)) {
                    val pid = extras[scanCursor % extras.size]
                    scanCursor++
                    val spec = PidRegistry.byPid[pid]
                    if (spec != null) {
                        val d = spec.decode(obd.queryPid(pid) ?: emptyList())
                        if (d != null) liveMap[pid] = d
                    }
                }
            }
        }
        state.update { it.copy(livePids = liveMap.toMap()) }
    }

    private fun checkAlerts(r: Raw) {
        if (!settings.alertsEnabled) return
        fun fire(key: String, title: String, text: String) {
            if (firedAlerts.add(key)) Alerts.post(app, key.hashCode(), title, text)
        }
        r.coolant?.let { c ->
            val f = c * 9.0 / 5.0 + 32.0
            if (f > settings.coolantMaxF)
                fire("coolant", "High coolant temperature",
                    "Coolant ${"%.0f".format(f)}°F is over ${settings.coolantMaxF.toInt()}°F.")
        }
        r.volts?.let { v ->
            val running = r.rpm > 300
            val thr = if (running) 13.2 else 12.0
            if (v < thr) fire("volt", "Low system voltage",
                "${"%.1f".format(v)} V (${if (running) "running" else "engine off"}) is below $thr V.")
        }
        r.fuel?.let { fl ->
            if (fl < settings.lowFuelPct)
                fire("fuel", "Low fuel", "Fuel level is ${"%.0f".format(fl)}%.")
        }
    }

    private suspend fun maybeCheckDtcs() {
        val now = System.currentTimeMillis()
        if (now - lastDtcCheckMs < 60_000) return
        lastDtcCheckMs = now
        refreshDiagnostics(alertOnNew = true)
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
        firedAlerts.clear()
        resetCoach()
        if (settings.blackBoxEnabled) blackBox.start(tripStartMs)
        settings.saveActiveTrip(tripStartMs, 0.0, 0.0)
        state.update { it.copy(tripActive = true, tripMiles = 0.0, tripGallons = 0.0, tripElapsedSec = 0) }
    }

    fun stopTrip() = autoSaveActiveTrip()

    private fun autoSaveActiveTrip() {
        val s = state.value
        val vehicleId = currentProfile?.id ?: ""
        if (s.tripActive && (s.tripMiles > 0.0 || s.tripGallons > 0.0)) {
            val idlePct = if (coachActiveSec > 0) coachIdleSec / coachActiveSec * 100.0 else 0.0
            val highPct = if (coachActiveSec > 0) coachHighLoadSec / coachActiveSec * 100.0 else 0.0
            val score = (100.0 - idlePct * 0.5 - coachHardAccels * 3.0 - highPct * 0.2)
                .coerceIn(0.0, 100.0).toInt()
            tripStore.add(
                Trip(
                    startedAt = tripStartMs,
                    durationSec = (System.currentTimeMillis() - tripStartMs) / 1000,
                    miles = s.tripMiles,
                    gallons = s.tripGallons,
                    score = score,
                    idlePct = idlePct,
                    hardAccels = coachHardAccels,
                    peakBoostPsi = peakBoostKpa * 0.145038,
                    vehicleId = vehicleId,
                )
            )
            if (vehicleId.isNotEmpty()) {
                val odo = maintStore.addMiles(vehicleId, s.tripMiles)
                if (settings.alertsEnabled) {
                    maintStore.items(vehicleId).filter { it.due(odo) }.forEach { d ->
                        if (maintNotified.add(d.name))
                            Alerts.post(app, ("maint" + d.name).hashCode(),
                                "Maintenance due", "${d.name} is due (interval ${d.intervalMiles} mi).")
                    }
                }
            }
            val mpg = if (s.tripGallons > 1e-6) s.tripMiles / s.tripGallons else 0.0
            updateWidget(mpg, "Last trip ${"%.1f".format(mpg)} MPG")
        }
        blackBox.stop()
        blackBox.prune(settings.maxLogs)
        settings.clearActiveTrip()
        state.update {
            it.copy(tripActive = false, tripMiles = 0.0, tripGallons = 0.0,
                tripElapsedSec = 0, trips = tripsForCurrent(),
                logNames = blackBox.listLogs().map { f -> f.name },
                odometer = if (vehicleId.isNotEmpty()) maintStore.odometer(vehicleId) else it.odometer,
                maintenance = if (vehicleId.isNotEmpty()) maintStore.items(vehicleId) else it.maintenance)
        }
    }

    fun setBlackBoxEnabled(enabled: Boolean) {
        settings.blackBoxEnabled = enabled
        if (!enabled) blackBox.stop()
        else if (state.value.tripActive) blackBox.start(tripStartMs)
        state.update { it.copy(blackBoxEnabled = enabled) }
    }

    /** Root-cause analyzer: snapshot the relevant PIDs and rank causes. */
    fun analyzeDtc(code: String) {
        scope.launch {
            state.update { it.copy(diagnosing = true, diagnosis = null) }
            val snap = Diagnostics.Snapshot(
                stft1 = ObdMath.fuelTrimPct(obd.queryPid(0x06) ?: emptyList()),
                ltft1 = ObdMath.fuelTrimPct(obd.queryPid(0x07) ?: emptyList()),
                stft2 = ObdMath.fuelTrimPct(obd.queryPid(0x08) ?: emptyList()),
                ltft2 = ObdMath.fuelTrimPct(obd.queryPid(0x09) ?: emptyList()),
                lambda = ObdMath.lambda(obd.queryPid(0x44) ?: emptyList()),
                coolantC = ObdMath.coolantC(obd.queryPid(0x05) ?: emptyList()),
                load = ObdMath.engineLoadPct(obd.queryPid(0x04) ?: emptyList()),
                rpm = ObdMath.rpm(obd.queryPid(0x0C) ?: emptyList()),
                mapKpa = ObdMath.mapKpa(obd.queryPid(0x0B) ?: emptyList()),
            )
            state.update { it.copy(diagnosing = false, diagnosis = Diagnostics.analyze(code, snap)) }
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
            refreshDiagnostics(alertOnNew = false)  // codes + readiness + freeze frame
        }
    }

    fun clearDtcs() {
        scope.launch {
            state.update { it.copy(dtcMessage = "Clearing…") }
            val ok = try { obd.clearDtcs() } catch (e: Exception) { false }
            if (ok) {
                knownDtcs = emptySet()
                state.update {
                    it.copy(dtcs = emptyList(), freezeDtc = null,
                        dtcMessage = "Cleared. (Real faults will return.)")
                }
            } else {
                state.update { it.copy(dtcMessage = "Clear failed") }
            }
        }
    }
}

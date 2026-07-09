package com.truempg.app

import com.truempg.app.data.CrankReading
import com.truempg.app.data.Fillup
import com.truempg.app.data.ServiceItem
import com.truempg.app.data.Trip
import com.truempg.app.obd.Diagnostics.Diagnosis
import com.truempg.app.obd.ObdMath.MpgMethod
import com.truempg.app.obd.ObdMath.Readiness

/** A paired Bluetooth adapter shown in the Connect list. */
data class DeviceInfo(val name: String, val address: String)

/** Live decoded values. Optional gauges are null when the vehicle lacks the PID. */
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
    val mafGps: Double? = null,
    val fuelRateLph: Double? = null,
    val lambda: Double = 1.0,
    val instMpg: Double = 0.0,   // canonical MPG (US); UI converts for display
)

/** Single source of truth for the whole app (held in ObdRepository). */
data class UiState(
    val bluetoothReady: Boolean = true,
    val devices: List<DeviceInfo> = emptyList(),
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Not connected",
    val readings: Readings = Readings(),
    // vehicle profile / capability
    val vin: String? = null,
    val vehicleLabel: String = "Vehicle",
    val protocol: String? = null,
    val supportedPids: Set<Int> = emptySet(),
    val fuelType: Int = 1,
    val displacementL: Double = 2.7,
    val mpgMethod: MpgMethod = MpgMethod.NONE,
    val methodOverride: MpgMethod? = null,
    // fuel model + units
    val ve: Double = 0.85,
    val distanceUnit: String = "MPH",
    val economyUnit: String = "MPG_US",
    val tempUnit: String = "C",
    // automatic operation
    val autoConnect: Boolean = true,
    val savedAdapter: String? = null,
    // trip
    val tripActive: Boolean = false,
    val tripMiles: Double = 0.0,
    val tripGallons: Double = 0.0,
    val tripElapsedSec: Long = 0L,
    val trips: List<Trip> = emptyList(),
    // codes / diagnostics
    val dtcs: List<String> = emptyList(),
    val dtcMessage: String = "",
    val readiness: Readiness? = null,
    val freezeDtc: String? = null,
    // live "everything" view: pid -> latest decoded value
    val livePids: Map<Int, Double> = emptyMap(),
    // alerts
    val alertsEnabled: Boolean = true,
    val coolantMaxF: Double = 230.0,
    val lowFuelPct: Double = 10.0,
    // calibration + fuel accounting
    val calProfiles: List<String> = listOf("Standard"),
    val activeCalName: String = "Standard",
    val activeFactor: Double = 1.0,
    val galSinceFillup: Double = 0.0,
    val milesSinceFillup: Double = 0.0,
    val lastPricePerGal: Double = 0.0,
    val fillups: List<Fillup> = emptyList(),
    val monthlyFuelCost: Double = 0.0,
    // Phase 6: black box, battery health, root-cause analyzer
    val blackBoxEnabled: Boolean = false,
    val logNames: List<String> = emptyList(),
    val batteryReadings: List<CrankReading> = emptyList(),
    val batteryAvg: Double? = null,
    val diagnosing: Boolean = false,
    val diagnosis: Diagnosis? = null,
    // Phase 7: driving coach, boost, performance timers
    val isTurbo: Boolean = false,
    val boostPsi: Double = 0.0,
    val peakBoostPsi: Double = 0.0,
    val coachScore: Int = 100,
    val coachHardAccels: Int = 0,
    val coachIdlePct: Double = 0.0,
    val coachHighLoadPct: Double = 0.0,
    val coachTip: String = "",
    val best060Sec: Double = 0.0,
    val bestQuarterSec: Double = 0.0,
    val bestQuarterMph: Double = 0.0,
    // Phase 8: maintenance, multi-vehicle, polish
    val odometer: Double = 0.0,
    val maintenance: List<ServiceItem> = emptyList(),
    val knownVehicles: List<String> = emptyList(),
    val keepScreenOn: Boolean = true,
)

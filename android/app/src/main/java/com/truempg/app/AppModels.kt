package com.truempg.app

import com.truempg.app.data.Trip
import com.truempg.app.obd.ObdMath.MpgMethod

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
    // automatic operation
    val autoConnect: Boolean = true,
    val savedAdapter: String? = null,
    // trip
    val tripActive: Boolean = false,
    val tripMiles: Double = 0.0,
    val tripGallons: Double = 0.0,
    val tripElapsedSec: Long = 0L,
    val trips: List<Trip> = emptyList(),
    // codes
    val dtcs: List<String> = emptyList(),
    val dtcMessage: String = "",
)

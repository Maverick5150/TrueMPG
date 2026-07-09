package com.truempg.app

import com.truempg.app.data.Trip

/** A paired Bluetooth adapter shown in the Connect list. */
data class DeviceInfo(val name: String, val address: String)

/** Live decoded values for the Drive tab / notification. */
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

/** Single source of truth for the whole app (held in ObdRepository). */
data class UiState(
    val bluetoothReady: Boolean = true,
    val devices: List<DeviceInfo> = emptyList(),
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Not connected",
    val readings: Readings = Readings(),
    val ve: Double = 0.85,
    val autoConnect: Boolean = true,
    val savedAdapter: String? = null,
    val tripActive: Boolean = false,
    val tripMiles: Double = 0.0,
    val tripGallons: Double = 0.0,
    val tripElapsedSec: Long = 0L,
    val trips: List<Trip> = emptyList(),
    val dtcs: List<String> = emptyList(),
    val dtcMessage: String = "",
)

package com.truempg.app.data

import android.content.Context

/**
 * Tiny SharedPreferences wrapper: user settings plus the in-progress trip so a
 * mid-drive phone reboot doesn't lose it. Doubles (VE, trip totals) are stored
 * as raw Long bits since SharedPreferences has no double type.
 */
class Settings(context: Context) {
    private val p = context.applicationContext
        .getSharedPreferences("truempg", Context.MODE_PRIVATE)

    var autoConnect: Boolean
        get() = p.getBoolean("autoConnect", true)
        set(v) { p.edit().putBoolean("autoConnect", v).apply() }

    var savedAdapterAddress: String?
        get() = p.getString("savedAdapter", null)
        set(v) { p.edit().putString("savedAdapter", v).apply() }

    /** Last negotiated ELM protocol number (e.g. "6") to speed up reconnects. */
    var lastProtocol: String?
        get() = p.getString("lastProtocol", null)
        set(v) { p.edit().putString("lastProtocol", v).apply() }

    /** "MPH" or "KMH" */
    var distanceUnit: String
        get() = p.getString("distanceUnit", "MPH") ?: "MPH"
        set(v) { p.edit().putString("distanceUnit", v).apply() }

    /** "MPG_US", "MPG_IMP", or "L_PER_100KM" */
    var economyUnit: String
        get() = p.getString("economyUnit", "MPG_US") ?: "MPG_US"
        set(v) { p.edit().putString("economyUnit", v).apply() }

    /** "C" or "F" */
    var tempUnit: String
        get() = p.getString("tempUnit", "C") ?: "C"
        set(v) { p.edit().putString("tempUnit", v).apply() }

    // ---- alert thresholds ----
    var alertsEnabled: Boolean
        get() = p.getBoolean("alertsEnabled", true)
        set(v) { p.edit().putBoolean("alertsEnabled", v).apply() }

    /** Coolant alert threshold in °F. */
    var coolantMaxF: Double
        get() = Double.fromBits(p.getLong("coolantMaxF", (230.0).toRawBits()))
        set(v) { p.edit().putLong("coolantMaxF", v.toRawBits()).apply() }

    /** Low-fuel alert threshold in %. */
    var lowFuelPct: Double
        get() = Double.fromBits(p.getLong("lowFuelPct", (10.0).toRawBits()))
        set(v) { p.edit().putLong("lowFuelPct", v.toRawBits()).apply() }

    // ---- fuel accounting (since last fill-up) ----
    var galSinceFillup: Double
        get() = Double.fromBits(p.getLong("galSinceFillup", (0.0).toRawBits()))
        set(v) { p.edit().putLong("galSinceFillup", v.toRawBits()).apply() }

    var milesSinceFillup: Double
        get() = Double.fromBits(p.getLong("milesSinceFillup", (0.0).toRawBits()))
        set(v) { p.edit().putLong("milesSinceFillup", v.toRawBits()).apply() }

    var lastPricePerGal: Double
        get() = Double.fromBits(p.getLong("lastPricePerGal", (0.0).toRawBits()))
        set(v) { p.edit().putLong("lastPricePerGal", v.toRawBits()).apply() }

    // ---- black-box logging ----
    var blackBoxEnabled: Boolean
        get() = p.getBoolean("blackBoxEnabled", false)
        set(v) { p.edit().putBoolean("blackBoxEnabled", v).apply() }

    var maxLogs: Int
        get() = p.getInt("maxLogs", 20)
        set(v) { p.edit().putInt("maxLogs", v).apply() }

    // ---- UI polish + widget ----
    var keepScreenOn: Boolean
        get() = p.getBoolean("keepScreenOn", true)
        set(v) { p.edit().putBoolean("keepScreenOn", v).apply() }

    /** Last completed-trip MPG and status text, cached for the home-screen widget. */
    var widgetMpg: Double
        get() = Double.fromBits(p.getLong("widgetMpg", (0.0).toRawBits()))
        set(v) { p.edit().putLong("widgetMpg", v.toRawBits()).apply() }

    var widgetStatus: String
        get() = p.getString("widgetStatus", "Not connected") ?: "Not connected"
        set(v) { p.edit().putString("widgetStatus", v).apply() }

    var ve: Double
        get() = Double.fromBits(p.getLong("ve", (0.85).toRawBits()))
        set(v) { p.edit().putLong("ve", v.toRawBits()).apply() }

    data class ActiveTrip(val startedAt: Long, val miles: Double, val gallons: Double)

    fun saveActiveTrip(startedAt: Long, miles: Double, gallons: Double) {
        p.edit()
            .putBoolean("at_active", true)
            .putLong("at_started", startedAt)
            .putLong("at_miles", miles.toRawBits())
            .putLong("at_gallons", gallons.toRawBits())
            .apply()
    }

    fun clearActiveTrip() {
        p.edit().putBoolean("at_active", false)
            .remove("at_started").remove("at_miles").remove("at_gallons").apply()
    }

    fun loadActiveTrip(): ActiveTrip? {
        if (!p.getBoolean("at_active", false)) return null
        return ActiveTrip(
            p.getLong("at_started", 0L),
            Double.fromBits(p.getLong("at_miles", 0L)),
            Double.fromBits(p.getLong("at_gallons", 0L)),
        )
    }
}

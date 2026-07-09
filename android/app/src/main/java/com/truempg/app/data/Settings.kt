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

package com.truempg.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One completed trip. */
data class Trip(
    val startedAt: Long,
    val durationSec: Long,
    val miles: Double,
    val gallons: Double,
) {
    val avgMpg: Double get() = if (gallons > 1e-6) miles / gallons else 0.0
}

/**
 * Dead-simple persistent trip log: a JSON array in the app's private files dir.
 * No database / no annotation processing -> nothing extra for the build to break on.
 */
class TripStore(context: Context) {
    private val file = File(context.filesDir, "trips.json")

    fun load(): List<Trip> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Trip(
                    startedAt = o.getLong("startedAt"),
                    durationSec = o.getLong("durationSec"),
                    miles = o.getDouble("miles"),
                    gallons = o.getDouble("gallons"),
                )
            }.sortedByDescending { it.startedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(trip: Trip) {
        val all = load().toMutableList()
        all.add(trip)
        save(all)
    }

    fun clear() = save(emptyList())

    private fun save(trips: List<Trip>) {
        val arr = JSONArray()
        trips.forEach { t ->
            arr.put(
                JSONObject()
                    .put("startedAt", t.startedAt)
                    .put("durationSec", t.durationSec)
                    .put("miles", t.miles)
                    .put("gallons", t.gallons)
            )
        }
        file.writeText(arr.toString())
    }
}

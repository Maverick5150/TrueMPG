package com.truempg.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A service item with a mileage interval, counted down from driven miles. */
data class ServiceItem(val name: String, val intervalMiles: Int, val lastServiceMiles: Double) {
    fun remaining(odometer: Double): Double = (lastServiceMiles + intervalMiles) - odometer
    fun due(odometer: Double): Boolean = remaining(odometer) <= 0.0
}

/**
 * Per-vehicle maintenance: a running odometer (driven miles accumulated by the
 * app, plus an optional user base) and service items. JSON keyed by vehicle id.
 */
class MaintenanceStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "maintenance.json")

    private val defaults = listOf(
        "Oil change" to 5000,
        "Tire rotation" to 7500,
        "Engine air filter" to 20000,
        "Cabin air filter" to 20000,
        "Spark plugs" to 60000,
        "Brake fluid" to 45000,
    )

    private fun root(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun save(r: JSONObject) = file.writeText(r.toString())

    fun odometer(vehicleId: String): Double =
        root().optJSONObject(vehicleId)?.optDouble("odo", 0.0) ?: 0.0

    fun items(vehicleId: String): List<ServiceItem> {
        val o = root().optJSONObject(vehicleId)
        val arr = o?.optJSONArray("items")
        if (arr == null || arr.length() == 0) {
            val odo = o?.optDouble("odo", 0.0) ?: 0.0
            return defaults.map { ServiceItem(it.first, it.second, odo) }
        }
        return (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            ServiceItem(p.getString("name"), p.getInt("interval"), p.getDouble("last"))
        }
    }

    private fun writeVehicle(vehicleId: String, odo: Double, items: List<ServiceItem>) {
        val r = root()
        val o = r.optJSONObject(vehicleId) ?: JSONObject()
        o.put("odo", odo)
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("name", it.name).put("interval", it.intervalMiles)
                .put("last", it.lastServiceMiles))
        }
        o.put("items", arr)
        r.put(vehicleId, o); save(r)
    }

    /** Add driven miles to the odometer; returns the new odometer. */
    fun addMiles(vehicleId: String, miles: Double): Double {
        val odo = odometer(vehicleId) + miles
        writeVehicle(vehicleId, odo, items(vehicleId))
        return odo
    }

    fun setOdometer(vehicleId: String, miles: Double) =
        writeVehicle(vehicleId, miles.coerceAtLeast(0.0), items(vehicleId))

    fun markDone(vehicleId: String, name: String) {
        val odo = odometer(vehicleId)
        val updated = items(vehicleId).map {
            if (it.name == name) it.copy(lastServiceMiles = odo) else it
        }
        writeVehicle(vehicleId, odo, updated)
    }

    fun addItem(vehicleId: String, name: String, intervalMiles: Int) {
        val odo = odometer(vehicleId)
        val updated = items(vehicleId).filter { it.name != name } + ServiceItem(name, intervalMiles, odo)
        writeVehicle(vehicleId, odo, updated)
    }
}

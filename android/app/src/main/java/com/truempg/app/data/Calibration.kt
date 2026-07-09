package com.truempg.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A named calibration profile (tow/haul). `factor` multiplies the estimated
 * fuel flow so MPG stays accurate under different loads. 1.0 = uncalibrated.
 */
data class CalProfile(val name: String, val factor: Double)

/** One logged fill-up: pump gallons + price, and what the app measured since. */
data class Fillup(
    val vehicleId: String,
    val timestamp: Long,
    val gallons: Double,
    val pricePerGal: Double,
    val milesSince: Double,
    val loggedGalSince: Double,
) {
    val actualMpg: Double get() = if (gallons > 0.01) milesSince / gallons else 0.0
    val cost: Double get() = gallons * pricePerGal
}

/** Per-vehicle calibration profiles, stored as JSON keyed by vehicle id. */
class CalibrationStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "calibration.json")

    private fun root(): JSONObject = try {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun save(r: JSONObject) = file.writeText(r.toString())

    private val default get() = listOf(CalProfile("Standard", 1.0))

    fun profiles(vehicleId: String): List<CalProfile> {
        val o = root().optJSONObject(vehicleId) ?: return default
        val arr = o.optJSONArray("profiles") ?: return default
        val list = (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            CalProfile(p.getString("name"), p.getDouble("factor"))
        }
        return list.ifEmpty { default }
    }

    fun activeName(vehicleId: String): String {
        val o = root().optJSONObject(vehicleId) ?: return "Standard"
        return o.optString("active", "Standard").ifEmpty { "Standard" }
    }

    fun active(vehicleId: String): CalProfile {
        val name = activeName(vehicleId)
        val list = profiles(vehicleId)
        return list.firstOrNull { it.name == name } ?: list.first()
    }

    fun setActive(vehicleId: String, name: String) {
        val r = root()
        val o = r.optJSONObject(vehicleId) ?: JSONObject()
        if (!o.has("profiles")) o.put("profiles", toJson(default))
        o.put("active", name)
        r.put(vehicleId, o); save(r)
    }

    /** Insert or replace a profile by name. */
    fun upsert(vehicleId: String, profile: CalProfile) {
        val r = root()
        val o = r.optJSONObject(vehicleId) ?: JSONObject()
        val updated = profiles(vehicleId).filter { it.name != profile.name }.toMutableList()
        updated.add(profile)
        o.put("profiles", toJson(updated))
        if (!o.has("active")) o.put("active", profile.name)
        r.put(vehicleId, o); save(r)
    }

    private fun toJson(list: List<CalProfile>): JSONArray {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("factor", it.factor)) }
        return arr
    }
}

/** Fill-up history across vehicles, stored as a flat JSON array. */
class FillupStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "fillups.json")

    fun loadAll(): List<Fillup> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Fillup(
                    o.getString("vehicleId"), o.getLong("timestamp"), o.getDouble("gallons"),
                    o.getDouble("price"), o.getDouble("milesSince"), o.getDouble("loggedGalSince")
                )
            }.sortedByDescending { it.timestamp }
        } catch (e: Exception) { emptyList() }
    }

    fun loadFor(vehicleId: String) = loadAll().filter { it.vehicleId == vehicleId }

    fun add(f: Fillup) {
        val all = loadAll().toMutableList()
        all.add(f)
        val arr = JSONArray()
        all.forEach {
            arr.put(
                JSONObject()
                    .put("vehicleId", it.vehicleId).put("timestamp", it.timestamp)
                    .put("gallons", it.gallons).put("price", it.pricePerGal)
                    .put("milesSince", it.milesSince).put("loggedGalSince", it.loggedGalSince)
            )
        }
        file.writeText(arr.toString())
    }
}

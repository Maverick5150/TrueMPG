package com.truempg.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Lowest module voltage captured around an engine start (cranking dip). */
data class CrankReading(val timestamp: Long, val voltage: Double)

class BatteryStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "battery.json")

    fun load(): List<CrankReading> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                CrankReading(o.getLong("t"), o.getDouble("v"))
            }.sortedByDescending { it.timestamp }
        } catch (e: Exception) { emptyList() }
    }

    fun add(reading: CrankReading) {
        val all = (load() + reading).sortedByDescending { it.timestamp }.take(200)
        val arr = JSONArray()
        all.forEach { arr.put(JSONObject().put("t", it.timestamp).put("v", it.voltage)) }
        file.writeText(arr.toString())
    }

    /** Rolling average of the most recent N crank readings. */
    fun recentAverage(n: Int = 5): Double? {
        val r = load().take(n)
        return if (r.isEmpty()) null else r.map { it.voltage }.average()
    }
}

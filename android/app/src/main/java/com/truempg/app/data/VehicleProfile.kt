package com.truempg.app.data

import android.content.Context
import com.truempg.app.obd.ObdMath.MpgMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A discovered vehicle. Keyed by VIN when readable, else by the adapter MAC.
 * Holds the negotiated protocol, supported-PID set, fuel type, displacement
 * (for speed-density vehicles), and an optional MPG-method override.
 */
data class VehicleProfile(
    val id: String,
    val vin: String? = null,
    val label: String = "Vehicle",
    val protocol: String? = null,
    val supportedPids: Set<Int> = emptySet(),
    val fuelType: Int = ObdMathFuel.GASOLINE,
    val displacementL: Double = 2.7,
    val methodOverride: MpgMethod? = null,
)

/** Small indirection so the data class default doesn't import ObdMath directly. */
object ObdMathFuel { const val GASOLINE = 1 }

class VehicleStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "vehicles.json")

    fun loadAll(): List<VehicleProfile> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) { emptyList() }
    }

    fun find(id: String): VehicleProfile? = loadAll().firstOrNull { it.id == id }

    fun save(profile: VehicleProfile) {
        val all = loadAll().filter { it.id != profile.id }.toMutableList()
        all.add(profile)
        val arr = JSONArray()
        all.forEach { arr.put(toJson(it)) }
        file.writeText(arr.toString())
    }

    private fun toJson(p: VehicleProfile) = JSONObject().apply {
        put("id", p.id)
        put("vin", p.vin ?: JSONObject.NULL)
        put("label", p.label)
        put("protocol", p.protocol ?: JSONObject.NULL)
        put("pids", JSONArray().also { a -> p.supportedPids.sorted().forEach { a.put(it) } })
        put("fuelType", p.fuelType)
        put("displacementL", p.displacementL)
        put("methodOverride", p.methodOverride?.name ?: JSONObject.NULL)
    }

    private fun fromJson(o: JSONObject): VehicleProfile {
        val pids = HashSet<Int>()
        val a = o.optJSONArray("pids")
        if (a != null) for (i in 0 until a.length()) pids.add(a.getInt(i))
        val mo = o.optString("methodOverride", "")
        return VehicleProfile(
            id = o.getString("id"),
            vin = o.optString("vin", null.toString()).takeIf { it != "null" && it.isNotEmpty() },
            label = o.optString("label", "Vehicle"),
            protocol = o.optString("protocol", null.toString()).takeIf { it != "null" && it.isNotEmpty() },
            supportedPids = pids,
            fuelType = o.optInt("fuelType", ObdMathFuel.GASOLINE),
            displacementL = o.optDouble("displacementL", 2.7),
            methodOverride = if (mo.isEmpty() || mo == "null") null
            else runCatching { MpgMethod.valueOf(mo) }.getOrNull(),
        )
    }
}

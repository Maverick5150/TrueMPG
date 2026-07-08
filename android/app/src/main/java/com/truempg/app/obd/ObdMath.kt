package com.truempg.app.obd

/**
 * Pure OBD-II helpers: response parsing, PID formulas, speed-density MPG,
 * and DTC decoding. No Android dependencies -> easy to reason about and port
 * straight from the Python tools (mpg_logger.py / p0430_diag.py).
 */
object ObdMath {

    // ---- Engine / unit constants (2.7L EcoBoost, speed-density) ----
    const val DISPLACEMENT_L = 2.7
    const val R_AIR = 0.287          // kPa*L/(g*K)
    const val AFR_STOICH = 14.64
    const val GRAMS_PER_GAL = 2820.0 // ~745 g/L gasoline * 3.785 L/gal
    const val KPH_PER_MPH = 1.609344

    /**
     * Clean an ELM/STN reply and return the data bytes that follow the
     * expected "mode+0x40" / pid marker. Assumes ATS0 (no spaces) & ATH0
     * (no headers). Returns null if the marker/"NO DATA" isn't present.
     *
     * mode 0x01 request -> response starts "41" + pid; e.g. 010C -> "410C1AF8".
     */
    fun parseData(raw: String, mode: Int, pid: Int): List<Int>? {
        val s = raw.uppercase()
            .replace(">", "")
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
        if (s.contains("NODATA") || s.contains("UNABLETOCONNECT") ||
            s.contains("STOPPED") || s.contains("ERROR") || s.contains("?")
        ) return null
        val marker = "%02X%02X".format(mode + 0x40, pid)
        val idx = s.indexOf(marker)
        if (idx < 0) return null
        val hex = s.substring(idx + marker.length)
        val bytes = ArrayList<Int>()
        var i = 0
        while (i + 1 < hex.length + 1 && i + 2 <= hex.length) {
            val b = hex.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(b)
            i += 2
        }
        return if (bytes.isEmpty()) null else bytes
    }

    // ---- PID formulas (take the raw byte list from parseData) ----
    fun rpm(b: List<Int>) = if (b.size >= 2) ((b[0] * 256) + b[1]) / 4.0 else null
    fun speedKph(b: List<Int>) = b.getOrNull(0)?.toDouble()
    fun mapKpa(b: List<Int>) = b.getOrNull(0)?.toDouble()
    fun iatC(b: List<Int>) = b.getOrNull(0)?.let { it - 40.0 }
    fun coolantC(b: List<Int>) = b.getOrNull(0)?.let { it - 40.0 }
    fun throttlePct(b: List<Int>) = b.getOrNull(0)?.let { it * 100.0 / 255.0 }
    fun engineLoadPct(b: List<Int>) = b.getOrNull(0)?.let { it * 100.0 / 255.0 }
    fun fuelLevelPct(b: List<Int>) = b.getOrNull(0)?.let { it * 100.0 / 255.0 }
    fun timingAdv(b: List<Int>) = b.getOrNull(0)?.let { it / 2.0 - 64.0 }
    fun moduleVolts(b: List<Int>) =
        if (b.size >= 2) ((b[0] * 256) + b[1]) / 1000.0 else null

    fun lambda(b: List<Int>) =
        if (b.size >= 2) ((b[0] * 256) + b[1]) / 32768.0 else null

    fun fuelTrimPct(b: List<Int>) = b.getOrNull(0)?.let { (it - 128) * 100.0 / 128.0 }

    /**
     * Speed-density fuel flow in US gallons/hour.
     * air_gps = (rpm/120) * disp * VE * MAP / (R_air * IAT_K)
     * fuel_gps = air_gps / (AFR * lambda)
     */
    fun gph(rpm: Double, mapKpa: Double, iatC: Double, lambda: Double, ve: Double): Double {
        if (rpm <= 0 || mapKpa <= 0) return 0.0
        val iatK = iatC + 273.15
        val lam = if (lambda > 0) lambda else 1.0
        val airGps = (rpm / 120.0) * DISPLACEMENT_L * ve * mapKpa / (R_AIR * iatK)
        val fuelGps = airGps / (AFR_STOICH * lam)
        return fuelGps * 3600.0 / GRAMS_PER_GAL
    }

    fun instMpg(mph: Double, gph: Double): Double =
        if (gph > 0.05) mph / gph else 0.0

    // ---- DTC decoding (mode 03 response) ----
    /** Decode the 2-byte groups after "43" into DTC strings like P0430. */
    fun decodeDtcs(raw: String): List<String> {
        val s = raw.uppercase()
            .replace(">", "").replace("\r", "").replace("\n", "").replace(" ", "")
        val idx = s.indexOf("43")
        if (idx < 0) return emptyList()
        var hex = s.substring(idx + 2)
        // Some ECUs prefix a count byte; drop it if it makes the remainder odd-grouped.
        if (hex.length % 4 == 2) hex = hex.substring(2)
        val out = ArrayList<String>()
        var i = 0
        while (i + 4 <= hex.length) {
            val group = hex.substring(i, i + 4)
            i += 4
            if (group == "0000") continue
            val v = group.toIntOrNull(16) ?: continue
            out.add(decodeOne(v))
        }
        return out.distinct()
    }

    private fun decodeOne(v: Int): String {
        val letter = when ((v shr 14) and 0x03) {
            0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U"
        }
        val d1 = (v shr 12) and 0x03
        val d2 = (v shr 8) and 0x0F
        val d3 = (v shr 4) and 0x0F
        val d4 = v and 0x0F
        return "%s%d%X%X%X".format(letter, d1, d2, d3, d4)
    }
}

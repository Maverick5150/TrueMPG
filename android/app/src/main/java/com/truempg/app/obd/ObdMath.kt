package com.truempg.app.obd

/**
 * Pure OBD-II helpers: response parsing, PID formulas, fuel-flow models for
 * every vehicle type, capability-bitmask decode, VIN parse/decode, DTC decode,
 * and unit conversions. No Android dependencies.
 */
object ObdMath {

    // ---- Fuel constants ----
    const val KPH_PER_MPH = 1.609344
    const val L_PER_GAL = 3.785411784

    // Gasoline (default) and diesel stoichiometry / density
    const val DISPLACEMENT_L = 2.7           // legacy default (2.7L EcoBoost)
    const val R_AIR = 0.287                  // kPa*L/(g*K)
    const val AFR_GAS = 14.64
    const val AFR_DIESEL = 14.5
    const val GRAMS_PER_GAL_GAS = 2820.0     // ~745 g/L * 3.785 L/gal
    const val GRAMS_PER_GAL_DIESEL = 3220.0  // ~850 g/L * 3.785 L/gal

    // SAE J1979 fuel-type codes (mode 01 PID 51)
    const val FUEL_GASOLINE = 1
    const val FUEL_DIESEL = 4

    fun isDiesel(fuelType: Int) = fuelType == FUEL_DIESEL

    // ---- Response parsing ----
    fun parseData(raw: String, mode: Int, pid: Int): List<Int>? {
        val s = raw.uppercase()
            .replace(">", "").replace("\r", "").replace("\n", "").replace(" ", "")
        if (s.contains("NODATA") || s.contains("UNABLETOCONNECT") ||
            s.contains("STOPPED") || s.contains("ERROR") || s.contains("?")
        ) return null
        val marker = "%02X%02X".format(mode + 0x40, pid)
        val idx = s.indexOf(marker)
        if (idx < 0) return null
        val hex = s.substring(idx + marker.length)
        val bytes = ArrayList<Int>()
        var i = 0
        while (i + 2 <= hex.length) {
            val b = hex.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(b); i += 2
        }
        return if (bytes.isEmpty()) null else bytes
    }

    // ---- PID formulas ----
    fun rpm(b: List<Int>) = if (b.size >= 2) ((b[0] * 256) + b[1]) / 4.0 else null
    fun speedKph(b: List<Int>) = b.getOrNull(0)?.toDouble()
    fun mapKpa(b: List<Int>) = b.getOrNull(0)?.toDouble()
    fun iatC(b: List<Int>) = b.getOrNull(0)?.let { it - 40.0 }
    fun coolantC(b: List<Int>) = b.getOrNull(0)?.let { it - 40.0 }
    fun throttlePct(b: List<Int>) = b.getOrNull(0)?.let { it * 100.0 / 255.0 }
    fun engineLoadPct(b: List<Int>) = b.getOrNull(0)?.let { it * 100.0 / 255.0 }
    fun fuelLevelPct(b: List<Int>) = b.getOrNull(0)?.let { it * 100.0 / 255.0 }
    fun timingAdv(b: List<Int>) = b.getOrNull(0)?.let { it / 2.0 - 64.0 }
    fun moduleVolts(b: List<Int>) = if (b.size >= 2) ((b[0] * 256) + b[1]) / 1000.0 else null
    fun lambda(b: List<Int>) = if (b.size >= 2) ((b[0] * 256) + b[1]) / 32768.0 else null
    fun mafGps(b: List<Int>) = if (b.size >= 2) ((b[0] * 256) + b[1]) / 100.0 else null
    fun fuelRateLph(b: List<Int>) = if (b.size >= 2) ((b[0] * 256) + b[1]) / 20.0 else null
    fun fuelType(b: List<Int>) = b.getOrNull(0)

    // ---- Fuel-flow models (US gallons/hour) ----
    /** MAF path: fuel = air / (AFR * lambda). Most accurate; most vehicles. */
    fun gphFromMaf(mafGps: Double, lambda: Double, diesel: Boolean): Double {
        if (mafGps <= 0) return 0.0
        val afr = if (diesel) AFR_DIESEL else AFR_GAS
        val gpg = if (diesel) GRAMS_PER_GAL_DIESEL else GRAMS_PER_GAL_GAS
        val lam = if (lambda > 0) lambda else 1.0
        return (mafGps / (afr * lam)) * 3600.0 / gpg
    }

    /** Direct fuel-rate PID (0x5E), L/h -> gal/h. Preferred when present. */
    fun gphFromFuelRate(lph: Double): Double = if (lph <= 0) 0.0 else lph / L_PER_GAL

    /** Speed-density path for MAF-less engines (e.g. 2.7L EcoBoost). */
    fun gphSpeedDensity(
        rpm: Double, mapKpa: Double, iatC: Double, lambda: Double,
        ve: Double, displacementL: Double, diesel: Boolean,
    ): Double {
        if (rpm <= 0 || mapKpa <= 0) return 0.0
        val afr = if (diesel) AFR_DIESEL else AFR_GAS
        val gpg = if (diesel) GRAMS_PER_GAL_DIESEL else GRAMS_PER_GAL_GAS
        val iatK = iatC + 273.15
        val lam = if (lambda > 0) lambda else 1.0
        val airGps = (rpm / 120.0) * displacementL * ve * mapKpa / (R_AIR * iatK)
        return (airGps / (afr * lam)) * 3600.0 / gpg
    }

    /** Legacy 2.7L-fixed speed-density (kept so old callers are unchanged). */
    fun gph(rpm: Double, mapKpa: Double, iatC: Double, lambda: Double, ve: Double): Double =
        gphSpeedDensity(rpm, mapKpa, iatC, lambda, ve, DISPLACEMENT_L, false)

    /** Instantaneous MPG (US) from mph and gal/hour. */
    fun instMpg(mph: Double, gph: Double): Double = if (gph > 0.05) mph / gph else 0.0

    // ---- Capability discovery ----
    /**
     * Decode a mode-01 supported-PID bitmask (4 bytes) into the PID numbers it
     * marks supported. base 0x00 covers 0x01..0x20, 0x20 covers 0x21..0x40, etc.
     */
    fun decodeSupportedPids(base: Int, bytes: List<Int>): List<Int> {
        if (bytes.size < 4) return emptyList()
        val out = ArrayList<Int>()
        var index = 0
        for (byte in bytes.take(4)) {
            for (bit in 7 downTo 0) {
                index++
                if ((byte shr bit) and 1 == 1) out.add(base + index)
            }
        }
        return out
    }

    // ---- VIN (mode 09 PID 02) ----
    /** Extract a 17-char VIN from a raw 0902 response (handles multi-frame). */
    fun parseVin(raw: String): String? {
        val hexOnly = raw.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
        if (!hexOnly.contains("4902")) return null
        val sb = StringBuilder()
        var i = 0
        while (i + 2 <= hexOnly.length) {
            val v = hexOnly.substring(i, i + 2).toIntOrNull(16)
            sb.append(if (v != null && v in 32..126) v.toChar() else ' ')
            i += 2
        }
        return Regex("[A-HJ-NPR-Z0-9]{17}").find(sb.toString())?.value
    }

    /** Approximate model year from the 10th VIN char (assumes 2010+ for letters). */
    fun vinYear(vin: String): Int? {
        if (vin.length < 10) return null
        val c = vin[9]
        val letters = "ABCDEFGHJKLMNPRSTVWXY"   // 2010..2030
        val li = letters.indexOf(c)
        if (li >= 0) return 2010 + li
        val d = c - '0'
        if (d in 1..9) return 2000 + d           // 2001..2009
        return null
    }

    private val WMI = mapOf(
        "1FT" to "Ford", "1FA" to "Ford", "1FM" to "Ford", "1FD" to "Ford",
        "2FM" to "Ford", "3FA" to "Ford",
        "1G" to "GM", "1GC" to "Chevrolet", "1GT" to "GMC", "3G" to "GM",
        "1HG" to "Honda", "JHM" to "Honda", "2HG" to "Honda", "5J6" to "Honda",
        "1N4" to "Nissan", "JN1" to "Nissan", "3N1" to "Nissan",
        "4T1" to "Toyota", "5TF" to "Toyota", "JTD" to "Toyota", "2T1" to "Toyota",
        "5YJ" to "Tesla", "7SA" to "Tesla",
        "WBA" to "BMW", "WBS" to "BMW", "WDB" to "Mercedes", "WDD" to "Mercedes",
        "WVW" to "Volkswagen", "3VW" to "Volkswagen", "1VW" to "Volkswagen",
        "KMH" to "Hyundai", "KND" to "Kia", "5XY" to "Hyundai",
        "1C4" to "Jeep", "2C3" to "Chrysler", "1C6" to "Ram", "3C6" to "Ram",
    )

    fun vinMake(vin: String): String? {
        if (vin.length < 3) return null
        WMI[vin.substring(0, 3)]?.let { return it }
        WMI[vin.substring(0, 2)]?.let { return it }
        return null
    }

    fun vehicleLabel(vin: String?): String {
        if (vin == null || vin.length < 10) return "Vehicle"
        val year = vinYear(vin)?.toString() ?: ""
        val make = vinMake(vin) ?: ""
        val label = "$year $make".trim()
        return if (label.isEmpty()) "Vehicle" else label
    }

    // ---- Unit conversions ----
    fun mpgUsToImperial(mpgUs: Double) = mpgUs * 1.20095
    fun mpgUsToLper100km(mpgUs: Double) = if (mpgUs > 0.01) 235.215 / mpgUs else 0.0
    fun mphToKph(mph: Double) = mph * KPH_PER_MPH

    // ---- DTC decoding (mode 03) ----
    fun decodeDtcs(raw: String): List<String> {
        val s = raw.uppercase()
            .replace(">", "").replace("\r", "").replace("\n", "").replace(" ", "")
        val idx = s.indexOf("43")
        if (idx < 0) return emptyList()
        var hex = s.substring(idx + 2)
        if (hex.length % 4 == 2) hex = hex.substring(2)
        val out = ArrayList<String>()
        var i = 0
        while (i + 4 <= hex.length) {
            val group = hex.substring(i, i + 4); i += 4
            if (group == "0000") continue
            val v = group.toIntOrNull(16) ?: continue
            out.add(decodeOne(v))
        }
        return out.distinct()
    }

    private fun decodeOne(v: Int): String {
        val letter = when ((v shr 14) and 0x03) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
        val d1 = (v shr 12) and 0x03
        val d2 = (v shr 8) and 0x0F
        val d3 = (v shr 4) and 0x0F
        val d4 = v and 0x0F
        return "%s%d%X%X%X".format(letter, d1, d2, d3, d4)
    }

    // ---- I/M readiness (mode 01 PID 01) ----
    data class Monitor(val name: String, val complete: Boolean)
    data class Readiness(val milOn: Boolean, val dtcCount: Int, val monitors: List<Monitor>)

    fun decodeReadiness(b: List<Int>): Readiness? {
        if (b.size < 4) return null
        val a = b[0]; val bb = b[1]; val c = b[2]; val d = b[3]
        val mil = (a and 0x80) != 0
        val count = a and 0x7F
        val mons = ArrayList<Monitor>()
        // Continuous: low nibble of B = supported, high nibble = incomplete (1=not ready)
        if ((bb and 0x01) != 0) mons.add(Monitor("Misfire", (bb and 0x10) == 0))
        if ((bb and 0x02) != 0) mons.add(Monitor("Fuel system", (bb and 0x20) == 0))
        if ((bb and 0x04) != 0) mons.add(Monitor("Components", (bb and 0x40) == 0))
        // Non-continuous (spark ignition): C = supported, D = incomplete
        val names = listOf("Catalyst", "Heated catalyst", "Evap system", "Secondary air",
            "A/C refrigerant", "O2 sensor", "O2 heater", "EGR system")
        for (i in 0..7) if ((c shr i) and 1 == 1) mons.add(Monitor(names[i], ((d shr i) and 1) == 0))
        return Readiness(mil, count, mons)
    }

    // ---- Freeze frame (mode 02 PID 02): the DTC that snapshotted ----
    fun parseFreezeDtc(raw: String): String? {
        val s = raw.uppercase()
            .replace(">", "").replace("\r", "").replace("\n", "").replace(" ", "")
        val idx = s.indexOf("4202")
        if (idx < 0) return null
        var rest = s.substring(idx + 4)
        if (rest.startsWith("00") && rest.length >= 6) rest = rest.substring(2) // frame byte
        val group = rest.take(4)
        if (group.length < 4 || group == "0000") return null
        val v = group.toIntOrNull(16) ?: return null
        return decodeOne(v)
    }

    // ---- MPG method selection ----
    enum class MpgMethod { FUEL_RATE, MAF, SPEED_DENSITY, NONE }

    /** Auto-pick the best available method; override wins if set. */
    fun selectMethod(supported: Set<Int>, override: MpgMethod?): MpgMethod {
        if (override != null && override != MpgMethod.NONE) return override
        return when {
            0x5E in supported -> MpgMethod.FUEL_RATE
            0x10 in supported -> MpgMethod.MAF
            0x0B in supported && 0x0C in supported -> MpgMethod.SPEED_DENSITY
            else -> MpgMethod.NONE
        }
    }
}

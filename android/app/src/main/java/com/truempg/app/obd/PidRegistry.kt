package com.truempg.app.obd

/** A displayable mode-01 PID: number, human name, unit, and a decoder. */
data class PidSpec(
    val pid: Int,
    val name: String,
    val unit: String,
    val decode: (List<Int>) -> Double?,
)

/**
 * Registry of the common mode-01 PIDs the app can display in the live
 * "everything" view. Only PIDs the vehicle reports as supported are shown.
 */
object PidRegistry {

    private fun u16(b: List<Int>): Double? =
        if (b.size >= 2) ((b[0] * 256) + b[1]).toDouble() else null

    private fun u8(b: List<Int>): Double? = b.getOrNull(0)?.toDouble()
    private fun pct(b: List<Int>): Double? = b.getOrNull(0)?.let { it * 100.0 / 255.0 }

    val specs: List<PidSpec> = listOf(
        PidSpec(0x04, "Engine load", "%", ObdMath::engineLoadPct),
        PidSpec(0x05, "Coolant", "°C", ObdMath::coolantC),
        PidSpec(0x06, "Short fuel trim B1", "%", ObdMath::fuelTrimPct),
        PidSpec(0x07, "Long fuel trim B1", "%", ObdMath::fuelTrimPct),
        PidSpec(0x08, "Short fuel trim B2", "%", ObdMath::fuelTrimPct),
        PidSpec(0x09, "Long fuel trim B2", "%", ObdMath::fuelTrimPct),
        PidSpec(0x0A, "Fuel pressure", "kPa", { b -> b.getOrNull(0)?.let { it * 3.0 } }),
        PidSpec(0x0B, "Intake MAP", "kPa", ObdMath::mapKpa),
        PidSpec(0x0C, "Engine RPM", "rpm", ObdMath::rpm),
        PidSpec(0x0D, "Vehicle speed", "km/h", ObdMath::speedKph),
        PidSpec(0x0E, "Timing advance", "°", ObdMath::timingAdv),
        PidSpec(0x0F, "Intake air temp", "°C", ObdMath::iatC),
        PidSpec(0x10, "MAF rate", "g/s", ObdMath::mafGps),
        PidSpec(0x11, "Throttle position", "%", ObdMath::throttlePct),
        PidSpec(0x1F, "Run time", "s", ::u16),
        PidSpec(0x21, "Distance with MIL", "km", ::u16),
        PidSpec(0x2C, "Commanded EGR", "%", ::pct),
        PidSpec(0x2D, "EGR error", "%", { b -> b.getOrNull(0)?.let { (it - 128) * 100.0 / 128.0 } }),
        PidSpec(0x2F, "Fuel level", "%", ObdMath::fuelLevelPct),
        PidSpec(0x31, "Distance since clear", "km", ::u16),
        PidSpec(0x33, "Barometric pressure", "kPa", ::u8),
        PidSpec(0x42, "Module voltage", "V", ObdMath::moduleVolts),
        PidSpec(0x43, "Absolute load", "%", { b -> u16(b)?.let { it * 100.0 / 255.0 } }),
        PidSpec(0x44, "Commanded lambda", "", ObdMath::lambda),
        PidSpec(0x45, "Relative throttle", "%", ::pct),
        PidSpec(0x46, "Ambient air temp", "°C", ObdMath::iatC),
        PidSpec(0x47, "Abs throttle B", "%", ::pct),
        PidSpec(0x49, "Accel pedal D", "%", ::pct),
        PidSpec(0x4A, "Accel pedal E", "%", ::pct),
        PidSpec(0x4C, "Commanded throttle", "%", ::pct),
        PidSpec(0x5C, "Engine oil temp", "°C", { b -> b.getOrNull(0)?.let { it - 40.0 } }),
        PidSpec(0x5E, "Fuel rate", "L/h", ObdMath::fuelRateLph),
    )

    val byPid: Map<Int, PidSpec> = specs.associateBy { it.pid }

    /** PIDs that carry a Celsius temperature (so the UI can apply °C/°F). */
    val tempPids: Set<Int> = setOf(0x05, 0x0F, 0x46, 0x5C)
}

package com.truempg.app.obd

/**
 * Offline root-cause analyzer. Given a DTC and a live snapshot of the relevant
 * PIDs, produces a ranked diagnosis with honest confidence levels, the evidence
 * behind it, and the physical checks that would confirm each cause. Rules are
 * generic across engines with notes for the 2.7L EcoBoost (no MAF, twin turbo).
 */
object Diagnostics {

    enum class Confidence { LIKELY, POSSIBLE, NEEDS_TEST;
        fun label() = when (this) {
            LIKELY -> "Likely"; POSSIBLE -> "Possible"; NEEDS_TEST -> "Needs physical test"
        }
    }

    data class Cause(val text: String, val confidence: Confidence)
    data class Diagnosis(
        val code: String,
        val title: String,
        val evidence: List<String>,
        val causes: List<Cause>,
        val checks: List<String>,
    )

    /** Snapshot of PIDs read on demand for the analysis. */
    data class Snapshot(
        val stft1: Double? = null, val ltft1: Double? = null,
        val stft2: Double? = null, val ltft2: Double? = null,
        val lambda: Double? = null, val coolantC: Double? = null,
        val load: Double? = null, val rpm: Double? = null, val mapKpa: Double? = null,
    )

    private fun bankTrim(short: Double?, long: Double?): Double? {
        if (short == null && long == null) return null
        return (short ?: 0.0) + (long ?: 0.0)
    }

    fun analyze(code: String, s: Snapshot): Diagnosis {
        val ev = ArrayList<String>()
        s.coolantC?.let { ev.add("Coolant ${"%.0f".format(it)}°C") }
        s.rpm?.let { ev.add("RPM ${"%.0f".format(it)}") }
        s.load?.let { ev.add("Load ${"%.0f".format(it)}%") }
        bankTrim(s.stft1, s.ltft1)?.let { ev.add("Bank 1 total fuel trim ${"%+.1f".format(it)}%") }
        bankTrim(s.stft2, s.ltft2)?.let { ev.add("Bank 2 total fuel trim ${"%+.1f".format(it)}%") }

        return when {
            code in setOf("P0171", "P0174") -> lean(code, s, ev)
            code in setOf("P0172", "P0175") -> rich(code, s, ev)
            code.matches(Regex("P030[0-9]")) -> misfire(code, ev)
            code in setOf("P0420", "P0430") -> catalyst(code, s, ev)
            code == "P0128" -> thermostat(s, ev)
            code in setOf("P0440", "P0441", "P0442", "P0446", "P0455", "P0456", "P0457") -> evap(code, ev)
            code in setOf("P0135", "P0141", "P0155", "P0161") -> o2Heater(code, ev)
            else -> Diagnosis(code, DtcDescriptions.describe(code) ?: "Fault", ev,
                listOf(Cause("Consult service information for this code family.", Confidence.NEEDS_TEST)),
                listOf("Compare freeze-frame conditions with live data.",
                    "Verify the code returns after clearing before replacing parts."))
        }
    }

    private fun lean(code: String, s: Snapshot, ev: List<String>): Diagnosis {
        val bank = if (code == "P0174") 2 else 1
        val trim = if (bank == 2) bankTrim(s.stft2, s.ltft2) else bankTrim(s.stft1, s.ltft1)
        val strong = (trim ?: 0.0) > 15.0
        val causes = mutableListOf(
            Cause("Unmetered air (vacuum/intake leak, PCV, loose intake boot)",
                if (strong) Confidence.LIKELY else Confidence.POSSIBLE),
            Cause("Low fuel delivery (weak pump, dirty injectors, restricted filter)", Confidence.POSSIBLE),
            Cause("Exhaust leak upstream of the O2 sensor pulling in air", Confidence.POSSIBLE),
        )
        return Diagnosis(code, "System too lean (Bank $bank)",
            ev + if (trim != null) listOf("Fuel trim is ${"%+.1f".format(trim)}% (positive = adding fuel to compensate for lean)") else emptyList(),
            causes,
            listOf("Smoke-test the intake for vacuum leaks.",
                "Check fuel pressure and injector flow.",
                "2.7L EcoBoost: inspect charge-air/intercooler piping and PCV — no MAF, so speed-density is sensitive to true leaks."))
    }

    private fun rich(code: String, s: Snapshot, ev: List<String>): Diagnosis {
        val bank = if (code == "P0175") 2 else 1
        val trim = if (bank == 2) bankTrim(s.stft2, s.ltft2) else bankTrim(s.stft1, s.ltft1)
        return Diagnosis(code, "System too rich (Bank $bank)",
            ev + if (trim != null) listOf("Fuel trim is ${"%+.1f".format(trim)}% (negative = pulling fuel)") else emptyList(),
            listOf(
                Cause("Leaking/dribbling injector", Confidence.POSSIBLE),
                Cause("Excess fuel pressure (regulator)", Confidence.POSSIBLE),
                Cause("Contaminated or lazy upstream O2 sensor", Confidence.POSSIBLE),
            ),
            listOf("Injector balance/leak-down test.",
                "Check fuel pressure against spec.",
                "Inspect for oil/coolant contamination fouling sensors."))
    }

    private fun misfire(code: String, ev: List<String>): Diagnosis {
        val cyl = code.last().digitToIntOrNull()?.takeIf { it in 1..9 }
        val title = if (cyl != null) "Misfire — Cylinder $cyl" else "Random/multiple misfire"
        val checks = mutableListOf<String>()
        if (cyl != null) checks.add("Swap the coil (and plug) from cyl $cyl to another cylinder — if the misfire follows, that part is bad.")
        checks.add("Inspect spark plug condition and gap.")
        checks.add("Compression/leak-down test if ignition & fuel check out.")
        checks.add("2.7L EcoBoost: check for carbon on intake valves and injector balance under boost.")
        return Diagnosis(code, title, ev,
            listOf(
                Cause("Ignition coil failing on that cylinder", Confidence.LIKELY),
                Cause("Worn/fouled spark plug", Confidence.LIKELY),
                Cause("Injector clogged or leaking", Confidence.POSSIBLE),
                Cause("Low compression (valve/ring)", Confidence.NEEDS_TEST),
            ), checks)
    }

    private fun catalyst(code: String, s: Snapshot, ev: List<String>): Diagnosis {
        val bank = if (code == "P0430") 2 else 1
        val note = ArrayList(ev)
        val t = if (bank == 2) bankTrim(s.stft2, s.ltft2) else bankTrim(s.stft1, s.ltft1)
        if (t != null && kotlin.math.abs(t) > 15.0)
            note.add("Abnormal fuel trim (${"%+.1f".format(t)}%) — bad fueling can cook a cat; fix that first.")
        return Diagnosis(code, "Catalyst efficiency below threshold (Bank $bank)", note,
            listOf(
                Cause("Aged/worn catalytic converter (substrate no longer stores O2)", Confidence.POSSIBLE),
                Cause("Lazy downstream O2 sensor reporting false inefficiency", Confidence.POSSIBLE),
                Cause("Exhaust leak near the sensors skewing readings", Confidence.NEEDS_TEST),
            ),
            listOf("Drive a full cycle, then read the Mode 06 catalyst monitor value vs limit.",
                "Compare upstream (B${bank}S1) vs downstream (B${bank}S2) O2 switching — heavy mirroring = worn cat; flat = dead sensor.",
                "Smoke-test the exhaust for leaks before condemning the cat.",
                "2.7L EcoBoost: check oil consumption — burning oil poisons the cat."))
    }

    private fun thermostat(s: Snapshot, ev: List<String>): Diagnosis {
        val cold = (s.coolantC ?: 100.0) < 70.0
        return Diagnosis("P0128", "Coolant thermostat below regulating temp", ev,
            listOf(
                Cause("Thermostat stuck open", if (cold) Confidence.LIKELY else Confidence.POSSIBLE),
                Cause("Coolant temp sensor reading low", Confidence.POSSIBLE),
            ),
            listOf("Confirm coolant reaches ~90°C after a full warm-up.",
                "If it never warms, replace the thermostat; if temp is erratic, suspect the sensor."))
    }

    private fun evap(code: String, ev: List<String>): Diagnosis {
        val capLikely = code == "P0457" || code == "P0455"
        return Diagnosis(code, "EVAP emissions leak/fault", ev,
            listOf(
                Cause("Loose or failed fuel cap", if (capLikely) Confidence.LIKELY else Confidence.POSSIBLE),
                Cause("Purge or vent valve stuck", Confidence.POSSIBLE),
                Cause("Cracked EVAP hose or canister leak", Confidence.NEEDS_TEST),
            ),
            listOf("Tighten/replace the fuel cap and clear the code first (cheapest fix).",
                "Smoke-test the EVAP system to pinpoint a leak.",
                "Check purge/vent valve operation with a scan tool."))
    }

    private fun o2Heater(code: String, ev: List<String>): Diagnosis =
        Diagnosis(code, "O2 sensor heater circuit", ev,
            listOf(
                Cause("O2 sensor heater element failed", Confidence.LIKELY),
                Cause("Blown heater fuse or wiring/connector fault", Confidence.POSSIBLE),
            ),
            listOf("Measure the sensor's heater resistance against spec.",
                "Check the O2 heater fuse and connector for corrosion."))
}

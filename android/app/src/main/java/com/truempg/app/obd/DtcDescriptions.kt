package com.truempg.app.obd

/**
 * Offline lookup for common powertrain DTCs so codes show a plain-English
 * description without any network. Generic OBD-II P0xxx plus a few
 * manufacturer-specific P1xxx (Ford, the developer's truck). Unknown codes
 * return null and the UI shows just the code.
 */
object DtcDescriptions {

    fun describe(code: String): String? = GENERIC[code] ?: FORD[code]

    /** "P0430" -> "P0430 — Catalyst System Efficiency Below Threshold (Bank 2)" */
    fun label(code: String): String {
        val d = describe(code)
        return if (d != null) "$code — $d" else code
    }

    private val GENERIC: Map<String, String> = mapOf(
        // Fuel & air metering
        "P0100" to "Mass Air Flow Circuit Malfunction",
        "P0101" to "MAF Circuit Range/Performance",
        "P0102" to "MAF Circuit Low Input",
        "P0103" to "MAF Circuit High Input",
        "P0105" to "MAP/Barometric Circuit Malfunction",
        "P0106" to "MAP/Barometric Circuit Range/Performance",
        "P0107" to "MAP/Barometric Circuit Low Input",
        "P0108" to "MAP/Barometric Circuit High Input",
        "P0110" to "Intake Air Temperature Circuit Malfunction",
        "P0111" to "Intake Air Temperature Circuit Range/Performance",
        "P0112" to "Intake Air Temperature Circuit Low",
        "P0113" to "Intake Air Temperature Circuit High",
        "P0115" to "Engine Coolant Temperature Circuit Malfunction",
        "P0116" to "ECT Circuit Range/Performance",
        "P0117" to "ECT Circuit Low",
        "P0118" to "ECT Circuit High",
        "P0120" to "Throttle Position Sensor Circuit Malfunction",
        "P0121" to "TPS Circuit Range/Performance",
        "P0122" to "TPS Circuit Low Input",
        "P0123" to "TPS Circuit High Input",
        "P0125" to "Insufficient Coolant Temp for Closed Loop",
        "P0128" to "Coolant Thermostat Below Regulating Temperature",
        // Fuel system / mixture
        "P0130" to "O2 Sensor Circuit (Bank 1 Sensor 1)",
        "P0131" to "O2 Sensor Circuit Low Voltage (B1S1)",
        "P0132" to "O2 Sensor Circuit High Voltage (B1S1)",
        "P0133" to "O2 Sensor Circuit Slow Response (B1S1)",
        "P0134" to "O2 Sensor Circuit No Activity (B1S1)",
        "P0135" to "O2 Sensor Heater Circuit (B1S1)",
        "P0136" to "O2 Sensor Circuit (Bank 1 Sensor 2)",
        "P0137" to "O2 Sensor Circuit Low Voltage (B1S2)",
        "P0138" to "O2 Sensor Circuit High Voltage (B1S2)",
        "P0140" to "O2 Sensor Circuit No Activity (B1S2)",
        "P0141" to "O2 Sensor Heater Circuit (B1S2)",
        "P0150" to "O2 Sensor Circuit (Bank 2 Sensor 1)",
        "P0155" to "O2 Sensor Heater Circuit (B2S1)",
        "P0156" to "O2 Sensor Circuit (Bank 2 Sensor 2)",
        "P0161" to "O2 Sensor Heater Circuit (B2S2)",
        "P0171" to "System Too Lean (Bank 1)",
        "P0172" to "System Too Rich (Bank 1)",
        "P0174" to "System Too Lean (Bank 2)",
        "P0175" to "System Too Rich (Bank 2)",
        // Ignition / misfire
        "P0300" to "Random/Multiple Cylinder Misfire Detected",
        "P0301" to "Cylinder 1 Misfire Detected",
        "P0302" to "Cylinder 2 Misfire Detected",
        "P0303" to "Cylinder 3 Misfire Detected",
        "P0304" to "Cylinder 4 Misfire Detected",
        "P0305" to "Cylinder 5 Misfire Detected",
        "P0306" to "Cylinder 6 Misfire Detected",
        "P0307" to "Cylinder 7 Misfire Detected",
        "P0308" to "Cylinder 8 Misfire Detected",
        "P0316" to "Misfire Detected on Startup",
        // EGR / emissions
        "P0401" to "EGR Flow Insufficient Detected",
        "P0402" to "EGR Flow Excessive Detected",
        "P0411" to "Secondary Air Injection Incorrect Flow",
        "P0420" to "Catalyst System Efficiency Below Threshold (Bank 1)",
        "P0430" to "Catalyst System Efficiency Below Threshold (Bank 2)",
        "P0440" to "EVAP System Malfunction",
        "P0441" to "EVAP System Incorrect Purge Flow",
        "P0442" to "EVAP System Leak Detected (small leak)",
        "P0443" to "EVAP Purge Control Valve Circuit",
        "P0446" to "EVAP Vent Control Circuit",
        "P0455" to "EVAP System Leak Detected (large leak)",
        "P0456" to "EVAP System Leak Detected (very small leak)",
        "P0457" to "EVAP Leak Detected (loose fuel cap)",
        // Speed / idle / misc
        "P0500" to "Vehicle Speed Sensor Malfunction",
        "P0505" to "Idle Control System Malfunction",
        "P0506" to "Idle Control System RPM Lower Than Expected",
        "P0507" to "Idle Control System RPM Higher Than Expected",
        "P0562" to "System Voltage Low",
        "P0563" to "System Voltage High",
        // Transmission (common)
        "P0700" to "Transmission Control System Malfunction",
        "P0715" to "Input/Turbine Speed Sensor Circuit",
        "P0740" to "Torque Converter Clutch Circuit Malfunction",
        "P0741" to "Torque Converter Clutch Stuck Off",
    )

    // Ford-specific (P1xxx). Small starter set.
    private val FORD: Map<String, String> = mapOf(
        "P1000" to "OBD-II Monitor Testing Not Complete",
        "P1001" to "KOER Test Cannot Be Completed",
        "P1131" to "Lack of HO2S Switch — Sensor Indicates Lean (Bank 1)",
        "P1151" to "Lack of HO2S Switch — Sensor Indicates Lean (Bank 2)",
        "P1299" to "Cylinder Head Over Temperature Protection Active",
        "P1450" to "Unable to Bleed Up Fuel Tank Vacuum",
    )
}

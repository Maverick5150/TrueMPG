package com.truempg.app.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File

/**
 * Optional black-box recorder: one CSV per trip (named by trip start ms so it
 * lines up with a Trip), auto-pruned to the newest N files.
 */
class BlackBoxLogger(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "blackbox").apply { mkdirs() }
    private var writer: BufferedWriter? = null

    @Volatile
    var active = false
        private set

    fun start(tripStartMs: Long) {
        stop()
        try {
            val f = File(dir, "trip_$tripStartMs.csv")
            writer = f.bufferedWriter().also { it.appendLine(HEADER) }
            active = true
        } catch (e: Exception) {
            writer = null; active = false
        }
    }

    fun log(
        tSec: Double, mph: Double, rpm: Double, mapKpa: Double, iatC: Double,
        coolantC: Double?, throttle: Double?, load: Double?, fuel: Double?,
        volts: Double?, lambda: Double, maf: Double?, fuelRate: Double?, instMpg: Double,
    ) {
        val w = writer ?: return
        fun f(v: Double?) = v?.let { "%.2f".format(it) } ?: ""
        try {
            w.appendLine(
                listOf(
                    "%.2f".format(tSec), f(mph), f(rpm), f(mapKpa), f(iatC), f(coolantC),
                    f(throttle), f(load), f(fuel), f(volts), "%.3f".format(lambda),
                    f(maf), f(fuelRate), f(instMpg)
                ).joinToString(",")
            )
        } catch (e: Exception) { /* keep driving even if a write fails */ }
    }

    fun stop() {
        try { writer?.flush(); writer?.close() } catch (e: Exception) {}
        writer = null
        active = false
    }

    fun listLogs(): List<File> =
        (dir.listFiles()?.filter { it.name.endsWith(".csv") } ?: emptyList())
            .sortedByDescending { it.lastModified() }

    fun logForTrip(tripStartMs: Long): File? =
        File(dir, "trip_$tripStartMs.csv").takeIf { it.exists() }

    fun prune(maxFiles: Int) {
        val logs = listLogs()
        if (logs.size > maxFiles) logs.drop(maxFiles).forEach { runCatching { it.delete() } }
    }

    companion object {
        const val HEADER =
            "t_s,mph,rpm,map_kpa,iat_c,coolant_c,throttle,load,fuel,volts,lambda,maf,fuelrate,inst_mpg"
    }
}

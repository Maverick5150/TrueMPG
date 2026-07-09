package com.truempg.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.truempg.app.obd.ObdRepository

class TrueMpgApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val monitor = NotificationChannel(
                CHANNEL_ID, "TrueMPG monitoring", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Live fuel-economy monitoring while driving" }
            val alerts = NotificationChannel(
                ALERT_CHANNEL_ID, "TrueMPG alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "New codes, high temp, low voltage/fuel" }
            nm.createNotificationChannel(monitor)
            nm.createNotificationChannel(alerts)
        }
        ObdRepository.init(this)
    }

    companion object {
        const val CHANNEL_ID = "truempg_monitor"
        const val ALERT_CHANNEL_ID = "truempg_alerts"
    }
}

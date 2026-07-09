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
            val ch = NotificationChannel(
                CHANNEL_ID, "TrueMPG monitoring", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Live fuel-economy monitoring while driving" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        ObdRepository.init(this)
    }

    companion object {
        const val CHANNEL_ID = "truempg_monitor"
    }
}

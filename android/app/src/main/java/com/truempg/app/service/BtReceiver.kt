package com.truempg.app.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.truempg.app.data.Settings
import com.truempg.app.obd.ObdRepository

/**
 * Auto-connect on truck start. When the saved OBD adapter's Bluetooth link comes
 * up (ignition on), start the foreground service to connect and monitor -- no
 * user interaction. Respects the auto-connect setting.
 *
 * NOTE: on Android 12+ starting a foreground service from a background broadcast
 * can be restricted; the battery-optimization exemption (prompted in the app)
 * makes this reliable. Wrapped in try/catch so a blocked start never crashes.
 */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // Warm the app after boot/update so it leaves the "stopped" state
                // and its manifest ACL_CONNECTED receiver stays armed for when the
                // truck (and adapter) power up.
                try { ObdRepository.init(context) } catch (e: Exception) {}
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val settings = Settings(context)
                if (!settings.autoConnect) return
                val saved = settings.savedAdapterAddress ?: return
                val device = IntentCompat.getParcelableExtra(
                    intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                )
                val addr = try { device?.address } catch (e: SecurityException) { null } ?: return
                if (!addr.equals(saved, ignoreCase = true)) return
                try { ObdService.autoStart(context) } catch (e: Exception) { /* bg start blocked */ }
            }
        }
    }
}

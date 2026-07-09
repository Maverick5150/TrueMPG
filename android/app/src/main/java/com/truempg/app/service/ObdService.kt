package com.truempg.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.truempg.app.MainActivity
import com.truempg.app.R
import com.truempg.app.TrueMpgApp
import com.truempg.app.UiState
import com.truempg.app.obd.ObdRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Foreground monitoring service. Keeps the OBD poll loop (in ObdRepository)
 * alive while the truck is running, even if the UI is closed, and shows a live
 * notification. Started by the UI (Connect) or the ACL_CONNECTED receiver.
 */
class ObdService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ObdRepository.init(this)
        startForeground(NOTIF_ID, buildNotification(ObdRepository.state.value))
        ObdRepository.state.onEach { st ->
            try {
                NotificationManagerCompat.from(this@ObdService)
                    .notify(NOTIF_ID, buildNotification(st))
            } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted */ }
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_ADDRESS)?.let { ObdRepository.connect(it) }
            ACTION_STOP -> { ObdRepository.disconnect(); stopSelf() }
            else -> ObdRepository.autoConnectSaved()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(st: UiState): Notification {
        val avg = if (st.tripGallons > 1e-6) st.tripMiles / st.tripGallons else 0.0
        val title = if (st.connected) "TrueMPG • connected" else st.status
        val text = if (st.connected)
            "avg %.1f MPG · %.0f mph · %.0f°C".format(avg, st.readings.mph, st.readings.coolantC ?: 0.0)
        else "Waiting for adapter…"

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ObdService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, TrueMpgApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mpg)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_CONNECT = "com.truempg.app.action.CONNECT"
        const val ACTION_STOP = "com.truempg.app.action.STOP"
        const val EXTRA_ADDRESS = "address"

        fun connect(context: Context, address: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ObdService::class.java)
                    .setAction(ACTION_CONNECT).putExtra(EXTRA_ADDRESS, address)
            )
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ObdService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Auto-start (no action) -> connects the saved adapter. */
        fun autoStart(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java))
        }
    }
}

package com.truempg.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.truempg.app.MainActivity
import com.truempg.app.R
import com.truempg.app.TrueMpgApp

/** Posts high-priority driver alerts (separate channel from the ongoing status). */
object Alerts {
    fun post(context: Context, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, TrueMpgApp.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mpg)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted */ }
    }
}

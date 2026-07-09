package com.truempg.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.truempg.app.MainActivity
import com.truempg.app.R
import com.truempg.app.data.Settings

/** Home-screen widget: last completed-trip MPG + connection status. */
class MpgWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) render(context, mgr, id)
    }

    companion object {
        /** Push the latest cached values to all placed widgets. */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MpgWidget::class.java))
            for (id in ids) render(context, mgr, id)
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val s = Settings(context)
            val views = RemoteViews(context.packageName, R.layout.widget_mpg)
            val mpg = s.widgetMpg
            views.setTextViewText(R.id.widget_mpg, if (mpg > 0) "%.1f MPG".format(mpg) else "-- MPG")
            views.setTextViewText(R.id.widget_status, s.widgetStatus)
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            mgr.updateAppWidget(id, views)
        }
    }
}

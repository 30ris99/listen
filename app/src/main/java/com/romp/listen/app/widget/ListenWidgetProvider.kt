package com.romp.listen.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.romp.listen.app.R
import com.romp.listen.app.service.ListenForegroundService
import com.romp.listen.app.settings.SettingsManager
import com.romp.listen.app.util.AppLog

/**
 * Minimal 2x1 home-screen widget: single toggle button (start/pause).
 * OpenClaw fork.
 */
class ListenWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            val svc = Intent(context, ListenForegroundService::class.java).apply {
                action = ListenForegroundService.ACTION_TOGGLE_RECORDING
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Widget toggle failed", e)
            }
            refresh(context)
        }
    }

    companion object {
        private const val TAG = "ListenWidget"
        const val ACTION_WIDGET_TOGGLE = "com.romp.listen.app.widget.TOGGLE"

        /** Rebuild all widget instances (called by service + toggle). */
        fun refresh(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val me = ComponentName(context, ListenWidgetProvider::class.java)
                for (id in mgr.getAppWidgetIds(me)) updateOne(context, mgr, id)
            } catch (_: Exception) { }
        }

        private fun updateOne(context: Context, mgr: AppWidgetManager, id: Int) {
            val running = try {
                SettingsManager(context).isServiceEnabled
            } catch (_: Exception) {
                false
            }
            val views = RemoteViews(context.packageName, R.layout.widget_listen).apply {
                setTextViewText(
                    R.id.widget_btn_toggle,
                    if (running) context.getString(R.string.widget_stop)
                    else context.getString(R.string.widget_start)
                )
                setTextViewText(
                    R.id.widget_tv_status,
                    if (running) context.getString(R.string.status_recording)
                    else context.getString(R.string.status_stopped)
                )
                val toggle = Intent(context, ListenWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_TOGGLE
                }
                val pi = PendingIntent.getBroadcast(
                    context, 0, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_btn_toggle, pi)
            }
            mgr.updateAppWidget(id, views)
        }
    }
}

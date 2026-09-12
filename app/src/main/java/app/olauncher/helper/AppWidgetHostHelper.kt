package app.olauncher.helper

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

class AppWidgetHostHelper(private val context: Context) {

    val hostId = 1024
    val appWidgetHost: AppWidgetHost = AppWidgetHost(context.applicationContext, hostId)
    val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context.applicationContext)

    fun startListening() {
        try {
            appWidgetHost.startListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopListening() {
        try {
            appWidgetHost.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun allocateAppWidgetId(): Int {
        return appWidgetHost.allocateAppWidgetId()
    }

    fun deleteAppWidgetId(appWidgetId: Int) {
        if (appWidgetId != -1) {
            try {
                appWidgetHost.deleteAppWidgetId(appWidgetId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun findWidgetProviderInfo(packageName: String): AppWidgetProviderInfo? {
        val installedProviders = appWidgetManager.installedProviders
        return installedProviders.firstOrNull { it.provider.packageName == packageName }
    }

    fun createWidgetView(context: Context, appWidgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView? {
        return try {
            val view = appWidgetHost.createView(context, appWidgetId, info)
            view.setAppWidget(appWidgetId, info)
            view
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

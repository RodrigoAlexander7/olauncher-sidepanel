package app.olauncher.helper

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context

class AppWidgetHostHelper private constructor(context: Context) {

    companion object {
        const val HOST_ID = 1024
        @Volatile
        private var instance: AppWidgetHostHelper? = null

        fun getInstance(context: Context): AppWidgetHostHelper {
            return instance ?: synchronized(this) {
                instance ?: AppWidgetHostHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    val appWidgetHost: AppWidgetHost = AppWidgetHost(context.applicationContext, HOST_ID)
    val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context.applicationContext)

    private var isListening = false

    fun startListening() {
        if (!isListening) {
            try {
                appWidgetHost.startListening()
                isListening = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stopListening() {
        if (isListening) {
            try {
                appWidgetHost.stopListening()
                isListening = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

    fun createWidgetView(context: Context, appWidgetId: Int, info: AppWidgetProviderInfo, heightDp: Int = 350): AppWidgetHostView? {
        return try {
            startListening()

            val dm = context.resources.displayMetrics
            val widthDp = (dm.widthPixels / dm.density).toInt()
            val options = android.os.Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
            }
            appWidgetManager.updateAppWidgetOptions(appWidgetId, options)

            val view = appWidgetHost.createView(context, appWidgetId, info)
            view.setAppWidget(appWidgetId, info)
            view.updateAppWidgetSize(options, widthDp, heightDp, widthDp, heightDp)
            view
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

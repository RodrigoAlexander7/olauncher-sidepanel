package app.olauncher.helper

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

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

    private val appContext: Context = context.applicationContext

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

    /**
     * Info of the provider this id is actually bound to. Never derive it from the package name:
     * an app can ship several providers and the one we bound to may not be the first in the list.
     */
    fun getBoundWidgetInfo(appWidgetId: Int): AppWidgetProviderInfo? {
        if (appWidgetId == -1) return null
        return try {
            appWidgetManager.getAppWidgetInfo(appWidgetId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun createWidgetView(context: Context, appWidgetId: Int, heightDp: Int = 350): AppWidgetHostView? {
        val info = getBoundWidgetInfo(appWidgetId) ?: return null
        return try {
            startListening()

            val dm = context.resources.displayMetrics
            val widthDp = (dm.widthPixels / dm.density).toInt()
            val options = buildOptions(widthDp, heightDp)
            appWidgetManager.updateAppWidgetOptions(appWidgetId, options)

            val view = appWidgetHost.createView(context, appWidgetId, info)
            view.updateAppWidgetSize(options, widthDp, heightDp, widthDp, heightDp)
            requestUpdate(appWidgetId, info)
            view
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun buildOptions(widthDp: Int, heightDp: Int): Bundle = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
        putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
    }

    /**
     * Providers only fill their views when they receive an update. The system sends one at bind
     * time, but a provider that was asleep (or whose update we missed while not listening) leaves
     * the host showing its initialLayout forever, which is the "Loading..." placeholder. Poking it
     * explicitly makes it push real RemoteViews.
     */
    fun requestUpdate(appWidgetId: Int, info: AppWidgetProviderInfo? = null) {
        if (appWidgetId == -1) return
        val providerInfo = info ?: getBoundWidgetInfo(appWidgetId) ?: return
        try {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                component = providerInfo.provider
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            }
            appContext.sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

}

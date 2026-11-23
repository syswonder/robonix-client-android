package com.robonix.client

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide events written to [AppLog]: crashes, why the previous process
 * ended, activity lifecycle, memory trims and default-network changes.
 */
object AppLogHooks {
    fun install(app: Application) {
        installCrashHandler()
        logPreviousExit(app)
        app.registerActivityLifecycleCallbacks(LifecycleLogger)
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(NetworkLogger())
        } catch (e: Exception) {
            AppLog.write("NET", "network callback not registered", e)
        }
    }

    fun onTrimMemory(level: Int) {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        AppLog.write("APP", "onTrimMemory level=$level heapUsed=${usedMb}MB")
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppLog.write("CRASH", "FATAL on ${thread.name}", throwable)
                AppLog.close()
            } catch (_: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** The system keeps why each earlier process died (killed for memory, ANR, native crash...). */
    private fun logPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(ActivityManager::class.java)
            // Newest first; skip the WebView's isolated renderer processes.
            val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .firstOrNull { it.processName == context.packageName } ?: return
            val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
            AppLog.write(
                "APP",
                "previous exit at $at pid=${exit.pid} reason=${exitReason(exit.reason)} " +
                    "status=${exit.status} importance=${exit.importance} " +
                    "pss=${exit.pss / 1024}MB rss=${exit.rss / 1024}MB desc=${exit.description}",
            )
        } catch (e: Exception) {
            AppLog.write("APP", "previous exit unavailable", e)
        }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "code$reason"
    }

    private object LifecycleLogger : Application.ActivityLifecycleCallbacks {
        private fun log(activity: Activity, event: String) =
            AppLog.write("LIFECYCLE", "${activity.javaClass.simpleName} $event")

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
            log(activity, if (savedInstanceState != null) "created (restored)" else "created")
        override fun onActivityStarted(activity: Activity) = log(activity, "started")
        override fun onActivityResumed(activity: Activity) = log(activity, "resumed")
        override fun onActivityPaused(activity: Activity) = log(activity, "paused")
        override fun onActivityStopped(activity: Activity) = log(activity, "stopped")
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) =
            log(activity, if (activity.isChangingConfigurations) "destroyed (config change)" else "destroyed")
    }

    /** Callbacks arrive on one ConnectivityManager thread, so the dedupe fields need no lock. */
    private class NetworkLogger : ConnectivityManager.NetworkCallback() {
        private var lastCaps = ""
        private var lastAddrs = ""

        override fun onAvailable(network: Network) {
            AppLog.write("NET", "default network available net=$network")
        }

        override fun onLost(network: Network) {
            lastCaps = ""
            lastAddrs = ""
            AppLog.write("NET", "default network lost net=$network")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val transports = buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            }.joinToString("+").ifEmpty { "other" }
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val summary = "$transports validated=$validated"
            if (summary != lastCaps) {
                lastCaps = summary
                AppLog.write("NET", "net=$network $summary")
            }
        }

        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
            val addrs = "${props.interfaceName} " +
                props.linkAddresses.joinToString(",") { it.address.hostAddress ?: "?" }
            if (addrs != lastAddrs) {
                lastAddrs = addrs
                AppLog.write("NET", "net=$network addrs=$addrs")
            }
        }
    }
}

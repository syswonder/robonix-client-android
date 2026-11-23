package com.robonix.client

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RobonixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLogHooks.install(this)
        AppLog.write("APP", "RobonixApp onCreate")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLogHooks.onTrimMemory(level)
    }
}

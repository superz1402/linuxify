package com.linuxify.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.linuxify.app.runtime.CrashReporter

class LinuxifyApp : Application() {

    companion object {
        /** Set in onCreate before anything else can touch it. */
        @Volatile
        lateinit var instance: LinuxifyApp
            private set

        /**
         * Whether any Activity of this process is currently resumed. "Run
         * completed" notifications are only posted when this is false — in the
         * foreground the user is watching, and a popup would be noise.
         */
        @Volatile
        var isInForeground: Boolean = true
            private set
    }

    private val activityCounter = object : ActivityLifecycleCallbacks {
        private var resumed = 0

        override fun onActivityStarted(activity: Activity) {
            synchronized(this) { resumed++; isInForeground = true }
        }

        override fun onActivityStopped(activity: Activity) {
            synchronized(this) {
                resumed = (resumed - 1).coerceAtLeast(0)
                isInForeground = resumed == 0
            }
        }

        override fun onActivityCreated(a: Activity, s: Bundle?) {}
        override fun onActivityDestroyed(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(activityCounter)
        // Any uncaught exception must present as an in-app, copyable report —
        // never as a silent "app keeps stopping" dialog (zero-opaque-errors).
        CrashReporter.install(this)
    }
}

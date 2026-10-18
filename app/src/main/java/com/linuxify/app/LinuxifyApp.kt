package com.linuxify.app

import android.app.Application
import com.linuxify.app.runtime.CrashReporter

class LinuxifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Any uncaught exception must present as an in-app, copyable report —
        // never as a silent "app keeps stopping" dialog (zero-opaque-errors).
        CrashReporter.install(this)
    }
}

package com.linuxify.app

import android.app.Application

class LinuxifyApp : Application() {
    // The AppViewModel owns runtime managers. When Android kills the app process,
    // child proot processes are terminated by the OS process-group teardown in
    // most cases; --kill-on-exit additionally guarantees tracees die with proot.
    // This is documented honestly in docs/RUNTIME.md.
}

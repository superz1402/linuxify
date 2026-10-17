# Keep Termux terminal JNI bridge (loaded via System.loadLibrary("termux"))
-keep class com.termux.terminal.JNI { *; }
# Keep terminal classes whose native methods / reflection may be involved
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

package com.linuxify.app.runtime

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.linuxify.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort diagnostics so a failure can never present as a silent
 * "app keeps stopping" dialog.
 *
 *  - Any uncaught exception is rendered with device/build context, persisted
 *    to app-private storage (survives the crash), and shown in a dedicated
 *    plain-Android report activity with a Copy button — the report is readable
 *    without adb, which is the only way a sideloaded-alpha user can relay it.
 *  - Non-fatal errors caught inside coroutines/UI are appended to the same
 *    log directory so patterns remain visible without crashing the app.
 *
 * The report activity deliberately does NOT use Compose: if the crash is
 * inside the Compose stack, the reporter must still be able to draw.
 */
object CrashReporter {

    private const val MAX_REPORT_BYTES = 64 * 1024

    fun install(appContext: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = render(thread, throwable)
            var reported = false
            try {
                save(appContext, trace)
                val intent = Intent(appContext, CrashReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashReportActivity.EXTRA_TRACE, trace)
                }
                appContext.startActivity(intent)
                reported = true
            } catch (_: Throwable) {
                // Reporting must never throw; fall back to the platform handler.
            }
            if (reported) {
                // We own the report now: let the startActivity IPC land, then exit
                // WITHOUT invoking the platform handler, so the system "app keeps
                // stopping" dialog never stacks on top of the report screen.
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                }
                Runtime.getRuntime().exit(2)
            } else {
                previous?.uncaughtException(thread, throwable)
                    ?: Runtime.getRuntime().exit(2)
            }
        }
    }

    fun render(thread: Thread, t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("Linuxify crash report")
        pw.println("when: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        pw.println("thread: ${thread.name}")
        pw.println("app: ${BuildConfig.VERSION_NAME} (vc ${BuildConfig.VERSION_CODE})")
        pw.println("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        pw.println("abi: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        pw.println("model: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println()
        t.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    fun reportFile(ctx: Context): File = File(RuntimeDirs.logsDir(ctx), "last-crash.txt")

    fun save(ctx: Context, trace: String) {
        runCatching {
            val f = reportFile(ctx)
            f.parentFile?.mkdirs()
            if (f.length() > MAX_REPORT_BYTES) f.delete()
            f.writeText(trace.take(MAX_REPORT_BYTES))
        }
    }

    /** Append a handled-but-notable error to a bounded log (never throws). */
    fun logNonFatal(ctx: Context, where: String, t: Throwable) {
        runCatching {
            val f = File(RuntimeDirs.logsDir(ctx), "nonfatal.log")
            f.parentFile?.mkdirs()
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val entry = "\n==== $where @ ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} ====\n" + sw.toString()
            f.appendText(entry.take(8_000))
            if (f.length() > 256 * 1024) f.delete()
        }
    }
}

/**
 * Plain-Android crash report screen: monospace, selectable, one tap to copy.
 * Registered in the manifest as not exported; only CrashReporter starts it.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace = intent.getStringExtra(EXTRA_TRACE)
            ?: runCatching { CrashReporter.reportFile(this).readText() }.getOrNull()
            ?: "No trace available."

        val pad = (resources.displayMetrics.density * 16).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Linuxify hit an unexpected error"
            textSize = 18f
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(title)

        val hint = TextView(this).apply {
            text = "The details below help fix the bug. Tap \"Copy report\" and paste it to the developer, then \"Restart app\"."
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(hint)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF14181F.toInt())
            clipToPadding = true
        }
        val traceView = TextView(this).apply {
            text = trace
            textSize = 12f
            setTextColor(0xFFE6E6E6.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        scroll.addView(traceView)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 2, 0, 0)
        }
        buttons.addView(
            Button(this).apply {
                text = "Copy report"
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Linuxify crash report", trace))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = pad / 2
            },
        )
        buttons.addView(
            Button(this).apply {
                text = "Restart app"
                setOnClickListener {
                    val i = packageManager.getLaunchIntentForPackage(packageName)
                    i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(i)
                    finishAffinity()
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(buttons)

        setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    companion object {
        const val EXTRA_TRACE = "trace"
    }
}

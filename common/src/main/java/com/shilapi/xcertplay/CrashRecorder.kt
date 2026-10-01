package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Persists Java/Kotlin crashes so field testing does not require adb/logcat. */
object CrashRecorder {
    private const val PREFS = "diplay_crash_recorder"
    private const val KEY_REPORT = "last_crash"
    private const val CRASH_FILE = "last-crash.txt"
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
                val report = "MG4CPlay crash\nTime: $timestamp\nThread: ${thread.name}\n" +
                    "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n" +
                    "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n$trace"
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_REPORT, report).commit()
                // Keep a second, durable copy. The dialog consumes SharedPreferences on launch,
                // while field diagnostics are commonly exported only after the app restarts.
                val file = File(File(appContext.filesDir, "logs"), CRASH_FILE)
                file.parentFile?.mkdirs()
                file.writeText(report)
            } catch (_: Throwable) {
                // Never replace the original failure with a recorder failure.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun consume(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val report = prefs.getString(KEY_REPORT, null) ?: return null
        prefs.edit().remove(KEY_REPORT).commit()
        return report
    }

    fun persisted(context: Context): String? = runCatching {
        File(File(context.filesDir, "logs"), CRASH_FILE)
            .takeIf { it.isFile }
            ?.readText()
    }.getOrNull()
}

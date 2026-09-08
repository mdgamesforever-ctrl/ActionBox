package com.futurepath.actionbox.diagnostics

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Minimal, dependency-free crash diagnostics. Added after a startup crash turned out to be
 * un-diagnosable without a physical device: an uncaught exception in one of
 * [com.futurepath.actionbox.ActionBoxApplication]'s background collectors (reminder scheduling,
 * widget updates) was taking down the whole process before any screen could even appear, with
 * nothing but "the app crashed" to go on.
 *
 * Persists the last uncaught exception to a file under the app's private storage — retrievable
 * without a live logcat session via, e.g.,
 * `adb shell run-as com.futurepath.actionbox cat files/last_crash.txt` on a debuggable build —
 * in addition to logging it the normal way via [Log.e].
 */
object CrashLogger {
    private const val TAG = "ActionBoxCrash"
    private const val CRASH_LOG_FILE_NAME = "last_crash.txt"

    /**
     * Installs a process-wide uncaught exception handler that records the crash (see [record])
     * before handing off to whatever handler was previously registered — this only adds a
     * diagnostic side effect, it never suppresses or changes the crash itself.
     */
    fun installGlobalHandler(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                record(appContext, throwable, thread)
            } catch (loggingFailure: Exception) {
                Log.e(TAG, "Failed to persist crash log", loggingFailure)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Logs [throwable] and overwrites the persisted crash file with it. Also called directly
     * (not just from the global handler) by background collectors that catch and log an
     * exception instead of letting it crash the app — see ActionBoxApplication.
     */
    fun record(context: Context, throwable: Throwable, thread: Thread = Thread.currentThread()) {
        Log.e(TAG, "Unhandled exception on thread '${thread.name}'", throwable)
        try {
            File(context.applicationContext.filesDir, CRASH_LOG_FILE_NAME).writeText(
                "Time: ${System.currentTimeMillis()}\n" +
                    "Thread: ${thread.name}\n" +
                    Log.getStackTraceString(throwable)
            )
        } catch (writeFailure: Exception) {
            Log.e(TAG, "Failed to write crash log file", writeFailure)
        }
    }

    /**
     * Reads back the persisted crash file directly via the app's own file access — no adb
     * needed — for [com.futurepath.actionbox.ui.settings.CrashLogScreen]. Null if nothing has
     * been recorded (no crash yet since install, or since app data was last cleared) or if the
     * file can't be read for some reason.
     */
    fun readLastCrash(context: Context): String? {
        val file = File(context.applicationContext.filesDir, CRASH_LOG_FILE_NAME)
        if (!file.exists()) return null
        return try {
            file.readText()
        } catch (readFailure: Exception) {
            Log.e(TAG, "Failed to read crash log file", readFailure)
            null
        }
    }
}

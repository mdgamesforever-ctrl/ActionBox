package com.futurepath.actionbox.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal, dependency-free crash diagnostics. Added after a startup crash turned out to be
 * un-diagnosable without a physical device: an uncaught exception in one of
 * [com.futurepath.actionbox.ActionBoxApplication]'s background collectors (reminder scheduling,
 * widget updates) was taking down the whole process before any screen could even appear, with
 * nothing but "the app crashed" to go on.
 *
 * Persists every recorded exception to THREE places, each covering a different way retrieval can
 * fail:
 *  1. The app's private storage (`filesDir/last_crash.txt`) — read in-app by
 *     [com.futurepath.actionbox.ui.settings.CrashLogScreen], for when the app is at least
 *     reachable but adb isn't.
 *  2. The public Downloads folder, via [MediaStore] (Android 10+ — see [writeToDownloads]) — a
 *     plain file any file manager or the Files app can open directly, for when adb ISN'T
 *     reachable (wireless debugging pairing failures) and there's no reliance on the app's UI
 *     still working, since this write happens from the crash handler itself before the process
 *     dies.
 *  3. Logcat, via [Log.e], for whenever a live logcat session IS available.
 */
object CrashLogger {
    private const val TAG = "ActionBoxCrash"
    private const val CRASH_LOG_FILE_NAME = "last_crash.txt"
    private const val DOWNLOADS_FILE_PREFIX = "ActionBox_crash_"

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
     * Logs [throwable] and persists it to both the private crash file and, on Android 10+, a
     * timestamped file in the public Downloads folder. Also called directly (not just from the
     * global handler) by background collectors that catch and log an exception instead of
     * letting it crash the app — see ActionBoxApplication.
     */
    fun record(context: Context, throwable: Throwable, thread: Thread = Thread.currentThread()) {
        Log.e(TAG, "Unhandled exception on thread '${thread.name}'", throwable)
        val appContext = context.applicationContext
        val text = "Time: ${System.currentTimeMillis()}\n" +
            "Thread: ${thread.name}\n" +
            Log.getStackTraceString(throwable)

        try {
            File(appContext.filesDir, CRASH_LOG_FILE_NAME).writeText(text)
        } catch (writeFailure: Exception) {
            Log.e(TAG, "Failed to write private crash log file", writeFailure)
        }

        try {
            writeToDownloads(appContext, text)
        } catch (writeFailure: Exception) {
            Log.e(TAG, "Failed to write crash log to Downloads", writeFailure)
        }
    }

    /**
     * Writes [text] as a new plain-text file directly in the shared Downloads folder via
     * [MediaStore] — the modern (scoped-storage-compliant) way to reach public storage, needing
     * no `WRITE_EXTERNAL_STORAGE` permission on the API levels it targets. A fresh, timestamped
     * file per crash (rather than one overwritten file) avoids needing to query for and reuse an
     * existing MediaStore entry, and leaves a full history to browse if there have been several.
     *
     * No-ops below Android 10 (API 29): the pre-scoped-storage direct-file APIs this would
     * otherwise need are a separate, deprecated code path (and a runtime permission) not worth
     * adding for what is purely a local debug-testing aid.
     */
    private fun writeToDownloads(context: Context, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val fileName = "$DOWNLOADS_FILE_PREFIX${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: run {
            Log.e(TAG, "MediaStore refused to create the crash log download entry")
            return
        }
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
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

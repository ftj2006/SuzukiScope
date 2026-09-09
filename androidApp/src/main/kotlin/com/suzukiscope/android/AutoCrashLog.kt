package com.suzukiscope.android

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

object AutoCrashLog {
    private var context: Context? = null
    private var enabled = true
    private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        this.context = context.applicationContext
        file = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS),
            "SuzukiScope/Debug/android-auto-crash.log",
        ).apply { parentFile?.mkdirs() }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            append("Uncaught exception on ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun append(message: String, throwable: Throwable? = null) {
        if (!enabled) return
        val details = throwable?.let { StringWriter().also { writer -> it.printStackTrace(PrintWriter(writer)) }.toString() }.orEmpty()
        val entry = "${Instant.now()} $message\n$details\n"
        file?.appendText(entry)
        context?.let { ExportStore.saveDebugEntry(it, entry) }
    }

    fun setEnabled(value: Boolean) { enabled = value }
}
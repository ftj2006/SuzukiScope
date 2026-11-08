package com.suzukiscope.android

import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.content.ContentValues
import android.content.Context
import java.io.File
import java.time.LocalDate

object ExportStore {
    private fun dayFolder(name: String): File = File(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SuzukiScope"),
        "$name/${LocalDate.now()}",
    ).apply { mkdirs() }

    /** Returns a user-readable location for each saved file. */
    fun save(context: Context, files: List<com.suzukiscope.ui.DashboardViewModel.ExportFile>): List<String> =
        files.map { file ->
            val folderName = if (file.name.endsWith(".csv")) "Logs" else "Debug"
            val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/SuzukiScope/$folderName/${LocalDate.now()}/"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, if (file.name.endsWith(".csv")) "text/csv" else "text/plain")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, relativePath)
                }
                val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: error("Unable to create export file in Documents/$folderName")
                context.contentResolver.openOutputStream(uri)?.use { it.write(file.contents.encodeToByteArray()) }
                    ?: error("Unable to write export file ${file.name}")
                "$relativePath${file.name}"
            } else {
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "SuzukiScope/$folderName/${LocalDate.now()}")
                    .apply { mkdirs() }
                    .resolve(file.name)
                    .apply { writeText(file.contents) }
                    .absolutePath
            }
        }

    fun notifyRecordingSaved(context: Context, path: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, "Recording saved to $path", android.widget.Toast.LENGTH_LONG).show()
        }
        val canPost = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!canPost) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            android.app.NotificationChannel(RECORDINGS_CHANNEL, "Recordings", android.app.NotificationManager.IMPORTANCE_DEFAULT),
        )
        val openFolder = android.app.PendingIntent.getActivity(
            context,
            0,
            openFolderIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(context, RECORDINGS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Recording saved")
            .setContentText(path)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(path))
            .setContentIntent(openFolder)
            .setAutoCancel(true)
            .build()
        manager.notify(path.hashCode(), notification)
    }

    private const val RECORDINGS_CHANNEL = "recordings"

    fun saveDebugEntry(context: Context, entry: String) {
        save(context, listOf(com.suzukiscope.ui.DashboardViewModel.ExportFile(
            "android-auto-crash-${System.currentTimeMillis()}.log",
            entry,
        )))
    }

    fun openFolderIntent(context: Context): Intent {
        val folderUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Documents/SuzukiScope",
        )
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (context.packageManager.resolveActivity(viewIntent, 0) != null) return viewIntent
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, folderUri)
        }
    }
}
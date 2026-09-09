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

    fun save(context: Context, files: List<com.suzukiscope.ui.DashboardViewModel.ExportFile>) {
        files.forEach { file ->
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
            } else {
                File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "SuzukiScope/$folderName/${LocalDate.now()}")
                    .apply { mkdirs() }
                    .resolve(file.name)
                    .writeText(file.contents)
            }
        }
    }

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
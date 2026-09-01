package com.machinepulse.edge.capture

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val REQUIRED_SESSION_FILES = listOf(
    "accelerometer.csv",
    "audio.wav",
    "metadata.json",
)

internal class SessionExporter(context: Context) {
    private val appContext = context.applicationContext
    private val sessionsDirectory = File(appContext.filesDir, "sessions")
    private val exportsDirectory = File(appContext.cacheDir, "session_exports")

    fun createLatestSessionShareIntent(): Intent {
        val session = latestCompletedSession()
            ?: throw IllegalStateException("No completed combined session is available to export.")
        exportsDirectory.mkdirs()
        exportsDirectory.listFiles()?.forEach { it.delete() }

        val archive = File(exportsDirectory, "machinepulse_${session.name}.zip")
        zipSessionDirectory(session, archive)
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            archive,
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MachinePulse session ${session.name}")
            clipData = ClipData.newRawUri("MachinePulse session", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun latestCompletedSession(): File? {
        return sessionsDirectory.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.filter { directory -> REQUIRED_SESSION_FILES.all { File(directory, it).isFile } }
            ?.filter { directory ->
                runCatching {
                    JSONObject(File(directory, "metadata.json").readText())
                        .optString("outcome") == "completed"
                }.getOrDefault(false)
            }
            ?.maxByOrNull { it.name }
    }
}

internal fun zipSessionDirectory(sessionDirectory: File, outputFile: File) {
    require(sessionDirectory.isDirectory) { "Session directory does not exist." }
    val files = REQUIRED_SESSION_FILES.map { name ->
        File(sessionDirectory, name).also {
            require(it.isFile) { "Session is missing $name." }
        }
    }

    ZipOutputStream(FileOutputStream(outputFile)).use { zip ->
        files.forEach { file ->
            zip.putNextEntry(ZipEntry(file.name))
            file.inputStream().use { input -> input.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

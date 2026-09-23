package com.romp.listen.app.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.romp.listen.app.settings.SettingsManager
import com.romp.listen.app.util.AppLog
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Uploads a closed audio segment to the FastAPI backend (/ingest/audio).
 * OpenClaw fork: replaces the missing "send logs every X minutes" piece.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            AppLog.w(TAG, "Upload skipped, file missing/empty: $path")
            return Result.failure()
        }
        if (isUploaded(file.name)) return Result.success()

        val settings = SettingsManager(applicationContext)
        if (!settings.uploadOnMobileData && !isUnmetered()) {
            AppLog.d(TAG, "Upload deferred (metered network): ${file.name}")
            return Result.retry()
        }

        return try {
            val code = postMultipart(settings.uploadEndpointUrl, settings.uploadSecret, file)
            when {
                code in 200..299 -> {
                    markUploaded(file.name)
                    AppLog.d(TAG, "Uploaded ${file.name} ($code)")
                    Result.success()
                }
                code == 401 || code == 400 -> {
                    AppLog.e(TAG, "Upload rejected ($code) for ${file.name}, not retrying")
                    Result.failure()
                }
                else -> {
                    AppLog.w(TAG, "Upload HTTP $code for ${file.name}, retrying")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Upload failed for ${file.name}, retrying: ${e.message}")
            Result.retry()
        }
    }

    private fun isUnmetered(): Boolean {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (_: Exception) {
            false
        }
    }

    private fun postMultipart(urlStr: String, secret: String, file: File): Int {
        val boundary = "listen-${System.currentTimeMillis()}"
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (secret.isNotBlank()) setRequestProperty("x-ingest-secret", secret)
        }
        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"device\"\r\n\r\nandroid\r\n")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
            out.writeBytes("Content-Type: audio/aac\r\n\r\n")
            FileInputStream(file).use { it.copyTo(out) }
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
        }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun isUploaded(name: String): Boolean {
        val prefs = applicationContext.getSharedPreferences(PREFS_UPLOADS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_DONE, emptySet())?.contains(name) == true
    }

    private fun markUploaded(name: String) {
        val prefs = applicationContext.getSharedPreferences(PREFS_UPLOADS, Context.MODE_PRIVATE)
        val done = (prefs.getStringSet(KEY_DONE, emptySet()) ?: emptySet()).toMutableSet()
        done.add(name)
        // keep the set bounded (last ~2000 entries)
        while (done.size > 2000) done.remove(done.first())
        prefs.edit { putStringSet(KEY_DONE, done) }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val PREFS_UPLOADS = "listen_uploads"
        private const val KEY_DONE = "uploaded_files"
        const val KEY_FILE_PATH = "file_path"

        /** Enqueue upload of a freshly closed segment. */
        fun enqueue(context: Context, file: File) {
            val settings = SettingsManager(context)
            if (settings.uploadEndpointUrl.isBlank()) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresCharging(settings.uploadOnlyWhenCharging)
                .build()
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(Data.Builder().putString(KEY_FILE_PATH, file.absolutePath).build())
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "upload-${file.name}",
                ExistingWorkPolicy.KEEP,
                req
            )
            AppLog.d(TAG, "Upload enqueued: ${file.name}")
        }
    }
}

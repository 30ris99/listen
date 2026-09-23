package com.romp.listen.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.romp.listen.app.R
import com.romp.listen.app.settings.SettingsManager
import com.romp.listen.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anteprima di cosa ha trascritto il PC: ultimi segmenti + stato.
 * OpenClaw fork. Poll leggero ogni 30s solo mentre la schermata è aperta.
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var tvStatus: TextView
    private lateinit var tvBody: TextView
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)
        settings = SettingsManager(this)
        tvStatus = findViewById(R.id.tv_preview_status)
        tvBody = findViewById(R.id.tv_preview_body)
        findViewById<Button>(R.id.btn_preview_refresh).setOnClickListener { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                refresh()
            }
        }
    }

    override fun onPause() {
        pollJob?.cancel()
        super.onPause()
    }

    private fun refresh() {
        lifecycleScope.launch {
            tvStatus.text = "Aggiornamento…"
            try {
                val out = withContext(Dispatchers.IO) { fetchLatest() }
                tvStatus.text = out.first
                tvBody.text = out.second
            } catch (e: Exception) {
                AppLog.w(TAG, "Preview fetch failed", e)
                tvStatus.text = "Errore: ${e.message ?: "connessione"}"
            }
        }
    }

    private fun fetchLatest(): Pair<String, String> {
        // Deriva /listen/latest dall'URL di upload configurato
        val base = settings.uploadEndpointUrl.substringBefore("/ingest/audio")
        val url = URL("$base/listen/latest?limit=10")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            if (settings.uploadSecret.isNotBlank()) {
                setRequestProperty("x-ingest-secret", settings.uploadSecret)
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return "HTTP $code" to "Il server ha risposto $code."
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val pending = json.optInt("pending_transcription", 0)
            val items = json.optJSONArray("items")
            val sb = StringBuilder()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val at = o.optString("at", "?").take(16).replace("T", " ")
                    val dur = (o.optDouble("duration", 0.0) / 60).toInt()
                    val text = o.optString("text", "").trim().ifBlank { "(silenzio / non udibile)" }
                    sb.append("[$at · ~${dur}min] ${o.optString("file", "")}\n$text\n\n")
                }
            }
            if (sb.isEmpty()) sb.append("Nessuna trascrizione ancora.")
            val status = if (pending > 0) "$pending segmenti in trascrizione…" else "Tutto trascritto ✓"
            return status to sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "PreviewActivity"
    }
}

package com.robonix.client.data.local

import android.content.Context
import android.util.AtomicFile
import com.robonix.client.AppLog
import com.robonix.client.data.model.AlertEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file get() = AtomicFile(File(context.filesDir, "robonix_alerts.json"))
    private val mutex = Mutex()

    companion object { const val MAX_HISTORY = 100 }

    suspend fun loadAll(): List<AlertEvent> = withContext(Dispatchers.IO) {
        mutex.withLock { readAll() }
    }

    private fun readAll(): List<AlertEvent> = try {
        val json = file.openRead().bufferedReader().use { it.readText() }
        AlertHistoryCodec.decode(json)
    } catch (_: java.io.FileNotFoundException) {
        emptyList()
    } catch (e: Exception) {
        AppLog.write("VITALS", "Failed to load alert history: ${e.message}", e)
        emptyList()
    }

    private fun writeAll(alerts: List<AlertEvent>) {
        val capped = alerts.sortedByDescending { it.timestampMs }.take(MAX_HISTORY)
        val atomic = file
        val stream = atomic.startWrite()
        try {
            stream.write(AlertHistoryCodec.encode(capped).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    /** Appends new alerts to the persisted history, de-duplicated and capped. */
    suspend fun append(newAlerts: List<AlertEvent>): List<AlertEvent> = withContext(Dispatchers.IO) {
        if (newAlerts.isEmpty()) return@withContext loadAll()
        mutex.withLock {
            val merged = (readAll() + newAlerts).distinct()
            writeAll(merged)
            merged.sortedByDescending { it.timestampMs }.take(MAX_HISTORY)
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock { file.delete() }
    }
}

/** Local alert history format, independent of the wire protocol. Bad entries are skipped individually. */
internal object AlertHistoryCodec {
    fun encode(alerts: List<AlertEvent>): String = JSONArray().apply {
        alerts.forEach { a ->
            put(JSONObject().apply {
                put("componentId", a.componentId)
                put("componentLabel", a.componentLabel)
                put("fromHealth", a.fromHealth)
                put("toHealth", a.toHealth)
                put("timestampMs", a.timestampMs)
            })
        }
    }.toString()

    fun decode(json: String): List<AlertEvent> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i -> runCatching {
            val o = arr.getJSONObject(i)
            AlertEvent(
                componentId = o.getString("componentId"),
                componentLabel = o.optString("componentLabel"),
                fromHealth = o.optString("fromHealth"),
                toHealth = o.optString("toHealth"),
                timestampMs = o.getLong("timestampMs"),
            )
        }.getOrNull() }
    }.getOrDefault(emptyList())
}

package com.robonix.client.data.local

import android.content.Context
import android.util.AtomicFile
import com.robonix.client.AppLog
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

data class Conversation(
    val id: String,
    val title: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val messagesJson: String = "[]",
    val timelineJson: String = "[]",
    val planJson: String? = null,
    val planRecordsJson: String = "[]",
    val batchesJson: String = "[]",
    val nodeStatesJson: String = "{}",
)

@Singleton
class ConversationStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file get() = AtomicFile(File(context.filesDir, "robonix_conversations.json"))
    private val mutex = Mutex()
    // In-flight snapshots issued before a destructive action must not recreate history.
    @Volatile private var revision = 0L
    // Protected by mutex. Deleting an unrelated history entry must not discard
    // the active conversation's terminal snapshot while its IO is queued.
    private var clearRevision = 0L
    private val deletedRevisions = mutableMapOf<String, Long>()
    fun revision(): Long = revision

    companion object { const val MAX_HISTORY = 30 }

    suspend fun loadAll(): List<Conversation> = withContext(Dispatchers.IO) {
        mutex.withLock { readAll() }
    }

    private fun readAll(): List<Conversation> = try {
        val arr = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        (0 until arr.length()).mapNotNull { i -> runCatching {
            val obj = arr.getJSONObject(i)
            Conversation(
                id = obj.getString("id"), title = obj.optString("title"), updatedAt = obj.optLong("updatedAt"),
                messagesJson = obj.optString("messages", "[]"), timelineJson = obj.optString("timeline", "[]"),
                planJson = obj.optString("plan", "null").takeIf { it != "null" },
                planRecordsJson = obj.optString("planRecords", "[]"), batchesJson = obj.optString("batches", "[]"),
                nodeStatesJson = obj.optString("nodeStates", "{}"),
            )
        }.getOrNull() }.filter { it.id.isNotBlank() }.sortedByDescending { it.updatedAt }
    } catch (_: java.io.FileNotFoundException) {
        emptyList()
    } catch (e: Exception) {
        AppLog.write("CONV", "Failed to load conversations: ${e.message}", e)
        emptyList()
    }

    private fun writeAll(conversations: List<Conversation>) {
        val arr = JSONArray()
        conversations.take(MAX_HISTORY).forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("title", c.title); put("updatedAt", c.updatedAt)
                put("messages", runCatching { JSONArray(c.messagesJson) }.getOrElse { JSONArray() })
                put("timeline", runCatching { JSONArray(c.timelineJson) }.getOrElse { JSONArray() })
                c.planJson?.let { put("plan", runCatching { JSONObject(it) }.getOrNull()) }
                put("planRecords", runCatching { JSONArray(c.planRecordsJson) }.getOrElse { JSONArray() })
                put("batches", runCatching { JSONArray(c.batchesJson) }.getOrElse { JSONArray() })
                put("nodeStates", runCatching { JSONObject(c.nodeStatesJson) }.getOrElse { JSONObject() })
            })
        }
        val atomic = file
        val stream = atomic.startWrite()
        try {
            stream.write(arr.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    suspend fun upsert(conversation: Conversation, expectedRevision: Long): List<Conversation> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = readAll()
            if (expectedRevision < clearRevision || expectedRevision < (deletedRevisions[conversation.id] ?: 0L)) return@withLock all
            val existing = all.find { it.id == conversation.id }
            val candidate = conversation.copy(updatedAt = existing?.updatedAt ?: conversation.updatedAt)
            val updated = if (existing == candidate) candidate else conversation
            val result = (all.filterNot { it.id == updated.id } + updated)
                .sortedByDescending { it.updatedAt }.take(MAX_HISTORY)
            writeAll(result)
            result
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            revision++
            deletedRevisions[id] = revision
            writeAll(readAll().filterNot { it.id == id })
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            revision++
            clearRevision = revision
            deletedRevisions.clear()
            file.delete()
        }
    }
}

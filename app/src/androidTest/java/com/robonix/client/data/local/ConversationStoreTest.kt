package com.robonix.client.data.local

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses real Android AtomicFile rather than android.jar's JVM stubs. */
class ConversationStoreTest {
    private lateinit var directory: File
    private lateinit var store: ConversationStore

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "conversation-test-${UUID.randomUUID()}").apply { mkdirs() }
        store = ConversationStore(object : ContextWrapper(context) {
            override fun getFilesDir(): File = directory
        })
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun deletionRejectsOldSnapshotOnlyForDeletedSession() = runBlocking {
        val revision = store.revision()
        store.upsert(Conversation("deleted"), revision)
        store.delete("deleted")
        store.upsert(Conversation("deleted", title = "stale"), revision)
        store.upsert(Conversation("active", title = "terminal reply"), revision)
        val saved = store.loadAll()
        assertEquals(listOf("active"), saved.map { it.id })
        assertEquals("terminal reply", saved.single().title)
    }

    @Test fun clearRejectsEveryOldSnapshotButAcceptsNewSession() = runBlocking {
        val revision = store.revision()
        store.upsert(Conversation("old"), revision)
        store.clearAll()
        store.upsert(Conversation("old"), revision)
        store.upsert(Conversation("queued"), revision)
        assertTrue(store.loadAll().isEmpty())
        store.upsert(Conversation("new"), store.revision())
        assertEquals(listOf("new"), store.loadAll().map { it.id })
    }

    @Test fun unchangedSaveDoesNotReorderHistory() = runBlocking {
        val revision = store.revision()
        val old = Conversation("old", updatedAt = 1L)
        store.upsert(old, revision)
        store.upsert(Conversation("recent", updatedAt = 2L), revision)
        store.upsert(old, revision)
        assertEquals(listOf("recent", "old"), store.loadAll().map { it.id })
        store.upsert(old.copy(title = "changed", updatedAt = 4L), revision)
        assertEquals(listOf("old", "recent"), store.loadAll().map { it.id })
    }
}

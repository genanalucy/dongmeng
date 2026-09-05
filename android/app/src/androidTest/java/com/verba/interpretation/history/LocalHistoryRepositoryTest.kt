package com.verba.interpretation.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHistoryRepositoryTest {
    @Test
    fun completedTurnCreatesOneSessionAndEncryptedOutbox() = runTest {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scheduler = RecordingScheduler()
        val repository = LocalHistoryRepository(database, ReversingCipher, scheduler) { "id-${scheduler.nextId++}" }

        val sessionId = repository.recordCompletedTurn(
            CompletedTurn("user-1", null, "solo", "zh", "en", "你好", "hello", 10L),
        )

        assertEquals("id-0", sessionId)
        val operation = database.historyDao().pendingOutbox("user-1", 10).single()
        assertTrue(operation.payloadEncrypted.startsWith("encrypted:"))
        assertEquals(listOf("user-1"), scheduler.scheduled)
        database.close()
    }

    @Test
    fun deleteHasPriorityOverUpsert() = runTest {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scheduler = RecordingScheduler()
        val repository = LocalHistoryRepository(database, ReversingCipher, scheduler) { "id-${scheduler.nextId++}" }
        val sessionId = repository.recordCompletedTurn(CompletedTurn("user-1", null, "solo", "zh", "en", "你好", "hello", 10L))

        repository.deleteSession("user-1", sessionId, 20L)

        assertEquals("DELETE", database.historyDao().pendingOutbox("user-1", 10).first().operation)
        database.close()
    }

    private object ReversingCipher : HistoryPayloadCipher {
        override fun encrypt(plainText: String) = "encrypted:$plainText"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("encrypted:")
    }

    private class RecordingScheduler : HistorySyncScheduler {
        val scheduled = mutableListOf<String>()
        var nextId = 0
        override fun schedule(userId: String) { scheduled += userId }
        override fun cancel(userId: String) = Unit
    }
}

package com.verba.interpretation.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.verba.interpretation.cloud.HistoryPullResponse
import com.verba.interpretation.cloud.HistoryPushOperation
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHistoryRepositoryTest {
    @Test
    fun completedTurnCreatesOneSessionAndEncryptedOutbox() = runTest {
        fixture { database, scheduler, repository ->
            val sessionId = repository.recordCompletedTurn(turn())

            assertEquals("id-0", sessionId)
            val operation = database.historyDao().pendingOutbox(USER, 10).single()
            assertTrue(operation.payloadEncrypted.startsWith("encrypted:"))
            assertEquals(listOf(USER), scheduler.scheduled)
            assertEquals("你好", repository.observeHistory(USER).first().single().turns.single().sourceText)
        }
    }

    @Test
    fun stableRetryReturnsOriginalSessionWithoutDuplicateOutboxEvenAfterQuotaFailure() = runTest {
        fixture { database, scheduler, repository ->
            val request = turn().copy(stableTurnId = "stable-turn")
            val sessionId = repository.recordCompletedTurn(request)
            database.historyDao().upsertSyncState(HistorySyncStateEntity(USER, true))
            val reopenedRepository = LocalHistoryRepository(database, ReversingCipher, scheduler)

            assertEquals(sessionId, reopenedRepository.recordCompletedTurn(request))
            assertEquals(1, repository.observeHistory(USER).first().single().turns.size)
            assertEquals(1, database.historyDao().pendingOutbox(USER, 10).size)
        }
    }

    @Test
    fun identicalWordsWithDifferentStableIdsRemainTwoTurnsAndLegacyCallsRemainAppendOnly() = runTest {
        fixture { database, _, repository ->
            val sessionId = repository.recordCompletedTurn(turn().copy(stableTurnId = "first"))
            val request = turn().copy(localSessionId = sessionId)
            repository.recordCompletedTurn(request.copy(stableTurnId = "second"))
            repository.recordCompletedTurn(request)
            repository.recordCompletedTurn(request)

            assertEquals(4, repository.observeHistory(USER).first().single().turns.size)
            assertEquals(4, database.historyDao().pendingOutbox(USER, 10).size)
        }
    }

    @Test
    fun stableIdCannotBeReassignedToAnotherAccountOrSession() = runTest {
        fixture { database, _, repository ->
            val request = turn().copy(stableTurnId = "stable-turn")
            repository.recordCompletedTurn(request)

            assertRejected("history_turn_ownership_mismatch") {
                repository.recordCompletedTurn(request.copy(userId = "other-user"))
            }
            assertRejected("history_turn_ownership_mismatch") {
                repository.recordCompletedTurn(request.copy(localSessionId = "another-session"))
            }
            assertTrue(repository.observeHistory("other-user").first().isEmpty())
            assertEquals(1, database.historyDao().pendingOutbox(USER, 10).size)
        }
    }

    @Test
    fun deleteHasPriorityAndRejectsBothKnownSessionAndInitialNullSessionRetries() = runTest {
        fixture { database, _, repository ->
            val request = turn().copy(stableTurnId = "stable-turn")
            val sessionId = repository.recordCompletedTurn(request)
            repository.deleteSession(USER, sessionId, 20L)

            assertRejected("history_session_deleted") {
                repository.recordCompletedTurn(request.copy(localSessionId = sessionId))
            }
            assertRejected("history_turn_deleted") { repository.recordCompletedTurn(request) }
            assertRejected("history_session_deleted") {
                repository.recordCompletedTurn(request.copy(localSessionId = sessionId, stableTurnId = "late-turn"))
            }
            assertTrue(repository.observeHistory(USER).first().isEmpty())
            assertEquals("DELETE", database.historyDao().pendingOutbox(USER, 10).single().operation)
        }
    }

    @Test
    fun discardRevokesOldEpochAcrossRepositoriesButAllowsFreshBinding() = runTest {
        fixture { database, scheduler, _ ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val user = "epoch-test-${UUID.randomUUID()}"
            val first = LocalHistoryRepository(context, database, ReversingCipher, scheduler)
            val second = LocalHistoryRepository(context, database, ReversingCipher, scheduler)
            val epoch = LocalHistoryRepository.captureAccountEpoch(context, user)
            val request = turn().copy(userId = user, stableTurnId = "epoch-turn", accountEpoch = epoch)
            first.recordCompletedTurn(request)

            second.discardUser(user)
            assertRejected("history_account_revoked") { first.recordCompletedTurn(request) }
            assertTrue(first.observeHistory(user).first().isEmpty())
            assertTrue(database.historyDao().pendingOutbox(user, 10).isEmpty())
            val freshEpoch = LocalHistoryRepository.captureAccountEpoch(context, user)
            assertEquals(epoch + 1, freshEpoch)
            val recreated = LocalHistoryRepository(context, database, ReversingCipher, scheduler)
            recreated.recordCompletedTurn(request.copy(accountEpoch = freshEpoch))
            assertEquals(1, recreated.observeHistory(user).first().single().turns.size)
        }
    }

    @Test
    fun schedulerFailureDoesNotFailCommittedSaveOrDelete() = runTest {
        fixture { database, scheduler, repository ->
            scheduler.failScheduling = true
            val request = turn().copy(stableTurnId = "stable-turn")
            val sessionId = repository.recordCompletedTurn(request)
            assertEquals(sessionId, repository.recordCompletedTurn(request))
            assertEquals("hello", repository.observeHistory(USER).first().single().turns.single().translatedText)
            assertEquals(1, database.historyDao().pendingOutbox(USER, 10).size)

            repository.deleteSession(USER, sessionId, 20L)
            assertTrue(repository.observeHistory(USER).first().isEmpty())
            assertEquals("DELETE", database.historyDao().pendingOutbox(USER, 10).single().operation)
        }
    }

    @Test
    fun inactiveAccountNeverPushesOrPullsAndKeepsOutbox() = runTest {
        fixture { database, _, repository ->
            repository.recordCompletedTurn(turn())
            val transport = object : LocalHistoryTransport {
                override suspend fun isAccountActive(userId: String) = false
                override suspend fun push(operations: List<HistoryPushOperation>) = error("must not upload")
                override suspend fun pull(cursor: Long): HistoryPullResponse = error("must not pull")
            }

            assertEquals(HistorySyncFailure.UNAUTHORIZED, repository.sync(USER, transport).failure)
            assertEquals(1, database.historyDao().pendingOutbox(USER, 10).size)
        }
    }

    @Test
    fun discardDuringPullCannotRecreateLocalAccountState() = runTest {
        fixture { database, scheduler, _ ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val user = "sync-epoch-test-${UUID.randomUUID()}"
            val repository = LocalHistoryRepository(context, database, ReversingCipher, scheduler)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val transport = object : LocalHistoryTransport {
                override suspend fun push(operations: List<HistoryPushOperation>) = Unit
                override suspend fun pull(cursor: Long): HistoryPullResponse {
                    entered.complete(Unit)
                    release.await()
                    return HistoryPullResponse(emptyList(), 42L, false)
                }
            }
            val sync = async { repository.sync(user, transport) }
            entered.await()
            repository.discardUser(user)
            release.complete(Unit)

            assertFalse(sync.await().success)
            assertEquals(null, database.historyDao().cursor(user))
            assertTrue(repository.observeHistory(user).first().isEmpty())
        }
    }

    @Test
    fun committedTurnAndOutboxSurviveDatabaseReopenAndRetry() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "history-reliable-save-test-${UUID.randomUUID()}.db"
        val scheduler = RecordingScheduler()
        var database = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
        try {
            val request = turn().copy(stableTurnId = "stable-turn")
            val first = LocalHistoryRepository(context, database, ReversingCipher, scheduler)
            val sessionId = first.recordCompletedTurn(request)
            database.close()
            database = Room.databaseBuilder(context, HistoryDatabase::class.java, name).build()
            val reopened = LocalHistoryRepository(context, database, ReversingCipher, scheduler)

            assertEquals(sessionId, reopened.recordCompletedTurn(request))
            assertEquals("你好", reopened.observeHistory(USER).first().single().turns.single().sourceText)
            assertEquals(1, database.historyDao().pendingOutbox(USER, 10).size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private suspend fun fixture(block: suspend (HistoryDatabase, RecordingScheduler, LocalHistoryRepository) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HistoryDatabase::class.java).build()
        val scheduler = RecordingScheduler()
        try {
            block(database, scheduler, LocalHistoryRepository(database, ReversingCipher, scheduler) { "id-${scheduler.nextId++}" })
        } finally {
            database.close()
        }
    }

    private suspend fun assertRejected(message: String, block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected IllegalStateException, got $failure", failure is IllegalStateException)
        assertEquals(message, failure?.message)
    }

    private fun turn() = CompletedTurn(USER, null, "solo", "zh", "en", "你好", "hello", 10L)

    private object ReversingCipher : HistoryPayloadCipher {
        override fun encrypt(plainText: String) = "encrypted:$plainText"
        override fun decrypt(encrypted: String) = encrypted.removePrefix("encrypted:")
    }

    private class RecordingScheduler : HistorySyncScheduler {
        val scheduled = mutableListOf<String>()
        var nextId = 0
        var failScheduling = false
        override fun schedule(userId: String) {
            if (failScheduling) error("scheduler unavailable")
            scheduled += userId
        }
        override fun cancel(userId: String) = Unit
    }

    private companion object {
        const val USER = "user-1"
    }
}

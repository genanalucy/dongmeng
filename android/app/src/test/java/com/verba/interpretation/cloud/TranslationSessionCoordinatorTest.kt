package com.verba.interpretation.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSessionCoordinatorTest {
    @Test fun endsOnceAndReportsOnlyMinimalAggregatedUsage() = runBlocking {
        val service = FakeService()
        val clock = FakeClock(10_000L)
        val coordinator = TranslationSessionCoordinator(service, this, clock::now)
        var actual: TranslationSessionGrant? = null
        coordinator.open(onGranted = { actual = it }, onFailure = { throw AssertionError(it) })
        while (actual == null) kotlinx.coroutines.yield()

        clock.value = 13_900L
        coordinator.end(actual?.sessionId)
        coordinator.end(actual?.sessionId)
        while (service.ended == null || service.usage == null) kotlinx.coroutines.yield()

        assertEquals("session-1", service.ended)
        assertEquals(UsageRecordPayload("session-1", audioSeconds = 3, characters = 0), service.usage)
        assertEquals(1, service.usageCalls)
        assertEquals(1, service.endCalls)
    }

    @Test fun endTransportFailureRetainsEndEligibilityUntilASingleRetrySucceeds() = runBlocking {
        val service = FakeService(failEndAttempts = 1)
        val coordinator = TranslationSessionCoordinator(service, this, elapsedRealtimeMillis = { 1_000L })
        var actual: TranslationSessionGrant? = null
        coordinator.open(onGranted = { actual = it }, onFailure = { throw AssertionError(it) })
        withTimeout(1_000L) { while (actual == null) kotlinx.coroutines.yield() }

        coordinator.end(actual?.sessionId)
        withTimeout(1_000L) { while (service.failedEndCalls != 1) kotlinx.coroutines.yield() }
        coordinator.end(actual?.sessionId)
        withTimeout(1_000L) { while (service.ended == null || service.usageCalls != 1) kotlinx.coroutines.yield() }

        assertEquals("session-1", service.ended)
        assertEquals(1, service.usageCalls)
        assertEquals(2, service.endCalls)
    }

    @Test fun cancelledOpenClosesALateGrantWithoutDeliveringIt() = runBlocking {
        val service = FakeService(createDeferred = CompletableDeferred())
        val coordinator = TranslationSessionCoordinator(service, this, elapsedRealtimeMillis = { 1_000L })
        var granted = false

        val opening = coordinator.open(onGranted = { granted = true }, onFailure = { throw AssertionError(it) })
        opening.cancel()
        service.createDeferred?.complete(TranslationSessionGrant("session-late", "user-1", "install-1", "token"))
        withTimeout(1_000L) { while (service.ended == null || service.usageCalls != 1) kotlinx.coroutines.yield() }

        assertEquals("session-late", service.ended)
        assertEquals(1, service.usageCalls)
        assertEquals(1, service.endCalls)
        assertEquals(false, granted)
    }

    @Test fun usageFailureDoesNotBlockSessionTermination() = runBlocking {
        val service = FakeService(failUsage = true)
        val coordinator = TranslationSessionCoordinator(service, this, elapsedRealtimeMillis = { 1_000L })
        var actual: TranslationSessionGrant? = null
        coordinator.open(onGranted = { actual = it }, onFailure = { throw AssertionError(it) })
        while (actual == null) kotlinx.coroutines.yield()

        coordinator.end(actual?.sessionId)
        while (service.ended == null || service.usageCalls == 0) kotlinx.coroutines.yield()

        assertEquals("session-1", service.ended)
        assertEquals(1, service.usageCalls)
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    @Test fun usageAndEndRunOnTheInjectedIoDispatcher() = runBlocking {
        val ioDispatcher: ExecutorCoroutineDispatcher = newSingleThreadContext("cloud-session-io")
        ioDispatcher.use {
            val service = FakeService()
            val coordinator = TranslationSessionCoordinator(
                cloud = service,
                scope = this,
                elapsedRealtimeMillis = { 1_000L },
                ioDispatcher = it,
            )
            var actual: TranslationSessionGrant? = null
            coordinator.open(onGranted = { actual = it }, onFailure = { throw AssertionError(it) })
            withTimeout(1_000L) { while (actual == null) kotlinx.coroutines.yield() }

            coordinator.end(actual?.sessionId)
            withTimeout(1_000L) { while (service.operationThreads.size != 2) kotlinx.coroutines.yield() }

            assertEquals(2, service.operationThreads.size)
            assertTrue(service.operationThreads.all { it.startsWith("cloud-session-io") })
        }
    }

    @Test fun endAutomaticallyRetriesAndRemovesItsStateOnlyAfterSuccess() = runBlocking {
        val service = FakeService(failEndAttempts = 1)
        val coordinator = TranslationSessionCoordinator(
            cloud = service,
            scope = this,
            elapsedRealtimeMillis = { 1_000L },
            maxEndAttempts = 2,
            retryDelayMillis = { 0L },
        )
        var actual: TranslationSessionGrant? = null
        coordinator.open(onGranted = { actual = it }, onFailure = { throw AssertionError(it) })
        withTimeout(1_000L) { while (actual == null) kotlinx.coroutines.yield() }

        coordinator.end(actual?.sessionId)
        withTimeout(1_000L) { while (service.ended == null) kotlinx.coroutines.yield() }
        coordinator.end(actual?.sessionId)

        assertEquals("session-1", service.ended)
        assertEquals(1, service.usageCalls)
        assertEquals(2, service.endCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun successfulEndRetryDoesNotPublishTerminalFailure() = runTest {
        val service = FakeService(failEndAttempts = 1)
        val coordinator = TranslationSessionCoordinator(
            cloud = service,
            scope = this,
            elapsedRealtimeMillis = { 1_000L },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            maxEndAttempts = 2,
            retryDelayMillis = { 0L },
        )
        val terminalFailure = MutableStateFlow<CloudSessionFailureCode?>(null)
        val observer = collectTerminalCloudSessionFailures(
            coordinator.endFailures,
            backgroundScope,
        ) { terminalFailure.value = it }
        runCurrent()
        var actual: TranslationSessionGrant? = null
        coordinator.open(onGranted = { actual = it }, onFailure = { throw AssertionError(it) })
        advanceUntilIdle()

        coordinator.end(actual?.sessionId)
        advanceUntilIdle()

        assertEquals(2, service.endCalls)
        assertNull(terminalFailure.value)
        observer.cancel()
    }

    @Test fun endReportsBoundedRetryFailureWithoutRepeatingUsage() = runBlocking {
        val service = FakeService(failEndAttempts = 2)
        val coordinator = TranslationSessionCoordinator(
            cloud = service,
            scope = this,
            elapsedRealtimeMillis = { 1_000L },
            maxEndAttempts = 2,
            retryDelayMillis = { 0L },
        )
        var actual: TranslationSessionGrant? = null
        coordinator.open(onGranted = { actual = it }, onFailure = { throw AssertionError(it) })
        withTimeout(1_000L) { while (actual == null) kotlinx.coroutines.yield() }

        coordinator.end(actual?.sessionId)
        withTimeout(1_000L) {
            while (coordinator.endFailures.replayCache.singleOrNull()?.willRetry != false) kotlinx.coroutines.yield()
        }

        assertEquals(2, service.endCalls)
        assertEquals(1, service.usageCalls)
        assertEquals(1, coordinator.endFailures.replayCache.size)
        assertEquals(2, coordinator.endFailures.replayCache.single().attempts)
        assertEquals(false, coordinator.endFailures.replayCache.single().willRetry)
    }

    private class FakeClock(initial: Long) {
        var value = initial
        fun now(): Long = value
    }

    private class FakeService(
        private val failUsage: Boolean = false,
        private var failEndAttempts: Int = 0,
        val createDeferred: CompletableDeferred<TranslationSessionGrant>? = null,
    ) : CloudTranslationSessionService {
        var usage: UsageRecordPayload? = null
        var ended: String? = null
        var usageCalls = 0
        var endCalls = 0
        var failedEndCalls = 0
        private val mutableOperationThreads = mutableListOf<String>()
        val operationThreads: List<String>
            get() = synchronized(mutableOperationThreads) { mutableOperationThreads.toList() }

        override fun createTranslationSession() = createDeferred?.let { runBlocking { it.await() } }
            ?: TranslationSessionGrant("session-1", "user-1", "install-1", "token")

        override fun createUsageRecord(payload: UsageRecordPayload) {
            synchronized(mutableOperationThreads) { mutableOperationThreads += Thread.currentThread().name }
            usageCalls += 1
            if (failUsage) throw IllegalStateException("offline")
            usage = payload
        }

        override fun endTranslationSession(sessionId: String) {
            synchronized(mutableOperationThreads) { mutableOperationThreads += Thread.currentThread().name }
            endCalls += 1
            if (failEndAttempts > 0) {
                failEndAttempts -= 1
                failedEndCalls += 1
                throw IllegalStateException("offline")
            }
            ended = sessionId
        }
    }
}

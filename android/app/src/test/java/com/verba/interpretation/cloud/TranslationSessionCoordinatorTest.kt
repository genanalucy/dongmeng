package com.verba.interpretation.cloud

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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

        override fun createTranslationSession() = createDeferred?.let { runBlocking { it.await() } }
            ?: TranslationSessionGrant("session-1", "user-1", "install-1", "token")

        override fun createUsageRecord(payload: UsageRecordPayload) {
            usageCalls += 1
            if (failUsage) throw IllegalStateException("offline")
            usage = payload
        }

        override fun endTranslationSession(sessionId: String) {
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

package com.verba.interpretation.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationSessionCoordinatorTest {
    @Test fun createsAndEndsTheCloudGrant() = runBlocking {
        val service = FakeService()
        val coordinator = TranslationSessionCoordinator(service, this)
        var actual: TranslationSessionGrant? = null
        coordinator.open(onGranted = { actual = it }, onFailure = { throw AssertionError(it) })
        while (actual == null) kotlinx.coroutines.yield()

        coordinator.end(actual?.sessionId)
        while (service.ended == null) kotlinx.coroutines.yield()

        assertEquals("session-1", actual?.sessionId)
        assertEquals("session-1", service.ended)
    }

    private class FakeService : CloudTranslationSessionService {
        var ended: String? = null
        override fun createTranslationSession() = TranslationSessionGrant("session-1", "user-1", "install-1", "token")
        override fun endTranslationSession(sessionId: String) { ended = sessionId }
    }
}

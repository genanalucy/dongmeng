package com.verba.interpretation.ui

import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.ui.account.AccountSummaryMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountUiStateTest {
    @Test fun unauthenticatedAccountShowsOnlyAuthenticationExperience() {
        assertEquals(ProductNavigationMode.AUTHENTICATION, AccountUiState().navigationMode)
    }

    @Test fun standardUserAlwaysGetsUserExperience() {
        val state = AccountUiState(user = CloudUser("user-1", "user@example.com", CloudRole.USER))

        assertEquals(ProductNavigationMode.USER, state.navigationMode)
    }

    @Test fun adminCanSwitchBetweenTestAndUserPreviewExperiences() {
        val admin = CloudUser("admin-1", "admin@example.com", CloudRole.ADMIN)

        assertEquals(ProductNavigationMode.ADMIN_TEST, AccountUiState(user = admin).navigationMode)
        assertEquals(ProductNavigationMode.USER, AccountUiState(user = admin, previewingUserExperience = true).navigationMode)
    }

    @Test fun accountSummaryDoesNotContainSensitiveTransportTerms() {
        val summary = AccountSummaryMapper.map(
            AccountUiState(
                user = CloudUser("user-1", "person@example.com", CloudRole.USER),
                entitlement = CloudEntitlement("trial", "2026-09-01"),
                message = "token=secret dsn://backend key=password session=raw",
            ),
        )

        assertEquals("已登录", summary.title)
        listOf("token", "dsn", "key", "password", "session", "secret", "person@example.com").forEach { term ->
            assertFalse("摘要不应包含敏感词：$term", summary.message.contains(term, ignoreCase = true))
            assertFalse("摘要不应包含敏感词：$term", summary.detail.contains(term, ignoreCase = true))
        }
    }
}

package com.verba.interpretation.ui

import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.ui.account.AccountAction
import com.verba.interpretation.ui.account.AccountActionDispatcher
import com.verba.interpretation.ui.account.AccountCallbacks
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

    @Test fun accountActionDispatcherRoutesEverySecondaryActionAndLogout() {
        val calls = mutableListOf<String>()
        val callbacks = AccountCallbacks(
            onBack = { calls += "back" },
            onHistory = { calls += "history" },
            onServiceSettings = { calls += "settings" },
            onHelp = { calls += "help" },
            onLogout = { calls += "logout" },
        )

        AccountActionDispatcher.dispatch(AccountAction.HISTORY, callbacks)
        AccountActionDispatcher.dispatch(AccountAction.SERVICE_SETTINGS, callbacks)
        AccountActionDispatcher.dispatch(AccountAction.HELP, callbacks)
        AccountActionDispatcher.dispatch(AccountAction.LOGOUT, callbacks)
        AccountActionDispatcher.back(callbacks)

        assertEquals(listOf("history", "settings", "help", "logout", "back"), calls)
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
        assertEquals("正式用户", summary.role)
        listOf("token", "dsn", "key", "password", "session", "secret", "person@example.com", "email", "http", "backend").forEach { term ->
            summary.renderedText.forEach { field ->
                assertFalse("摘要不应包含敏感词：$term", field.contains(term, ignoreCase = true))
            }
        }
    }

    @Test fun accountSummaryMapsUntrustedExpiryToSafeFixedCopy() {
        val summary = AccountSummaryMapper.map(
            AccountUiState(
                user = CloudUser("admin-1", "admin@example.com", CloudRole.ADMIN),
                entitlement = CloudEntitlement("trial", "https://endpoint.example/token=secret"),
            ),
        )

        assertEquals("管理员", summary.role)
        assertEquals("试用权益已启用。", summary.detail)
        assertFalse(summary.renderedText.any { it.contains("endpoint", ignoreCase = true) })
    }
}

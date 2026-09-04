package com.verba.interpretation.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.UsageSummary
import com.verba.interpretation.ui.AccountUiState
import org.junit.Rule
import org.junit.Test

/** Release builds must not expose the endpoint-editing entry from the account screen. */
class AccountScreenServiceSettingsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun releaseAccountScreenHidesServiceSettingsEntry() {
        composeRule.setContent {
            MaterialTheme {
                AccountScreen(
                    state = signedInState(),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                    showServiceSettings = false,
                )
            }
        }

        composeRule.onNodeWithText("服务设置").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("服务设置").assertDoesNotExist()
        composeRule.onNodeWithText("账户设置").assertExists()
    }

    @Test fun debugAccountScreenKeepsServiceSettingsEntry() {
        composeRule.setContent {
            MaterialTheme {
                AccountScreen(
                    state = signedInState(),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                    showServiceSettings = true,
                )
            }
        }

        composeRule.onNodeWithText("服务设置").assertExists()
        composeRule.onNodeWithContentDescription("服务设置").assertExists()
    }

    private fun signedInState() = AccountUiState(
        user = CloudUser("user-1", "alice_01", CloudRole.USER, "alice@example.test"),
        overview = AccountOverview("alice_01", null, UsageSummary(3600, 2, "2026-09-02T12:00:00Z")),
    )
}

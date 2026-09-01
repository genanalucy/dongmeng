package com.verba.interpretation.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.UsageSummary
import com.verba.interpretation.ui.AccountUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountCenterScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun myPageShowsUsernameAndNeverExposesEmailOrPhone() {
        composeRule.setContent {
            MaterialTheme {
                AccountScreen(
                    state = signedInState(),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                )
            }
        }

        composeRule.onNodeWithText("alice_01").assertExists()
        composeRule.onNodeWithText("账户与权益").assertExists()
        composeRule.onNodeWithText("alice@example.test", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("13800138000", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("使用与权益").assertExists()
        composeRule.onNodeWithContentDescription("账户设置").assertExists()
    }

    @Test fun identitySettingsDisplaysLoadedEmailAndMaskedPhone() {
        composeRule.setContent {
            MaterialTheme {
                AccountIdentitySettingsScreen(
                    username = "alice_01", email = "alice@example.test", maskedPhone = "138****8000", loading = false, message = null,
                    onBack = {}, onSubmit = { _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("identity-email").assertTextEquals("alice@example.test")
        composeRule.onNodeWithTag("identity-phone").assertTextEquals("138****8000")
    }

    @Test fun identitySettingsRequiresFullPhoneAndDoesNotDispatchInvalidForm() {
        var calls = 0
        composeRule.setContent {
            MaterialTheme {
                AccountIdentitySettingsScreen(
                    username = "alice_01", email = "alice@example.test", maskedPhone = "138****8000", loading = false, message = null,
                    onBack = {}, onSubmit = { _, _, _, _ -> calls++ },
                )
            }
        }

        composeRule.onNodeWithTag("identity-current-password").performTextInput("Aa123456")
        composeRule.onNodeWithText("保存账户设置").performClick()
        assertEquals(0, calls)
        composeRule.onNodeWithText("请输入有效的中国大陆手机号。", substring = true).assertExists()
    }

    private fun signedInState() = AccountUiState(
        user = CloudUser("user-1", "alice_01", CloudRole.USER, "alice@example.test"),
        overview = AccountOverview("alice_01", null, UsageSummary(3600, 2, "2026-09-02T12:00:00Z")),
    )
}

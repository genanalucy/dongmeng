package com.verba.interpretation.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.verba.interpretation.ui.RegistrationUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AuthenticationFormTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun registrationDetailsShowOnlyUsernameEmailAndPasswordFields() {
        setForm()
        composeRule.onNodeWithText("注册账户").performClick()

        assertEditableTags("username", "email", "password", "confirmation")
        composeRule.onAllNodesWithText("手机号", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("发送验证码").assertExists()
    }

    @Test fun registrationDetailsDispatchEmailOnlyAndClearCredentials() {
        var submitted: List<String>? = null
        setForm(onRequestVerification = { username, email, password -> submitted = listOf(username, email, password) })

        composeRule.onNodeWithText("注册账户").performClick()
        composeRule.onNodeWithTag("username").performTextInput("alice_01")
        composeRule.onNodeWithTag("email").performTextInput("Alice@Example.COM")
        composeRule.onNodeWithTag("password").performTextInput("Passw0rd")
        composeRule.onNodeWithTag("confirmation").performTextInput("Passw0rd")
        composeRule.onNodeWithText("发送验证码").performClick()

        assertEquals(listOf("alice_01", "alice@example.com", "Passw0rd"), submitted)
    }

    @Test fun challengeShowsMaskedEmailSixDigitCodeAndDisabledResend() {
        setForm(registration = RegistrationUiState.Challenge("alice_01", "alice@example.com", "a***e@example.com", System.currentTimeMillis() + 60_000L))

        assertEditableTags("verification-code")
        composeRule.onNodeWithText("a***e@example.com").assertExists()
        composeRule.onNodeWithText("确认注册").assertExists()
        composeRule.onNodeWithText("60 秒后可重新发送").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        composeRule.onAllNodesWithText("alice@example.com", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun challengeEnablesResendAtCooldownExpiryAndDispatchesOnce() {
        var nowMillis by mutableLongStateOf(1_000L)
        var resendCalls = 0
        setForm(
            registration = RegistrationUiState.Challenge("alice_01", "alice@example.com", "a***e@example.com", 61_000L),
            onResend = { _, _, _ -> resendCalls++ },
            clockMillis = { nowMillis },
            tickerMillis = 1L,
        )

        composeRule.onNodeWithText("60 秒后可重新发送").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        nowMillis = 60_000L
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1 秒后可重新发送").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        nowMillis = 61_000L
        composeRule.waitForIdle()
        composeRule.onNodeWithText("重新发送验证码").performClick()

        assertEquals(1, resendCalls)
    }

    @Test fun challengeAcceptsSixDigitCodeAndCanReturnToDetails() {
        var confirmed: Pair<String, String>? = null
        var editCalls = 0
        setForm(
            registration = RegistrationUiState.Challenge("alice_01", "alice@example.com", "a***e@example.com", 0L),
            onConfirmVerification = { email, code -> confirmed = email to code },
            onEditDetails = { editCalls++ },
        )

        composeRule.onNodeWithTag("verification-code").performTextInput("012345")
        composeRule.onNodeWithText("确认注册").performClick()
        composeRule.onNodeWithText("返回编辑资料").performClick()

        assertEquals("a***e@example.com" to "012345", confirmed)
        assertEquals(1, editCalls)
    }

    private fun setForm(
        registration: RegistrationUiState = RegistrationUiState.Details,
        onRequestVerification: (String, String, String) -> Unit = { _, _, _ -> },
        onConfirmVerification: (String, String) -> Unit = { _, _ -> },
        onEditDetails: () -> Unit = {},
        onResend: (String, String, String) -> Unit = { _, _, _ -> },
        clockMillis: () -> Long = System::currentTimeMillis,
        tickerMillis: Long = 1_000L,
    ) {
        composeRule.setContent {
            MaterialTheme {
                AuthenticationForm(
                    loading = false,
                    registration = registration,
                    onLogin = { _, _ -> },
                    onRequestVerification = onRequestVerification,
                    onConfirmVerification = onConfirmVerification,
                    onEditDetails = onEditDetails,
                    onResend = onResend,
                    clockMillis = clockMillis,
                    tickerMillis = tickerMillis,
                )
            }
        }
    }

    private fun assertEditableTags(vararg expected: String) {
        expected.forEach { tag -> composeRule.onNodeWithTag(tag).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText)) }
    }
}

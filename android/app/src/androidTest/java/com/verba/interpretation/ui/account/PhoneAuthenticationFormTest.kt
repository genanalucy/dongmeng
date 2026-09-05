package com.verba.interpretation.ui.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.verba.interpretation.ui.RegistrationUiState
import org.junit.Rule
import org.junit.Test

class PhoneAuthenticationFormTest {
    @get:Rule val compose = createComposeRule()

    @Test fun registrationDetailsContainsNoEmailVerificationOrResendControls() {
        compose.setContent { AuthenticationForm(false, RegistrationUiState.Details, { _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}, {}) }
        compose.onNodeWithText("完成拼图验证").assertExists()
        compose.onNodeWithText("发送验证码").assertDoesNotExist()
        compose.onNodeWithText("重新发送验证码").assertDoesNotExist()
    }

    @Test fun slideCaptchaShowsNativeBoardAndRefreshAction() {
        val captcha = RegistrationUiState.SlideCaptcha("captcha", 6, 300, 220, "", "", 40, 40, 0, 20, Long.MAX_VALUE)
        compose.setContent { AuthenticationForm(false, captcha, { _, _ -> }, { _, _, _ -> }, { _, _, _, _ -> }, {}, {}) }
        compose.onNodeWithText("完成拼图验证").assertIsDisplayed()
        compose.onNodeWithTag("captcha-refresh").assertExists()
    }
}

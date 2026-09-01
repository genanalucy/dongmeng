package com.verba.interpretation.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PhoneAuthenticationFormTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun loginShowsOnlyPhoneAndPasswordWithAccessibleErrors() {
        composeRule.setContent {
            MaterialTheme { PhoneAuthenticationForm(loading = false, onLogin = { _, _ -> }, onRegister = { _, _, _ -> }) }
        }

        composeRule.onNodeWithTag("phone").assertExists().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeRule.onNodeWithTag("password").assertExists().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeRule.onNodeWithTag("username").assertDoesNotExist()
        composeRule.onNodeWithTag("confirmation").assertDoesNotExist()
        composeRule.onAllNodesWithText("邮箱").assertCountEquals(0)
        composeRule.onAllNodesWithText("验证码").assertCountEquals(0)
        composeRule.onNodeWithText("请输入有效的中国大陆手机号。").assertExists()
        composeRule.onNodeWithText("请输入密码。").assertExists()
    }

    @Test fun registrationShowsExactlyFourPhoneAuthenticationFields() {
        composeRule.setContent {
            MaterialTheme { PhoneAuthenticationForm(loading = false, onLogin = { _, _ -> }, onRegister = { _, _, _ -> }) }
        }

        composeRule.onNodeWithText("注册账户").performClick()

        composeRule.onNodeWithTag("username").assertExists()
        composeRule.onNodeWithTag("phone").assertExists()
        composeRule.onNodeWithTag("password").assertExists()
        composeRule.onNodeWithTag("confirmation").assertExists()
        composeRule.onAllNodesWithText("邮箱").assertCountEquals(0)
        composeRule.onAllNodesWithText("验证码").assertCountEquals(0)
    }

    @Test fun loginButtonDispatchesNormalizedPhoneAndPassword() {
        var submitted: Pair<String, String>? = null
        composeRule.setContent {
            MaterialTheme { PhoneAuthenticationForm(loading = false, onLogin = { phone, password -> submitted = phone to password }, onRegister = { _, _, _ -> }) }
        }

        composeRule.onNodeWithTag("phone").performTextInput("13800138000")
        composeRule.onNodeWithTag("password").performTextInput("legacy")
        composeRule.onNodeWithText("登录").performClick()

        assertEquals("+8613800138000" to "legacy", submitted)
    }

    @Test fun registrationButtonDispatchesOnlyUsernamePhoneAndPassword() {
        var submitted: Triple<String, String, String>? = null
        composeRule.setContent {
            MaterialTheme { PhoneAuthenticationForm(loading = false, onLogin = { _, _ -> }, onRegister = { username, phone, password -> submitted = Triple(username, phone, password) }) }
        }

        composeRule.onNodeWithText("注册账户").performClick()
        composeRule.onNodeWithTag("username").performTextInput("alice_01")
        composeRule.onNodeWithTag("phone").performTextInput("13800138000")
        composeRule.onNodeWithTag("password").performTextInput("Passw0rd")
        composeRule.onNodeWithTag("confirmation").performTextInput("Passw0rd")
        composeRule.onNodeWithText("注册").performClick()

        assertEquals(Triple("alice_01", "+8613800138000", "Passw0rd"), submitted)
    }
}

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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PhoneAuthenticationFormTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun loginHasExactlyPhoneAndPasswordFieldsWithAccurateErrors() {
        setForm()

        assertEditableTags("phone", "password")
        assertForbiddenAuthenticationTermsAbsent()
        assertError("phone", "请输入有效的中国大陆手机号。")
        assertError("password", "请输入密码。")
        composeRule.onNodeWithText("请输入有效的中国大陆手机号。", substring = true).assertExists()
        composeRule.onNodeWithText("请输入密码。", substring = true).assertExists()
    }

    @Test fun registrationHasExactlyFourFieldsWithAccurateErrors() {
        setForm()
        composeRule.onNodeWithText("注册账户").performClick()

        assertEditableTags("username", "phone", "password", "confirmation")
        assertForbiddenAuthenticationTermsAbsent()
        assertError("username", "用户名需要 3 至 32 个字符，仅支持字母、数字和下划线。")
        assertError("phone", "请输入有效的中国大陆手机号。")
        assertError("password", "密码至少需要 8 个字符。")
        assertError("confirmation", "两次输入的密码不一致。")
        listOf(
            "用户名需要 3 至 32 个字符，仅支持字母、数字和下划线。",
            "请输入有效的中国大陆手机号。",
            "密码至少需要 8 个字符。",
            "两次输入的密码不一致。",
        ).forEach { composeRule.onNodeWithText(it, substring = true).assertExists() }
    }

    @Test fun loginButtonDispatchesNormalizedPhoneAndPassword() {
        var submitted: Pair<String, String>? = null
        setForm(onLogin = { phone, password -> submitted = phone to password })

        composeRule.onNodeWithTag("phone").performTextInput("13800138000")
        composeRule.onNodeWithTag("password").performTextInput("legacy")
        composeRule.onNodeWithText("登录").performClick()

        assertEquals("+8613800138000" to "legacy", submitted)
    }

    @Test fun registrationButtonDispatchesOnlyUsernamePhoneAndPassword() {
        var submitted: Triple<String, String, String>? = null
        setForm(onRegister = { username, phone, password -> submitted = Triple(username, phone, password) })

        composeRule.onNodeWithText("注册账户").performClick()
        composeRule.onNodeWithTag("username").performTextInput("alice_01")
        composeRule.onNodeWithTag("phone").performTextInput("13800138000")
        composeRule.onNodeWithTag("password").performTextInput("Passw0rd")
        composeRule.onNodeWithTag("confirmation").performTextInput("Passw0rd")
        composeRule.onNodeWithText("注册").performClick()

        assertEquals(Triple("alice_01", "+8613800138000", "Passw0rd"), submitted)
    }

    private fun setForm(
        onLogin: (String, String) -> Unit = { _, _ -> },
        onRegister: (String, String, String) -> Unit = { _, _, _ -> },
    ) {
        composeRule.setContent { MaterialTheme { PhoneAuthenticationForm(false, onLogin, onRegister) } }
    }

    private fun assertEditableTags(vararg expected: String) {
        val editable = composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        editable.assertCountEquals(expected.size)
        expected.forEach { composeRule.onNodeWithTag(it).assertExists() }
    }

    private fun assertForbiddenAuthenticationTermsAbsent() {
        listOf("邮箱", "验证码").forEach { term ->
            composeRule.onAllNodesWithText(term, substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
    }

    private fun assertError(tag: String, expected: String) {
        composeRule.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, expected))
    }
}

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

/**
 * 首页固定四个纵向入口，不再展示服务设置；
 * 服务设置入口由账户二级页提供并保持 debug 限制。
 */
class AccountScreenServiceSettingsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun accountScreenShowsExactlyFourEntriesWithoutServiceSettings() {
        composeRule.setContent {
            MaterialTheme {
                AccountScreen(
                    state = signedInState(),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                )
            }
        }

        listOf("历史记录", "权益详情", "账户设置", "安全与登录").forEach { entry ->
            composeRule.onNodeWithText(entry).assertExists()
            composeRule.onNodeWithContentDescription(entry).assertExists()
        }
        composeRule.onNodeWithText("服务设置").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("服务设置").assertDoesNotExist()
    }

    /** 旧调用兼容参数不再让首页出现第五个入口，无论 debug 或 release 取值。 */
    @Test fun legacyShowServiceSettingsFlagNoLongerAddsHomeEntry() {
        composeRule.setContent {
            MaterialTheme {
                AccountScreen(
                    state = signedInState(),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                    showServiceSettings = true,
                )
            }
        }

        composeRule.onNodeWithText("服务设置").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("服务设置").assertDoesNotExist()
        composeRule.onNodeWithText("账户设置").assertExists()
    }

    private fun signedInState() = AccountUiState(
        user = CloudUser("user-1", "alice_01", CloudRole.USER, "alice@example.test"),
        overview = AccountOverview("alice_01", null, UsageSummary(3600, 2, "2026-09-02T12:00:00Z")),
    )
}

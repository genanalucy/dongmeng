package com.verba.interpretation.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.verba.interpretation.brand.BrandConfig
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.UsageSummary
import com.verba.interpretation.ui.AccountUiState
import com.verba.interpretation.ui.RedeemUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountCenterScreenTest {
    @get:Rule val compose = createComposeRule()

    // --- 首页：身份与结构 ---

    @Test fun accountScreenShowsBrandIdentityWithoutEmailEditingOrRedeemInput() {
        compose.setContent { MaterialTheme { AccountScreen(signedInState(), {}, {}, {}, {}, {}, {}) } }

        compose.onNodeWithText("alice_01").assertExists()
        compose.onNodeWithText("正式用户").assertExists()
        compose.onNodeWithContentDescription("${BrandConfig.shortName} 品牌标志").assertExists()
        compose.onNodeWithText("alice@example.test", substring = true).assertDoesNotExist()
        compose.onNodeWithText("修改用户名", substring = true).assertDoesNotExist()
        // 首页不常驻兑换输入、不放退出登录。
        compose.onNodeWithContentDescription("兑换码输入框").assertDoesNotExist()
        compose.onNodeWithText("退出登录").assertDoesNotExist()
    }

    @Test fun accountScreenShowsFourVerticalEntriesWithSecurityCallback() {
        var securityOpened = false
        var historyOpened = false
        compose.setContent {
            MaterialTheme {
                AccountScreen(
                    signedInState(),
                    onBack = {},
                    onUsage = {},
                    onHistory = { historyOpened = true },
                    onSettings = {},
                    onServiceSettings = {},
                    onLogout = {},
                    onSecurity = { securityOpened = true },
                )
            }
        }

        listOf("历史记录", "权益详情", "账户设置", "安全与登录").forEach { entry ->
            compose.onNodeWithText(entry).assertExists()
            compose.onNodeWithContentDescription(entry).assertExists()
        }
        compose.onNodeWithContentDescription("安全与登录").performClick()
        compose.onNodeWithContentDescription("历史记录").performClick()
        compose.runOnIdle {
            assertTrue(securityOpened)
            assertTrue(historyOpened)
        }
    }

    // --- 首页：权益大卡状态 ---

    @Test fun accountScreenActiveEntitlementCardShowsDaysAndOpensDetails() {
        val future = "2030-09-02T12:00:00Z"
        var usageOpened = false
        compose.setContent {
            MaterialTheme {
                AccountScreen(
                    signedInState(entitlement = CloudEntitlement("subscription", future, active = true, remainingSeconds = 27L * 86_400)),
                    onBack = {},
                    onUsage = { usageOpened = true },
                    onHistory = {},
                    onSettings = {},
                    onServiceSettings = {},
                    onLogout = {},
                )
            }
        }

        compose.onNodeWithText("订阅").assertExists()
        compose.onNodeWithText("有效").assertExists()
        compose.onNodeWithText("27").assertExists()
        compose.onNodeWithText(" 天剩余", substring = true).assertExists()
        compose.onNodeWithText(expectedExpiryText(future), substring = true).assertExists()
        // 整卡可点进入权益详情。
        compose.onNodeWithText("27").performClick()
        compose.runOnIdle { assertTrue(usageOpened) }
    }

    @Test fun accountScreenExpiringWithinDayShowsUnderOneDay() {
        compose.setContent {
            MaterialTheme {
                AccountScreen(
                    signedInState(entitlement = CloudEntitlement("trial", "2030-09-02T12:00:00Z", active = true, remainingSeconds = 3_600)),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                )
            }
        }

        compose.onNodeWithText("不足 1 天").assertExists()
        compose.onNodeWithText("天剩余", substring = true).assertDoesNotExist()
    }

    @Test fun accountScreenExpiredEntitlementIsNotShownAsEmpty() {
        var navigated = false
        compose.setContent {
            MaterialTheme {
                AccountScreen(
                    signedInState(entitlement = CloudEntitlement("trial", "2020-01-01T00:00:00Z", active = false)),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                    onRedeemNavigate = { navigated = true },
                )
            }
        }

        compose.onNodeWithText("已过期").assertExists()
        compose.onNodeWithText("暂无有效权益").assertDoesNotExist()
        compose.onNodeWithText("天剩余", substring = true).assertDoesNotExist()
        // 已知过期与空权益同样提供清晰的兑换导航按钮。
        compose.onNodeWithContentDescription("兑换权益码").assertExists()
        compose.onNodeWithContentDescription("兑换权益码").performClick()
        compose.runOnIdle { assertTrue(navigated) }
    }

    @Test fun accountScreenWithoutEntitlementShowsRedeemNavigation() {
        var navigated = false
        compose.setContent {
            MaterialTheme {
                AccountScreen(
                    signedInState(),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                    onRedeemNavigate = { navigated = true },
                )
            }
        }

        compose.onNodeWithText("暂无有效权益").assertExists()
        compose.onNodeWithContentDescription("兑换权益码").performClick()
        compose.runOnIdle { assertTrue(navigated) }
    }

    @Test fun accountScreenLoadingEntitlementShowsSkeletonInsteadOfEmptyCard() {
        compose.setContent {
            MaterialTheme {
                AccountScreen(signedInState(loading = true), {}, {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithContentDescription("正在加载权益信息").assertExists()
        compose.onNodeWithText("暂无有效权益").assertDoesNotExist()
    }

    @Test fun accountScreenFailedEntitlementShowsRetryInsteadOfEmptyCard() {
        var retried = false
        compose.setContent {
            MaterialTheme {
                AccountScreen(
                    signedInState(message = "账户状态暂时无法更新，请稍后重试。"),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                    onRetry = { retried = true },
                )
            }
        }

        compose.onNodeWithText("权益状态暂不可确认").assertExists()
        compose.onNodeWithText("暂无有效权益").assertDoesNotExist()
        compose.onNodeWithContentDescription("重试加载权益").performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test fun accountScreenFailedWithStaleEntitlementDoesNotShowStaleActiveState() {
        var retried = false
        compose.setContent {
            MaterialTheme {
                AccountScreen(
                    staleEntitlementFailureState(),
                    onBack = {}, onUsage = {}, onHistory = {}, onSettings = {}, onServiceSettings = {}, onLogout = {},
                    onRetry = { retried = true },
                )
            }
        }

        // 失败优先于陈旧数据：状态未知时不用旧 entitlement 冒充最新有效状态。
        compose.onNodeWithText("权益状态暂不可确认").assertExists()
        compose.onNodeWithText("有效").assertDoesNotExist()
        compose.onNodeWithText("天剩余", substring = true).assertDoesNotExist()
        compose.onNodeWithText("不足 1 天").assertDoesNotExist()
        compose.onNodeWithText("暂无有效权益").assertDoesNotExist()
        compose.onNodeWithContentDescription("重试加载权益").performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    // --- 权益详情页 ---

    @Test fun entitlementScreenShowsUsageAndOpensRedeemPage() {
        var navigated = false
        compose.setContent {
            MaterialTheme {
                AccountEntitlementScreen(signedInState(), {}, { navigated = true }, {})
            }
        }

        compose.onNodeWithText("权益详情").assertExists()
        compose.onNodeWithText("使用情况").assertExists()
        compose.onNodeWithText("1 小时 0 分", substring = true).assertExists()
        compose.onNodeWithContentDescription("兑换权益码").performClick()
        compose.runOnIdle { assertTrue(navigated) }
    }

    @Test fun entitlementScreenFailedWithStaleEntitlementDoesNotShowStaleActiveState() {
        var retried = false
        compose.setContent {
            MaterialTheme {
                AccountEntitlementScreen(
                    staleEntitlementFailureState(),
                    onBack = {},
                    onRedeemNavigate = {},
                    onRetry = { retried = true },
                )
            }
        }

        compose.onNodeWithText("权益状态暂不可确认").assertExists()
        compose.onNodeWithText("有效").assertDoesNotExist()
        compose.onNodeWithText("天剩余", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("重试加载权益").performClick()
        compose.runOnIdle { assertTrue(retried) }
    }

    @Test fun entitlementScreenEmptyEntitlementKeepsSingleRedeemCta() {
        compose.setContent {
            MaterialTheme { AccountEntitlementScreen(signedInState(), {}, {}, {}) }
        }

        compose.onNodeWithText("暂无有效权益").assertExists()
        // 卡内不放兑换按钮，整页只有一个兑换主 CTA（底部按钮）。
        assertEquals(
            1,
            compose.onAllNodesWithContentDescription("兑换权益码").fetchSemanticsNodes().size,
        )
    }

    @Test fun entitlementScreenExpiredEntitlementKeepsSingleRedeemCta() {
        compose.setContent {
            MaterialTheme {
                AccountEntitlementScreen(
                    signedInState(entitlement = CloudEntitlement("trial", "2020-01-01T00:00:00Z", active = false)),
                    {}, {}, {},
                )
            }
        }

        compose.onNodeWithText("已过期").assertExists()
        // 过期权益也不在卡内重复放兑换按钮，整页仍只有一个兑换主 CTA。
        assertEquals(
            1,
            compose.onAllNodesWithContentDescription("兑换权益码").fetchSemanticsNodes().size,
        )
    }

    // --- 兑换独立页 ---

    @Test fun redemptionScreenSubmitsEnteredCode() {
        var submittedCode: String? = null
        compose.setContent {
            MaterialTheme {
                AccountRedemptionScreen(
                    state = signedInState(),
                    onBack = {},
                    onCodeChange = { submittedCode = it },
                    onRedeem = { submittedCode = submittedCode?.trim()?.uppercase() },
                )
            }
        }

        compose.onNodeWithContentDescription("兑换码输入框").performTextInput("aaaaaa-bbbbbb-cccccc-dddddd")
        compose.onNodeWithContentDescription("兑换权益码").performClick()

        assertEquals("AAAAAA-BBBBBB-CCCCCC-DDDDDD", submittedCode)
    }

    @Test fun redemptionAccountMessageOverridesRedeemSuccessFeedback() {
        compose.setContent {
            MaterialTheme {
                AccountRedemptionScreen(
                    state = signedInState().copy(
                        message = "登录已过期，请重新登录。",
                        redeem = RedeemUiState.Success("兑换成功，权益已更新。"),
                    ),
                    onBack = {}, onCodeChange = {}, onRedeem = {},
                )
            }
        }

        compose.onNodeWithContentDescription("账户错误：登录已过期，请重新登录。").assertExists()
        compose.onNodeWithContentDescription("兑换结果：兑换成功，权益已更新。").assertDoesNotExist()
    }

    @Test fun redemptionSuccessFeedbackIsShownWithoutAccountMessage() {
        compose.setContent {
            MaterialTheme {
                AccountRedemptionScreen(
                    state = signedInState().copy(redeem = RedeemUiState.Success("兑换成功，权益已更新。")),
                    onBack = {}, onCodeChange = {}, onRedeem = {},
                )
            }
        }

        compose.onNodeWithContentDescription("兑换结果：兑换成功，权益已更新。").assertExists()
    }

    // --- 既有账户设置页回归 ---

    @Test fun deletionDialogRequiresExactUsername() {
        var submitted: String? = null
        compose.setContent { MaterialTheme { AccountIdentitySettingsScreen("alice_01", false, null, {}, { submitted = it }, false) } }
        compose.onNodeWithTag("delete-account").performClick()
        compose.onNodeWithTag("delete-account-confirmation").performTextInput("alice_01")
        compose.onNodeWithText("永久删除").performClick()
        assertEquals("alice_01", submitted)
    }

    @Test fun adminHasNoSelfDeletionControl() {
        compose.setContent { MaterialTheme { AccountIdentitySettingsScreen("admin", false, null, {}, {}, true) } }
        compose.onNodeWithTag("delete-account").assertDoesNotExist()
    }

    private fun signedInState(
        entitlement: CloudEntitlement? = null,
        loading: Boolean = false,
        message: String? = null,
    ) = AccountUiState(
        user = CloudUser("user-1", "alice_01", CloudRole.USER, "alice@example.test"),
        overview = AccountOverview("alice_01", entitlement, UsageSummary(3600, 2, "2026-09-02T12:00:00Z")),
        loading = loading,
        message = message,
    )

    /**
     * 权益详情加载失败后的真实状态：overview/usage 被清空，仅剩较早的陈旧 entitlement
     * 与账户级失败提示；界面不得把陈旧 entitlement 当作最新有效状态展示。
     */
    private fun staleEntitlementFailureState() = AccountUiState(
        user = CloudUser("user-1", "alice_01", CloudRole.USER, "alice@example.test"),
        entitlement = CloudEntitlement("subscription", "2030-09-02T12:00:00Z", active = true, remainingSeconds = 27L * 86_400),
        message = "账户状态暂时无法更新，请稍后重试。",
    )

    /** 与页面相同的时区/格式，避免仪器环境时区差异导致断言漂移。 */
    private fun expectedExpiryText(value: String): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.parse(value))
}

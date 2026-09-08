package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.cloud.AccountApi
import com.verba.interpretation.cloud.AccountIdentityProfile
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.AuthTokens
import com.verba.interpretation.cloud.CloudApiException
import com.verba.interpretation.cloud.CloudDevice
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.IdentityUpdateRequest
import com.verba.interpretation.cloud.InstallationIdStore
import com.verba.interpretation.cloud.RegistrationResponse
import com.verba.interpretation.cloud.SlideCaptchaChallenge
import com.verba.interpretation.cloud.SlideCaptchaImage
import com.verba.interpretation.cloud.SlideCaptchaTile
import com.verba.interpretation.cloud.TranslationSession
import com.verba.interpretation.cloud.UsagePage
import com.verba.interpretation.cloud.UsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 安全页状态契约（关联设备模型）：
 * - devices == null 表示尚未成功加载（含失败），绝不回退为伪造列表；
 * - devices == 空列表 表示服务端确认当前没有关联设备记录；
 * - 设备标识只以缩略形式出屏，完整标识不进入 UI 状态；
 * - 会话过期、安全退出后设备列表必须复位，不残留到下一个账户。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelSecurityTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadSecurityListsRealDevicesWithMaskedIdsAndCurrentDeviceFlag() {
        val api = SecurityApi().apply {
            devices = listOf(
                CloudDevice(
                    id = "0f0e0d0c-0b0a-4909-8807-060504030201",
                    installId = "11111111-2222-3333-4444-555555555555",
                    lastSeenAt = "2026-09-01T00:00:00Z",
                    createdAt = "2026-08-01T00:00:00Z",
                ),
                CloudDevice(
                    id = "10203040-5060-4708-9010-a0b0c0d0e0f0",
                    installId = "99999999-8888-7777-6666-555555555555",
                    lastSeenAt = "2026-09-02T08:30:00Z",
                    createdAt = "2026-08-02T00:00:00Z",
                ),
            )
        }
        val install = FixedInstallationIdStore("11111111-2222-3333-4444-555555555555")
        val viewModel = AccountViewModel(Application(), api, dispatcher, installationIdStore = install)

        viewModel.loadSecurity()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.securityState.value
        assertFalse(state.loading)
        assertNull(state.message)
        val devices = state.devices.orEmpty()
        assertEquals(2, devices.size)
        assertEquals("11111111…", devices[0].installIdMasked)
        assertEquals("2026-09-01T00:00:00Z", devices[0].lastSeenAt)
        assertTrue(devices[0].isCurrentDevice)
        assertEquals("99999999…", devices[1].installIdMasked)
        assertEquals("2026-09-02T08:30:00Z", devices[1].lastSeenAt)
        assertFalse(devices[1].isCurrentDevice)
    }

    @Test fun loadSecurityNeverExposesFullDeviceIdentifiers() {
        val api = SecurityApi().apply {
            devices = listOf(
                CloudDevice("0f0e0d0c-0b0a-4909-8807-060504030201", "11111111-2222-3333-4444-555555555555", "2026-09-01T00:00:00Z", "2026-08-01T00:00:00Z"),
            )
        }
        val viewModel = AccountViewModel(Application(), api, dispatcher)

        viewModel.loadSecurity()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.securityState.value.devices.orEmpty().forEach { summary ->
            assertTrue(summary.installIdMasked.length <= 9)
            assertFalse(summary.installIdMasked.contains("2222"))
            assertFalse(summary.installIdMasked.contains("5555"))
        }
    }

    @Test fun loadSecurityFailureIsIdentifiableAndKeepsDevicesUnloaded() {
        val api = SecurityApi().apply { failure = CloudApiException("unavailable", 503) }
        val viewModel = AccountViewModel(Application(), api, dispatcher)

        viewModel.loadSecurity()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.securityState.value
        assertFalse(state.loading)
        assertEquals("安全信息暂时无法更新，请稍后重试。", state.message)
        assertNull(state.devices)
    }

    @Test fun loadSecurityRealEmptyDeviceListIsAnEmptyListNotNull() {
        val api = SecurityApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher)

        viewModel.loadSecurity()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(emptyList<SecurityDeviceSummary>(), viewModel.securityState.value.devices)
        assertNull(viewModel.securityState.value.message)
    }

    @Test fun loadSecurityRefreshFailureClearsStaleDevicesInsteadOfShowingThem() {
        val api = SecurityApi().apply {
            devices = listOf(
                CloudDevice("0f0e0d0c-0b0a-4909-8807-060504030201", "11111111-2222-3333-4444-555555555555", "2026-09-01T00:00:00Z", "2026-08-01T00:00:00Z"),
            )
        }
        val viewModel = AccountViewModel(Application(), api, dispatcher)
        viewModel.loadSecurity()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.securityState.value.devices.orEmpty().size)

        api.failure = java.io.IOException("offline")
        viewModel.loadSecurity()
        dispatcher.scheduler.advanceUntilIdle()

        // 刷新失败：旧列表被清空，不用陈旧设备冒充最新。
        assertNull(viewModel.securityState.value.devices)
        assertEquals("安全信息暂时无法更新，请稍后重试。", viewModel.securityState.value.message)
        assertFalse(viewModel.securityState.value.loading)
    }

    @Test fun loadSecuritySessionExpirySignsOutAndClearsStaleDevices() {
        val api = SecurityApi().apply {
            devices = listOf(
                CloudDevice("0f0e0d0c-0b0a-4909-8807-060504030201", "11111111-2222-3333-4444-555555555555", "2026-09-01T00:00:00Z", "2026-08-01T00:00:00Z"),
            )
        }
        val viewModel = AccountViewModel(Application(), api, dispatcher)
        viewModel.loadSecurity()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.securityState.value.devices.orEmpty().size)

        api.failure = CloudApiException("expired", 401, sessionExpired = true)
        viewModel.loadSecurity()
        dispatcher.scheduler.advanceUntilIdle()

        // 会话过期：整体复位，上一账号的设备列表不残留到下一次登录。
        assertNull(viewModel.state.value.user)
        assertFalse(viewModel.state.value.signedIn)
        assertEquals(ProductNavigationMode.AUTHENTICATION, viewModel.state.value.navigationMode)
        assertNull(viewModel.securityState.value.devices)
        assertNull(viewModel.securityState.value.message)
        assertFalse(viewModel.securityState.value.loading)
    }

    @Test fun logoutClearsSecurityStateSoDevicesDoNotLeakToNextAccount() {
        val api = SecurityApi().apply {
            devices = listOf(
                CloudDevice("0f0e0d0c-0b0a-4909-8807-060504030201", "11111111-2222-3333-4444-555555555555", "2026-09-01T00:00:00Z", "2026-08-01T00:00:00Z"),
            )
        }
        val viewModel = AccountViewModel(Application(), api, dispatcher)
        viewModel.loadSecurity()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.securityState.value.devices.orEmpty().size)

        viewModel.logout()
        dispatcher.scheduler.advanceUntilIdle()

        // 安全退出：设备列表复位，不残留到下一个登录的账户。
        assertNull(viewModel.securityState.value.devices)
        assertNull(viewModel.securityState.value.message)
        assertFalse(viewModel.securityState.value.loading)
        assertFalse(viewModel.state.value.signedIn)
        assertEquals("已退出登录。", viewModel.state.value.message)
    }

    @Test fun deviceIdentityMaskAbbreviatesPrefixOnlyAndNeverKeepsTail() {
        val identifier = "0123456789abcdef-1122"
        val masked = DeviceIdentityMaskPolicy.mask(identifier)

        assertTrue(masked.endsWith("…"))
        assertTrue(masked.length <= 9)
        assertFalse(masked.contains("89abcdef"))
        assertFalse(masked.contains("1122"))
        assertEquals("未知", DeviceIdentityMaskPolicy.mask("   "))
    }

    @Test fun deviceIdentityMaskFullyHidesShortIdentifiers() {
        // 短标识展示前缀几乎等于泄露全文，必须整体掩码。
        assertEquals("…", DeviceIdentityMaskPolicy.mask("short-id"))
        assertEquals("…", DeviceIdentityMaskPolicy.mask("12345678"))
        assertEquals("…", DeviceIdentityMaskPolicy.mask("1234567890123456"))
        // 超过两倍前缀长度才保留短前缀。
        assertEquals("12345678…", DeviceIdentityMaskPolicy.mask("12345678901234567"))
    }
}

private class FixedInstallationIdStore(private val id: String) : InstallationIdStore {
    override fun get() = id
    override fun clear() = Unit
}

private class SecurityApi : AccountApi {
    var devices: List<CloudDevice> = emptyList()
    var failure: Exception? = null

    override fun devices(): List<CloudDevice> {
        failure?.let { throw it }
        return devices
    }

    override fun translationSessions(): List<TranslationSession> = emptyList()
    override fun fetchRegistrationCaptcha() = SlideCaptchaChallenge("captcha", 300, 6, SlideCaptchaImage("a", "image/jpeg", 300, 220), SlideCaptchaTile(SlideCaptchaImage("b", "image/png", 20, 20), 0, 0))
    override fun register(username: String, email: String, password: String, captchaId: String, captchaX: Int): RegistrationResponse =
        RegistrationResponse(CloudUser("user-1", "alice_01", CloudRole.USER), CloudEntitlement("trial", "2026-09-05"), AuthTokens("access", "refresh"))
    override fun deleteAccount(username: String) = Unit
    override fun login(identifier: String, password: String) = AuthTokens("access", "refresh")
    override fun logout() = Unit
    override fun currentUser() = CloudUser("user-1", "alice_01", CloudRole.USER)
    override fun currentEntitlement(): CloudEntitlement? = CloudEntitlement("trial", "2026-09-01")
    override fun redeem(code: String) = CloudEntitlement("trial", "2026-09-01")
    override fun hasCredentials() = false
    override fun accountOverview() = AccountOverview("alice_01", null, UsageSummary(0, 0, null))
    override fun accountIdentityProfile() = AccountIdentityProfile("alice_01", "alice@example.test", null)
    override fun usage(limit: Int, offset: Int) = UsagePage(emptyList(), 0)
    override fun updateIdentity(request: IdentityUpdateRequest) = Unit
}

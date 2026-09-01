package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.cloud.AccountApi
import com.verba.interpretation.cloud.AuthTokens
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelPhoneAuthenticationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun registerKeepsAutomaticLoginAndFetchSequence() {
        val api = RecordingAccountApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher)

        viewModel.register("alice_01", "+8613800138000", "Passw0rd")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("register:alice_01:+8613800138000", "login:+8613800138000", "currentUser", "currentEntitlement"),
            api.calls,
        )
        assertEquals("alice_01", viewModel.state.value.user?.username)
    }
}

private class RecordingAccountApi : AccountApi {
    val calls = mutableListOf<String>()
    override fun register(username: String, phone: String, password: String) { calls += "register:$username:$phone" }
    override fun login(phone: String, password: String): AuthTokens {
        calls += "login:$phone"
        return AuthTokens("access", "refresh")
    }
    override fun logout() = Unit
    override fun currentUser(): CloudUser {
        calls += "currentUser"
        return CloudUser("user-1", "alice_01", CloudRole.USER)
    }
    override fun currentEntitlement(): CloudEntitlement? {
        calls += "currentEntitlement"
        return CloudEntitlement("trial", "2026-09-01")
    }
    override fun redeem(code: String): CloudEntitlement = error("not used")
    override fun hasCredentials(): Boolean = false
}

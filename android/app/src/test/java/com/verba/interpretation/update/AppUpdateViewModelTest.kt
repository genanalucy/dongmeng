package com.verba.interpretation.update

import android.app.Application
import com.verba.interpretation.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun ordinaryAutomaticUpdateCanBeIgnoredByVersion() = runTest(dispatcher) {
        val preferences = RecordingPreferences()
        val viewModel = viewModel(preferences, forceUpdate = false)

        viewModel.checkAutomatically()
        advanceUntilIdle()
        assertEquals(testUpdate(false), viewModel.automaticPrompt.value)

        viewModel.ignoreAutomaticPromptVersion()
        viewModel.checkAutomatically()
        advanceUntilIdle()

        assertNull(viewModel.automaticPrompt.value)
        assertEquals(testUpdate(false).versionCode, preferences.ignoredVersion)
    }

    @Test fun forceUpdateBypassesDisabledAutomaticPrompts() = runTest(dispatcher) {
        val preferences = RecordingPreferences(enabled = false)
        val viewModel = viewModel(preferences, forceUpdate = true)

        viewModel.checkAutomatically()
        advanceUntilIdle()

        assertEquals(testUpdate(true), viewModel.automaticPrompt.value)
    }

    @Test fun manualCheckIgnoresAutomaticPromptPreferences() = runTest(dispatcher) {
        val viewModel = viewModel(RecordingPreferences(enabled = false), forceUpdate = false)

        viewModel.check()
        advanceUntilIdle()

        assertEquals(AppUpdateState.Available(testUpdate(false)), viewModel.state.value)
    }

    private fun viewModel(preferences: RecordingPreferences, forceUpdate: Boolean) = AppUpdateViewModel(
        Application(),
        service = object : AppUpdateService {
            override fun checkForUpdate(): AppUpdateInfo = testUpdate(forceUpdate)
        },
        promptPreferences = preferences,
        dispatcher = dispatcher,
    )

    private fun testUpdate(forceUpdate: Boolean) = AppUpdateInfo(
        packageName = BuildConfig.APPLICATION_ID,
        versionCode = BuildConfig.VERSION_CODE + 1,
        versionName = "next",
        apkUrl = "https://updates.example.test/app.apk",
        sha256 = "a".repeat(64),
        releaseNotes = "修复问题",
        forceUpdate = forceUpdate,
    )
}

private class RecordingPreferences(
    private var enabled: Boolean = true,
    var ignoredVersion: Int? = null,
) : AppUpdatePromptPreferences {
    override fun shouldPrompt(versionCode: Int): Boolean = enabled && ignoredVersion != versionCode
    override fun ignoreVersion(versionCode: Int) { ignoredVersion = versionCode }
    override fun disableAutomaticPrompts() { enabled = false }
}

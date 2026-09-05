package com.verba.interpretation.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
    @get:Rule val compose = createComposeRule()

    @Test fun accountScreenHasNoIdentityEditingCopyOrPrivateFields() {
        compose.setContent { MaterialTheme { AccountScreen(signedInState(), {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("账户管理").assertExists()
        compose.onNodeWithText("修改用户名", substring = true).assertDoesNotExist()
        compose.onNodeWithText("alice@example.test", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("账户管理").assertExists()
    }

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

    private fun signedInState() = AccountUiState(user = CloudUser("user-1", "alice_01", CloudRole.USER), overview = AccountOverview("alice_01", null, UsageSummary(3600, 2, "2026-09-02T12:00:00Z")))
}

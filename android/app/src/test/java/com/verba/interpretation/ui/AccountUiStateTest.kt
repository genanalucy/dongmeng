package com.verba.interpretation.ui

import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountUiStateTest {
    @Test fun unauthenticatedAccountShowsOnlyAuthenticationExperience() {
        assertEquals(ProductNavigationMode.AUTHENTICATION, AccountUiState().navigationMode)
    }

    @Test fun standardUserAlwaysGetsUserExperience() {
        val state = AccountUiState(user = CloudUser("user-1", "user@example.com", CloudRole.USER))

        assertEquals(ProductNavigationMode.USER, state.navigationMode)
    }

    @Test fun adminCanSwitchBetweenTestAndUserPreviewExperiences() {
        val admin = CloudUser("admin-1", "admin@example.com", CloudRole.ADMIN)

        assertEquals(ProductNavigationMode.ADMIN_TEST, AccountUiState(user = admin).navigationMode)
        assertEquals(ProductNavigationMode.USER, AccountUiState(user = admin, previewingUserExperience = true).navigationMode)
    }
}

package com.verba.interpretation.ui.account

data class AccountIdentityValidation(
    val username: String,
    val email: String,
    val phone: String,
    val usernameError: String?,
    val emailError: String?,
    val phoneError: String?,
    val currentPasswordError: String?,
) {
    val isValid: Boolean get() = listOf(usernameError, emailError, phoneError, currentPasswordError).all { it == null }
}

object AccountIdentityFormPolicy {
    fun validate(username: String, email: String, phone: String, currentPassword: String): AccountIdentityValidation {
        val registration = PhoneAuthenticationFormPolicy.register(username, email, phone, currentPassword, currentPassword)
        return AccountIdentityValidation(
            username = registration.normalizedUsername,
            email = registration.normalizedEmail,
            phone = registration.normalizedPhone,
            usernameError = registration.usernameError,
            emailError = registration.emailError,
            phoneError = registration.phoneError,
            currentPasswordError = if (currentPassword.isEmpty()) "请输入当前密码。" else registration.passwordError,
        )
    }
}

object AccountIdentitySubmissionPolicy {
    fun submit(
        username: String,
        email: String,
        phone: String,
        currentPassword: String,
        onSubmit: (String, String, String, String) -> Unit,
    ) {
        val validation = AccountIdentityFormPolicy.validate(username, email, phone, currentPassword)
        if (validation.isValid) onSubmit(validation.username, validation.email, validation.phone, currentPassword)
    }
}

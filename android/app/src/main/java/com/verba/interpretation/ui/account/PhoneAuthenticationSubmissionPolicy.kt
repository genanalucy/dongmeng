package com.verba.interpretation.ui.account

/** Keeps Compose event handlers from dispatching malformed authentication requests. */
object PhoneAuthenticationSubmissionPolicy {
    fun submitLogin(identifier: String, password: String, onLogin: (String, String) -> Unit): Boolean {
        val validation = PhoneAuthenticationFormPolicy.login(identifier, password)
        if (!validation.isValid) return false
        onLogin(validation.normalizedIdentifier, password)
        return true
    }

    fun submitRegistration(
        username: String,
        email: String,
        phone: String,
        password: String,
        confirmation: String,
        onRegister: (String, String, String, String) -> Unit,
    ): Boolean {
        val validation = PhoneAuthenticationFormPolicy.register(username, email, phone, password, confirmation)
        if (!validation.isValid) return false
        onRegister(validation.normalizedUsername, validation.normalizedEmail, validation.normalizedPhone, password)
        return true
    }
}

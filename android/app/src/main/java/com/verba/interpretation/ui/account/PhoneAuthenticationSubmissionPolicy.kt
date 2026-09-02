package com.verba.interpretation.ui.account

/** Keeps Compose event handlers from dispatching malformed authentication requests. */
object AuthenticationSubmissionPolicy {
    fun submitLogin(identifier: String, password: String, onLogin: (String, String) -> Unit): Boolean {
        val validation = AuthenticationFormPolicy.login(identifier, password)
        if (!validation.isValid) return false
        onLogin(validation.normalizedIdentifier, password)
        return true
    }

    fun submitRegistration(
        username: String,
        email: String,
        password: String,
        confirmation: String,
        onRequestVerification: (String, String, String) -> Unit,
    ): Boolean {
        val validation = AuthenticationFormPolicy.register(username, email, password, confirmation)
        if (!validation.isValid) return false
        onRequestVerification(validation.normalizedUsername, validation.normalizedEmail, password)
        return true
    }

    fun submitVerification(email: String, code: String, onConfirmVerification: (String, String) -> Unit): Boolean {
        if (!RegistrationFormPolicy.validateVerificationCode(code).isValid) return false
        onConfirmVerification(email, code)
        return true
    }
}

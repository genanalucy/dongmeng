package com.verba.interpretation.ui.account

/** Keeps Compose event handlers from dispatching malformed authentication requests. */
object PhoneAuthenticationSubmissionPolicy {
    fun submitLogin(phone: String, password: String, onLogin: (String, String) -> Unit): Boolean {
        val validation = PhoneAuthenticationFormPolicy.login(phone, password)
        if (!validation.isValid) return false
        onLogin(validation.normalizedPhone, password)
        return true
    }

    fun submitRegistration(
        username: String,
        phone: String,
        password: String,
        confirmation: String,
        onRegister: (String, String, String) -> Unit,
    ): Boolean {
        val validation = PhoneAuthenticationFormPolicy.register(username, phone, password, confirmation)
        if (!validation.isValid) return false
        onRegister(validation.normalizedUsername, validation.normalizedPhone, password)
        return true
    }
}

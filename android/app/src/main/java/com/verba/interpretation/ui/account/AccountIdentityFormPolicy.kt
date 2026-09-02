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
        val registration = RegistrationFormPolicy.validate(email, currentPassword, currentPassword)
        val normalizedUsername = username.trim().lowercase()
        val normalizedPhone = normalizePhone(phone)
        return AccountIdentityValidation(
            username = normalizedUsername,
            email = registration.normalizedEmail,
            phone = normalizedPhone,
            usernameError = if (normalizedUsername.length in 3..32 && normalizedUsername.all { it in 'a'..'z' || it in '0'..'9' || it == '_' } && normalizedUsername.any { it !in '0'..'9' }) null else "用户名需要 3 至 32 个字符，仅支持字母、数字和下划线，且不能全为数字。",
            emailError = registration.emailError,
            phoneError = if (normalizedPhone.isEmpty()) "请输入有效的中国大陆手机号。" else null,
            currentPasswordError = if (currentPassword.isEmpty()) "请输入当前密码。" else null,
        )
    }

    private fun normalizePhone(phone: String): String {
        val mainlandNumber = phone.trim().removePrefix("+86")
        return if (mainlandNumber.length == 11 && mainlandNumber[0] == '1' && mainlandNumber[1] in '3'..'9' && mainlandNumber.all { it in '0'..'9' }) "+86$mainlandNumber" else ""
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

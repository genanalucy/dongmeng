package com.verba.interpretation.ui.account

import java.util.Locale

data class PhoneRegistrationFormValidation(
    val normalizedUsername: String,
    val normalizedPhone: String,
    val usernameError: String?,
    val phoneError: String?,
    val passwordError: String?,
    val confirmationError: String?,
) {
    val isValid: Boolean
        get() = usernameError == null && phoneError == null && passwordError == null && confirmationError == null
    val renderedErrors: List<String>
        get() = listOfNotNull(usernameError, phoneError, passwordError, confirmationError)
}

data class PhoneLoginFormValidation(
    val normalizedPhone: String,
    val phoneError: String?,
    val passwordError: String?,
) {
    val isValid: Boolean get() = phoneError == null && passwordError == null
    val renderedErrors: List<String> get() = listOfNotNull(phoneError, passwordError)
}

object PhoneAuthenticationFormPolicy {
    private const val InvalidUsernameMessage = "用户名需要 3 至 32 个字符，仅支持字母、数字和下划线。"
    private const val InvalidPhoneMessage = "请输入有效的中国大陆手机号。"
    private const val EmptyPasswordMessage = "请输入密码。"
    private const val ShortPasswordMessage = "密码至少需要 8 个字符。"
    private const val MissingUppercaseMessage = "密码需包含大写英文字母。"
    private const val MissingLowercaseMessage = "密码需包含小写英文字母。"
    private const val MissingNumberMessage = "密码需包含数字。"
    private const val MismatchedPasswordMessage = "两次输入的密码不一致。"

    fun register(username: String, phone: String, password: String, confirmation: String): PhoneRegistrationFormValidation {
        val normalizedUsername = username.trim().lowercase(Locale.ROOT)
        val normalizedPhone = normalizePhone(phone)
        return PhoneRegistrationFormValidation(
            normalizedUsername = normalizedUsername,
            normalizedPhone = normalizedPhone,
            usernameError = if (isValidUsername(normalizedUsername)) null else InvalidUsernameMessage,
            phoneError = if (normalizedPhone.isNotEmpty()) null else InvalidPhoneMessage,
            passwordError = passwordError(password, required = false),
            confirmationError = if (confirmation == password) null else MismatchedPasswordMessage,
        )
    }

    fun login(phone: String, password: String): PhoneLoginFormValidation {
        val normalizedPhone = normalizePhone(phone)
        return PhoneLoginFormValidation(
            normalizedPhone = normalizedPhone,
            phoneError = if (normalizedPhone.isNotEmpty()) null else InvalidPhoneMessage,
            passwordError = passwordError(password, required = true),
        )
    }

    private fun isValidUsername(username: String): Boolean =
        username.length in 3..32 && username.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    private fun normalizePhone(phone: String): String {
        val mainlandNumber = phone.trim().removePrefix("+86")
        return if (mainlandNumber.length == 11 && mainlandNumber[0] == '1' && mainlandNumber[1] in '3'..'9' && mainlandNumber.all { it in '0'..'9' }) {
            "+86$mainlandNumber"
        } else {
            ""
        }
    }

    private fun passwordError(password: String, required: Boolean): String? = when {
        password.isEmpty() && required -> EmptyPasswordMessage
        password.length < 8 -> ShortPasswordMessage
        password.none { it in 'A'..'Z' } -> MissingUppercaseMessage
        password.none { it in 'a'..'z' } -> MissingLowercaseMessage
        password.none { it in '0'..'9' } -> MissingNumberMessage
        else -> null
    }
}

package com.verba.interpretation.ui.account

import java.util.Locale

enum class AuthenticationMode { LOGIN, REGISTER }

data class RegistrationModeCallbacks(
    val onLogin: () -> Unit,
    val onRegister: () -> Unit,
)

object RegistrationModeDispatcher {
    fun dispatch(mode: AuthenticationMode, callbacks: RegistrationModeCallbacks) = when (mode) {
        AuthenticationMode.LOGIN -> callbacks.onLogin()
        AuthenticationMode.REGISTER -> callbacks.onRegister()
    }
}

data class RegistrationFormValidation(
    val normalizedEmail: String,
    val emailError: String?,
    val passwordError: String?,
    val confirmationError: String?,
) {
    val isValid: Boolean get() = emailError == null && passwordError == null && confirmationError == null
    val renderedErrors: List<String> get() = listOfNotNull(emailError, passwordError, confirmationError)
}

data class VerificationCodeValidation(val codeError: String?) {
    val isValid: Boolean get() = codeError == null
    val renderedErrors: List<String> get() = listOfNotNull(codeError)
}

object RegistrationFormPolicy {
    private const val InvalidEmailMessage = "请输入有效的邮箱地址。"
    private const val ShortPasswordMessage = "密码至少需要 8 个字符。"
    private const val MissingUppercaseMessage = "密码需包含大写英文字母。"
    private const val MissingLowercaseMessage = "密码需包含小写英文字母。"
    private const val MissingNumberMessage = "密码需包含数字。"
    private const val MismatchedPasswordMessage = "两次输入的密码不一致。"
    private const val InvalidCodeMessage = "请输入 6 位数字验证码。"

    fun validate(email: String, password: String, confirmation: String): RegistrationFormValidation {
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        return RegistrationFormValidation(
            normalizedEmail = normalizedEmail,
            emailError = if (isServerCompatibleEmail(normalizedEmail)) null else InvalidEmailMessage,
            passwordError = passwordError(password),
            confirmationError = if (confirmation == password) null else MismatchedPasswordMessage,
        )
    }

    fun validateVerificationCode(code: String): VerificationCodeValidation =
        VerificationCodeValidation(if (code.length == 6 && code.all { it in '0'..'9' }) null else InvalidCodeMessage)

    private fun isServerCompatibleEmail(email: String): Boolean {
        if (email.isEmpty() || email.toByteArray(Charsets.UTF_8).size > 254 || !email.isWellFormedUtf16()) return false
        if (email.count { it == '@' } != 1 || email.any { it.isWhitespace() || it.isISOControl() }) return false
        val at = email.indexOf('@')
        return at > 0 && at < email.lastIndex
    }

    private fun passwordError(password: String): String? = when {
        password.length < 8 -> ShortPasswordMessage
        password.none { it in 'A'..'Z' } -> MissingUppercaseMessage
        password.none { it in 'a'..'z' } -> MissingLowercaseMessage
        password.none { it in '0'..'9' } -> MissingNumberMessage
        else -> null
    }

    private fun String.isWellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val char = this[index]
            if (char.isHighSurrogate()) {
                if (index + 1 == length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            } else {
                if (char.isLowSurrogate()) return false
                index++
            }
        }
        return true
    }
}

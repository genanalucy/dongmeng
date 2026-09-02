package com.verba.interpretation.ui.account

import java.nio.charset.StandardCharsets
import java.util.Locale

data class RegistrationDetailsValidation(
    val normalizedUsername: String,
    val normalizedEmail: String,
    val usernameError: String?,
    val emailError: String?,
    val passwordError: String?,
    val confirmationError: String?,
) {
    val isValid: Boolean
        get() = usernameError == null && emailError == null && passwordError == null && confirmationError == null
    val renderedErrors: List<String>
        get() = listOfNotNull(usernameError, emailError, passwordError, confirmationError)
}

data class LoginFormValidation(
    val normalizedIdentifier: String,
    val identifierError: String?,
    val passwordError: String?,
) {
    val isValid: Boolean get() = identifierError == null && passwordError == null
    val renderedErrors: List<String> get() = listOfNotNull(identifierError, passwordError)
}

object AuthenticationFormPolicy {
    private const val InvalidUsernameMessage = "用户名需要 3 至 32 个字符，仅支持字母、数字和下划线，且不能全为数字。"
    private const val InvalidEmailMessage = "请输入有效的邮箱地址。"
    private const val InvalidIdentifierMessage = "请输入有效的邮箱、手机号或用户名。"
    private const val EmptyPasswordMessage = "请输入密码。"
    private const val ShortPasswordMessage = "密码至少需要 8 个字符。"
    private const val MissingUppercaseMessage = "密码需包含大写英文字母。"
    private const val MissingLowercaseMessage = "密码需包含小写英文字母。"
    private const val MissingNumberMessage = "密码需包含数字。"
    private const val PasswordTooLongMessage = "密码不能超过 256 个字节。"
    private const val MismatchedPasswordMessage = "两次输入的密码不一致。"

    fun register(username: String, email: String, password: String, confirmation: String): RegistrationDetailsValidation {
        val normalizedUsername = normalizeUsername(username)
        val normalizedEmail = normalizeEmail(email)
        return RegistrationDetailsValidation(
            normalizedUsername = normalizedUsername,
            normalizedEmail = normalizedEmail,
            usernameError = if (isValidUsername(normalizedUsername)) null else InvalidUsernameMessage,
            emailError = if (normalizedEmail.isNotEmpty()) null else InvalidEmailMessage,
            passwordError = registrationPasswordError(password),
            confirmationError = if (confirmation == password) null else MismatchedPasswordMessage,
        )
    }

    fun login(identifier: String, password: String): LoginFormValidation {
        val normalizedIdentifier = normalizeIdentifier(identifier)
        return LoginFormValidation(
            normalizedIdentifier = normalizedIdentifier,
            identifierError = if (normalizedIdentifier.isNotEmpty()) null else InvalidIdentifierMessage,
            passwordError = if (password.isEmpty()) EmptyPasswordMessage else null,
        )
    }

    private fun normalizeIdentifier(identifier: String): String =
        normalizePhone(identifier).ifEmpty { normalizeEmail(identifier).ifEmpty { normalizeUsername(identifier).takeIf(::isValidUsername).orEmpty() } }

    private fun normalizeUsername(username: String): String = username.trim().lowercase(Locale.ROOT)

    private fun isValidUsername(username: String): Boolean =
        username.length in 3..32 && username.all { it in 'a'..'z' || it in '0'..'9' || it == '_' } && username.any { it !in '0'..'9' }

    private fun normalizeEmail(email: String): String {
        val normalized = email.trim().lowercase(Locale.ROOT)
        return normalized.takeIf { isValidUtf8(it) && it.toByteArray(StandardCharsets.UTF_8).size <= 254 && emailPattern.matches(it) }.orEmpty()
    }

    // Login remains compatible with pre-email-verification phone accounts.
    private fun normalizePhone(phone: String): String {
        val mainlandNumber = phone.trim().removePrefix("+86")
        return if (mainlandNumber.length == 11 && mainlandNumber[0] == '1' && mainlandNumber[1] in '3'..'9' && mainlandNumber.all { it in '0'..'9' }) "+86$mainlandNumber" else ""
    }

    private fun registrationPasswordError(password: String): String? = when {
        !isValidUtf8(password) || password.toByteArray(StandardCharsets.UTF_8).size > 256 -> PasswordTooLongMessage
        password.toByteArray(StandardCharsets.UTF_8).size < 8 -> ShortPasswordMessage
        password.none { it in 'A'..'Z' } -> MissingUppercaseMessage
        password.none { it in 'a'..'z' } -> MissingLowercaseMessage
        password.none { it in '0'..'9' } -> MissingNumberMessage
        else -> null
    }

    private fun isValidUtf8(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            when (value[index]) {
                in '\uD800'..'\uDBFF' -> {
                    if (index + 1 == value.length || value[index + 1] !in '\uDC00'..'\uDFFF') return false
                    index += 2
                }
                in '\uDC00'..'\uDFFF' -> return false
                else -> index++
            }
        }
        return true
    }

    private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+$")
}

package com.verba.interpretation.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.RegistrationUiState

@Composable
fun AuthenticationForm(
    loading: Boolean,
    registration: RegistrationUiState,
    onLogin: (String, String) -> Unit,
    onRequestVerification: (String, String, String) -> Unit,
    onConfirmVerification: (String, String) -> Unit,
    onEditDetails: () -> Unit,
    onResend: (String, String, String) -> Unit,
) {
    var mode by remember { mutableStateOf(AuthenticationMode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(emptySet<String>()) }
    val login = AuthenticationFormPolicy.login(identifier, password)
    val details = AuthenticationFormPolicy.register(username, email, password, confirmation)
    val verification = RegistrationFormPolicy.validateVerificationCode(code)
    Column {
        if (mode == AuthenticationMode.LOGIN) {
            LoginForm(loading, identifier, password, login, { identifier = it; touched += "identifier" }, { password = it; touched += "password" }, onLogin, { mode = AuthenticationMode.REGISTER })
        } else when (registration) {
            RegistrationUiState.Details -> RegistrationDetailsForm(
                loading, username, email, password, confirmation, details,
                { username = it; touched += "username" }, { email = it; touched += "email" },
                { password = it; touched += "password" }, { confirmation = it; touched += "confirmation" },
                { AuthenticationSubmissionPolicy.submitRegistration(username, email, password, confirmation, onRequestVerification) },
                { mode = AuthenticationMode.LOGIN },
            )
            is RegistrationUiState.Challenge -> VerificationForm(
                loading, registration, code, verification,
                { code = it.take(6).filter { character -> character in '0'..'9' }; touched += "verification-code" },
                { AuthenticationSubmissionPolicy.submitVerification(registration.email, code, onConfirmVerification) },
                onEditDetails,
                { onResend(registration.username, registration.email, password) },
                "verification-code" in touched,
            )
        }
    }
}

@Composable
private fun LoginForm(loading: Boolean, identifier: String, password: String, validation: LoginFormValidation, onIdentifierChange: (String) -> Unit, onPasswordChange: (String) -> Unit, onLogin: (String, String) -> Unit, onRegister: () -> Unit) {
    Text("登录", style = MaterialTheme.typography.titleMedium)
    TextField(identifier, onIdentifierChange, "邮箱 / 手机号 / 用户名", validation.identifierError, "identifier", KeyboardType.Text, ImeAction.Next, false)
    PasswordField(password, onPasswordChange, validation.passwordError, "password", ImeAction.Done)
    Button(onClick = { AuthenticationSubmissionPolicy.submitLogin(identifier, password, onLogin) }, enabled = !loading && validation.isValid, modifier = actionModifier()) { Text(if (loading) "处理中…" else "登录") }
    TextButton(onClick = onRegister, enabled = !loading, modifier = fullWidthActionModifier()) { Text("注册账户") }
}

@Composable
private fun RegistrationDetailsForm(loading: Boolean, username: String, email: String, password: String, confirmation: String, validation: RegistrationDetailsValidation, onUsernameChange: (String) -> Unit, onEmailChange: (String) -> Unit, onPasswordChange: (String) -> Unit, onConfirmationChange: (String) -> Unit, onSend: () -> Unit, onLogin: () -> Unit) {
    Text("注册账户", style = MaterialTheme.typography.titleMedium)
    TextField(username, onUsernameChange, "用户名", validation.usernameError, "username", KeyboardType.Text, ImeAction.Next, false)
    TextField(email, onEmailChange, "邮箱", validation.emailError, "email", KeyboardType.Email, ImeAction.Next, false)
    PasswordField(password, onPasswordChange, validation.passwordError, "password", ImeAction.Next)
    PasswordField(confirmation, onConfirmationChange, validation.confirmationError, "confirmation", ImeAction.Done)
    Button(onClick = onSend, enabled = !loading && validation.isValid, modifier = actionModifier()) { Text(if (loading) "处理中…" else "发送验证码") }
    TextButton(onClick = onLogin, enabled = !loading, modifier = fullWidthActionModifier()) { Text("返回登录") }
}

@Composable
private fun VerificationForm(loading: Boolean, challenge: RegistrationUiState.Challenge, code: String, validation: VerificationCodeValidation, onCodeChange: (String) -> Unit, onConfirm: () -> Unit, onEditDetails: () -> Unit, onResend: () -> Unit, codeTouched: Boolean) {
    Text("确认邮箱验证码", style = MaterialTheme.typography.titleMedium)
    Text("验证码已发送至 ${challenge.maskedEmail}")
    TextField(code, onCodeChange, "6 位数字验证码", validation.codeError.takeIf { codeTouched }, "verification-code", KeyboardType.NumberPassword, ImeAction.Done, false)
    Button(onClick = onConfirm, enabled = !loading && validation.isValid, modifier = actionModifier()) { Text(if (loading) "处理中…" else "确认注册") }
    TextButton(onClick = onEditDetails, enabled = !loading, modifier = fullWidthActionModifier()) { Text("返回编辑资料") }
    var nowMillis by remember(challenge.resendAvailableAtMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(challenge.resendAvailableAtMillis) {
        while (nowMillis < challenge.resendAvailableAtMillis) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    val remainingSeconds = RegistrationResendPolicy.remainingSeconds(challenge.resendAvailableAtMillis, nowMillis)
    TextButton(onClick = onResend, enabled = !loading && remainingSeconds == 0, modifier = fullWidthActionModifier()) {
        Text(if (remainingSeconds == 0) "重新发送验证码" else "${remainingSeconds} 秒后可重新发送")
    }
}

@Composable private fun PasswordField(value: String, onChange: (String) -> Unit, error: String?, tag: String, ime: ImeAction) =
    TextField(value, onChange, if (tag == "confirmation") "确认密码" else "密码", error, tag, KeyboardType.Password, ime, true)

@Composable private fun TextField(value: String, onChange: (String) -> Unit, label: String, errorText: String?, tag: String, keyboardType: KeyboardType, ime: ImeAction, password: Boolean) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = errorText != null, supportingText = errorText?.let { { Text(it) } }, visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType, imeAction = ime), modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp).semantics { errorText?.let { error(it) }; testTag = tag })
}

private fun actionModifier(): Modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp)
private fun fullWidthActionModifier(): Modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)

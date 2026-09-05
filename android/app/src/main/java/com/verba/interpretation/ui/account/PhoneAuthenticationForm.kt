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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.RegistrationUiState

/** 密码只保留在此组合函数的本地状态，并作为提交参数短暂传递。 */
@Composable
fun AuthenticationForm(
    loading: Boolean,
    registration: RegistrationUiState,
    onLogin: (String, String) -> Unit,
    onRequestCaptcha: (String, String, String) -> Unit,
    onSubmitCaptcha: (String, String, String, Int) -> Unit,
    onRefreshCaptcha: () -> Unit,
    onEditDetails: () -> Unit,
    initialIdentifier: String = "",
) {
    var mode by remember { mutableStateOf(AuthenticationMode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var identifier by remember(initialIdentifier) { mutableStateOf(initialIdentifier) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val login = AuthenticationFormPolicy.login(identifier, password)
    val details = AuthenticationFormPolicy.register(username, email, password, confirmation)
    Column {
        if (mode == AuthenticationMode.LOGIN) {
            LoginForm(loading, identifier, password, login, { identifier = it }, { password = it }, onLogin, { mode = AuthenticationMode.REGISTER })
        } else when (registration) {
            RegistrationUiState.Details -> RegistrationDetailsForm(
                loading, username, email, password, confirmation, details,
                { username = it }, { email = it }, { password = it }, { confirmation = it },
                { AuthenticationSubmissionPolicy.submitRegistration(username, email, password, confirmation, onRequestCaptcha) },
                { mode = AuthenticationMode.LOGIN },
            )
            is RegistrationUiState.SlideCaptcha -> SlideCaptchaChallengeForm(
                captcha = registration,
                loading = loading,
                onSubmitCaptcha = { captchaX -> onSubmitCaptcha(username, email, password, captchaX) },
                onRefresh = onRefreshCaptcha,
                onEditDetails = onEditDetails,
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
private fun RegistrationDetailsForm(loading: Boolean, username: String, email: String, password: String, confirmation: String, validation: RegistrationDetailsValidation, onUsernameChange: (String) -> Unit, onEmailChange: (String) -> Unit, onPasswordChange: (String) -> Unit, onConfirmationChange: (String) -> Unit, onRequestCaptcha: () -> Unit, onLogin: () -> Unit) {
    Text("注册账户", style = MaterialTheme.typography.titleMedium)
    TextField(username, onUsernameChange, "用户名", validation.usernameError, "username", KeyboardType.Text, ImeAction.Next, false)
    TextField(email, onEmailChange, "邮箱", validation.emailError, "email", KeyboardType.Email, ImeAction.Next, false)
    PasswordField(password, onPasswordChange, validation.passwordError, "password", ImeAction.Next)
    PasswordField(confirmation, onConfirmationChange, validation.confirmationError, "confirmation", ImeAction.Done)
    Button(onClick = onRequestCaptcha, enabled = !loading && validation.isValid, modifier = actionModifier()) { Text(if (loading) "处理中…" else "完成拼图验证") }
    TextButton(onClick = onLogin, enabled = !loading, modifier = fullWidthActionModifier()) { Text("返回登录") }
}

@Composable private fun PasswordField(value: String, onChange: (String) -> Unit, error: String?, tag: String, ime: ImeAction) =
    TextField(value, onChange, if (tag == "confirmation") "确认密码" else "密码", error, tag, KeyboardType.Password, ime, true)

@Composable private fun TextField(value: String, onChange: (String) -> Unit, label: String, errorText: String?, tag: String, keyboardType: KeyboardType, ime: ImeAction, password: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType, imeAction = ime),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp).semantics { errorText?.let { error(it) }; testTag = tag },
    )
}

private fun actionModifier(): Modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp)
private fun fullWidthActionModifier(): Modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)

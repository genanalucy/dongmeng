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

@Composable
fun PhoneAuthenticationForm(
    loading: Boolean,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String, String) -> Unit,
) {
    var mode by remember { mutableStateOf(AuthenticationMode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var touched by remember { mutableStateOf(emptySet<String>()) }
    val login = PhoneAuthenticationFormPolicy.login(identifier, password)
    val registration = PhoneAuthenticationFormPolicy.register(username, email, phone, password, confirmation)
    Column {
        if (mode == AuthenticationMode.LOGIN) {
            Text("登录", style = MaterialTheme.typography.titleMedium)
            TextField(identifier, { identifier = it; touched = touched + "identifier" }, "邮箱 / 手机号 / 用户名", login.identifierError.takeIf { "identifier" in touched }, "identifier", KeyboardType.Text, ImeAction.Next, false)
            PasswordField(password, { password = it; touched = touched + "password" }, login.passwordError.takeIf { "password" in touched }, "password", ImeAction.Done)
            Button(
                onClick = { PhoneAuthenticationSubmissionPolicy.submitLogin(identifier, password, onLogin) },
                enabled = !loading && login.isValid,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp),
            ) { Text(if (loading) "处理中…" else "登录") }
            TextButton(onClick = { mode = AuthenticationMode.REGISTER }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("注册账户") }
        } else {
            Text("注册账户", style = MaterialTheme.typography.titleMedium)
            TextField(username, { username = it; touched = touched + "username" }, "用户名", registration.usernameError.takeIf { "username" in touched }, "username", KeyboardType.Text, ImeAction.Next, false)
            TextField(email, { email = it; touched = touched + "email" }, "邮箱", registration.emailError.takeIf { "email" in touched }, "email", KeyboardType.Email, ImeAction.Next, false)
            PhoneField(phone, { phone = it; touched = touched + "phone" }, registration.phoneError.takeIf { "phone" in touched }, "phone", ImeAction.Next)
            PasswordField(password, { password = it; touched = touched + "password" }, registration.passwordError.takeIf { "password" in touched }, "password", ImeAction.Next)
            PasswordField(confirmation, { confirmation = it; touched = touched + "confirmation" }, registration.confirmationError.takeIf { "confirmation" in touched }, "confirmation", ImeAction.Done)
            Button(
                onClick = { PhoneAuthenticationSubmissionPolicy.submitRegistration(username, email, phone, password, confirmation, onRegister) },
                enabled = !loading && registration.isValid,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp),
            ) { Text(if (loading) "处理中…" else "注册并登录") }
            TextButton(onClick = { mode = AuthenticationMode.LOGIN }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回登录") }
        }
    }
}

@Composable private fun PhoneField(value: String, onChange: (String) -> Unit, error: String?, tag: String, ime: ImeAction) =
    TextField(value, onChange, "手机号", error, tag, KeyboardType.Phone, ime, false)

@Composable private fun PasswordField(value: String, onChange: (String) -> Unit, error: String?, tag: String, ime: ImeAction) =
    TextField(value, onChange, if (tag == "confirmation") "确认密码" else "密码", error, tag, KeyboardType.Password, ime, true)

@Composable private fun TextField(value: String, onChange: (String) -> Unit, label: String, errorText: String?, tag: String, keyboardType: KeyboardType, ime: ImeAction, password: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        isError = errorText != null, supportingText = errorText?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType, imeAction = ime),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp).semantics { errorText?.let { error(it) }; testTag = tag },
    )
}

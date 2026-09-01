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
    onRegister: (String, String, String) -> Unit,
) {
    var mode by remember { mutableStateOf(AuthenticationMode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val login = PhoneAuthenticationFormPolicy.login(phone, password)
    val registration = PhoneAuthenticationFormPolicy.register(username, phone, password, confirmation)
    Column {
        if (mode == AuthenticationMode.LOGIN) {
            Text("手机号登录", style = MaterialTheme.typography.titleMedium)
            PhoneField(phone, { phone = it }, login.phoneError, "phone", ImeAction.Next)
            PasswordField(password, { password = it }, login.passwordError, "password", ImeAction.Done)
            Button(
                onClick = { PhoneAuthenticationSubmissionPolicy.submitLogin(phone, password, onLogin) },
                enabled = !loading && login.isValid,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp),
            ) { Text(if (loading) "处理中…" else "登录") }
            TextButton(onClick = { mode = AuthenticationMode.REGISTER }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("注册账户") }
        } else {
            Text("注册账户", style = MaterialTheme.typography.titleMedium)
            TextField(username, { username = it }, "用户名", registration.usernameError, "username", KeyboardType.Text, ImeAction.Next, false)
            PhoneField(phone, { phone = it }, registration.phoneError, "phone", ImeAction.Next)
            PasswordField(password, { password = it }, registration.passwordError, "password", ImeAction.Next)
            PasswordField(confirmation, { confirmation = it }, registration.confirmationError, "confirmation", ImeAction.Done)
            Button(
                onClick = { PhoneAuthenticationSubmissionPolicy.submitRegistration(username, phone, password, confirmation, onRegister) },
                enabled = !loading && registration.isValid,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp),
            ) { Text(if (loading) "处理中…" else "注册") }
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

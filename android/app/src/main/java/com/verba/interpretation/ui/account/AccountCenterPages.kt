package com.verba.interpretation.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.UsagePage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountUsageScreen(
    overview: AccountOverview?,
    usage: UsagePage?,
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("使用与权益") },
            navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                val entitlement = overview?.entitlement
                Text("权益详情", style = MaterialTheme.typography.titleMedium)
                Text(entitlement?.let { "${it.kind} · 到期时间 ${it.expiresAt} · 剩余 ${formatDuration(it.remainingSeconds)}" } ?: "暂无可用权益")
            }
            item {
                Text("使用统计", style = MaterialTheme.typography.titleMedium)
                val summary = overview?.usage
                Text(summary?.let { "累计 ${formatDuration(it.totalSeconds)}，${it.sessionCount} 次会话" } ?: "正在加载使用统计")
            }
            when {
                loading && usage == null -> item { Text("正在加载账户信息…") }
                message != null -> item { Text("账户信息暂时无法加载，请稍后重试。", color = MaterialTheme.colorScheme.error) }
                usage?.items.isNullOrEmpty() -> item { Text("暂无使用记录") }
                else -> items(usage?.items?.size ?: 0) { index ->
                    val item = usage!!.items[index]
                    Text("${formatDuration(item.durationSeconds)} · ${item.startedAt}")
                    item.sourceLanguage?.let { source -> Text("$source → ${item.targetLanguage ?: "未知"}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (usage != null && usage.items.size < usage.total) item {
                Button(onClick = onLoadMore, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountIdentitySettingsScreen(
    username: String,
    email: String,
    maskedPhone: String,
    loading: Boolean,
    onBack: () -> Unit,
    onSubmit: (String, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editedUsername by remember(username) { mutableStateOf(username) }
    var editedEmail by remember(email) { mutableStateOf(email) }
    var phone by remember { mutableStateOf(maskedPhone) }
    var currentPassword by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val validation = AccountIdentityFormPolicy.validate(editedUsername, editedEmail, phone, currentPassword)
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("账户设置") },
            navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { IdentityField(editedUsername, { editedUsername = it }, "用户名", "identity-username", validation.usernameError.takeIf { submitted }, KeyboardType.Text, false) }
            item { IdentityField(editedEmail, { editedEmail = it }, "邮箱", "identity-email", validation.emailError.takeIf { submitted }, KeyboardType.Email, false) }
            item {
                Text("手机号首次显示为掩码；如需保存修改，请重新输入完整手机号。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                IdentityField(phone, { phone = it }, "手机号", "identity-phone", validation.phoneError.takeIf { submitted }, KeyboardType.Phone, false)
            }
            item { IdentityField(currentPassword, { currentPassword = it }, "当前密码", "identity-current-password", validation.currentPasswordError.takeIf { submitted }, KeyboardType.Password, true) }
            item {
                Button(
                    onClick = {
                        submitted = true
                        AccountIdentitySubmissionPolicy.submit(editedUsername, editedEmail, phone, currentPassword, onSubmit)
                    },
                    enabled = !loading && validation.isValid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (loading) "正在保存…" else "保存账户设置") }
            }
        }
    }
}

@Composable
private fun IdentityField(value: String, onChange: (String) -> Unit, label: String, tag: String, errorText: String?, keyboardType: KeyboardType, password: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = errorText != null,
        supportingText = errorText?.let { { Text(it) } },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { errorText?.let { error(it) }; testTag = tag },
    )
}

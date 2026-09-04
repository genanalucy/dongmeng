package com.verba.interpretation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.brand.BrandTheme
import kotlinx.coroutines.launch

/** Debug-only visual prototype. It has no API, storage, or production navigation wiring. */
class DebugPrototypeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BrandTheme { DebugPrototypeApp() } }
    }
}

private enum class PrototypeScreen(val label: String) {
    LOGIN("登录"), ACCOUNT("账户"), HISTORY("历史"), DETAIL("详情"), ADMIN("后台历史"), SOLO_RECOVERY("同传恢复")
}

@Composable
private fun DebugPrototypeApp() {
    var screen by remember { mutableStateOf(PrototypeScreen.LOGIN) }
    Scaffold(
        snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (screen) {
            PrototypeScreen.LOGIN -> LoginPrototype(Modifier.padding(padding), onAccount = { screen = PrototypeScreen.ACCOUNT })
            PrototypeScreen.ACCOUNT -> AccountPrototype(Modifier.padding(padding), onBack = { screen = PrototypeScreen.LOGIN }, onHistory = { screen = PrototypeScreen.HISTORY })
            PrototypeScreen.HISTORY -> HistoryPrototype(Modifier.padding(padding), onBack = { screen = PrototypeScreen.ACCOUNT }, onDetail = { screen = PrototypeScreen.DETAIL })
            PrototypeScreen.DETAIL -> DetailPrototype(Modifier.padding(padding), onBack = { screen = PrototypeScreen.HISTORY })
            PrototypeScreen.ADMIN -> AdminHistoryPrototype(Modifier.padding(padding), onBack = { screen = PrototypeScreen.ACCOUNT })
            PrototypeScreen.SOLO_RECOVERY -> SoloRecoveryPrototype(Modifier.padding(padding), onBack = { screen = PrototypeScreen.ACCOUNT })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrototypeTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun LoginPrototype(modifier: Modifier, onAccount: () -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("VERBA", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        item { Spacer(Modifier.heightIn(min = 16.dp)) }
        item { Text("欢迎回来", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
        item { Text("登录后继续你的实时翻译，并同步已完成的翻译历史。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("用户名或邮箱", style = MaterialTheme.typography.labelLarge) }
        item { PrototypeField("上次登录账号") }
        item { Text("密码", style = MaterialTheme.typography.labelLarge) }
        item { PrototypeField("输入密码") }
        item { Button(onClick = onAccount, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("登录") } }
        item { Text("没有账号？注册", modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.primary) }
        item { Text("体验预览：所有内容均为样例，不会登录或保存数据。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PrototypeField(text: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPrototype(modifier: Modifier, onBack: () -> Unit, onHistory: () -> Unit) {
    Column(modifier.fillMaxSize()) {
        PrototypeTopBar("账户与权益", onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp)) { Text("体验用户", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text("账户与权益", color = MaterialTheme.colorScheme.onPrimaryContainer) } } }
            item { PrototypeRow("翻译历史", "查看已完成翻译与同步状态", Icons.Outlined.History, onHistory) }
            item { PrototypeRow("账户设置", "修改用户名、邮箱和手机号", Icons.Outlined.ManageAccounts) {} }
            item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(8.dp)); Text("退出登录") } }
        }
    }
}

@Composable
private fun PrototypeRow(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) }, supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).semantics { contentDescription = title },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryPrototype(modifier: Modifier, onBack: () -> Unit, onDetail: () -> Unit) {
    Column(modifier.fillMaxSize()) {
        PrototypeTopBar("翻译历史", onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SyncNotice("已同步 · 已完成翻译会自动保存") }
            item { Text("最近会话", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            item { SessionCard("面对面翻译", "今天 09:41 · 中文 → English · 8 条", "已同步", onDetail) }
            item { SessionCard("单人同传", "昨天 18:06 · 中文 → English · 14 条", "等待同步", onDetail) }
            item { SessionCard("面对面翻译", "9 月 1 日 · English → 中文 · 5 条", "已同步", onDetail) }
            item { OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清空全部历史") } }
        }
    }
}

@Composable
private fun SyncNotice(copy: String) = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Text(copy, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }

@Composable
private fun SessionCard(title: String, detail: String, status: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPrototype(modifier: Modifier, onBack: () -> Unit) {
    Column(modifier.fillMaxSize()) {
        PrototypeTopBar("面对面翻译", onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("今天 09:41 · 中文 → English", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { TurnCard("中文", "请问附近有地铁站吗？", "Is there a subway station nearby?") }
            item { TurnCard("English", "Yes, it is two blocks away.", "有，就在两个街区外。") }
            item { TurnCard("中文", "谢谢你的帮助。", "Thank you for your help.") }
            item { OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth(), colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除此会话") } }
        }
    }
}

@Composable
private fun TurnCard(language: String, original: String, translated: String) = Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(language, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(original, modifier = Modifier.padding(top = 6.dp)); Text(translated, modifier = Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminHistoryPrototype(modifier: Modifier, onBack: () -> Unit) {
    Column(modifier.fillMaxSize()) {
        PrototypeTopBar("用户详情", onBack)
        TabRow(selectedTabIndex = 2) { listOf("概览", "账户", "翻译历史").forEachIndexed { index, title -> Tab(selected = index == 2, onClick = {}, text = { Text(title) }) } }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Text("正在查看该用户的翻译历史。此访问会被自动记录。", modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer) } }
            item { Text("只读 · 已完成文本 · 用户删除后不可查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { SessionCard("面对面翻译", "今天 09:41 · 中文 → English · 8 条", "只读") {} }
            item { SessionCard("单人同传", "昨天 18:06 · 中文 → English · 14 条", "只读") {} }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoloRecoveryPrototype(modifier: Modifier, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize()) {
        PrototypeTopBar("单人同传", onBack)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("中文 → English", color = MaterialTheme.colorScheme.primary); Text("正在翻译…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) } } }
            item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Column(Modifier.padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("翻译连接出现问题", fontWeight = FontWeight.SemiBold) }; Text("恢复后，当前未完成内容将被丢弃；已完成字幕会保留。", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall); OutlinedButton(onClick = { scope.launch { snackbar.showSnackbar("已清理未完成翻译，现在可以重新开始") } }, modifier = Modifier.padding(top = 10.dp)) { Text("恢复翻译") } } } }
            item { TurnCard("已完成", "欢迎参加今天的会议。", "Welcome to today's meeting.") }
        }
        SnackbarHost(snackbar, modifier = Modifier.padding(16.dp))
    }
}

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.BuildConfig
import com.verba.interpretation.update.AppUpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAboutScreen(
    state: AppUpdateState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = when (state) {
        AppUpdateState.Idle, AppUpdateState.Current, is AppUpdateState.Failed -> "检查更新"
        AppUpdateState.Checking -> "正在检查…"
        is AppUpdateState.Available -> "下载并更新"
        is AppUpdateState.Downloading -> "正在下载…"
        is AppUpdateState.ReadyToInstall -> "安装更新"
    }
    val detail = when (state) {
        AppUpdateState.Idle -> "检查是否有新版本。"
        AppUpdateState.Checking -> "正在检查新版本…"
        AppUpdateState.Current -> "当前已是最新版本。"
        is AppUpdateState.Available -> state.update.releaseNotes.ifBlank { "发现新版本 ${state.update.versionName}。" }
        is AppUpdateState.Downloading -> "正在下载并校验更新包，请勿退出。"
        is AppUpdateState.ReadyToInstall -> "更新包已校验完成，请在系统安装器中确认。"
        is AppUpdateState.Failed -> state.message
    }
    val enabled = state !is AppUpdateState.Checking && state !is AppUpdateState.Downloading
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("关于", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp)) {
                        Text("言枢智能翻译", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("版本 ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item {
                Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp)) {
                        Text("检查更新", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        Button(
                            onClick = { if (state is AppUpdateState.Available || state is AppUpdateState.ReadyToInstall) onDownload() else onCheck() },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp).semantics { contentDescription = action },
                        ) { Text(action) }
                    }
                }
            }
        }
    }
}

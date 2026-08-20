package com.verba.interpretation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.InterpretationViewModel
import com.verba.interpretation.ui.SessionPhase

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { VerbaApp() } }
    }
}

private enum class Screen { HOME, SOLO, FACE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerbaApp(viewModel: InterpretationViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    Scaffold(topBar = { TopAppBar(title = { Text("Verba 同传") }, navigationIcon = {
        if (screen != Screen.HOME) OutlinedButton(onClick = {
            if (screen == Screen.SOLO) viewModel.cancel()
            screen = Screen.HOME
        }) { Text("返回") }
    }) }) { padding ->
        when (screen) {
            Screen.HOME -> Home(Modifier.padding(padding), onSolo = { screen = Screen.SOLO }, onFace = { screen = Screen.FACE })
            Screen.SOLO -> Solo(Modifier.padding(padding), viewModel)
            Screen.FACE -> Placeholder(Modifier.padding(padding), "面对面翻译")
        }
    }
}

@Composable
private fun Home(modifier: Modifier, onSolo: () -> Unit, onFace: () -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("选择翻译模式", style = MaterialTheme.typography.headlineMedium)
        Card(onClick = onSolo, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) { Text("单人同传", style = MaterialTheme.typography.titleLarge); Text("8 秒 Turn、实时字幕与定向耳机播放") }
        }
        Card(onClick = onFace, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) { Text("面对面", style = MaterialTheme.typography.titleLarge); Text("入口已预留") }
        }
    }
}

@Composable
private fun Placeholder(modifier: Modifier, title: String) {
    Column(modifier.fillMaxSize().padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("此模式为占位入口，后续版本实现。")
    }
}

@Composable
private fun Solo(modifier: Modifier, viewModel: InterpretationViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.cancel()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else viewModel.microphonePermissionDenied()
    }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("单人同传", style = MaterialTheme.typography.headlineMedium)
            Text("状态：${state.phase}")
        }
        item {
            Text("目标语言")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.targetLanguage == "en", onClick = { viewModel.setTarget("en") }, label = { Text("English") })
                FilterChip(selected = state.targetLanguage == "zh", onClick = { viewModel.setTarget("zh") }, label = { Text("中文") })
            }
        }
        item {
            Text("播放路由")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlaybackRoute.entries.forEach { route ->
                    FilterChip(selected = state.route == route, onClick = { viewModel.setRoute(route) }, label = { Text(route.label()) })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.phase) {
                    SessionPhase.IDLE -> Button(onClick = {
                        if (viewModel.getApplication<android.app.Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.start()
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("开始") }
                    SessionPhase.STARTING, SessionPhase.RUNNING -> Button(onClick = viewModel::pause) { Text("暂停") }
                    SessionPhase.PAUSED -> Button(onClick = viewModel::resume) { Text("恢复") }
                    SessionPhase.ERROR -> Button(onClick = viewModel::clearError) { Text("重置") }
                    SessionPhase.STOPPING -> Text("正在结束…")
                }
                if (state.phase != SessionPhase.IDLE && state.phase != SessionPhase.STOPPING) OutlinedButton(onClick = viewModel::finish) { Text("结束") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item { Text("字幕列表", style = MaterialTheme.typography.titleLarge) }
        items(state.turns, key = { it.id }) { turn ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Turn ${turn.id} · ${turn.sourceLanguage} → ${turn.targetLanguage}${if (turn.finished) " · 完成" else ""}")
                    Text("原文：${turn.sourceText.ifEmpty { "…" }}")
                    Text("译文：${turn.translatedText.ifEmpty { "…" }}")
                }
            }
        }
    }
}

private fun PlaybackRoute.label(): String = when (this) {
    PlaybackRoute.LEFT -> "左耳"
    PlaybackRoute.RIGHT -> "右耳"
    PlaybackRoute.BOTH -> "双耳"
    PlaybackRoute.CAPTIONS -> "仅字幕"
}

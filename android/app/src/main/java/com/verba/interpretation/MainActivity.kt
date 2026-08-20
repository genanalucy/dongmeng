package com.verba.interpretation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceTurn
import com.verba.interpretation.ui.FaceToFaceViewModel
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
        if (screen != Screen.HOME) OutlinedButton(onClick = { screen = Screen.HOME }) { Text("返回") }
    }) }) { padding ->
        when (screen) {
            Screen.HOME -> Home(Modifier.padding(padding), onSolo = { screen = Screen.SOLO }, onFace = { screen = Screen.FACE })
            Screen.SOLO -> Solo(Modifier.padding(padding), viewModel)
            Screen.FACE -> FaceToFace(Modifier.padding(padding))
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
            Column(Modifier.padding(20.dp)) { Text("面对面", style = MaterialTheme.typography.titleLarge); Text("手动 PTT 或自动交替，左右耳定向播放") }
        }
    }
}

@Composable
private fun FaceToFace(modifier: Modifier, faceViewModel: FaceToFaceViewModel = viewModel()) {
    val state by faceViewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, faceViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) faceViewModel.cancel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            faceViewModel.cancel()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            !granted -> faceViewModel.microphonePermissionDenied()
            state.mode == FaceToFaceMode.AUTO -> faceViewModel.startAuto()
            // Manual PTT must begin from a fresh pointer-down after the permission dialog closes.
            else -> Unit
        }
    }
    val hasPermission = {
        faceViewModel.getApplication<android.app.Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val requestOrRun: (() -> Unit) -> Unit = { action ->
        if (hasPermission()) action() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("面对面翻译", style = MaterialTheme.typography.headlineMedium)
            Text("状态：${state.statusLabel()}")
            Text("左侧中文 → English（右耳） · 右侧 English → 中文（左耳）")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == FaceToFaceMode.MANUAL,
                    enabled = state.phase == FaceToFacePhase.IDLE,
                    onClick = { faceViewModel.setMode(FaceToFaceMode.MANUAL) },
                    label = { Text("手动 PTT") },
                )
                FilterChip(
                    selected = state.mode == FaceToFaceMode.AUTO,
                    enabled = state.phase == FaceToFacePhase.IDLE,
                    onClick = { faceViewModel.setMode(FaceToFaceMode.AUTO) },
                    label = { Text("自动交替") },
                )
            }
        }
        item {
            if (state.mode == FaceToFaceMode.AUTO) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (state.phase) {
                        FaceToFacePhase.IDLE -> Button(onClick = { requestOrRun(faceViewModel::startAuto) }) { Text("开始（默认左侧）") }
                        FaceToFacePhase.LISTENING -> OutlinedButton(onClick = faceViewModel::stopAuto) { Text("停止采集") }
                        FaceToFacePhase.ERROR -> Button(onClick = faceViewModel::clearError) { Text("重置") }
                        FaceToFacePhase.PROCESSING, FaceToFacePhase.STOPPING -> Text("后台 Turn 正在完成…")
                    }
                }
            } else if (state.phase == FaceToFacePhase.ERROR) {
                Button(onClick = faceViewModel::clearError) { Text("重置") }
            } else {
                Text(if (state.manualInputLocked) "请等待当前 Turn 完成并播放排空" else "按住说话，松开结束；最长 25 秒")
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FaceTalkButton(
                    modifier = Modifier.weight(1f),
                    side = FaceToFaceSide.LEFT,
                    state = state,
                    onPress = {
                        if (state.mode == FaceToFaceMode.MANUAL) requestOrRun { faceViewModel.manualPress(FaceToFaceSide.LEFT) }
                    },
                    onRelease = { if (state.mode == FaceToFaceMode.MANUAL) faceViewModel.manualRelease() },
                )
                FaceTalkButton(
                    modifier = Modifier.weight(1f),
                    side = FaceToFaceSide.RIGHT,
                    state = state,
                    onPress = {
                        when (state.mode) {
                            FaceToFaceMode.MANUAL -> requestOrRun { faceViewModel.manualPress(FaceToFaceSide.RIGHT) }
                            FaceToFaceMode.AUTO -> if (state.phase == FaceToFacePhase.LISTENING) faceViewModel.pressRightAuto()
                        }
                    },
                    onRelease = {
                        when (state.mode) {
                            FaceToFaceMode.MANUAL -> faceViewModel.manualRelease()
                            FaceToFaceMode.AUTO -> if (state.phase == FaceToFacePhase.LISTENING) faceViewModel.releaseRightAuto()
                        }
                    },
                )
            }
        }
        item { Text("左侧字幕", style = MaterialTheme.typography.titleLarge) }
        val leftTurns = state.turns.filter { it.side == FaceToFaceSide.LEFT }
        if (leftTurns.isEmpty()) item { Text("暂无") } else items(leftTurns, key = { "left-${it.id}" }) { FaceSubtitleCard(it) }
        item { Text("右侧字幕", style = MaterialTheme.typography.titleLarge) }
        val rightTurns = state.turns.filter { it.side == FaceToFaceSide.RIGHT }
        if (rightTurns.isEmpty()) item { Text("暂无") } else items(rightTurns, key = { "right-${it.id}" }) { FaceSubtitleCard(it) }
    }
}

@Composable
private fun FaceTalkButton(
    modifier: Modifier,
    side: FaceToFaceSide,
    state: FaceToFaceState,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val enabled = when (state.mode) {
        FaceToFaceMode.MANUAL -> state.phase == FaceToFacePhase.IDLE || (state.phase == FaceToFacePhase.LISTENING && state.activeSide == side)
        FaceToFaceMode.AUTO -> state.phase == FaceToFacePhase.LISTENING && side == FaceToFaceSide.RIGHT
    }
    val active = state.activeSide == side
    Card(
        modifier = modifier
            .heightIn(min = 132.dp)
            .pointerInput(enabled, state.mode) {
                if (enabled) detectTapGestures(onPress = {
                    onPress()
                    try { awaitRelease() } finally { onRelease() }
                })
            },
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (side == FaceToFaceSide.LEFT) "左侧" else "右侧", style = MaterialTheme.typography.titleLarge)
            Text(if (side == FaceToFaceSide.LEFT) "中文" else "English")
            Text(
                when {
                    active -> "正在收音"
                    state.mode == FaceToFaceMode.AUTO && side == FaceToFaceSide.LEFT -> "默认自动收音"
                    state.mode == FaceToFaceMode.AUTO -> "按住抢话"
                    enabled -> "按住说话"
                    else -> "已锁定"
                },
            )
        }
    }
}

@Composable
private fun FaceSubtitleCard(turn: FaceToFaceTurn) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Turn ${turn.id} · ${turn.sourceLanguage} → ${turn.targetLanguage}${if (turn.finished) " · 完成" else ""}")
            Text("原文：${turn.sourceText.ifEmpty { "…" }}")
            Text("译文：${turn.translatedText.ifEmpty { "…" }}")
        }
    }
}

private fun FaceToFaceState.statusLabel(): String = when (phase) {
    FaceToFacePhase.IDLE -> "空闲"
    FaceToFacePhase.LISTENING -> "${if (activeSide == FaceToFaceSide.LEFT) "左侧" else "右侧"}收音中"
    FaceToFacePhase.PROCESSING -> "等待翻译与播放完成"
    FaceToFacePhase.STOPPING -> "采集已停止，后台处理中"
    FaceToFacePhase.ERROR -> "错误"
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

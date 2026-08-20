package com.verba.interpretation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.brand.BrandConfig
import com.verba.interpretation.brand.BrandTheme
import com.verba.interpretation.ui.ChatFollowEvent
import com.verba.interpretation.ui.ChatFollowPolicy
import com.verba.interpretation.ui.ChatFollowState
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceTurn
import com.verba.interpretation.ui.FaceToFaceViewModel
import com.verba.interpretation.ui.InterpretationViewModel
import com.verba.interpretation.ui.SessionPhase
import com.verba.interpretation.ui.SubtitleTurn

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BrandTheme { InterpretationApp() } }
    }
}

private enum class Screen { HOME, SOLO, FACE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterpretationApp(viewModel: InterpretationViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandConfig.Logo(Modifier.size(36.dp))
                        Column {
                            Text(BrandConfig.appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (screen == Screen.HOME) Text(BrandConfig.tagline, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        TextButton(
                            onClick = { screen = Screen.HOME },
                            modifier = Modifier.semantics { contentDescription = "返回模式选择" },
                        ) { Text("返回") }
                    }
                },
            )
        },
    ) { padding ->
        when (screen) {
            Screen.HOME -> Home(Modifier.padding(padding), onSolo = { screen = Screen.SOLO }, onFace = { screen = Screen.FACE })
            Screen.SOLO -> Solo(Modifier.padding(padding), viewModel)
            Screen.FACE -> FaceToFace(Modifier.padding(padding))
        }
    }
}

@Composable
private fun Home(modifier: Modifier, onSolo: () -> Unit, onFace: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("听懂彼此，从容交流", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("选择适合当下场景的翻译方式。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        ModeCard(
            title = "单人同传",
            description = "连续收听演讲、会议与视频，实时查看双语字幕。",
            actionLabel = "进入单人同传",
            onClick = onSolo,
        )
        ModeCard(
            title = "面对面翻译",
            description = "双方自然交谈，按说话方向显示字幕并定向播放。",
            actionLabel = "进入面对面翻译",
            onClick = onFace,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "语音仅在翻译期间处理，离开页面会停止当前会话。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeCard(title: String, description: String, actionLabel: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$actionLabel。$description" },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("开始", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FaceToFace(modifier: Modifier, faceViewModel: FaceToFaceViewModel = viewModel()) {
    val state by faceViewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, faceViewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) faceViewModel.cancel() }
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
            else -> Unit
        }
    }
    val hasPermission = {
        faceViewModel.getApplication<android.app.Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val requestOrRun: (() -> Unit) -> Unit = { action ->
        if (hasPermission()) action() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        PageHeading("面对面翻译", state.statusLabel())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.mode == FaceToFaceMode.MANUAL,
                enabled = state.phase == FaceToFacePhase.IDLE,
                onClick = { faceViewModel.setMode(FaceToFaceMode.MANUAL) },
                label = { Text("手动按住说话") },
            )
            FilterChip(
                selected = state.mode == FaceToFaceMode.AUTO,
                enabled = state.phase == FaceToFacePhase.IDLE,
                onClick = { faceViewModel.setMode(FaceToFaceMode.AUTO) },
                label = { Text("自动交替") },
            )
        }
        FaceSessionAction(state, requestOrRun, faceViewModel)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FaceTalkButton(
                modifier = Modifier.weight(1f),
                side = FaceToFaceSide.LEFT,
                state = state,
                onPress = { if (state.mode == FaceToFaceMode.MANUAL) requestOrRun { faceViewModel.manualPress(FaceToFaceSide.LEFT) } },
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
        Text("实时字幕", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
        FaceTranscriptFeed(state.turns, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun FaceSessionAction(
    state: FaceToFaceState,
    requestOrRun: (() -> Unit) -> Unit,
    faceViewModel: FaceToFaceViewModel,
) {
    if (state.mode == FaceToFaceMode.AUTO) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state.phase) {
                FaceToFacePhase.IDLE -> Button(onClick = { requestOrRun(faceViewModel::startAuto) }) { Text("开始自动翻译") }
                FaceToFacePhase.LISTENING -> OutlinedButton(onClick = faceViewModel::stopAuto) { Text("停止采集") }
                FaceToFacePhase.ERROR -> Button(onClick = faceViewModel::clearError) { Text("重置") }
                FaceToFacePhase.PROCESSING, FaceToFacePhase.STOPPING -> Text("正在完成剩余字幕")
            }
        }
    } else if (state.phase == FaceToFacePhase.ERROR) {
        Button(onClick = faceViewModel::clearError) { Text("重置") }
    } else {
        Text(
            if (state.manualInputLocked) "正在完成本轮翻译，请稍候" else "按住一侧说话，松开即翻译，最长 25 秒",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    val sideLabel = if (side == FaceToFaceSide.LEFT) "左侧，中文" else "右侧，English"
    val actionLabel = when {
        active -> "正在收音"
        state.mode == FaceToFaceMode.AUTO && side == FaceToFaceSide.LEFT -> "默认自动收音"
        state.mode == FaceToFaceMode.AUTO -> "按住抢话"
        enabled -> "按住说话"
        else -> "当前不可用"
    }
    Card(
        modifier = modifier
            .heightIn(min = 104.dp)
            .semantics {
                role = Role.Button
                contentDescription = sideLabel
                stateDescription = actionLabel
            }
            .pointerInput(enabled, state.mode) {
                if (enabled) detectTapGestures(onPress = {
                    onPress()
                    try { awaitRelease() } finally { onRelease() }
                })
            },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (side == FaceToFaceSide.LEFT) "左侧" else "右侧", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(if (side == FaceToFaceSide.LEFT) "中文" else "English", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FaceTranscriptFeed(turns: List<FaceToFaceTurn>, modifier: Modifier = Modifier) {
    TranscriptFeed(
        itemCount = turns.size,
        updateToken = turns.transcriptToken { it.id to listOf(it.sourceText, it.translatedText, it.finished).hashCode() },
        modifier = modifier,
        emptyLabel = "对话字幕会显示在这里",
    ) {
        items(turns, key = { it.id }) { turn -> FaceSubtitleBubble(turn) }
    }
}

@Composable
private fun FaceSubtitleBubble(turn: FaceToFaceTurn) {
    val isRight = turn.side == FaceToFaceSide.RIGHT
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalAlignment = if (isRight) Alignment.End else Alignment.Start,
    ) {
        Text(
            if (isRight) "右侧 · English → 中文" else "左侧 · 中文 → English",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(0.86f).semantics {
                contentDescription = "${if (isRight) "右侧" else "左侧"}字幕。原文${turn.sourceText.ifEmpty { "等待识别" }}。译文${turn.translatedText.ifEmpty { "等待翻译" }}"
            },
            shape = RoundedCornerShape(18.dp),
            color = if (isRight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            SubtitleContent(turn.sourceText, turn.translatedText, turn.finished)
        }
    }
}

private fun FaceToFaceState.statusLabel(): String = when (phase) {
    FaceToFacePhase.IDLE -> "准备就绪"
    FaceToFacePhase.LISTENING -> "${if (activeSide == FaceToFaceSide.LEFT) "左侧" else "右侧"}收音中"
    FaceToFacePhase.PROCESSING -> "正在翻译并播放"
    FaceToFacePhase.STOPPING -> "正在完成剩余内容"
    FaceToFacePhase.ERROR -> "需要处理错误"
}

@Composable
private fun Solo(modifier: Modifier, viewModel: InterpretationViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.cancel() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.cancel()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else viewModel.microphonePermissionDenied()
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        PageHeading("单人同传", state.phase.label())
        Text("目标语言", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.targetLanguage == "en", onClick = { viewModel.setTarget("en") }, label = { Text("English") })
            FilterChip(selected = state.targetLanguage == "zh", onClick = { viewModel.setTarget("zh") }, label = { Text("中文") })
        }
        Text("播放位置", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaybackRoute.entries.forEach { route ->
                FilterChip(selected = state.route == route, onClick = { viewModel.setRoute(route) }, label = { Text(route.label()) })
            }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state.phase) {
                SessionPhase.IDLE -> Button(onClick = {
                    if (viewModel.getApplication<android.app.Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.start()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("开始翻译") }
                SessionPhase.STARTING, SessionPhase.RUNNING -> Button(onClick = viewModel::pause) { Text("暂停") }
                SessionPhase.PAUSED -> Button(onClick = viewModel::resume) { Text("继续") }
                SessionPhase.ERROR -> Button(onClick = viewModel::clearError) { Text("重置") }
                SessionPhase.STOPPING -> Text("正在结束")
            }
            if (state.phase != SessionPhase.IDLE && state.phase != SessionPhase.STOPPING) {
                OutlinedButton(onClick = viewModel::finish) { Text("结束") }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 6.dp)) }
        Text("实时字幕", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))
        SoloTranscriptFeed(state.turns, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun SoloTranscriptFeed(turns: List<SubtitleTurn>, modifier: Modifier = Modifier) {
    TranscriptFeed(
        itemCount = turns.size,
        updateToken = turns.transcriptToken { it.id to listOf(it.sourceText, it.translatedText, it.finished).hashCode() },
        modifier = modifier,
        emptyLabel = "开始翻译后，双语字幕会显示在这里",
    ) {
        items(turns, key = { it.id }) { turn ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalAlignment = Alignment.Start) {
                Text(
                    "${turn.sourceLanguage.languageLabel()} → ${turn.targetLanguage.languageLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(0.92f).semantics {
                        contentDescription = "原文${turn.sourceText.ifEmpty { "等待识别" }}。译文${turn.translatedText.ifEmpty { "等待翻译" }}"
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    SubtitleContent(turn.sourceText, turn.translatedText, turn.finished)
                }
            }
        }
    }
}

@Composable
private fun SubtitleContent(sourceText: String, translatedText: String, finished: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(sourceText.ifEmpty { "正在聆听…" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            translatedText.ifEmpty { "等待翻译…" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        if (finished) Text("已完成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TranscriptFeed(
    itemCount: Int,
    updateToken: Int,
    modifier: Modifier,
    emptyLabel: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    var followState by remember { mutableStateOf(ChatFollowState()) }
    var previousItemCount by remember { mutableIntStateOf(itemCount) }
    var previousUpdateToken by remember { mutableIntStateOf(updateToken) }
    var scrollRequest by remember { mutableIntStateOf(0) }
    val atLatest by remember(listState) { derivedStateOf { !listState.canScrollForward } }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(Unit) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }
    LaunchedEffect(isUserDragging, atLatest) {
        followState = when {
            atLatest -> ChatFollowPolicy.reduce(followState, ChatFollowEvent.UserReachedLatest)
            isUserDragging -> ChatFollowPolicy.reduce(followState, ChatFollowEvent.UserLeftLatest)
            else -> followState
        }
    }
    LaunchedEffect(updateToken, itemCount) {
        if (updateToken != previousUpdateToken) {
            val addedItems = (itemCount - previousItemCount).coerceAtLeast(1)
            val shouldFollow = followState.followsLatest
            followState = ChatFollowPolicy.reduce(followState, ChatFollowEvent.TranscriptChanged(addedItems))
            previousUpdateToken = updateToken
            previousItemCount = itemCount
            if (shouldFollow && itemCount > 0) listState.scrollToItem(itemCount - 1)
        }
    }
    LaunchedEffect(scrollRequest) {
        if (scrollRequest > 0 && itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
            followState = ChatFollowPolicy.reduce(followState, ChatFollowEvent.UserReachedLatest)
        }
    }

    Box(modifier.clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(MaterialTheme.colorScheme.surface)) {
        if (itemCount == 0) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
                Text(emptyLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "实时字幕聊天记录，可向上滑动查看历史" },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                content = content,
            )
        }
        if (!followState.followsLatest) {
            Button(
                onClick = { scrollRequest += 1 },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).semantics {
                    contentDescription = if (followState.unseenUpdates > 0) "回到最新，${followState.unseenUpdates} 条新字幕" else "回到最新字幕"
                },
            ) {
                Text(if (followState.unseenUpdates > 0) "回到最新 · ${followState.unseenUpdates} 条新字幕" else "回到最新")
            }
        }
    }
}

@Composable
private fun PageHeading(title: String, status: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(BrandConfig.shortName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private inline fun <T> List<T>.transcriptToken(transform: (T) -> Any): Int = fold(1) { result, item -> 31 * result + transform(item).hashCode() }

private fun SessionPhase.label(): String = when (this) {
    SessionPhase.IDLE -> "准备就绪"
    SessionPhase.STARTING -> "正在连接"
    SessionPhase.RUNNING -> "翻译中"
    SessionPhase.PAUSED -> "已暂停"
    SessionPhase.STOPPING -> "正在结束"
    SessionPhase.ERROR -> "需要处理错误"
}

private fun String.languageLabel(): String = when (this) {
    "zh" -> "中文"
    "en" -> "English"
    else -> this
}

private fun PlaybackRoute.label(): String = when (this) {
    PlaybackRoute.LEFT -> "左耳"
    PlaybackRoute.RIGHT -> "右耳"
    PlaybackRoute.BOTH -> "双耳"
    PlaybackRoute.CAPTIONS -> "仅字幕"
}

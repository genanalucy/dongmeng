package com.verba.interpretation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.brand.BrandConfig
import com.verba.interpretation.brand.BrandTheme
import com.verba.interpretation.protocol.EndpointSettings
import com.verba.interpretation.ui.ChatFollowEvent
import com.verba.interpretation.ui.ChatFollowPolicy
import com.verba.interpretation.ui.ChatFollowState
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceTurn
import com.verba.interpretation.ui.FaceToFaceViewModel
import com.verba.interpretation.ui.HistoryEmptyStatePolicy
import com.verba.interpretation.ui.HistoryFilter
import com.verba.interpretation.ui.InterpretationViewModel
import com.verba.interpretation.ui.ProductDestination
import com.verba.interpretation.ui.ProductNavigationPolicy
import com.verba.interpretation.ui.ProductScreen
import com.verba.interpretation.ui.SessionPhase
import com.verba.interpretation.ui.SubtitleTurn
import com.verba.interpretation.ui.TranslationLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BrandTheme { InterpretationApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterpretationApp(viewModel: InterpretationViewModel = viewModel()) {
    var screen by remember { mutableStateOf(ProductScreen.TRANSLATE) }
    val interpretationState by viewModel.state.collectAsStateWithLifecycle()
    val showBottomBar = ProductNavigationPolicy.showsBottomBar(screen)
    BackHandler(enabled = screen != ProductScreen.TRANSLATE) {
        screen = ProductNavigationPolicy.exitTarget(screen)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showBottomBar) {
                ProductTopBar(screen)
            }
        },
        bottomBar = {
            if (showBottomBar) {
                ProductNavigationBar(
                    selected = ProductNavigationPolicy.selectedDestination(screen),
                    onSelect = { screen = ProductNavigationPolicy.screenFor(it) },
                )
            }
        },
    ) { padding ->
        when (screen) {
            ProductScreen.TRANSLATE -> TranslationHome(
                modifier = Modifier.padding(padding),
                interpretationPhase = interpretationState.phase,
                onLanguagePair = { source, target ->
                    viewModel.setLanguages(source, target)
                    screen = ProductScreen.INTERPRETATION_WORKBENCH
                },
                onInterpretation = { screen = ProductScreen.INTERPRETATION_WORKBENCH },
                onFaceToFace = { screen = ProductScreen.FACE_TO_FACE_WORKBENCH },
            )
            ProductScreen.INTERPRETATION_WORKBENCH -> SoloWorkbench(
                modifier = Modifier.padding(padding),
                viewModel = viewModel,
                onExit = { screen = ProductNavigationPolicy.exitTarget(screen) },
            )
            ProductScreen.FACE_TO_FACE_WORKBENCH -> FaceToFaceWorkbench(
                modifier = Modifier.padding(padding),
                onExit = { screen = ProductNavigationPolicy.exitTarget(screen) },
            )
            ProductScreen.HISTORY -> HistoryPage(Modifier.padding(padding))
            ProductScreen.PROFILE -> ProfilePage(
                modifier = Modifier.padding(padding),
                onEndpointSettings = { screen = ProductScreen.ENDPOINT_SETTINGS },
            )
            ProductScreen.ENDPOINT_SETTINGS -> EndpointSettingsPage(
                modifier = Modifier.padding(padding),
                onBack = { screen = ProductNavigationPolicy.exitTarget(screen) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductTopBar(screen: ProductScreen) {
    val title = when (screen) {
        ProductScreen.TRANSLATE -> BrandConfig.appName
        ProductScreen.HISTORY -> "历史"
        ProductScreen.PROFILE -> "我的"
        else -> ""
    }
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (screen == ProductScreen.TRANSLATE) BrandConfig.Logo(Modifier.size(36.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (screen == ProductScreen.TRANSLATE) {
                        Text(
                            BrandConfig.tagline,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun ProductNavigationBar(selected: ProductDestination, onSelect: (ProductDestination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        ProductDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon(), contentDescription = null) },
                label = { Text(destination.label, maxLines = 1) },
                modifier = Modifier.semantics {
                    contentDescription = "${destination.label}页"
                    stateDescription = if (selected == destination) "已选择" else "未选择"
                },
            )
        }
    }
}

private fun ProductDestination.icon(): ImageVector = when (this) {
    ProductDestination.TRANSLATE -> Icons.Outlined.Translate
    ProductDestination.INTERPRETATION -> Icons.Outlined.GraphicEq
    ProductDestination.FACE_TO_FACE -> Icons.Outlined.Groups
    ProductDestination.HISTORY -> Icons.Outlined.History
    ProductDestination.PROFILE -> Icons.Outlined.PersonOutline
}

@Composable
private fun TranslationHome(
    modifier: Modifier,
    interpretationPhase: SessionPhase,
    onLanguagePair: (String, String) -> Unit,
    onInterpretation: () -> Unit,
    onFaceToFace: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ConnectionStatusCard(interpretationPhase) }
        item { SectionLabel("语言快捷入口", "常用语言对") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LanguagePairCard(
                    modifier = Modifier.weight(1f),
                    source = "中文",
                    target = "English",
                    onClick = { onLanguagePair("zh", "en") },
                )
                LanguagePairCard(
                    modifier = Modifier.weight(1f),
                    source = "English",
                    target = "中文",
                    onClick = { onLanguagePair("en", "zh") },
                )
            }
        }
        item { SectionLabel("选择模式", "适合不同交流场景") }
        item {
            ProductModeCard(
                icon = Icons.Outlined.GraphicEq,
                eyebrow = "连续聆听",
                title = "实时同传",
                description = "演讲、会议或视频的连续双语字幕，可选择耳机播放位置。",
                actionLabel = "进入同传工作台",
                onClick = onInterpretation,
            )
        }
        item {
            ProductModeCard(
                icon = Icons.Outlined.Groups,
                eyebrow = "双向交流",
                title = "面对面翻译",
                description = "双方按方向自然交谈，字幕分侧显示并定向播放。",
                actionLabel = "进入面对面工作台",
                onClick = onFaceToFace,
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "语音仅在翻译期间处理。结束工作台或离开应用会停止当前会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(phase: SessionPhase) {
    val connected = phase == SessionPhase.RUNNING
    val connecting = phase == SessionPhase.STARTING || phase == SessionPhase.STOPPING
    val title = when {
        connected -> "实时服务已连接"
        connecting -> "正在连接实时服务"
        else -> "服务待连接"
    }
    val supporting = when {
        connected -> "同传会话正在进行"
        connecting -> "正在建立或结束安全会话"
        else -> "开始翻译时自动连接"
    }
    Surface(
        color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = if (connected) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "连接状态：$title。$supporting" },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Icon(
                    Icons.Outlined.CloudQueue,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp).size(20.dp),
                    tint = if (connected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(
                    if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
    }
}

@Composable
private fun LanguagePairCard(modifier: Modifier, source: String, target: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 126.dp).semantics {
            contentDescription = "$source 翻译为 $target，进入同传工作台"
        },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(source, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProductModeCard(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$actionLabel。$description" },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(13.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 5.dp))
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SectionLabel(title: String, supporting: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(supporting, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkbenchHeader(title: String, status: String, onExit: () -> Unit, onMore: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit, modifier = Modifier.semantics { contentDescription = "退出$title" }) {
            Icon(Icons.Filled.Close, contentDescription = null)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (onMore != null) {
            IconButton(onClick = onMore, modifier = Modifier.semantics { contentDescription = "${title}更多选项" }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
        } else {
            Text(BrandConfig.shortName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun SoloWorkbench(modifier: Modifier, viewModel: InterpretationViewModel, onExit: () -> Unit) {
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
    val startWithPermission = {
        if (viewModel.getApplication<android.app.Application>().checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.start()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(modifier.fillMaxSize()) {
        WorkbenchHeader("实时同传", state.phase.label(), onExit)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (state.phase == SessionPhase.IDLE) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("翻译语言", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::swapLanguages) { Text("交换方向") }
                }
                TranslationLanguage.entries.chunked(2).forEach { languages ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        languages.forEach { language ->
                            LanguageSelector(
                                modifier = Modifier.weight(1f),
                                selected = state.targetLanguage == language.code,
                                title = language.displayName,
                                enabled = language.code != state.sourceLanguage,
                                onClick = { viewModel.setLanguages(state.sourceLanguage, language.code) },
                            )
                        }
                        if (languages.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text(
                    "源语言：${TranslationLanguage.displayName(state.sourceLanguage)}。法语和越南语由 Qwen 实时服务处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            WorkbenchSummary(
                "${TranslationLanguage.displayName(state.sourceLanguage)} → ${TranslationLanguage.displayName(state.targetLanguage)}",
                "双耳播放",
                Icons.Outlined.Headphones,
                Modifier.padding(top = if (state.phase == SessionPhase.IDLE) 18.dp else 4.dp),
            )
            state.error?.let { ErrorSurface(it, Modifier.padding(top = 12.dp)) }
            TranscriptTitle("实时字幕", state.turns.size, Modifier.padding(top = 20.dp))
            SoloTranscriptFeed(state.turns, Modifier.fillMaxWidth().weight(1f))
        }
        WorkbenchActionDock {
            SoloPrimaryAction(
                phase = state.phase,
                startWithPermission = startWithPermission,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.phase != SessionPhase.IDLE && state.phase != SessionPhase.STOPPING) {
                TextButton(onClick = viewModel::finish, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("结束同传")
                }
            }
        }
    }
}

@Composable
private fun WorkbenchSummary(title: String, detail: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(9.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(" · $detail", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkbenchActionDock(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
private fun LanguageSelector(modifier: Modifier, selected: Boolean, title: String, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            disabledContainerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SoloPrimaryAction(phase: SessionPhase, startWithPermission: () -> Unit, viewModel: InterpretationViewModel, modifier: Modifier = Modifier) {
    when (phase) {
        SessionPhase.IDLE -> Button(onClick = startWithPermission, modifier = modifier.height(52.dp)) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("开始同传")
        }
        SessionPhase.STARTING, SessionPhase.RUNNING -> Button(onClick = viewModel::pause, modifier = modifier.height(52.dp)) {
            Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("暂停")
        }
        SessionPhase.PAUSED -> Button(onClick = viewModel::resume, modifier = modifier.height(52.dp)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("继续")
        }
        SessionPhase.ERROR -> Button(onClick = viewModel::clearError, modifier = modifier.height(52.dp)) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("重置")
        }
        SessionPhase.STOPPING -> Text("正在结束并完成剩余字幕", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FaceToFaceWorkbench(
    modifier: Modifier,
    onExit: () -> Unit,
    faceViewModel: FaceToFaceViewModel = viewModel(),
) {
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

    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Box {
            WorkbenchHeader("面对面翻译", state.statusLabel(), onExit, onMore = { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                DropdownMenuItem(
                    text = { Text("按住说话") },
                    leadingIcon = { if (state.mode == FaceToFaceMode.MANUAL) Icon(Icons.Filled.Mic, null) },
                    onClick = { faceViewModel.setMode(FaceToFaceMode.MANUAL); menuOpen = false },
                    enabled = state.phase == FaceToFacePhase.IDLE,
                )
                DropdownMenuItem(
                    text = { Text("连续翻译") },
                    leadingIcon = { if (state.mode == FaceToFaceMode.AUTO) Icon(Icons.Outlined.GraphicEq, null) },
                    onClick = { faceViewModel.setMode(FaceToFaceMode.AUTO); menuOpen = false },
                    enabled = state.phase == FaceToFacePhase.IDLE,
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                val landscapeWorkbench = maxWidth >= 700.dp
                if (landscapeWorkbench) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (state.phase == FaceToFacePhase.IDLE) FaceLanguageBubbles(state, faceViewModel)
                            state.error?.let { ErrorSurface(it, Modifier.padding(top = 12.dp)) }
                            Spacer(Modifier.weight(1f))
                            WorkbenchActionDock { FaceActionControls(state, requestOrRun, faceViewModel) }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            TranscriptTitle("双方字幕", state.turns.size)
                            FaceTranscriptFeed(turns = state.turns, activeSide = state.activeSide, captureLevel = state.captureLevel, modifier = Modifier.fillMaxSize())
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (state.phase == FaceToFacePhase.IDLE) FaceLanguageBubbles(state, faceViewModel)
                        state.error?.let { ErrorSurface(it, Modifier.padding(top = 12.dp)) }
                        FaceTranscriptFeed(
                            turns = state.turns,
                            activeSide = state.activeSide,
                            captureLevel = state.captureLevel,
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                        )
                        WorkbenchActionDock { FaceActionControls(state, requestOrRun, faceViewModel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FaceLanguageBubbles(state: FaceToFaceState, faceViewModel: FaceToFaceViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        FaceLanguageBubble(
            side = FaceToFaceSide.RIGHT,
            language = state.rightLanguage,
            otherLanguage = state.leftLanguage,
            active = state.activeSide == FaceToFaceSide.RIGHT && state.captureActive,
            level = state.captureLevel,
            modifier = Modifier.align(Alignment.End),
            onSelect = { faceViewModel.setLanguages(state.leftLanguage, it) },
        )
        Spacer(Modifier.height(12.dp))
        FaceLanguageBubble(
            side = FaceToFaceSide.LEFT,
            language = state.leftLanguage,
            otherLanguage = state.rightLanguage,
            active = state.activeSide == FaceToFaceSide.LEFT && state.captureActive,
            level = state.captureLevel,
            modifier = Modifier.align(Alignment.Start),
            onSelect = { faceViewModel.setLanguages(it, state.rightLanguage) },
        )
    }
}

@Composable
private fun FaceLanguageBubble(
    side: FaceToFaceSide,
    language: String,
    otherLanguage: String,
    active: Boolean,
    level: Float,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val targetScale = if (active) 1f + level.coerceIn(0f, 1f) * 0.08f else 1f
    val scale by animateFloatAsState(targetScale, animationSpec = tween(durationMillis = if (targetScale > 1f) 90 else 220), label = "captureBubbleScale")
    val accent = if (side == FaceToFaceSide.LEFT) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFFB4765A)
    Surface(
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(24.dp),
        color = if (active) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = if (active) 0.45f else 0.18f)),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(0.dp)) {
                    Text(TranslationLanguage.displayName(language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("⌄", modifier = Modifier.padding(start = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    TranslationLanguage.entries.filter { it.code != otherLanguage }.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.displayName) }, onClick = { onSelect(choice.code); expanded = false })
                    }
                }
            }
            Text(
                if (active) languageListeningLabel(language) else "按住说话",
                style = MaterialTheme.typography.bodyMedium,
                color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

private fun languageListeningLabel(language: String): String = when (language) {
    "zh" -> "听取中…"
    "en" -> "Listening…"
    "fr" -> "À l’écoute…"
    "vi" -> "Đang lắng nghe…"
    else -> "Listening…"
}

@Composable
private fun FaceActionControls(state: FaceToFaceState, requestOrRun: (() -> Unit) -> Unit, faceViewModel: FaceToFaceViewModel) {
    if (state.mode == FaceToFaceMode.MANUAL) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FaceTalkButton(Modifier.weight(1f), FaceToFaceSide.LEFT, state, { requestOrRun { faceViewModel.manualPress(FaceToFaceSide.LEFT) } }, faceViewModel::manualRelease)
            FaceTalkButton(Modifier.weight(1f), FaceToFaceSide.RIGHT, state, { requestOrRun { faceViewModel.manualPress(FaceToFaceSide.RIGHT) } }, faceViewModel::manualRelease)
        }
    } else {
        AutoTranslationControls(state, requestOrRun, faceViewModel)
    }
}

@Composable
private fun AutoTranslationControls(state: FaceToFaceState, requestOrRun: (() -> Unit) -> Unit, faceViewModel: FaceToFaceViewModel) {
    val listening = state.phase == FaceToFacePhase.LISTENING
    val paused = state.phase == FaceToFacePhase.PAUSED
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = when { listening -> faceViewModel::pauseAuto; paused -> faceViewModel::resumeAuto; else -> { { requestOrRun(faceViewModel::startAuto) } } },
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Icon(if (listening) Icons.Filled.Pause else if (paused) Icons.Filled.PlayArrow else Icons.Filled.Mic, contentDescription = if (listening) "暂停连续翻译" else if (paused) "继续连续翻译" else "开始连续翻译")
        }
        if (listening) {
            FaceTalkButton(
                modifier = Modifier.weight(1.45f),
                side = FaceToFaceSide.RIGHT,
                state = state,
                onPress = faceViewModel::pressRightAuto,
                onRelease = faceViewModel::releaseRightAuto,
            )
        }
        OutlinedButton(
            onClick = faceViewModel::stopAuto,
            enabled = listening || paused,
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = "停止连续翻译")
        }
    }
}

@Composable
private fun FaceSessionAction(
    state: FaceToFaceState,
    requestOrRun: (() -> Unit) -> Unit,
    faceViewModel: FaceToFaceViewModel,
) {
    if (state.mode == FaceToFaceMode.AUTO) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            when (state.phase) {
                FaceToFacePhase.IDLE -> Button(onClick = { requestOrRun(faceViewModel::startAuto) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("开始自动翻译")
                }
                FaceToFacePhase.LISTENING -> {
                    OutlinedButton(onClick = faceViewModel::stopAuto, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("停止连续翻译")
                    }
                    TextButton(onClick = faceViewModel::cancel) { Text("结束会话") }
                }
                FaceToFacePhase.ERROR -> Button(onClick = faceViewModel::clearError, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("重置") }
                FaceToFacePhase.PAUSED -> Button(onClick = faceViewModel::resumeAuto, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("继续连续翻译") }
                FaceToFacePhase.PROCESSING, FaceToFacePhase.STOPPING -> Text("正在完成剩余字幕", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (state.phase == FaceToFacePhase.ERROR) {
        Button(onClick = faceViewModel::clearError, modifier = Modifier.padding(top = 4.dp)) { Text("重置") }
    } else {
        Text(
            if (state.manualInputLocked) "正在完成本轮翻译，请稍候" else "按住说话，松开即翻译 · 单次最长 25 秒",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
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
    val language = if (side == FaceToFaceSide.LEFT) state.leftLanguage else state.rightLanguage
    val sideLabel = TranslationLanguage.displayName(language)
    val identityColor = if (side == FaceToFaceSide.LEFT) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color(0xFFB4765A)
    val actionLabel = when {
        active -> "正在收音"
        state.mode == FaceToFaceMode.AUTO && side == FaceToFaceSide.LEFT -> "默认自动收音"
        state.mode == FaceToFaceMode.AUTO -> "按住抢话"
        enabled -> "按住说话"
        else -> "当前不可用"
    }
    Card(
        modifier = modifier
            .heightIn(min = 72.dp)
            .semantics {
                role = Role.Button
                contentDescription = sideLabel
                stateDescription = actionLabel
            }
            .pointerInput(enabled, state.mode) {
                if (enabled) detectTapGestures(onPress = {
                    onPress()
                    try {
                        awaitRelease()
                    } finally {
                        onRelease()
                    }
                })
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) identityColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, identityColor.copy(alpha = if (active) 0.55f else 0.28f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (active) identityColor else identityColor.copy(alpha = 0.12f),
            ) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.padding(7.dp).size(18.dp),
                    tint = if (active) MaterialTheme.colorScheme.onPrimary else identityColor,
                )
            }
            Text(TranslationLanguage.displayName(language), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = identityColor)
        }
    }
}

@Composable
private fun HistoryPage(modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                "回看每一次交流",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "记录仅展示本机已保存的会话。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索历史") },
                placeholder = { Text("搜索语言或字幕关键词") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        item {
            HistoryEmptyState(query = query, filter = filter)
        }
    }
}

@Composable
private fun HistoryEmptyState(query: String, filter: HistoryFilter) {
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.padding(18.dp).size(30.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text("暂无记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Text(
                HistoryEmptyStatePolicy.message(query, filter),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "当前版本不会展示云端或其他设备的数据",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun ProfilePage(modifier: Modifier, onEndpointSettings: () -> Unit) {
    var notice by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Outlined.PersonOutline, contentDescription = null, modifier = Modifier.padding(15.dp).size(30.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Guest", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("访客模式 · 未登录", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Text("本机", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Text(
                "账户与服务",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            ProfileEntryGroup {
                ProfileEntry(
                    icon = Icons.Outlined.WorkspacePremium,
                    title = "套餐",
                    supporting = "未登录，暂无套餐信息",
                    onClick = { notice = "登录功能尚未接入，当前保持 Guest 模式。" },
                )
                HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ProfileEntry(
                    icon = Icons.Outlined.DataUsage,
                    title = "用量",
                    supporting = "登录后查看真实用量",
                    onClick = { notice = "暂无真实用量数据，不展示模拟数值。" },
                )
                HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ProfileEntry(
                    icon = Icons.Outlined.Devices,
                    title = "设备",
                    supporting = "当前 Android 设备",
                    onClick = { notice = "设备管理尚未接入。" },
                )
            }
        }
        item {
            Text(
                "设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            ProfileEntryGroup {
                ProfileEntry(
                    icon = Icons.Outlined.Settings,
                    title = "偏好设置",
                    supporting = "语言、播放与显示",
                    onClick = { notice = "请在同传工作台选择语言与播放位置。" },
                )
                HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ProfileEntry(
                    icon = Icons.Outlined.Science,
                    title = "测试服务地址",
                    supporting = "Agent HTTP 与 WebSocket",
                    onClick = onEndpointSettings,
                )
            }
        }
        notice?.let { message ->
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(14.dp))
                }
            }
        }
        item {
            Text(
                "Verba 阶段一 · 不包含账户认证与云端用量",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ProfileEntryGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(content = content)
    }
}

@Composable
private fun ProfileEntry(icon: ImageVector, title: String, supporting: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title。$supporting" },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointSettingsPage(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { EndpointSettings(context) }
    var endpoints by remember { mutableStateOf(settings.current()) }
    var httpUrl by remember(endpoints) { mutableStateOf(endpoints.httpUrl) }
    var webSocketUrl by remember(endpoints) { mutableStateOf(endpoints.webSocketUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("测试服务地址", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回我的") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("仅用于测试连接", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("保存后，新会话将使用新地址。不会更改正在进行的翻译。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = httpUrl,
                    onValueChange = { httpUrl = it },
                    label = { Text("HTTP 地址") },
                    supportingText = { Text("Agent health 检查地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = webSocketUrl,
                    onValueChange = { webSocketUrl = it },
                    label = { Text("WebSocket 地址") },
                    supportingText = { Text("实时翻译服务地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    "Debug 可使用 http/ws；Release 仅允许 https/wss。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            settings.save(httpUrl, webSocketUrl).onSuccess {
                                endpoints = it
                                message = "地址已保存。"
                            }.onFailure { message = it.message }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存地址") }
                    OutlinedButton(
                        onClick = {
                            settings.deriveWebSocketUrl(httpUrl).onSuccess { webSocketUrl = it }.onFailure { message = it.message }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("推导 WS") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            endpoints = settings.restoreDefaults()
                            httpUrl = endpoints.httpUrl
                            webSocketUrl = endpoints.webSocketUrl
                            message = "已恢复默认地址。"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("恢复默认") }
                    OutlinedButton(
                        enabled = !checking,
                        onClick = {
                            settings.validate(httpUrl, webSocketUrl).onSuccess { config ->
                                checking = true
                                message = "正在检查…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { settings.checkHealth(config) }
                                    checking = false
                                    message = result.fold(onSuccess = { "Health 检查成功。" }, onFailure = { it.message })
                                }
                            }.onFailure { message = it.message }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (checking) "检查中…" else "Health 检查") }
                }
            }
            message?.let { value ->
                item {
                    val positive = value.contains("成功") || value.contains("已")
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (positive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.semantics { contentDescription = "服务地址状态：$value" },
                    ) {
                        Text(
                            value,
                            color = if (positive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorSurface(message: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun TranscriptTitle(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(if (count == 0) "等待开始" else "$count 段", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FaceTranscriptFeed(
    turns: List<FaceToFaceTurn>,
    activeSide: FaceToFaceSide? = null,
    captureLevel: Float = 0f,
    modifier: Modifier = Modifier,
) {
    TranscriptFeed(
        itemCount = turns.size,
        updateToken = turns.transcriptToken { it.id to listOf(it.sourceText, it.translatedText, it.finished).hashCode() },
        modifier = modifier,
        emptyLabel = "对话字幕会按双方方向显示在这里",
    ) {
        items(turns, key = { it.id }) { turn -> FaceSubtitleBubble(turn, isActive = !turn.finished && turn.side == activeSide, captureLevel = captureLevel) }
    }
}

@Composable
private fun FaceSubtitleBubble(turn: FaceToFaceTurn, isActive: Boolean = false, captureLevel: Float = 0f) {
    val isRight = turn.side == FaceToFaceSide.RIGHT
    val sourceLanguage = if (isRight) "English" else "中文"
    val targetLanguage = if (isRight) "中文" else "English"
    val identityColor = if (isRight) androidx.compose.ui.graphics.Color(0xFFB4765A) else MaterialTheme.colorScheme.primary
    val targetScale = if (isActive) 1f + captureLevel.coerceIn(0f, 1f) * 0.14f else 1f
    val scale by animateFloatAsState(targetScale, animationSpec = tween(durationMillis = if (targetScale > 1f) 65 else 180), label = "activeSubtitleScale")
    Column(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).graphicsLayer { scaleX = scale; scaleY = scale },

        horizontalAlignment = if (isRight) Alignment.End else Alignment.Start,
    ) {
        Text(
            "$sourceLanguage  ·  $sourceLanguage → $targetLanguage",
            style = MaterialTheme.typography.labelSmall,
            color = identityColor,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 3.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f).semantics {
                contentDescription = "$sourceLanguage 字幕。原文${turn.sourceText.ifEmpty { "等待识别" }}。译文${turn.translatedText.ifEmpty { "等待翻译" }}"
            },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, identityColor.copy(alpha = 0.25f)),
        ) {
            SubtitleContent(turn.sourceText, turn.translatedText, turn.finished)
        }
    }
}

@Composable
private fun SoloTranscriptFeed(turns: List<SubtitleTurn>, modifier: Modifier = Modifier) {
    TranscriptFeed(
        itemCount = turns.size,
        updateToken = turns.transcriptToken { it.id to listOf(it.sourceText, it.translatedText, it.finished).hashCode() },
        modifier = modifier,
        emptyLabel = "开始同传后，双语字幕会显示在这里",
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
                    modifier = Modifier.fillMaxWidth(0.94f).semantics {
                        contentDescription = "原文${turn.sourceText.ifEmpty { "等待识别" }}。译文${turn.translatedText.ifEmpty { "等待翻译" }}"
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    SubtitleContent(turn.sourceText, turn.translatedText, turn.finished)
                }
            }
        }
    }
}

@Composable
private fun SubtitleContent(sourceText: String, translatedText: String, finished: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(sourceText.ifEmpty { "正在聆听…" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(translatedText.ifEmpty { "等待翻译…" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
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

    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.background)) {
        if (itemCount == 0) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Outlined.GraphicEq, contentDescription = null, modifier = Modifier.padding(14.dp).size(26.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(emptyLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "实时字幕记录，可向上滑动查看较早内容" },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                content = content,
            )
        }
        if (!followState.followsLatest) {
            Button(
                onClick = { scrollRequest += 1 },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).semantics {
                    contentDescription = if (followState.unseenUpdates > 0) "回到最新，${followState.unseenUpdates} 条新字幕" else "回到最新字幕"
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(if (followState.unseenUpdates > 0) "回到最新 · ${followState.unseenUpdates} 条" else "回到最新")
            }
        }
    }
}

private fun FaceToFaceState.statusLabel(): String = when (phase) {
    FaceToFacePhase.IDLE -> "准备就绪"
    FaceToFacePhase.LISTENING -> "收音中"
    FaceToFacePhase.PAUSED -> "连续翻译已暂停"
    FaceToFacePhase.PROCESSING -> "正在翻译并播放"
    FaceToFacePhase.STOPPING -> "正在完成剩余内容"
    FaceToFacePhase.ERROR -> "需要处理错误"
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

package com.verba.interpretation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verba.interpretation.protocol.TranslationProvider
import com.verba.interpretation.protocol.TranslationSettings
import com.verba.interpretation.protocol.TranslationSettingsStore
import com.verba.interpretation.protocol.TranslationVoiceOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranslationSettingsScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { TranslationSettingsStore(context) }
    var settings by remember { mutableStateOf(store.load()) }

    fun update(provider: TranslationProvider = settings.provider, voices: Map<String, String> = settings.voices) {
        settings = TranslationSettings(provider, voices)
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("翻译测试设置") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回账户") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text("仅 Debug 测试包可见。修改在下一次翻译会话生效。", modifier = Modifier.padding(16.dp))
                }
            }
            item {
                Text("翻译引擎", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.padding(top = 8.dp)) {
                    Column {
                        ProviderRow("火山引擎", TranslationProvider.VOLCENGINE, settings.provider) { provider ->
                            store.saveProvider(provider)
                            update(provider = provider)
                        }
                        HorizontalDivider()
                        ProviderRow("Azure 测试", TranslationProvider.AZURE, settings.provider) { provider ->
                            store.saveProvider(provider)
                            update(provider = provider)
                        }
                    }
                }
            }
            item { Text("目标语言合成声音", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            TranslationVoiceOptions.voicesByLanguage.forEach { (language, choices) ->
                item {
                    VoicePreferenceRow(
                        language = languageName(language),
                        voice = settings.voiceFor(language),
                        choices = choices,
                        onSelect = { voice ->
                            store.saveVoice(language, voice)
                            update(voices = settings.voices + (language to voice))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(label: String, provider: TranslationProvider, current: TranslationProvider, onSelect: (TranslationProvider) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(provider) }.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current == provider, onClick = { onSelect(provider) })
        Text(label)
    }
}

@Composable
private fun VoicePreferenceRow(language: String, voice: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(language)
                Text(voice.ifEmpty { "默认" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
                DropdownMenuItem(text = { Text("默认") }, onClick = { onSelect(""); expanded = false })
                choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice) }, onClick = { onSelect(choice); expanded = false })
                }
            }
        }
    }
}

private fun languageName(language: String): String = when (language) {
    "zh" -> "中文"
    "en" -> "英语"
    "fr" -> "法语"
    "vi" -> "越南语"
    else -> language
}

package com.verba.interpretation.ui.account

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.RegistrationUiState
import kotlinx.coroutines.delay

/** Android 原生解码 base64 JPEG 背景和 PNG 拼图块，不引入图片依赖。 */
@Composable
fun SlideCaptchaChallengeForm(
    captcha: RegistrationUiState.SlideCaptcha,
    loading: Boolean,
    onSubmitCaptcha: (Int) -> Unit,
    onRefresh: () -> Unit,
    onEditDetails: () -> Unit,
    clockMillis: () -> Long = System::currentTimeMillis,
) {
    val challengeBitmap = remember(captcha.challengeImageBase64) { decodeCaptchaImage(captcha.challengeImageBase64) }
    val tileBitmap = remember(captcha.tileImageBase64) { decodeCaptchaImage(captcha.tileImageBase64) }
    var nowMillis by remember(captcha.captchaId) { mutableLongStateOf(clockMillis()) }
    LaunchedEffect(captcha.captchaId) {
        while (nowMillis < captcha.expiresAtMillis) {
            delay(1_000L)
            nowMillis = clockMillis()
        }
    }
    val expired = nowMillis >= captcha.expiresAtMillis

    Column {
        Text("完成拼图验证", style = MaterialTheme.typography.titleMedium)
        Text("按住拼图块水平拖动，对准背景缺口后松开提交。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        when {
            challengeBitmap == null || tileBitmap == null -> CaptchaNotice("拼图加载失败，请刷新后重试。")
            expired -> CaptchaNotice("拼图已过期，请刷新后重试。")
            else -> SlideCaptchaBoard(captcha, challengeBitmap, tileBitmap, loading, onSubmitCaptcha)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { testTag = "captcha-refresh" }) { Text("刷新拼图") }
            TextButton(onClick = onEditDetails, enabled = !loading, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("返回编辑资料") }
        }
    }
}

@Composable
private fun CaptchaNotice(message: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(14.dp))
    }
}

@Composable
private fun SlideCaptchaBoard(
    captcha: RegistrationUiState.SlideCaptcha,
    challengeBitmap: ImageBitmap,
    tileBitmap: ImageBitmap,
    loading: Boolean,
    onSubmitCaptcha: (Int) -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp))
            .semantics { testTag = "captcha-challenge-board"; contentDescription = "拼图验证：拖动拼图块对准背景缺口" },
    ) {
        val density = LocalDensity.current
        val boardWidthPx = with(density) { maxWidth.toPx() }.takeIf { it > 0f } ?: captcha.challengeWidth.toFloat()
        val scale = SlideCaptchaGeometryPolicy.displayScale(boardWidthPx, captcha.challengeWidth)
        var dragOffset by remember(captcha.captchaId) { mutableFloatStateOf(0f) }
        val tileWidth = with(density) { (captcha.tileWidth * scale).toDp() }
        val tileHeight = with(density) { (captcha.tileHeight * scale).toDp() }

        Image(
            bitmap = challengeBitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(captcha.challengeWidth.toFloat() / captcha.challengeHeight),
            contentScale = ContentScale.FillBounds,
        )
        Image(
            tileBitmap,
            null,
            Modifier.size(tileWidth, tileHeight).graphicsLayer {
                translationX = captcha.tileStartX * scale + dragOffset
                translationY = captcha.tileStartY * scale
            }.pointerInput(captcha.captchaId, loading, scale) {
                if (!loading) detectHorizontalDragGestures(
                    onDragEnd = {
                        SlideCaptchaSubmissionPolicy.submit(dragOffset, captcha.tileStartX, captcha.tileWidth, captcha.challengeWidth, scale, onSubmitCaptcha)
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragOffset = SlideCaptchaGeometryPolicy.clampDragOffset(dragOffset + amount, captcha.tileStartX, captcha.tileWidth, captcha.challengeWidth, scale)
                    },
                )
            }.semantics { testTag = "captcha-tile"; stateDescription = if (loading) "正在提交" else "可拖动" },
        )
        if (loading) Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), modifier = Modifier.matchParentSize().semantics { contentDescription = "正在提交拼图验证" }) {}
    }
}

private fun decodeCaptchaImage(base64: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

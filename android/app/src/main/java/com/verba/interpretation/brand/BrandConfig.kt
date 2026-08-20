package com.verba.interpretation.brand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Android 品牌替换的唯一入口。
 *
 * 替换名称、文案、颜色或 [Logo] 的实现即可换牌，无需改动业务页面。
 * XML 中仅保留 Android 系统所需的 application label 资源占位。
 */
object BrandConfig {
    const val appName = "Verba 同传"
    const val shortName = "Verba"
    const val tagline = "让每一次对话，都清晰抵达"

    val primary = Color(0xFF176B68)
    val secondary = Color(0xFF4E635F)

    /** 可替换为 Image、painterResource 或独立品牌资源。 */
    @Composable
    fun Logo(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(primary)
                .semantics { contentDescription = "$shortName 品牌标志" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = shortName.take(1).uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

package com.verba.interpretation.brand

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.verba.interpretation.R

/**
 * Android 品牌替换的唯一入口。
 *
 * 替换名称、文案、颜色或 [Logo] 的实现即可换牌，无需改动业务页面。
 * XML 中仅保留 Android 系统所需的 application label 资源占位。
 */
object BrandConfig {
    const val appName = "言枢智能"
    const val shortName = "言枢"
    const val tagline = "实时同传，智联世界"

    val primary = Color(0xFF176B68)
    val secondary = Color(0xFF4E635F)

    /** 品牌主标志来自根目录 branding/logo/app-logo.svg 的 Android vector 版本。 */
    @Composable
    fun Logo(modifier: Modifier = Modifier) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = "$shortName 品牌标志",
            modifier = modifier.semantics { contentDescription = "$shortName 品牌标志" },
        )
    }
}

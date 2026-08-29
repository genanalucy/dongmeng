package com.verba.interpretation.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object VerbaColors {
    val Background = Color(0xFFF6F7FB)
    val Ink = Color(0xFF171923)
    val Muted = Color(0xFF747784)
    val Brand = Color(0xFF5B6CFF)
    val BrandSoft = Color(0xFFEEF0FF)
    val Danger = Color(0xFFC95B63)
    val BottomNavigationLabel = Color(0xFF535664)
}

object VerbaSpacing {
    val Unit4 = 4.dp
    val Unit8 = 8.dp
}

object VerbaShapes {
    val Small = RoundedCornerShape(18.dp)
    val Medium = RoundedCornerShape(24.dp)
    val Large = RoundedCornerShape(30.dp)
}

object VerbaTouchTargets {
    val Minimum: Dp = 48.dp
}

package com.verba.interpretation.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Explicit tokens for the dark translation shell. They are intentionally stable across themes. */
object VerbaColors {
    val Background = Color(0xFF07111F)
    val Canvas = Background
    val History = Color(0xFF202630)
    val Raised = Color(0xFF303947)
    val Navigation = Color(0xFF141D28)
    val TopControl = Color(0xFF151F2B)
    val Ink = Color(0xFFF5F5F2)
    val Muted = Color(0xFFABB5C3)
    val Translation = Color(0xFFFFC46B)
    val Brand = Translation
    val LeftMic = Color(0xFF91B5D5)
    val RightMic = Color(0xFFE0BC83)
    val LeftLive = Color(0xFF182533)
    val RightLive = Color(0xFF28272A)
    val LeftLiveStroke = Color(0xFF7593AD)
    val RightLiveStroke = Color(0xFFAC9169)
    val Divider = Color(0xFF48515E)
    val ShellStroke = Color(0xFF36404C)
    val Danger = Color(0xFFFFDAD6)
    val ErrorSurface = Color(0xFF35242A)
    val BrandSoft = Raised
    val BottomNavigationLabel = Muted
}

object VerbaSpacing {
    val Unit4 = 4.dp
    val Unit8 = 8.dp
}

object VerbaShapes {
    val Small = RoundedCornerShape(18.dp)
    val Medium = RoundedCornerShape(22.dp)
    val Large = RoundedCornerShape(30.dp)
}

object VerbaTouchTargets {
    val Minimum: Dp = 48.dp
}

enum class ConversationTimelineVisualStyle {
    CONVERSATION,
    FACE,
}

data class ConversationTimelineVisualSpec(
    val horizontalPadding: Dp,
    val bubbleHorizontalPadding: Dp,
    val bubbleVerticalPadding: Dp,
    val sourceFontSize: TextUnit,
    val sourceLineHeight: TextUnit,
    val translationFontSize: TextUnit,
    val translationLineHeight: TextUnit,
    val turnSpacing: Dp,
) {
    companion object {
        val Conversation = ConversationTimelineVisualSpec(
            horizontalPadding = 14.dp,
            bubbleHorizontalPadding = 15.dp,
            bubbleVerticalPadding = 16.dp,
            sourceFontSize = 19.sp,
            sourceLineHeight = 26.sp,
            translationFontSize = 22.sp,
            translationLineHeight = 29.sp,
            turnSpacing = 16.dp,
        )
        val Face = ConversationTimelineVisualSpec(
            horizontalPadding = 15.dp,
            bubbleHorizontalPadding = 12.dp,
            bubbleVerticalPadding = 12.dp,
            sourceFontSize = 17.sp,
            sourceLineHeight = 24.sp,
            translationFontSize = 20.sp,
            translationLineHeight = 27.sp,
            turnSpacing = 12.dp,
        )
    }
}

object TranslationVisualTokens {
    val TopBarHeight = 66.dp
    val InterpretationDirectionRowHeight = 36.dp
    val InterpretationHeaderTotalHeight = TopBarHeight + InterpretationDirectionRowHeight
    val PrimaryActionMinWidth = 104.dp
    val SecondaryActionWidth = 88.dp
    val HeaderHorizontalPadding = 16.dp
    val ConversationHorizontalPadding = 14.dp
    val FaceHorizontalPadding = 15.dp
    val OperationHeight = 148.dp
    val MicDiameter = 82.dp
    val MicGroupWidth = 102.dp
    val MicGroupGap = 40.dp
    val NavigationHeight = 60.dp
    val NavigationHorizontalMargin = 30.dp
    val NavigationGap = 9.dp
    val NavigationBottomMargin = 12.dp
    val BubbleRadius = 22.dp
    val BubbleTailRadius = 5.dp
    val BubbleHorizontalPadding = 15.dp
    val BubbleVerticalPadding = 16.dp
    val BubbleOuterSlot = 48.dp
    val BubbleGap = 6.dp
    val BubbleSpacing = 16.dp
    val SourceLineHeight = 26.dp
    val TranslationMinHeight = 42.dp
}

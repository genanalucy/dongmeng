package com.verba.interpretation.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ripple
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HeadsetMic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.verba.interpretation.BuildConfig
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verba.interpretation.ui.ProductDestination
import com.verba.interpretation.ui.design.FaceToFaceConversationIcon
import com.verba.interpretation.ui.design.LiquidGlassSurface
import com.verba.interpretation.ui.design.MotionQuickMillis
import com.verba.interpretation.ui.design.TranslationVisualTokens

@Composable
fun ProductBottomBar(
    destinations: List<ProductDestination>,
    selected: ProductDestination,
    onSelect: (ProductDestination) -> Unit,
) {
    // Semantic color mapping; in dark mode these resolve to the exact legacy
    // VerbaColors values (Navigation -> surfaceContainerLowest, Raised ->
    // primaryContainer, Translation -> primary, Muted -> onSurfaceVariant,
    // ShellStroke -> outline), while light mode follows BrandLightColors.
    val barContainer = if (BuildConfig.DEBUG) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLowest
    val selectedContainer = MaterialTheme.colorScheme.primaryContainer
    val selectedContent = MaterialTheme.colorScheme.primary
    val unselectedContent = MaterialTheme.colorScheme.onSurfaceVariant
    LiquidGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = if (BuildConfig.DEBUG) 20.dp else TranslationVisualTokens.NavigationHorizontalMargin,
                end = if (BuildConfig.DEBUG) 20.dp else TranslationVisualTokens.NavigationHorizontalMargin,
                top = TranslationVisualTokens.NavigationGap,
                bottom = TranslationVisualTokens.NavigationBottomMargin,
            )
            .then(if (BuildConfig.DEBUG) Modifier.heightIn(min = 68.dp) else Modifier.height(TranslationVisualTokens.NavigationHeight)),
        shape = RoundedCornerShape(if (BuildConfig.DEBUG) 26.dp else 30.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(if (BuildConfig.DEBUG) 8.dp else 4.dp).semantics { selectableGroup() },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { destination ->
            val isSelected = destination == selected
            val capsuleShape = RoundedCornerShape(25.dp)
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val container by animateColorAsState(
                targetValue = if (isSelected || (BuildConfig.DEBUG && pressed)) selectedContainer else barContainer,
                animationSpec = tween(MotionQuickMillis, easing = FastOutSlowInEasing),
                label = "bottomNavContainer",
            )
            val content by animateColorAsState(
                targetValue = if (isSelected) selectedContent else unselectedContent,
                animationSpec = tween(MotionQuickMillis, easing = FastOutSlowInEasing),
                label = "bottomNavContent",
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(if (BuildConfig.DEBUG) Modifier.heightIn(min = 50.dp).padding(vertical = 4.dp) else Modifier.height(50.dp))
                    .then(if (BuildConfig.DEBUG) Modifier.clip(capsuleShape) else Modifier)
                    .background(container, capsuleShape)
                    .semantics {
                        contentDescription = "${destination.visualLabel()}页"
                        this.selected = isSelected
                    }
                    .selectable(
                        selected = isSelected,
                        interactionSource = interactionSource,
                        indication = if (BuildConfig.DEBUG) ripple(color = selectedContent) else LocalIndication.current,
                        role = Role.Tab,
                        onClick = { onSelect(destination) },
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val icon = destination.icon()
                    Icon(
                        imageVector = icon.image,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = content,
                    )
                    Text(
                        destination.visualLabel(),
                        color = content,
                        fontSize = if (BuildConfig.DEBUG) 11.sp else 10.sp,
                        lineHeight = if (BuildConfig.DEBUG) 16.sp else 14.sp,
                        fontWeight = if (BuildConfig.DEBUG && isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            }
        }
    }
}

private fun ProductDestination.visualLabel(): String = when (this) {
    ProductDestination.FACE_TO_FACE -> "对话"
    else -> label
}

private data class ProductNavigationIcon(
    val image: ImageVector,
    val contentDescription: String,
)

private fun ProductDestination.icon(): ProductNavigationIcon = when (this) {
    ProductDestination.FACE_TO_FACE -> ProductNavigationIcon(FaceToFaceConversationIcon.Image, FaceToFaceConversationIcon.ContentDescription)
    ProductDestination.INTERPRETATION -> ProductNavigationIcon(Icons.Outlined.HeadsetMic, "同声传译")
    ProductDestination.PROFILE -> ProductNavigationIcon(Icons.Outlined.Person, "我的")
    else -> error("ProductBottomBar does not support $this")
}

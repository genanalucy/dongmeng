package com.verba.interpretation.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HeadsetMic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.verba.interpretation.ui.design.VerbaColors
import com.verba.interpretation.ui.design.TranslationVisualTokens

@Composable
fun ProductBottomBar(
    destinations: List<ProductDestination>,
    selected: ProductDestination,
    onSelect: (ProductDestination) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = TranslationVisualTokens.NavigationHorizontalMargin,
                end = TranslationVisualTokens.NavigationHorizontalMargin,
                top = TranslationVisualTokens.NavigationGap,
                bottom = TranslationVisualTokens.NavigationBottomMargin,
            )
            .height(TranslationVisualTokens.NavigationHeight)
            .background(VerbaColors.Navigation, RoundedCornerShape(30.dp))
            .border(1.dp, VerbaColors.ShellStroke, RoundedCornerShape(30.dp))
            .padding(4.dp)
            .semantics { selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { destination ->
            val isSelected = destination == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .background(
                        if (isSelected) VerbaColors.Raised else VerbaColors.Navigation,
                        RoundedCornerShape(25.dp),
                    )
                    .semantics {
                        contentDescription = "${destination.visualLabel()}页"
                        this.selected = isSelected
                    }
                    .selectable(
                        selected = isSelected,
                        interactionSource = MutableInteractionSource(),
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
                        tint = if (isSelected) VerbaColors.Translation else VerbaColors.Muted,
                    )
                    Text(
                        destination.visualLabel(),
                        color = if (isSelected) VerbaColors.Translation else VerbaColors.Muted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
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

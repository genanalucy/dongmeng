package com.verba.interpretation.ui.navigation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HeadsetMic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.verba.interpretation.ui.ProductDestination
import com.verba.interpretation.ui.ProductNavigationMode
import com.verba.interpretation.ui.ProductNavigationPolicy
import com.verba.interpretation.ui.design.VerbaColors
import com.verba.interpretation.ui.design.VerbaTouchTargets

@Composable
fun ProductBottomBar(
    selected: ProductDestination,
    onSelect: (ProductDestination) -> Unit,
) {
    val destinations = ProductNavigationPolicy.destinationsFor(ProductNavigationMode.USER)

    NavigationBar(containerColor = VerbaColors.Background) {
        destinations.forEach { destination ->
            val icon = destination.icon()
            NavigationBarItem(
                modifier = Modifier.heightIn(min = VerbaTouchTargets.Minimum),
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        imageVector = icon.image,
                        contentDescription = icon.contentDescription,
                    )
                },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = VerbaColors.Brand,
                    selectedTextColor = VerbaColors.BottomNavigationLabel,
                    indicatorColor = VerbaColors.BrandSoft,
                    unselectedIconColor = VerbaColors.Muted,
                    unselectedTextColor = VerbaColors.BottomNavigationLabel,
                ),
            )
        }
    }
}

private data class ProductNavigationIcon(
    val image: ImageVector,
    val contentDescription: String,
)

private fun ProductDestination.icon(): ProductNavigationIcon = when (this) {
    ProductDestination.FACE_TO_FACE -> ProductNavigationIcon(Icons.Outlined.Groups, "面对面翻译")
    ProductDestination.INTERPRETATION -> ProductNavigationIcon(Icons.Outlined.HeadsetMic, "同声传译")
    ProductDestination.PROFILE -> ProductNavigationIcon(Icons.Outlined.Person, "我的")
    else -> error("ProductBottomBar does not support $this")
}

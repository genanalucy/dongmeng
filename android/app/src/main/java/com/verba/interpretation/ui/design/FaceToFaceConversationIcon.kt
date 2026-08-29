package com.verba.interpretation.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object FaceToFaceConversationIcon {
    const val ContentDescription = "面对面翻译"

    val Image: ImageVector
        get() = ImageVector.Builder(
            name = "FaceToFaceConversation",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(2f, 6f)
                curveTo(2f, 3.8f, 3.8f, 2f, 6f, 2f)
                curveTo(8.1f, 2f, 9.6f, 3.6f, 9.6f, 5.7f)
                lineTo(11.5f, 6.8f)
                lineTo(9.5f, 8f)
                curveTo(8.8f, 9.8f, 7.6f, 10.5f, 6f, 10.5f)
                curveTo(3.8f, 10.5f, 2f, 8.5f, 2f, 6f)
                close()
                moveTo(22f, 6f)
                curveTo(22f, 3.8f, 20.2f, 2f, 18f, 2f)
                curveTo(15.9f, 2f, 14.4f, 3.6f, 14.4f, 5.7f)
                lineTo(12.5f, 6.8f)
                lineTo(14.5f, 8f)
                curveTo(15.2f, 9.8f, 16.4f, 10.5f, 18f, 10.5f)
                curveTo(20.2f, 10.5f, 22f, 8.5f, 22f, 6f)
                close()
                moveTo(1f, 21f)
                curveTo(1f, 15.2f, 3.4f, 12f, 6.5f, 12f)
                curveTo(9.2f, 12f, 10.8f, 14.8f, 10.8f, 21f)
                close()
                moveTo(23f, 21f)
                curveTo(23f, 15.2f, 20.6f, 12f, 17.5f, 12f)
                curveTo(14.8f, 12f, 13.2f, 14.8f, 13.2f, 21f)
                close()
                moveTo(11f, 3f)
                lineTo(13f, 3f)
                lineTo(13f, 4f)
                lineTo(11f, 4f)
                close()
                moveTo(11f, 9.5f)
                lineTo(13f, 9.5f)
                lineTo(13f, 10.5f)
                lineTo(11f, 10.5f)
                close()
            }
        }.build()
}

package app.kotowski.keepsimalive.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Timer3Icon: ImageVector =
    ImageVector
        .Builder(
            name = "timer_3",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.Companion.NonZero,
            ) {
                moveTo(8f, 19f)
                verticalLineTo(16f)
                horizontalLineToRelative(6f)
                verticalLineTo(13.5f)
                horizontalLineTo(9f)
                verticalLineToRelative(-3f)
                horizontalLineToRelative(5f)
                verticalLineTo(8f)
                horizontalLineTo(8f)
                verticalLineTo(5f)
                horizontalLineToRelative(6f)
                quadToRelative(1.25f, 0f, 2.13f, 0.88f)
                reflectiveQuadTo(17f, 8f)
                verticalLineTo(9.9f)
                quadToRelative(0f, 0.88f, -0.61f, 1.49f)
                quadTo(15.78f, 12f, 14.9f, 12f)
                quadToRelative(0.88f, 0f, 1.49f, 0.61f)
                quadTo(17f, 13.23f, 17f, 14.1f)
                verticalLineTo(16f)
                quadToRelative(0f, 1.25f, -0.88f, 2.13f)
                reflectiveQuadTo(14f, 19f)
                horizontalLineTo(8f)
                close()
            }
        }.build()

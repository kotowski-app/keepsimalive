package app.kotowski.keepsimalive.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val Timer2Icon: ImageVector =
    ImageVector
        .Builder(
            name = "timer_2",
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
                verticalLineTo(13.5f)
                quadTo(8f, 12.25f, 8.88f, 11.38f)
                reflectiveQuadTo(11f, 10.5f)
                horizontalLineToRelative(3f)
                verticalLineTo(8f)
                horizontalLineTo(8f)
                verticalLineTo(5f)
                horizontalLineToRelative(6f)
                quadToRelative(1.25f, 0f, 2.13f, 0.88f)
                reflectiveQuadTo(17f, 8f)
                verticalLineToRelative(2.5f)
                quadToRelative(0f, 1.25f, -0.88f, 2.13f)
                reflectiveQuadTo(14f, 13.5f)
                horizontalLineTo(11f)
                verticalLineTo(16f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(3f)
                horizontalLineTo(8f)
                close()
            }
        }.build()

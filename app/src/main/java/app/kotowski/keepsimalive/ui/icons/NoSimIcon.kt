package app.kotowski.keepsimalive.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Material Symbols Outlined "no_sim" (24dp, wght 400, ROND 50).
val NoSimIcon: ImageVector =
    ImageVector
        .Builder(
            name = "no_sim",
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
                moveTo(20f, 17.18f)
                lineToRelative(-2f, -2f)
                verticalLineTo(4f)
                horizontalLineTo(10.85f)
                lineToRelative(-2f, 2f)
                lineTo(7.4f, 4.6f)
                lineTo(10f, 2f)
                horizontalLineToRelative(8f)
                quadToRelative(0.82f, 0f, 1.41f, 0.59f)
                reflectiveQuadTo(20f, 4f)
                verticalLineTo(17.18f)
                close()
                moveToRelative(0.5f, 6.13f)
                lineTo(6f, 8.8f)
                verticalLineTo(20f)
                horizontalLineTo(18f)
                verticalLineTo(17.98f)
                lineToRelative(2f, 2f)
                verticalLineTo(20f)
                quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                reflectiveQuadTo(18f, 22f)
                horizontalLineTo(6f)
                quadTo(5.18f, 22f, 4.59f, 21.41f)
                reflectiveQuadTo(4f, 20f)
                verticalLineTo(8f)
                lineTo(4.6f, 7.4f)
                lineTo(0.7f, 3.5f)
                lineTo(2.13f, 2.1f)
                lineTo(21.9f, 21.88f)
                lineTo(20.5f, 23.3f)
                close()
                moveTo(13.53f, 10.68f)
                close()
                moveToRelative(-1.88f, 3.8f)
                close()
            }
        }.build()

package app.kotowski.keepsimalive.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Material Symbols Outlined "edit_square" (24dp, wght 400, ROND 50).
val EditSquareIcon: ImageVector =
    ImageVector
        .Builder(
            name = "edit_square",
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
                moveTo(5f, 21f)
                quadTo(4.18f, 21f, 3.59f, 20.41f)
                reflectiveQuadTo(3f, 19f)
                verticalLineTo(5f)
                quadTo(3f, 4.17f, 3.59f, 3.59f)
                reflectiveQuadTo(5f, 3f)
                horizontalLineToRelative(8.93f)
                lineToRelative(-2f, 2f)
                horizontalLineTo(5f)
                verticalLineTo(19f)
                horizontalLineTo(19f)
                verticalLineTo(12.05f)
                lineToRelative(2f, -2f)
                verticalLineTo(19f)
                quadToRelative(0f, 0.82f, -0.59f, 1.41f)
                reflectiveQuadTo(19f, 21f)
                horizontalLineTo(5f)
                close()
                moveToRelative(7f, -9f)
                close()
                moveTo(9f, 15f)
                verticalLineTo(10.75f)
                lineTo(18.18f, 1.57f)
                quadToRelative(0.3f, -0.3f, 0.68f, -0.45f)
                quadTo(19.23f, 0.97f, 19.6f, 0.97f)
                quadToRelative(0.4f, 0f, 0.76f, 0.15f)
                quadToRelative(0.36f, 0.15f, 0.66f, 0.45f)
                lineTo(22.43f, 3f)
                quadToRelative(0.28f, 0.3f, 0.43f, 0.66f)
                reflectiveQuadTo(23f, 4.4f)
                reflectiveQuadTo(22.86f, 5.14f)
                reflectiveQuadTo(22.43f, 5.8f)
                lineTo(13.25f, 15f)
                horizontalLineTo(9f)
                close()
                moveTo(21.03f, 4.4f)
                lineTo(19.63f, 3f)
                lineToRelative(1.4f, 1.4f)
                close()
                moveTo(11f, 13f)
                horizontalLineToRelative(1.4f)
                lineTo(18.2f, 7.2f)
                lineTo(17.5f, 6.5f)
                lineTo(16.78f, 5.8f)
                lineTo(11f, 11.58f)
                verticalLineTo(13f)
                close()
                moveTo(17.5f, 6.5f)
                lineTo(16.78f, 5.8f)
                lineTo(17.5f, 6.5f)
                lineToRelative(0.7f, 0.7f)
                lineTo(17.5f, 6.5f)
                close()
            }
        }.build()

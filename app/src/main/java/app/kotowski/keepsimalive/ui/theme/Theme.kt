package app.kotowski.keepsimalive.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.kotowski.keepsimalive.util.AppPrefs

@Composable
fun KeepSimAliveTheme(
    themeMode: String = AppPrefs.THEME_MODE_SYSTEM,
    content: @Composable () -> Unit,
) {
    // "system" (the default) follows the device's dark/light setting live, the other modes
    // force the palette. On Android 12+ use the Material You palette derived from the
    // system/wallpaper, on older versions the default Material 3 baseline palette. No forced
    // brand palette.
    val darkTheme =
        when (themeMode) {
            AppPrefs.THEME_MODE_LIGHT -> false
            AppPrefs.THEME_MODE_DARK -> true
            else -> isSystemInDarkTheme()
        }

    val colorScheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                darkColorScheme()
            }

            else -> {
                lightColorScheme()
            }
        }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

package app.kotowski.keepsimalive.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import app.kotowski.keepsimalive.util.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The app must not force its own palette: on Android 12+ the theme follows the Material You
// (wallpaper-derived) palette, on older versions the Material 3 baseline. The "system" mode
// (default) follows the device setting, the other modes force a palette.
@RunWith(RobolectricTestRunner::class)
class ThemeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ColorScheme has no structural equals in material3, so compare the full token set.
    private fun assertSamePaletteAs(
        expected: ColorScheme,
        actual: ColorScheme,
    ) {
        assertEquals(expected.toString(), actual.toString())
    }

    @Test
    @Config(sdk = [34])
    fun lightThemeUsesTheDynamicColorSchemeInsteadOfAForcedPalette() {
        var actual: ColorScheme? = null
        var expected: ColorScheme? = null
        composeTestRule.setContent {
            expected = dynamicLightColorScheme(LocalContext.current)
            KeepSimAliveTheme(themeMode = AppPrefs.THEME_MODE_LIGHT) {
                actual = MaterialTheme.colorScheme
            }
        }
        assertSamePaletteAs(expected!!, actual!!)
    }

    @Test
    @Config(sdk = [34])
    fun darkThemeUsesTheDynamicDarkColorScheme() {
        var actual: ColorScheme? = null
        var expected: ColorScheme? = null
        composeTestRule.setContent {
            expected = dynamicDarkColorScheme(LocalContext.current)
            KeepSimAliveTheme(themeMode = AppPrefs.THEME_MODE_DARK) {
                actual = MaterialTheme.colorScheme
            }
        }
        assertSamePaletteAs(expected!!, actual!!)
    }

    @Test
    @Config(sdk = [29])
    fun preSDevicesFallBackToTheDefaultLightBaselinePalette() {
        var actual: ColorScheme? = null
        var expected: ColorScheme? = null
        composeTestRule.setContent {
            expected = lightColorScheme()
            KeepSimAliveTheme(themeMode = AppPrefs.THEME_MODE_LIGHT) {
                actual = MaterialTheme.colorScheme
            }
        }
        assertSamePaletteAs(expected!!, actual!!)
    }

    @Test
    @Config(sdk = [29])
    fun preSDarkThemeFallsBackToTheDefaultDarkBaselinePalette() {
        var actual: ColorScheme? = null
        var expected: ColorScheme? = null
        composeTestRule.setContent {
            expected = darkColorScheme()
            KeepSimAliveTheme(themeMode = AppPrefs.THEME_MODE_DARK) {
                actual = MaterialTheme.colorScheme
            }
        }
        assertSamePaletteAs(expected!!, actual!!)
    }

    @Test
    @Config(sdk = [29])
    fun theSystemModeFollowsTheDeviceLightSetting() {
        var actual: ColorScheme? = null
        var expected: ColorScheme? = null
        composeTestRule.setContent {
            expected = lightColorScheme()
            KeepSimAliveTheme(themeMode = AppPrefs.THEME_MODE_SYSTEM) {
                actual = MaterialTheme.colorScheme
            }
        }
        assertSamePaletteAs(expected!!, actual!!)
    }

    @Test
    @Config(sdk = [29], qualifiers = "night")
    fun theSystemModeFollowsTheDeviceDarkSetting() {
        var actual: ColorScheme? = null
        var expected: ColorScheme? = null
        composeTestRule.setContent {
            expected = darkColorScheme()
            KeepSimAliveTheme(themeMode = AppPrefs.THEME_MODE_SYSTEM) {
                actual = MaterialTheme.colorScheme
            }
        }
        assertSamePaletteAs(expected!!, actual!!)
    }
}

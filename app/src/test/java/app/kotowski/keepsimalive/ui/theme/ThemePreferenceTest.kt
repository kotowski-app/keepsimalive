package app.kotowski.keepsimalive.ui.theme

import android.app.Application
import app.kotowski.keepsimalive.util.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ThemePreferenceTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val prefs = AppPrefs(context)

    @Before
    fun setup() {
        prefs.themeMode = AppPrefs.THEME_MODE_SYSTEM
    }

    @Test
    fun `the initial mode is the stored pref`() {
        prefs.themeMode = AppPrefs.THEME_MODE_DARK
        assertEquals(AppPrefs.THEME_MODE_DARK, ThemePreference(prefs).mode.value)
    }

    @Test
    fun `set persists the mode and emits it`() {
        val preference = ThemePreference(prefs)
        preference.set(AppPrefs.THEME_MODE_LIGHT)
        assertEquals(AppPrefs.THEME_MODE_LIGHT, prefs.themeMode)
        assertEquals(AppPrefs.THEME_MODE_LIGHT, preference.mode.value)
    }

    @Test
    fun `set ignores a mode that is not one of the three`() {
        val preference = ThemePreference(prefs)
        preference.set("neon")
        assertEquals(AppPrefs.THEME_MODE_SYSTEM, prefs.themeMode)
        assertEquals(AppPrefs.THEME_MODE_SYSTEM, preference.mode.value)
    }
}

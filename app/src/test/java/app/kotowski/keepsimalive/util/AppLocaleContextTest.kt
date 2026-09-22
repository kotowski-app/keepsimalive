package app.kotowski.keepsimalive.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.kotowski.keepsimalive.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// The locale copy of the application context: the app language the user picked in Settings
// must resolve from any context, while below API 33 (the suite default) only the activity
// context follows it natively.
@RunWith(RobolectricTestRunner::class)
class AppLocaleContextTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        // The delegate stores the app language in static state that leaks across tests, so
        // reset it to "follow the system" before every test.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `while the app follows the system language the context is returned unchanged`() {
        assertSame(context, context.appLocaleContext())
        assertEquals("Settings", context.appLocaleContext().getString(R.string.settings_title))
    }

    @Test
    fun `the picked app language resolves from the application context`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        assertEquals("Настройки", context.appLocaleContext().getString(R.string.settings_title))
        // The raw app context is not locale-aware: it keeps the system language.
        assertEquals("Settings", context.getString(R.string.settings_title))
    }

    @Test
    fun `plurals resolve with the picked language categories`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        assertEquals("2 минуты назад", context.appLocaleContext().resources.getQuantityString(R.plurals.past_minutes, 2, 2))
    }

    @Test
    fun `appString and appQuantity resolve with the picked app language`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        assertEquals("Настройки", context.appString(R.string.settings_title))
        assertEquals("2 минуты назад", context.appQuantity(R.plurals.past_minutes, 2, 2))
        assertEquals("Settings", context.getString(R.string.settings_title))
    }

    @Test
    fun `appString and appQuantity keep the system language by default`() {
        assertEquals("Settings", context.appString(R.string.settings_title))
        assertEquals("2 minutes ago", context.appQuantity(R.plurals.past_minutes, 2, 2))
    }

    @Test
    fun `a fresh background process falls back to the persisted tag while the state is empty`() {
        // The state is empty from the @Before reset: the fresh background process below
        // API 33, where the AppCompat state stays empty until the first activity attach
        // (or the startup re-apply). The persisted tag — written through the same prefs
        // appLocaleContext reads — must cover that window.
        AppPrefs(context).appLanguageTag = "ru"
        assertEquals("Настройки", context.appLocaleContext().getString(R.string.settings_title))
        assertEquals("Настройки", context.appString(R.string.settings_title))
        assertEquals("Settings", context.getString(R.string.settings_title))
    }
}

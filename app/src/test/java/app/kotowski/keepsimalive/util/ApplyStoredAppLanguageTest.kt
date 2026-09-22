package app.kotowski.keepsimalive.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// The process-start re-apply of the persisted app language (applyStoredAppLanguage): a
// fresh background process below API 33 (the suite default) starts with the AppCompat
// state empty until the first activity attach, and the re-apply must restore the pick
// there. From API 33 the platform owns the locale process-wide, so the re-apply must
// leave the state alone.
@RunWith(RobolectricTestRunner::class)
class ApplyStoredAppLanguageTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = AppPrefs(context)

    @Before
    fun setup() {
        // The delegate stores the app language in static state that leaks across tests, so
        // reset it to "follow the system" before every test.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        prefs.appLanguageTag = ""
    }

    @After
    fun tearDown() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `a stored tag re-applies to the AppCompat state below the platform gate`() {
        prefs.appLanguageTag = "ru"
        context.applyStoredAppLanguage()
        assertEquals(LocaleListCompat.forLanguageTags("ru"), AppCompatDelegate.getApplicationLocales())
    }

    @Test
    fun `an empty stored tag leaves the AppCompat state unchanged`() {
        context.applyStoredAppLanguage()
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    // From API 33 the platform owns and applies the per-app locale process-wide (the suite
    // default is 29, below the gate), so the re-apply must not touch the state there.
    @Config(sdk = [34])
    @Test
    fun `from the platform gate on a stored tag leaves the AppCompat state alone`() {
        prefs.appLanguageTag = "ru"
        context.applyStoredAppLanguage()
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }
}

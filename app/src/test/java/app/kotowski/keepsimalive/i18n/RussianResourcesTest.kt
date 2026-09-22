package app.kotowski.keepsimalive.i18n

import android.content.Context
import app.kotowski.keepsimalive.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Proves the shipped values-ru resource resolves under the Russian locale: the strings the
// user actually sees, the Russian plural categories (one/few/many), and the non-translatable
// language labels that keep their native form in every locale.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class RussianResourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `russian strings resolve under the russian locale`() {
        assertEquals("Настройки", context.getString(R.string.settings_title))
        assertEquals("Язык", context.getString(R.string.settings_language_label))
        assertEquals("Как в системе", context.getString(R.string.settings_system_default))
    }

    @Test
    fun `the language labels keep their native form in russian`() {
        assertEquals("English", context.getString(R.string.settings_language_english))
        assertEquals("Русский", context.getString(R.string.settings_language_russian))
    }

    @Test
    fun `russian plurals pick the one and many categories`() {
        // 1 -> "one", 2 -> "few", 5 -> "many" in the Russian plural rules.
        assertEquals("1 минуту назад", context.resources.getQuantityString(R.plurals.past_minutes, 1, 1))
        assertEquals("2 минуты назад", context.resources.getQuantityString(R.plurals.past_minutes, 2, 2))
        assertEquals("5 минут назад", context.resources.getQuantityString(R.plurals.past_minutes, 5, 5))
    }
}

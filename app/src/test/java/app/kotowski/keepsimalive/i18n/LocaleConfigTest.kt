package app.kotowski.keepsimalive.i18n

import android.content.Context
import app.kotowski.keepsimalive.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

// Proves the locale-config the API 33+ system per-app language picker is built from
// declares exactly the locales the in-app Settings picker offers (en, ru) and no locale
// the setAppLanguage guard would reject.
@RunWith(RobolectricTestRunner::class)
class LocaleConfigTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `the locale config declares exactly the locales the in-app picker offers`() {
        val locales =
            context.resources.getXml(R.xml.locales_config).use { parser ->
                val names = mutableListOf<String>()
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                        names.add(
                            parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name"),
                        )
                    }
                    parser.next()
                    event = parser.eventType
                }
                names
            }
        assertEquals(listOf("en", "ru"), locales)
    }
}

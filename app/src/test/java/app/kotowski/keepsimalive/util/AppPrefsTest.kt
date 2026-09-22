package app.kotowski.keepsimalive.util

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AppPrefsTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun prefsFile(): File = File(File(context.dataDir, "shared_prefs"), "${AppPrefs.PREFS_NAME}.xml")

    private fun writePrefsFile(content: String) {
        prefsFile().apply {
            parentFile?.mkdirs()
            writeText(content)
        }
    }

    @Test
    fun `getters return safe defaults on a fresh install`() {
        val prefs = AppPrefs(context)
        assertEquals(0, prefs.historyRetentionDays)
        assertEquals(0, prefs.getSimNotPresentFailures(1))
        assertEquals(0L, prefs.getRhythmResetAtMillis(1))
        assertTrue(prefs.showStickyNotification)
    }

    @Test
    fun `values set are readable back`() {
        val prefs = AppPrefs(context)
        prefs.historyRetentionDays = 7
        prefs.showStickyNotification = false
        prefs.setSimNotPresentFailures(2, 3)

        assertEquals(7, prefs.historyRetentionDays)
        assertFalse(prefs.showStickyNotification)
        assertEquals(3, prefs.getSimNotPresentFailures(2))
    }

    @Test
    fun `themeMode defaults to system, stores a valid mode and degrades a tampered one`() {
        val prefs = AppPrefs(context)
        assertEquals(AppPrefs.THEME_MODE_SYSTEM, prefs.themeMode)
        prefs.themeMode = AppPrefs.THEME_MODE_DARK
        assertEquals(AppPrefs.THEME_MODE_DARK, prefs.themeMode)
        prefs.themeMode = AppPrefs.THEME_MODE_LIGHT
        assertEquals(AppPrefs.THEME_MODE_LIGHT, prefs.themeMode)
        prefs.themeMode = "neon"
        assertEquals(AppPrefs.THEME_MODE_SYSTEM, prefs.themeMode)
    }

    @Test
    fun `appLanguageTag defaults to empty, round-trips a tag and back to empty`() {
        val prefs = AppPrefs(context)
        assertEquals("", prefs.appLanguageTag)
        prefs.appLanguageTag = "ru"
        assertEquals("ru", prefs.appLanguageTag)
        prefs.appLanguageTag = "en"
        assertEquals("en", prefs.appLanguageTag)
        prefs.appLanguageTag = ""
        assertEquals("", prefs.appLanguageTag)
    }

    @Test
    fun `existing data on disk is read back on open`() {
        writePrefsFile(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<map>\n" +
                "    <int name=\"history_retention_days\" value=\"14\" />\n" +
                "    <boolean name=\"show_sticky_notification\" value=\"false\" />\n" +
                "    <string name=\"theme_mode\">dark</string>\n" +
                "    <int name=\"sim_not_present_failures_sim_3\" value=\"5\" />\n" +
                "    <long name=\"catch_up_anchor_sim_1\" value=\"1700000000000\" />\n" +
                "    <long name=\"catch_up_anchor_sim_2\" value=\"-5\" />\n" +
                "    <long name=\"rhythm_reset_at_1\" value=\"1700000000000\" />\n" +
                "</map>",
        )

        val prefs = AppPrefs(context)
        assertEquals(14, prefs.historyRetentionDays)
        assertFalse(prefs.showStickyNotification)
        assertEquals(AppPrefs.THEME_MODE_DARK, prefs.themeMode)
        assertEquals(5, prefs.getSimNotPresentFailures(3))
        // A tampered (non-positive) anchor degrades to unset (0) like an absent one.
        assertEquals(1_700_000_000_000L, prefs.getCatchUpAnchor(1))
        assertEquals(0L, prefs.getCatchUpAnchor(2))
        assertEquals(1_700_000_000_000L, prefs.getRhythmResetAtMillis(1))
    }

    @Test
    fun `catch-up anchor defaults to unset, round-trips per SIM and clears`() {
        val prefs = AppPrefs(context)
        assertEquals(0L, prefs.getCatchUpAnchor(1))

        prefs.setCatchUpAnchor(1, 1_700_000_000_000L)
        prefs.setCatchUpAnchor(2, 1_700_000_000_001L)
        assertEquals(1_700_000_000_000L, prefs.getCatchUpAnchor(1))
        assertEquals(1_700_000_000_001L, prefs.getCatchUpAnchor(2))

        prefs.clearCatchUpAnchor(1)
        assertEquals(0L, prefs.getCatchUpAnchor(1))
        assertEquals(1_700_000_000_001L, prefs.getCatchUpAnchor(2))
    }

    @Test
    fun `rhythm reset defaults to unset, round-trips per SIM and keeps other SIMs untouched`() {
        val prefs = AppPrefs(context)
        assertEquals(0L, prefs.getRhythmResetAtMillis(1))

        prefs.setRhythmResetAtMillis(1, 1_700_000_000_000L)
        prefs.setRhythmResetAtMillis(2, 1_700_000_000_001L)
        assertEquals(1_700_000_000_000L, prefs.getRhythmResetAtMillis(1))
        assertEquals(1_700_000_000_001L, prefs.getRhythmResetAtMillis(2))
        assertEquals(0L, prefs.getRhythmResetAtMillis(3))
    }

    @Test
    fun `corrupt prefs file degrades to defaults without throwing`() {
        writePrefsFile("garbage-not-xml")
        val prefs = AppPrefs(context)
        assertEquals(0, prefs.historyRetentionDays)
        assertTrue(prefs.showStickyNotification)
    }

    @Test
    fun `a write after corruption replaces the damaged file`() {
        writePrefsFile("garbage-not-xml")
        val prefs = AppPrefs(context)
        prefs.historyRetentionDays = 7
        assertTrue(prefsFile().readText().contains("history_retention_days"))
    }
}

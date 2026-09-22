package app.kotowski.keepsimalive.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import app.kotowski.keepsimalive.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
class KeepaliveNotificationTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val shadowNotificationManager: ShadowNotificationManager
        get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    @Test
    fun `show creates the low importance channel and posts the ongoing hint`() {
        KeepaliveNotification.show(context)

        val channel =
            shadowNotificationManager.notificationChannels
                .find { (it as NotificationChannel).id == CHANNEL_ID } as? NotificationChannel
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
        assertEquals("Keep Alive", channel.name)

        val posted = shadowNotificationManager.activeNotifications.find { it.id == NOTIFICATION_ID }
        assertNotNull(posted)
        val notification = posted!!.notification
        assertTrue(hasFlag(notification, Notification.FLAG_ONGOING_EVENT))
        assertFalse(hasFlag(notification, Notification.FLAG_AUTO_CANCEL))
        assertEquals(
            context.getString(R.string.app_name),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.notification_status_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
    }

    // Below API 26 notification channels do not exist — the post must skip channel
    // registration and go through NotificationCompat. Level 24 (the app's minSdk)
    // exercises that sub-26 path.
    @Config(sdk = [24])
    @Test
    fun `show posts the hint without registering a channel below O`() {
        KeepaliveNotification.show(context)

        assertTrue(shadowNotificationManager.notificationChannels.isEmpty())
        assertNotNull(shadowNotificationManager.activeNotifications.find { it.id == NOTIFICATION_ID })
    }

    @Test
    fun `showing again replaces the hint in place with a single notification`() {
        KeepaliveNotification.show(context)
        KeepaliveNotification.show(context)

        assertEquals(1, shadowNotificationManager.activeNotifications.count { it.id == NOTIFICATION_ID })
    }

    @Test
    fun `notifyAutoDisabled posts a one-shot alert on the shared channel`() {
        KeepaliveNotification.notifyAutoDisabled(context, "Vodafone")

        val channel =
            shadowNotificationManager.notificationChannels
                .find { (it as NotificationChannel).id == CHANNEL_ID } as? NotificationChannel
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)

        val posted =
            shadowNotificationManager.activeNotifications
                .find { it.id == AUTO_DISABLED_NOTIFICATION_ID }
        assertNotNull(posted)
        val notification = posted!!.notification
        assertTrue(hasFlag(notification, Notification.FLAG_AUTO_CANCEL))
        assertFalse(hasFlag(notification, Notification.FLAG_ONGOING_EVENT))
        assertEquals(
            context.getString(R.string.notification_auto_disabled_title, "Vodafone"),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.resources.getQuantityString(
                R.plurals.notification_auto_disabled_text,
                AppConfig.MAX_SIM_NOT_PRESENT_FAILURES,
                "Vodafone",
                AppConfig.MAX_SIM_NOT_PRESENT_FAILURES,
            ),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        // The alert is independent of the ongoing hint.
        assertNull(shadowNotificationManager.activeNotifications.find { it.id == NOTIFICATION_ID })
    }

    @Test
    fun `dismiss removes the hint`() {
        KeepaliveNotification.show(context)
        assertNotNull(shadowNotificationManager.activeNotifications.find { it.id == NOTIFICATION_ID })

        KeepaliveNotification.dismiss(context)

        assertNull(shadowNotificationManager.activeNotifications.find { it.id == NOTIFICATION_ID })
    }

    private fun hasFlag(
        notification: Notification,
        flag: Int,
    ): Boolean = notification.flags and flag != 0

    private companion object {
        const val CHANNEL_ID = "keepalive_status_channel"
        const val NOTIFICATION_ID = 1
        const val AUTO_DISABLED_NOTIFICATION_ID = 2
    }
}

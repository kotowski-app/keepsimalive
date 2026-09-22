package app.kotowski.keepsimalive.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.kotowski.keepsimalive.MainActivity
import app.kotowski.keepsimalive.R

private const val CHANNEL_ID = "keepalive_status_channel"
private const val NOTIFICATION_ID = 1
private const val AUTO_DISABLED_NOTIFICATION_ID = 2

// The persistent "keepalive is running" hint: informational only, tap opens the app.
object KeepaliveNotification {
    fun show(context: Context) {
        try {
            createChannel(context)
            val notification = buildNotification(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.w("KeepaliveNotification", "Failed to show notification: ${e.javaClass.simpleName}", e)
        }
    }

    // One-shot "keepalive was auto-disabled" alert on the same channel: posted when the
    // engine disables a schedule after too many consecutive SIM-not-present failures, so
    // the disable is visible without opening the app. `simName` is the SIM's display name
    // (the last known identity, since the SIM is absent by definition). Tap opens the app
    // and clears the alert (auto-cancel), unlike the ongoing hint.
    fun notifyAutoDisabled(
        context: Context,
        simName: String,
    ) {
        try {
            createChannel(context)
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .apply {
                        setContentTitle(context.getString(R.string.notification_auto_disabled_title, simName))
                        setContentText(
                            context.resources.getQuantityString(
                                R.plurals.notification_auto_disabled_text,
                                AppConfig.MAX_SIM_NOT_PRESENT_FAILURES,
                                simName,
                                AppConfig.MAX_SIM_NOT_PRESENT_FAILURES,
                            ),
                        )
                        setSmallIcon(R.drawable.ic_notification)
                        setContentIntent(contentIntent(context))
                        setAutoCancel(true)
                        setPriority(NotificationCompat.PRIORITY_LOW)
                    }.build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(AUTO_DISABLED_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Logger.w("KeepaliveNotification", "Failed to show auto-disabled notification: ${e.javaClass.simpleName}", e)
        }
    }

    fun dismiss(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Logger.w("KeepaliveNotification", "Failed to dismiss notification: ${e.javaClass.simpleName}", e)
        }
    }

    // Below API 26 notification channels do not exist: NotificationCompat ignores the
    // channel id there, so nothing is registered on those devices.
    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sim_config_toggle),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(context: Context) =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .apply {
                setContentTitle(context.getString(R.string.app_name))
                setContentText(context.getString(R.string.notification_status_text))
                setSmallIcon(R.drawable.ic_notification)
                setContentIntent(contentIntent(context))
                setOngoing(true)
                setPriority(NotificationCompat.PRIORITY_LOW)
            }.build()

    private fun contentIntent(context: Context) =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

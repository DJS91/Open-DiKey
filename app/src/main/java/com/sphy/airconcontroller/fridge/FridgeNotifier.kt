package com.sphy.airconcontroller.fridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sphy.airconcontroller.R

/** Posts/cancels the fridge alert notifications produced by [FridgeAlerts]. */
class FridgeNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.fridge_alert_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.fridge_alert_channel_description) },
        )
    }

    fun handle(event: FridgeAlerts.Event, fridgeName: String?) {
        val name = fridgeName ?: context.getString(R.string.fridge_title)
        when (event) {
            FridgeAlerts.Event.Disconnected -> post(
                ID_DISCONNECTED,
                context.getString(R.string.fridge_alert_disconnected_title, name),
                context.getString(R.string.fridge_alert_disconnected_body),
            )
            FridgeAlerts.Event.Reconnected -> manager.cancel(ID_DISCONNECTED)
            is FridgeAlerts.Event.Warm -> {
                val side = context.getString(if (event.compartment == 1) R.string.fridge_left else R.string.fridge_right)
                val unit = if (event.celsius) "C" else "F"
                post(
                    ID_WARM + event.compartment,
                    context.getString(R.string.fridge_alert_warm_title, side, name),
                    context.getString(R.string.fridge_alert_warm_body, side, event.temperature, event.target, unit),
                )
            }
            is FridgeAlerts.Event.WarmCleared -> manager.cancel(ID_WARM + event.compartment)
        }
    }

    private fun post(id: Int, title: String, body: String) {
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, FridgeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Android 13+ without the notification permission.
            Log.w(TAG, "Notification permission missing", e)
        }
    }

    companion object {
        private const val TAG = "FridgeNotifier"
        const val CHANNEL_ID = "fridge_alerts"
        private const val ID_DISCONNECTED = 4300
        private const val ID_WARM = 4310
    }
}

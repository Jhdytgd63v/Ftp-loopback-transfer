package com.sandbox.ftptransfer.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Utility for creating and updating app notifications.
 * Created: 2025-11-21T12:17:22.506Z
 */
object AppNotificationManager {
    private const val DEFAULT_CHANNEL_ID = "ftp_loopback_channel"
    private const val DEFAULT_CHANNEL_NAME = "FTP Loopback"

    private fun ensureChannel(context: Context, channelId: String = DEFAULT_CHANNEL_ID) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(channelId, DEFAULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                channel.description = "Status and progress notifications"
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun notifyStatus(
        context: Context,
        id: Int,
        title: String,
        text: String,
        ongoing: Boolean = false,
        channelId: String = DEFAULT_CHANNEL_ID
    ) {
        ensureChannel(context, channelId)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}

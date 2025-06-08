package ru.yandexpraktikum.blechat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BleChat : Application() {

    var channel: NotificationChannel? = null
    var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        setUpNotificationsChannel()
    }

    private fun setUpNotificationsChannel() {
        channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        channel?.let { notificationManager?.createNotificationChannel(it) }
    }

    companion object {
        private const val CHANNEL_ID = "channel_id"
        private const val CHANNEL_NAME = "Message"
    }
}
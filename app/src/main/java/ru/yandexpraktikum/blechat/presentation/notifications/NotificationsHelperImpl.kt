package ru.yandexpraktikum.blechat.presentation.notifications

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.yandexpraktikum.blechat.R
import ru.yandexpraktikum.blechat.utils.checkNotificationPermission
import javax.inject.Inject

class NotificationsHelperImpl
@Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManagerCompat: NotificationManagerCompat
) : NotificationsHelper {

    override fun notifyOnMessageReceived(title: String, message: String) {
        val notificationBuilder =
            NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.bluetooth_ic)
                .setAutoCancel(true).setContentTitle(title).setContentText(message)

        context.checkNotificationPermission {
            notificationManagerCompat.notify(NOTIFICATION_ID, notificationBuilder.build())
        }
    }

    companion object {
        private const val CHANNEL_ID = "channel_id"
        private const val NOTIFICATION_ID = 1234
    }
}
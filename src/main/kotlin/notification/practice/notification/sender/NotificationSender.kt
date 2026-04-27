package notification.practice.notification.sender

import notification.practice.notification.Notification
import notification.practice.notification.NotificationChannel

interface NotificationSender {
    fun supports(channel: NotificationChannel): Boolean

    fun send(notification: Notification)
}

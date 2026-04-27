package notification.practice.notification.sender

import notification.practice.notification.domain.Notification
import notification.practice.notification.domain.NotificationChannel

interface NotificationSender {
    fun supports(channel: NotificationChannel): Boolean

    fun send(notification: Notification)
}

package notification.practice.notification.dispatcher

import notification.practice.notification.domain.Notification

interface NotificationDispatcher {
    fun dispatch(notification: Notification)
}

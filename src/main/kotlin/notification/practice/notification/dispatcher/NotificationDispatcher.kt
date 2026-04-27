package notification.practice.notification.dispatcher

import notification.practice.notification.Notification

interface NotificationDispatcher {
    fun dispatch(notification: Notification)
}

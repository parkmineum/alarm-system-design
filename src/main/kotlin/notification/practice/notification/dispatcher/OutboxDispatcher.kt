package notification.practice.notification.dispatcher

import notification.practice.notification.Notification
import org.springframework.stereotype.Component

@Component
class OutboxDispatcher : NotificationDispatcher {
    override fun dispatch(notification: Notification) {
        // no-op: notification is PENDING in DB, worker handles dispatch
    }
}

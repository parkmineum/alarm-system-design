package notification.practice.notification.dispatcher

import notification.practice.notification.Notification
import notification.practice.notification.sender.NotificationSenderRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SyncDispatcher(
    private val senderRegistry: NotificationSenderRegistry,
) : NotificationDispatcher {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun dispatch(notification: Notification) {
        try {
            senderRegistry.find(notification.channel).send(notification)
            notification.markSent()
        } catch (e: Exception) {
            log.warn("[dispatch] send failed id={} reason={}", notification.id, e.message)
            notification.markFailed(e.message ?: e.javaClass.simpleName)
        }
    }
}

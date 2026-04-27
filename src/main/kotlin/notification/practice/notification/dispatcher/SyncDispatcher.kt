package notification.practice.notification.dispatcher

import notification.practice.notification.domain.Notification
import notification.practice.notification.repository.NotificationRepository
import notification.practice.notification.sender.NotificationSenderRegistry
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

class SyncDispatcher(
    private val senderRegistry: NotificationSenderRegistry,
    private val notifications: NotificationRepository,
) : NotificationDispatcher {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun dispatch(notification: Notification) {
        try {
            senderRegistry.find(notification.channel).send(notification)
            notification.markSent()
        } catch (e: Exception) {
            log.warn("[dispatch] send failed id={} reason={}", notification.id, e.message)
            notification.markFailed(e.message ?: e.javaClass.simpleName)
        }
        notifications.save(notification)
    }
}

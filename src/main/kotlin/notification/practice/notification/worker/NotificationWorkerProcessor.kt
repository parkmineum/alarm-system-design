package notification.practice.notification.worker

import notification.practice.notification.Notification
import notification.practice.notification.NotificationRepository
import notification.practice.notification.sender.NotificationSenderRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class NotificationWorkerProcessor(
    private val notifications: NotificationRepository,
    private val senderRegistry: NotificationSenderRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun process(notification: Notification) {
        try {
            senderRegistry.find(notification.channel).send(notification)
            notification.markSent()
        } catch (e: Exception) {
            log.warn("[worker] dispatch failed id={} reason={}", notification.id, e.message)
            notification.markFailed(e.message ?: e.javaClass.simpleName)
        }
        notifications.save(notification)
    }
}

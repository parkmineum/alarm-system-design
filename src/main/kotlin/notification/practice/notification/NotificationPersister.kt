package notification.practice.notification

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class NotificationPersister(private val notifications: NotificationRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun insert(notification: Notification): Notification = notifications.saveAndFlush(notification)
}

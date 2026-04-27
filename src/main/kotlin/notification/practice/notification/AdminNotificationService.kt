package notification.practice.notification

import notification.practice.notification.dto.NotificationResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AdminNotificationService(
    private val notifications: NotificationRepository,
) {
    @Transactional(readOnly = true)
    fun listDeadLetters(): List<NotificationResponse> = notifications.findDeadLetters().map(NotificationResponse::from)

    @Transactional
    fun retry(
        id: Long,
        actorId: String,
    ): NotificationResponse {
        val notification = notifications.findById(id).orElseThrow { NotificationNotFoundException(id) }
        if (notification.status != NotificationStatus.DEAD_LETTER) throw NotificationNotDeadLetterException(id)
        if (notification.manualRetryCount >= notification.maxManualRetries) {
            throw ManualRetryLimitExceededException(id, notification.maxManualRetries)
        }
        notification.requeue(actorId, Instant.now())
        return NotificationResponse.from(notification)
    }
}

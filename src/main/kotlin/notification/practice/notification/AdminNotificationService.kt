package notification.practice.notification

import notification.practice.notification.dto.NotificationResponse
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AdminNotificationService(
    private val notifications: NotificationRepository,
) {
    @Transactional(readOnly = true)
    fun listDeadLetters(
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): List<NotificationResponse> =
        notifications
            .findDeadLetters(PageRequest.of(page, size.coerceIn(1, MAX_PAGE_SIZE)))
            .map(NotificationResponse::from)

    @Transactional
    fun retry(
        id: Long,
        actorId: String,
    ): NotificationResponse {
        require(actorId.isNotBlank()) { "actorId 는 비어있을 수 없습니다" }
        require(actorId.length <= MAX_ACTOR_ID_LENGTH) { "actorId 는 ${MAX_ACTOR_ID_LENGTH}자 이하이어야 합니다" }

        val notification = notifications.findByIdForUpdate(id).orElseThrow { NotificationNotFoundException(id) }
        if (notification.status != NotificationStatus.DEAD_LETTER) throw NotificationNotDeadLetterException(id)
        if (notification.manualRetryCount >= Notification.DEFAULT_MAX_MANUAL_RETRIES) {
            throw ManualRetryLimitExceededException(id, Notification.DEFAULT_MAX_MANUAL_RETRIES)
        }
        notification.requeue(actorId, Instant.now())
        return NotificationResponse.from(notification)
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 50
        private const val MAX_PAGE_SIZE = 200
        private const val MAX_ACTOR_ID_LENGTH = 64
    }
}

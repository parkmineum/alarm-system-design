package notification.practice.notification

import notification.practice.notification.dispatcher.NotificationDispatcher
import notification.practice.notification.dto.NotificationResponse
import notification.practice.notification.dto.RegisterNotificationRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationService(
    private val notifications: NotificationRepository,
    private val dispatcher: NotificationDispatcher,
) {
    @Transactional
    fun register(request: RegisterNotificationRequest): NotificationResponse {
        val recipientId = requireNotNull(request.recipientId)
        val type = requireNotNull(request.type)
        val channel = requireNotNull(request.channel)
        val refType = requireNotNull(request.refType)
        val refId = requireNotNull(request.refId)

        val key =
            IdempotencyKey.derive(
                type = type,
                refType = refType,
                refId = refId,
                recipientId = recipientId,
                channel = channel,
            )

        notifications.findByIdempotencyKey(key)?.let { return NotificationResponse.from(it) }

        val saved =
            try {
                notifications.saveAndFlush(
                    Notification(
                        recipientId = recipientId,
                        type = type,
                        channel = channel,
                        refType = refType,
                        refId = refId,
                        payload = request.payload,
                        idempotencyKey = key,
                    ),
                )
            } catch (e: DataIntegrityViolationException) {
                val existing =
                    notifications.findByIdempotencyKey(key)
                        ?: throw IllegalStateException("멱등성 충돌 후 row 조회 실패", e)
                return NotificationResponse.from(existing)
            }

        dispatcher.dispatch(saved)
        return NotificationResponse.from(saved)
    }

    @Transactional(readOnly = true)
    fun get(id: Long): NotificationResponse {
        val notification = notifications.findById(id).orElseThrow { NotificationNotFoundException(id) }
        return NotificationResponse.from(notification)
    }

    @Transactional(readOnly = true)
    fun listInbox(
        recipientId: Long,
        read: Boolean?,
    ): List<NotificationResponse> {
        val rows =
            when (read) {
                null -> notifications.findByRecipientIdOrderByCreatedAtDesc(recipientId)
                true -> notifications.findByRecipientIdAndReadAtIsNotNullOrderByCreatedAtDesc(recipientId)
                false -> notifications.findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(recipientId)
            }
        return rows.map(NotificationResponse::from)
    }
}

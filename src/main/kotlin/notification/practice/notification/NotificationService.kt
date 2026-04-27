package notification.practice.notification

import notification.practice.notification.dto.NotificationResponse
import notification.practice.notification.dto.RegisterNotificationRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class NotificationService(
    private val notifications: NotificationRepository,
    private val persister: NotificationPersister,
) {
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

        notifications.findByIdempotencyKey(key)?.let { existing ->
            if (existing.payload != request.payload) throw NotificationIdempotencyConflictException(key)
            return NotificationResponse.from(existing)
        }

        val saved =
            try {
                persister.insert(
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
                if (!isUniqueViolation(e)) throw e
                notifications.findByIdempotencyKey(key)
                    ?: throw IllegalStateException("멱등성 충돌 후 row 조회 실패", e)
            }

        return NotificationResponse.from(saved)
    }

    @Transactional(readOnly = true)
    fun get(
        id: Long,
        requesterId: Long,
    ): NotificationResponse {
        val notification = notifications.findById(id).orElseThrow { NotificationNotFoundException(id) }
        if (notification.recipientId != requesterId) throw NotificationNotFoundException(id)
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

    @Transactional
    fun markRead(
        id: Long,
        requesterId: Long,
    ): NotificationResponse {
        val notification = notifications.findById(id).orElseThrow { NotificationNotFoundException(id) }
        if (notification.recipientId != requesterId) throw NotificationNotFoundException(id)
        notifications.markReadIfUnread(id, Instant.now())
        return NotificationResponse.from(notifications.findById(id).orElseThrow { NotificationNotFoundException(id) })
    }

    private fun isUniqueViolation(e: DataIntegrityViolationException): Boolean {
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is java.sql.SQLException) {
                return cause.sqlState?.startsWith("23") == true
            }
            cause = cause.cause
        }
        return false
    }
}

package notification.practice.notification.service
import notification.practice.notification.controller.dto.NotificationResponse
import notification.practice.notification.controller.dto.RegisterNotificationRequest
import notification.practice.notification.domain.IdempotencyKey
import notification.practice.notification.domain.Notification
import notification.practice.notification.exception.NotificationIdempotencyConflictException
import notification.practice.notification.exception.NotificationNotFoundException
import notification.practice.notification.repository.NotificationRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

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
        val scheduledAt = request.scheduledAt?.truncatedTo(ChronoUnit.MILLIS)

        require(scheduledAt == null || scheduledAt.isAfter(Instant.now())) {
            "scheduledAt 은 미래 시각이어야 합니다"
        }

        val key =
            IdempotencyKey.derive(
                type = type,
                refType = refType,
                refId = refId,
                recipientId = recipientId,
                channel = channel,
                scheduledAt = scheduledAt,
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
                        scheduledAt = scheduledAt ?: Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    ),
                )
            } catch (e: DataIntegrityViolationException) {
                if (!isUniqueViolation(e)) throw e
                val existing =
                    notifications.findByIdempotencyKey(key)
                        ?: throw IllegalStateException("멱등성 충돌 후 row 조회 실패", e)
                if (existing.payload != request.payload) throw NotificationIdempotencyConflictException(key)
                existing
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

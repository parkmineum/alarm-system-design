package notification.practice.notification

import org.springframework.data.jpa.repository.JpaRepository

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findByIdempotencyKey(idempotencyKey: String): Notification?

    fun findByRecipientIdOrderByCreatedAtDesc(recipientId: Long): List<Notification>

    fun findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(recipientId: Long): List<Notification>

    fun findByRecipientIdAndReadAtIsNotNullOrderByCreatedAtDesc(recipientId: Long): List<Notification>
}

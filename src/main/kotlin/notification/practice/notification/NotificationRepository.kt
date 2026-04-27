package notification.practice.notification

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findByIdempotencyKey(idempotencyKey: String): Notification?

    @Query("SELECT n FROM Notification n WHERE n.recipientId = :recipientId ORDER BY n.createdAt DESC, n.id DESC")
    fun findByRecipientIdOrderByCreatedAtDesc(
        @Param("recipientId") recipientId: Long,
    ): List<Notification>

    @Query("SELECT n FROM Notification n WHERE n.recipientId = :recipientId AND n.readAt IS NULL ORDER BY n.createdAt DESC, n.id DESC")
    fun findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(
        @Param("recipientId") recipientId: Long,
    ): List<Notification>

    @Query("SELECT n FROM Notification n WHERE n.recipientId = :recipientId AND n.readAt IS NOT NULL ORDER BY n.createdAt DESC, n.id DESC")
    fun findByRecipientIdAndReadAtIsNotNullOrderByCreatedAtDesc(
        @Param("recipientId") recipientId: Long,
    ): List<Notification>
}

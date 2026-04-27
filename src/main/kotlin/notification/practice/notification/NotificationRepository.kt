package notification.practice.notification

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional

interface NotificationRepository : JpaRepository<Notification, Long> {
    fun findByIdempotencyKey(idempotencyKey: String): Notification?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM Notification n WHERE n.id = :id")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): Optional<Notification>

    @Query(
        """SELECT n FROM Notification n
           WHERE n.status = 'PENDING' AND n.scheduledAt <= :now
           ORDER BY n.scheduledAt ASC, n.id ASC""",
    )
    fun findPendingDispatchable(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Notification>

    @Query(
        """SELECT n FROM Notification n
           WHERE n.status = 'FAILED' AND n.nextRetryAt <= :now
           ORDER BY n.nextRetryAt ASC, n.id ASC""",
    )
    fun findRetriableDispatchable(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Notification>

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

    @Query("SELECT n FROM Notification n WHERE n.status = 'DEAD_LETTER' ORDER BY n.createdAt DESC, n.id DESC")
    fun findDeadLetters(pageable: Pageable): List<Notification>
}

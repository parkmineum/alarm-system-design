package notification.practice.notification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.temporal.ChronoUnit

@Entity
@Table(
    name = "notification",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_notification_idempotency_key",
            columnNames = ["idempotency_key"],
        ),
    ],
    indexes = [
        Index(
            name = "ix_notification_recipient_read",
            columnList = "recipient_id, read_at",
        ),
        Index(
            name = "ix_notification_status_scheduled",
            columnList = "status, scheduled_at",
        ),
        Index(
            name = "ix_notification_status_retry",
            columnList = "status, next_retry_at",
        ),
    ],
)
class Notification(
    @Column(name = "recipient_id", nullable = false)
    val recipientId: Long,
    @Column(nullable = false, length = 50)
    val type: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val channel: NotificationChannel,
    @Column(name = "ref_type", nullable = false, length = 50)
    val refType: String,
    @Column(name = "ref_id", nullable = false, length = 64)
    val refId: String,
    @Column(columnDefinition = "TEXT")
    val payload: String? = null,
    @Column(name = "idempotency_key", nullable = false, length = 64)
    val idempotencyKey: String,
    @Column(name = "scheduled_at", nullable = false)
    val scheduledAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: NotificationStatus = NotificationStatus.PENDING
        protected set

    @Column(name = "auto_attempt_count", nullable = false)
    var autoAttemptCount: Int = 0
        protected set

    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = null
        protected set

    @Column(name = "last_error", length = 500)
    var lastError: String? = null
        protected set

    @Column(name = "read_at")
    var readAt: Instant? = null
        protected set

    @Column(name = "processed_at")
    var processedAt: Instant? = null
        protected set

    @Column(name = "manual_retry_count", nullable = false)
    var manualRetryCount: Int = 0
        protected set

    @Column(name = "max_manual_retries", nullable = false)
    val maxManualRetries: Int = DEFAULT_MAX_MANUAL_RETRIES

    @Column(name = "last_manual_retry_at")
    var lastManualRetryAt: Instant? = null
        protected set

    @Column(name = "last_manual_retry_actor_id", length = 64)
    var lastManualRetryActorId: String? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
        protected set

    fun markProcessing(now: Instant = Instant.now()) {
        status = NotificationStatus.PROCESSING
        updatedAt = now
    }

    fun markSent(now: Instant = Instant.now()) {
        status = NotificationStatus.SENT
        processedAt = now
        lastError = null
        updatedAt = now
    }

    fun markFailed(
        reason: String,
        now: Instant = Instant.now(),
    ) {
        autoAttemptCount++
        lastError = reason.take(MAX_ERROR_LENGTH)
        processedAt = now
        updatedAt = now

        if (autoAttemptCount >= DEFAULT_MAX_AUTO_ATTEMPTS) {
            status = NotificationStatus.DEAD_LETTER
            nextRetryAt = null
        } else {
            status = NotificationStatus.FAILED
            nextRetryAt = now.plusSeconds(BACKOFF_SECONDS[autoAttemptCount - 1])
        }
    }

    fun requeue(
        actorId: String,
        now: Instant = Instant.now(),
    ) {
        status = NotificationStatus.PENDING
        autoAttemptCount = 0
        nextRetryAt = null
        manualRetryCount++
        lastManualRetryAt = now
        lastManualRetryActorId = actorId
        updatedAt = now
    }

    companion object {
        private const val MAX_ERROR_LENGTH = 500
        const val DEFAULT_MAX_AUTO_ATTEMPTS = 5
        const val DEFAULT_MAX_MANUAL_RETRIES = 3

        // 30초 → 2분 → 10분 → 30분 (4회 재시도, 5회째 DEAD_LETTER)
        val BACKOFF_SECONDS = listOf(30L, 120L, 600L, 1800L)

        init {
            require(BACKOFF_SECONDS.size == DEFAULT_MAX_AUTO_ATTEMPTS - 1) {
                "BACKOFF_SECONDS.size(${BACKOFF_SECONDS.size}) != DEFAULT_MAX_AUTO_ATTEMPTS-1(${DEFAULT_MAX_AUTO_ATTEMPTS - 1})"
            }
        }
    }
}

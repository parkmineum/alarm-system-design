package notification.practice.notification.worker

import notification.practice.notification.controller.dto.RegisterNotificationRequest
import notification.practice.notification.domain.Notification
import notification.practice.notification.domain.NotificationChannel
import notification.practice.notification.domain.NotificationStatus
import notification.practice.notification.repository.NotificationRepository
import notification.practice.notification.sender.NotificationSender
import notification.practice.notification.sender.NotificationSenderRegistry
import notification.practice.notification.service.NotificationService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals

@SpringBootTest
@TestPropertySource(
    properties = [
        "notification.worker.poll-interval-ms=999999",
        "notification.processing-timeout.check-interval-ms=999999",
    ],
)
class ProcessingTimeoutRecoveryTest
    @Autowired
    constructor(
        private val service: NotificationService,
        private val notifications: NotificationRepository,
        private val recoveryJob: ProcessingTimeoutRecoveryJob,
        private val tx: TransactionTemplate,
        private val jdbc: JdbcTemplate,
    ) {
        @MockBean
        private lateinit var senderRegistry: NotificationSenderRegistry

        @Test
        fun `PROCESSING 상태가 처리 타임아웃을 초과하면 PENDING 으로 복원된다`() {
            val sender = org.mockito.kotlin.mock<NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(sender)

            val response = service.register(sampleRequest(recipientId = 500L, refId = "timeout-1"))
            forceProcessingWithOldUpdatedAt(response.id, ProcessingTimeoutRecoveryJob.PROCESSING_TIMEOUT_SECONDS + 60)

            recoveryJob.recover()

            val stored = load(response.id)
            assertEquals(NotificationStatus.PENDING, stored.status)
        }

        @Test
        fun `PROCESSING 상태가 타임아웃 미만이면 복원하지 않는다`() {
            val sender = org.mockito.kotlin.mock<NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(sender)

            val response = service.register(sampleRequest(recipientId = 501L, refId = "timeout-2"))
            forceProcessingWithOldUpdatedAt(response.id, ProcessingTimeoutRecoveryJob.PROCESSING_TIMEOUT_SECONDS - 60)

            recoveryJob.recover()

            val stored = load(response.id)
            assertEquals(NotificationStatus.PROCESSING, stored.status)
        }

        private fun forceProcessingWithOldUpdatedAt(
            id: Long,
            secondsAgo: Long,
        ) {
            jdbc.update(
                "UPDATE notification SET status = 'PROCESSING', updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(secondsAgo)),
                id,
            )
        }

        private fun load(id: Long): Notification = tx.execute { notifications.findById(id).orElseThrow() }!!

        private fun sampleRequest(
            recipientId: Long,
            refId: String,
        ) = RegisterNotificationRequest(
            recipientId = recipientId,
            type = "COURSE_ENROLLMENT_COMPLETED",
            channel = NotificationChannel.EMAIL,
            refType = "COURSE",
            refId = refId,
            payload = null,
        )
    }

package notification.practice.notification

import notification.practice.notification.dto.RegisterNotificationRequest
import notification.practice.notification.sender.NotificationSenderRegistry
import notification.practice.notification.worker.NotificationWorker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@TestPropertySource(properties = ["notification.worker.poll-interval-ms=999999"])
class ManualRetryTest
    @Autowired
    constructor(
        private val service: NotificationService,
        private val adminService: AdminNotificationService,
        private val notifications: NotificationRepository,
        private val worker: NotificationWorker,
        private val tx: TransactionTemplate,
        private val jdbc: JdbcTemplate,
    ) {
        @MockBean
        private lateinit var senderRegistry: NotificationSenderRegistry

        @Test
        fun `DEAD_LETTER 수동 재시도 시 PENDING 복귀, autoAttemptCount 초기화, manualRetryCount 증가`() {
            val failingSender = mock<notification.practice.notification.sender.NotificationSender>()
            doThrow(RuntimeException("permanent failure")).whenever(failingSender).send(any())
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val response = service.register(sampleRequest(recipientId = 500L, refId = "manual-retry-1"))
            driveToDeadLetter(response.id)

            val stored = load(response.id)
            assertEquals(NotificationStatus.DEAD_LETTER, stored.status)

            val retried = adminService.retry(response.id, "admin-user")

            assertEquals(NotificationStatus.PENDING, retried.status)
            assertEquals(0, retried.autoAttemptCount)
            assertEquals(1, retried.manualRetryCount)
            assertNotNull(retried.lastManualRetryAt)
        }

        @Test
        fun `수동 재시도 후 워커가 재처리하면 SENT 로 전이된다`() {
            val failingSender = mock<notification.practice.notification.sender.NotificationSender>()
            var callCount = 0
            whenever(senderRegistry.find(any())).thenReturn(failingSender)
            org.mockito.kotlin.doAnswer { if (++callCount <= Notification.DEFAULT_MAX_AUTO_ATTEMPTS) throw RuntimeException("fail") }
                .whenever(failingSender).send(any())

            val response = service.register(sampleRequest(recipientId = 501L, refId = "manual-retry-2"))
            driveToDeadLetter(response.id)

            adminService.retry(response.id, "admin-user")

            worker.poll()

            assertEquals(NotificationStatus.SENT, load(response.id).status)
        }

        @Test
        fun `DEAD_LETTER 가 아닌 알림에 수동 재시도 시 예외가 발생한다`() {
            val okSender = mock<notification.practice.notification.sender.NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(okSender)

            val response = service.register(sampleRequest(recipientId = 502L, refId = "manual-retry-3"))
            worker.poll()
            assertEquals(NotificationStatus.SENT, load(response.id).status)

            assertThrows<NotificationNotDeadLetterException> {
                adminService.retry(response.id, "admin-user")
            }
        }

        @Test
        fun `수동 재시도 한도 초과 시 ManualRetryLimitExceededException 발생`() {
            val failingSender = mock<notification.practice.notification.sender.NotificationSender>()
            doThrow(RuntimeException("permanent failure")).whenever(failingSender).send(any())
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val response = service.register(sampleRequest(recipientId = 503L, refId = "manual-retry-4"))
            driveToDeadLetter(response.id)

            repeat(Notification.DEFAULT_MAX_MANUAL_RETRIES) { i ->
                adminService.retry(response.id, "admin-$i")
                driveToDeadLetter(response.id)
            }

            assertThrows<ManualRetryLimitExceededException> {
                adminService.retry(response.id, "admin-over")
            }
        }

        @Test
        fun `listDeadLetters 는 DEAD_LETTER 상태 알림만 반환한다`() {
            val failingSender = mock<notification.practice.notification.sender.NotificationSender>()
            doThrow(RuntimeException("permanent failure")).whenever(failingSender).send(any())
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val dlResponse = service.register(sampleRequest(recipientId = 504L, refId = "manual-retry-5"))
            driveToDeadLetter(dlResponse.id)

            val deadLetters = adminService.listDeadLetters()

            assert(deadLetters.any { it.id == dlResponse.id })
            assert(deadLetters.all { it.status == NotificationStatus.DEAD_LETTER })
        }

        @Test
        fun `actorId 가 lastManualRetryActorId 에 기록된다`() {
            val failingSender = mock<notification.practice.notification.sender.NotificationSender>()
            doThrow(RuntimeException("permanent failure")).whenever(failingSender).send(any())
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val response = service.register(sampleRequest(recipientId = 505L, refId = "manual-retry-6"))
            driveToDeadLetter(response.id)

            adminService.retry(response.id, "ops-team")

            val actorId =
                jdbc.queryForObject(
                    "SELECT last_manual_retry_actor_id FROM notification WHERE id = ?",
                    String::class.java,
                    response.id,
                )
            assertEquals("ops-team", actorId)
        }

        private fun driveToDeadLetter(id: Long) {
            repeat(Notification.DEFAULT_MAX_AUTO_ATTEMPTS) { attempt ->
                if (attempt > 0) forceNextRetryAtPast(id)
                if (load(id).status == NotificationStatus.DEAD_LETTER) return
                worker.poll()
            }
        }

        private fun load(id: Long): Notification = tx.execute { notifications.findById(id).orElseThrow() }!!

        private fun forceNextRetryAtPast(id: Long) {
            jdbc.update(
                "UPDATE notification SET next_retry_at = ?, status = 'FAILED' WHERE id = ?",
                Timestamp.from(Instant.EPOCH),
                id,
            )
        }

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

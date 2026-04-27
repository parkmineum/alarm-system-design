package notification.practice.notification.service
import notification.practice.notification.controller.dto.RegisterNotificationRequest
import notification.practice.notification.domain.Notification
import notification.practice.notification.domain.NotificationChannel
import notification.practice.notification.domain.NotificationStatus
import notification.practice.notification.exception.ManualRetryLimitExceededException
import notification.practice.notification.exception.NotificationNotDeadLetterException
import notification.practice.notification.repository.NotificationRepository
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
            val failingSender = failingSender("permanent failure")
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val response = service.register(sampleRequest(recipientId = 500L, refId = "manual-retry-1"))
            driveToDeadLetter(response.id)

            val retried = adminService.retry(response.id, "admin-user")

            assertEquals(NotificationStatus.PENDING, retried.status)
            assertEquals(0, retried.autoAttemptCount)
            assertEquals(1, retried.manualRetryCount)
            assertNotNull(retried.lastManualRetryAt)
            assertNull(load(response.id).nextRetryAt)
        }

        @Test
        fun `requeue 후 lastError 가 초기화된다`() {
            val failingSender = failingSender("some error")
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val response = service.register(sampleRequest(recipientId = 510L, refId = "manual-retry-err"))
            driveToDeadLetter(response.id)

            val retried = adminService.retry(response.id, "admin-user")

            assertNull(retried.lastError)
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
        fun `SENT 상태 알림에 수동 재시도 시 NotificationNotDeadLetterException 이 발생한다`() {
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
        fun `PENDING 상태 알림에 수동 재시도 시 NotificationNotDeadLetterException 이 발생한다`() {
            val response = service.register(sampleRequest(recipientId = 506L, refId = "manual-retry-pending"))

            assertThrows<NotificationNotDeadLetterException> {
                adminService.retry(response.id, "admin-user")
            }
        }

        @Test
        fun `마지막 허용 회차 retry 는 성공하고 한도 초과 직후는 예외가 발생한다`() {
            val failingSender = failingSender("permanent failure")
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val response = service.register(sampleRequest(recipientId = 507L, refId = "manual-retry-boundary"))
            driveToDeadLetter(response.id)

            repeat(Notification.DEFAULT_MAX_MANUAL_RETRIES - 1) { i ->
                adminService.retry(response.id, "admin-$i")
                driveToDeadLetter(response.id)
            }

            // 마지막 허용 회차 — 예외 없이 성공해야 한다
            val lastAllowed = adminService.retry(response.id, "admin-last")
            assertEquals(Notification.DEFAULT_MAX_MANUAL_RETRIES, lastAllowed.manualRetryCount)

            driveToDeadLetter(response.id)

            assertThrows<ManualRetryLimitExceededException> {
                adminService.retry(response.id, "admin-over")
            }
        }

        @Test
        fun `수동 재시도 한도 초과 시 ManualRetryLimitExceededException 발생`() {
            val failingSender = failingSender("permanent failure")
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
            val failingSender = failingSender("permanent failure")
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val dlResponse = service.register(sampleRequest(recipientId = 504L, refId = "manual-retry-5"))
            driveToDeadLetter(dlResponse.id)

            val deadLetters = adminService.listDeadLetters()

            assert(deadLetters.any { it.id == dlResponse.id })
            assert(deadLetters.all { it.status == NotificationStatus.DEAD_LETTER })
        }

        @Test
        fun `actorId 가 lastManualRetryActorId 에 기록된다`() {
            val failingSender = failingSender("permanent failure")
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

        @Test
        fun `actorId 가 빈 문자열이면 IllegalArgumentException 이 발생한다`() {
            val failingSender = failingSender("permanent failure")
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val response = service.register(sampleRequest(recipientId = 508L, refId = "manual-retry-blank"))
            driveToDeadLetter(response.id)

            assertThrows<IllegalArgumentException> {
                adminService.retry(response.id, "   ")
            }
        }

        @Test
        fun `actorId 가 64자를 초과하면 IllegalArgumentException 이 발생한다`() {
            val failingSender = failingSender("permanent failure")
            whenever(senderRegistry.find(any())).thenReturn(failingSender)

            val response = service.register(sampleRequest(recipientId = 509L, refId = "manual-retry-long"))
            driveToDeadLetter(response.id)

            assertThrows<IllegalArgumentException> {
                adminService.retry(response.id, "a".repeat(65))
            }
        }

        private fun driveToDeadLetter(id: Long) {
            // 첫 poll 전에 미리 nextRetryAt 을 과거로 설정해 FAILED 알림도 확실히 픽업되도록 한다
            forceRetriable(id)
            repeat(Notification.DEFAULT_MAX_AUTO_ATTEMPTS + 1) {
                if (load(id).status == NotificationStatus.DEAD_LETTER) return
                worker.poll()
                forceRetriable(id)
            }
        }

        private fun load(id: Long): Notification = tx.execute { notifications.findById(id).orElseThrow() }!!

        private fun forceRetriable(id: Long) {
            jdbc.update(
                """UPDATE notification SET next_retry_at = ?
                   WHERE id = ? AND status = 'FAILED'""",
                Timestamp.from(Instant.EPOCH),
                id,
            )
        }

        private fun failingSender(message: String) =
            mock<notification.practice.notification.sender.NotificationSender>().also {
                doThrow(RuntimeException(message)).whenever(it).send(any())
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

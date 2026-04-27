package notification.practice.notification

import notification.practice.notification.dto.RegisterNotificationRequest
import notification.practice.notification.sender.NotificationSender
import notification.practice.notification.sender.NotificationSenderRegistry
import notification.practice.notification.worker.NotificationWorker
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
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
import kotlin.test.assertTrue

@SpringBootTest
@TestPropertySource(properties = ["notification.worker.poll-interval-ms=999999"])
class NotificationRetryTest
    @Autowired
    constructor(
        private val service: NotificationService,
        private val notifications: NotificationRepository,
        private val worker: NotificationWorker,
        private val tx: TransactionTemplate,
        private val jdbc: JdbcTemplate,
    ) {
        @MockBean
        private lateinit var senderRegistry: NotificationSenderRegistry

        @Test
        fun `첫 번째 실패 시 FAILED 전이, autoAttemptCount=1, nextRetryAt 설정`() {
            val sender = failingSender("connect timeout")
            whenever(senderRegistry.find(any())).thenReturn(sender)

            val response = service.register(sampleRequest(recipientId = 300L, refId = "retry-1"))
            worker.poll()

            val stored = load(response.id)
            assertEquals(NotificationStatus.FAILED, stored.status)
            assertEquals(1, stored.autoAttemptCount)
            assertNotNull(stored.nextRetryAt)
            assertEquals("connect timeout", stored.lastError)
        }

        @Test
        fun `재시도 성공 시 SENT 전이`() {
            val sender = mock<NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(sender)
            var callCount = 0
            doAnswer { if (++callCount == 1) throw RuntimeException("first failure") }
                .whenever(sender).send(any())

            val response = service.register(sampleRequest(recipientId = 301L, refId = "retry-2"))

            worker.poll()
            assertEquals(NotificationStatus.FAILED, load(response.id).status)

            forceNextRetryAtPast(response.id)
            worker.poll()

            val stored = load(response.id)
            assertEquals(NotificationStatus.SENT, stored.status)
            assertEquals(1, stored.autoAttemptCount)
            assertNull(stored.lastError)
        }

        @Test
        fun `maxAutoAttempts 도달 시 DEAD_LETTER 전이, nextRetryAt null`() {
            val sender = failingSender("permanent failure")
            whenever(senderRegistry.find(any())).thenReturn(sender)

            val response = service.register(sampleRequest(recipientId = 302L, refId = "retry-3"))

            repeat(Notification.DEFAULT_MAX_AUTO_ATTEMPTS) { attempt ->
                if (attempt > 0) forceNextRetryAtPast(response.id)
                worker.poll()
            }

            val stored = load(response.id)
            assertEquals(NotificationStatus.DEAD_LETTER, stored.status)
            assertEquals(Notification.DEFAULT_MAX_AUTO_ATTEMPTS, stored.autoAttemptCount)
            assertNull(stored.nextRetryAt)
            assertEquals("permanent failure", stored.lastError)
        }

        @Test
        fun `backoff 간격이 시도 횟수에 따라 증가한다`() {
            val sender = failingSender("fail")
            whenever(senderRegistry.find(any())).thenReturn(sender)

            Notification.BACKOFF_MINUTES.take(Notification.DEFAULT_MAX_AUTO_ATTEMPTS - 1)
                .forEachIndexed { attempt, expectedMinutes ->
                    val response =
                        service.register(sampleRequest(recipientId = 310L + attempt, refId = "backoff-$attempt"))

                    repeat(attempt + 1) { i ->
                        if (i > 0) forceNextRetryAtPast(response.id)
                        worker.poll()
                    }

                    val stored = load(response.id)
                    val actualDelay = stored.nextRetryAt!!.epochSecond - stored.processedAt!!.epochSecond
                    val expectedDelay = expectedMinutes * 60

                    assertTrue(
                        actualDelay in (expectedDelay - 5)..(expectedDelay + 5),
                        "attempt=${attempt + 1}: expected ~${expectedDelay}s got ${actualDelay}s",
                    )
                }
        }

        private fun load(id: Long): Notification = tx.execute { notifications.findById(id).orElseThrow() }!!

        private fun forceNextRetryAtPast(id: Long) {
            jdbc.update(
                "UPDATE notification SET next_retry_at = ? WHERE id = ?",
                Timestamp.from(Instant.EPOCH),
                id,
            )
        }

        private fun failingSender(message: String): NotificationSender =
            mock<NotificationSender>().also { doThrow(RuntimeException(message)).whenever(it).send(any()) }

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

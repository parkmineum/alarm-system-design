package notification.practice.notification

import notification.practice.notification.dto.RegisterNotificationRequest
import notification.practice.notification.sender.NotificationSender
import notification.practice.notification.sender.NotificationSenderRegistry
import notification.practice.notification.worker.NotificationWorker
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@TestPropertySource(
    properties = [
        "notification.worker.poll-interval-ms=999999",
        "notification.processing-timeout.check-interval-ms=999999",
    ],
)
class NotificationRegistrationTest
    @Autowired
    constructor(
        private val service: NotificationService,
        private val notifications: NotificationRepository,
        private val worker: NotificationWorker,
        private val tx: TransactionTemplate,
    ) {
        @MockBean
        private lateinit var senderRegistry: NotificationSenderRegistry

        @Test
        fun `같은 이벤트로 N회 등록 시 row 1건만 저장되고 응답 id 가 동일하다`() {
            val request = sampleRequest()

            val first = service.register(request)
            val second = service.register(request)
            val third = service.register(request)

            assertEquals(first.id, second.id)
            assertEquals(first.id, third.id)
            assertEquals(1, tx.execute { notifications.findByRecipientIdOrderByCreatedAtDesc(42L).size })
        }

        @Test
        fun `등록 직후 status 는 PENDING 이다`() {
            val response = service.register(sampleRequest(recipientId = 10L))

            assertEquals(NotificationStatus.PENDING, response.status)
            val stored = tx.execute { notifications.findById(response.id).orElseThrow() }!!
            assertEquals(NotificationStatus.PENDING, stored.status)
            assertNull(stored.processedAt)
            assertNull(stored.lastError)
        }

        @Test
        fun `채널이 다르면 멱등성 키가 달라져 별개의 row 로 저장된다`() {
            val email = service.register(sampleRequest(channel = NotificationChannel.EMAIL, recipientId = 20L))
            val inApp = service.register(sampleRequest(channel = NotificationChannel.IN_APP, recipientId = 20L))

            assertEquals(NotificationStatus.PENDING, email.status)
            assertEquals(NotificationStatus.PENDING, inApp.status)
            assert(email.id != inApp.id)
        }

        @Test
        fun `scheduledAt 이 미래인 알림은 워커가 폴링해도 발송되지 않는다`() {
            whenever(senderRegistry.find(any())).thenReturn(mock())

            service.register(
                sampleRequest(recipientId = 500L, refId = "sched-1", scheduledAt = Instant.now().plusSeconds(3600)),
            )
            worker.poll()

            val stored = tx.execute { notifications.findByRecipientIdOrderByCreatedAtDesc(500L).first() }!!
            assertEquals(NotificationStatus.PENDING, stored.status)
        }

        @Test
        fun `scheduledAt 이 없으면 즉시 발송 대상이 된다`() {
            val sender = mock<NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(sender)

            service.register(sampleRequest(recipientId = 501L, refId = "sched-2"))
            worker.poll()

            val stored = tx.execute { notifications.findByRecipientIdOrderByCreatedAtDesc(501L).first() }!!
            assertEquals(NotificationStatus.SENT, stored.status)
        }

        private fun sampleRequest(
            channel: NotificationChannel = NotificationChannel.EMAIL,
            recipientId: Long = 42L,
            refId: String = "c-100",
            scheduledAt: Instant? = null,
        ) = RegisterNotificationRequest(
            recipientId = recipientId,
            type = "COURSE_ENROLLMENT_COMPLETED",
            channel = channel,
            refType = "COURSE",
            refId = refId,
            payload = """{"courseName":"Kotlin in Action"}""",
            scheduledAt = scheduledAt,
        )
    }

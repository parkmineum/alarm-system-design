package notification.practice.notification.worker

import notification.practice.notification.NotificationChannel
import notification.practice.notification.NotificationRepository
import notification.practice.notification.NotificationService
import notification.practice.notification.NotificationStatus
import notification.practice.notification.dto.RegisterNotificationRequest
import notification.practice.notification.sender.NotificationSender
import notification.practice.notification.sender.NotificationSenderRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@TestPropertySource(properties = ["notification.worker.poll-interval-ms=999999"])
class NotificationWorkerTest
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
        fun `poll() 이 PENDING 알림을 SENT 로 전환한다`() {
            val sender = mock<NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(sender)

            val response = service.register(sampleRequest(recipientId = 200L))
            assertEquals(NotificationStatus.PENDING, response.status)

            worker.poll()

            val stored = tx.execute { notifications.findById(response.id).orElseThrow() }!!
            assertEquals(NotificationStatus.SENT, stored.status)
            assertNotNull(stored.processedAt)
            assertNull(stored.lastError)
        }

        @Test
        fun `send 가 예외를 던지면 FAILED 로 전환되고 lastError 에 메시지가 기록된다`() {
            val sender = mock<NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(sender)
            doThrow(RuntimeException("SMTP timeout")).whenever(sender).send(any())

            val response = service.register(sampleRequest(recipientId = 201L))

            worker.poll()

            val stored = tx.execute { notifications.findById(response.id).orElseThrow() }!!
            assertEquals(NotificationStatus.FAILED, stored.status)
            assertNotNull(stored.processedAt)
            assertEquals("SMTP timeout", stored.lastError)
        }

        private fun sampleRequest(recipientId: Long) =
            RegisterNotificationRequest(
                recipientId = recipientId,
                type = "COURSE_ENROLLMENT_COMPLETED",
                channel = NotificationChannel.EMAIL,
                refType = "COURSE",
                refId = "c-worker-test",
                payload = """{"courseName":"Kotlin in Action"}""",
            )
    }

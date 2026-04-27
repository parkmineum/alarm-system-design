package notification.practice.notification

import notification.practice.notification.dto.RegisterNotificationRequest
import notification.practice.notification.worker.NotificationWorker
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
class NotificationRegistrationTest
    @Autowired
    constructor(
        private val service: NotificationService,
        private val notifications: NotificationRepository,
        private val tx: TransactionTemplate,
    ) {
        @MockBean
        private lateinit var notificationWorker: NotificationWorker

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

        private fun sampleRequest(
            channel: NotificationChannel = NotificationChannel.EMAIL,
            recipientId: Long = 42L,
        ) = RegisterNotificationRequest(
            recipientId = recipientId,
            type = "COURSE_ENROLLMENT_COMPLETED",
            channel = channel,
            refType = "COURSE",
            refId = "c-100",
            payload = """{"courseName":"Kotlin in Action"}""",
        )
    }

package notification.practice.notification

import notification.practice.notification.dto.RegisterNotificationRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals

@SpringBootTest
class NotificationRegistrationTest
    @Autowired
    constructor(
        private val service: NotificationService,
        private val notifications: NotificationRepository,
        private val tx: TransactionTemplate,
    ) {
        @Test
        fun `같은 이벤트로 N회 등록 시 row 1건만 저장되고 응답 id 가 동일하다`() {
            val request = sampleRequest()

            val first = service.register(request)
            val second = service.register(request)
            val third = service.register(request)

            assertEquals(first.id, second.id)
            assertEquals(first.id, third.id)
            assertEquals(1, tx.execute { notifications.findAll().size })
        }

        @Test
        fun `동기 발송이 성공하면 status 는 SENT 로 전이된다`() {
            val response = service.register(sampleRequest())

            assertEquals(NotificationStatus.SENT, response.status)
            val stored = tx.execute { notifications.findById(response.id).orElseThrow() }!!
            assertEquals(NotificationStatus.SENT, stored.status)
        }

        @Test
        fun `채널이 다르면 멱등성 키가 달라져 별개의 row 로 저장된다`() {
            val email = service.register(sampleRequest(channel = NotificationChannel.EMAIL))
            val inApp = service.register(sampleRequest(channel = NotificationChannel.IN_APP))

            assertEquals(2, tx.execute { notifications.findAll().size })
            assert(email.id != inApp.id)
        }

        private fun sampleRequest(channel: NotificationChannel = NotificationChannel.EMAIL) =
            RegisterNotificationRequest(
                recipientId = 42L,
                type = "COURSE_ENROLLMENT_COMPLETED",
                channel = channel,
                refType = "COURSE",
                refId = "c-100",
                payload = """{"courseName":"Kotlin in Action"}""",
            )
    }

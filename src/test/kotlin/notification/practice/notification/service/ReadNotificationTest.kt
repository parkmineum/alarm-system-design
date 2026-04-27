package notification.practice.notification.service
import notification.practice.notification.controller.dto.RegisterNotificationRequest
import notification.practice.notification.domain.Notification
import notification.practice.notification.domain.NotificationChannel
import notification.practice.notification.repository.NotificationRepository
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@TestPropertySource(
    properties = [
        "notification.worker.poll-interval-ms=999999",
        "notification.processing-timeout.check-interval-ms=999999",
    ],
)
class ReadNotificationTest
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
        fun `읽음 처리 후 readAt 이 설정되고 목록 필터에 반영된다`() {
            val sender = mock<NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(sender)

            val response = service.register(sampleRequest(recipientId = 700L, refId = "read-1"))
            worker.poll()

            assertNull(load(response.id).readAt)

            service.markRead(response.id, 700L)

            val stored = load(response.id)
            assertNotNull(stored.readAt)
            assertEquals(
                listOf(response.id),
                service.listInbox(700L, true).map { it.id },
            )
            assertEquals(emptyList(), service.listInbox(700L, false).map { it.id })
        }

        @Test
        fun `이미 읽은 알림을 다시 읽어도 readAt 이 변경되지 않는다`() {
            val sender = mock<NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(sender)

            val response = service.register(sampleRequest(recipientId = 701L, refId = "read-2"))
            service.markRead(response.id, 701L)
            val firstReadAt = load(response.id).readAt

            Thread.sleep(10)
            service.markRead(response.id, 701L)

            assertEquals(firstReadAt, load(response.id).readAt)
        }

        @Test
        fun `다중 기기에서 동시 읽음 요청이 들어와도 readAt 은 처음 설정된 값이 보존된다`() {
            val sender = mock<NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(sender)

            val response = service.register(sampleRequest(recipientId = 702L, refId = "read-3"))

            val ready = CountDownLatch(1)
            val futures =
                (1..5).map {
                    CompletableFuture.runAsync {
                        ready.await()
                        service.markRead(response.id, 702L)
                    }
                }
            ready.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }

            val finalReadAt = load(response.id).readAt
            assertNotNull(finalReadAt)
            repeat(3) { assertEquals(finalReadAt, load(response.id).readAt) }
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

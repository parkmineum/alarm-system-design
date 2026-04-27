package notification.practice.notification

import notification.practice.notification.dto.RegisterNotificationRequest
import notification.practice.notification.sender.NotificationSender
import notification.practice.notification.sender.NotificationSenderRegistry
import notification.practice.notification.worker.NotificationWorker
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.TestPropertySource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@SpringBootTest
@TestPropertySource(
    properties = [
        "notification.worker.poll-interval-ms=999999",
        "notification.zombie.check-interval-ms=999999",
    ],
)
class ConcurrentWorkerTest
    @Autowired
    constructor(
        private val service: NotificationService,
        private val worker: NotificationWorker,
    ) {
        @MockBean
        private lateinit var senderRegistry: NotificationSenderRegistry

        @Test
        fun `다중 워커가 동시에 poll 해도 각 알림은 정확히 1회만 발송된다`() {
            val sendCount = AtomicInteger(0)
            val sender = mock<NotificationSender>()
            doAnswer { sendCount.incrementAndGet() }.whenever(sender).send(any())
            whenever(senderRegistry.find(any())).thenReturn(sender)

            val notificationCount = 5
            repeat(notificationCount) { i ->
                service.register(sampleRequest(recipientId = 600L + i, refId = "concurrent-$i"))
            }

            val ready = CountDownLatch(1)
            val futures =
                (1..3).map {
                    CompletableFuture.runAsync {
                        ready.await()
                        worker.poll()
                    }
                }
            ready.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }

            assertEquals(notificationCount, sendCount.get())
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

package notification.practice.notification.dispatcher

import notification.practice.notification.Notification
import notification.practice.notification.NotificationChannel
import notification.practice.notification.NotificationRepository
import notification.practice.notification.NotificationStatus
import notification.practice.notification.dispatcher.SyncDispatcher
import notification.practice.notification.sender.NotificationSender
import notification.practice.notification.sender.NotificationSenderRegistry
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SyncDispatcherTest {
    private val senderRegistry: NotificationSenderRegistry = mock()
    private val notifications: NotificationRepository = mock()
    private val dispatcher = SyncDispatcher(senderRegistry, notifications)

    private val sender: NotificationSender = mock()

    @Test
    fun `sender 성공 시 SENT 로 전이되고 processedAt 이 채워지며 lastError 는 없다`() {
        val notification = notification()
        whenever(senderRegistry.find(notification.channel)).thenReturn(sender)

        dispatcher.dispatch(notification)

        assertEquals(NotificationStatus.SENT, notification.status)
        assertNotNull(notification.processedAt)
        assertNull(notification.lastError)
        verify(notifications).save(notification)
    }

    @Test
    fun `sender 예외 시 FAILED 로 전이되고 lastError 에 메시지가 담긴다`() {
        val notification = notification()
        whenever(senderRegistry.find(notification.channel)).thenReturn(sender)
        whenever(sender.send(notification)).thenThrow(RuntimeException("연결 실패"))

        dispatcher.dispatch(notification)

        assertEquals(NotificationStatus.FAILED, notification.status)
        assertNotNull(notification.processedAt)
        assertEquals("연결 실패", notification.lastError)
        verify(notifications).save(notification)
    }

    private fun notification() =
        Notification(
            recipientId = 1L,
            type = "TEST",
            channel = NotificationChannel.EMAIL,
            refType = "REF",
            refId = "1",
            idempotencyKey = "test-key",
        )
}

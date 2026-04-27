package notification.practice.notification

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationTest {
    @Test
    fun `lastError 가 500자를 초과하면 정확히 500자로 잘린다`() {
        val n = notification()
        val longReason = "x".repeat(600)

        n.markFailed(longReason)

        assertEquals(500, n.lastError!!.length)
    }

    @Test
    fun `markFailed 는 processedAt 을 갱신한다`() {
        val n = notification()
        val before = Instant.now()

        n.markFailed("error")

        assertTrue(n.processedAt!! >= before)
    }

    @Test
    fun `markSent 는 lastError 를 null 로 초기화한다`() {
        val n = notification()
        n.markFailed("error")

        n.markFailed("second")
        // force retryable state for markSent call
        n.markSent()

        assertNull(n.lastError)
    }

    @Test
    fun `BACKOFF_SECONDS 크기는 DEFAULT_MAX_AUTO_ATTEMPTS - 1 과 같다`() {
        assertEquals(
            Notification.DEFAULT_MAX_AUTO_ATTEMPTS - 1,
            Notification.BACKOFF_SECONDS.size,
        )
    }

    @Test
    fun `backoff 간격이 순서대로 증가한다`() {
        val delays = Notification.BACKOFF_SECONDS
        for (i in 0 until delays.size - 1) {
            assertTrue(delays[i] < delays[i + 1], "delays[$i]=${delays[i]} >= delays[${i + 1}]=${delays[i + 1]}")
        }
    }

    private fun notification() =
        Notification(
            recipientId = 1L,
            type = "TEST",
            channel = NotificationChannel.EMAIL,
            refType = "TEST",
            refId = "r-1",
            idempotencyKey = "test-key",
        )
}

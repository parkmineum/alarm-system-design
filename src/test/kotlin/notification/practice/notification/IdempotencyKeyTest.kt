package notification.practice.notification

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdempotencyKeyTest {
    @Test
    fun `동일 입력은 항상 같은 키를 반환한다`() {
        assertEquals(key(), key())
    }

    @Test
    fun `키 형식은 64자 소문자 hex 이다`() {
        val k = key()
        assertEquals(64, k.length)
        assertTrue(k.matches(Regex("[0-9a-f]+")))
    }

    @Test
    fun `channel 이 다르면 키가 달라진다`() {
        assertNotEquals(key(channel = NotificationChannel.EMAIL), key(channel = NotificationChannel.IN_APP))
    }

    @Test
    fun `recipientId 가 다르면 키가 달라진다`() {
        assertNotEquals(key(recipientId = 1L), key(recipientId = 2L))
    }

    @Test
    fun `refId 가 다르면 키가 달라진다`() {
        assertNotEquals(key(refId = "c-1"), key(refId = "c-2"))
    }

    @Test
    fun `scheduledAt 이 null 이면 scheduledAt 이 있는 경우와 키가 달라진다`() {
        val withSchedule = key(scheduledAt = Instant.parse("2026-06-01T09:00:00Z"))
        val withoutSchedule = key(scheduledAt = null)
        assertNotEquals(withSchedule, withoutSchedule)
    }

    @Test
    fun `scheduledAt 이 다르면 키가 달라진다`() {
        val t1 = key(scheduledAt = Instant.parse("2026-06-01T09:00:00Z"))
        val t2 = key(scheduledAt = Instant.parse("2026-06-02T09:00:00Z"))
        assertNotEquals(t1, t2)
    }

    @Test
    fun `scheduledAt 이 같으면 키가 동일하다`() {
        val t = Instant.parse("2026-06-01T09:00:00Z")
        assertEquals(key(scheduledAt = t), key(scheduledAt = t))
    }

    private fun key(
        type: String = "COURSE_ENROLLMENT_COMPLETED",
        refType: String = "COURSE",
        refId: String = "c-100",
        recipientId: Long = 1L,
        channel: NotificationChannel = NotificationChannel.EMAIL,
        scheduledAt: Instant? = null,
    ) = IdempotencyKey.derive(type, refType, refId, recipientId, channel, scheduledAt)
}

package notification.practice.notification

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DataJpaTest
class NotificationRepositoryTest
    @Autowired
    constructor(
        private val notifications: NotificationRepository,
        private val em: EntityManager,
    ) {
        @Test
        fun `idempotency_key UNIQUE 제약이 중복 INSERT 를 차단한다`() {
            val key = "duplicate-key"
            notifications.saveAndFlush(notification(recipientId = 1L, idempotencyKey = key))

            assertThrows<DataIntegrityViolationException> {
                notifications.saveAndFlush(notification(recipientId = 2L, idempotencyKey = key))
            }
        }

        @Test
        fun `idempotency_key 로 단건 조회한다`() {
            val saved = notifications.saveAndFlush(notification(idempotencyKey = "lookup-key"))

            assertEquals(saved.id, notifications.findByIdempotencyKey("lookup-key")?.id)
            assertEquals(null, notifications.findByIdempotencyKey("missing-key"))
        }

        @Test
        fun `수신함은 최신순으로 반환되고 다른 수신자의 알림은 제외된다`() {
            val older = notifications.saveAndFlush(notification(recipientId = 7L, idempotencyKey = "older"))
            val newer = notifications.saveAndFlush(notification(recipientId = 7L, idempotencyKey = "newer"))
            notifications.saveAndFlush(notification(recipientId = 99L, idempotencyKey = "other-user"))

            val rows = notifications.findByRecipientIdOrderByCreatedAtDesc(7L)

            assertEquals(listOf(newer.id, older.id), rows.map { it.id })
        }

        @Test
        fun `read=false 는 readAt 이 없는 알림만, read=true 는 readAt 이 있는 알림만 반환한다`() {
            val n1 = notifications.saveAndFlush(notification(recipientId = 7L, idempotencyKey = "n-1"))
            val n2 = notifications.saveAndFlush(notification(recipientId = 7L, idempotencyKey = "n-2"))

            em.createNativeQuery("UPDATE notification SET read_at = NOW() WHERE id = :id")
                .setParameter("id", n1.id)
                .executeUpdate()
            em.flush()
            em.clear()

            val unread = notifications.findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(7L)
            val read = notifications.findByRecipientIdAndReadAtIsNotNullOrderByCreatedAtDesc(7L)

            assertEquals(listOf(n2.id), unread.map { it.id })
            assertEquals(listOf(n1.id), read.map { it.id })
            assertFalse(read.isEmpty())
            assertTrue(unread.none { it.id == n1.id })
        }

        private fun notification(
            recipientId: Long = 1L,
            idempotencyKey: String,
        ): Notification =
            Notification(
                recipientId = recipientId,
                type = "COURSE_ENROLLMENT_COMPLETED",
                channel = NotificationChannel.EMAIL,
                refType = "COURSE",
                refId = "c-100",
                payload = null,
                idempotencyKey = idempotencyKey,
            )
    }

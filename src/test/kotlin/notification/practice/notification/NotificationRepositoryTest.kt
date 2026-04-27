package notification.practice.notification

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

        @Test
        fun `findPendingDispatchable 은 scheduledAt 이 과거인 PENDING 만 반환한다`() {
            val past = notifications.saveAndFlush(notification(idempotencyKey = "pending-past"))
            val future =
                notifications.saveAndFlush(
                    Notification(
                        recipientId = 1L,
                        type = "T",
                        channel = NotificationChannel.EMAIL,
                        refType = "R",
                        refId = "r",
                        idempotencyKey = "pending-future",
                        scheduledAt = Instant.now().plusSeconds(3600),
                    ),
                )

            val result = notifications.findPendingDispatchable(Instant.now(), PageRequest.of(0, 10))
            assertTrue(result.any { it.id == past.id })
            assertTrue(result.none { it.id == future.id })
        }

        @Test
        fun `findRetriableDispatchable 은 nextRetryAt 이 과거인 FAILED 만 반환한다`() {
            val pastFailed = notifications.saveAndFlush(notification(idempotencyKey = "failed-past"))
            val futureFailed = notifications.saveAndFlush(notification(idempotencyKey = "failed-future"))
            em.createNativeQuery(
                "UPDATE notification SET status = 'FAILED', next_retry_at = :past WHERE id = :id",
            ).setParameter("past", java.sql.Timestamp.from(Instant.EPOCH))
                .setParameter("id", pastFailed.id)
                .executeUpdate()
            em.createNativeQuery(
                "UPDATE notification SET status = 'FAILED', next_retry_at = :future WHERE id = :id",
            ).setParameter("future", java.sql.Timestamp.from(Instant.now().plusSeconds(3600)))
                .setParameter("id", futureFailed.id)
                .executeUpdate()
            em.flush()
            em.clear()

            val result = notifications.findRetriableDispatchable(Instant.now(), PageRequest.of(0, 10))
            assertTrue(result.any { it.id == pastFailed.id })
            assertTrue(result.none { it.id == futureFailed.id })
        }

        @Test
        fun `findRetriableDispatchable 은 DEAD_LETTER 알림을 반환하지 않는다`() {
            val n = notifications.saveAndFlush(notification(idempotencyKey = "dead-letter"))
            em.createNativeQuery(
                "UPDATE notification SET status = 'DEAD_LETTER', next_retry_at = :past WHERE id = :id",
            ).setParameter("past", java.sql.Timestamp.from(Instant.EPOCH))
                .setParameter("id", n.id)
                .executeUpdate()
            em.flush()
            em.clear()

            val result = notifications.findRetriableDispatchable(Instant.now(), PageRequest.of(0, 10))
            assertTrue(result.none { it.id == n.id })
        }

        @Test
        fun `markReadIfUnread 는 readAt 이 null 일 때 1 을 반환하고 readAt 을 설정한다`() {
            val n = notifications.saveAndFlush(notification(idempotencyKey = "mark-read-1"))
            val id = requireNotNull(n.id)
            val now = Instant.now()

            val updated = notifications.markReadIfUnread(id, now)
            em.flush()
            em.clear()

            assertEquals(1, updated)
            val stored = notifications.findById(id).orElseThrow()
            assertNotNull(stored.readAt)
        }

        @Test
        fun `markReadIfUnread 는 이미 readAt 이 설정된 경우 0 을 반환하고 기존 readAt 을 변경하지 않는다`() {
            val n = notifications.saveAndFlush(notification(idempotencyKey = "mark-read-2"))
            val id = requireNotNull(n.id)
            val first = Instant.now().minusSeconds(60)
            notifications.markReadIfUnread(id, first)
            em.flush()
            em.clear()

            val updated = notifications.markReadIfUnread(id, Instant.now())
            em.flush()
            em.clear()

            assertEquals(0, updated)
            val stored = notifications.findById(id).orElseThrow()
            assertEquals(first.epochSecond, stored.readAt!!.epochSecond)
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

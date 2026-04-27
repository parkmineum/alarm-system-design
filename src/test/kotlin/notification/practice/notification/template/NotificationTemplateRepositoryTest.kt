package notification.practice.notification.template

import notification.practice.notification.NotificationChannel
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
class NotificationTemplateRepositoryTest
    @Autowired
    constructor(
        private val templates: NotificationTemplateRepository,
    ) {
        @Test
        fun `active 템플릿을 type 과 channel 로 조회한다`() {
            val saved = templates.saveAndFlush(template(type = "T1", channel = NotificationChannel.EMAIL))

            val found = templates.findByTypeAndChannelAndActiveTrue("T1", NotificationChannel.EMAIL)

            assertNotNull(found)
            assertEquals(saved.id, found.id)
        }

        @Test
        fun `비활성 템플릿은 findByTypeAndChannelAndActiveTrue 에서 반환되지 않는다`() {
            val t = templates.saveAndFlush(template(type = "T2", channel = NotificationChannel.EMAIL))
            t.deactivate()
            templates.saveAndFlush(t)

            val found = templates.findByTypeAndChannelAndActiveTrue("T2", NotificationChannel.EMAIL)

            assertNull(found)
        }

        @Test
        fun `findLatestByTypeAndChannel 은 가장 높은 version 을 반환한다`() {
            templates.saveAndFlush(template(type = "T3", channel = NotificationChannel.IN_APP, version = 1))
            templates.saveAndFlush(template(type = "T3", channel = NotificationChannel.IN_APP, version = 2))
            templates.saveAndFlush(template(type = "T3", channel = NotificationChannel.IN_APP, version = 3))

            val latest = templates.findFirstByTypeAndChannelOrderByVersionDesc("T3", NotificationChannel.IN_APP)

            assertNotNull(latest)
            assertEquals(3, latest.version)
        }

        @Test
        fun `(type, channel, version) 조합 중복 INSERT 는 예외를 발생시킨다`() {
            templates.saveAndFlush(template(type = "T4", channel = NotificationChannel.EMAIL, version = 1))

            org.junit.jupiter.api.assertThrows<Exception> {
                templates.saveAndFlush(template(type = "T4", channel = NotificationChannel.EMAIL, version = 1))
            }
        }

        @Test
        fun `findAllByOrderByTypeAscChannelAscVersionDesc 는 정렬 순서를 보장한다`() {
            templates.saveAndFlush(template(type = "B", channel = NotificationChannel.EMAIL, version = 1))
            templates.saveAndFlush(template(type = "A", channel = NotificationChannel.EMAIL, version = 1))
            templates.saveAndFlush(template(type = "A", channel = NotificationChannel.EMAIL, version = 2))

            val result = templates.findAllByOrderByTypeAscChannelAscVersionDesc()

            val aTemplates = result.filter { it.type == "A" }
            assertTrue(aTemplates.first().version > aTemplates.last().version)
            assertTrue(result.indexOfFirst { it.type == "A" } < result.indexOfFirst { it.type == "B" })
        }

        private fun template(
            type: String,
            channel: NotificationChannel,
            version: Int = 1,
        ) = NotificationTemplate(
            type = type,
            channel = channel,
            subject = "제목 {{name}}",
            body = "안녕하세요 {{name}}님, {{message}}",
            version = version,
        )
    }

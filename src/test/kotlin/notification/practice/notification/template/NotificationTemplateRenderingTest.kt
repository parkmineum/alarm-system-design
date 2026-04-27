package notification.practice.notification.template

import notification.practice.notification.NotificationChannel
import notification.practice.notification.NotificationService
import notification.practice.notification.NotificationStatus
import notification.practice.notification.dto.RegisterNotificationRequest
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@TestPropertySource(properties = ["notification.worker.poll-interval-ms=999999"])
class NotificationTemplateRenderingTest
    @Autowired
    constructor(
        private val notificationService: notification.practice.notification.NotificationService,
        private val templateService: NotificationTemplateService,
        private val worker: NotificationWorker,
        private val tx: TransactionTemplate,
        private val notifications: notification.practice.notification.NotificationRepository,
    ) {
        @MockBean
        private lateinit var senderRegistry: NotificationSenderRegistry

        @Test
        fun `활성 템플릿이 있으면 워커 발송 후 renderedBody 가 영속화된다`() {
            val okSender = mock<notification.practice.notification.sender.NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(okSender)

            templateService.register(
                type = "COURSE_ENROLLMENT_COMPLETED",
                channel = NotificationChannel.EMAIL,
                subject = "수강 완료",
                body = "안녕하세요 {{name}}님, {{courseName}} 수강이 완료되었습니다.",
            )

            val response =
                notificationService.register(
                    RegisterNotificationRequest(
                        recipientId = 800L,
                        type = "COURSE_ENROLLMENT_COMPLETED",
                        channel = NotificationChannel.EMAIL,
                        refType = "COURSE",
                        refId = "render-1",
                        payload = """{"name":"홍길동","courseName":"Kotlin in Action"}""",
                    ),
                )

            worker.poll()

            val saved = tx.execute { notifications.findById(response.id).orElseThrow() }!!
            assertEquals(NotificationStatus.SENT, saved.status)
            assertNotNull(saved.renderedBody)
            assertEquals("안녕하세요 홍길동님, Kotlin in Action 수강이 완료되었습니다.", saved.renderedBody)
            assertNotNull(saved.templateVersion)
        }

        @Test
        fun `템플릿이 없으면 renderedBody 는 null 로 유지된다`() {
            val okSender = mock<notification.practice.notification.sender.NotificationSender>()
            whenever(senderRegistry.find(any())).thenReturn(okSender)

            val response =
                notificationService.register(
                    RegisterNotificationRequest(
                        recipientId = 801L,
                        type = "NO_TEMPLATE_TYPE",
                        channel = NotificationChannel.IN_APP,
                        refType = "COURSE",
                        refId = "render-2",
                        payload = """{"name":"홍길동"}""",
                    ),
                )

            worker.poll()

            val saved = tx.execute { notifications.findById(response.id).orElseThrow() }!!
            assertEquals(NotificationStatus.SENT, saved.status)
            assertNull(saved.renderedBody)
            assertNull(saved.templateVersion)
        }

        @Test
        fun `템플릿 신규 등록 시 version 이 1 부터 시작한다`() {
            val template =
                templateService.register(
                    type = "NEW_TYPE_V",
                    channel = NotificationChannel.EMAIL,
                    subject = "제목",
                    body = "본문",
                )

            assertEquals(1, template.version)
            assertEquals(true, template.active)
        }

        @Test
        fun `템플릿 재등록 시 version 이 증가하고 이전 버전이 비활성화된다`() {
            templateService.register(
                type = "UPDATE_TYPE",
                channel = NotificationChannel.EMAIL,
                subject = "v1 제목",
                body = "v1 본문",
            )
            val v2 =
                templateService.register(
                    type = "UPDATE_TYPE",
                    channel = NotificationChannel.EMAIL,
                    subject = "v2 제목",
                    body = "v2 본문",
                )

            assertEquals(2, v2.version)
            assertEquals(true, v2.active)

            val active = templateService.renderBody("UPDATE_TYPE", NotificationChannel.EMAIL, null)
            assertNotNull(active)
            assertEquals(2, active.templateVersion)
        }

        @Test
        fun `payload 가 null 이어도 렌더링이 정상 동작한다`() {
            templateService.register(
                type = "NULL_PAYLOAD_TYPE",
                channel = NotificationChannel.EMAIL,
                subject = "안내",
                body = "내용입니다.",
            )

            val rendered = templateService.renderBody("NULL_PAYLOAD_TYPE", NotificationChannel.EMAIL, null)

            assertNotNull(rendered)
            assertEquals("내용입니다.", rendered.body)
        }
    }

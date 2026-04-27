package notification.practice.notification

import com.fasterxml.jackson.databind.ObjectMapper
import notification.practice.common.error.GlobalExceptionHandler
import notification.practice.notification.dto.NotificationResponse
import notification.practice.notification.dto.RegisterNotificationRequest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(controllers = [NotificationController::class, MyNotificationController::class])
@Import(GlobalExceptionHandler::class)
class NotificationControllerTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
    ) {
        @MockBean
        private lateinit var notificationService: NotificationService

        @Test
        fun `POST notifications - 정상 등록은 201 과 응답 바디를 돌려준다`() {
            val response = sampleResponse()
            whenever(notificationService.register(any())).thenReturn(response)

            val body =
                RegisterNotificationRequest(
                    recipientId = 1L,
                    type = "COURSE_ENROLLMENT_COMPLETED",
                    channel = NotificationChannel.EMAIL,
                    refType = "COURSE",
                    refId = "c-100",
                    payload = null,
                )

            mockMvc
                .perform(
                    post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)),
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.id").value(response.id))
                .andExpect(jsonPath("$.status").value(response.status.name))
        }

        @Test
        fun `POST notifications - 필수 필드 누락 시 400 과 VALIDATION_FAILED 코드를 돌려준다`() {
            val body =
                mapOf(
                    "recipientId" to null,
                    "type" to "",
                    "refType" to "",
                    "refId" to "",
                )

            mockMvc
                .perform(
                    post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)),
                )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        }

        @Test
        fun `GET notifications id - 미존재 id 는 404 와 NOTIFICATION_NOT_FOUND 코드를 돌려준다`() {
            whenever(notificationService.get(999L)).thenThrow(NotificationNotFoundException(999L))

            mockMvc
                .perform(get("/notifications/999"))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
        }

        @Test
        fun `GET me notifications - X-User-Id 헤더가 없으면 400 과 MISSING_HEADER 코드를 돌려준다`() {
            mockMvc
                .perform(get("/me/notifications"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
        }

        @Test
        fun `GET me notifications - 헤더와 read 파라미터를 서비스에 위임한다`() {
            whenever(notificationService.listInbox(7L, false)).thenReturn(listOf(sampleResponse()))

            mockMvc
                .perform(get("/me/notifications?read=false").header("X-User-Id", "7"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
        }

        private fun sampleResponse(): NotificationResponse =
            NotificationResponse(
                id = 1L,
                recipientId = 1L,
                type = "COURSE_ENROLLMENT_COMPLETED",
                channel = NotificationChannel.EMAIL,
                refType = "COURSE",
                refId = "c-100",
                status = NotificationStatus.SENT,
                readAt = null,
                processedAt = Instant.now(),
                lastError = null,
                createdAt = Instant.now(),
            )
    }

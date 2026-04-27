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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
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
        fun `POST notifications - 정상 등록은 201 과 Location 헤더 및 응답 바디를 돌려준다`() {
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
                    post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)),
                )
                .andExpect(status().isCreated)
                .andExpect(header().string("Location", "/api/v1/notifications/${response.id}"))
                .andExpect(jsonPath("$.id").value(response.id))
                .andExpect(jsonPath("$.recipientId").value(response.recipientId))
                .andExpect(jsonPath("$.type").value(response.type))
                .andExpect(jsonPath("$.channel").value(response.channel.name))
                .andExpect(jsonPath("$.refType").value(response.refType))
                .andExpect(jsonPath("$.refId").value(response.refId))
                .andExpect(jsonPath("$.status").value(response.status.name))
        }

        @Test
        fun `POST notifications - 필수 필드 누락 시 400 과 VALIDATION_FAILED 코드 및 details 를 돌려준다`() {
            val body =
                mapOf(
                    "recipientId" to null,
                    "type" to "",
                    "refType" to "",
                    "refId" to "",
                )

            mockMvc
                .perform(
                    post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)),
                )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details").isMap)
                .andExpect(jsonPath("$.details.recipientId").exists())
        }

        @Test
        fun `POST notifications - 지원하지 않는 channel 값은 400 과 MALFORMED_REQUEST 를 돌려준다`() {
            val body = """{"recipientId":1,"type":"T","channel":"SMS","refType":"R","refId":"1"}"""

            mockMvc
                .perform(
                    post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body),
                )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
        }

        @Test
        fun `GET notifications id - 정상 조회는 200 과 응답 바디를 돌려준다`() {
            val response = sampleResponse()
            whenever(notificationService.get(1L, 1L)).thenReturn(response)

            mockMvc
                .perform(get("/api/v1/notifications/1").header("X-User-Id", "1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(response.id))
                .andExpect(jsonPath("$.status").value(response.status.name))
        }

        @Test
        fun `GET notifications id - 미존재 id 는 404 와 NOTIFICATION_NOT_FOUND 코드를 돌려준다`() {
            whenever(notificationService.get(999L, 1L)).thenThrow(NotificationNotFoundException(999L))

            mockMvc
                .perform(get("/api/v1/notifications/999").header("X-User-Id", "1"))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
        }

        @Test
        fun `GET notifications id - X-User-Id 헤더가 없으면 400 과 MISSING_HEADER 를 돌려준다`() {
            mockMvc
                .perform(get("/api/v1/notifications/1"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
        }

        @Test
        fun `GET users userId notifications - userId 가 숫자가 아니면 400 과 INVALID_PARAMETER 를 돌려준다`() {
            mockMvc
                .perform(get("/api/v1/users/abc/notifications"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
        }

        @Test
        fun `GET users userId notifications - read=false 이면 서비스에 false 를 전달한다`() {
            whenever(notificationService.listInbox(7L, false)).thenReturn(listOf(sampleResponse()))

            mockMvc
                .perform(get("/api/v1/users/7/notifications?read=false"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
        }

        @Test
        fun `GET users userId notifications - read=true 이면 서비스에 true 를 전달한다`() {
            whenever(notificationService.listInbox(7L, true)).thenReturn(listOf(sampleResponse()))

            mockMvc
                .perform(get("/api/v1/users/7/notifications?read=true"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
        }

        @Test
        fun `GET users userId notifications - read 파라미터 생략 시 서비스에 null 을 전달한다`() {
            whenever(notificationService.listInbox(7L, null)).thenReturn(listOf(sampleResponse(), sampleResponse()))

            mockMvc
                .perform(get("/api/v1/users/7/notifications"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(2))
        }

        @Test
        fun `PATCH notifications id read - 정상 읽음 처리는 200 과 응답 바디를 돌려준다`() {
            val response = sampleResponse()
            whenever(notificationService.markRead(1L, 1L)).thenReturn(response)

            mockMvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/notifications/1/read")
                        .header("X-User-Id", "1"),
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(response.id))
        }

        @Test
        fun `PATCH notifications id read - 미존재 id 는 404 를 돌려준다`() {
            whenever(notificationService.markRead(999L, 1L)).thenThrow(NotificationNotFoundException(999L))

            mockMvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/notifications/999/read")
                        .header("X-User-Id", "1"),
                )
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
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
                autoAttemptCount = 0,
                nextRetryAt = null,
                readAt = null,
                processedAt = Instant.now(),
                lastError = null,
                createdAt = Instant.now(),
            )
    }

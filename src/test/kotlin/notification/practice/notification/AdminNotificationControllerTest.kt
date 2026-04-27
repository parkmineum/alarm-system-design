package notification.practice.notification

import com.fasterxml.jackson.databind.ObjectMapper
import notification.practice.common.error.GlobalExceptionHandler
import notification.practice.notification.dto.NotificationResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(controllers = [AdminNotificationController::class])
@Import(GlobalExceptionHandler::class)
class AdminNotificationControllerTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
    ) {
        @MockBean
        private lateinit var adminService: AdminNotificationService

        @Test
        fun `GET dead-letters - 200 과 목록을 돌려준다`() {
            whenever(adminService.listDeadLetters()).thenReturn(listOf(deadLetterResponse()))

            mockMvc
                .perform(get("/api/v1/admin/notifications/dead-letters"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("DEAD_LETTER"))
        }

        @Test
        fun `GET dead-letters - 빈 목록도 200 으로 응답한다`() {
            whenever(adminService.listDeadLetters()).thenReturn(emptyList())

            mockMvc
                .perform(get("/api/v1/admin/notifications/dead-letters"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(0))
        }

        @Test
        fun `POST retry - 정상 재시도 요청은 200 과 응답 바디를 돌려준다`() {
            val response = deadLetterResponse().copy(status = NotificationStatus.PENDING, manualRetryCount = 1)
            whenever(adminService.retry(eq(1L), eq("admin-1"))).thenReturn(response)

            mockMvc
                .perform(
                    post("/api/v1/admin/notifications/1/retry")
                        .header("X-Actor-Id", "admin-1"),
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.manualRetryCount").value(1))
        }

        @Test
        fun `POST retry - X-Actor-Id 헤더 없으면 400 과 MISSING_HEADER 를 돌려준다`() {
            mockMvc
                .perform(post("/api/v1/admin/notifications/1/retry"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
        }

        @Test
        fun `POST retry - DEAD_LETTER 아닌 알림은 409 와 NOT_DEAD_LETTER 를 돌려준다`() {
            whenever(adminService.retry(any(), any())).thenThrow(NotificationNotDeadLetterException(1L))

            mockMvc
                .perform(
                    post("/api/v1/admin/notifications/1/retry")
                        .header("X-Actor-Id", "admin-1"),
                )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("NOT_DEAD_LETTER"))
        }

        @Test
        fun `POST retry - 수동 재시도 한도 초과 시 422 와 MANUAL_RETRY_LIMIT_EXCEEDED 를 돌려준다`() {
            whenever(adminService.retry(any(), any())).thenThrow(ManualRetryLimitExceededException(1L, 3))

            mockMvc
                .perform(
                    post("/api/v1/admin/notifications/1/retry")
                        .header("X-Actor-Id", "admin-1"),
                )
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.code").value("MANUAL_RETRY_LIMIT_EXCEEDED"))
        }

        @Test
        fun `POST retry - 미존재 id 는 404 와 NOTIFICATION_NOT_FOUND 를 돌려준다`() {
            whenever(adminService.retry(any(), any())).thenThrow(NotificationNotFoundException(999L))

            mockMvc
                .perform(
                    post("/api/v1/admin/notifications/999/retry")
                        .header("X-Actor-Id", "admin-1"),
                )
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
        }

        private fun deadLetterResponse(): NotificationResponse =
            NotificationResponse(
                id = 1L,
                recipientId = 1L,
                type = "COURSE_ENROLLMENT_COMPLETED",
                channel = NotificationChannel.EMAIL,
                refType = "COURSE",
                refId = "c-100",
                status = NotificationStatus.DEAD_LETTER,
                autoAttemptCount = 5,
                nextRetryAt = null,
                readAt = null,
                processedAt = Instant.now(),
                lastError = "connect timeout",
                manualRetryCount = 0,
                lastManualRetryAt = null,
                createdAt = Instant.now(),
            )
    }

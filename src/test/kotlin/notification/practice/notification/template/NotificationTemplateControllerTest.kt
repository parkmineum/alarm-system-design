package notification.practice.notification.template

import com.fasterxml.jackson.databind.ObjectMapper
import notification.practice.notification.NotificationChannel
import notification.practice.common.error.GlobalExceptionHandler
import notification.practice.notification.template.dto.NotificationTemplateRequest
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

@WebMvcTest(controllers = [NotificationTemplateController::class])
@Import(GlobalExceptionHandler::class)
class NotificationTemplateControllerTest
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val objectMapper: ObjectMapper,
    ) {
        @MockBean
        private lateinit var templateService: NotificationTemplateService

        @Test
        fun `POST templates - 정상 등록 시 201 과 응답 바디를 반환한다`() {
            whenever(templateService.register(any(), any(), any(), any())).thenReturn(sampleTemplate())

            mockMvc
                .perform(
                    post("/api/v1/admin/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                NotificationTemplateRequest(
                                    type = "COURSE_ENROLLMENT_COMPLETED",
                                    channel = NotificationChannel.EMAIL,
                                    subject = "수강 신청 완료",
                                    body = "안녕하세요 {{name}}님",
                                ),
                            ),
                        ),
                )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.type").value("COURSE_ENROLLMENT_COMPLETED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.active").value(true))
        }

        @Test
        fun `POST templates - type 누락 시 400 을 반환한다`() {
            mockMvc
                .perform(
                    post("/api/v1/admin/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            objectMapper.writeValueAsString(
                                NotificationTemplateRequest(
                                    type = null,
                                    channel = NotificationChannel.EMAIL,
                                    subject = "제목",
                                    body = "본문",
                                ),
                            ),
                        ),
                )
                .andExpect(status().isBadRequest)
        }

        @Test
        fun `GET templates - 목록을 반환한다`() {
            whenever(templateService.list()).thenReturn(listOf(sampleTemplate()))

            mockMvc
                .perform(get("/api/v1/admin/templates"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("COURSE_ENROLLMENT_COMPLETED"))
        }

        private fun sampleTemplate() =
            NotificationTemplate(
                type = "COURSE_ENROLLMENT_COMPLETED",
                channel = NotificationChannel.EMAIL,
                subject = "수강 신청 완료",
                body = "안녕하세요 {{name}}님",
                version = 1,
            ).also {
                val field = it.javaClass.getDeclaredField("id")
                field.isAccessible = true
                field.set(it, 1L)
                val createdAtField = it.javaClass.getDeclaredField("createdAt")
                createdAtField.isAccessible = true
                createdAtField.set(it, Instant.now())
            }
    }

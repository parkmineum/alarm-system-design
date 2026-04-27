package notification.practice.notification.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import notification.practice.notification.NotificationChannel

@Schema(description = "알림 등록 요청")
data class RegisterNotificationRequest(
    @Schema(description = "수신자 사용자 ID", example = "42", required = true)
    @field:NotNull
    val recipientId: Long?,
    @Schema(description = "알림 타입", example = "COURSE_ENROLLMENT_COMPLETED", maxLength = 50, required = true)
    @field:NotBlank
    @field:Size(max = 50)
    val type: String?,
    @Schema(description = "발송 채널", required = true)
    @field:NotNull
    val channel: NotificationChannel?,
    @Schema(description = "참조 도메인 타입", example = "COURSE", maxLength = 50, required = true)
    @field:NotBlank
    @field:Size(max = 50)
    val refType: String?,
    @Schema(description = "참조 도메인 ID", example = "c-100", maxLength = 64, required = true)
    @field:NotBlank
    @field:Size(max = 64)
    val refId: String?,
    @Schema(description = "알림 본문 JSON", example = """{"courseName":"Kotlin in Action"}""", required = false)
    val payload: String? = null,
)

package notification.practice.notification.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import notification.practice.notification.NotificationChannel

data class RegisterNotificationRequest(
    @field:NotNull
    val recipientId: Long?,
    @field:NotBlank
    @field:Size(max = 50)
    val type: String?,
    @field:NotNull
    val channel: NotificationChannel?,
    @field:NotBlank
    @field:Size(max = 50)
    val refType: String?,
    @field:NotBlank
    @field:Size(max = 64)
    val refId: String?,
    val payload: String? = null,
)

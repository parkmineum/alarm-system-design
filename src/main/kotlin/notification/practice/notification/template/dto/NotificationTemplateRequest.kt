package notification.practice.notification.template.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import notification.practice.notification.NotificationChannel

data class NotificationTemplateRequest(
    @field:NotBlank @field:Size(max = 50)
    val type: String?,
    @field:NotNull
    val channel: NotificationChannel?,
    @field:NotBlank @field:Size(max = 200)
    val subject: String?,
    @field:NotBlank
    val body: String?,
)

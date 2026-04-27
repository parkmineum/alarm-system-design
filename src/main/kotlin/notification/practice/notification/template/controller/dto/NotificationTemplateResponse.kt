package notification.practice.notification.template.controller.dto

import notification.practice.notification.domain.NotificationChannel
import notification.practice.notification.template.domain.NotificationTemplate
import java.time.Instant

data class NotificationTemplateResponse(
    val id: Long,
    val type: String,
    val channel: NotificationChannel,
    val subject: String,
    val body: String,
    val version: Int,
    val active: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(template: NotificationTemplate): NotificationTemplateResponse =
            NotificationTemplateResponse(
                id = template.id ?: error("저장되지 않은 템플릿"),
                type = template.type,
                channel = template.channel,
                subject = template.subject,
                body = template.body,
                version = template.version,
                active = template.active,
                createdAt = template.createdAt,
            )
    }
}

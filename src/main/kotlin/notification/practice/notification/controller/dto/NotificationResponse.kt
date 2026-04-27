package notification.practice.notification.controller.dto

import notification.practice.notification.domain.Notification
import notification.practice.notification.domain.NotificationChannel
import notification.practice.notification.domain.NotificationStatus
import java.time.Instant

data class NotificationResponse(
    val id: Long,
    val recipientId: Long,
    val type: String,
    val channel: NotificationChannel,
    val refType: String,
    val refId: String,
    val scheduledAt: Instant,
    val status: NotificationStatus,
    val autoAttemptCount: Int,
    val nextRetryAt: Instant?,
    val readAt: Instant?,
    val processedAt: Instant?,
    val lastError: String?,
    val renderedBody: String?,
    val templateVersion: Int?,
    val manualRetryCount: Int,
    val lastManualRetryAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun from(notification: Notification): NotificationResponse =
            NotificationResponse(
                id = notification.id ?: error("저장되지 않은 알림은 응답으로 변환할 수 없습니다"),
                recipientId = notification.recipientId,
                type = notification.type,
                channel = notification.channel,
                refType = notification.refType,
                refId = notification.refId,
                scheduledAt = notification.scheduledAt,
                status = notification.status,
                autoAttemptCount = notification.autoAttemptCount,
                nextRetryAt = notification.nextRetryAt,
                readAt = notification.readAt,
                processedAt = notification.processedAt,
                lastError = notification.lastError,
                renderedBody = notification.renderedBody,
                templateVersion = notification.templateVersion,
                manualRetryCount = notification.manualRetryCount,
                lastManualRetryAt = notification.lastManualRetryAt,
                createdAt = notification.createdAt,
            )
    }
}

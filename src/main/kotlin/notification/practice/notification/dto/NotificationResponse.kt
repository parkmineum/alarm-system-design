package notification.practice.notification.dto

import notification.practice.notification.Notification
import notification.practice.notification.NotificationChannel
import notification.practice.notification.NotificationStatus
import java.time.Instant

data class NotificationResponse(
    val id: Long,
    val recipientId: Long,
    val type: String,
    val channel: NotificationChannel,
    val refType: String,
    val refId: String,
    val status: NotificationStatus,
    val readAt: Instant?,
    val processedAt: Instant?,
    val lastError: String?,
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
                status = notification.status,
                readAt = notification.readAt,
                processedAt = notification.processedAt,
                lastError = notification.lastError,
                createdAt = notification.createdAt,
            )
    }
}

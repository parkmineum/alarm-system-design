package notification.practice.notification.domain

enum class NotificationStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    DEAD_LETTER,
}

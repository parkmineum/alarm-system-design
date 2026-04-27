package notification.practice.notification.exception

class NotificationIdempotencyConflictException(key: String) :
    RuntimeException("같은 이벤트에 대한 알림이 이미 등록되어 있으나 payload 가 다릅니다: key=$key")

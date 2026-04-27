package notification.practice.notification.exception

class NotificationNotFoundException(id: Long) : RuntimeException("알림을 찾을 수 없습니다: id=$id")

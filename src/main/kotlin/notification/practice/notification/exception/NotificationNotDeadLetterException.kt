package notification.practice.notification.exception

class NotificationNotDeadLetterException(id: Long) :
    RuntimeException("알림 $id 은 DEAD_LETTER 상태가 아닙니다")

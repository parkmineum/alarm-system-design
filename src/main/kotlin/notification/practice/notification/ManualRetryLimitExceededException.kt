package notification.practice.notification

class ManualRetryLimitExceededException(id: Long, limit: Int) :
    RuntimeException("알림 $id 의 수동 재시도 한도($limit 회)를 초과했습니다")

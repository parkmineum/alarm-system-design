package notification.practice.notification.worker

import notification.practice.notification.Notification
import notification.practice.notification.NotificationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class NotificationWorkerClaimer(
    private val notifications: NotificationRepository,
) {
    @Transactional
    fun claimBatch(
        now: Instant,
        batchSize: Int,
    ): List<Notification> {
        val pending = notifications.findPendingDispatchable(now, PageRequest.of(0, batchSize))
        val retries = notifications.findRetriableDispatchable(now, PageRequest.of(0, batchSize - pending.size))
        val batch = pending + retries
        batch.forEach {
            it.markProcessing(now)
            notifications.save(it)
        }
        return batch
    }
}

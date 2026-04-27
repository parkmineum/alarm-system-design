package notification.practice.notification.worker

import notification.practice.notification.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class NotificationWorker(
    private val notifications: NotificationRepository,
    private val processor: NotificationWorkerProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${notification.worker.poll-interval-ms:1000}")
    fun poll() {
        val now = Instant.now()
        val pending = notifications.findPendingDispatchable(now, PageRequest.of(0, BATCH_SIZE))
        val retries = notifications.findRetriableDispatchable(now, PageRequest.of(0, BATCH_SIZE - pending.size))
        val batch = pending + retries
        if (batch.isNotEmpty()) {
            log.debug("[worker] picked up {} notifications (pending={}, retry={})", batch.size, pending.size, retries.size)
        }
        batch.forEach { processor.process(it) }
    }

    companion object {
        private const val BATCH_SIZE = 50
    }
}

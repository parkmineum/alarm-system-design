package notification.practice.notification.worker

import notification.practice.notification.repository.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ProcessingTimeoutRecoveryJob(
    private val notifications: NotificationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${notification.processing-timeout.check-interval-ms:60000}")
    @Transactional
    fun recover() {
        val now = Instant.now()
        val cutoff = now.minusSeconds(PROCESSING_TIMEOUT_SECONDS)
        val count = notifications.resetTimedOutProcessing(cutoff, now)
        if (count > 0) {
            log.warn("[recovery] {} notification(s) stuck in PROCESSING recovered to PENDING", count)
        }
    }

    companion object {
        const val PROCESSING_TIMEOUT_SECONDS = 300L
    }
}

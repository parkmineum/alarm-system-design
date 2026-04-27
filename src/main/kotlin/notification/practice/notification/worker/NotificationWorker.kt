package notification.practice.notification.worker

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class NotificationWorker(
    private val claimer: NotificationWorkerClaimer,
    private val processor: NotificationWorkerProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${notification.worker.poll-interval-ms:1000}")
    fun poll() {
        val batch = claimer.claimBatch(Instant.now(), BATCH_SIZE)
        if (batch.isNotEmpty()) {
            log.debug("[worker] claimed {} notifications", batch.size)
        }
        batch.forEach { processor.process(it) }
    }

    companion object {
        private const val BATCH_SIZE = 50
    }
}

package notification.practice.notification.worker

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class NotificationWorker(
    private val claimer: NotificationWorkerClaimer,
    private val processor: NotificationWorkerProcessor,
    @Value("\${notification.worker.batch-size:50}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${notification.worker.poll-interval-ms:1000}")
    fun poll() {
        val batch = claimer.claimBatch(Instant.now(), batchSize)
        if (batch.isNotEmpty()) {
            log.debug("[worker] claimed {} notifications", batch.size)
        }
        batch.forEach { processor.process(it) }
    }
}

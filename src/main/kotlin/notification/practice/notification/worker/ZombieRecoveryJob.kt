package notification.practice.notification.worker

import notification.practice.notification.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ZombieRecoveryJob(
    private val notifications: NotificationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${notification.zombie.check-interval-ms:60000}")
    @Transactional
    fun recover() {
        val now = Instant.now()
        val cutoff = now.minusSeconds(ZOMBIE_TIMEOUT_SECONDS)
        val count = notifications.resetZombies(cutoff, now)
        if (count > 0) {
            log.warn("[zombie] {} notification(s) stuck in PROCESSING recovered to PENDING", count)
        }
    }

    companion object {
        // PROCESSING 상태가 이 시간을 초과하면 좀비로 간주
        const val ZOMBIE_TIMEOUT_SECONDS = 300L
    }
}

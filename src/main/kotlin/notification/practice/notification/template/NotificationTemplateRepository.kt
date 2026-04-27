package notification.practice.notification.template

import notification.practice.notification.NotificationChannel
import org.springframework.data.jpa.repository.JpaRepository

interface NotificationTemplateRepository : JpaRepository<NotificationTemplate, Long> {
    fun findByTypeAndChannelAndActiveTrue(
        type: String,
        channel: NotificationChannel,
    ): NotificationTemplate?

    fun findFirstByTypeAndChannelOrderByVersionDesc(
        type: String,
        channel: NotificationChannel,
    ): NotificationTemplate?

    fun findAllByOrderByTypeAscChannelAscVersionDesc(): List<NotificationTemplate>
}

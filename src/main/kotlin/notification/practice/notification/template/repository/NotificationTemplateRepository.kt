package notification.practice.notification.template.repository
import notification.practice.notification.domain.NotificationChannel
import notification.practice.notification.template.domain.NotificationTemplate
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

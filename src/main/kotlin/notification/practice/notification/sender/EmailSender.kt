package notification.practice.notification.sender

import notification.practice.notification.Notification
import notification.practice.notification.NotificationChannel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class EmailSender : NotificationSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun supports(channel: NotificationChannel): Boolean = channel == NotificationChannel.EMAIL

    override fun send(notification: Notification) {
        log.info(
            "[EMAIL] sent recipientId={} type={} refType={} refId={}",
            notification.recipientId,
            notification.type,
            notification.refType,
            notification.refId,
        )
    }
}

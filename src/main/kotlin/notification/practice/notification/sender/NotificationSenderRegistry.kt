package notification.practice.notification.sender

import notification.practice.notification.domain.NotificationChannel
import org.springframework.stereotype.Component

@Component
class NotificationSenderRegistry(senders: List<NotificationSender>) {
    private val byChannel: Map<NotificationChannel, NotificationSender> =
        NotificationChannel.entries.associateWith { channel ->
            senders.firstOrNull { it.supports(channel) }
                ?: error("등록된 sender 가 없습니다: channel=$channel")
        }

    fun find(channel: NotificationChannel): NotificationSender = byChannel.getValue(channel)
}

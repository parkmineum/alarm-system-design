package notification.practice.notification.worker

import notification.practice.notification.domain.Notification
import notification.practice.notification.repository.NotificationRepository
import notification.practice.notification.sender.NotificationSenderRegistry
import notification.practice.notification.template.service.NotificationTemplateService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class NotificationWorkerProcessor(
    private val notifications: NotificationRepository,
    private val senderRegistry: NotificationSenderRegistry,
    private val templateService: NotificationTemplateService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun process(notification: Notification) {
        renderTemplate(notification)
        try {
            senderRegistry.find(notification.channel).send(notification)
            notification.markSent()
        } catch (e: Exception) {
            log.warn("[worker] dispatch failed id={} reason={}", notification.id, e.message)
            notification.markFailed(e.message ?: e.javaClass.simpleName)
        }
        notifications.save(notification)
    }

    private fun renderTemplate(notification: Notification) {
        val rendered =
            templateService.renderBody(
                notificationType = notification.type,
                channel = notification.channel,
                payload = notification.payload,
            ) ?: return
        notification.applyRenderedTemplate(rendered.body, rendered.templateVersion)
    }
}

package notification.practice.notification.template

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import notification.practice.notification.NotificationChannel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationTemplateService(
    private val templates: NotificationTemplateRepository,
    private val renderer: TemplateRenderer,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun register(
        type: String,
        channel: NotificationChannel,
        subject: String,
        body: String,
    ): NotificationTemplate {
        require(type.isNotBlank()) { "type 은 비어있을 수 없습니다" }
        require(subject.isNotBlank()) { "subject 는 비어있을 수 없습니다" }
        require(body.isNotBlank()) { "body 는 비어있을 수 없습니다" }

        val nextVersion = (templates.findFirstByTypeAndChannelOrderByVersionDesc(type, channel)?.version ?: 0) + 1

        templates.findByTypeAndChannelAndActiveTrue(type, channel)?.let {
            it.deactivate()
            templates.save(it)
        }

        return templates.save(
            NotificationTemplate(
                type = type,
                channel = channel,
                subject = subject,
                body = body,
                version = nextVersion,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun list(): List<NotificationTemplate> = templates.findAllByOrderByTypeAscChannelAscVersionDesc()

    fun renderBody(
        notificationType: String,
        channel: NotificationChannel,
        payload: String?,
    ): RenderedTemplate? {
        val template = templates.findByTypeAndChannelAndActiveTrue(notificationType, channel) ?: return null
        val variables = parsePayload(payload)
        return RenderedTemplate(
            templateVersion = template.version,
            subject = renderer.render(template.subject, variables),
            body = renderer.render(template.body, variables),
        )
    }

    private fun parsePayload(payload: String?): Map<String, Any?> {
        if (payload.isNullOrBlank()) return emptyMap()
        return try {
            objectMapper.readValue(payload, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

data class RenderedTemplate(
    val templateVersion: Int,
    val subject: String,
    val body: String,
)

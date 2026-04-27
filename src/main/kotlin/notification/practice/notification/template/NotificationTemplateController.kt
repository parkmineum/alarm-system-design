package notification.practice.notification.template

import jakarta.validation.Valid
import notification.practice.notification.template.dto.NotificationTemplateRequest
import notification.practice.notification.template.dto.NotificationTemplateResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/admin/templates")
class NotificationTemplateController(
    private val templateService: NotificationTemplateService,
) {
    @PostMapping
    fun register(
        @RequestBody @Valid request: NotificationTemplateRequest,
    ): ResponseEntity<NotificationTemplateResponse> {
        val saved =
            templateService.register(
                type = requireNotNull(request.type),
                channel = requireNotNull(request.channel),
                subject = requireNotNull(request.subject),
                body = requireNotNull(request.body),
            )
        return ResponseEntity
            .created(URI.create("/api/v1/admin/templates/${saved.id}"))
            .body(NotificationTemplateResponse.from(saved))
    }

    @GetMapping
    fun list(): List<NotificationTemplateResponse> = templateService.list().map(NotificationTemplateResponse::from)
}

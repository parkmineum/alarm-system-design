package notification.practice.notification

import jakarta.validation.Valid
import notification.practice.notification.dto.NotificationResponse
import notification.practice.notification.dto.RegisterNotificationRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val notificationService: NotificationService,
) {
    @PostMapping
    fun register(
        @RequestBody @Valid request: RegisterNotificationRequest,
    ): ResponseEntity<NotificationResponse> {
        val response = notificationService.register(request)
        return ResponseEntity.created(URI.create("/notifications/${response.id}")).body(response)
    }

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: Long,
        @RequestHeader("X-User-Id") userId: Long,
    ): NotificationResponse = notificationService.get(id, userId)
}

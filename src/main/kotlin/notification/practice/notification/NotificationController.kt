package notification.practice.notification

import jakarta.validation.Valid
import notification.practice.notification.dto.NotificationResponse
import notification.practice.notification.dto.RegisterNotificationRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val notificationService: NotificationService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @RequestBody @Valid request: RegisterNotificationRequest,
    ): NotificationResponse = notificationService.register(request)

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: Long,
    ): NotificationResponse = notificationService.get(id)
}

package notification.practice.notification

import notification.practice.notification.dto.NotificationResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/me/notifications")
class MyNotificationController(
    private val notificationService: NotificationService,
) {
    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestParam(name = "read", required = false) read: Boolean?,
    ): List<NotificationResponse> = notificationService.listInbox(userId, read)
}

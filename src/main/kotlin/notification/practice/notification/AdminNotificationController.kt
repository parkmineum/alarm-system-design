package notification.practice.notification

import notification.practice.notification.dto.NotificationResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/notifications")
class AdminNotificationController(
    private val adminService: AdminNotificationService,
) {
    @GetMapping("/dead-letters")
    fun listDeadLetters(): ResponseEntity<List<NotificationResponse>> = ResponseEntity.ok(adminService.listDeadLetters())

    @PostMapping("/{id}/retry")
    fun retry(
        @PathVariable id: Long,
        @RequestHeader("X-Actor-Id") actorId: String,
    ): ResponseEntity<NotificationResponse> = ResponseEntity.ok(adminService.retry(id, actorId))
}

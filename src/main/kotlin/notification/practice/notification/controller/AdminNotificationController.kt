package notification.practice.notification.controller
import notification.practice.notification.controller.dto.NotificationResponse
import notification.practice.notification.service.AdminNotificationService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin/notifications")
class AdminNotificationController(
    private val adminService: AdminNotificationService,
) {
    @GetMapping("/dead-letters")
    fun listDeadLetters(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<List<NotificationResponse>> = ResponseEntity.ok(adminService.listDeadLetters(page, size))

    @PostMapping("/{id}/retry")
    fun retry(
        @PathVariable id: Long,
        @RequestHeader("X-Actor-Id") actorId: String,
    ): ResponseEntity<NotificationResponse> = ResponseEntity.ok(adminService.retry(id, actorId))
}

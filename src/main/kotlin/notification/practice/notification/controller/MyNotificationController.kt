package notification.practice.notification.controller
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import notification.practice.common.error.ApiError
import notification.practice.notification.controller.dto.NotificationResponse
import notification.practice.notification.service.NotificationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "수신함", description = "사용자 알림 목록 조회")
@RestController
@RequestMapping("/api/v1/users/{userId}/notifications")
class MyNotificationController(
    private val notificationService: NotificationService,
) {
    @Operation(summary = "수신함 목록 조회", description = "최신순 정렬. read 파라미터로 읽음/미읽음 필터링 가능.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "userId 형식 오류",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    @GetMapping
    fun list(
        @Parameter(description = "사용자 ID", required = true)
        @PathVariable userId: Long,
        @Parameter(description = "읽음 여부 필터. true=읽음만, false=미읽음만, 생략=전체")
        @RequestParam(name = "read", required = false) read: Boolean?,
    ): List<NotificationResponse> = notificationService.listInbox(userId, read)
}

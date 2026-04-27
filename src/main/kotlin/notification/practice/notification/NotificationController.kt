package notification.practice.notification

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import notification.practice.common.error.ApiError
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

@Tag(name = "알림", description = "알림 등록 및 단건 조회")
@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val notificationService: NotificationService,
) {
    @Operation(summary = "알림 등록", description = "동일 이벤트를 중복 등록해도 멱등성 키 기준으로 row 1건만 생성된다.")
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "등록 성공",
            headers = [Header(name = "Location", description = "/notifications/{id}")],
        ),
        ApiResponse(
            responseCode = "400",
            description = "요청 본문 검증 실패",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "동일 멱등성 키에 payload 가 다른 요청",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    @PostMapping
    fun register(
        @RequestBody @Valid request: RegisterNotificationRequest,
    ): ResponseEntity<NotificationResponse> {
        val response = notificationService.register(request)
        return ResponseEntity.created(URI.create("/notifications/${response.id}")).body(response)
    }

    @Operation(summary = "알림 단건 조회", description = "요청자 본인의 알림만 조회할 수 있다. 타인 알림 접근 시 404를 반환한다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(
            responseCode = "400",
            description = "X-User-Id 헤더 누락",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "알림 없음 또는 다른 사용자 알림",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: Long,
        @Parameter(description = "요청자 사용자 ID", required = true)
        @RequestHeader("X-User-Id") userId: Long,
    ): NotificationResponse = notificationService.get(id, userId)
}

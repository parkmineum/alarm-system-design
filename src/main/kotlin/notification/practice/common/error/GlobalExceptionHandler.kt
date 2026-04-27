package notification.practice.common.error

import notification.practice.notification.ManualRetryLimitExceededException
import notification.practice.notification.NotificationIdempotencyConflictException
import notification.practice.notification.NotificationNotDeadLetterException
import notification.practice.notification.NotificationNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(NotificationNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(e: NotificationNotFoundException): ApiError = ApiError(code = "NOTIFICATION_NOT_FOUND", message = e.message ?: "")

    @ExceptionHandler(NotificationIdempotencyConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun idempotencyConflict(e: NotificationIdempotencyConflictException): ApiError =
        ApiError(code = "IDEMPOTENCY_CONFLICT", message = e.message ?: "")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun validation(e: MethodArgumentNotValidException): ApiError {
        val fields =
            e.bindingResult.fieldErrors.associate {
                it.field to (it.defaultMessage ?: "유효하지 않은 값입니다")
            }
        return ApiError(
            code = "VALIDATION_FAILED",
            message = "요청 본문 검증에 실패했습니다",
            details = fields,
        )
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun typeMismatch(e: MethodArgumentTypeMismatchException): ApiError =
        ApiError(code = "INVALID_PARAMETER", message = "올바르지 않은 파라미터 값입니다: ${e.name}")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadable(e: HttpMessageNotReadableException): ApiError = ApiError(code = "MALFORMED_REQUEST", message = "요청 본문을 읽을 수 없습니다")

    @ExceptionHandler(MissingRequestHeaderException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun missingHeader(e: MissingRequestHeaderException): ApiError =
        ApiError(
            code = "MISSING_HEADER",
            message = "필수 헤더가 없습니다: ${e.headerName}",
        )

    @ExceptionHandler(NotificationNotDeadLetterException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun notDeadLetter(e: NotificationNotDeadLetterException): ApiError =
        ApiError(code = "NOT_DEAD_LETTER", message = e.message ?: "")

    @ExceptionHandler(ManualRetryLimitExceededException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun manualRetryLimitExceeded(e: ManualRetryLimitExceededException): ApiError =
        ApiError(code = "MANUAL_RETRY_LIMIT_EXCEEDED", message = e.message ?: "")

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun illegalArgument(e: IllegalArgumentException): ApiError =
        ApiError(code = "INVALID_REQUEST", message = e.message ?: "잘못된 요청입니다")
}

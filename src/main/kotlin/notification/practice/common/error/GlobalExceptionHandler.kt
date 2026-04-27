package notification.practice.common.error

import notification.practice.notification.NotificationNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(NotificationNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(e: NotificationNotFoundException): ApiError = ApiError(code = "NOTIFICATION_NOT_FOUND", message = e.message ?: "")

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
}

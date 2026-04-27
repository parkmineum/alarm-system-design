package notification.practice.common.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("알림 발송 시스템 API")
                .description("이벤트 기반 비동기 알림 시스템. 채널: EMAIL, IN_APP")
                .version("v1"),
        )
}

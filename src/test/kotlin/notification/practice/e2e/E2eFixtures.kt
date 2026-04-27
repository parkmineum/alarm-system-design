package notification.practice.e2e

import org.springframework.boot.test.context.TestComponent
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 각 E2E 테스트 사이에 도메인 테이블을 비워 격리한다. ddl-auto=update 라 스키마는 유지된다.
 */
@TestComponent
class E2eFixtures(
    private val jdbc: JdbcTemplate,
) {
    fun cleanAll() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0")
        jdbc.execute("TRUNCATE TABLE notification")
        jdbc.execute("TRUNCATE TABLE notification_template")
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1")
    }
}

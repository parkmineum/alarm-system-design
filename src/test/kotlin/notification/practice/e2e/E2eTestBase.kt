package notification.practice.e2e

import io.restassured.RestAssured
import io.restassured.config.ObjectMapperConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.mapper.ObjectMapperType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * 운영 환경과 동일하게 MySQL 8 컨테이너 + 임의 포트로 부팅한 앱을 띄워 HTTP 로 호출한다.
 *
 * H2 로 대체하지 않는 이유: `SELECT ... FOR UPDATE SKIP LOCKED` 와 비동기 워커의 락 동작은
 * MySQL 8.0.1+ 에서만 동작하므로, 슬라이스 테스트와 별개로 실제 DB 위에서 한 번 더 검증한다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
@Testcontainers
@Tag("e2e")
@Import(E2eFixtures::class)
abstract class E2eTestBase {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    protected lateinit var fixtures: E2eFixtures

    @BeforeEach
    fun setUpRestAssured() {
        RestAssured.port = port
        RestAssured.baseURI = "http://localhost"
        RestAssured.config =
            RestAssuredConfig
                .config()
                .objectMapperConfig(ObjectMapperConfig().defaultObjectMapperType(ObjectMapperType.JACKSON_2))
        fixtures.cleanAll()
    }

    companion object {
        @Container
        @JvmStatic
        @ServiceConnection
        val mysql: MySQLContainer<*> =
            MySQLContainer("mysql:8.0")
                .withDatabaseName("notification")
                .withUsername("notification")
                .withPassword("notification")
                .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                )
                .also { it.start() }
    }
}

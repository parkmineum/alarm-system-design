package notification.practice.e2e

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.awaitility.Awaitility.await
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Duration

/**
 * 운영 환경(MySQL 8) 위에서 Swagger 에 노출된 모든 엔드포인트가 의도대로 연결되어 동작하는지
 * HTTP 호출만으로 검증한다. 단위·슬라이스 테스트가 잡지 못하는 비동기 워커, 락, 트랜잭션 경계
 * 동작을 한 번에 가드한다.
 */
class NotificationE2eTest : E2eTestBase() {
    @Test
    fun `EMAIL 알림 등록 후 워커가 SENT 로 전이하고 템플릿이 렌더링된다`() {
        registerTemplate(type = "WELCOME", channel = "EMAIL", subject = "Hi {{userName}}", body = "Course: {{courseName}}")

        val id =
            given()
                .contentType(ContentType.JSON)
                .body(
                    """
                    {
                        "recipientId": 1,
                        "type": "WELCOME",
                        "channel": "EMAIL",
                        "refType": "COURSE",
                        "refId": "c-1",
                        "payload": "{\"userName\":\"민음\",\"courseName\":\"Kotlin\"}"
                    }
                    """.trimIndent(),
                ).post("/api/v1/notifications")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .header("Location", notNullValue())
                .body("status", equalTo("PENDING"))
                .extract()
                .jsonPath()
                .getLong("id")

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            given()
                .header("X-User-Id", 1)
                .get("/api/v1/notifications/$id")
                .then()
                .statusCode(200)
                .body("status", equalTo("SENT"))
                .body("renderedBody", equalTo("Course: Kotlin"))
                .body("templateVersion", equalTo(1))
                .body("processedAt", notNullValue())
        }
    }

    @Test
    fun `같은 멱등키 + 같은 페이로드 재요청은 같은 id 를 돌려준다`() {
        val payload =
            """
            {
                "recipientId": 2,
                "type": "ENROLL",
                "channel": "IN_APP",
                "refType": "COURSE",
                "refId": "c-2"
            }
            """.trimIndent()

        val first = postNotification(payload, expectStatus = 201)
        val second = postNotification(payload, expectStatus = 201)

        assert(first == second) { "멱등 등록은 같은 id 를 돌려줘야 한다 (first=$first, second=$second)" }
    }

    @Test
    fun `같은 멱등키 + 다른 페이로드는 409 IDEMPOTENCY_CONFLICT 를 돌려준다`() {
        val base =
            """
            {
                "recipientId": 3,
                "type": "ENROLL",
                "channel": "EMAIL",
                "refType": "COURSE",
                "refId": "c-3"
            }
            """.trimIndent()
        postNotification(base, expectStatus = 201)

        given()
            .contentType(ContentType.JSON)
            .body(base.replace("\"refId\": \"c-3\"", "\"refId\": \"c-3\", \"payload\": \"{\\\"diff\\\":1}\""))
            .post("/api/v1/notifications")
            .then()
            .statusCode(409)
            .body("code", equalTo("IDEMPOTENCY_CONFLICT"))
    }

    @Test
    fun `타인 알림 단건 조회는 정보 노출 없이 404 를 돌려준다`() {
        val id = postNotification(payloadFor(recipientId = 10, refId = "c-10"), expectStatus = 201)

        given()
            .header("X-User-Id", 999)
            .get("/api/v1/notifications/$id")
            .then()
            .statusCode(404)
            .body("code", equalTo("NOTIFICATION_NOT_FOUND"))
    }

    @Test
    fun `X-User-Id 누락 시 400 MISSING_HEADER 를 돌려준다`() {
        val id = postNotification(payloadFor(recipientId = 11, refId = "c-11"), expectStatus = 201)

        given()
            .get("/api/v1/notifications/$id")
            .then()
            .statusCode(400)
            .body("code", equalTo("MISSING_HEADER"))
    }

    @Test
    fun `읽음 처리는 멱등하다 — 두 번째 호출에도 readAt 이 변하지 않는다`() {
        val id = postNotification(payloadFor(recipientId = 20, refId = "c-20"), expectStatus = 201)

        val firstReadAt =
            given()
                .header("X-User-Id", 20)
                .patch("/api/v1/notifications/$id/read")
                .then()
                .statusCode(200)
                .body("readAt", notNullValue())
                .extract()
                .jsonPath()
                .getString("readAt")

        val secondReadAt =
            given()
                .header("X-User-Id", 20)
                .patch("/api/v1/notifications/$id/read")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("readAt")

        assert(firstReadAt == secondReadAt) { "재호출에도 readAt 가 고정되어야 한다 (first=$firstReadAt, second=$secondReadAt)" }
    }

    @Test
    fun `수신함 read 필터가 읽음 미읽음을 분리한다`() {
        val readId = postNotification(payloadFor(recipientId = 30, refId = "c-30-read"), expectStatus = 201)
        postNotification(payloadFor(recipientId = 30, refId = "c-30-unread"), expectStatus = 201)

        given()
            .header("X-User-Id", 30)
            .patch("/api/v1/notifications/$readId/read")
            .then()
            .statusCode(200)

        given().get("/api/v1/users/30/notifications").then().statusCode(200).body("size()", equalTo(2))
        given().get("/api/v1/users/30/notifications?read=true").then().body("size()", equalTo(1)).body("[0].id", equalTo(readId.toInt()))
        given().get("/api/v1/users/30/notifications?read=false").then().body("size()", equalTo(1)).body("[0].readAt", nullValue())
    }

    @Test
    fun `dead-letters 목록은 비어있고 DEAD 가 아닌 row 에 retry 하면 409 NOT_DEAD_LETTER`() {
        val id = postNotification(payloadFor(recipientId = 40, refId = "c-40"), expectStatus = 201)

        given()
            .get("/api/v1/admin/notifications/dead-letters")
            .then()
            .statusCode(200)
            .body("size()", equalTo(0))

        given()
            .header("X-Actor-Id", "admin-1")
            .post("/api/v1/admin/notifications/$id/retry")
            .then()
            .statusCode(409)
            .body("code", equalTo("NOT_DEAD_LETTER"))
    }

    @Test
    fun `없는 알림에 retry 하면 404 NOTIFICATION_NOT_FOUND`() {
        given()
            .header("X-Actor-Id", "admin-1")
            .post("/api/v1/admin/notifications/9999/retry")
            .then()
            .statusCode(404)
            .body("code", equalTo("NOTIFICATION_NOT_FOUND"))
    }

    @Test
    fun `필수 필드 누락은 400 VALIDATION_FAILED + details 를 동봉한다`() {
        given()
            .contentType(ContentType.JSON)
            .body("""{"recipientId": 1}""")
            .post("/api/v1/notifications")
            .then()
            .statusCode(400)
            .body("code", equalTo("VALIDATION_FAILED"))
            .body("details", notNullValue())
            .body("details.type", notNullValue())
            .body("details.channel", notNullValue())
    }

    private fun postNotification(
        body: String,
        expectStatus: Int,
    ): Long =
        given()
            .contentType(ContentType.JSON)
            .body(body)
            .post("/api/v1/notifications")
            .then()
            .statusCode(expectStatus)
            .extract()
            .jsonPath()
            .getLong("id")

    private fun registerTemplate(
        type: String,
        channel: String,
        subject: String,
        body: String,
    ) {
        given()
            .contentType(ContentType.JSON)
            .body("""{"type":"$type","channel":"$channel","subject":"$subject","body":"$body"}""")
            .post("/api/v1/admin/templates")
            .then()
            .statusCode(201)
    }

    private fun payloadFor(
        recipientId: Int,
        refId: String,
    ): String =
        """
        {
            "recipientId": $recipientId,
            "type": "ENROLL",
            "channel": "EMAIL",
            "refType": "COURSE",
            "refId": "$refId"
        }
        """.trimIndent()
}

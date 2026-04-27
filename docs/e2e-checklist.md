# E2E 검증 체크리스트

자동화는 [`NotificationE2eTest`](../src/test/kotlin/notification/practice/e2e/NotificationE2eTest.kt) 가 Testcontainers 로 MySQL 8 을 띄워 매 PR 마다 실행한다. 이 문서는 동일 시나리오를 손으로 한 번 더 돌려야 할 때 쓰는 체크리스트와 한 번 돌렸을 때의 응답 본문 기록이다.

## 사전 준비

```bash
docker compose up -d
# 또는 MySQL 만 띄우고 ./gradlew bootRun
```

`http://localhost:28080/swagger-ui/index.html` 가 200 이면 시작.

## 시나리오

| # | 호출 | 기대 결과 |
|---|---|---|
| 1 | `POST /api/v1/admin/templates` × 2 (같은 type, EMAIL/IN_APP) | `201` × 2 |
| 2 | `POST /api/v1/notifications` 1차 | `201`, `status=PENDING`, `Location` 헤더 |
| 3 | `POST /api/v1/notifications` 동일 페이로드 2차 | `201`, **같은 `id`**, 워커 처리 후 `status=SENT` |
| 4 | `POST /api/v1/notifications` 같은 멱등키 + 다른 payload | `409 IDEMPOTENCY_CONFLICT` |
| 5 | `GET /api/v1/notifications/{id}` (헤더 누락) | `400 MISSING_HEADER` |
| 6 | `GET /api/v1/notifications/{id}` (타인 ID) | `404 NOTIFICATION_NOT_FOUND` |
| 7 | `PATCH /api/v1/notifications/{id}/read` × 2 | 두 번째에도 `readAt` 동일 (멱등) |
| 8 | `GET /api/v1/users/{userId}/notifications?read=true|false` | 필터 적용된 목록 |
| 9 | `GET /api/v1/admin/notifications/dead-letters` | `200`, 빈 배열 (정상 발송 흐름엔 DLQ 없음) |
| 10 | `POST /api/v1/admin/notifications/9999/retry` | `404 NOTIFICATION_NOT_FOUND` |
| 11 | `POST /api/v1/admin/notifications/{sent_id}/retry` | `409 NOT_DEAD_LETTER` |
| 12 | `POST /api/v1/notifications` 필수 필드 누락 / `scheduledAt` 과거 | `400 VALIDATION_FAILED` + `details` 동봉 |

## 응답 형식 가드

모든 4xx 응답은 `{ code, message, details? }` 형태를 유지한다. 자동화 테스트는 시나리오마다 `code` 값을 단언하므로 형식이 어긋나면 즉시 실패한다.

## 자동화 ↔ 수동 분담

| 항목 | 자동화 | 수동 |
|---|---|---|
| 멱등성 (같은 키 N 회) | ✅ | — |
| 비동기 전이 PENDING → SENT | ✅ (Awaitility 5s) | — |
| 권한 (헤더 누락 / 타인 ID) | ✅ | — |
| DLQ 라우팅 + retry 가드 | ✅ | — |
| Swagger UI 렌더링 / OpenAPI 스펙 | — | UI 직접 확인 |
| 다중 인스턴스 동시성 | — (단일 JVM 동시성은 [`ConcurrentWorkerTest`](../src/test/kotlin/notification/practice/notification/worker/ConcurrentWorkerTest.kt) 가 검증) | 수평 확장 시 별도 부하 테스트 |

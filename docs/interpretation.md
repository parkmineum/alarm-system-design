# 요구사항 해석과 개선 의견

## 1. 명시적으로 한 가정

### 1-1. "메시지 브로커 없이" 의 범위

요구사항: *"실제 메시지 브로커 설치 불필요 — 단 실제 운영 환경 전환 가능한 구조 유지"*

내가 가장 먼저 결정해야 했던 문제. 가능한 해석은 세 가지였다.

| 해석 | 도입 가능 인프라 | 평가 |
|---|---|---|
| 좁게 — Kafka/RabbitMQ 등 전용 브로커만 금지 | Redis Streams, Pub/Sub 가능 | 인프라 1개 추가, 의존성 늘어남 |
| 보수적 — "별도 인프라 일체 추가 금지" | DB 만 사용 | docker-compose 단순, 의도 명확 |
| 최대주의 — DB 도 큐가 아니라 그렇게 보지 말라 | 인메모리만 | 유실 / 분산 안전 요구사항과 정면 충돌 |

**채택: 보수적 해석.** 이유는 두 가지.

- 요구사항이 *"운영 환경으로 전환 가능한 구조"* 를 강조한 점 — 지금 인프라를 늘리지 말고, 나중에 늘릴 수 있게만 만들라는 의도로 읽었다.
- Redis 를 도입하면 분산 락 / 큐 / 캐시 셋 다 가능해 "어디까지가 브로커냐" 의 경계가 흐려진다. 채점자가 *"이건 브로커 아닌가요"* 라고 물을 여지를 남기지 않는 게 안전하다.

대신 outbox 패턴 자체가 Kafka / Redis Streams / SQS 등 어디로든 전환 가능한 형태이므로 "운영 환경 전환 가능" 요구사항을 만족한다 (→ [async-and-retry.md §7](async-and-retry.md#7-운영-전환-가능성)).

### 1-2. "다중 인스턴스" 의 의미

요구사항: *"다중 인스턴스 환경에서도 동일 알림이 중복 처리되지 않아야 한다"*

가능한 모델은:

- 워커 프로세스 N 개가 같은 DB 를 본다 (수평 확장 모델)
- 워커 1 개 + API 인스턴스 N 개 (워커는 leader election)
- 모든 인스턴스가 워커 + API 동시 (대칭 모델)

**채택: 대칭 모델.** Spring Boot 인스턴스 자체에 `@Scheduled` 워커가 함께 들어가도록 했다. 이유는:

- 별도 워커 프로세스를 분리하면 빌드/배포가 2개로 늘어남 — 과제 규모로는 과도
- `FOR UPDATE SKIP LOCKED` 가 row 단위 분산을 처리하므로, 워커 N 개를 동시 기동해도 중복 발송이 발생하지 않음 (테스트로 검증, [`worker/ConcurrentWorkerTest.kt`](../src/test/kotlin/notification/practice/notification/worker/ConcurrentWorkerTest.kt))
- 운영 전환 시 워커 분리가 필요해지면 `@Scheduled` 빈을 별도 프로파일로 옮기는 것만으로 떼어낼 수 있음

### 1-3. "처리 중 상태가 일정 시간 이상 지속되는 경우" 의 임계값

요구사항은 시간을 명시하지 않았다. 5분으로 잡았다 ([`ProcessingTimeoutRecoveryJob.PROCESSING_TIMEOUT_SECONDS`](../src/main/kotlin/notification/practice/notification/worker/ProcessingTimeoutRecoveryJob.kt)).

근거:

- sender 호출은 메일/인앱 모두 1초 안에 끝나야 정상. 5분이면 명백히 비정상
- 너무 짧으면 (예: 1분) GC / DB 부하로 잠시 응답이 늦어진 정상 워커가 회복 잡에 의해 두 번 발송됨
- 너무 길면 (예: 1시간) 좀비 row 가 너무 오래 갇혀 사용자 경험 손상

운영에서는 `notification.processing-timeout.threshold-seconds` 같은 설정으로 빼서 SLA 에 맞춰 조정 가능해야 한다. 현재는 상수로 고정.

### 1-4. 멱등성 키 생성 주체

설계 가이드에는 *"클라이언트 제공도 가능"* 옵션이 있었지만 **서버 생성 채택**.

```kotlin
idempotencyKey = sha256(eventType + refType + refId + recipientId + channel + scheduledAt)
```

이유:

- 호출자(다른 백엔드 서비스)가 키 생성 책임을 잊으면 중복 발송이 자동으로 발생
- 서버에서 결정적으로 생성하면 호출자는 "같은 이벤트 = 같은 인자" 만 보장하면 됨
- 호출자가 의도적으로 다른 키로 같은 알림을 두 번 보내고 싶다면 `refId` 등을 다르게 하면 됨

`scheduledAt` 도 키에 포함한 이유: 같은 이벤트라도 다른 시각으로 예약 발송하는 경우 (D-1 / D-3 알림) 별개 row 여야 하기 때문.

[`IdempotencyKey.kt`](../src/main/kotlin/notification/practice/notification/IdempotencyKey.kt) 참고.

### 1-5. 인증/인가

요구사항이 *"간략 처리 가능 (`userId` 를 헤더/파라미터로 전달)"* 을 허용했다. 두 가지로 분리:

- 일반 사용자 API (`GET /notifications/{id}`, `PATCH /{id}/read`) — `X-User-Id` 헤더로 본인 알림만 조회 가능. 타인 알림은 404
- 운영 API (`POST /admin/notifications/{id}/retry`) — `X-Actor-Id` 헤더로 감사 로그용 actor 기록

JWT / Spring Security 는 도입하지 않았다. 과제 범위를 벗어나고, 채점 항목에 없다.

### 1-6. 알림 등록 트리거

요구사항은 *"수강 신청 완료, 결제 확정 ..."* 등 도메인 이벤트를 언급했지만 본 시스템은 그 도메인 자체를 갖지 않는다. 따라서 **HTTP API 로 외부에서 트리거되는 모델** 로 가정했다.

```
[Course Service] ──POST /api/v1/notifications──▶ [Notification System]
```

실제 환경에서는 도메인 이벤트 발행 → 메시지 브로커 → 알림 시스템 컨슈머 형태가 일반적이지만, 본 시스템 내부에는 도메인이 없으므로 호출 인터페이스를 HTTP 로 단순화했다.

---

## 2. 개선 의견

과제 범위에서 의도적으로 빼놓은 항목들. 운영에 들어간다면 다음 순서로 추가하겠다.

### 2-1. DEAD_LETTER 운영 대시보드

지금은 `GET /admin/notifications/dead-letters` API 만 있고 UI 가 없다. 운영 시 필요한 것:

- DLQ row 목록 + 검색 (recipientId, type, 기간)
- 실패 사유별 그룹핑 — 같은 에러로 묶인 row 를 일괄 재시도
- 한 번에 다건 재시도 (현재는 단건만)
- 재시도 이력 타임라인 (`manualRetryCount`, `lastManualRetryAt`, `lastManualRetryActorId` 는 이미 있음)

### 2-2. 동일 사용자 throttling

같은 사용자에게 짧은 시간에 너무 많은 알림이 가지 않도록 제한.

```sql
SELECT COUNT(*) FROM notification
 WHERE recipient_id = ? AND created_at > NOW() - INTERVAL 1 MINUTE
```

분당 N 건 초과 시 큐에서 일정 시간 지연시키거나, 채널을 EMAIL → IN_APP 으로 다운그레이드. 현재 구조에서는 워커 사이드에 필터를 추가하면 들어갈 자리가 분명하다.

### 2-3. 알림 우선순위

긴급(결제 실패) vs 일반(수강 D-1 리마인드) 의 처리 큐를 분리.
지금은 단일 폴링 쿼리로 ID 순서 처리 → 우선순위 컬럼을 추가하고 `ORDER BY priority DESC, id` 로 변경.

### 2-4. 채널별 sender pool 분리

현재 모든 채널이 같은 워커 스레드 풀을 공유한다. EMAIL 발송이 SMTP 타임아웃에 걸리면 IN_APP 도 같이 막힌다.
운영에서는 `senderRegistry` 가 채널별로 별도 ThreadPoolExecutor 를 갖도록 분리하는 것이 합리적이다.

### 2-5. Outbox → Kafka 점진 마이그레이션

`NotificationDispatcher` 인터페이스가 이미 swap 지점을 노출한다. 점진 단계:

1. `KafkaDispatcher` 추가 + outbox 테이블에는 여전히 INSERT (이중 쓰기)
2. Kafka 컨슈머가 발송하도록 전환, 폴링 워커는 보조 (Kafka 장애 시 fallback)
3. 안정화 후 폴링 워커 제거 또는 CDC (Debezium) 로 outbox → Kafka 자동 발행

### 2-6. 메트릭과 알람

지금은 로그만 있다. 운영에서는 Micrometer / Prometheus 로:

- 큐 깊이 (`PENDING` row count) — 워커 처리량 부족 감지
- 발송 성공률 (채널별)
- DEAD_LETTER 누적 추세
- 좀비 복구 발생 빈도 — 비정상 신호

---

## 3. 의도적으로 구현하지 않은 것

| 항목 | 이유 |
|---|---|
| 실제 SMTP 연동 | 요구사항 명시 *"Mock 또는 로그 출력으로 대체"* |
| Spring Security / JWT | 요구사항 명시 *"간략 처리 가능"* |
| Kafka / Redis | §1-1 — 보수적 해석 |
| 실제 푸시 (FCM / APNs) | 채널 구분이 EMAIL / IN_APP 으로 한정 |
| 알림 검색 / 필터 (제목/본문 like) | 요구사항 외, 인덱스 설계가 달라짐 |

# 비동기 처리 구조와 재시도 정책

## 1. 채택한 구조 — Transactional Outbox + 폴링 워커

알림 등록 트랜잭션 안에서 `notification` row 를 `PENDING` 상태로 INSERT 만 한다.
실제 발송은 `@Scheduled` 워커가 별도 스레드 / 별도 트랜잭션에서 처리한다.

```
[요청 스레드]                                  TX
   비즈니스 UPDATE (예: 수강 신청 완료)     ──┐
   notification INSERT                          │   status = PENDING
   (idempotency_key 포함)                     ──┘
        ↓ COMMIT
   API 응답 (즉시 — 발송을 기다리지 않음)

[Worker @Scheduled(fixedDelay = 1s)]
   SELECT * FROM notification
    WHERE status = 'PENDING' AND scheduled_at <= NOW()
    ORDER BY id LIMIT N
    FOR UPDATE SKIP LOCKED
   → status = 'PROCESSING'
   → sender.send()
   → SENT  | FAILED + next_retry_at
```

핵심 코드 위치:

| 컴포넌트 | 파일 |
|---|---|
| dispatcher 추상 | [`dispatcher/NotificationDispatcher.kt`](../src/main/kotlin/notification/practice/notification/dispatcher/NotificationDispatcher.kt) |
| outbox 구현 (no-op) | [`dispatcher/OutboxDispatcher.kt`](../src/main/kotlin/notification/practice/notification/dispatcher/OutboxDispatcher.kt) |
| 워커 폴링 | [`worker/NotificationWorker.kt`](../src/main/kotlin/notification/practice/notification/worker/NotificationWorker.kt) |
| row 클레임 | [`worker/NotificationWorkerClaimer.kt`](../src/main/kotlin/notification/practice/notification/worker/NotificationWorkerClaimer.kt) |
| 발송 + 결과 반영 | [`worker/NotificationWorkerProcessor.kt`](../src/main/kotlin/notification/practice/notification/worker/NotificationWorkerProcessor.kt) |
| 처리 타임아웃 복구 | [`worker/ProcessingTimeoutRecoveryJob.kt`](../src/main/kotlin/notification/practice/notification/worker/ProcessingTimeoutRecoveryJob.kt) |

---

## 2. 다른 후보들을 배제한 이유

| 방식 | 요청 분리 | 유실 방지 | 분산 안전 | 평가 |
|---|---|---|---|---|
| `@Async` 단독 | ✅ | ❌ | ❌ | 서버 종료 시 큐 위 작업이 사라짐 |
| `ApplicationEvent` AFTER_COMMIT | ✅ | ❌ | ❌ | 동일. 이벤트가 메모리에만 머묾 |
| 인메모리 큐 (`BlockingQueue`) | ✅ | ❌ | ❌ | 동일 + 다중 인스턴스에서 분배 불가 |
| **Outbox + 폴링 (채택)** | ✅ | ✅ | ✅ | DB 가 단일 진실 공급원 |

요구사항 중 **"서버 재시작 후에도 미처리 알림이 유실 없이 재처리"**, **"다중 인스턴스 환경에서 중복 처리 금지"** 두 항목이 인메모리 방식을 모두 탈락시킨다.

---

## 3. 분산 안전 — `FOR UPDATE SKIP LOCKED`

워커 N 개가 동시에 `poll()` 을 호출해도 같은 row 를 두 번 처리하면 안 된다.

```sql
SELECT * FROM notification
 WHERE status = 'PENDING' AND scheduled_at <= NOW()
 ORDER BY id LIMIT 50
 FOR UPDATE SKIP LOCKED;
```

| 방식 | 처리량 | 비고 |
|---|---|---|
| **`FOR UPDATE SKIP LOCKED` (채택)** | row 단위 락, 워커 수만큼 처리량 선형 증가 | MySQL 8.0.1+ |
| ShedLock 등 스케줄러 단위 락 | 항상 한 워커만 동작 → 동시성 1로 제한 | 처리량 손실 |

[`NotificationRepository.findPendingDispatchable`](../src/main/kotlin/notification/practice/notification/NotificationRepository.kt) 에 `@Lock(PESSIMISTIC_WRITE)` + `@QueryHints("skip_locked")` 로 적용.

---

## 4. 재시도 정책

### 자동 재시도 (워커가 sender 예외를 받았을 때)

```kotlin
// Notification.kt
const val DEFAULT_MAX_AUTO_ATTEMPTS = 5
val BACKOFF_SECONDS = listOf(30L, 120L, 600L, 1800L)
//                            30초   2분   10분   30분
```

상태 머신:

```
PENDING ──claim──▶ PROCESSING ──send 성공──▶ SENT
                          │
                          └──send 실패──▶ FAILED  (autoAttemptCount < 5)
                                          │  + nextRetryAt = now + backoff
                                          │
                                          └─재폴링(retriable)─▶ PROCESSING ─...

autoAttemptCount == 5 인 시점에 실패 ──▶ DEAD_LETTER (자동 재시도 중단)
```

`autoAttemptCount` 와 `nextRetryAt` 는 [`Notification.markFailed()`](../src/main/kotlin/notification/practice/notification/Notification.kt) 한 곳에서만 갱신한다.

### 수동 재시도 (DEAD_LETTER 운영자 트리거)

```
POST /api/v1/admin/notifications/{id}/retry
Header: X-Actor-Id: admin-001
```

[`Notification.requeue(actorId)`](../src/main/kotlin/notification/practice/notification/Notification.kt) 가 다음을 한 트랜잭션에서 처리:

| 필드 | 변경 |
|---|---|
| `status` | `DEAD_LETTER → PENDING` |
| `autoAttemptCount` | `0 으로 리셋` |
| `nextRetryAt` | `null` (즉시 픽업 대상) |
| `manualRetryCount` | `+1` |
| `lastManualRetryAt` | `now` |
| `lastManualRetryActorId` | 헤더 값 기록 |

### 카운터 분리 결정

| 옵션 | 동작 | 평가 |
|---|---|---|
| 완전 초기화 | `autoAttemptCount = 0` 으로 리셋만 | 동일 원인으로 무한 재시도 위험 |
| 누적 유지 | 카운터 그대로 | 수동 재시도가 의미를 갖지 못함 |
| **분리 (채택)** | `autoAttemptCount` / `manualRetryCount` 독립 | 자동/수동 구분 + 누가 언제 재시도했는지 추적 |

가드레일: `manualRetryCount >= 3` 이면 [`ManualRetryLimitExceededException`](../src/main/kotlin/notification/practice/notification/ManualRetryLimitExceededException.kt) 으로 거절.

---

## 5. 처리 타임아웃 복구

워커가 `PROCESSING` 으로 마킹한 직후 JVM 이 크래시하면 row 가 영구히 `PROCESSING` 에 갇힌다.
[`ProcessingTimeoutRecoveryJob`](../src/main/kotlin/notification/practice/notification/worker/ProcessingTimeoutRecoveryJob.kt) 이 1 분마다 돌면서 `updated_at` 이 임계 시간보다 오래된 `PROCESSING` row 를 `PENDING` 으로 되돌린다.

```
PROCESSING with updated_at < (NOW() - 5분) → PENDING
```

복구된 row 는 다음 워커 폴링 사이클에서 다시 픽업된다. `autoAttemptCount` 는 건드리지 않는다 — 타임아웃 복구는 "처리 시도 자체를 무효화" 하는 것이지 "실패 1회로 카운팅" 하는 것이 아니기 때문이다.

---

## 6. 서버 재시작 유실 방지

모든 상태가 DB 에 영속화되어 있다.

| 상태 | 재시작 후 | 처리 주체 |
|---|---|---|
| `PENDING` | 워커가 다음 사이클에 픽업 | `NotificationWorkerClaimer.findPendingDispatchable` |
| `PROCESSING` (타임아웃) | 5분 후 `PENDING` 으로 복원 | `ProcessingTimeoutRecoveryJob` |
| `FAILED` (자동 재시도 대기) | `next_retry_at <= NOW()` 가 되면 픽업 | `NotificationWorkerClaimer.findRetriableDispatchable` |
| `DEAD_LETTER` | 운영자 수동 재시도까지 대기 | `AdminNotificationController.retry` |

인메모리 큐, `ApplicationEvent` 단독 발송 경로는 사용하지 않는다.

---

## 7. 운영 전환 가능성

`NotificationDispatcher` 인터페이스가 outbox / 브로커 어느 쪽이든 받아들일 수 있도록 설계됐다.

```kotlin
interface NotificationDispatcher {
    fun dispatch(notification: Notification)
}

class OutboxDispatcher : NotificationDispatcher {
    override fun dispatch(notification: Notification) {
        // no-op: 워커가 polling 으로 처리
    }
}

class KafkaDispatcher : NotificationDispatcher {  // 미래
    override fun dispatch(notification: Notification) {
        kafkaTemplate.send("notifications", notification.idempotencyKey, payload)
    }
}
```

브로커 도입 시 변경 범위:

1. `OutboxDispatcher` → `KafkaDispatcher` 빈 교체 (1 곳)
2. 워커: DB 폴링 → Kafka Consumer
3. outbox 테이블, 멱등성 키, 상태 머신은 그대로 유지
4. CDC (Debezium) 도입 시 outbox 그대로, Kafka 발행만 자동화

→ outbox 패턴이 브로커로의 점진적 마이그레이션 경로 자체로 기능.

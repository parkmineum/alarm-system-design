# 비동기 처리 구조 및 재시도 정책

## 비동기 처리 구조

알림 발송은 Transactional Outbox 패턴으로 구현했다. API 요청 스레드는 DB INSERT만 수행하고 즉시 응답하며, 발송은 별도 워커가 담당한다.

```
API 요청
  │
  ▼
NotificationService.register()
  │  트랜잭션: notification INSERT (status=PENDING)
  │  응답: 201 Created
  │
  (요청 스레드 종료)

  ┌──────────────────────────────────────────┐
  │  NotificationWorker (@Scheduled, 1초 주기) │
  │                                           │
  │  NotificationWorkerClaimer               │
  │    SELECT FOR UPDATE SKIP LOCKED         │
  │    status → PROCESSING (배치, 최대 50건)  │
  │                                           │
  │  NotificationWorkerProcessor (건별)       │
  │    NotificationSender.send()             │
  │    성공 → SENT                            │
  │    실패 → FAILED (backoff) or DEAD_LETTER │
  └──────────────────────────────────────────┘
```

이 구조는 실제 메시지 브로커(Kafka, RabbitMQ 등)로의 전환을 전제로 설계했다. `NotificationDispatcher` 인터페이스가 Outbox 방식(`OutboxDispatcher`)과 동기 방식(`SyncDispatcher`)을 교체 가능하도록 추상화하고 있다.

### 다중 인스턴스 안전성

`SELECT FOR UPDATE SKIP LOCKED`를 사용한다. 인스턴스 A가 알림 100번을 가져가면, 인스턴스 B는 해당 행을 건너뛰고 다음 대상을 가져간다. DB 락이 유지되는 동안은 물리적으로 중복 할당이 불가능하다.

### 좀비 복구

워커가 PROCESSING 상태로 전환한 뒤 프로세스가 비정상 종료되면 해당 알림은 영구 PROCESSING에 머문다. `ProcessingTimeoutRecoveryJob`이 60초마다 실행되어 5분 이상 PROCESSING 상태인 알림을 PENDING으로 복원한다.

---

## 재시도 정책

### 자동 재시도

발송 실패 시 `auto_attempt_count`를 증가시키고 `next_retry_at`을 설정한다. 워커는 `next_retry_at <= NOW()`인 FAILED 알림만 폴링하므로 backoff가 자연스럽게 구현된다.

| 시도 회차 | 실패 후 다음 시도까지 대기 |
|---|---|
| 1회 (최초) | 30초 |
| 2회 | 2분 |
| 3회 | 10분 |
| 4회 | 30분 |
| 5회 | DEAD_LETTER 전이 |

최대 자동 시도 횟수: 5회. 5회 모두 실패하면 `DEAD_LETTER`로 전이되고 자동 재시도가 중단된다.

### 수동 재시도 (관리자)

`DEAD_LETTER` 상태 알림은 관리자가 `POST /api/v1/admin/notifications/{id}/retry`로 수동 재시도할 수 있다.

수동 재시도 시 발생하는 변화:
- `status` → `PENDING`
- `auto_attempt_count` → 0 (초기화)
- `next_retry_at` → null
- `last_error` → null
- `manual_retry_count` 증가
- `last_manual_retry_at`, `last_manual_retry_actor_id` 기록

`auto_attempt_count`를 0으로 초기화하므로 수동 재시도 1회당 자동 시도 최대 5회가 새로 소비된다. 수동 재시도 최대 허용 횟수는 3회다.

### 상태 전이 다이어그램

```
PENDING
  │
  ▼ 워커 픽업
PROCESSING
  │
  ├─ 성공 ──────────────────────────── SENT (종단)
  │
  └─ 실패
       │
       ├─ auto_attempt_count < 5 ──── FAILED ──── (backoff 후) ──▶ PROCESSING
       │
       └─ auto_attempt_count == 5 ── DEAD_LETTER
                                          │
                                          └─ 관리자 수동 재시도 ──▶ PENDING
```

### 설계 근거

**왜 낙관적 락이 아닌 비관적 락인가.** 낙관적 락은 충돌 시 예외를 던지고 호출자가 재시도해야 한다. 워커 환경에서 충돌이 반복되면 배치 처리량이 떨어지고 복잡도가 증가한다. 비관적 락(`SKIP LOCKED`)은 충돌 없이 각 워커가 겹치지 않는 행을 가져가므로 운영 복잡도가 낮다.

**왜 카운터를 분리했는가.** `auto_attempt_count`만 있으면 관리자가 수동으로 재시도한 사실을 추적할 수 없다. 분리하면 "자동으로 몇 번 실패했는지"와 "관리자가 몇 번 개입했는지"를 독립적으로 관리할 수 있어 운영 감사 로그로 활용 가능하다.

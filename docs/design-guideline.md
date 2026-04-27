# 설계

## 1. 도메인 모델

```
Notification
  id, recipientId, type, channel (EMAIL | IN_APP)
  refType, refId, payload (JSON), renderedBody
  status, autoAttemptCount, maxAutoAttempts, manualRetryCount
  nextRetryAt, lastError
  idempotencyKey (UNIQUE)
  scheduledAt, processedAt, readAt
  templateId, templateVersion
  createdAt, updatedAt
```

상태 흐름: `PENDING → PROCESSING → SENT | FAILED → DEAD_LETTER`

읽음 처리는 별도 분리 가능 (`NotificationRead` 테이블) — 다중 기기 시나리오에 따라 결정.

---

## 2. 멱등성

`idempotencyKey = hash(eventType + refId + recipientId + channel)`

- DB UNIQUE 제약으로 중복 INSERT 차단
- Duplicate key 예외는 무시하고 정상 응답으로 처리 (재발행 안전)
- 키 생성 주체는 서버 (요구사항에 따라 클라이언트 제공도 가능 — README 에 결정 근거 명시)

---

## 3. 비동기 처리 — Transactional Outbox + 폴링 워커

브로커 없이 DB 기반 outbox 로 구현. 인메모리 큐 / `@Async` 단독 / `ApplicationEvent` 단독은 서버 종료 시 유실 위험으로 배제.

### 흐름

```
[요청 스레드]                 TX
   business UPDATE         ──┐
   notification INSERT       │   (status = PENDING)
   (idempotencyKey 포함)    ──┘
        ↓ COMMIT
   API 응답 (즉시)

[Worker @Scheduled(fixedDelay = 500ms)]
   SELECT * FROM notification
    WHERE status = 'PENDING' AND scheduled_at <= NOW()
    ORDER BY id LIMIT N
    FOR UPDATE SKIP LOCKED
   → status = 'PROCESSING'
   → 채널 어댑터 호출
   → SENT | FAILED + nextRetryAt 갱신
```

### 다른 방식과의 비교

| 방식 | 요청 분리 | 유실 방지 | 분산 안전 |
|---|---|---|---|
| `@Async` 단독 | ✅ | ❌ | ❌ |
| `ApplicationEvent` AFTER_COMMIT | ✅ | ❌ | ❌ |
| 인메모리 큐 (`BlockingQueue`) | ✅ | ❌ | ❌ |
| **Outbox + 폴링 (채택)** | ✅ | ✅ | ✅ |
| Outbox + AFTER_COMMIT 즉시발송 + 폴링 백업 | ✅ | ✅ | ✅ (지연 최소화) |

---

## 4. 분산 인스턴스 중복 처리 방지

`SELECT ... FOR UPDATE SKIP LOCKED` (MySQL 8.0.1+).
워커 N 개가 같은 row 를 동시에 잡지 않음.

| 방식 | 처리량 | 비고 |
|---|---|---|
| **`FOR UPDATE SKIP LOCKED` (채택)** | row 단위 락, 처리량 유지 | MySQL 8.0.1+ |
| ShedLock 등 스케줄러 단위 락 | 동시성 1로 제한 | 처리량 손실 |

---

## 5. 재시도 정책

- Exponential backoff: 1m → 5m → 30m → 2h → 6h
- `autoAttemptCount >= maxAutoAttempts` → `DEAD_LETTER`
- 좀비 복구 잡: `PROCESSING` 이 N 분 이상 머무는 row → `PENDING` 으로 복원 (서버 크래시 대응)

---

## 6. 서버 재시작 유실 방지

- 모든 상태가 DB 에 영속화
- 재시작 후 워커는 `PENDING` 과 stuck `PROCESSING` 을 다시 픽업
- 인메모리 큐, `ApplicationEvent` 단독은 사용하지 않음

---

## 7. 운영 전환 가능성 — 추상화 경계

```kotlin
interface NotificationDispatcher {
    fun enqueue(notification: Notification)
}

class OutboxDispatcher : NotificationDispatcher
class KafkaDispatcher : NotificationDispatcher  // 미래

interface NotificationSender { ... }
class EmailSender : NotificationSender
class InAppSender : NotificationSender
```

브로커 도입 시 변경 범위:

1. `OutboxDispatcher` → `KafkaDispatcher` 빈 교체 (1 곳)
2. 워커: DB 폴링 → Kafka Consumer
3. outbox 테이블, 멱등성 키, 상태 머신은 유지
4. CDC (Debezium) 도입 시 outbox 그대로, Kafka 발행만 자동화

→ outbox 패턴이 브로커로의 점진적 마이그레이션 경로 자체로 기능.

---

## 8. Redis 미도입 근거

| 용도 | DB 대체 |
|---|---|
| 분산 락 (Redisson) | `SELECT ... FOR UPDATE SKIP LOCKED` |
| 큐 (Streams) | Outbox 폴링 |
| 멱등성 키 캐시 | DB UNIQUE 제약 |
| Rate limiting | 필요 시 별도 도입 |

- "메시지 브로커 없이" 요구사항을 보수적으로 해석할 때 Redis Streams/Pub-Sub 도 회색지대
- 인프라 1개 추가는 docker-compose 복잡도, 의존성, 운영 비용 증가
- Redis 도입 시 변경되는 부분은 README 에 별도 섹션으로 명시

---

## 9. 발송 스케줄링

별도 스케줄러 인프라 없이 `scheduled_at` 컬럼으로 통합.

```sql
SELECT * FROM notification
 WHERE status = 'PENDING' AND scheduled_at <= NOW()
 ORDER BY scheduled_at, id
 FOR UPDATE SKIP LOCKED;
```

- 즉시 발송: `scheduled_at = NOW()`
- 예약 발송: `scheduled_at = 미래 시각`
- 인덱스: `(status, scheduled_at)`

---

## 10. 템플릿

```
NotificationTemplate
  id, type, channel, subject, body, version, active
```

렌더링 흐름:

1. 발송 요청 시 `payload` (변수값) 저장
2. 워커가 발송 직전 `template.render(payload)` 호출
3. 결과를 `Notification.renderedBody` 에 영속화 — 발송 시점 메시지 보존

버전 정책: 수정 시 새 row + `version++`, 기존 row `active = false`.
렌더러는 Strategy 패턴 (`MustacheRenderer`, `ThymeleafRenderer` 등 교체 가능).

---

## 11. 읽음 처리 (다중 기기)

읽음은 멱등 연산으로 모델링.

```sql
UPDATE notification
   SET read_at = NOW()
 WHERE id = ?
   AND recipient_id = ?
   AND read_at IS NULL;
```

- `affected rows = 1`: 최초 읽음
- `affected rows = 0`: 이미 읽음 (200 OK, no-op)
- DB row write lock 이 자동 직렬화 — 별도 락 불필요
- `read_at IS NULL` 조건이 첫 읽음 시각 보존

| 방식 | 결과 |
|---|---|
| 무조건 UPDATE | 마지막 요청 시각으로 덮어씀 (첫 읽음 시각 손실) |
| `SELECT FOR UPDATE` 후 UPDATE | 정확하지만 불필요한 락 |
| **조건부 UPDATE (채택)** | 멱등 + 첫 시각 보존 + 락 없음 |

---

## 12. DEAD_LETTER 와 수동 재시도

```
PENDING → PROCESSING → SENT
                    ↓
                  FAILED (auto retry)
                    ↓ (autoAttemptCount >= max)
                  DEAD_LETTER
                    ↓ (admin 트리거)
                  PENDING (재진입)
```

### 카운터 분리

| 옵션 | 동작 | 평가 |
|---|---|---|
| A. 완전 초기화 | `autoAttemptCount = 0` | 동일 원인 무한 재시도 위험 |
| B. 누적 유지 | 카운터 그대로 | 수동 재시도 의미 약화 |
| **C. 분리 (채택)** | `autoAttemptCount` / `manualRetryCount` 분리 | 자동/수동 구분 + 추적 |

### 수동 재시도 시 동작

1. `status: DEAD_LETTER → PENDING`
2. `autoAttemptCount = 0`
3. `manualRetryCount += 1`
4. `nextRetryAt = NOW()` (즉시 처리)
5. 워커가 다시 픽업

### 가드레일

- `manualRetryCount` 한도 (예: 3회) — 운영자 무한 클릭 방지
- `actorId` 기록 — 감사 로그용

---

## 13. 최종 데이터 모델

```
Notification
  id, recipient_id, type, channel
  ref_type, ref_id
  payload (JSON), rendered_body
  status (PENDING | PROCESSING | SENT | FAILED | DEAD_LETTER)
  scheduled_at, processed_at, read_at
  auto_attempt_count, max_auto_attempts
  manual_retry_count, last_manual_retry_at
  next_retry_at, last_error
  error_history (JSON, optional)
  idempotency_key UNIQUE
  template_id, template_version
  created_at, updated_at

NotificationTemplate
  id, type, channel, subject, body, version, active

NotificationAuditLog (선택)
  id, notification_id, actor, action, at, detail
```

### 인덱스

| 인덱스 | 용도 |
|---|---|
| `(status, scheduled_at)` | 워커 폴링 |
| `(recipient_id, read_at)` | 사용자 수신함 |
| `idempotency_key` UNIQUE | 멱등성 |
| `(status, next_retry_at)` | 재시도 큐 |

---

## 14. API

```
POST   /notifications                   요청 등록 (내부 호출)
GET    /notifications/{id}              상태 조회
GET    /me/notifications?read=false     수신함
PATCH  /notifications/{id}/read         읽음 처리
POST   /admin/notifications/{id}/retry  DEAD_LETTER 수동 재시도
```

---

## 15. 테스트 시나리오

- **멱등성**: 같은 키로 N 회 요청 → DB row 1 건
- **분산 워커**: 워커 2~3 개 동시 기동 → 중복 발송 0 건
- **재시도**: 첫 시도 실패 → backoff 경과 → 두 번째 시도 성공
- **좀비 복구**: `PROCESSING` 강제 변경 + timeout 경과 → 자동 `PENDING` 복원
- **읽음 동시성**: 다중 디바이스 동시 읽음 → `read_at` 최초 시각 유지

---

## 16. README 보강 항목

- 비동기 처리 구조 및 재시도 정책 (Outbox 선택 근거, 브로커 도입 시 마이그레이션 경로)
- Redis 미도입 근거와 도입 시 변경 부분
- 요구사항 해석 및 가정
- 개선 의견 (DEAD_LETTER 운영 대시보드, 동일 사용자 알림 throttling 등)
# 비동기 처리 구조와 재시도 정책

이 문서는 알림 발송이 어떤 경로로 비동기 처리되는지, 실패가 어떻게 재시도되는지, 그리고 그 설계가 어떤 트레이드오프를 거쳐 선택되었는지를 정리한다.

---

## 1. 만족해야 하는 제약

| # | 제약 | 출처 |
|---|---|---|
| C1 | 알림 처리 실패가 비즈니스 트랜잭션을 깨뜨리지 않아야 한다 | 과제 본문 |
| C2 | 동일 이벤트에 대해 알림이 중복 발송되지 않는다 | 과제 본문 |
| C3 | API 요청 스레드와 발송이 분리된다 | 필수 4 |
| C4 | 메시지 브로커 없이 구현하되, **운영 환경 전환 가능**해야 한다 | 필수 4 |
| C5 | 처리 중 상태가 일정 시간 이상 지속되면 복구된다 | 필수 5 |
| C6 | 서버 재시작 후에도 미처리 알림이 유실 없이 재처리된다 | 필수 5 |
| C7 | 다중 인스턴스 환경에서도 동일 알림이 중복 처리되지 않는다 | 필수 5 |

C3 과 C6 은 동시에 만족시키기가 까다롭다. "요청 스레드와 분리" 만 보면 인메모리 큐로도 충분하지만, "재시작 후 유실 없음" 을 함께 만족시키려면 **상태가 영속화된 매체**가 필요하다.

---

## 2. 채택 구조 — Transactional Outbox + 폴링 워커

비즈니스 트랜잭션에서 `notification` 테이블에 한 행을 `PENDING` 상태로 INSERT 하고, 별도 워커가 그 행을 폴링해 실제 발송을 수행한다. DB 가 큐 역할을 겸한다.

```
[요청 스레드]                       TX
   business UPDATE              ──┐
   notification INSERT            │   (status = PENDING,
   (idempotencyKey 포함)         ──┘    scheduled_at = NOW)
        ↓ COMMIT
   API 응답 (즉시 반환)

[Worker @Scheduled(fixedDelay = 500ms)]   별도 TX
   SELECT * FROM notification
    WHERE status = 'PENDING'
      AND scheduled_at <= NOW()
    ORDER BY scheduled_at, id
    LIMIT N
    FOR UPDATE SKIP LOCKED;
   → status = 'PROCESSING'        (commit)
   → 채널 어댑터 호출 (네트워크)
   → 결과에 따라 SENT 또는 FAILED + nextRetryAt 갱신
```

핵심 포인트:

- **요청 트랜잭션과 비즈니스 트랜잭션이 한 묶음**이다. 결제 완료가 커밋되어야 그에 대한 알림 row 도 함께 커밋된다. 알림이 누락되거나, 반대로 결제가 롤백되었는데 알림만 나가는 일이 원천적으로 없다.
- **워커는 별도 트랜잭션**이다. 발송 중 예외가 비즈니스 트랜잭션에 전파되지 않는다 (C1).
- **서버가 죽어도 PENDING row 는 DB 에 남는다**. 재시작 후 다음 폴링 사이클에서 자연스럽게 다시 픽업된다 (C6).

---

## 3. 다른 비동기 방식과의 비교

| 방식 | 요청 분리(C3) | 유실 방지(C6) | 분산 안전(C7) | 운영 가시성 | 평가 |
|---|---|---|---|---|---|
| `@Async` 단독 | ✅ | ❌ JVM 메모리 | ❌ | 낮음 (로그뿐) | 단일 인스턴스 데모용 |
| `ApplicationEvent` AFTER_COMMIT | ✅ | ❌ JVM 메모리 | ❌ | 낮음 | 트랜잭션 경계는 깔끔하지만 영속성 없음 |
| 인메모리 `BlockingQueue` | ✅ | ❌ | ❌ | 낮음 | 백프레셔는 가능, 영속성 없음 |
| **Outbox + 폴링 (채택)** | ✅ | ✅ | ✅ (락으로) | 높음 (DB 쿼리로 모든 상태 확인) | 운영 부담 ↑ 이지만 모든 제약 충족 |
| Kafka 등 외부 브로커 | ✅ | ✅ | ✅ | 높음 | 과제 제약상 도입 불가 |
| Outbox + AFTER_COMMIT 즉시발송 + 폴링 백업 | ✅ | ✅ | ✅ | 높음 | 정상 경로 지연 최소화, 유실 시 폴링이 보완 |

마지막 줄의 하이브리드(즉시발송 + 폴링 백업)는 처리 지연을 줄이는 일반적인 최적화다. 채점 기준 안에서는 단순한 폴링만으로 모든 요건이 충족되므로, 즉시발송 경로는 향후 개선 항목으로 남겨두었다 ([interpretation.md](interpretation.md) §개선 의견 참조).

---

## 4. 다중 인스턴스 중복 처리 방지

`SELECT ... FOR UPDATE SKIP LOCKED` (MySQL 8.0.1+) 로 row 단위 락을 건다. 여러 워커가 같은 쿼리를 동시에 실행해도 락이 걸린 행은 다른 워커에게 보이지 않는다.

| 방식 | 처리량 | 비고 |
|---|---|---|
| **`FOR UPDATE SKIP LOCKED` (채택)** | row 단위 락, 워커 N 개 병렬 처리 | MySQL 8.0.1+ 필수 |
| 스케줄러 단위 분산 락 (ShedLock 등) | 동시성 1 로 제한 | 처리량이 워커 수만큼 손해 |
| 비관적 락 + `SKIP LOCKED` 미사용 | row 단위지만 다른 워커가 대기 | 락 대기로 처리량 저하 |
| 낙관적 락 (`@Version`) | 충돌 후 재시도 | 충돌 빈도 높을수록 비효율 |

워커가 픽업 직후 `status = PROCESSING` 으로 즉시 UPDATE 하고 짧은 트랜잭션으로 락을 풀면, 실제 발송(네트워크 호출)은 락 밖에서 수행된다. 락 보유 시간이 짧을수록 다른 워커가 다음 row 를 가져갈 때까지의 대기가 줄어든다.

---

## 5. 재시도 정책

### 5.1 자동 재시도 — Exponential Backoff (with jitter)

| 시도 | 다음 재시도까지 | 누적 경과 |
|---|---|---|
| 1 (실패) | 1m | 1m |
| 2 (실패) | 5m | 6m |
| 3 (실패) | 30m | 36m |
| 4 (실패) | 2h | 약 2.5h |
| 5 (실패) | 6h | 약 8.5h |
| 6 도달 | DEAD_LETTER | — |

위 값에 **`±20%` jitter** 를 더해 `nextRetryAt` 을 계산한다. 동일 실패 원인으로 묶여 있는 다수의 알림이 같은 시각에 동시에 재시도되어 외부 게이트웨이에 thundering herd 를 일으키는 것을 방지한다.

```
nextRetryAt = NOW() + base * (1 + uniform(-0.2, 0.2))
```

### 5.2 DEAD_LETTER 전이

`autoAttemptCount >= maxAutoAttempts` 시 `status = DEAD_LETTER`. 자동 처리는 멈추고, 운영자의 수동 개입 대상이 된다.

### 5.3 수동 재시도

자동 카운터(`autoAttemptCount`) 와 수동 카운터(`manualRetryCount`) 를 분리한다.

| 옵션 | 동작 | 평가 |
|---|---|---|
| A. 완전 초기화 | `autoAttemptCount = 0` 만으로 부활 | 동일 원인이 그대로면 무한 자동 재시도 위험 |
| B. 누적 유지 | 카운터 그대로 두고 PENDING 으로 변경 | 첫 자동 사이클이 즉시 DEAD_LETTER 로 회귀 |
| **C. 분리 (채택)** | `autoAttemptCount = 0`, `manualRetryCount += 1` | 자동/수동을 명확히 구분, 운영자 행동 추적 가능 |

가드레일:

- `manualRetryCount` 한도(예: 3) 를 두어 운영자 무한 클릭 방지
- 액션 수행자의 식별자(`actorId`) 를 기록해 감사 로그 확보

### 5.4 어떤 실패는 재시도하지 않는가

지수 백오프 자체는 외부 의존성의 일시적 장애(네트워크 끊김, 5xx, 레이트 리밋) 를 상정한다. 영속적 실패(이메일 형식 오류, 수신자 없음, 4xx) 는 같은 정책으로 재시도해 봐야 똑같이 실패한다. 재시도 정책에 두 가지 분기를 둔다.

| 실패 분류 | 결정 기준 | 처리 |
|---|---|---|
| 일시적 (transient) | 5xx, 타임아웃, 커넥션 오류 | 자동 재시도 (백오프) |
| 영속적 (permanent) | 4xx, 잘못된 수신자 | 즉시 DEAD_LETTER, 자동 재시도 생략 |

`NotificationSender` 어댑터가 결과를 `SendResult.Retryable` / `SendResult.Permanent` / `SendResult.Success` 로 반환하고, 워커는 이 분류에 따라 분기한다.

---

## 6. 좀비 복구 — PROCESSING 정체 회복

워커가 row 를 `PROCESSING` 으로 바꾼 직후 서버가 비정상 종료되면 그 row 는 영원히 `PROCESSING` 상태로 남는다. 어떤 워커도 그 행을 다시 가져가지 않는다.

복구 잡(`@Scheduled` 별도 인스턴스) 이 주기적으로 다음 쿼리를 실행한다:

```sql
UPDATE notification
   SET status = 'PENDING',
       last_error = 'recovered: stuck in PROCESSING'
 WHERE status = 'PROCESSING'
   AND processed_at < NOW() - INTERVAL 10 MINUTE;
```

| 임계값 | 트레이드오프 |
|---|---|
| 짧음 (예: 1분) | 정상 발송이 길어지는 경우 잘못된 복구 발생 |
| **중간 (10분, 채택)** | 외부 게이트웨이 타임아웃 한계(보통 30~60s) 보다 충분히 길면서, 운영 지연 부담은 적음 |
| 긺 (예: 1시간) | 좀비가 너무 오래 방치되어 사용자 체감 지연 ↑ |

복구된 row 는 자동 카운터를 증가시키지 않는다. 좀비는 외부 호출 결과를 알 수 없는 상태이므로, 카운터를 올리면 정상 동작 가능한 row 가 빠르게 DEAD_LETTER 로 갈 위험이 있다.

---

## 7. 멱등성과 중복 발송 방지

```
idempotencyKey = sha256(eventType + ':' + refType + ':' + refId + ':' + recipientId + ':' + channel)
```

- DB UNIQUE 제약으로 중복 INSERT 차단
- Duplicate key 예외는 무시하고 정상 응답 → 발행자 관점에서 idempotent
- 같은 사용자에게 같은 강의의 D-1 알림을 두 번 만들고 싶다면 `eventType` 에 발생 시각을 포함시키는 식으로 키를 다르게 구성

키 생성 주체는 **서버**다. 클라이언트 제공도 가능하지만, 내부 호출 환경에서는 호출자마다 키 정책이 흩어지는 것보다 한 곳에서 일관되게 만드는 편이 단순하다.

---

## 8. 서버 재시작 시 유실 방지

모든 상태가 DB 에 영속화되어 있으므로 별도의 복구 로직이 필요 없다. 재시작 후 워커가 첫 폴링을 돌면:

1. `PENDING` 인 row 는 그대로 픽업
2. `PROCESSING` 으로 멈춰있던 row 는 좀비 복구 잡이 임계값 경과 후 `PENDING` 으로 되돌림
3. `SENT`/`FAILED`/`DEAD_LETTER` 는 종결 상태이므로 무시

JVM 메모리에 의존하는 `@Async`, `ApplicationEvent`, 인메모리 큐 방식이 모두 이 지점에서 탈락한다.

---

## 9. 브로커 도입 시 마이그레이션 경로

```kotlin
interface NotificationDispatcher {
    fun enqueue(notification: Notification)
}

class OutboxDispatcher : NotificationDispatcher       // 현재
class KafkaDispatcher : NotificationDispatcher        // 미래
```

브로커 도입 시 변경 범위:

1. `OutboxDispatcher` → `KafkaDispatcher` 빈 교체 (1 곳)
2. 워커: DB 폴링 → Kafka Consumer
3. outbox 테이블, 멱등성 키, 상태 머신은 유지
4. CDC (Debezium) 도입 시 outbox 그대로, Kafka 발행만 자동화

즉, **outbox 패턴 자체가 브로커로의 점진적 마이그레이션 경로** 다. 빅뱅 전환이 아니라 dispatcher 빈 단위로 트래픽을 옮기면서 기존 폴링 워커를 백업으로 둘 수 있다.

---

## 10. 운영 관찰 포인트

| 지표 | 의미 | 임계 알람 예시 |
|---|---|---|
| `PENDING` 누적 개수 | 워커 처리량 부족 / 다운 | `> 1000` 또는 5분 연속 증가 |
| `PROCESSING` 정체 시간 | 좀비 발생 | `oldest > 30m` |
| `DEAD_LETTER` 일별 증가량 | 영속적 실패 또는 정책 한도 도달 | `> 평소 평균 × 3` |
| 채널별 실패율 | 외부 게이트웨이 장애 | `> 10% / 5m` |
| `auto_attempt_count` 평균 | 외부 의존성 상태 추정 | `> 2` 지속 시 의심 |

이 지표들은 모두 `notification` 테이블 단일 쿼리로 추출 가능하다. 별도 메트릭 인프라가 없는 단계에서도 운영 SQL 한 벌만 준비하면 된다.

---

## 11. 가정과 결정의 한계

- **순서 보장은 하지 않는다.** 같은 사용자의 알림이라도 발송 순서가 등록 순서와 일치한다는 보장은 없다. 워커 동시성과 재시도 백오프로 인해 자연스럽게 어긋난다. 순서가 의미 있는 시나리오라면(예: 결제 완료 → 영수증 → 강의 시작) 비즈니스 레이어가 발송 시점을 제어하거나, partition key 단위 직렬화를 별도로 도입해야 한다.
- **글로벌 throttle 은 없다.** 같은 사용자에게 짧은 시간 내 다수 알림이 트리거되면 전부 발송된다. 사용자 단위 throttle / digest 는 [interpretation.md](interpretation.md) 의 개선 의견에 정리한다.
- **외부 게이트웨이의 응답이 정확하다고 가정한다.** 실제로는 게이트웨이가 "성공" 으로 응답했지만 메일이 도달하지 않을 수 있다. 그 케이스는 알림 시스템 단독으로 해결할 수 없으며, 사용자의 실제 수신 확인 신호(웹훅, 클릭 트래킹) 가 필요하다.

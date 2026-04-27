# 알림 발송 시스템

수강 신청 완료, 결제 확정, 강의 시작 D-1, 취소 등의 비즈니스 이벤트가 발생할 때 사용자에게 EMAIL / IN_APP 알림을 비동기로 발송한다. 메시지 브로커 없이 **Transactional Outbox + 폴링 워커** 로 구현하되, 실제 운영 환경(Kafka 등) 으로 점진적 전환이 가능하도록 dispatcher 추상 경계를 둔다.

## 한눈에 보기

```
[비즈니스 트랜잭션]
  결제 UPDATE  ──┐
  notification  │   status = PENDING
  INSERT        │   idempotency_key UNIQUE
                ──┘
       COMMIT
       │
       ▼ (응답 즉시 반환)
[폴링 워커 N 개]   매 500ms
  SELECT ... FOR UPDATE SKIP LOCKED
       │
       ▼
  채널 어댑터 (EmailSender / InAppSender)
       │
       ├─ 성공  → SENT
       └─ 실패  → FAILED + 백오프된 next_retry_at
                    │
                    └─ max 도달 → DEAD_LETTER (수동 재시도 대상)
```

핵심 설계 결정 4 줄:

1. **알림 INSERT 가 비즈니스 트랜잭션 안에서 함께 커밋된다** — 결제가 롤백되면 알림 row 도 같이 사라진다. 알림 실패가 비즈니스를 깨뜨리지 않고, 비즈니스 롤백이 알림 누수를 만들지도 않는다.
2. **DB 가 큐 역할을 한다** — 별도 메시지 브로커 없이도 서버 재시작 / 다중 인스턴스 / 좀비 복구가 모두 DB 한 종류로 해결된다.
3. **`SELECT ... FOR UPDATE SKIP LOCKED` 로 워커 N 개가 안전하게 병렬 처리** — ShedLock 같은 스케줄러 단위 락보다 처리량이 높다.
4. **dispatcher 가 추상 인터페이스** — `OutboxDispatcher` → `KafkaDispatcher` 로 빈 한 곳만 교체하면 운영 브로커로 전환된다.

## 문서 구성

이 README 는 시스템 개요와 실행 방법을 다룬다. 깊이 있는 설계 근거와 트레이드오프는 별도 문서에 있다.

| 문서 | 다루는 내용 |
|---|---|
| **[docs/async-and-retry.md](docs/async-and-retry.md)** | 비동기 처리 구조, 재시도 정책, 좀비 복구, 분산 안전, 브로커 마이그레이션 경로 |
| **[docs/interpretation.md](docs/interpretation.md)** | 요구사항 해석과 가정, 의도적으로 제외한 것, 실제 운영 시 우선 보완할 항목 |
| [.claude/docs/assignment.md](.claude/docs/assignment.md) | 과제 요구사항 원문 |
| [.claude/docs/design-guideline.md](.claude/docs/design-guideline.md) | 설계 결정 노트 (도메인 모델, 멱등성, 템플릿, 읽음 처리 등) |
| [.claude/docs/roadmap.md](.claude/docs/roadmap.md) | 단계별 PR 계획 |
| [.claude/docs/git-conventions.md](.claude/docs/git-conventions.md) | 브랜치 / 커밋 / PR 컨벤션 |

---

## 기술 스택

- **언어 / 런타임**: Kotlin 1.9.25 / Java 17
- **프레임워크**: Spring Boot 3.5.14, Spring Data JPA, Spring Web
- **데이터베이스**: MySQL 8.0 (운영) / H2 (테스트)
- **빌드**: Gradle Kotlin DSL
- **린트 / 포매팅**: ktlint
- **컨테이너**: Docker / docker-compose

선택 근거는 [docs/interpretation.md](docs/interpretation.md) 참조. 요지: 과제 제약 하에서 가장 익숙한 조합으로 구현 시간을 아끼고, 결정의 비중을 설계 영역에 둔다.

---

## 실행 방법

### 로컬 (앱만 실행, MySQL 별도)

```bash
./gradlew bootRun
```

### Docker Compose (앱 + MySQL)

```bash
docker compose up -d
```

기본 포트 `8080`. MySQL 은 컨테이너 내 `db:3306` 으로 떠 있고, 호스트에서는 `localhost:3306` 으로 접근.

### 테스트

```bash
./gradlew test           # 전체 테스트
./gradlew ktlintCheck    # 린트
```

### Git hook 설치 (선택)

```bash
./scripts/install-hooks.sh
```

- pre-commit: 컴파일 검증
- pre-push: `ktlintCheck` + `test`

---

## API 목록

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/notifications` | 알림 등록 (내부 호출용) |
| `GET` | `/notifications/{id}` | 단일 알림 상태 조회 |
| `GET` | `/me/notifications?read=false` | 수신자 기준 알림 목록 (읽음/안읽음 필터) |
| `PATCH` | `/notifications/{id}/read` | 읽음 처리 (다중 기기 멱등) |
| `POST` | `/admin/notifications/{id}/retry` | DEAD_LETTER 수동 재시도 |

### 등록 요청 예시

```http
POST /notifications
Content-Type: application/json

{
  "recipientId": "u_42",
  "type": "PAYMENT_CONFIRMED",
  "channel": "EMAIL",
  "refType": "PAYMENT",
  "refId": "p_1024",
  "payload": { "amount": 49000, "courseName": "Spring Boot 입문" },
  "scheduledAt": null
}
```

응답:

```json
{
  "id": "n_8X3K",
  "status": "PENDING"
}
```

같은 키로 재요청해도 row 는 1 건이 보장된다 (멱등성 키 = `sha256(type + refType + refId + recipientId + channel)`).

### 에러 응답 형식

```json
{
  "code": "INVALID_CHANNEL",
  "message": "지원하지 않는 채널입니다",
  "details": { "channel": "SMS" }
}
```

---

## 데이터 모델

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
  idempotency_key UNIQUE
  template_id, template_version
  created_at, updated_at

NotificationTemplate
  id, type, channel, subject, body, version, active
```

상태 전이:

```
PENDING ─► PROCESSING ─► SENT
                      └─► FAILED ─► (auto retry 한도 도달) ─► DEAD_LETTER
                                                              │
                                                              └─ (수동 재시도) ─► PENDING
```

인덱스:

| 인덱스 | 용도 |
|---|---|
| `(status, scheduled_at)` | 워커 폴링 |
| `(recipient_id, read_at)` | 사용자 수신함 |
| `idempotency_key` UNIQUE | 멱등성 |
| `(status, next_retry_at)` | 재시도 큐 |

엔티티 단위 결정 근거는 [.claude/docs/design-guideline.md](.claude/docs/design-guideline.md) 1, 13장 참조.

---

## 요구사항 해석과 가정

상세는 [docs/interpretation.md](docs/interpretation.md) 에 정리한다. 요약:

- "메시지 브로커 없이" 는 Kafka / RabbitMQ / Redis Streams 까지 보수적으로 제외 — DB 만으로 모든 요건 충족
- 멱등성 키 생성은 **서버 측** — 호출자별 키 정책 분산을 막는다
- 처리 중 정체 임계값은 **10분** — 외부 게이트웨이 타임아웃보다 충분히 길고, 사용자 체감 지연은 작음
- "다중 인스턴스" = 같은 앱의 수평 확장 (멀티 리전 / 활성-활성 DB 는 범위 밖)
- 알림 순서 보장 / 사용자 단위 throttle 은 의도적으로 제외 (개선 의견에 정리)

---

## 설계 결정과 이유 (요약)

| 결정 | 이유 | 상세 |
|---|---|---|
| Outbox + 폴링 워커 | 메모리 큐는 재시작 시 유실, `@Async` 단독은 분산 안전성 ❌ | [async-and-retry §2](docs/async-and-retry.md) |
| `FOR UPDATE SKIP LOCKED` | 스케줄러 단위 락은 동시성 1로 제한되어 처리량 손실 | [async-and-retry §4](docs/async-and-retry.md) |
| Exponential backoff + jitter | 동일 시각 동시 재시도로 인한 thundering herd 방지 | [async-and-retry §5.1](docs/async-and-retry.md) |
| 자동 / 수동 재시도 카운터 분리 | DEAD_LETTER 부활 후 자동 재시도가 즉시 다시 DLQ 로 회귀하는 것 방지 | [async-and-retry §5.3](docs/async-and-retry.md) |
| 영속적 실패는 자동 재시도 생략 | 4xx / 잘못된 수신자는 백오프해도 결과 동일 | [async-and-retry §5.4](docs/async-and-retry.md) |
| `scheduled_at` 컬럼으로 예약 발송 통합 | 별도 스케줄러 인프라 불필요, 폴링 쿼리 한 줄로 대응 | [.claude/docs/design-guideline.md §9](.claude/docs/design-guideline.md) |
| 읽음 처리는 조건부 UPDATE | 다중 기기 동시 읽음에서도 첫 읽음 시각 보존 + 별도 락 불필요 | [.claude/docs/design-guideline.md §11](.claude/docs/design-guideline.md) |

---

## 미구현 / 제약 사항

| 항목 | 상태 | 사유 |
|---|---|---|
| 실제 이메일 발송 (SES / SendGrid) | Mock (로그 출력) | 과제 명시 |
| Kafka 등 브로커 연동 | dispatcher 추상만 | 과제 제약 |
| 운영 대시보드 UI | 없음 | API 까지가 과제 범위 |
| 사용자 채널 선호도 / opt-out | 없음 | 사용자 서비스 영역 |
| 다국어 템플릿 자원 | 단일 언어 | i18n 인프라 별도 |
| Flyway / Liquibase | JPA `ddl-auto = update` | 단일 PR 단위 개발 단계 |
| Webhook / bounce 수신 | 없음 | 외부 게이트웨이 의존, Mock 환경에서 무의미 |

각 항목의 보완 방향은 [docs/interpretation.md §5](docs/interpretation.md) 에 정리.

---

## AI 활용 범위

이 프로젝트는 Claude Code (Anthropic) 와 페어로 진행했다.

| 영역 | 활용 방식 |
|---|---|
| 요구사항 해석 / 트레이드오프 정리 | Claude 와 대화하면서 후보안과 근거를 표 형태로 정리. 채택은 본인이 결정. |
| 설계 문서 초안 | `.claude/docs/design-guideline.md`, `docs/async-and-retry.md`, `docs/interpretation.md` 의 골격을 Claude 가 작성, 본인이 검토·수정. |
| PR 단위 분할 | roadmap.md 의 PR 분할안을 Claude 가 제안, 의존 관계와 PR 의도를 본인이 검증. |
| 코드 작성 | 엔티티 / 서비스 / 워커 / 테스트 작성에 보조로 사용. 모든 변경은 본인이 리뷰 후 머지. |
| 커밋 / PR 메시지 | 컨벤션을 코딩하고 Claude 에게 적용 시킴. 단, "왜" 의 본문은 본인이 직접 작성. |
| 검토 자동화 | `.claude/commands/review-pr.md` — 머지 전 셀프 리뷰용 슬래시 커맨드 |

설계 결정과 코드 책임은 본인에게 있다. AI 는 정리·초안·검토의 가속 도구로 사용했다.

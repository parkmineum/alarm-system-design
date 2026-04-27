# 알림 발송 시스템

이벤트 기반 비동기 알림 시스템. 수강 신청 완료, 결제 확정 등 비즈니스 이벤트가 발생하면 이메일 또는 인앱 알림을 신뢰성 있게 발송한다.

---

## 기술 스택

| 항목 | 선택 |
|---|---|
| 언어 | Kotlin 1.9.25 / Java 17 |
| 프레임워크 | Spring Boot 3.5.14 |
| 영속성 | Spring Data JPA (Hibernate) |
| DB (운영) | MySQL 8.0 |
| DB (테스트) | H2 (in-memory) |
| 빌드 | Gradle Kotlin DSL |
| 문서 | SpringDoc OpenAPI 3 |

---

## 실행 방법

### Docker (권장)

```bash
docker compose up -d
```

앱: `http://localhost:28080`
Swagger UI: `http://localhost:28080/swagger-ui.html`

### 로컬 (MySQL 별도 구동 필요)

```bash
# MySQL 컨테이너만 먼저 실행
docker compose up -d mysql

# 앱 실행
./gradlew bootRun
```

---

## API 목록

### 알림 등록

```
POST /api/v1/notifications
Content-Type: application/json

{
  "recipientId": 42,
  "type": "COURSE_ENROLLMENT_COMPLETED",
  "channel": "EMAIL",
  "refType": "COURSE",
  "refId": "c-100",
  "payload": "{\"courseName\":\"Kotlin in Action\"}",
  "scheduledAt": "2026-05-01T09:00:00Z"
}
```

응답: `201 Created`, `Location: /api/v1/notifications/{id}`

`scheduledAt`을 생략하면 즉시 발송 대상이 된다. 동일 이벤트를 중복 등록해도 row 1건만 생성된다. payload가 다르면 `409 IDEMPOTENCY_CONFLICT`.

### 알림 단건 조회

```
GET /api/v1/notifications/{id}
X-User-Id: 42
```

본인 알림만 조회 가능. 타인 알림 접근 시 `404 NOTIFICATION_NOT_FOUND`.

### 알림 읽음 처리

```
PATCH /api/v1/notifications/{id}/read
X-User-Id: 42
```

최초 읽은 시각만 기록. 이미 읽은 알림을 재호출해도 `readAt`이 변경되지 않는다.

### 수신함 목록 조회

```
GET /api/v1/users/{userId}/notifications?read=false
```

`read` 파라미터: `true`=읽음만, `false`=미읽음만, 생략=전체. 최신순 정렬.

### DLQ 목록 조회 (관리자)

```
GET /api/v1/admin/notifications/dead-letters?page=0&size=50
```

`DEAD_LETTER` 상태 알림 목록. 기본 50건, 최대 200건.

### 수동 재시도 (관리자)

```
POST /api/v1/admin/notifications/{id}/retry
X-Actor-Id: ops-team-user
```

`DEAD_LETTER` 상태 알림을 `PENDING`으로 복귀. 수동 재시도 한도(3회) 초과 시 `422 MANUAL_RETRY_LIMIT_EXCEEDED`.

---

## 데이터 모델

### notification 테이블

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT PK | 자동 증가 |
| `recipient_id` | BIGINT | 수신자 사용자 ID |
| `type` | VARCHAR(50) | 알림 타입 (예: `COURSE_ENROLLMENT_COMPLETED`) |
| `channel` | VARCHAR(16) | `EMAIL` / `IN_APP` |
| `ref_type` | VARCHAR(50) | 참조 도메인 타입 (예: `COURSE`) |
| `ref_id` | VARCHAR(64) | 참조 도메인 ID |
| `payload` | TEXT | 알림 본문 JSON (선택) |
| `idempotency_key` | VARCHAR(64) UNIQUE | SHA-256(type\|refType\|refId\|recipientId\|channel[\|scheduledAt]) |
| `scheduled_at` | DATETIME | 발송 예약 시각 (기본값: 등록 시각) |
| `status` | VARCHAR(16) | `PENDING` / `PROCESSING` / `SENT` / `FAILED` / `DEAD_LETTER` |
| `auto_attempt_count` | INT | 자동 발송 시도 횟수 |
| `next_retry_at` | DATETIME | 다음 자동 재시도 예정 시각 |
| `last_error` | VARCHAR(500) | 마지막 실패 사유 |
| `manual_retry_count` | INT | 수동 재시도 횟수 |
| `last_manual_retry_at` | DATETIME | 마지막 수동 재시도 시각 |
| `last_manual_retry_actor_id` | VARCHAR(64) | 마지막 수동 재시도 담당자 ID |
| `read_at` | DATETIME | 읽음 처리 시각 |
| `processed_at` | DATETIME | 발송 완료 또는 최종 실패 시각 |
| `created_at` | DATETIME | 등록 시각 |
| `updated_at` | DATETIME | 마지막 상태 변경 시각 |

**인덱스**

- `(status, scheduled_at)` — PENDING 폴링 쿼리 가속
- `(status, next_retry_at)` — FAILED 재시도 폴링 가속
- `(recipient_id, read_at)` — 수신함 조회 가속

---

## 테스트 실행

```bash
# 전체 테스트
./gradlew test

# 린트
./gradlew ktlintCheck
```

테스트는 H2 in-memory DB를 사용하므로 MySQL 없이 실행된다.

슬라이스 구성:
- `@DataJpaTest` — 리포지토리 쿼리·제약 검증
- `@WebMvcTest` — 컨트롤러 요청/응답·에러 핸들러 검증
- `@SpringBootTest` — 워커·재시도·동시성 등 통합 시나리오 검증

---

## 요구사항 해석 및 가정

요구사항 해석의 상세 내용은 [docs/interpretation.md](docs/interpretation.md)를 참조한다.

1. **인증/인가 간략 처리**: `X-User-Id` / `X-Actor-Id` 헤더로 신원을 전달한다. 토큰 검증은 구현하지 않으며, 잘못된 헤더 값은 `404`로 자연스럽게 거부된다.
2. **실제 발송 불필요**: `EmailSender`, `InAppSender` 모두 로그 출력으로 대체. `NotificationSender` 인터페이스를 구현하는 것으로 실제 외부 서비스 연결이 가능하다.
3. **멱등성 키 자동 생성**: 호출자가 키를 직접 전달하지 않고, 서버가 `(type, refType, refId, recipientId, channel, scheduledAt?)` 조합으로 SHA-256을 계산한다. 같은 이벤트를 여러 번 등록해도 DB에 row 1건만 생성된다.
4. **스케줄링 기준 시각**: `scheduledAt`을 생략하면 등록 시각이 기본값이 되어 즉시 폴링 대상이 된다.

---

## 설계 결정과 이유

비동기 구조·재시도 정책의 상세 설명은 [docs/async-and-retry.md](docs/async-and-retry.md)를 참조한다.

**Transactional Outbox 패턴**: 알림 등록 API는 DB INSERT만 하고 즉시 응답한다. 워커가 별도 스레드에서 폴링하여 발송한다. 메시지 브로커 없이도 API 트랜잭션 실패 시 알림이 유실되지 않는다.

**SELECT FOR UPDATE**: 워커가 PENDING/FAILED 알림을 가져올 때 비관적 락을 사용한다. 다중 인스턴스 환경에서 동일 알림이 두 워커에게 할당되는 것을 DB 수준에서 차단한다.

**카운터 분리**: `auto_attempt_count`와 `manual_retry_count`를 별도 컬럼으로 관리한다. 자동 재시도 소진 여부와 수동 개입 이력을 독립적으로 추적할 수 있다.

---

## 미구현 / 제약사항

- 알림 템플릿 관리 (타입별 메시지 템플릿) — 미구현
- 실제 이메일/푸시 발송 — Mock(로그 출력)으로 대체
- 인증 토큰 검증 — 헤더 값 신뢰
- 수신함 목록 페이지네이션 — 현재 전체 반환 (DLQ 목록만 Pageable 적용)

---

## AI 활용 범위

본 프로젝트는 Claude Code(claude-sonnet-4-6)를 적극 활용하여 개발했다.

**AI가 수행한 작업:**
- 전체 도메인 모델·API·서비스·리포지토리 레이어 설계 및 구현
- 슬라이스 테스트(`@DataJpaTest`, `@WebMvcTest`) 및 통합 테스트(`@SpringBootTest`) 작성
- 코드 리뷰: 동시성 락 누락, 무제한 DLQ 조회, 응답 DTO 불일치 등 발견 및 수정
- PR별 충돌 해결, ktlint 포맷 수정

**사람이 수행한 작업:**
- 요구사항 검토 및 구현 우선순위 결정
- 각 PR 코드 리뷰 및 머지 승인
- 설계 방향 최종 결정 (재시도 정책, 카운터 분리 방식 등)

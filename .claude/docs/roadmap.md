# 단계별 PR 계획

기능 → 비동기 전환 → 견고성 → 분산 안전 → 가산점 항목 순으로 쌓는다.
PR 단위는 **하나의 의도**를 가진다 — 한 PR 의 제목으로 변경 의도를 한 줄에 설명할 수 있어야 한다.

## 원칙

- 각 PR 은 그 자체로 머지 가능해야 한다 (테스트 통과 + 동작 가능).
- 의존 PR 이 머지되기 전 후속 PR 을 시작하지 않는다.
- 한 PR 에 두 개 이상의 의도를 섞지 않는다 (예: "재시도 + 분산 락" 은 분리).

---

## 필수 요구사항 트랙

### PR 1 — 기능: 등록/조회/목록 API + 동기 발송 + 멱등성

> "알림이 등록되고 발송되고 조회된다" 는 사용자 가치 완결.

- `Notification` 엔티티 + enum + `idempotency_key` UNIQUE
- `POST /notifications` / `GET /notifications/{id}` / `GET /me/notifications?read=...`
- `NotificationSender` 인터페이스 + `EmailSender` / `InAppSender` (로그 Mock)
- 등록 트랜잭션 안에서 sender 호출 → 즉시 `SENT`
- 다음 PR 의 swap 지점인 `NotificationDispatcher` 추상은 미리 깔아둔다
- 슬라이스 테스트 (`@DataJpaTest`, `@WebMvcTest`) + 멱등성 시나리오

### PR 2 — 비동기 전환: Outbox + 폴링 워커

> 동기 호출 한 줄을 dispatcher 로 교체. 추상의 가치를 디프로 증명.

- `OutboxDispatcher` 도입 — API 는 `PENDING` INSERT 후 즉시 응답
- `@Scheduled` 워커가 `PENDING` 픽업 → sender 호출 → `SENT`
- 워커 별도 트랜잭션
- `(status, scheduled_at)` 인덱스 + `scheduled_at <= NOW()` 폴링 쿼리

### PR 3 — 견고성: 재시도 + DEAD_LETTER + 실패 사유

- `auto_attempt_count`, `next_retry_at`, `last_error`
- Exponential backoff (1m → 5m → 30m → 2h → 6h)
- `max_auto_attempts` 도달 시 `DEAD_LETTER` 전이
- 실패 → 재시도 성공 / 최대 도달 시 DLQ 시나리오 테스트

### PR 4 — 분산 안전 + 좀비 복구

- `SELECT ... FOR UPDATE SKIP LOCKED` (MySQL 8.0.1+)
- 좀비 복구 잡 (`PROCESSING` N분 초과 → `PENDING` 복원)
- 워커 N개 동시 기동 → 중복 발송 0건 시나리오 테스트

→ **여기까지가 채점 기준 필수 요구사항 전부.**

---

## 가산점 트랙 (독립적, 어떤 순서로도 가능)

### PR 5 — 읽음 처리 (다중 기기)

- `PATCH /notifications/{id}/read`
- 조건부 UPDATE (`WHERE read_at IS NULL`) — 멱등 + 첫 시각 보존
- 다중 디바이스 동시 요청 시나리오

### PR 6 — DEAD_LETTER 수동 재시도

- `POST /admin/notifications/{id}/retry`
- `manual_retry_count` 분리 (자동/수동 카운터 독립)
- 한도 가드 + `actor_id` 감사 로그
- DLQ 목록 조회 API

### PR 7 — 알림 템플릿

- `NotificationTemplate` 엔티티 (`type`, `channel`, `version`, `active`)
- 렌더러 Strategy
- 워커가 발송 직전 렌더링 → `rendered_body` 영속화
- 템플릿 등록/조회 API

### PR 8 — 발송 스케줄링 API 노출

- `POST /notifications` 에 `scheduledAt` 입력 허용
- 워커 폴링 쿼리는 PR 2 부터 `scheduled_at <= NOW()` 로 깔려 있어 신규 인프라 없음
- 미래 시각 검증

---

## 마무리 트랙

### PR 9 — 제출 직전 문서 정리

- `README.md` — 과제 README 필수 항목 10개
- `docs/async-and-retry.md` — 비동기 구조 + 재시도 정책
- `docs/interpretation.md` — 요구사항 해석 및 개선 의견
- AI 활용 범위 명시

---

## 5일 페이스 (참고)

| 일자 | 진행 |
|---|---|
| D1 ~ D2 | PR 1, PR 2 |
| D3 | PR 3, PR 4 |
| D4 | PR 5 ~ PR 8 중 선택 |
| D5 | PR 9 + 마무리 |

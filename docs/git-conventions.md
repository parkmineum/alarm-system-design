# Git 컨벤션

## 브랜치

```
<type>/<scope>-<short-description>
```

| 타입 | 사용 |
|---|---|
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `chore` | 빌드/도구/CI/의존성 |
| `docs` | 문서 |

`<scope>` 는 도메인 영역 (예: `notification`, `worker`, `idempotency`).

예시: `feat/notification-idempotency-key`, `refactor/worker-polling`

---

## 커밋

### 형식

```
<type>(<scope>): <한줄 요약>

<본문 — 의도 / 결정 근거 / 영향 범위>
```

### 타입

| 타입 | 사용 |
|---|---|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `test` | 테스트 추가/수정 |
| `docs` | 문서 |
| `chore` | 빌드/도구/CI/의존성 |

### 원칙

- 한 커밋 = 한 의미. 두 가지 변경은 두 커밋으로 분리한다.
- 리팩토링은 별도 커밋. `feat` 안에 정리 작업을 섞지 않는다.
- 본문에는 "왜" 를 적는다. "무엇" 은 diff 가 보여준다.
- 제목은 명령형, 마침표 없이 작성한다.

### 예시

```
feat(notification): add idempotency key with DB unique constraint

같은 이벤트가 재발행되어도 row 1건만 보장하기 위해 SHA-256 기반
키와 UNIQUE 제약을 도입한다. duplicate key 예외는 무시하고 정상
응답으로 처리해 발행자 관점에서 멱등하게 동작하도록 한다.
```

---

## PR

### 제목

커밋과 동일한 형식.

```
<type>(<scope>): <description>
```

예시: `feat(notification): add idempotency key with DB unique constraint`

### 본문 템플릿

```markdown
## Summary

<1~3 문장. 변경의 결과와 의도>

### 문제

<배경, 재현 조건, 영향 범위>

### 수정

- <변경 1>
- <변경 2>
- <제거된 항목 / 마이그레이션 영향>

### 머지 전 수동 작업

- <환경변수 추가, 마이그레이션 실행, 외부 설정 등 — 없으면 섹션 생략>

## Test plan

- [ ] <검증 1>
- [ ] <검증 2>
```

### 원칙

- Summary 는 변경된 파일 나열이 아니라 **시스템·사용자 관점의 변화**를 적는다.
- 문제 섹션은 누가 봐도 재현 가능한 수준의 정보를 담는다.
- 머지 전 수동 작업이 있으면 반드시 명시한다 (운영 환경변수 추가, 데이터 마이그레이션 등).
- Test plan 의 모든 체크박스는 머지 시점에 ✅ 되어 있어야 한다.
- 자동 검증이 어려운 항목은 수동 검증 절차로 명시한다.

### 예시 (참고: depromeet/ssolv-server#187)

```markdown
## Summary

같은 알림 이벤트가 중복 발행돼도 발송이 한 번만 일어나도록
멱등성 키와 DB UNIQUE 제약을 도입한다.

### 문제

기존 구현은 동일 이벤트가 두 번 발행되면 row 두 건이 생성되어
사용자에게 알림이 2회 전송됐다. 결제·만료 이벤트가 외부 워커에서
재발행되는 구조 특성상 멱등성이 필수다.

### 수정

- `idempotency_key` 컬럼과 UNIQUE 제약 추가 (Flyway V2)
- `NotificationService.create()` 에서 키 생성 로직 추가
  (`hash(eventType + refId + recipientId + channel)`)
- duplicate key 예외는 무시하고 기존 row 를 조회해 응답
- 멱등성 검증 통합 테스트 추가 (`NotificationIdempotencyTest`)

## Test plan

- [x] CI 통과 (ktlint / test)
- [x] 같은 키로 100회 요청 → row 1건 확인
- [x] 다른 채널/수신자는 별도 row 생성 확인
```

# 알림 발송 시스템

이벤트 기반 비동기 알림 시스템. 채널: `EMAIL`, `IN_APP`.

## 참조 문서

작업 전 **반드시** 다음 두 문서를 먼저 읽는다:

- 과제 요구사항: [.claude/docs/assignment.md](.claude/docs/assignment.md)
- 설계 결정: [.claude/docs/design-guideline.md](.claude/docs/design-guideline.md)

그 외:

- 단계별 PR 계획: [.claude/docs/roadmap.md](.claude/docs/roadmap.md) — 작업 단위·순서
- Git 컨벤션: [.claude/docs/git-conventions.md](.claude/docs/git-conventions.md) — 브랜치·커밋·PR 규칙 **(PR 작성 전 반드시 확인 — 기존 머지된 PR 스타일을 먼저 보고 맞출 것)**

## 기술 스택

- Kotlin 1.9.25 / Java 17
- Spring Boot 3.5.14, Spring Data JPA, Spring Web
- MySQL 8.0 (운영) / H2 (테스트)
- Gradle Kotlin DSL

## 실행

```bash
./gradlew bootRun        # 로컬 실행
./gradlew test           # 테스트
./gradlew ktlintCheck    # 린트
docker compose up -d     # MySQL + 앱
```

## 코드 컨벤션

- 패키지: `notification.practice.<domain>`
- 레이어: `controller` → `service` → `repository`
- 엔티티: JPA, `allOpen` 적용
- 도메인 예외는 `RuntimeException` 상속 + `@RestControllerAdvice` 매핑
- 에러 응답 형식: `{ code, message, details? }`
- 시간: `Instant` 보관, 표시 시점에만 KST 변환
- 테스트: 슬라이스 테스트 우선 (`@DataJpaTest`, `@WebMvcTest`)

## 기술적 주의사항

- MySQL 8 + JPA 의 `SELECT ... FOR UPDATE SKIP LOCKED` 는 8.0.1+ 에서 동작
- `@Async` 만으로는 다중 인스턴스 중복 처리 방지 불가 — DB 락 필수
- `@Transactional` 안의 비동기 호출은 트랜잭션 전파되지 않음 — 워커에서 별도 트랜잭션

## 훅

- pre-commit: 컴파일 검증
- pre-push: `./gradlew ktlintCheck test`

설치: `./scripts/install-hooks.sh`

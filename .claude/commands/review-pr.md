---
description: PR 열기 직전 현재 브랜치 디프를 ce-correctness / ce-adversarial / ce-testing 리뷰어로 병렬 검토 후 통합 보고
argument-hint: "[선택: 비교 베이스 브랜치, 기본 main]"
---

PR 열기 직전 마지막 자체 검토용. 현재 브랜치 디프를 세 리뷰어에게 **병렬로** 던진 뒤 결과를 통합해 보고한다.

## 입력 수집

비교 베이스: $ARGUMENTS (지정 없으면 `main`).

먼저 한 번에 다음을 수집한다 (병렬 Bash):
- `git fetch origin <base>` 후 `git log --oneline <base>..HEAD` — 포함될 커밋 목록
- `git diff <base>...HEAD --stat` — 변경 규모
- `git diff <base>...HEAD` — 전체 디프 (리뷰어에게 컨텍스트로 전달용)
- `git branch --show-current` — 현재 브랜치
- `gh pr view --json title,body,url 2>/dev/null` — 이미 열린 PR 이 있으면 본문 (없어도 OK)

수집한 정보를 기준으로 아래 세 에이전트를 **단일 메시지에 병렬 호출** 한다 (Agent 툴 다중 tool_use).

## 병렬 리뷰어 호출

### 1. `compound-engineering:ce-correctness-reviewer`

프롬프트 골격:
> 현재 브랜치 `<branch>` 의 `<base>...HEAD` 디프를 리뷰한다. 목적: 로직 오류, 엣지 케이스, 상태 관리 버그, 에러 전파 실패, 의도-구현 불일치를 찾는다.
>
> 변경 요약: `<git log oneline 결과>`
> 변경 파일: `<diff --stat 결과>`
> 전체 디프:
> ```diff
> <전체 diff>
> ```
>
> 프로젝트 규약: `CLAUDE.md`, `.claude/docs/assignment.md`, `.claude/docs/design-guideline.md` 를 필요시 참조.
>
> **출력 형식**: 각 발견 항목을 `[심각도: HIGH/MEDIUM/LOW] 파일:줄 — 한 줄 설명 — 권장 수정` 형식으로. 발견 없으면 명시적으로 "이슈 없음" 이라 적는다.

### 2. `compound-engineering:ce-adversarial-reviewer`

같은 디프·컨텍스트를 전달하되 프롬프트 추가:
> 알려진 안티패턴 체크리스트를 돌리지 말고, 이 구현을 **깨뜨리는 시나리오를 능동적으로 구성**한다. 동시성, 부분 실패, 잘못된 입력, 상태 회귀, 우회 경로 등. 시나리오마다 "어떤 입력/타이밍에서, 어떤 코드 경로를 거쳐, 무슨 실패가 나는지" 를 구체적으로 적는다.
>
> 출력 형식 동일.

### 3. `compound-engineering:ce-testing-reviewer`

같은 디프·컨텍스트:
> 테스트 커버리지 갭, 약한 단언, 구현에 결합된 부서지기 쉬운 테스트, 누락된 엣지 케이스를 찾는다. 추가하면 좋을 테스트는 "어떤 시나리오를 어떤 슬라이스(@DataJpaTest / @WebMvcTest / @SpringBootTest) 로" 표시한다.
>
> 출력 형식 동일.

## 통합 보고

세 결과를 받은 뒤 다음 형식으로 한 번에 출력한다:

```
# 자체 리뷰 결과 — <branch> (vs <base>)

## 요약
- 변경 규모: <files changed, +X/-Y>
- 커밋 수: <N>
- HIGH: <개수> | MEDIUM: <개수> | LOW: <개수>

## HIGH (머지 전 반드시 처리)
<리뷰어 출처 명시 — [correctness] / [adversarial] / [testing] 태그>
- ...

## MEDIUM (머지 전 권장)
- ...

## LOW (개선 여지)
- ...

## 다음 액션
- [ ] HIGH 항목 X개 처리
- [ ] (선택) MEDIUM 중 어떤 것 처리할지 결정
```

## 원칙

- 세 리뷰어 호출은 **반드시 단일 메시지 다중 tool_use** 로 병렬 실행. 순차 호출 금지.
- 통합 보고에서 같은 이슈를 두 리뷰어가 잡으면 한 항목으로 합치되 출처를 둘 다 표시.
- 통합 단계에서 **새 이슈를 추가하지 않는다** — 리뷰어 결과만 정리한다. 추가 의견이 있다면 별도 "## 추가 메모" 섹션에 분리.
- AI slop 톤 (모호한 추상어, 사용자 가치 운운) 의 코드 주석/문서가 보이면 적극적으로 지적한다.

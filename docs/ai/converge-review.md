# Converge review — 도구 중립 실행 규칙

Phase converge는 특정 AI 제품이 아니라 **독립 검토 역할과 동일한 체크리스트**를 요구한다.
설계 대조의 정본은 이 저장소의 `docs/design/`다. 별도 wiki나 외부 저장소와의 동기화는 검토 조건이 아니다.

## 순서

1. 구현 세션이 관련 테스트, `./gradlew ktlintCheck`, OpenAPI 생성물, `git diff --check`를 통과시킨다.
2. 구현 세션과 분리된 읽기 전용 세션이 `.claude/agents/design-sync.md`를 전부 읽고 design-sync를 수행한다.
3. 구현 세션이 지적을 반영하고 검증을 다시 수행한다.
4. 또 다른 읽기 전용 리뷰 turn 또는 독립 세션이 `.claude/agents/code-reviewer.md`를 전부 읽고 코드 리뷰를 수행한다.
5. Critical이 없을 때 도구·모델, 실제 diff 범위, 결과, 검토 commit을 `PROGRESS.md`에 기록한다.

design-sync와 code-reviewer는 병렬 실행하지 않는다. 리뷰 세션은 파일을 수정하지 않고 발견만 보고하며, 구현 세션이 스스로
최종 `code-reviewer` 판정을 내릴 수 없다.

## 실행기

- **Claude Code**: `./scripts/converge-review.sh design-sync <base>` 후 같은 방식으로 `code-reviewer`를 실행한다.
- **Codex**: 구현 세션과 분리된 reviewer agent/session에 해당 `.claude/agents/*.md` 체크리스트와 diff 범위를 전달한다.
- **사람 리뷰어**: 같은 체크리스트와 보고 형식을 사용하면 동등하게 인정한다.

한 실행기의 사용량·인증·서비스 장애는 다른 실행기로 교체한다. 교체 때문에 체크리스트, 실제 diff 확인, 읽기 전용 원칙 또는
Critical 0 완료 기준을 낮추지 않는다.

## 모델 선택

- 설계 정본·생성물 신선도·정형 계약 대조가 중심인 design-sync는 경량 모델과 낮은 reasoning effort를 우선한다.
- 자금 이동·정족수·동시성·트랜잭션·보안 경계를 판단하는 code-reviewer는 당시 사용 가능한 강한 추론 모델을 사용한다.
- 제품별 모델 이름은 바뀌므로 저장소 규칙에 고정하지 않고, 실행 결과에 실제 도구·모델·effort를 기록한다.
- 모델을 낮춰도 Critical 판정 기준과 필수 체크 항목은 줄이지 않는다. 불확실하면 더 강한 모델이나 사람 리뷰로 승격한다.

# AGENTS.md — 비(非) Claude Code 도구용 진입점

> Codex 등 다른 AI 코딩 도구가 이 저장소에서 작업할 때 읽는 파일.
> **정본은 [CLAUDE.md](CLAUDE.md) — 그대로, 전부 따른다.** 이 파일은 포인터일 뿐 규칙을 재정의하지 않는다.

## 반드시 읽을 순서

1. [CLAUDE.md](CLAUDE.md) — 금지 사항(0절)·확정 결정(3절)·아키텍처(4절)·작업 규율(6절)
2. [PLAN.md](PLAN.md) 현재 Phase + [PROGRESS.md](PROGRESS.md) — 세션 시작 리추얼
3. `.claude/rules/` — interaction·testing·error-handling 규칙 (Claude Code 전용 아님 — 내용은 도구 무관)

## Claude Code 밖에서는 자동 강제가 없다 — 수동 준수 필수

이 저장소의 일부 규칙은 Claude Code hook 으로 이중화돼 있어 다른 도구에서는 **걸리지 않는다**.
장치가 없다고 규칙이 없는 게 아니다:

- **docs/design/ 수정 절대 금지** — read-only 설계 사본 (byte-동일 유지). 설계 변경은 `../waas-wiki` 에서.
- **ktlint** — 커밋 전 `./gradlew ktlintCheck` 를 직접 돌린다.
- **docs/api 생성물** — `openapi.yaml` 수정 시 `python3 docs/api/build.py` 재생성. `api.md`·`api.html`·`spec.js` 직접 수정 금지.
- Phase converge(설계 정합 재검사·코드 리뷰)는 Claude Code 의 design-sync·code-reviewer agent 소관 —
  다른 도구로 구현했더라도 converge 는 Claude Code 세션에서 돌린다.

git pre-commit(gitleaks — `.githooks/`)은 도구 무관하게 걸린다. 우회 금지.

## 세션 종료 시

PROGRESS.md 를 갱신한다 (50줄 이내, 다음 세션의 AI 가 읽는 전제). 커밋 메시지는 한글 서술형 현행 유지.

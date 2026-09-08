# AGENTS.md — 비(非) Claude Code 도구용 진입점

> Codex 등 다른 AI 코딩 도구가 이 저장소에서 작업할 때 읽는 파일.
> **정본은 [CLAUDE.md](CLAUDE.md) — 그대로, 전부 따른다.** 이 파일은 포인터일 뿐 규칙을 재정의하지 않는다.

## 반드시 읽을 순서

1. [CLAUDE.md](CLAUDE.md) — 금지 사항(0절)·확정 결정(3절)·아키텍처(4절)·작업 규율(6절)
2. [PLAN.md](PLAN.md) 현재 Phase + [PROGRESS.md](PROGRESS.md) — 세션 시작 리추얼
3. `.claude/rules/` — interaction·testing·error-handling 규칙 (Claude Code 전용 아님 — 내용은 도구 무관)
4. `.claude/skills/` — 요청과 맞는 저장소 전용 절차. Admin 기능은 `admin-feature`, 정책·컨트랙트 변경은
   `admin-policy-change`를 Claude Code 밖에서도 직접 읽고 따른다.

## Claude Code 밖에서는 자동 강제가 없다 — 수동 준수 필수

이 저장소의 일부 규칙은 Claude Code hook 으로 이중화돼 있어 다른 도구에서는 **걸리지 않는다**.
장치가 없다고 규칙이 없는 게 아니다:

- **docs/design/가 설계 정본** — 사용자 요청·확정 결정에 따라 이 저장소에서 수정하고 코드·테스트·API 계약과 대조한다. 외부 저장소 동기화는 요구하지 않는다.
- **ktlint** — 커밋 전 `./gradlew ktlintCheck` 를 직접 돌린다.
- **docs/api 생성물** — `openapi.yaml` 수정 시 `python3 docs/api/build.py` 재생성. `api.md`·`api.html`·`spec.js` 직접 수정 금지.
- Phase converge는 **구현 세션과 분리된 읽기 전용 리뷰 세션**에서 design-sync→code-reviewer 순서로 수행한다.
  Claude Code는 `.claude/agents/`와 `scripts/converge-review.sh`를 사용하고, Codex는 같은 agent 문서를 읽은 별도
  reviewer agent/session으로 대체할 수 있다. 세부 기준은 `docs/ai/converge-review.md`다. 특정 벤더의 사용량 한도가
  converge를 막아서는 안 된다.

git pre-commit(gitleaks — `.githooks/`)은 도구 무관하게 걸린다. 우회 금지.

## 세션 종료 시

PROGRESS.md 를 갱신한다 (50줄 이내, 다음 세션의 AI 가 읽는 전제). 커밋 메시지는 한글 서술형 현행 유지.

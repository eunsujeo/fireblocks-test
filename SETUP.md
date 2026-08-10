# SETUP — 새 머신에서 작업 시작하기

> 저장소에 들어 있는 것(CLAUDE.md·PLAN·docs·`.claude/agents/`·`.mcp.json`)은 clone 만 하면 적용된다.
> 이 문서는 **저장소 밖이라 머신마다 다시 해야 하는 것**의 체크리스트다.

## 1. 저장소 배치 (경로 전제)

```
~/Workspace/
  blockchain-manager-svc/   ← 이 저장소
  waas-wiki/                ← 설계 정본 (선택 — 설계를 고치거나 사본을 동기화할 때만 필요)
  fbhook/                   ← 웹훅 PoC (선택 — Phase 3 이식 참고용. 없으면 97 문서로 대체 가능)
```

설계 문서 사본이 `docs/design/` 에 들어 있어 **waas-wiki 없이도 구현·리뷰·설계 대조가 된다.**
waas-wiki 가 필요한 경우는 둘뿐 — 설계 자체를 고칠 때, 사본을 최신으로 동기화할 때 ([docs/design/README.md](docs/design/README.md)).

## 2. Claude Code

- **MCP** — `.mcp.json` 이 저장소에 있어 자동 인식된다. 첫 실행 때 승인 프롬프트만 통과하면 끝 (fireblocks-docs 하나 — 원격이라 로컬 프로세스 없음. context7 은 2026-08-05 재검토로 제거, tooling.md 3절).
- **플러그인** (user 설정이라 머신마다):
  ```
  brew install JetBrains/utils/kotlin-lsp
  /plugin install kotlin-lsp@claude-plugins-official
  /plugin install commit-commands@claude-plugins-official
  ```
- **agent** — `.claude/agents/` 3종(code-reviewer·test-writer·design-sync)은 저장소에 포함, 설치 불필요.
- **세션 메모리 주의** — Claude 의 auto-memory 는 머신·프로젝트 경로별이라 따라오지 않는다. 그래서 **결정은 전부 문서가 정본**이다: 확정 결정 = CLAUDE.md 3절, 로드맵 = PLAN.md, 미해결 = PLAN.md 하단 표. 새 머신의 새 세션이 이상한 소리를 하면 CLAUDE.md 를 먼저 읽었는지 확인할 것.

## 3. 개발 환경

- **JDK — 사내망 밖에서는 설치 불필요.** Gradle toolchain(foojay resolver)이 Temurin 25 를 자동으로 받아온다.
  **★ 사내망 머신은 JDK 25 를 직접 설치할 것** — TLS 인터셉트 프록시 때문에 foojay 다운로드가
  `PKIX path building failed` 로 실패한다 (2026-08-05 실측). 로컬에 25 가 있으면 foojay 를 호출하지 않는다.
- **Gradle — 별도 설치 불필요.** wrapper(`./gradlew`)가 저장소에 있다.
- **의존성 저장소** — Gradle Plugin Portal과 Maven Central만 사용한다.
- **Docker** — Testcontainers(PostgreSQL·Kafka)가 요구.
- **강제 장치 도구** (hook 이 사용 — 없으면 각 hook 이 안내/차단한다):
  ```
  brew install gitleaks ktlint
  git config core.hooksPath .githooks   # 저장소 안에서 1회 — pre-commit 시크릿 스캔 활성화
  ```
- **IntelliJ 쓰는 경우** — Settings → Tools → MCP Server → Enable → Auto-Configure (Claude Code 연동). brave mode 는 켜지 않는다.

## 4. 시크릿 (절대 커밋 금지)

- 벤더 API key·DB 접속 정보는 `.env` 또는 환경변수 — `.gitignore` 로 차단돼 있는지 확인.
- 템플릿은 **`env.example`** (점 없이) — `.env*` 은 Claude 의 Read deny 패턴이라 점으로 시작하면 템플릿까지 못 읽는다.
- 새 머신에서 처음 받을 때: 시크릿 매니저(또는 담당자)에서 수령. 저장소 히스토리에는 없다.

## 5. 확인

- [ ] `docs/design/` 사본 존재 (waas-wiki 는 설계 수정·동기화 때만)
- [ ] Claude Code 에서 `/mcp` → fireblocks-docs 연결 확인
- [ ] `claude` 실행 후 CLAUDE.md 를 읽는지 확인 (첫 응답에서 확정 결정을 아는지)
- [ ] `./gradlew build` 그린 (첫 실행은 toolchain JDK 다운로드로 수 분)

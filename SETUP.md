# SETUP — 새 머신에서 작업 시작하기

> 저장소에 들어 있는 것(CLAUDE.md·PLAN·docs·`.claude/agents/`)은 clone 만 하면 적용된다.
> 이 문서는 **저장소 밖이라 머신마다 다시 해야 하는 것**의 체크리스트다.

## 1. 저장소 배치 (경로 전제)

```
~/Workspace/
  blockchain-manager-svc/   ← 이 저장소
  fbhook/                   ← 웹훅 PoC (선택 — Phase 3 이식 참고용. 없으면 97 문서로 대체 가능)
```

설계 정본은 이 저장소의 `docs/design/`다. **설계 수정·구현·리뷰에 별도 wiki 저장소가 필요하지 않다.**
설계도 같은 저장소에서 변경하고 검증한다 ([docs/design/README.md](docs/design/README.md)).

## 2. Claude Code

- 필수 MCP·플러그인 설치는 없다. Fireblocks Docs MCP·context7은 제거됐고 `kotlin-lsp`·`commit-commands`는 보류 상태다.
- IntelliJ 내장 MCP는 선택 사항이다. 현재 채택·관리 기준은 [도구 안내](docs/tooling.md)를 따른다.
- **agent** — `.claude/agents/` 3종(code-reviewer·test-writer·design-sync)은 저장소에 포함, 설치 불필요.
- **세션 메모리 주의** — Claude 의 auto-memory 는 머신·프로젝트 경로별이라 따라오지 않는다. 그래서 **결정은 전부 문서가 정본**이다: 확정 결정 = CLAUDE.md 3절, 로드맵 = PLAN.md, 미해결 = PLAN.md 하단 표. 새 머신의 새 세션이 이상한 소리를 하면 CLAUDE.md 를 먼저 읽었는지 확인할 것.

## 3. 개발 환경

- **JDK — 사내망 밖에서는 설치 불필요.** Gradle toolchain(foojay resolver)이 Temurin 25 를 자동으로 받아온다.
  **★ 사내망 머신은 JDK 25 를 직접 설치할 것** — TLS 인터셉트 프록시 때문에 foojay 다운로드가
  `PKIX path building failed` 로 실패한다 (2026-08-05 실측). 로컬에 25 가 있으면 foojay 를 호출하지 않는다.
- **Gradle — 별도 설치 불필요.** wrapper(`./gradlew`)가 저장소에 있다.
- **의존성 저장소** — Gradle Plugin Portal과 Maven Central만 사용한다.
- **Docker** — Testcontainers와 로컬 실행기의 PostgreSQL·Kafka가 요구. Docker Desktop(macOS·Windows) 또는
  Docker Engine+Compose v2(Linux)를 사용한다. Windows의 로컬 실행기는 Git Bash 또는 WSL2도 필요하다.
- **Foundry/Anvil 1.7.1** — Phase 11 로컬 EVM 계약 빌드·테스트가 요구한다. macOS·Linux와 Windows WSL2/Git Bash에서
  공식 설치기 설치 후 고정 버전을 선택한다. `./gradlew build` 첫 실행은 고정 `solc 0.8.35`도 받아 캐시한다.
  ```sh
  curl -L https://foundry.paradigm.xyz | bash
  foundryup -i v1.7.1
  anvil --version   # 1.7.1
  forge --version   # 1.7.1
  ```
  폐쇄망 서버는 이 설치기를 실행하지 않고 T11.6의 사전 검증된 Anvil 파일 패키지를 사용한다.
- **강제 장치 도구** (hook 이 사용 — 없으면 각 hook 이 안내/차단한다):
  ```
  brew install gitleaks ktlint
  git config core.hooksPath .githooks   # 저장소 안에서 1회 — pre-commit 시크릿 스캔 활성화
  ```
- **IntelliJ 쓰는 경우** — Settings → Tools → MCP Server → Enable → Auto-Configure (Claude Code 연동). brave mode 는 켜지 않는다.

## 4. 로컬 실행과 시크릿 (절대 커밋 금지)

- Docker를 실행한 뒤 `./scripts/local.sh up`(Windows: `.\scripts\local.ps1 up`)을 실행한다. 첫 실행 질문에
  개발자 자신의 Fireblocks 테스트 workspace API Key와 PKCS#8 Private Key 파일 경로를 입력하면 `.env`가 자동 생성된다.
- Base URL과 JWKS URL은 별도 값이 없으면 일반 API 기본값을 사용한다. Private Key는 `.env`에 복사하지 않고 파일 경로만
  저장하며, 파일 위치는 저장소 밖을 권장한다.
- 벤더 API key·DB 접속 정보는 `.env` 또는 환경변수 — `.gitignore` 로 차단돼 있는지 확인.
- 템플릿은 **`env.example`** (점 없이) — `.env*` 은 Claude 의 Read deny 패턴이라 점으로 시작하면 템플릿까지 못 읽는다.
- 새 머신에서 처음 받을 때: 시크릿 매니저(또는 담당자)에서 수령. 저장소 히스토리에는 없다.

## 5. 확인

- [ ] `docs/design/` 설계 정본 존재
- [ ] `claude` 실행 후 CLAUDE.md 를 읽는지 확인 (첫 응답에서 확정 결정을 아는지)
- [ ] `./scripts/local.sh up` 후 Admin `http://127.0.0.1:9080/admin/dashboard` 확인
- [ ] `anvil --version`·`forge --version` 모두 1.7.1
- [ ] `./gradlew build` 그린 (첫 실행은 toolchain JDK 다운로드로 수 분)

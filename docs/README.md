# 문서 안내

개발·연동·운영 중 하려는 일에서 시작하세요. 상세 계약과 실측 자료는 필요한 때 아래 링크로 찾아갑니다.

| 목적 | 먼저 볼 문서 | 찾을 수 있는 내용 |
|---|---|---|
| **시작** | [로컬 실행](../README.md#빌드--실행) · [새 머신 준비](../SETUP.md) | 실행 명령·접속 주소·설치 |
| **구조** | [모듈과 의존성](standards/architecture.md) | 코드 위치·헥사고날 경계·자동 검사 |
| **업무** | [업무별 설계 찾기](design/README.md) | 계정·입금·출금·Sweep·Admin·DB |
| **API** | [API 문서 열기](api/README.md) | 포털·요청 실행·OpenAPI·공유용 파일 |
| **운영** | [상황별 운영 절차](runbooks/README.md) | 모니터링·웹훅 복구·취약점·배포 준비 |
| **참고** | [테스트·근거·이력](#참고자료) | 검증 방법·벤더 실측·외부 시스템·과거 결정 |

## 참고자료

- 개발 검증: [테스트 전략](testing.md), [현재 도구와 관리 규칙](tooling.md).
- 벤더 동작 확인: [실측·PoC 목록](design/README.md#실측과-채택-근거).
- 실행 환경 호환 계획: [Fireblocks·Dfns·로컬 블록체인](dfns-compatibility-plan.md) — 공통 업무·보안·복구 검증, 멀티체인 후속 확장.
- 외부 서비스 경계: [컴플라이언스·Co-signer](design/README.md#외부-시스템-맥락).
- 외부 Wallet SDK: [개발 가이드](wallet-sdk/index.html) · [공유용 ZIP](wallet-sdk.zip). 2026-09-11 SDK v1.1.0 공개 문서를 정리한 참고자료이며, 압축 해제 후 `index.html`을 열면 됩니다.
- 설계자 원장 v0.1.4: [업무·DB 가이드](ledger/index.html) · [DAWBC / Wallet SDK 흐름 비교](flow-comparison/index.html). `index.html`을 직접 열어 처리 순서·잔고 변화·원문을 확인합니다.
- 화면 작업: [승인된 Admin 기준 화면](admin-reference/README.md).
- AI 작업: [요청 가이드](ai/prompt-guide.md), [독립 리뷰 절차](ai/converge-review.md).
- 진행 상황: [현재 계획·미해결 결정](../PLAN.md), [완료 Phase 이력](history/phase-0-14-plan.md),
  [해결된 설계 항목](history/resolved-design-items.md), [도구 선정 이력](history/tooling-research-2026-08.md).

## 문서 관리

- 설계 계약은 `design/`, HTTP 계약은 [openapi.yaml](api/openapi.yaml), 버전은 [version catalog](../gradle/libs.versions.toml)가 정본이다.
- 안내에는 대상 독자·범위와 정본 링크를 짧게 적고, 다른 문서의 정책·절차는 복사 대신 해당 절로 연결한다.
- `evidence/`는 구현 근거 원문, `context/`는 외부 서비스 맥락, `history/`는 과거 기록이다. 현행 계약과 구분해 읽는다.
- API의 `api.md`·`api.html`·`spec.js`는 생성물이다. 정본 수정 후 `python3 docs/api/build.py`로 갱신한다.
- 새 문서는 기존 문서에 담을 수 없는 독립 목적과 유지 책임이 있을 때만 추가한다.
- 문서를 이동하거나 제목을 바꾸면 상대 링크·절 앵커·코드·스크립트·AI 작업 규칙의 참조도 함께 확인한다.

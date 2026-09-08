# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~30 완료. Phase 15는 계속 보류.**
- 설계 정본은 이 저장소의 `docs/design/`다. 사용자 요청·확정 결정에 따라 직접 수정·리뷰한다.
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 작업 — 문서 정리 (2026-09-08, Codex)
- 사용자 승인에 따라 PLAN D1~D4 완료. 모듈 경계 보완과 문서 정리를 3개 커밋으로 분리했다. push는 하지 않았다.
- docs/README는 31줄·6개 목적별 입구, design/README는 업무별 읽기 순서, runbooks/README는 상황별 절차다.
- 로컬 실행·모듈 경계·입금 흐름·API 포털·웹훅 복구를 docs/README에서 각각 링크 2회 이내에 찾는 것을 검증했다.
- design/90·93~98을 evidence/로, 04·05·12를 context/로 이동했다. design 최상위 Markdown은 21→11개다.
- Admin 화면 안내의 정책 중복과 99의 반복 설명을 줄이고, 98의 기존 출시 조건 6개를 06에서 관리한다.
- 메트릭·경보는 runbooks/monitoring.md로 통합. DB·Admin은 피처별 절 바로가기를 추가했다.
- tooling 과거 검토는 history/tooling-research-2026-08.md로 보존. SETUP의 제거된 MCP·보류 플러그인 설치 안내를 정리했다.
- 01·CLAUDE의 중복 전체 목차, README의 낡은 Phase 문구, 현행 설계의 To Do 카드 상태를 제거했다.
- 문서·.claude 참조와 빌드 주석 2곳을 갱신했다. 이번 작업의 애플리케이션 코드·DB·HTTP 계약 변경은 없다.
- 내부 Markdown 링크·절 앵커 345개, HTML 리소스 링크 5개, 이전 이동 경로 잔존 0 확인.
- 이동 문서 10개의 코드 블록, 9개 참고 문서 본문(안내·상대 경로 제외), DB·자산·Admin 정의와 기존 출시 조건 보존 검증.
- OpenAPI 재생성 변경 0, 포털 테스트 11건·전체 ktlintCheck 통과. API 패키징 리소스 6개가 docs/api 원본과 동일하다.
- 커밋 전 ktlint·문서 링크·원문 보존 재검증 통과. 전체 애플리케이션 테스트·실벤더 호출·정식 Phase converge 재승인은 미수행.

## 직전 작업 — 모듈 경계 보완
- c9489c4: 경계·실행 조립 테스트 보강. 59db18d: 중복 제거·Webhook 실행 파일 이동·Jackson 의존 이전.
- API·Webhook·BAT 조립 명시화. Webhook 판단·allowance 회수·Sweep 배치의 피처 간 Repository 참조를 소유 서비스로 캡슐화.
- 기존 호출자 트랜잭션·잠금·예외 유지. 다른 유스케이스의 직접 참조는 후속 정리 대상이다.
- ArchUnit은 전체 실행 모듈·중복 FQCN·빈 검사·금지 의존·위 3개 유스케이스를 검증하며 Gradle bytecode 입력 추적도 등록했다.
- 관련 테스트 161건(API 81·Webhook 56·BAT 24), 경계 테스트 후속 12건, 전체 ktlintCheck, 운영 JAR 3종 기동 검증 통과.
- Codex 독립 agent design-sync→code-reviewer 순차 완료(e61696a 대비 당시 작업 트리). 지적 1건 반영·재확인 후 잔여 0.
- e61696a: 외부 wiki 의존 제거·설계 정본 전환, origin/main push 완료.
- 3d6c9ab·1650315: 입금 토큰 귀속·Sweep reorg·잔액 재처리 수정, origin/main push 완료.

## 다음 작업
- main에 테스트·구현·문서 커밋 완료. 사용자 요청 시 push한다.
- 다른 유스케이스 정리 시 피처 접근 검사 범위를 확장한다. 열린 설계 항목은 PLAN 표를 따른다.
- Phase 15는 운영 논의 재개 전까지 보류한다.
- 실제 Fireblocks mutation과 manual-fireblocks golden test는 계속 명시 승인 경계다.

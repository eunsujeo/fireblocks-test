# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~29 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 구현 — T14.29 GET API 오류 계약 정합
- 기존 런타임 검증은 유지하고 `depositAddressesOf`·`balancesOf` OpenAPI 응답에 `400 ValidationFailed`를 추가했다.
- OpenAPI v0.10.2와 `api.md`·`api.html`·`spec.js` 생성물을 동기화했다.
- 계정 경로변수 65자 검증의 실제 `400 VALIDATION_FAILED` envelope를 두 GET 모두 스펙 validator로 대조한다.
- PLAN #22는 해결 이력으로 이동했다. 프로덕션 Kotlin·DB·`docs/design/`은 변경하지 않았다.

## 검증 완료 (2026-09-01)
- 계약 테스트는 스펙 수정 전 추가 케이스만 실패(red)함을 확인했다.
- `AccountSpecComplianceTest` 7건과 API 문서 helper 11건이 통과했다.
- 전체 `./scripts/ci.sh`가 그린이다. Dependency-Check는 기존 PLAN #42 Kafka Medium 1건만 보고하고 게이트를 통과했다.

## 다음 작업
- 열린 설계 항목은 17건이며 PLAN 표를 기준으로 외부 조건이 추가로 확정된 항목부터 진행한다.
- Phase 15는 운영 논의 재개 전까지 보류한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.

# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~30 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 구현 — T14.30 잔액 미발급·벤더 drift 계약
- 계정은 있지만 조건에 맞는 발급 자산이 없으면 벤더 미호출 `200 data: []`로 확정했다.
- 발급 자산의 실제 0잔액은 자산 행과 문자열 `"0"`, 로컬 발급 기록과 벤더 wallet 불일치는 `500 INTERNAL`로 구분한다.
- `VendorApiException`은 시스템 오류로 ERROR 기록하며 OpenAPI v0.10.3과 생성 문서를 동기화했다.
- PLAN #23은 해결 이력으로 이동했다. DB·`docs/design/`·실 Fireblocks 호출은 변경하지 않았다.

## 검증 완료 (2026-09-01)
- 계약 테스트 42건 중 vendor ERROR 로그 단언만 실패(red)함을 확인했다.
- `AccountServiceTest`·`AccountSpecComplianceTest`·`ApiExceptionHandlerTest`와 API 문서 helper 11건이 통과했다.
- 전체 `./scripts/ci.sh`가 그린이다. Dependency-Check는 기존 PLAN #42 Kafka Medium 1건만 보고하고 게이트를 통과했다.

## 다음 작업
- 열린 설계 항목은 16건이며 PLAN 표를 기준으로 외부 조건이 추가로 확정된 항목부터 진행한다.
- Phase 15는 운영 논의 재개 전까지 보류한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.

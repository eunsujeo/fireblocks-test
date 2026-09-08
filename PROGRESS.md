# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~30 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 개선 (2026-09-08, Codex)
- HEAD `1a3cb5d` 리뷰에서 재현한 Critical 3건을 사용자 요청에 따라 수정했다. 테스트·구현을 별도 커밋으로 정리한다. 정식 Phase converge 아님.
- 입금 주소 조회에 symbol 조건을 추가해 동일 주소의 USDT·USDC 오귀속을 방지했다. 다른 토큰만 발급된 주소는 미귀속 처리한다.
- 완료·부분 완료 Sweep의 reorg FAILED를 공통 `SweepInvalidationService`에서 처리한다. 실행·항목 실패, 요청 재개, target 복구, 실패 outbox를 같은 트랜잭션에 반영한다.
- 중복 무효화는 멱등 처리하고 후속 실행이 보유한 PROCESSING 요청·target claim은 유지한다. 기존 성공 이벤트는 보존한다.
- 성공 후 잔액이 최소 수량 이상이거나 입금 스냅샷이 바뀌면 요청 항목을 PENDING으로 유지해 같은 요청으로 후속 Sweep을 선택할 수 있다.
- 성공 대사에서 tx 행 잠금으로 FAILED 역전을 차단하고 request → target 잠금 순서를 맞췄다. Sweep outbox publisher는 공통 application으로 이동했다.
- 회귀 테스트 3건의 red를 확인한 뒤 수정했다. 추가 5건으로 미귀속·롤백·동시 중복·후속 claim·오래된 성공 관측도 검증했다.
- domain 89·application 6·Webhook 34·BAT 48·persistence 34·API 18 = 관련 테스트 229건 통과. Stub·Anvil Sweep, 기동·아키텍처 검사 포함.
- 최종 BAT 48건 재검증·전체 `./gradlew --offline ktlintCheck`·`git diff --check` 통과. 전체 CI·실벤더 수용 검증은 실행하지 않았다.
- DB 스키마·API 계약·`docs/design/` 변경 및 실 Fireblocks 호출 없음.
- 설계 사본 20개 중 16개 byte 동일, 06·90·98·99는 waas-wiki 작업본과 다름. 정본에 미커밋 작업이 있어 동기화하지 않았다.

## 직전 구현 — T14.30 잔액 미발급·벤더 drift 계약
- 계정은 있지만 조건에 맞는 발급 자산이 없으면 벤더 미호출 `200 data: []`로 확정했다.
- 발급 자산의 실제 0잔액은 자산 행과 문자열 `"0"`, 로컬 발급 기록과 벤더 wallet 불일치는 `500 INTERNAL`로 구분한다.
- `VendorApiException`은 시스템 오류로 ERROR 기록하며 OpenAPI v0.10.3과 생성 문서를 동기화했다.
- PLAN #23은 해결 이력으로 이동했다. DB·`docs/design/`·실 Fireblocks 호출은 변경하지 않았다.

## 검증 완료 (2026-09-01)
- 계약 테스트 42건 중 vendor ERROR 로그 단언만 실패(red)함을 확인했다.
- `AccountServiceTest`·`AccountSpecComplianceTest`·`ApiExceptionHandlerTest`와 API 문서 helper 11건이 통과했다.
- 전체 `./scripts/ci.sh`가 그린이다. Dependency-Check는 기존 PLAN #42 Kafka Medium 1건만 보고하고 게이트를 통과했다.

## 다음 작업
- 이번 변경의 독립 리뷰 및 필요 시 전체 CI를 진행한다. 현행 완료 Phase 표시는 이번 작업으로 재승인하지 않았다.
- 열린 설계 항목은 16건이며 PLAN 표를 기준으로 외부 조건이 추가로 확정된 항목부터 진행한다.
- Phase 15는 운영 논의 재개 전까지 보류한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.

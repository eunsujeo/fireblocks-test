# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 완료 후 로컬/Admin 보강 중, Phase 15 Production 배포 준비는 사용자 결정으로 보류 (2026-08-24).**
- API/Webhook/BAT는 Admin·test-support·로컬 체인 없이 동작하며 production boundary가 런타임 의존성 0을 강제한다.
- OpenAPI 정본과 빌드 포함 실행 포털은 카테고리별 API·schema, 편집 가능한 요청과 포맷된 응답을 제공한다.

## 최근 완료
- Fireblocks 블록체인 카탈로그와 **모든 Network의 자산 카탈로그**를 읽기 전용으로 동기화한다. Network 채택과 자산 매핑
  등록은 자동화하지 않았고 기존 명시 행위로 유지한다.
- 정기 `VENDOR_ASSET_CATALOG_SYNC`와 `asset-catalog-sync-once`는 전체 Network를 처리한다. 로컬 `up fireblocks`와
  Stub/system test bootstrap은 `asset-catalog-supported-sync-once`로 지원 Network만 빠르게 준비해 시작 시간을 전체 목록에 묶지 않는다.
- V13은 `bcm_blkc_m.ast_sync_dttm VARCHAR(16)`을 추가한다. 자산이 0건인 성공 snapshot도 READY로 구분하고 실패 시 직전
  성공 시각과 snapshot을 보존한다.
- Admin `USDC` 검색은 지원·미지원 Network 후보를 함께 보여 주며 Fireblocks blockchain/asset ID, contract address,
  decimals를 비교한다. 서버가 `registrationAllowed`와 이유를 반환하고 미지원 후보는 UI에서 선택·등록할 수 없다.
- 후보 검색은 exact/prefix/FTS/address 순위를 유지하면서 같은 관련도에서는 지원 후보를 먼저 둔다. 최대 50건과 전체 Network
  source 상태를 반환하고 UI는 READY/STALE/NOT SYNCED 집계와 문제 원천 최대 10개를 표시한다.
- `./scripts/local.sh sync assets`는 현재 fireblocks/stub 데이터셋의 전체 읽기 전용 자산 캐시를 갱신한다.
- 설계 정본 03·07·08·10과 svc 사본은 전체 관찰·수동 등록 경계로 byte 동일하다.

## 이번 검증
- V13 PostgreSQL schema, 빈 snapshot READY, 미지원 후보 검색·지원 우선 순위·snapshot rollback persistence 테스트 통과.
- BAT 전체/지원 범위, 페이지 수집, 부분 실패 계속, blockchain mismatch, 두 one-shot 종료 테스트 통과.
- API service/controller/OpenAPI, Admin functional E2E·브라우저 25건, local/system runner·production boundary·distribution 테스트 통과.
- OpenAPI 생성물 paths 19/schemas 69과 Admin Kotlin 타입 재생성, `ktlintCheck`·`git diff --check`·`./scripts/ci.sh` 전체 green.
- 실 Fireblocks API 호출과 실제 전체 카탈로그 동기화는 이번 세션에서 실행하지 않았다(명시 승인 경계 유지).

## 다음 작업
- 로컬에서 `./scripts/local.sh restart fireblocks`로 V13을 적용한 뒤 `./scripts/local.sh sync assets`를 실행하고,
  Admin Assets에서 `USDC` 지원/미지원 후보·선택 차단·등록을 실제 workspace로 확인한다.

## 외부 조건·후속
- Phase 15 전제는 Linux+systemd, 초기 API/Webhook/BAT 1/1/1. Admin 배포 여부와 PostgreSQL·Kafka, ingress/TLS,
  Secret, 운영 일정은 미정이고 실제 배포는 기능 점검 완료 뒤 별도 승인한다.
- `manual-fireblocks` golden contract test는 별도 실행 승인 경계를 유지한다.
- 공유 Admin mutation 공개는 mTLS+5분 이하 JWT 인증/인가 구현 뒤에만 가능하다.
- 자산 cache 정기 작업의 다중 BAT 단일 실행 제어는 PLAN #30과 함께 배포 전 확정한다.

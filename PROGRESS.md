# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 완료 후 로컬/Admin 보강 중, Phase 15 Production 배포 준비는 사용자 결정으로 보류 (2026-08-24).**
- API/Webhook/BAT는 Admin·test-support·로컬 체인 없이 동작하며 production boundary가 런타임 의존성 0을 강제한다.
- OpenAPI 정본과 빌드 포함 실행 포털은 카테고리별 API·schema, 편집 가능한 요청과 포맷된 응답을 제공한다.

## 최근 완료
- Admin 자산 화면은 통합 검색·`Advanced` 필터와 Fireblocks형 등록 모달로 심볼·이름·contract address 후보를 비교한다.
- 자산 후보 빈 상태는 Network 미등록·catalog 미동기화·실제 결과 없음을 구분하고 Network 등록 또는 sync 다음 행동을 제공한다.
- 자산 목록 검색의 label 내부 보조 문구를 제거해 검색 input·action 버튼의 하단 정렬을 복원했다.
- V11은 자산 현재 매핑 활성 상태와 등록·논리 해제·재활성·교체의 전후 snapshot 원장을 추가했다.
- V12 `bcm_vndr_ast_ctlg_m`은 채택 네트워크별 Fireblocks 자산 snapshot을 보관한다. 성공 시 한 트랜잭션으로
  upsert하고 사라진 행은 `prst_yn=N`; 네트워크 하나가 실패하면 기존 snapshot을 보존하고 다른 네트워크는 계속한다.
- BAT `VENDOR_ASSET_CATALOG_SYNC`는 일 1회와 `asset-catalog-sync-once`가 같은 service를 사용하고 부분 실패를 경보한다.
- Admin 후보 API는 벤더 fan-out 대신 PostgreSQL symbol/name/address prefix+GIN FTS 인덱스를 사용해 최대 50건을 반환한다.
  응답은 네트워크별 `READY/STALE/NEVER_SYNCED`와 sync 시각을 포함하며 48시간 경과 시 `STALE`이다.
- 후보 캐시는 탐색 전용이다. 자산 등록 POST는 기존처럼 Fireblocks 전체 페이지에서 contract address를 다시 해소한다.
- 로컬 `./scripts/local.sh sync assets`는 현재 fireblocks/stub 데이터셋에서 자산 캐시 one-shot을 실행한다.
  `up fireblocks`는 사용자 결정대로 블록체인 목록 읽기를 통한 API 인증 확인만 자동 수행한다.
- `./scripts/local.sh restart`는 현재 active mode를 보존해 down→up을 한 명령으로 수행하며 잘못된 mode는 종료 전에 거부한다.
- fireblocks/stub은 한 번에 하나만 활성화되며 up 완료·status·logs가 모드 구성 상태와 분리 데이터셋을 표시한다.
- `up stub` 초기화와 smoke/full은 블록체인 sync→네트워크 채택→자산 cache sync→USDC/KRWK 매핑 순서를 사용한다.
- fireblocks/stub PostgreSQL·Kafka volume은 분리되고, Stub은 Ethereum/Base Anvil과 각 USDC·KRWK(6 decimals)를 쓴다.
- 설계 정본 03·07·08·10은 waas-wiki `26fd5a2`, svc 사본은 byte 동일하며 기존 UX checkpoint `e7da08d`가 push됐다.

## 이번 검증
- V12 PostgreSQL schema/index·검색 순위·snapshot rollback·stale/never source persistence 테스트 통과.
- BAT 전체 page·채택 네트워크 한정·부분 실패 계속·응답 blockchain mismatch·one-shot 종료 테스트 통과.
- API service/controller/spec, Admin functional E2E, 브라우저 상태 23건, local shell 안전 계약 통과.
- 자산 검색 정렬 회귀를 브라우저 정적 계약으로 고정했고 Admin frontend 23건이 통과했다.
- OpenAPI 생성물 paths 19/schemas 69 재생성; Python compile·ktlint·`git diff --check`·`./scripts/ci.sh` 전체 green.
- system smoke `20260824T005932Z-cfab3910` 10/10 PASSED: 블록체인 sync→네트워크 채택→자산 cache sync→매핑→
  입금/Webhook/Kafka/Admin 조사→잔존 리소스 정리를 실제 독립 프로세스로 통과했다.

## 다음 작업
- 실제 workspace는 `up fireblocks`→Admin 네트워크 채택→`sync assets`→자산 검색 순으로 검증한다.

## 외부 조건·후속
- Phase 15 전제는 Linux+systemd, 초기 API/Webhook/BAT 1/1/1. Admin 배포 여부와 PostgreSQL·Kafka, ingress/TLS,
  Secret, 운영 일정은 미정이고 실제 배포는 기능 점검 완료 뒤 별도 승인한다.
- `up fireblocks` 자동 확인은 읽기 전용 블록체인 API 인증뿐이다. 자산 cache sync와 `manual-fireblocks`는 명시 실행한다.
- 공유 Admin mutation 공개는 mTLS+5분 이하 JWT 인증/인가 구현 뒤에만 가능하다.
- 자산 cache 정기 작업의 다중 BAT 단일 실행 제어는 PLAN #30과 함께 배포 전 확정한다.

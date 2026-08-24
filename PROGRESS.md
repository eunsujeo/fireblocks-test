# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 완료 후 로컬 개발환경 보강 완료, Phase 15는 사용자 결정으로 보류 (2026-08-21).**
- API/Webhook/BAT는 Admin·test-support·로컬 체인 없이 동작하며 production boundary가 런타임 의존성 0을 강제한다.
- OpenAPI 정본과 빌드 포함 실행 포털은 카테고리별 API·schema, 편집 가능한 요청, 포맷된 JSON 본문과 접힌 헤더·HTTP 원문을 제공한다.
- Admin 로컬 시나리오는 FUNCTION_TEST+STUB+LOCAL+loopback에서만 실행되고 단계·실패 조치·관련 ID를 원장으로 남긴다.

## 이번 보강
- Admin 첫 화면을 Fireblocks 카탈로그 전체/BCM 채택/등록 가능 후보/활성 자산과 마지막 동기화 시각 중심의 빠른 시작 흐름으로 개편했다.
- 로컬 자산 관리가 켜진 `FUNCTION_TEST+loopback`에서는 Fireblocks 네트워크 후보를 BCM 코드로 채택하고 자산 후보를 검색·등록한다.
  두 mutation은 전용 헤더+loopback Host+동일 Origin+JSON을 강제하며 공유 환경과 논리 해제·교체는 계속 닫는다.
- vault·입금 주소 생성은 Admin mutation으로 만들지 않고 DAW-CORE가 사용하는 실행 가능한 공개 API 문서로 연결한다.
- Admin 자산 화면과 전역 검색은 `USDC` 같은 심볼 하나를 Fireblocks 네트워크별 후보 목록으로 이어 선택·등록하게 한다.
- 등록 후보는 Name·Network·Contract address·Decimals와 기존 등록 여부를 보여 주며 `Advanced` 등 기술 라벨은 영어, 설명·action은 한국어로 유지한다.
- V11은 자산 현재 매핑에 `actv_yn`을 추가하고 등록·논리 해제·재활성·교체의 전후 snapshot을 `bcm_vndr_ast_chng_l`에 추가 전용으로 남긴다.
- `local.sh up fireblocks`와 `up stub`은 같은 PostgreSQL·Kafka volume을 공유하지 않는다. 상태와 Admin 상단에 vendor·chain·dataset을 표시한다.
- 분리 전 공용 `bcm-local_postgres-data`·`bcm-local_kafka-data`는 자동 삭제하지 않는다. 새 기동부터 Compose 프로젝트별
  `fireblocks-*`/`stub-*` volume을 사용하고 `purge`도 현재 모드만 삭제한다.
- `up fireblocks`는 Anvil/Stub을 기동하지 않고 실제 Fireblocks workspace만 사용한다.
- `up fireblocks`는 BCM component 기동 전에 실제 `FireblocksClient`의 블록체인 목록 읽기로 API 인증을 확인하고, 성공 결과를
  카탈로그에 동기화한다. 실패 로그는 권한 제한된 `build/local/fireblocks-preflight.log`에 남기고 기동을 중단한다.
- 기본 `up stub`은 Ethereum Anvil(chain id 31337)과 Base Anvil(31338)을 띄우고 각 체인에 USDC·KRWK를 배포한다.
  네 Stub asset id는 `USDC_ETH_LOCAL`, `KRWK_ETH_LOCAL`, `USDC_BASE_LOCAL`, `KRWK_BASE_LOCAL`이며 decimals는 모두 6이다.
- 첫 Stub 기동은 BAT catalog sync→ETHEREUM/BASE 채택→4개 asset mapping을 자동 수행한다. 재기동은 결정적 주소와 기존 mapping을
  멱등 재사용하며 manifest/DB contract drift는 실패한다. vault·주소·잔액은 명시적 Admin 시나리오가 만든다.
- 기존 smoke/full은 legacy LOCAL/TUSD fixture를 전용 환경에서 유지해 새 상시 카탈로그와 회귀 계약을 분리한다.
- 설계 정본 `07-asset-master.md`, `08-bcm-admin.md`, `10-local-fireblocks-integration.md`를 waas-wiki에서 갱신했고 svc 사본은 byte 동일하다.

## 검증
- 실제 격리된 `up stub`에서 두 Anvil, 4개 6자리 token catalog/mapping, Admin `STUB+LOCAL · stub`, 재기동 reuse를 확인했다.
- `:blockchain-manager-test-support:test`, `:blockchain-manager-app:bcm-admin:test`, `ktlintCheck`, Python compile,
  API portal Node 10건, local shell 계약이 통과했다.
- system smoke `20260821T060441Z-6c274826` 10/10 PASSED. 전용 volume dataset 정리 회귀도 고정했고 잔존 resource가 없다.
- `./scripts/ci.sh`는 전체 green. 최종 Compose 프로젝트 격리 변경 뒤 local/production-boundary 테스트도 재통과했다.
- 자산 모달·로컬 BFF·V11 변경 뒤 Admin/API/persistence/BAT 회귀와 `./scripts/ci.sh` 전체가 다시 green이다.
- 검색·용어 보강 뒤 Admin/API/persistence 테스트, 브라우저 상태 23건과 `ktlintCheck`가 통과했다.
- OpenAPI 생성물 paths 19/schemas 67 fresh, API portal Node 11건과 `git diff --check` 통과.

## 다음 작업
- 사용자가 `up fireblocks`를 실행해 실제 workspace API 인증 preflight 성공을 확인한다. AI 검증에서는 실벤더를 호출하지 않는다.

## 외부 조건·후속
- Phase 15 운영 배포는 보류다. 전제는 Linux+systemd, 초기 API/Webhook/BAT 1/1/1이며 Admin 배포 여부와
  PostgreSQL·Kafka, ingress/TLS, Secret, 운영 일정은 미정이다.
- `up fireblocks`는 읽기 전용 API 인증만 자동 확인한다. 더 넓은 실 Fireblocks 계약 검사는 자동 실행하지 않으며,
  `manual-fireblocks`는 실행별 사용자 승인·공식 origin·개인 자격증명만 사용한다.
- 공유 Admin mutation 공개는 mTLS+5분 이하 JWT 인증/인가 구현 뒤에만 가능하다.
- `bcm_job_m.markSucceeded` 다중 인스턴스, pending 실패 격리, 대사 제외 ID·보관 scan은 PLAN #44·#45 후속이다.

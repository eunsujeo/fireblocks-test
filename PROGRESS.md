# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 11 T11.8 완료. Phase 11 전체 구현·E2E·converge 종료.**
- 로컬 지원 조합은 `STUB+LOCAL`, `FIREBLOCKS+TESTNET`, `FIREBLOCKS+MAINNET`이며 나머지 조합과 로컬 모드의 실 Secret·
  외부 RPC·mainnet chain id를 시작 전에 거부한다.
- production API/BAT는 test-support·Admin·로컬 체인에 런타임 의존하지 않는다. reset은 Stub+Anvil만 소유하고 기존 BCM
  PostgreSQL·Kafka를 설치·초기화·삭제하지 않는다.
- 상태형 Stub은 실제 raw transaction·RS256 API 인증·RS512 Webhook·실패 큐/재전송, 입출금·내부이체·부분 sweep과
  BCM 원장/Kafka/BAT 대사를 실제 Anvil 결과로 검증한다.
- Universal Gasless는 Anvil Prague EIP-7702에서 source native 0, fee payer, nonce/deadline/replay, allowance approve와
  batch sweep을 검증했다. Fireblocks MPC·TAP·실 relayer·과금은 `REAL_FIREBLOCKS_ONLY`다.
- 폐쇄망 배포물은 고정 Anvil 1.7.1·JRE 25·Stub·artifact·systemd를 CPU별 tar.gz로 제공하며 Docker·PostgreSQL·Kafka·
  Fireblocks Secret을 포함하지 않는다.
- 로컬 PID 종료는 양수 PID·cwd·Gradle wrapper·task·start token을 검증한다. pre-token 정상 프로세스는 exact legacy
  identity로 인계하고 무관 live PID는 종료하거나 추적 파일을 지우지 않는다.
- 실 Fireblocks read-only 계약 검사는 실행별 승인과 Secret을 요구하고 wrapper·Gradle task·client에서 공식
  `https://api.fireblocks.io` origin만 허용한다. 일반 CI에서는 실 API를 호출하지 않는다.
- 설계 사본 18개가 wiki 정본과 byte 동일하고 OpenAPI 생성물은 fresh다.
- 전체 `scripts/ci.sh`, ARM64 network-none distribution smoke, 독립 design-sync와 code-reviewer가 통과했다.
- 최종 converge 범위 `bfc5a10..1bab456`: Critical 0 / Major 0. 작업트리는 plan/progress 갱신 전 clean이었다.

## 다음 작업
- Phase 11 이후 신규 Phase는 아직 PLAN에 없다. 다음 구현 범위는 사용자와 우선순위를 정해 PLAN에 추가한다.
- 코드 리뷰 Improvement: Gradle wrapper가 shell→Java로 전환되는 매우 짧은 기동 구간에는 identity 판정 grace retry를
  고려할 수 있다. converge 차단 사항은 아니다.

## 외부 조건·후속
- 실제 Fireblocks 조회도 실행별 사용자 승인이 필요하며 write golden test는 비용·자금·TAP·정리 영향 확인 뒤 별도 수행한다.
- 공유 Admin mutation 공개는 mTLS+단기 JWT 인증/인가 구현 뒤에만 가능하다.
- 계열 승자의 cnfm>0 뒤 reorg, FAILED boost 뒤 늦은 웹훅/COMPLETED hash는 설계 판단·벤더 실측이 필요하다.
- `bcm_job_m.markSucceeded` 다중 인스턴스, pending 실패 격리, 대사 제외 ID·보관 scan은 PLAN #44·#45 후속이다.
- Kotlin 2.4.20 GA·Boot 4.1 호환 확인 후 PLAN #41 suppression 제거와 build cache 재활성화가 필요하다.

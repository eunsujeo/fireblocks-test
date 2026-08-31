# PROGRESS — 세션 핸드오프
> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치
- **Phase 14 후속 T14.11~26 완료. Phase 15는 계속 보류.**
- 공유 환경 Admin workflow는 DAW-ADMIN 소유다. 이 저장소 Admin은 DAW-CORE 비의존 로컬 개발·진단 콘솔이다.
- 운영 DB SQL은 DBA가 먼저 배포하며 애플리케이션은 DDL 권한을 갖지 않는다.

## 이번 구현 — T14.26 Fireblocks 문서 계약 정정
- 07의 신규 API 응답에서 체인 `metadata.deprecated`, 온체인 자산 `onchain.decimals`, FIAT root `decimals`를 구분하고
  중첩 우선·root 호환 fallback을 명시했다.
- Webhooks V2 재전송 범위를 `resend_failed` 24시간, resource ID 30일, query 최근 72시간·요청 창 24시간으로 분리했다.
- BCM 수동 복구는 기존 `resend_failed` 24시간과 오래된 거래 공백 tx 대사를 유지하며 코드·OpenAPI는 변경하지 않았다.
- 99 복구 설계의 `resend_failed` 30일 잔존 문구와 runbook의 열린 #40 참조도 같은 endpoint 계약으로 정정했다.
- waas-wiki 정본 QnA는 `a56f394`, 07은 후속 `04309a2`, 99는 `cf6c6c0`으로 푸시하고 서비스 사본을 byte 동일하게 동기화했다.

## 검증 완료 (2026-08-31)
- waas-wiki 정본 07·QnA와 서비스 사본의 byte 동일성 및 전체 설계 사본 동기화를 검증했다.
- `./scripts/ci.sh` 최종 통과: local/production boundary, 전체 build/test, ktlint, API drift, dependency check green.
- Dependency-Check의 기존 `kafka-clients 4.2.1 / CVE-2026-41115` 경고 1건은 PLAN #42대로 보고서에 유지한다.
- converge 범위는 `53277b0..working tree`. 독립 design-sync는 clean 통과했고, code-reviewer(GPT-5 계열,
  effort 미노출)는 99·runbook 잔존 문구 수정 뒤 Critical·Improvement·Minor 0, 커밋 가능으로 판정했다.

## 다음 작업
- T14.26 후속 필수 작업은 없다.
- 운영 논의 전까지 Phase 15는 보류한다.
- 열린 설계 항목은 PLAN 표를 기준으로 외부 조건이 추가로 확정된 항목부터 진행한다.
- 실제 Fireblocks mutation과 `manual-fireblocks` golden test는 계속 명시 승인 경계다.

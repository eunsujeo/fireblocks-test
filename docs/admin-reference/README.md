# Blockchain Manager Admin 승인 기준안

> 상태: **2026-08-17 사용자 승인·정본 반영 완료**. 이 폴더는 승인 기준 화면과 토큰을 보관하며,
> 설계 정본은 waas-wiki `BC/설계/08-bcm-admin.md`와 영향 문서, 서비스 사본은 `docs/design/`이다.

## 1. 배치와 신뢰 경계

- Admin Frontend와 **Admin BFF**는 DAW-CORE와 분리된 Blockchain Manager 전용 애플리케이션으로 이 저장소에서 운영한다.
- 첫 용도는 읽기 전용 기능 테스트다. 기본 profile은 frontend·BFF와 대상 BCM을 loopback에만 바인딩하고 상태 변경 API를 노출하지 않는다.
- 브라우저는 BFF만 호출한다.
- BCM Admin API는 `bcm-api`와 같은 애플리케이션으로 배포하되 일반 업무 API와 분리된 private listener/ingress에 둔다.
- 공유 환경의 BFF→BCM은 mTLS 서비스 신원과 5분 이하의 단기 서명 JWT를 함께 검증한다. JWT는 `aud=bcm-admin-api`,
  직원번호, 부점코드, 역할, 세션/요청 ID를 담는다. 기존 직원번호·부점코드 헤더만으로는 인증하지 않는다.
- Fireblocks, RPC, 컨트랙트 artifact, multisig 자격은 BFF와 브라우저에 전달하지 않는다.

## 2. 역할과 정족수

역할 claim은 `BCM_VIEWER`, `BCM_OPERATOR`, `BCM_APPROVER`, `BCM_SECURITY_APPROVER`,
`BCM_AUDITOR`로 고정하고 실제 사내 인증 제공자의 그룹을 BFF에서 매핑한다. BCM 서버가 최종 권한과
정족수를 판정한다.

| 위험 등급 | 예 | 필요한 사람 |
|---|---|---|
| 조회 | 상태·감사·증적 조회 | 해당 조회 역할 1명 |
| 신속 중지 | 출금/sweep/approve 신규 실행 중지 | 운영자 1명, 사후 감사 필수 |
| 일반 변경 | hard ceiling 안의 운영값 변경, 웹훅 복구 요청 | 요청자 + 독립 승인자 1명 |
| 자금 실행 | 승인된 밴드S hot→cold, allowance 회수 | 요청자 + 독립 승인자 1명 |
| 보안 변경 | 컨트랙트 활성화, 상한 확대, 목적지 변경 | 요청자 + 서로 다른 승인자 2명, 그중 1명은 보안 승인자 |
| 재개 | 중지 해제, 보안 기능 재활성화 | 요청자 + 서로 다른 승인자 2명, 그중 1명은 보안 승인자 |

- 요청자는 어떤 등급에서도 자신의 요청을 승인할 수 없다.
- 고위험 요청의 두 승인은 서로 다른 사람이어야 한다.
- 거절·만료·snapshot 변화가 있으면 기존 승인을 재사용하지 않는다.
- 중지는 즉시 적용하지만 재개는 원인 해소, 외부 drift 없음, 최신 snapshot을 모두 재검증한다.

## 3. 컨트랙트·RPC 증적 정본

- sweep 컨트랙트 소스·ABI·재현 빌드는 별도 `blockchain-manager-contracts` 저장소의 서명된 immutable release가 정본이다.
- release에는 source commit, compiler/optimizer 설정, ABI/artifact SHA-256, 예상 runtime bytecode hash,
  배포 manifest를 포함한다.
- 실제 주소·code·불변값은 BCM 서버가 사내 RPC gateway에서 pinned block 기준으로 읽은 온체인 값이 정본이다.
- 활성화와 재개 시에는 서로 독립된 두 RPC endpoint의 `chainId`, code hash, 불변값이 모두 일치해야 한다.
  한쪽 실패·stale·불일치는 fail-closed다.
- 독립 감사·TAP·Callback·Gasless·회수 훈련 문서는 승인된 문서 보관소 URI와 SHA-256으로 등록하고,
  Admin DB에는 불변 evidence snapshot만 보관한다.

## 4. 밴드S 계산과 콜드 이동

- **DAW-CORE Treasury/Admin 백엔드가 계산 주체**다. 환율·NAV·원장 정본을 가진 쪽에서 immutable input snapshot과
  이동안을 만들고, BCM은 승인된 이동안을 검증·멱등 제출·대사하는 실행기다.
- 핫 합계는 고객 vault, 옴니버스, 출금 풀의 체인 관찰 잔액이다.
- 고객 vault 잔액은 기존 sweep을 거쳐 옴니버스로 모으고 출금 풀 초과분은 옴니버스로 회수한다.
  **옴니버스 하나만** TAP allowlist의 고정 콜드 주소로 출금한다.
- 첫 출시는 외부 콜드 주소 경로로 한정한다. Fireblocks cold workspace는 워크스페이스 간 이동·권한·수수료를
  실측한 뒤 별도 정책 버전으로 추가한다.
- cold→hot은 Admin이 자동 서명하지 않는다. 외부 콜드 서명 절차로 옴니버스에 입금된 사실을 확인한 뒤,
  강화 정족수로 출금 풀 재분배를 승인한다.
- 계산은 `총자산 A = 관찰 hot H + 관찰 cold C`, 상한 초과 이동량은 `H - 목표비율 × A`다.
  진행 중 hot→cold 예약분은 유효 hot에서 **한 번만** 빼고 분모 A에서는 빼지 않는다.
- 입력 누락·만료, 예약 충돌, 출금 풀 최소 운영잔액 침해, 목적지/TAP/RPC drift가 있으면 이동안 승인을 막는다.

## 5. UI 기준

- 미감은 장식보다 증적과 행동 순서를 강조하는 **운영 관제 원장**이다.
- 상태는 아이콘·텍스트·색을 함께 쓰고 색만으로 구분하지 않는다.
- 고위험 작업은 diff, 영향, snapshot, 정족수, 금지 사유를 action 바로 앞에서 다시 보여 준다.
- optimistic update를 쓰지 않고 서버 재조회가 끝나기 전까지 `적용 중`으로 표시한다.
- 기본 표기는 사용자 시간대이며 UTC 원문을 즉시 확인할 수 있다.
- 빈 상태, 권한 없음, stale, 부분 실패, 동시 변경, 중복 클릭을 독립 상태로 설계한다.

기준 화면은 [index.html](index.html)이며 Dashboard, Transaction Detail, Policy Approval,
Band S Simulation 네 화면과 비상 흐름·권한·stale 상태를 포함한다. 색·간격·상태 토큰은
[tokens.css](tokens.css)가 정본 후보이다.

## 정본 반영 범위

1. waas-wiki `01-infra.md`: 독립 Blockchain Manager Admin BFF, loopback 기능 테스트 경계, 공유 환경 mTLS/JWT 경계
2. `02-bcm-flow.md`: 요청/승인/활성화, 밴드S omnibus 경로, 중지/재개 흐름
3. `03-bcm-db.md`: immutable policy/contract/evidence/request/approval/audit/snapshot 원장
4. `06-sweep.md`: DAW-CORE 계산, external cold MVP, omnibus 출구, 예약분 산식
5. `07-asset-master.md`: 직원 헤더 인증 제거, 논리 해제·감사·주소 발급 경합
6. `08-bcm-admin.md`: 역할·정족수·증적·UI 토큰과 기준 화면
7. `09-asset-map.md`: 시나리오별 vault 간 자산 이동과 경계 판정
8. `docs/design/`: 위 정본과 byte-동일 동기화

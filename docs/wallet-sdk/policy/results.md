---
title: 결과 읽기
description: 정책 판단의 결과가 API 응답의 어느 자리에 나타나는지
---

_읽는 사람: 파트너사 개발자. 정책이 요청을 거절하거나 붙잡았을 때 그 사실이 어느 응답 필드에 나타나는지를 다룹니다._

정책 엔진은 요청마다 결정 하나를 냅니다. 결정은 커널이 내는 네 종류의 종결이고, 커널은 조건
트리를 평가하고 결정을 결합하는 정책 무관 고정 부분입니다([자세히](/policy/rule/decision)).
이 페이지는 그 결정이 Wallet API·Admin API·Policy API의 응답에서 각각 어느 자리에 실리는지를
씁니다.

## 결정은 네 값 중 하나입니다

| 값 | 파트너사가 읽는 뜻 |
|---|---|
| `allow` | 통과입니다. 서명 인가가 발급되고 집행으로 넘어갑니다 |
| `deny` | 거부입니다. 서명은 일어나지 않습니다 |
| `pending` | obligation 이행을 기다립니다. 승인 정족수가 대표적인 예입니다 |
| `need_more_data` | 판단에 필요한 사실을 아직 못 읽었습니다. 수집이 끝나면 다시 평가합니다 |

룰이 적는 것은 `allow`와 `deny` 둘뿐이고 나머지 둘은 커널이 산출합니다. 여러 룰과 여러 모듈의
답을 하나로 모으는 규칙은 [결정과 결합](/policy/rule/decision)에 있습니다.

아래 세 절은 이 네 값이 실제 응답의 어느 필드로 번역되는지를 표면별로 씁니다. 표면마다 번역
방식이 다릅니다.

## 출금은 상태와 세부 상태에 실립니다

Wallet 서버와 실 정책 서버의 연결은 아직 없습니다. 지금은 요청을 처리하는 그 자리에서 동기로
자동 승인되고, 아래 대응은 계약과 코드에 자리만 잡혀 있습니다.

출금 조회 응답은 `status`와 `subStatus`를 함께 냅니다. 정책이 거절하면 `status`는 `FAILED`가
되고 `subStatus`는 `SCREENING_REJECTED`가 됩니다. 이 조합이 출금에서 정책 거절을 읽는 자리입니다.

**출금에는 정책 거절 전용 에러 코드가 없습니다.** 요청 자체도 에러 응답으로 끝나지만, 그 자리에
쓰이는 코드가 계약의 `ErrorCode` 목록에 아직 없습니다. 그래서 출금의 정책 거절은 에러 코드가
아니라 상태로 분기합니다.

같은 `FAILED` 안에서 세부 상태가 원인을 가릅니다. `REQUEST_REJECTED`는 정책이 아니라 뒷단
플랫폼이 제출을 거절했을 때입니다. `TR_DENIED`는 계약에 있으나 아직 어느 코드도 이 값을 쓰지
않습니다.

## 집금과 vault 이체는 에러 코드로 거절을 알립니다

이 둘은 거절이 에러 응답에 실립니다. 집금은 `SWEEP_POLICY_DENIED`, 워크스페이스 vault 이체는
`WORKSPACE_VAULT_TRANSFER_POLICY_DENIED`입니다.

남는 기록은 서로 다릅니다. 집금은 인가를 받기 전에 행을 만들지 않아 거절되면 조회할 기록이
없습니다. vault 이체는 접수 행을 먼저 만들기 때문에 `status`가 `FAILED`, `subStatus`가
`SCREENING_REJECTED`로 마감됩니다.

**두 코드 모두 지금은 응답에 나오지 않습니다.** 정책 인가가 자동 승인이라 거절 분기에 도달하지
않습니다. 연동을 만들 때는 코드를 받는 쪽 처리를 미리 넣어 둡니다.

## 승인이 필요한 요청은 멈추고 승인을 기다립니다

승인 게이팅은 어드민 액션이 승인자의 확인을 거쳐야 실행되는
통제입니다([자세히](/policy/sequences/approval-gate)). 승인 절차가 붙는 Admin API 요청은 즉시
실행되지 않고 공통 응답 하나로 돌아옵니다.

그 응답은 `status`·`approvalId`·`requesterAdminId`·`quorum`·`result` 다섯을 냅니다. `status`는
`PENDING_APPROVAL`·`APPROVED`·`DENIED` 중 하나이고, `result`는 `APPROVED`가 아니면 `null`입니다.
`requesterAdminId`는 그 요청을 만든 어드민 유저 식별자입니다. `quorum`은 정족수 현황이고, 정족수는
승인이 성립하는 데 필요한 최소 승인자 수입니다. `quorum.threshold`가 필요한 표 수,
`quorum.approved`가 지금까지 모인 표 수입니다.

`PENDING_APPROVAL`로 돌아온 요청은 Approvals API에서 이어 갑니다. 엔드포인트는 셋입니다.

- `GET /admin/api/v1/approvals` — 승인 진행 내역 목록
- `GET /admin/api/v1/approvals/{approvalId}` — 승인 진행 내역 상세
- `POST /admin/api/v1/approvals/{approvalId}` — 단건 승인

**Approvals API는 지금 고정 응답을 냅니다.** 세 엔드포인트가 요청 내용과 무관하게 같은 값을
돌려줍니다. 승인 게이팅을 실제로 거치는 어드민 엔드포인트는 `APPROVED`와 `DENIED` 둘만 내고
`quorum`은 `null`입니다. 정족수가 모여 승인이 성립하는 흐름은
[승인 게이트](/policy/sequences/approval-gate)에 있고, 콘솔에서 승인 요청이 만들어지는 자리는
[룰 저작과 발행](/policy/workflow)이 씁니다.

## 판단 요청을 직접 조회하면 결정 전문이 보입니다

판단 요청은 한 업무 요청을 두고 엔진이 소유하는 판단 기록입니다. Policy API의
`GET /request/{policyRequestId}`가 그 기록 전문을 냅니다. 부르는 주체는 Wallet 서버입니다.
Wallet 서버는 파트너 대상 API를 받아 정책 엔진에 판단을 묻고 서명 인프라에 제출하는 Wallet SDK의
서버 컴포넌트입니다([자세히](/policy/architecture)).

응답의 `status`는 판단 요청이 지금 어느 단계인지입니다. 값은
`RECEIVED`·`NORMALIZING`·`EVALUATING`·`PENDING_DATA`·`PENDING_OBLIGATION`·`APPROVED`·`CONSUMED`·`DENIED`·`EXPIRED`·`FAILED`입니다.
`CONSUMED`는 발급된 인가가 실제로 쓰였다는 뜻이고, `APPROVED`에서 멈춰 있으면 아직 집행되지
않은 것입니다.

**결정 전문은 `decision` 안에 있습니다.** 다섯 필드를 함께 읽습니다.

- **`decision.decision`**: 위 표의 결정값 넷 중 하나입니다
- **`decision.reasons`**: 판단 사유입니다. 발동한 룰과 소스 이름이 여기 실립니다
- **`decision.unevaluatedRules`**: deny가 나와 평가가 중단되는 바람에 판정하지 않은 룰입니다. 룰
  이름과 `effect`가 함께 옵니다([자세히](/policy/rule/decision))
- **`decision.obligations`**: `pending`일 때 이행해야 할 obligation과 충족 현황입니다.
  obligation은 통과로 확정되기 전에 이행해야 하는 조건입니다([자세히](/policy/rules)). 정족수
  obligation이면 `quorumRequired`와 `quorumApproved`가 함께 옵니다
- **`decision.required`**: `need_more_data`일 때 더 읽어야 할 PIP 소스 이름입니다. PIP 소스는
  룰이 참조하는 외부 사실의 출처입니다([자세히](/policy/runtime/pip))

엔드포인트 전수와 필드 계약은 [Policy API](/reference/policy)에 있습니다.

## 다음으로

- [룰 저작과 발행](/policy/workflow) — draft에서 활성까지 사람이 거치는 절차
- [승인 게이트](/policy/sequences/approval-gate) — 정족수가 모여 승인이 성립하는 흐름
- [Wallet API](/reference/wallet) — 출금·집금·vault 이체 엔드포인트 전수
- [Admin API](/reference/admin) — 승인 게이팅과 Approvals 엔드포인트 전수

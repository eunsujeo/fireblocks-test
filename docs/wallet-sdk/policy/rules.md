---
title: 룰 쓰기
description: 룰 하나가 무엇으로 이루어지고 무엇을 참조할 수 있는지
---

_읽는 사람: 파트너사 개발자. 룰 하나의 JSON이 어떤 조각으로 이루어지고 그 조각이 무엇을 가리킬 수 있는지를 설명합니다._

룰은 `scope`와 조건을 갖고 `allow`·`deny`를 내는 단위입니다. 정책 엔진이 요청 하나를 심사할 때
쓰는 최소 단위이고, 파트너사가 JSON으로 적습니다. 이 페이지는 그 JSON의 네 조각과, 룰이 참조할 수
있는 필드를 설명합니다.

## 룰의 네 조각

룰은 `effect`·`scope`·`when`·`obligations` 네 조각입니다. 아래는 워크스페이스 시드에 들어 있는
외부 주소 출금 허가 룰입니다. 워크스페이스는 Wallet SDK를 도입한 회사 하나이고 최상위 격리
컨테이너입니다([계정 계층](/accounts-wallets/accounts)).

이 룰에는 통과로 확정되기 전에 이행해야 하는 조건인 obligation이 없습니다.

```json
{
  "id": "withdrawal/allow-evm-external-address",
  "effect": "allow",
  "scope": {
    "all": [
      { "field": "operation", "op": "eq", "value": "value_transfer" },
      { "field": "chain.family", "op": "eq", "value": "evm" },
      { "field": "destination.type", "op": "eq", "value": "external_address" },
      { "field": "source_vault.kind", "op": "eq", "value": "custody" }
    ]
  },
  "when": { "field": "address_ownership.status", "op": "eq", "value": "active" }
}
```

**`scope`가 이 룰이 발동하는 범위를 정하고 `when`이 그 안에서 판정합니다.** scope에 걸리지 않는
요청에 이 룰은 없는 것과 같습니다.

`effect`에 적을 수 있는 값은 `allow`와 `deny` 둘뿐입니다. `pending`과 `need_more_data`는 조건
트리를 평가하고 결정을 결합하는 [커널](/policy/rule/decision)이 내는 결정값이고, 파트너사가 고르는
값이 아닙니다.

`operation`에 적는 값은 정규화된 연산 값입니다. 출금·vault 이체·집금 셋을
[정규화](/policy/runtime/normalization)가 `value_transfer` 하나로 합칩니다. 그래서 룰이 그 셋을
가르는 것은 `destination.type`과 `source_vault.kind`입니다.

요청 표면의 값인 `withdrawal`을 여기 적으면 어떤 요청에도 매치되지 않습니다. 거부는 나므로 아무것도
깨지지 않고, 요청이 끝까지 통과하는지 재본 뒤에야 그 룰이 죽어 있었다는 것이 드러납니다.

## 조건 노드

`scope`와 `when`은 같은 문법을 씁니다. 둘 다 조건 트리이고, 조건 트리는 `all`·`any`·`not`과
비교·멤버십 리프로 이루어진 룰의 조건식입니다.

노드는 다섯입니다 — 비교(`eq`·`ne`·`lt`·`lte`·`gt`·`gte`), 멤버십(`in`·`not_in`), 그리고
`all`·`any`·`not`입니다. 중첩할 수 있고 깊이 상한은 컴파일러가 봅니다.

**카탈로그에 없는 필드는 참조할 수 없습니다.** 참조하면 컴파일이 실패합니다. 값이 채워지지 않은
필드를 비교하면 불성립(거짓)으로 봅니다. allow 룰에 안전한 방향입니다.

## 요청 필드와 수집 필드

룰이 참조할 수 있는 경로는 두 갈래로 나뉩니다. 요청에서 나오는 필드와, 평가 중에 밖에서 읽어 오는
필드입니다.

요청 필드는 정규화가 요청에서 뽑은 값입니다. 요청이 접수되는 순간 값이 정해지므로 `scope`와 `when`
어디서든 쓸 수 있습니다.

<FieldCatalog only="request" />

수집 필드는 평가 도중 PIP 소스가 채우는 값입니다. PIP 소스는 룰이 참조하는 외부 사실의
출처입니다([자세히](/policy/runtime/pip)). 수집 이전에 `scope`가 판정되기 때문에 수집 필드는
`when`에서만 쓸 수 있습니다. 시드 출금 룰이 `address_ownership.status`를 `when`에 적는 것이 그
예입니다.

아래 목록은 그 워크스페이스에 등재된 PIP 소스에 따릅니다. 소스를 더하면 참조할 수 있는 경로도 함께
늘어납니다.

<FieldCatalog only="pip" />

어드민 액션의 `payload.*` 필드는 플랫폼이 저작하는 표면이라 이 표에 없습니다. 그 계약은
[룰 명세](/policy/rule/reference)에 있습니다.

## 리터럴과 슬롯

값은 리터럴이거나 슬롯입니다. 슬롯은 카탈로그에 선언되고 워크스페이스가 조정할 수 있는 값입니다.

플랫폼이 하한(floor)이나 허용 값 목록을 정하고 파트너사가 그 안에서 값을 골라 발행합니다. 시드
카탈로그에 있는 슬롯은 다섯입니다.

- **`grantable_roles`**: 권한 부여 액션으로 줄 수 있는 role 목록입니다
- **`approval_quorum`**: 권한 부여를 승인하는 데 필요한 최소 승인자 수입니다
- **`escalation_quorum`**: 어드민 액션 중 권한을 올리는 성격의 것들인 escalation surface에서 요구하는
  최소 승인자 수의 하한이고, 기본값이 `approval_quorum`보다 높습니다
- **`approved_intent_ttl_seconds`**: 승인된 요청이 서명까지 유효한 시간(초)입니다
- **`resource_limit_per_tx`**: 건당 수수료 총액 상한이고, 체인별 값을 각각 선언합니다

**금액 상한은 리터럴로 적을 수 없습니다.** 금액 리터럴의 순서 비교는 컴파일 시점에 거부되므로,
상한은 금액 슬롯을 참조해서만 씁니다.

커널이 평가 시점에 다시 보는 것은 하한과 타입까지이고, 허용 값 목록은 발행 시점에만 대조합니다. 그
차이가 왜 생기는지는 [결정과 결합](/policy/rule/decision)에서 설명합니다.

## obligation 세 종류

obligation은 "통과시키되 이것을 이행하라"는 요구입니다. 종류는 셋이고, 룰이 그중 하나를 골라
답니다.

| 타입 | 무엇을 요구하나 |
|---|---|
| `require_2fa` | 요청자의 2단계 인증 |
| `require_quorum` | 승인이 성립하는 데 필요한 최소 승인자 수인 정족수. 인원수·승인 가능 역할·본인 승인 허용 여부를 정합니다 |
| `attach_travel_rule_payload` | 트래블룰 정보 첨부 |

`require_quorum`의 본인 승인 허용은 생략하면 불허입니다. 요청자와 승인자가 같으면 정족수가 통제가
아니라 형식이 되기 때문입니다.

**`deny` 룰에는 obligation을 달 수 없습니다.** 거부하면서 이행을 요구하는 것은 뜻이 성립하지
않습니다.

obligation은 배열이라 둘 이상을 함께 답니다. 플랫폼이 발행하는 역할 부여 허가 룰이 그 사례입니다.

```json
"obligations": [
  { "type": "require_2fa" },
  { "type": "require_quorum", "params": { "n": { "slot": "approval_quorum" } } }
]
```

## 번들 두 종류

저작 결과가 들어가는 번들은 둘입니다. 누가 발행하고 무엇을 담는지가 갈립니다.

정책 로직 번들은 룰과 선언 데이터를 커널과 함께 담아 발행한 배포 산출물입니다. 플랫폼 거버넌스가
관리하고, 배포 단위당 하나이며, 변경은 제안자와 승인자를 갈라 두는 maker-checker 승인을 거칩니다.

워크스페이스 번들은 한 워크스페이스가 소유하는 값 이동 룰과 슬롯 값입니다. 값 이동은 출금·vault
이체·집금처럼 자산을 실제로 움직이는 연산 셋입니다. 파트너사가 쓰는 룰과 슬롯 값이 여기 들어갑니다.

하한만 두고 상한을 두지 않습니다. 인가 TTL의 상한은 슬롯 선언이 아니라 검사 둘이 강제합니다. 발행
시점의 컴파일 검사와, 승인 스탬프를 찍는 절차입니다.

**조정을 불허할 값은 슬롯이 아니라 룰에 직접 씁니다.** 커널이 평가 시점에 허용 값 목록을 다시 보지
않기 때문입니다.

## 다음으로

- [예시 정책](/policy/examples) — 시드에 들어 있는 룰 셋과, 통과하는 요청과 거절되는 요청
- [룰 저작과 발행](/policy/workflow) — draft에서 활성까지 사람이 거치는 절차
- [룰 명세](/policy/rule/reference) — 참조 가능 경로와 어드민 액션 표면의 전수 계약
- [결정과 결합](/policy/rule/decision) — 커널이 룰의 결정을 결합하는 규칙

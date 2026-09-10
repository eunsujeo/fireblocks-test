---
title: 룰 구조
description: 룰은 네 조각입니다
---

_읽는 사람: 구현팀. 룰 하나가 무엇으로 이루어지는지를 담고, 룰이 무엇을 가리킬 수 있는지는 [룰 명세](/policy/rule/reference)에 있습니다._

룰은 `scope`와 조건을 갖고 `allow`·`deny`를 내는 단위입니다. 정책 엔진이 요청을 심사할 때 쓰는
최소 단위이고, 저작자가 JSON으로 적습니다. 이 페이지는 그 JSON이 어떤 조각으로 이루어지는지를
씁니다.

## 룰의 네 조각

룰은 `effect`·`scope`·`when`·`obligations` 네 조각입니다. 아래는 저작 세트에 실제로 있는 외부 주소
출금 허가 룰이고, 이 룰에는 obligation이 없습니다.

```json
{
  "id": "withdrawal/allow-evm-external-address",
  "description": "custody vault 에서 나가는 EVM 외부 주소 출금을 등재된 주소로만 허가한다",
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

`operation`에 적는 값은 정규화된 연산 값입니다. 출금·vault 이체·집금 셋을 정규화기가
`value_transfer` 하나로 합칩니다. 그래서 룰이 그 셋을 가르는 것은 `destination.type`과
`source_vault.kind`입니다.

요청 표면의 값인 `withdrawal`을 여기 적으면 어떤 요청에도 매치되지 않습니다. 거부는 나므로 아무것도
깨지지 않고, 요청이 끝까지 통과하는지 재본 뒤에야 그 룰이 죽어 있었다는 것이 드러납니다.

## `effect`의 두 값

`effect`에 적을 수 있는 값은 `allow`와 `deny` 둘뿐입니다. `pending`이나 `need_more_data`를 룰이
적을 수 없습니다. 그 둘은 조건 트리를 평가하고 결정을 결합하는
[커널](/policy/rule/decision)이 산출하는 결정값이지 저작자가 고르는 값이 아닙니다.

**`deny` 룰에는 obligation을 달 수 없습니다.** 거부하면서 이행을 요구하는 것은 뜻이 성립하지
않습니다.

## `obligations`의 세 종류

obligation은 통과로 확정되기 전에 이행해야 하는 조건입니다. "통과시키되 이것을 이행하라"는
요구이고, 종류는 셋입니다.

| 타입 | 무엇을 요구하나 |
|---|---|
| `require_2fa` | 요청자의 2단계 인증 |
| `require_quorum` | 승인이 성립하는 데 필요한 최소 승인자 수인 정족수. 인원수·승인 가능 역할·본인 승인 허용 여부를 정합니다 |
| `attach_travel_rule_payload` | 트래블룰 정보 첨부 |

`require_quorum`의 본인 승인 허용은 생략하면 **불허**입니다. 요청자와 승인자가 같으면 정족수가
통제가 아니라 형식이 되기 때문입니다.

obligation은 배열로 답니다. 저작 세트의 역할 부여 허가 룰이 둘을 함께 다는 실제 사례입니다.

```json
"obligations": [
  { "type": "require_2fa" },
  { "type": "require_quorum", "params": { "n": { "slot": "approval_quorum" } } }
]
```

## 조건 노드 다섯

조건은 다섯 노드로 씁니다. 비교(`eq`·`ne`·`lt`·`lte`·`gt`·`gte`), 멤버십(`in`·`not_in`), 그리고
`all`·`any`·`not`입니다. 자유롭게 중첩할 수 있고 깊이 상한은 컴파일러가 봅니다.

**카탈로그에 없는 필드는 참조할 수 없습니다.** 참조하면 컴파일이 실패합니다. 값이 채워지지 않은
필드를 비교하면 불성립(거짓)으로 봅니다. allow 룰에 안전한 방향입니다.

## 리터럴과 슬롯

값은 리터럴이거나 슬롯입니다. 슬롯은 카탈로그에 선언되고 워크스페이스가 조정할 수 있는 값입니다.
워크스페이스는 Wallet SDK를 도입한 회사 하나이고 최상위 격리
컨테이너입니다([계정 계층](/accounts-wallets/accounts)). 플랫폼이 하한과 허용 값 목록을 정하고 파트너사가
그 안에서 발행합니다.

**커널이 평가 시점에 다시 보는 것은 하한과 타입입니다.** 발행 시점 검증만 두면 그 검증을 우회한
값이 평가에 들어올 수 있기 때문입니다. 검증의 기준점이 주장자와 다른 곳에 있어야 합니다.

허용 값 목록은 발행 시점에만 대조합니다. 문자열 목록 슬롯의 값이 카탈로그의 목록 안인지를
커널은 평가 시점에 다시 보지 않습니다. 그 검사는 발행 경로가 성립할 때만 유효합니다.

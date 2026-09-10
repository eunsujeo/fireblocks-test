---
title: 웹훅(Webhook)
description: 무엇이 언제 오고 무엇을 하면 되는가
---

_읽는 사람: 파트너사 개발자. 어떤 통지가 언제 오고 받는 쪽이 무엇을 하면 되는지를 설명합니다._

웹훅은 통지를 나르는 채널입니다. 파트너사가 등록한 URL로 HTTP 호출이 갑니다. 통지는 온체인
이벤트를 파트너사에 보내는 아웃바운드 호출이고, 웹훅에 담기는 내용물입니다.

## 인지할 수 있는 온체인 이벤트는 걸러내지 않고 보냅니다

온체인 이벤트는 최대한 보냅니다. 파트너사가 무엇을 원장에 반영할지는 파트너사가 정할 문제입니다.

입금 통지는 두 단계로 오고, 확정 뒤에 확인 재료가 늘면 갱신 통지가 따라옵니다.

| 이벤트 | 언제 | 파트너사에게 보이는 상태 |
|---|---|---|
| `DEPOSIT_DETECTED` | 온체인에서 관측했을 때 | `DETECTED` |
| `DEPOSIT_FINALIZED` | 확정됐을 때 | `FINALIZED` |
| `DEPOSIT_UPDATED` | 확정 뒤에 트래블룰 매핑이나 KYT 결과가 붙었을 때 | `FINALIZED` |
| `DEPOSIT_FAILED` | 확정 전에 실패했을 때 | `FAILED` |

갱신 통지는 한 건에 여러 번 올 수 있고, 매번 그 순간까지 확인된 정보를 전부 담습니다. 트래블룰
정보가 온체인 확정보다 늦게 오는 것이 보통이라 확정 통지에는 없던 값이 갱신 통지에 들어갑니다.
왜 그 순서인지는 [입금](/fund-flows/deposit)에 있습니다.

**파트너사에게 보이는 상태는 도메인 상태와 다릅니다.** 확정된 입금의 도메인 상태는
동결(`FROZEN`)이지만 통지에는 `FINALIZED`로 나갑니다. 동결은 입금을 확정 시 fail-closed로 잡아
두는 상태입니다([자세히](/fund-flows/release)).

해제는 파트너사가 요청하는 흐름입니다. 그래서 통지는 "확정됐다"까지만 말하고, 동결 여부를
파트너사에게 보이는 상태 이름에 섞지 않습니다.

## 나가는 자금도 시작과 끝을 통지합니다

출금·집금·vault 이체도 각각 통지를 냅니다. 이동 종류마다 이벤트 이름이 갈립니다.

| 이벤트 | 언제 |
|---|---|
| `WITHDRAWAL_INITIATED` | 출금 트랜잭션을 제출했을 때 |
| `WITHDRAWAL_FINALIZED` | 출금이 온체인에서 확정됐을 때 |
| `WITHDRAWAL_FAILED` | 출금이 실패로 끝났을 때 |
| `SWEEP_INITIATED` | 집금 트랜잭션을 제출했을 때 |
| `SWEEP_FINALIZED` | 집금이 온체인에서 확정됐을 때 |
| `SWEEP_FAILED` | 집금이 실패로 끝났을 때 |
| `VAULT_TRANSFER_FINALIZED` | vault 이체가 온체인에서 확정됐을 때 |
| `VAULT_TRANSFER_FAILED` | vault 이체가 실패로 끝났을 때 |

**vault 이체에는 시작 통지가 없습니다.** 출금과 집금에는 `..._INITIATED`가 있지만 vault 이체는
완료와 실패만 냅니다. 접수 직후의 진행 상태가 필요하면 조회로 확인합니다.

## 통지 본문에는 판단 재료가 들어갑니다

관측·확정 통지의 본문은 아래 형태입니다.

```json
{
  "depositId": "awd-...",
  "status": "FINALIZED",
  "workspaceId": "ws-...",
  "tenantId": "tn-...",
  "accountId": "acc-...",
  "accountWalletId": "aw-...",
  "tokenId": "tok-...",
  "amount": "100.00",
  "amountRaw": "100000000",
  "txHash": "0x...",
  "sourceRegistered": true,
  "addressBookId": "aab-...",
  "travelRule": null,
  "kyt": null
}
```

`sourceRegistered`는 발신 주소가 그 계정의 주소록에 등록돼 있는지입니다. 주소록은 주소에 등재
근거를 붙이는 장부입니다([자세히](/fund-flows/address-book)). 해제를 요청할지 판단하는 근거로
씁니다. 등록돼 있으면 `addressBookId`가 함께 옵니다.

`travelRule`은 트래블룰 매핑이 끝난 뒤 `trId`와 상대 VASP 식별자를 담고, `kyt`는 KYT 검사가 끝난
뒤 결과와 시각을 담습니다. 둘 다 아직 없으면 `null`입니다. 확정 통지에 `null`로 왔다가 갱신 통지에서
채워지는 것이 보통입니다.

**실패 통지에는 `sourceRegistered`·`addressBookId`가 없습니다.** 판단할 것이 없기 때문입니다.

## 중복 수신을 전제로 만듭니다

수신 측은 같은 통지를 두 번 받을 수 있다고 보고 만듭니다. `correlationKey`가
`{depositId}:{eventType}` 형태로 오므로, 수신 측은 그 값으로 중복을 제거합니다.

`depositId`만으로 제거하면 안 됩니다. 관측 통지와 확정 통지가 같은 `depositId`를 공유해서, 키만
보면 확정 통지가 중복으로 걸러집니다.

갱신 통지는 `{depositId}:DEPOSIT_UPDATED:{순번}`입니다. 한 건에 갱신이 여러 번 오므로 이벤트
종류만으로는 갈리지 않고, 순번이 1씩 오릅니다. 순번이 큰 통지가 최신 상태입니다.

## 아직 지키지 못하는 것

통지가 아직 지키지 못하는 것이 둘입니다.

**트래블룰 정보와 갱신 통지는 아직 나가지 않습니다.** 트래블룰은 가상자산 이전 시 송·수신 정보를
사업자끼리 교환하는 규제 의무입니다([자세히](/#함께-제공하는-모듈)). `DEPOSIT_UPDATED`와 `travelRule`·`kyt`
필드는 계약으로 정해졌지만 트래블룰 매칭과 KYT 연동이 아직 구현되지 않았습니다. 그때까지 VASP
입금의 해제 판단은 주소록 등록 여부 하나에 기댑니다.

재시도가 4xx와 5xx를 구분하지 않습니다. 지금은 둘 다 똑같이 재시도해서, 영구 실패로 판정해야 할
응답도 재시도 대상이 됩니다. 진행 상황은 [변경 이력](/changelog)에서 추적합니다.

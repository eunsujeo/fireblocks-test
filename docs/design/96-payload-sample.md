---
title: 웹훅 payload 실물 샘플 (참고) — v2 알림 원문
status: Done
ref: 참고
---

[수신 PoC](97-webhook-poc-result.md)에서 실제로 받은 Fireblocks 웹훅 v2 알림 원문이다. 같은 입금 tx 의 **감지·확정 두 건**이고, 필드 이름을 확정한 근거다.

## 감지 — `transaction.created`

```json
{
  "id": "434e8c65-9489-4621-830f-eebe03cb2b3d",
  "resourceId": "f3339e5d-428e-4add-8018-631b972f3195",
  "webhookId": "ac8bf7d0-1dff-499a-81f4-3a87719f3b3c",
  "workspaceId": "f0e016ae-d9cc-510d-b66d-0c30dd520503",
  "eventType": "transaction.created",
  "createdAt": 1785738506511,
  "data": {
    "id": "f3339e5d-428e-4add-8018-631b972f3195",
    "createdAt": 1785738506296,
    "lastUpdated": 1785738506359,
    "assetId": "KBKRW_ETH_TEST5_6KCC",
    "source": {
      "id": "4b5351f6-9dcc-4322-a2dc-3e1cec34e549",
      "type": "INTERNAL_WALLET",
      "name": "mob",
      "subType": "Internal"
    },
    "destination": {
      "id": "23",
      "type": "VAULT_ACCOUNT",
      "name": "MOB_TEST",
      "subType": ""
    },
    "amount": 100,
    "fee": 0.000113517074303855,
    "networkFee": 0.000113517074303855,
    "netAmount": 100,
    "sourceAddress": "0xC05A705eFE3f89b3a7a6Ceb6D79107529Ce20f7C",
    "destinationAddress": "0x628501678d302023ca4555B678581917dF8D7636",
    "destinationAddressDescription": "",
    "destinationTag": "",
    "status": "CONFIRMING",
    "txHash": "0x18a64b1576aed8df84db0d259bf771929f66ebfeb877194fd2a679688e864b1f",
    "subStatus": "PENDING_BLOCKCHAIN_CONFIRMATIONS",
    "signedBy": [],
    "createdBy": "",
    "rejectedBy": "",
    "amountUSD": null,
    "addressType": "",
    "note": "",
    "exchangeTxId": "",
    "requestedAmount": 100,
    "feeCurrency": "ETH_TEST5",
    "operation": "TRANSFER",
    "customerRefId": null,
    "numOfConfirmations": 1,
    "amountInfo": {
      "amount": "100",
      "requestedAmount": "100",
      "netAmount": "100"
    },
    "feeInfo": {
      "networkFee": "0.000113517074303855",
      "gasPrice": "2.5916548550000003"
    },
    "destinations": [],
    "externalTxId": null,
    "blockInfo": {
      "blockHeight": "11408638",
      "blockHash": "0x9e52eb16d9118038998640c36ec7e3096e2c74c487c533eabeddc9f0507d4dd5"
    },
    "signedMessages": [],
    "index": 0,
    "assetType": "ERC20",
    "blockchainIndex": "164",
    "blockchainInfo": {
      "evmTransferType": "TOKEN"
    }
  }
}
```

## 확정 — `transaction.status.updated`

같은 tx 라 나머지 필드는 위와 같고, **일곱 개만 다르다**:

| 필드 | 감지 | 확정 |
|---|---|---|
| `id` (알림 id) | `434e8c65-…` | `284fb70a-…` — 알림마다 새로 발급된다 |
| `eventType` | `transaction.created` | `transaction.status.updated` |
| `createdAt` (알림) | 1785738506511 | 1785738526897 — 약 20초 뒤 |
| `data.lastUpdated` | 1785738506359 | 1785738526715 |
| `data.status` | `CONFIRMING` | `COMPLETED` |
| `data.subStatus` | `PENDING_BLOCKCHAIN_CONFIRMATIONS` | `CONFIRMED` |
| `data.numOfConfirmations` | 1 | 3 |

`resourceId`·`data.id`·`txHash`·`blockInfo`·금액·주소는 두 알림에서 동일하다.

## 읽을 점

- 최상위 **`id` 가 알림 id** (인박스 `noti_id`), **`data.id` 가 벤더 tx id** (`vndr_tx_id`). 최상위 `resourceId` 는 `data.id` 와 같은 값이다.
- **`transaction.created` 가 이미 `CONFIRMING` + `txHash` + `blockInfo` 를 담고 온다** — 입금은 체인에 올라간 뒤부터 알림이 시작한다.
- 금액은 `amount`(숫자)와 `amountInfo.amount`(문자열)로 둘 다 온다 — **문자열 쪽을 쓴다**(정밀도).
- `source.type` 이 `INTERNAL_WALLET`, `destination.type` 이 `VAULT_ACCOUNT` — 방향 판단에 쓰는 값이다.
- `externalTxId` 는 입금이라 `null`, `operation` 은 `TRANSFER`, `assetType` 은 `ERC20`.

원본 파일은 fbhook 저장소 `docs/payload-samples/` 에도 같이 있다.

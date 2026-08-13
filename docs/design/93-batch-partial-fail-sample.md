---
title: 부분 실패한 배치 payload 실물 샘플 (참고) — 한 이동이 되돌려진 경우
status: Done
ref: 참고
---

vault 82(승인 잔여 100)에 200 을, vault 83(잔여 150)에 100 을 요청한 배치다. 82 의 `transferFrom` 만 되돌려졌다.

레코드는 **4개**이고 **82 는 어느 레코드에도 없다.** 성공만 담긴 경우는 [배치 sweep payload 실물 샘플](94-batch-payload-sample.md)에 있다.

## 제출 — `transaction.created`

```json
{
  "id": "c451a7d4-8dc6-4879-a88f-7fbbd61d7f0e",
  "data": {
    "id": "647c2c5b-b2a0-49b3-8b46-d2300c34c8bb",
    "fee": -1,
    "note": "approve-pull 부분 실패 재현 — 한 건은 승인 잔여 초과",
    "amount": 0,
    "source": {
      "id": "84",
      "name": "approve-pull-operator",
      "type": "VAULT_ACCOUNT",
      "subType": ""
    },
    "status": "SUBMITTED",
    "txHash": "",
    "assetId": "ETH_TEST5",
    "feeInfo": {},
    "signedBy": [],
    "amountUSD": null,
    "blockInfo": {},
    "createdAt": 1786407516290,
    "createdBy": "a040583c-465a-453f-8b02-bef2f3e97c48",
    "netAmount": 0,
    "operation": "CONTRACT_CALL",
    "subStatus": "",
    "amountInfo": {
      "amount": "0",
      "netAmount": "0",
      "requestedAmount": "0"
    },
    "networkFee": -1,
    "rejectedBy": "",
    "addressType": "",
    "destination": {
      "id": null,
      "name": "N/A",
      "type": "ONE_TIME_ADDRESS",
      "subType": ""
    },
    "feeCurrency": "ETH_TEST5",
    "lastUpdated": 1786407516290,
    "exchangeTxId": "",
    "externalTxId": "approve-pull-partial-fail-1",
    "sourceAddress": "",
    "destinationTag": "",
    "extraParameters": {
      "contractCallData": "0xd128dea1000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000002000000000000000000000000429cdea1dc75bbda4e006675abe5f773e299dddb000000000000000000000000b6df2ad4d9fb89529874636276af2e367cf091d2000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000ad78ebc5ac62000000000000000000000000000000000000000000000000000056bc75e2d63100000"
    },
    "requestedAmount": 0,
    "destinationAddress": "0xF95AFc896461a3eb7426714267eC6abb1cd6A1c9",
    "destinationAddressDescription": ""
  },
  "createdAt": 1786407516426,
  "eventType": "transaction.created",
  "webhookId": "ac8bf7d0-1dff-499a-81f4-3a87719f3b3c",
  "resourceId": "647c2c5b-b2a0-49b3-8b46-d2300c34c8bb",
  "workspaceId": "f0e016ae-d9cc-510d-b66d-0c30dd520503"
}
```

`extraParameters.contractCallData` 에 **요청 두 건이 들어 있다.** 우리 컨트랙트 ABI 로 풀면 이렇게 나온다.

```
$ cast decode-calldata "batchSweep(address[],uint256[])" 0xd128dea1…
[0x429CdEa1DC75bBDa4e006675Abe5F773E299Dddb, 0xB6Df2ad4d9FB89529874636276AF2E367cf091D2]
[200000000000000000000, 100000000000000000000]
```

vault 82 에 200, vault 83 에 100 이다. 아래 이동 내역 알림에는 83 의 100 만 남는다 — **요청은 여기 있고 결과는 거기 있어서, 둘을 맞춰야 82 의 실패가 드러난다.**

## 이동 내역 — `transaction.network_records.processing_completed`

```json
{
  "id": "0e9584b7-e50a-444a-8987-d6e41b2acc0c",
  "data": {
    "id": "647c2c5b-b2a0-49b3-8b46-d2300c34c8bb",
    "fee": 0.000152011774567585,
    "note": "approve-pull 부분 실패 재현 — 한 건은 승인 잔여 초과",
    "nonce": "1",
    "amount": 0,
    "source": {
      "id": "84",
      "name": "approve-pull-operator",
      "type": "VAULT_ACCOUNT",
      "subType": ""
    },
    "status": "CONFIRMING",
    "txHash": "0xd1c257ebb16b65887a1371e633966249ac8c6987a451316efce39f468f94f3b4",
    "assetId": "ETH_TEST5",
    "feeInfo": {
      "feeUSD": "0.2844507992583298765401704667545",
      "gasPrice": "1.9997602389999998",
      "networkFee": "0.000152011774567585"
    },
    "signedBy": [
      "a040583c-465a-453f-8b02-bef2f3e97c48"
    ],
    "amountUSD": 0,
    "assetType": "BASE_ASSET",
    "blockInfo": {
      "blockHash": "0x34337febfef57fc7a4a4863fe9b916052a87bd05abf56b01f84f50e1550f6c54",
      "blockHeight": "11462654"
    },
    "createdAt": 1786407516290,
    "createdBy": "a040583c-465a-453f-8b02-bef2f3e97c48",
    "netAmount": 0,
    "operation": "CONTRACT_CALL",
    "subStatus": "PENDING_BLOCKCHAIN_CONFIRMATIONS",
    "amountInfo": {
      "amount": "0",
      "amountUSD": "0.00",
      "netAmount": "0",
      "requestedAmount": "0"
    },
    "networkFee": 0.000152011774567585,
    "rejectedBy": "",
    "addressType": "",
    "destination": {
      "id": null,
      "name": "N/A",
      "type": "ONE_TIME_ADDRESS",
      "subType": ""
    },
    "feeCurrency": "ETH_TEST5",
    "lastUpdated": 1786407520886,
    "exchangeTxId": "",
    "externalTxId": "approve-pull-partial-fail-1",
    "sourceAddress": "",
    "destinationTag": "",
    "networkRecords": [
      {
        "type": "CONTRACT_CALL",
        "source": {
          "id": "",
          "name": "External",
          "type": "UNKNOWN",
          "subType": ""
        },
        "txHash": "0xd1c257ebb16b65887a1371e633966249ac8c6987a451316efce39f468f94f3b4",
        "assetId": "KBKRW_ETH_TEST5_6KCC",
        "amountUSD": null,
        "isDropped": false,
        "netAmount": "100",
        "networkFee": "0.000152011774567585",
        "destination": {
          "id": "12",
          "name": "kb-test-stablecoin-issuer",
          "type": "VAULT_ACCOUNT",
          "subType": ""
        },
        "destinationAddress": "0x496E49e0d3F30336079FF0B921F98D77eb00055D"
      },
      {
        "type": "CONTRACT_CALL",
        "source": {
          "id": "83",
          "name": "approve-pull-owner2",
          "type": "VAULT_ACCOUNT",
          "subType": ""
        },
        "txHash": "0xd1c257ebb16b65887a1371e633966249ac8c6987a451316efce39f468f94f3b4",
        "assetId": "KBKRW_ETH_TEST5_6KCC",
        "amountUSD": null,
        "isDropped": false,
        "netAmount": "100",
        "networkFee": "0.000152011774567585",
        "destination": {
          "id": null,
          "name": "N/A",
          "type": "ONE_TIME_ADDRESS",
          "subType": ""
        },
        "destinationAddress": "0x496E49e0d3F30336079FF0B921F98D77eb00055D"
      },
      {
        "type": "CONTRACT_CALL",
        "source": {
          "id": "83",
          "name": "approve-pull-owner2",
          "type": "VAULT_ACCOUNT",
          "subType": ""
        },
        "txHash": "0xd1c257ebb16b65887a1371e633966249ac8c6987a451316efce39f468f94f3b4",
        "assetId": "KBKRW_ETH_TEST5_6KCC",
        "amountUSD": null,
        "isDropped": false,
        "netAmount": "0",
        "networkFee": "0.000152011774567585",
        "destination": {
          "id": null,
          "name": "N/A",
          "type": "ONE_TIME_ADDRESS",
          "subType": ""
        },
        "destinationAddress": "0xF95AFc896461a3eb7426714267eC6abb1cd6A1c9"
      },
      {
        "type": "CONTRACT_CALL",
        "source": {
          "id": "84",
          "name": "approve-pull-operator",
          "type": "VAULT_ACCOUNT",
          "subType": ""
        },
        "txHash": "0xd1c257ebb16b65887a1371e633966249ac8c6987a451316efce39f468f94f3b4",
        "assetId": "ETH_TEST5",
        "amountUSD": "0",
        "isDropped": false,
        "netAmount": "0.000000000000000000",
        "networkFee": "0.000152011774567585",
        "destination": {
          "id": null,
          "name": "N/A",
          "type": "ONE_TIME_ADDRESS",
          "subType": ""
        },
        "destinationAddress": "0xF95AFc896461a3eb7426714267eC6abb1cd6A1c9"
      }
    ],
    "signedMessages": [],
    "extraParameters": {
      "contractCallData": "0xd128dea1000000000000000000000000000000000000000000000000000000000000004000000000000000000000000000000000000000000000000000000000000000a00000000000000000000000000000000000000000000000000000000000000002000000000000000000000000429cdea1dc75bbda4e006675abe5f773e299dddb000000000000000000000000b6df2ad4d9fb89529874636276af2e367cf091d2000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000ad78ebc5ac62000000000000000000000000000000000000000000000000000056bc75e2d63100000"
    },
    "requestedAmount": 0,
    "destinationAddress": "0xF95AFc896461a3eb7426714267eC6abb1cd6A1c9",
    "numOfConfirmations": 0,
    "destinationAddressDescription": ""
  },
  "createdAt": 1786407553926,
  "eventType": "transaction.network_records.processing_completed",
  "webhookId": "ac8bf7d0-1dff-499a-81f4-3a87719f3b3c",
  "resourceId": "647c2c5b-b2a0-49b3-8b46-d2300c34c8bb",
  "workspaceId": "f0e016ae-d9cc-510d-b66d-0c30dd520503"
}
```

같은 거래의 영수증에서 `SweepLeg` 이벤트를 디코딩하면 실패가 나온다.

```
SweepLeg  from=0x429cdea1…(vault 82)  요청=200  성공=False
SweepLeg  from=0xb6df2ad4…(vault 83)  요청=100  성공=True
SweepDone 이동=2  성공=1
```

## 읽을 점

되돌려진 이동은 이 알림에 나오지 않았다. 되돌려진 `transferFrom` 은 온체인에 `Transfer` 이벤트를 남기지 않으므로 적을 것이 없었던 것으로 보이는데, 확인한 실패 유형은 이 하나다. 레코드에는 `isDropped` 필드가 있고 이번에 본 값은 `false` 뿐이다.

위 `SweepLeg` 은 우리 sweeper 가 실패한 항목도 이벤트로 남기게 짜여 있어서 나온다. 이벤트를 내지 않는 컨트랙트라면 되돌려진 이동은 온체인에도 흔적이 없다.

레코드 4개는 `Transfer` 1개가 만든 두 행(보낸 vault 기준·받은 vault 기준), `Approval` 1개가 만든 금액 0 행, 그리고 호출 자체 한 행이다. 가스는 행이 아니라 각 행의 `networkFee` 필드다. 필드 자료형은 [배치 sweep payload 실물 샘플](94-batch-payload-sample.md)의 "파싱할 때 걸리는 값" 과 같다.

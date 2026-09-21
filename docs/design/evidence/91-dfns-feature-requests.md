---
title: Dfns 기능 문의 — support 문의 본문과 내부 판단
status: To Do
---

> 대상 명세는 채택본 `dfns-openapi-1.1018.3` + 공식 문서 사이트. 회신은 받은 뒤 이 문서에 이어 적는다.
> **아래 "보낼 본문"만 전달한다.** 내부 메모는 보내지 않는다.
> 1차 문서 문의(2026-09-21) 회신은 [계약13 기능 문의 회신](../13-dfns-contracts.md#기능-문의-회신--2026-09-21)에 정리했다.

---

# 내부 메모 — 전달하지 않는다

## 1차에서 답이 나와 뺀 것

| 물음 | 결론 | 왜 뺐나 |
|---|---|---|
| `Confirmed`의 기준 | 네트워크별 고정 delay(Ethereum 12 · Base 50 · Solana 8). Tier-2는 `N/A` | 문서에 있다. **다시 물으면 안 읽은 것으로 보인다** |
| `externalId`가 응답·웹훅에 실리나 | 요청에 넣으면 그대로 실린다 | 해소 |
| `WalletHistoryEvent`에 ID가 있나 | 없고 놓친 필드도 없다. `network+txHash+index`가 유일 보장 키이며 우리 방식과 동등 | 해소 — 남은 건 콘솔 검색뿐 |
| 재제출 없는 확인 경로 | 웹훅 스트림 재구성. **우리가 이미 한다** | 해소 |
| `retryOf`·자동 재전송 | 새 고유 ID + `retryOf`, 5회·24시간·백오프, `deliveryFailed=true` 조회 | 해소 |

## 왜 이 순서인가

| # | 문의 | 없어서 생긴 비용 |
|---|---|---|
| 1 | `Failed`의 체인 도달 여부 | 실패한 출금의 **재시도를 못 연다**. `422`로 거절하고 사람이 개입 |
| 2 | 임계 설정·reorg 알림·컨펌 수 | **위탁 RPC를 직접 운영**. head 실패 시 확정 보류 |
| 3 | `index`·`from` 실제 보장 | 없으면 **처리 보류**(자금 멈춤) |
| 4 | `externalId` 조회 | 재제출이 마지막 회수 수단이라 백그라운드에 못 연다 |
| 5 | 콘솔 검색 | 고객 문의 대응 시 `txHash`로만 대조 |
| 6 | 웹훅 수동 재전송 | 최대 재시도 뒤 놓친 건은 이력 조회로 회수해야 함 |
| 7 | 명세 보완 | 형식·단위를 가정하고 방어 코드로 막는 중 |

**1번이 가장 크다.** 없는 걸 만들어 달라기 전에 **있는 걸로 되는지 먼저 묻는다** — `dateBroadcasted`·`txHash`의 부재 해석이다.

**nonce를 받아 우리가 확인하겠다는 제안은 뺐다.** nonce의 온체인 결말을 보려면 **노드를 직접 조회**해야 하는데,
확정 판정용 위탁 RPC 하나도 걷어낼 수 있는지 검토 중인 마당에 의존을 하나 더 들이는 방향이다.
판정은 **벤더가 알려주는 값으로** 서야 한다.

## 다른 수탁 벤더(Fireblocks)에서는

7건 중 다섯이 그쪽에서는 벤더가 주는 값이다. **전달하지 않는다** — 협상 재료가 아니라
"우리 요구가 업계 표준 범위 안"이라는 내부 확신의 근거다.

| # | Fireblocks |
|---|---|
| 1 | `subStatus`(`SMART_CONTRACT_EXECUTION_FAILED` 등)와 `txHash` 유무로 구분 |
| 2 | 알림에 `numOfConfirmations` |
| 4 | `GET /v1/transactions/external_tx_id/{externalTxId}` — 문서가 응답 유실 시 이 조회를 **권장 절차로 명시** |
| 5 | 입금에도 벤더 tx id |
| 6 | `POST /v1/webhooks/{id}/notifications/resend_failed` |

---

# 보낼 본문

안녕하세요. 수탁형 지갑의 온체인 자산 이동을 Dfns로 연동하고 있습니다.

공개 문서로 확인할 수 있는 것은 먼저 확인했고(`Confirmed`의 confirmation delay, `externalId` 반영,
`WalletHistoryEvent`의 식별자 부재, 웹훅 자동 재전송 규칙), **문서 범위를 벗어난 것만** 문의드립니다.

## 1. `Failed` 전송이 체인에 나갔는지 판단하는 방법

실패한 출금을 재시도해도 되는지 판단해야 합니다. 같은 `externalId` 재제출은 기존 실패 엔티티를 돌려주고,
새 `externalId`로 보내면 **이미 체인에 나간 건일 때 이중 지급**이 됩니다. 지금은 보수적으로 재시도를 막고 있습니다.

- `Failed` 전송에서 **브로드캐스트 여부를 판단할 방법**이 있을까요? `dateBroadcasted`나 `txHash`의 부재로 판단해도 되는지 알고 싶습니다.
- 없다면 `TransferRequest`에 `onChainSubmitted`나 `failureStage` 같은 **구분 필드를 추가하실 계획**이 있는지 궁금합니다.

## 2. 확정 임계·reorg 알림·컨펌 수

`Confirmed`가 네트워크별 고정 confirmation delay 경과라는 것은 확인했습니다. 세 가지를 문의드립니다.

- **임계를 고객이 설정**할 수 있을까요? 저희는 네트워크별 자체 임계를 운영합니다.
- `Confirmed` 이후 재구성(reorg)으로 **뒤집히는 경우 알림**을 제공하실 계획이 있을까요?
- `WalletHistoryEvent`에 **컨펌 수**를 실어 주실 수 있을까요? 현재 깊이를 알 수 없어 운영 조사에서 대조가 어렵습니다.

## 3. `index`와 `from`이 실제로 항상 채워지나요

스키마상 `WalletHistoryEvent.index`와 `NativeTransfer`·`SplTransfer`의 `from`이 optional인 것은 확인했습니다.
**실제 인덱서가 이 값들을 항상 채워 보내는지** 알고 싶습니다.

- `index`가 **멀티 트랜스퍼가 아닌 트랜잭션에서는 오지 않는다**고 보면 될까요? 그렇다면 그 부재 자체를 "이 트랜잭션에는 이동이 하나"라는 뜻으로 읽어도 될까요?
- `from`이 비어 오는 경우가 실제로 있나요?
- 보장된다면 스키마를 `required`로 올려 주실 수 있을까요?

> 저희는 `network + txHash + index`로 거래 ID를 만들고 `from`을 입금 이벤트에 싣습니다.
> 둘 중 하나라도 없으면 자금 이동 처리를 멈추고 사람이 확인합니다.

## 4. `externalId`로 전송을 찾는 경로

List Transfers에 필터가 없고 Get Transfer가 `(walletId, transferId)`를 요구하는 것은 확인했습니다.

- `externalId` 필터나 전용 조회 경로를 추가하실 계획이 있을까요?
- `walletId` 없이 `transferId`만으로 조회하는 경로도 함께 문의드립니다.

> 제출 응답을 잃었을 때 웹훅으로 상태를 재구성하고 있고, 그것으로 대부분 해결됩니다.
> 다만 웹훅까지 놓친 경우의 마지막 수단이 **같은 본문 재제출**뿐이라, 이는 자금 이동 시도이기도 해서 자동화하지 못하고 있습니다.

## 5. 콘솔에서 거래를 찾는 방법

입금은 벤더 전송 ID가 없어 저희가 `network + txHash + index`로 ID를 만들어 고객에게 노출합니다.
운영 문의가 들어오면 **그 ID로는 Dfns 콘솔에서 검색되지 않아** `txHash`로만 대조합니다.

- 콘솔에서 `txHash` 외의 기준으로 검색할 방법이 있을까요?
- 또는 `WalletHistoryEvent`에 안정적인 ID를 추가하실 계획이 있을까요?

## 6. 웹훅 수동 재전송

자동 재전송(최대 5회·24시간)과 `deliveryFailed=true` 조회는 확인했습니다.

- 최대 재시도에 도달한 이벤트를 **수동으로 다시 받을 방법**이 있을까요? 계획이 있는지도 궁금합니다.

> 지금은 상한을 넘긴 건을 `GET /wallets/{walletId}/history`로 회수하는 경로를 만들 계획입니다.

## 7. 명세 보완 요청

- `WalletHistoryEvent.timestamp`의 **형식·시간대**를 명세에 적어 주실 수 있을까요? 예시는 ISO 8601 UTC로 보이는데 명문이 없어 저희는 웹훅 envelope의 `date`를 쓰고 있습니다.
- `WalletHistoryEvent.value`가 **최소 단위 정수 문자열**이 맞는지도 명세에 적어 주시면 좋겠습니다.

## 그 밖에 확인하고 싶은 동작

| 대상 | 질문 |
|---|---|
| `wallet.transfer.*` · `wallet.blockchainevent.*` | 같은 전송에 대해 둘 다 오는지, 도착 순서와 간격 |
| 관리 지갑 간 이동 | 발신·수신 `WalletHistoryEvent`가 둘 다 오는지 |
| `POST /wallets/{walletId}/transfers` | 같은 본문 재제출이 **앞 제출이 진행 중일 때도** `200`인지 |
| 같은 엔드포인트의 `409` | `error.details.duplicate` 외의 `409` 원인이 있는지 |
| `PUT /wallets/{walletId}/transfers/{transferId}/cancel` | 보장 범위·멱등성·비EVM 지원 계획 |
| Solana `SplTransfer.to` | owner 주소인지 ATA 주소인지 |

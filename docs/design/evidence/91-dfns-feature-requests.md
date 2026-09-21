---
title: Dfns 기능 문의 — 벤더에 보낼 질문과 내부 판단
status: To Do
---

> 대상 명세는 채택본 `dfns-openapi-1.1018.3`. 회신은 받은 뒤 이 문서에 이어 적는다.
> **아래 "보낼 본문"만 전달한다.** 내부 메모는 보내지 않는다.

---

# 내부 메모 — 전달하지 않는다

## 왜 이 순서인가

| # | 문의 | 없어서 생긴 비용 | 우선순위 근거 |
|---|---|---|---|
| 1 | `Failed`의 체인 도달 여부 | 실패한 출금의 **재시도를 못 연다**. `422`로 거절하고 사람이 개입 | 자금 |
| 2 | `Confirmed`의 final 기준 | **위탁 RPC를 직접 운영**해 블록 깊이 계산. head 실패 시 확정 보류 | 자금·운영 |
| 3 | `WalletHistoryEvent`의 ID | 파생 ID를 만들어 고객에 노출 — **벤더 콘솔에서 검색 불가** | 운영 |
| 4 | `externalId` 조회 | 조회가 없어 **재제출이 회수 수단**. 백그라운드에 열지 못함 | 운영 |
| 5 | 세 필드 required | `index`·`from` 없으면 **처리 보류**(자금 멈춤) | 정합성 |
| 6 | `timestamp`·`value` 명세 | envelope `date`로 대체, 단위는 가정 | 정합성 |
| 7 | 웹훅 재전송 | 이력 조회로 회수하는 경로를 따로 만듦 | 운영 |

## 2번 주의 — 문서를 먼저 읽을 것

Dfns 문서는 `Confirmed`를 **final로 명시**한다. "확정을 알려 달라"고 쓰면 이미 주는 것을 요구하는 꼴이다.
우리가 그 표기를 안 믿기로 한 것은 [CLAUDE.md 3절 확정 결정](../../../CLAUDE.md)이고 이유는 reorg 가능성이다.
그래서 물을 것은 **final의 기준**(컨펌 수·네트워크별 차이·설정 가능 여부)과 **뒤집힐 때의 알림**이다.

계약13의 `Confirmed` 서술("벤더 인덱싱 확인")도 벤더 문서보다 약하게 옮겨져 있다 — 별도로 대조할 것.

## 다른 수탁 벤더(Fireblocks)에서는

7건 중 6건이 그쪽에서는 벤더가 주는 값이다. **이 사실은 전달하지 않는다** — 협상 재료가 아니라
"우리 요구가 업계 표준 범위 안"이라는 내부 확신의 근거다.

| # | Fireblocks |
|---|---|
| 1 | `subStatus`(`SMART_CONTRACT_EXECUTION_FAILED` 등)와 `txHash` 유무로 구분 |
| 2 | 알림에 `numOfConfirmations` — 임계를 우리가 정해 비교 |
| 3 | 입금에도 벤더 tx id |
| 4 | `GET /v1/transactions/external_tx_id/{externalTxId}` — 문서가 응답 유실 시 이 조회를 **권장 절차로 명시** |
| 5 | `amountInfo.amount`·`source`·`destination` 제공 |
| 7 | `POST /v1/webhooks/{id}/notifications/resend_failed` |

---

# 보낼 본문

안녕하세요. 수탁형 지갑의 온체인 자산 이동을 Dfns로 연동하고 있습니다.
구현하면서 명세만으로 판단이 서지 않은 부분이 있어 문의드립니다.
현재 저희가 우회하고 있는 방식도 함께 적었으니, 더 나은 방법이 있으면 알려 주시면 감사하겠습니다.

## 1. `TransferRequest`의 `Failed` 상태

`status: "Failed"`가 **브로드캐스트 전 실패와 온체인 실행 실패를 모두** 나타내는 것으로 이해하고 있습니다.

- 이 둘을 **구분할 수 있는 필드**가 `TransferRequest`에 있을까요? 저희가 놓친 것이 있다면 알려 주세요.
- 없다면, `onChainSubmitted: boolean`이나 `failureStage` 같은 필드를 추가하실 계획이 있는지 궁금합니다.
- 또는 실패한 전송에도 `txHash`·`nonce`가 채워진다면 저희가 온체인 결말을 직접 확인할 수 있습니다. 가능할까요?

> 저희는 실패한 출금의 재시도 가능 여부를 판단해야 하는데, 구분이 안 되면 이미 체인에 나간 건에 새 키를
> 발급해 이중 지급이 될 수 있습니다. 지금은 보수적으로 재시도를 막고 사람이 확인하는 방식입니다.

## 2. `Confirmed`가 뜻하는 final의 기준

[Webhook Events 문서](https://docs.dfns.co/api-reference/webhook-events)에서 `Included`는 pre-confirmation,
`Confirmed`는 **final**로 표시된다고 읽었습니다. 그 기준을 좀 더 알고 싶습니다.

- `Confirmed`는 **컨펌 몇 개** 기준인가요? 네트워크마다 다른가요?
- 그 임계를 **고객이 설정**할 수 있나요? 저희는 네트워크별 자체 임계를 운영하고 있어 맞출 수 있으면 좋겠습니다.
- `Confirmed` 이후 재구성(reorg)으로 **뒤집히는 경우** 어떤 알림이 오나요? 지금까지 그런 사건을 받아 본 적이 없습니다.
- `WalletHistoryEvent`에 **컨펌 수**를 함께 실어 주실 수 있을까요? 있으면 저희 임계와 직접 비교할 수 있습니다.

> 저희는 수탁 자산이라 확정 기준을 스스로 증명해야 해서, 지금은 `blockNumber`와 체인 head의 깊이를
> 별도 RPC로 계산하고 있습니다. Dfns가 이미 체인을 인덱싱하고 계시니, `Confirmed`의 기준이 저희 임계와
> 맞거나 컨펌 수를 받을 수 있다면 그 RPC 의존을 없앨 수 있겠습니다.

## 3. `WalletHistoryEvent`의 식별자

`TransferRequest`에는 `id`(`xfr-…`)가 있는데 `WalletHistoryEvent`에는 식별자 필드가 보이지 않습니다.

- 저희가 놓친 필드가 있을까요? 없다면 **안정적인 ID**를 추가하실 계획이 있는지 궁금합니다.
- 같은 이동을 이력 조회로 다시 읽어도 같은 값이면 충분합니다.

> 입금은 전송 요청이 없어 ID가 없으므로, 저희가 `SHA-256(network · txHash · index)`로 값을 만들어
> 고객에게 노출하고 있습니다. 그런데 이 값은 Dfns 콘솔에서 검색되지 않아, 운영 문의가 들어오면
> `txHash`로만 대조할 수 있습니다.

## 4. `externalId`로 전송 찾기

`GET /wallets/{walletId}/transfers`의 query가 `limit`·`paginationToken`인 것으로 확인했습니다.

- `externalId`로 전송을 찾는 방법이 있을까요? 필터 추가나 별도 조회 경로 계획이 있는지 궁금합니다.
- 더불어 `walletId` 없이 `transferId`만으로 단건 조회가 가능하면 도움이 됩니다.

> 제출 응답을 받지 못했을 때 결과를 확인할 방법이 필요합니다. 지금은 멱등 계약에 기대어
> **같은 본문을 다시 제출**해 확인하는데, 재제출은 자금 이동 시도이기도 해서
> 백그라운드 점검에는 쓰지 못하고 사람이 트리거하는 경로에서만 쓰고 있습니다.

## 5. 선택으로 되어 있는 세 필드

아래 세 필드가 `required`가 아닌데, 실제로는 **항상 채워져 오는지** 확인하고 싶습니다.
항상 온다면 `required`로 올려 주실 수 있을지도 함께 문의드립니다.

| 필드 | 없을 때 저희 동작 |
|---|---|
| `WalletHistoryEvent.index` | 여러 이동이 같은 파생 ID가 되므로 처리를 멈춥니다 |
| `WalletHistoryEvent.from` (`NativeTransfer`·`SplTransfer`) | 입금 이벤트를 만들지 못하고 멈춥니다 |
| `WalletHistoryEvent.metadata.asset.decimals` | 최소 단위 금액을 환산할 수 없습니다 |

> `index`는 3번의 ID가 제공되면 저희에게는 필요 없어집니다.

## 6. 명세 확인 두 가지

- `WalletHistoryEvent.timestamp`의 **형식과 시간대**가 명세에 보이지 않습니다. 웹훅 envelope의 `date`와 같은 형식으로 보면 될까요?
- `WalletHistoryEvent.value`가 **최소 단위 정수**가 맞는지 확인하고 싶습니다.

## 7. 웹훅 재전송

수동 재전송 API가 없고 이력 보존이 31일인 것으로 확인했습니다.

- 알림 ID나 기간으로 **재전송을 요청하는 방법**이 있을까요? 계획이 있는지도 궁금합니다.

> 지금은 알림을 놓치면 `GET /wallets/{walletId}/history`로 회수하는 경로를 따로 두고 있습니다.

## 그 밖에 확인하고 싶은 동작

명세만으로 판단이 어려워 저희가 가정하고 구현한 부분입니다.

| 대상 | 질문 |
|---|---|
| `wallet.transfer.*` · `wallet.blockchainevent.*` | 같은 전송에 대해 둘 다 오는지, 도착 순서와 간격은 어떤지 |
| 관리 지갑 간 이동 | 발신·수신 `WalletHistoryEvent`가 둘 다 오는지 |
| `POST /wallets/{walletId}/transfers` | 같은 본문 재제출이 **앞 제출이 진행 중일 때도** `200`인지 |
| 같은 엔드포인트의 `409` | `error.details.duplicate` 외의 `409` 원인이 있는지 |
| `PUT /wallets/{walletId}/transfers/{transferId}/cancel` | 보장 범위와 멱등성, 비EVM 지원 계획 |
| Solana `SplTransfer.to` | owner 주소인지 ATA 주소인지 |

---
title: Dfns 기능 요청 — 벤더에 제안할 항목
status: To Do
---

> 벤더에 보낼 **제안 초안**이다. 각 항목은 현재 구현에서 우회한 자리와 1:1로 대응하며, 근거는 [계약13](../13-dfns-contracts.md)에 있다.
> 대상 명세는 채택본 `dfns-openapi-1.1018.3`. 전달·회신 결과는 회신을 받은 뒤 이 문서에 이어 적는다.

수탁형 지갑의 온체인 자산 이동을 Dfns로 처리하고 있습니다. 아래는 **없어서 저희가 우회한 것**들이고,
각 항목에 **무엇을 고쳐야 하는지**(스키마인지 엔드포인트인지)와 그 우회가 만든 실제 비용을 적었습니다. 우선순위 순입니다.

> `TransferRequest`·`WalletHistoryEvent`는 **스키마**입니다. 여러 엔드포인트와 웹훅이 같은 스키마를 쓰므로,
> 필드 하나를 더하면 그 자리들이 함께 해결됩니다.

---

## 한눈에 — 다른 수탁 벤더(Fireblocks) 대비

저희는 같은 업무를 Fireblocks로도 운영합니다. 아래 대부분은 **거기서는 벤더가 주는 값**이라,
Dfns 경로에서만 저희가 대신 만들거나 우회하고 있습니다.

| # | 요청 | Fireblocks | Dfns |
|---|---|---|---|
| 1 | 실패가 체인에 나갔는지 | `subStatus`(`SMART_CONTRACT_EXECUTION_FAILED` 등)와 `txHash` 유무로 구분됨 | 구분 필드 없음 |
| 2 | 확정 판정 | 알림에 `numOfConfirmations` — 임계와 비교만 함 | 컨펌 수 없음 → **우리가 RPC 운영** |
| 3 | 온체인 이동의 ID | 입금도 벤더 tx id를 줌 | ID 없음 → **우리가 파생 ID 생성** |
| 4 | 요청 키로 조회 | `GET /v1/transactions/external_tx_id/{externalTxId}` | 필터·경로 없음 → 재제출로 회수 |
| 5 | 금액·주소 필드 | `amountInfo.amount`·`source`·`destination` 제공 | 일부 변형에서 선택 |
| 7 | 웹훅 재전송 | `POST /v1/webhooks/{id}/notifications/resend_failed` | 수동 retry 없음(보존 31일) |

6번(명세 보완)만 Fireblocks 비교 대상이 아닙니다.

## 1. [스키마] `TransferRequest` — `Failed`가 체인에 나갔는지 알려 주세요

**고칠 곳**: `TransferRequest` 스키마 — 필드 하나를 더하면 아래 **네 자리가 함께** 해결됩니다.

| 쓰이는 곳 | |
|---|---|
| `POST /wallets/{walletId}/transfers` | Transfer Asset 응답 |
| `GET /wallets/{walletId}/transfers/{transferId}` | Get Transfer 응답 |
| `GET /wallets/{walletId}/transfers` | List Transfers 응답 |
| `wallet.transfer.*` 웹훅 | `data.transferRequest` |

**요청**: 아래 중 하나
- `onChainSubmitted: boolean` 또는 `failureStage: "PRE_BROADCAST" | "ON_CHAIN"` 필드 추가
- 또는 실패한 전송에도 `txHash`·`nonce`를 채워 주세요 — 저희가 온체인 결말을 직접 확인하겠습니다
- 또는 `POST /wallets/{walletId}/transfers/{transferId}/retry` 처럼 **재시도 안전성을 벤더가 보증하는 엔드포인트**

> **없어서**: `status: "Failed"`가 브로드캐스트 전 실패와 온체인 실행 실패를 모두 덮고, 둘을 가를 필드가 `TransferRequest`에 없습니다.
> 그래서 **실패한 출금을 재시도할 수 없습니다** — 같은 `externalId` 재제출은 기존 실패 엔티티를 돌려주고,
> 새 `externalId`로 보내면 이중 지급 위험이라 저희는 `422`로 거절하고 사람이 개입합니다.
> (2026-09-18 문의에서 "구분하는 필드가 없다"고 회신받았습니다.)

## 2. [스키마 + 웹훅] `WalletHistoryEvent` — 확정을 알려 주세요

**고칠 곳**: `WalletHistoryEvent` 스키마 — `wallet.blockchainevent.detected` · `wallet.blockchain_event.transfer.included` 웹훅의 `data.blockchainEvent`와
`GET /wallets/{walletId}/history` 응답에 함께 쓰입니다. 새 웹훅 kind 신설은 별도 요청입니다(아래).

**요청**: 아래 중 하나
- `WalletHistoryEvent`에 **`confirmations: number`** 추가
- 또는 네트워크별 확정 임계 설정과 **`wallet.blockchainevent.finalized`** 웹훅 신설
- 함께: reorg로 되돌아갔을 때의 **무효화 웹훅**(예: `wallet.blockchainevent.reverted`)

> **없어서**: 사건에 컨펌 수가 없고 `Included`/`Confirmed`는 reorg 안전성을 뜻하지 않습니다.
> 그래서 **저희가 RPC를 따로 붙여** `blockNumber`와 체인 head의 깊이를 계산합니다.
> Dfns가 RPC를 보는데 저희가 또 RPC를 보는 이중 의존이고, head 조회가 실패하면 확정이 멈춥니다.

## 3. [스키마] `WalletHistoryEvent` — 온체인 이동에 ID를 주세요

**고칠 곳**: `WalletHistoryEvent` 스키마 (2번과 같은 자리)

**요청**: **벤더가 발급한 안정적인 ID 필드**(예: `id`). 같은 이동을 다시 조회해도 같은 값이면 됩니다.

> **없어서**: `TransferRequest`에는 `id`(`xfr-…`)가 있지만 `WalletHistoryEvent`에는 ID가 없습니다.
> 저희는 `SHA-256(network · txHash · index)`로 결정적 ID를 만들어 고객에게 노출하는데,
> 그 값은 **Dfns 콘솔에서 검색되지 않습니다.** 운영 조사는 `txHash`로만 가능합니다.

## 4. [엔드포인트] List Transfers — `externalId`로 찾게 해 주세요

**고칠 곳**: `GET /wallets/{walletId}/transfers` (현재 query: `limit`·`paginationToken`)

**요청**: 아래 중 하나
- query에 **`externalId`** 필터 추가
- 또는 `GET /transfers/by-external-id/{externalId}` 신설
- 함께: `walletId` 없이 **`transferId`만으로** 부르는 단건 조회

> **없어서**: 제출 응답을 잃었을 때 조회로 확인할 방법이 없어, **같은 본문 재제출**을 회수 수단으로 씁니다.
> 멱등 계약 덕에 안전하지만 재제출은 곧 자금 이동 시도라, 백그라운드 점검에는 열지 못했습니다.
> 단건 조회가 `(walletId, transferId)`를 요구하는 것도 같은 이유로 걸립니다 — 지갑을 먼저 알아야 하는데 그건 저희 원장에만 있습니다.

## 5. [스키마] `WalletHistoryEvent` — 세 필드를 `required`로 올려 주세요

| 스키마 · 필드 | 지금 | 없으면 |
|---|---|---|
| `WalletHistoryEvent.index` | 선택 | 한 트랜잭션의 여러 이동이 같은 파생 ID가 되어 **처리를 멈춥니다** |
| `WalletHistoryEvent.from` (`NativeTransfer`·`SplTransfer` 변형) | 선택 | 입금 이벤트를 만들지 못하고 멈춥니다 |
| `WalletHistoryEvent.metadata.asset.decimals` | 선택 · 최상위 동명 필드는 `@deprecated` | 최소 단위 금액을 환산할 수 없습니다 |

> `index`는 3번(벤더 ID)이 제공되면 필요 없습니다.

## 6. [문서] `WalletHistoryEvent` — 명세를 두 군데 보완해 주세요

- **`timestamp`의 형식·시간대** — 서술이 없어 저희는 웹훅 envelope의 `date`를 대신 씁니다
- **`value`의 단위** — 최소 단위인지 명시가 없습니다

## 7. [엔드포인트 신설] 웹훅 재전송

**요청**: 알림 ID 또는 기간으로 재전송하는 엔드포인트(예: `POST /webhooks/{webhookId}/events/{eventId}/retry`)

> **없어서**: 수동 retry가 없고 이력 보존이 31일이라, 알림을 잃으면 **`GET /wallets/{walletId}/history`로 회수하는 경로를 따로** 만들어야 합니다.

---

## 확인만 해주셔도 되는 것

명세에 없어 저희가 **가정하고 구현한** 것들입니다.

| 확인 대상 | 질문 |
|---|---|
| `wallet.transfer.*` · `wallet.blockchainevent.*` | 같은 전송에 대해 **둘 다 오는지**, 도착 순서와 간격은 어떤지 |
| 관리 지갑 → 관리 지갑 이동 | 발신·수신 `WalletHistoryEvent`가 **둘 다 오는지** |
| `POST /wallets/{walletId}/transfers` | 같은 본문 재제출이 **앞 제출이 진행 중일 때도** `200`인지 (문서는 종결 뒤만 분명합니다) |
| 같은 엔드포인트의 `409` | `error.details.duplicate` **외의 `409` 원인**이 있는지 |
| `PUT /wallets/{walletId}/transfers/{transferId}/cancel` | 보장 범위·멱등성·비EVM 지원 계획 (절대 보장이 아니라고 회신받았습니다) |
| Solana `SplTransfer.to` | **owner 주소인지 ATA 주소인지** |

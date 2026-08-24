---
title: 로컬 블록체인 + Fireblocks Stub 통합 테스트 환경
status: To Do
group: 운영 설계
---

Fireblocks 사용 가능 여부와 무관하게 BCM의 벤더 HTTP 계약과 실제 EVM 결과를 반복 검증하는 전용 환경이다.
Stub이 Fireblocks 전체를 흉내 낸다고 주장하지 않고, **실제 체인 결과·벤더 시뮬레이션·실 Fireblocks 전용 영역을 분리**한다.

## 목적과 비목적

이 환경이 검증하는 것은 다음 두 경계를 한 흐름으로 잇는 일이다.

1. 기존 BCM `FireblocksClient`가 사용하는 HTTP 요청·응답, 상태 전이, 웹훅 서명·복구 계약
2. 실제 EVM의 잔액·nonce·gas·receipt·event log와 ERC-20 `approve`·`transferFrom`·batch sweep 결과

다음은 목적이 아니다.

- Fireblocks MPC, DKG, API Co-signer, TAP 평가 엔진을 복제하거나 보안 등가성을 주장하는 일
- 실제 Universal Gasless Relay의 운영·과금·실패 동작을 Stub 결과만으로 보증하는 일
- Fireblocks Sandbox/Testnet 계약 검사를 로컬 Stub으로 대체하는 일
- 네이티브 BandChain이나 비EVM 체인을 1차 범위에 포함하는 일
- BCM 운영 DB·Kafka 데이터를 로컬 체인 `reset`이 함께 지우는 일

운영 Domain Port나 프로덕션 코드에 Stub 전용 분기를 추가하지 않는다. 기존 클라이언트의 Base URL과 EVM RPC 등 외부 설정만 바꾼다.

## 실행 모드 — 세 조합만 허용

벤더 모드와 체인 모드는 프로세스 시작 때 고정한다. Admin에서 실행 중 전환하지 않는다.

| 벤더 모드 | 체인 모드 | 허용 | 용도 |
|---|---|---:|---|
| `STUB` | `LOCAL` | O | 개발자 PC·CI·폐쇄망 일반 Linux 서버의 결정적 통합 테스트 |
| `FIREBLOCKS` | `TESTNET` | O | 실제 Fireblocks Sandbox/Testnet 계약·통합 검사 |
| `FIREBLOCKS` | `MAINNET` | O | 운영 배포. 자동 테스트 기본 대상이 아님 |
| 그 밖의 모든 조합 | - | X | 시작 검증에서 거부. 특히 `FIREBLOCKS+LOCAL`, `STUB+MAINNET` 금지 |

`STUB+LOCAL`은 실제 Fireblocks Secret이나 외부 RPC URL이 들어오면 시작을 거부한다. Stub·Anvil·bootstrap은 loopback 또는 같은 서버의 내부 주소에만 바인딩하고 외부로 공개하지 않는다.

개발자 PC의 `fireblocks`와 `stub` 모드는 BCM PostgreSQL·Kafka 데이터까지 서로 격리한다. 두 모드는 같은 compose volume,
database, Kafka log·consumer offset을 재사용하지 않으며 한 모드에서 만든 네트워크·자산 매핑·계정·거래·Webhook 원장이 다른
모드의 Admin이나 API에 나타나면 안 된다. `.env`의 실 Fireblocks 자격은 `fireblocks` 프로세스에만 주입하고 Stub용 테스트 키·
manifest는 별도 런타임 경로에 둔다. 모드 전환은 반대 모드 프로세스를 종료한 뒤 수행하며, `down`은 선택 모드의 데이터를 보존하고
`purge`는 사용자가 지정하고 확인한 모드의 데이터만 삭제한다.

### 기본 로컬 EVM 카탈로그와 최초 준비

상시 개발용 `STUB+LOCAL`은 다음 두 독립 Anvil과 네 ERC-20 자산을 고정 카탈로그로 제공한다. BCM 네트워크 코드는 업무 표준인
`ETHEREUM`·`BASE`를 사용하며 `ETH`는 화면 설명에서만 쓰는 축약어다.

| BCM 네트워크 | Stub blockchain id | 로컬 chain id | 자산 | Stub asset id | decimals |
|---|---|---:|---|---|---:|
| `ETHEREUM` | `ethereum-local` | `31337` | USDC | `USDC_ETH_LOCAL` | 6 |
| `ETHEREUM` | `ethereum-local` | `31337` | KRWK | `KRWK_ETH_LOCAL` | 6 |
| `BASE` | `base-local` | `31338` | USDC | `USDC_BASE_LOCAL` | 6 |
| `BASE` | `base-local` | `31338` | KRWK | `KRWK_BASE_LOCAL` | 6 |

각 Anvil에는 해당 네트워크의 USDC·KRWK 테스트 컨트랙트를 별도로 배포하고 manifest에 chain id, RPC, 컨트랙트 주소와 code
hash를 기록한다. `local.sh up stub`은 체인·Stub readiness 뒤 BCM 블록체인 카탈로그 동기화 → 네트워크 채택 → 채택 네트워크의
자산 카탈로그 동기화 → 자산 매핑을 멱등하게
완료한 뒤 준비 완료를 출력한다. 일부만 존재하거나 manifest와 DB가 어긋나면 성공처럼 덮어쓰지 않고 어느 네트워크·자산이
불일치하는지 실패한다. Anvil은 프로세스마다 빈 체인에서 다시 시작하지만 같은 seed로 동일한 컨트랙트 주소를 재현하고 새 manifest의
code hash를 검증한다. BCM의 유효한 기존 mapping은 중복 생성하지 않고 재사용한다. 고객 vault·입금 주소·테스트 잔액은 기본
bootstrap에 만들지 않고 명시적인 시나리오가 소유한다.

## BCM이 실제 사용하는 Fireblocks API 계약

지원 범위는 현재 BCM 코드가 호출하거나 파싱하는 항목까지다. 새로운 Fireblocks 기능이 필요해지면 이 표와 계약 테스트를 먼저 바꾼다.

### Vault·자산 카탈로그·잔액

| 기능 | 메서드·경로 | BCM이 보내는 값 | BCM이 읽는 값 | Stub |
|---|---|---|---|---|
| Vault 생성 | `POST /v1/vault/accounts` | `name`, `Idempotency-Key` | `id`, `name` | 상태형 구현 |
| Vault 목록 | `GET /v1/vault/accounts_paged` | `limit=1..500`, 선택 `after` | `accounts[].id/name/assets`, `paging.after` | Admin 읽기·대조용 페이지 구현 |
| Vault wallet·주소 생성 | `POST /v1/vault/accounts/{vaultId}/{assetId}` | `Idempotency-Key`, 본문 없음 | `address`, 선택 `tag` | 결정적 EVM 주소 할당 |
| Vault 자산 잔액 | `GET /v1/vault/accounts/{vaultId}/{assetId}` | 경로 식별자 | `total`, `available`, `pending`, `frozen`, `lockedAmount` | Anvil 상태를 기준으로 산출하되 Fireblocks 잔액 구분은 시뮬레이션 |
| 블록체인 목록 | `GET /v1/blockchains` | `pageSize=500`, 선택 `pageCursor` | 필수 `data[].id/displayName`; 선택 `metadata.deprecated`, `onchain.protocol/chainId/test/signingAlgo`, `next` | 로컬 EVM 카탈로그 |
| 자산 목록 | `GET /v1/assets` | `blockchainId`, `pageSize=1000`, 선택 `symbol/pageCursor` | 필수 `id/blockchainId/displaySymbol`; 선택 `displayName`, `decimals` 또는 `onchain.decimals`, `assetClass`, `onchain.address`, `next` | native·테스트 ERC-20 카탈로그 |

### 거래 제출·조회

두 제출 오퍼레이션은 같은 `POST /v1/transactions`를 사용한다.

| 오퍼레이션 | BCM 요청의 필수 모양 | 선택 값 | Stub 실행 |
|---|---|---|---|
| `TRANSFER` | `operation=TRANSFER`, `externalTxId`, `assetId`, source `VAULT_ACCOUNT`, destination `ONE_TIME_ADDRESS`·`VAULT_ACCOUNT`·`EXTERNAL_WALLET`, 십진 문자열 `amount`, `useGasless` | `note`, `travelRuleMessage`, `replaceTxByHash`, `feeLevel=LOW|MEDIUM|HIGH` | 결정적 EVM 키로 raw transaction을 서명해 Anvil 제출 |
| `CONTRACT_CALL` | `operation=CONTRACT_CALL`, `externalTxId`, 네트워크별 native gas `assetId`, source vault, destination contract `ONE_TIME_ADDRESS`, `amount="0"`, `useGasless`, `extraParameters.contractCallData` | 현재 BCM은 추가 선택 필드를 보내지 않음 | calldata를 그대로 사용해 Anvil contract call 제출 |

성공 응답에서 BCM은 `id`를 필수로 읽는다. `externalTxId`는 멱등·응답 유실 회수 키다. 같은 키와 같은 요청은 같은 벤더 거래를 반환하고, 같은 키에 다른 자금 이동 내용을 허용하지 않는다.

| 기능 | 메서드·경로 | 요청·필터 | BCM이 읽는 응답 |
|---|---|---|---|
| 거래 단건 | `GET /v1/transactions/{txId}` | tx id | 아래 거래 공통 필드. 404는 없음으로 처리 |
| external id 조회 | `GET /v1/transactions/external_tx_id/{externalTxId}` | external id | 거래 공통 필드 또는 404 |
| 거래 목록 | `GET /v1/transactions` | `after`, `before`, 선택 `status`, `sort=ASC|DESC`, `limit=1..500`, 선택 `next`, vault 범위면 `sourceType=VAULT_ACCOUNT&sourceId=...` | 거래 배열, `next-page` 응답 헤더. 요청 vault 밖 거래가 섞이면 BCM이 오류로 거부 |

거래 공통 필드 계약은 다음과 같다.

| 구분 | 필드 |
|---|---|
| 필수 | `id`, `assetId`, `status`, `source.type`, `destination.type`, `amountInfo.amount`, `createdAt`, `lastUpdated` |
| 선택 peer 식별자 | `source.id`, `destination.id` — 요청 vault 범위 검증처럼 해당 흐름이 요구할 때 사용 |
| 상태 판단 | `subStatus`, `numOfConfirmations` — 확인 수가 없으면 조회 응답에서는 0으로 취급 |
| 체인 결과 | `txHash`, `sourceAddress`, `destinationAddress` |
| 회수·귀속 | `externalTxId`, `extraParameters.contractCallData` |
| batch 대사 | `networkRecords[].type/source/destination/destinationAddress/txHash/assetId/netAmount/isDropped` |

Webhook 판단 워커는 여기에 더해 입금에서 `sourceAddress`·`destinationAddress`를 요구한다. 체인에 오르기 전 vault 발신 알림의 `destinationAddress`는 비어 있을 수 있다.

### 수수료·Webhook 운영

| 기능 | 메서드·경로 | BCM 계약 | Stub |
|---|---|---|---|
| 네트워크 수수료 견적 | `GET /v1/estimate_network_fee?assetId=...` | `low`, `medium`, `high` 각각에서 `feePerByte/gasPrice/networkFee/baseFee/priorityFee` 중 하나 이상 | 결정적 시뮬레이션 값. 필요하면 Anvil base fee를 입력으로 쓰되 실제 Fireblocks 견적과 동일하다고 보지 않음 |
| Webhook 조회 | `GET /v1/webhooks/{id}` | `id`, `status=ENABLED|DISABLED|SUSPENDED`, `events` | 상태형 구현 |
| Webhook 활성화 | `PATCH /v1/webhooks/{id}` | 요청 `enabled=true`, 같은 조회 응답 | 상태 변경 |
| 실패 알림 재전송 | `POST /v1/webhooks/{id}/notifications/resend_failed` | 빈 객체, 응답 `total` | 실패 큐 재전송과 건수 반환 |

### Fireblocks API 요청 인증

모든 BCM→Fireblocks 요청에는 `X-API-Key`와 `Authorization: Bearer <JWT>`가 실린다. JWT는 RS256이며 BCM 구현이 사용하는 claim은 `uri`, 매 요청 고유 `nonce`, `iat`, `exp`, `sub`, 원문 body의 SHA-256 `bodyHash`다.

- 기본 `STUB+LOCAL` 경로는 API key·Bearer 존재, JWT 3-part와 필수 claim·요청 URI/body 형태를 검사한다. 로컬 기능 시나리오가 인증 구현 세부에 종속되지 않게 한다.
- 별도의 작은 인증 계약 테스트는 대응 공개키로 RS256 서명, `sub`, `uri`, `nonce` 재사용, `iat/exp`, `bodyHash` 불일치를 엄격히 검사한다.
- 실제 Fireblocks 계약 검사는 공식 서버가 최종 판정한다. Stub 통과는 실벤더 인증 호환성의 증명이 아니다.

근거: [Signing a request — JWT structure](https://developers.fireblocks.com/reference/signing-a-request-jwt-structure).

## 거래 상태와 Webhook 계약

Stub은 Anvil 결과를 관찰해 Fireblocks 원어 상태를 만들고, BCM은 이를 공통 `TxStatus`로 번역한다.

| Fireblocks 원어 | BCM 상태 | 로컬 발생 기준 |
|---|---|---|
| `SUBMITTED`, `PENDING_SIGNATURE`, `QUEUED`, `BROADCASTING` | `SUBMITTED` | 접수·서명·전파 대기 상태를 시뮬레이션 |
| `CONFIRMING` | `CONFIRMED` | Anvil receipt가 생겼고 finality 임계 전 |
| `COMPLETED` | 확인 수가 정책 임계 이상이면 `FINALIZED`, 아니면 `CONFIRMED` | receipt와 블록 진행을 실제 관찰 |
| `REJECTED`, `BLOCKED` | `REJECTED` | 주입한 벤더 거부 결과. TAP 자체를 평가한 결과가 아님 |
| `FAILED` | `FAILED` | RPC 실패·revert·drop 또는 주입한 벤더 실패 |

`AUTO_FREEZE`, `FROZEN_MANUALLY`, `REJECTED_AML_SCREENING` subStatus는 raw status보다 우선해 `REJECTED`로 번역한다. 이 값을 주입해 BCM 동결 처리는 검증할 수 있지만 실제 Fireblocks 스크리닝·TAP 정책을 검증하는 것은 아니다.

Stub Webhook은 기존 BCM 수신 경로를 그대로 지난다.

- 헤더: `Fireblocks-Webhook-Signature`
- 서명: Stub 전용 RSA 키의 RS512 detached JWS, `kid`로 Stub JWKS 공개키 선택, 수신 원문 byte 서명
- 지원 이벤트: `transaction.created`, `transaction.status.updated`, `transaction.approval_status.updated`, `transaction.network_records.processing_completed`
- 최상위 필수 값: `id`, `eventType`; `data.id`는 알림의 vendor transaction id
- 판단 워커 필수 값: `data.id`, `assetId`, `source.type`, `amountInfo.amount`, `status`, `numOfConfirmations`, `createdAt`
- 중복·역순·유실·지연, 잘못된 서명, 낯선 `kid`, 재전송은 실패 시나리오에서 제어할 수 있어야 한다.

Webhook 재시도 간격과 실제 벤더 스케줄러를 완전히 복제하지 않는다. Stub은 결정적 가상 시각 또는 명시적 trigger로 재현하며, BCM의 수신·중복 제거·복구 결과를 검증한다. 실제 재시도 정책은 [공식 Responses & retries](https://developers.fireblocks.com/reference/webhooks-gettingstarted-responsesretries)와 실벤더 계약 검사에서 확인한다.

## 오류·응답 유실 계약

Stub은 정상 응답 외에 다음을 endpoint·externalTxId·호출 횟수 기준으로 결정적으로 주입한다.

| 주입 | BCM에서 확인할 계약 |
|---|---|
| `400` | 거래 제출은 즉시 중복/검증 오류로 단정하지 않고 `externalTxId` 조회로 소유권 회수 |
| `401`, `403` | 인증·권한 오류, 자동 재시도하지 않음 |
| `404` | 거래 단건·external id 조회에서 없음 반환, 그 밖의 endpoint는 오류 |
| `409`, `422` | 제출의 확정적 거절로 처리 |
| `429` + `Retry-After` | BCM 클라이언트의 유일한 HTTP 자동 재시도 대상. 상한 내 backoff |
| `5xx`, 연결 timeout | 결과 미확정 오류와 재대사 경계 검증 |
| 체인 제출 성공 뒤 HTTP 응답 유실 | 재요청 시 `externalTxId` 조회로 같은 tx를 회수하고 이중 제출하지 않음 |
| 장기 pending·Webhook 유실 | 단건·목록 대사와 운영 경보·복구 흐름 검증 |

Stub의 오류 body는 BCM이 소비하는 최소 형식만 제공한다. Fireblocks 전체 오류 코드와 문구를 복제하지 않으며, 실제 목록은 [공식 API error codes](https://developers.fireblocks.com/reference/api-error-codes)에서 별도 확인한다.

## 실제·시뮬레이션·미지원 경계

| 분류 | 로컬에서 검증하는 것 | 해석 |
|---|---|---|
| `REAL_LOCAL` | Anvil 블록·receipt·log, EVM 주소·잔액·nonce·gas, native/ERC-20 전송, allowance, contract call, revert, batch sweep 결과 | 실제 EVM 실행 결과. 선택한 Anvil 버전·fork 규칙의 범위 |
| `SIMULATED_VENDOR` | Vault 객체·카탈로그, Fireblocks transaction id와 상태 머신, HTTP 오류·429, Webhook 구독·서명·재전송, `networkRecords`, BLOCKED/REJECTED, 수수료 응답 | BCM 벤더 어댑터와 복구 계약 검증용. Fireblocks 내부 구현의 증거가 아님 |
| `REAL_FIREBLOCKS_ONLY` | MPC/DKG, API Co-signer, TAP 정책 평가, 실제 custodial signing, 실제 Webhook 스케줄·rate limit, Fireblocks 내부 이체 semantics, 실제 Universal Gasless Relay·청구 | 사용자 승인 아래 Sandbox/Testnet 또는 제한된 운영 계약 검사로만 확인 |
| `UNSUPPORTED` | 네이티브 BandChain, 비EVM 일반화, Fireblocks cold workspace, Policy Editor/TAP Admin API, 실 Travel Rule·screening | 별도 설계·구현 없이는 테스트하지 않음 |

EVM 네트워크의 BAND ERC-20은 일반 ERC-20으로 포함할 수 있다. 네이티브 BandChain 서명·수수료·finality는 이 환경의 범위가 아니다.

Universal Gasless는 Anvil Prague와 사용 라이브러리의 EIP-7702 type-4 지원을 먼저 검증한다. 로컬에서 확인하는 것은 delegation·fee payer·nonce/replay/deadline·revert 같은 프로토콜 결과와 BCM 관찰값이다. Fireblocks MPC·TAP·Relay가 같은 방식으로 작동한다는 결론은 내리지 않으며, 확정된 `approve + transferFrom` batch sweep을 임의로 EIP-7702 직접 pull로 바꾸지 않는다.

## 전체 시스템 실행 원장과 진행 상태

Phase 11의 개별 계약 테스트와 별도로 Admin → BCM API/Webhook/BAT → PostgreSQL·Kafka → Stub → Anvil → Webhook·대사를 실제
프로세스로 조립한 전체 시스템 suite를 둔다. 자동 suite는 `STUB+LOCAL`만 사용하고 실제 Fireblocks 계약 검사는 아래 승인 경계의
수동 lane으로 분리한다.

### 실행 명령과 상태

개발자와 CI의 공통 진입점은 다음 한 명령이다.

```text
./scripts/system-test.sh smoke
./scripts/system-test.sh full
./scripts/system-test.sh status [runId]
./scripts/system-test.sh logs [runId] [component]
./scripts/system-test.sh stop [runId]
```

실행기는 의존 도구·포트·전용 DB/topic·component readiness를 단계로 확인하고 각 전이마다 다음 한 줄을 즉시 출력한다.

```text
[3/10] BCM API 시작 OK 4.2s
```

실패 출력에는 runId, 실패 단계, 안전한 오류 코드·요약, 재시도 가능 여부, 다음 조치, Admin 진단 URL, artifact 경로를 포함한다.
TTY 색상·spinner가 있어도 CI의 평문 한 줄 형식과 의미는 바뀌지 않는다.

| 대상 | 상태 |
|---|---|
| 실행 | `PENDING`, `RUNNING`, `PASSED`, `FAILED`, `ABORTED` |
| 단계 | `PENDING`, `RUNNING`, `PASSED`, `FAILED`, `ABORTED` |
| component | `STARTING`, `UP`, `DOWN`, `FAILED` |

runner가 현재/전체 단계와 정수 percent, 현재 단계, 마지막 성공 단계, UTC `Z` 시각을 계산한다. 프론트나 개별 component가 전체
진행률을 추측하지 않는다. runId는 ASCII 영숫자·`-`·`_`만 사용하는 최대 64자의 불투명 식별자다.

### Artifact와 보안

실행별 정본은 서비스 저장소의 Git 제외 경로 `build/system-test/<runId>/`다.

```text
run.json                 현재 snapshot. 임시 파일 후 rename으로 원자 교체
events.jsonl             상태 전이 append-only 원장
logs/<component>.log     로컬 원문 component 로그
results/                 assertion·요약 결과
```

`run.json`과 event에는 suite, 상태, 단계·component health, 시작·갱신·완료 시각, 안전한 실패 요약·다음 조치, 검증 분류와
연관 `requestId/externalTxId/submissionId/vendorTxId/txHash/eventId/executionId/jobRunId`를 기록한다. 로컬 `SCENARIO`는 실제 생성한
`accountId/address`도 결과 확인용으로 기록한다. 그 밖의 값을 임의로 추가하지 않는다. Secret, API key, JWT,
PEM·key 내용, Webhook 서명·raw payload, HTTP 원문 body는 기록하지 않는다. component 원문 로그는 브라우저·BFF 응답에 싣지
않고 로컬 `logs` 명령과 CI 접근 제한 artifact에서만 본다.

이 로컬 artifact 계약은 운영 중앙 로그의 포맷과 보존 기간을 정하지 않는다. 운영 프로세스의 구조화 JSON 로그와 중앙 수집·삭제는
[운영 로그 정책](11-operational-log-policy.md)을 따른다.

실행기는 새 run 시작 때 완료된 오래된 artifact를 정리해 최근 20건만 남기고 진행 중 실행은 삭제하지 않는다. 로컬 실패는 artifact를
보존하되 프로세스를 기본 정리한다. `--keep-on-failure`를 명시하면 조사할 component를 유지하며 `stop`으로 정리한다. CI는 성공·실패와
무관하게 프로세스를 정리하고 artifact를 업로드한다.

### Suite와 추적 계약

`smoke`는 다음 최소 세로줄을 한 번에 검증한다.

1. 전용 PostgreSQL·Kafka, Anvil, Stub, BCM API, BCM Webhook, Admin을 각각 기동하고 readiness를 확인한다.
2. BCM 공개 API로 계정·주소를 만들고 Stub 제어면으로 테스트 ERC-20 입금을 주입한다.
3. 실제 Anvil receipt와 Stub 서명 Webhook을 거쳐 BCM 거래를 `FINALIZED`로 수렴시킨다.
4. 고객 Kafka event와 Admin 거래 조사에서 같은 업무 식별자·금액·상태를 확인한다.
5. component를 정리하고 전용 실행 자원만 제거한다.

`full`은 smoke에 출금, gasless, batch sweep 부분 성공과 명시적 BCM BAT 1회 대사, 429·timeout·응답 유실, Webhook
중복·역순·유실·재전송, reset 뒤 BCM PostgreSQL·Kafka 불변 검증을 더한다. BAT는 일반 scheduler 시각을 기다리지 않고 테스트가
소유한 명시적 1회 job으로 실행해 jobRunId를 같은 runId에 연결한다.

진단 상관관계는 `runId/stepId → requestId → externalTxId/submissionId → vendorTxId → txHash → eventId →
executionId/jobRunId` 순서다. 실제로 생긴 식별자만 기록하며 fixture가 존재하지 않는 운영 ID를 만들지 않는다. 각 assertion은
`REAL_LOCAL` 또는 `SIMULATED_VENDOR`를 표시하고 `REAL_FIREBLOCKS_ONLY`를 통과했다고 보고하지 않는다.

시스템 테스트 실행기와 별개로, 상시 개발 환경의 수명은 `./scripts/local.sh` 가 관리한다 — `configure [fireblocks]` · `up
[fireblocks|stub]` · `status` · `stop [api|webhook|admin]` · `test deposit` · `logs [api|webhook|admin|chain|stub|infra]` ·
`down` · `reset` · `purge`.
기본 모드는 fireblocks 고, stub 은 실 Fireblocks 자격증명 없이 결정적 로컬 체인을 쓴다. `system-test.sh` 가 runId 단위 검증
실행을 다룬다면 `local.sh` 는 환경 기동·점검·정리를 다룬다.

`local.sh up fireblocks`는 로컬 PostgreSQL·Kafka를 준비한 뒤 API·Webhook·Admin을 시작하기 전에 실제 BCM
`FireblocksClient`로 `GET /v1/blockchains` 읽기를 한 번 수행한다. 이 확인은 `.env`의 API key·PKCS#8 private key·공식 Base URL로
만든 요청 JWT가 실제 Fireblocks에서 받아들여지는지만 판정하며 거래 생성 권한·TAP·Webhook JWKS까지 검증했다고 해석하지 않는다.
성공하면 같은 읽기 결과를 로컬 블록체인 카탈로그에 동기화하고 다음 component를 기동한다. API가 준비되면 로컬 TESTNET 지원 목록의
Ethereum Sepolia(`11155111` → `ETHEREUM_SEPOLIA`)와 Base Sepolia(`84532` → `BASE_SEPOLIA`)를 멱등하게 연결하고, 두 네트워크의
자산 카탈로그 one-shot 동기화까지 완료한 뒤 Admin 준비 완료를 출력한다. 이 단계는 자산 매핑을 자동 등록하지 않으며 사용자가
Assets 화면에서 `USDC`를 검색해 Fireblocks assetId와 컨트랙트 주소를 비교·선택하게 한다. 지원 네트워크가 없거나 기존 연결이
고정 목록과 어긋나거나 자산 동기화가 실패하면 성공처럼 계속하지 않는다. 인증·연결·응답 해석 중 하나라도 실패하면
애플리케이션 기동 전에 중단한다. 콘솔에는 Secret·JWT·벤더 응답 원문을 내보내지 않고 권한이 제한된
`build/local/fireblocks-preflight.log` 경로와 안전한 다음 조치만 안내한다.

로컬 시작 bootstrap의 자산 동기화는 위 두 지원 네트워크만 대상으로 해 `up fireblocks` 완료 시간을 Fireblocks 전체 카탈로그 크기에
묶지 않는다. 모든 네트워크의 읽기 전용 자산 후보가 필요하면 `local.sh sync assets`로 전체 one-shot을 명시 실행한다. 이 실행도
미지원 네트워크를 자동 채택하거나 자산 매핑을 만들지 않는다.

이미 `local.sh up stub`으로 기동한 개발 환경의 빠른 입금 점검은 `local.sh test deposit`으로 제공한다. 이 helper는
`STUB+LOCAL`에서만 실행하며 각 단계의 시작·성공·실패와 다음 조치를 평문으로 출력한다. 공개 BCM API로 계정·주소를 준비하고,
Stub 제어면으로 Anvil 입금을 주입한 뒤 독립 BCM Webhook 수신, `FINALIZED`, Kafka event까지 확인한다. 운영 코드·Domain Port에
테스트 분기를 추가하지 않고 Fireblocks 실환경에서는 즉시 거부한다. API key·private key·Webhook 서명·raw payload는 출력하지 않는다.

같은 상시 개발 환경에서 Admin이 시작하는 `asset-catalog`, `customer-vault`, `deposit-success`는 위 helper와 동일한 공개 API·Stub
제어 계약을 사용하되 각 실행을 `suite=SCENARIO` 원장으로 남긴다. 자산 카탈로그는 상시 BAT가 자동으로 떠 있다고 표시하지 않는다.
Admin에서 명시적으로 실행하면 블록체인과 채택 네트워크 자산 카탈로그의 one-shot 프로세스 시작·성공·실패를 각각 기록하고, 완료 뒤 후보·채택 네트워크·
mapping을 다시 읽어 결과를 확인한다. 고객 vault는 공개 `POST /accounts`와 `POST /accounts/{accountId}/addresses`로 만들며
Fireblocks 내부 vault를 Admin 전용 우회 경로로 생성하지 않는다.

Admin BFF가 받을 수 있는 것은 고정 시나리오 ID와 길이·문자 allowlist를 통과한 입력뿐이다. BFF는 임의 실행 파일·shell·URL을
받지 않으며 저장소의 고정 Python/shell 진입점을 `ProcessBuilder` 인자 배열로 비동기 기동한다. 동시에 중복 클릭된 같은 로컬
mutation은 하나만 실행하고 나머지는 `409`로 거부한다. 실제 Fireblocks mode, 비-loopback Admin, 기능 비활성 설정에서는 시나리오
카탈로그·실행 route가 존재하지 않는다. 실행 POST는 JSON과 전용 비단순 헤더를 요구하고 요청 `Origin`이 현재 loopback Admin
origin과 정확히 같은지 서버가 검사해, 외부 웹 페이지가 개발자의 로컬 시나리오를 시작하지 못하게 한다.

API와 Webhook의 장애 단위는 독립 검증한다. `stop api` 뒤에도 Webhook health가 유지되고, `stop webhook` 뒤에도 API health가
유지돼야 한다. 중단 중 Stub의 서명 알림은 실패 queue에 남고 Webhook만 재기동한 뒤 `resend_failed`로 BCM 원장과 outbox를
수렴시킨다. 로컬 실행기는 검증된 process group만 신호 대상으로 삼아 Gradle client와 실제 애플리케이션 JVM을 함께 종료한다.

PR CI는 smoke, nightly는 full을 실행한다. 실제 `FIREBLOCKS+TESTNET`은 일반 CI와 이 스크립트의 기본값에 포함하지 않고 사용자
실행별 승인 뒤 기존 golden contract test로만 실행한다. Admin·Stub·Anvil·test-support가 없거나 기동되지 않은 상태에서도 BCM
production 모듈의 빌드·실행 경로가 이 실행 원장에 의존하지 않는지를 아키텍처 테스트로 고정한다.

## 배치와 배포

### 개발자 PC·CI

개발자·CI는 Anvil과 Stub을 테스트 수명에 맞춰 기동하고, 전체 BCM E2E일 때만 Testcontainers의 PostgreSQL·Kafka를 함께 사용한다. 작은 Stub 계약 테스트는 BCM DB·Kafka 없이 실행할 수 있어야 한다.

### 폐쇄망 일반 Linux 서버

원격 서버는 Docker와 인터넷 접속을 요구하지 않는다.

```mermaid
flowchart LR
  API["BCM API"] -->|"Fireblocks HTTP · loopback"| STUB["Fireblocks Stub fat JAR"]
  API -->|"기존 연결"| PG["기설치 PostgreSQL"]
  WH["BCM Webhook"] -->|"기존 연결"| PG
  WH -->|"기존 연결"| KAFKA["기설치 Kafka"]
  STUB -->|"JSON-RPC · loopback"| ANVIL["고정 버전 Anvil"]
  STUB -->|"RS512 Webhook · 내부 주소"| WH

  classDef ours fill:#dbeafe,stroke:#2563eb
  classDef local fill:#fef3c7,stroke:#d97706
  class API,WH,PG,KAFKA ours
  class STUB,ANVIL local
```

폐쇄망 반입물은 연결 환경에서 미리 빌드·검사한 버전 고정 `tar.gz`다. CPU 아키텍처별 Anvil, 전용 JRE·Stub JAR, chain bootstrap, contract artifact·배포 manifest, 설정 예시, systemd unit, start/stop/reset/health-check, checksum·license 정보를 포함한다. 설치·기동 중 외부 다운로드가 발생하면 실패다.

systemd 기동 순서는 Anvil health → chain bootstrap/manifest 검증 → Stub health다. 로컬 장치와 별개로 배포되는 BCM API와 BCM
Webhook은 Stub·JWKS 준비 뒤 각자 기동하며, Fireblocks callback URL은 BCM Webhook listener만 가리킨다. PostgreSQL·Kafka는
패키지에 포함하지 않고 설치·초기화·reset하지 않는다.

## 키와 신뢰 경계

세 키는 목적·알고리즘·보유 프로세스가 다르며 재사용하지 않는다.

| 키 | 알고리즘·용도 | private 보유 | public·설정 |
|---|---|---|---|
| Fireblocks API 요청 키 | RSA, BCM 요청 JWT RS256 | BCM. 로컬에서는 테스트 전용 키만 | Stub strict-auth profile에 public key |
| Webhook 서명 키 | RSA, detached JWS RS512 | Stub만 | BCM은 Stub JWKS URL과 `kid`만 사용 |
| EVM 거래 키 | secp256k1, local raw transaction | Stub/Anvil 테스트 런타임만 | seed manifest에는 주소·체인 결과만 노출 |

- private key·실 Secret은 Git·배포 설정 예시에 넣지 않는다. 설치 또는 테스트 run이 권한 제한된 런타임 경로에 테스트 키를 만들고, CI 키는 실행 종료와 함께 폐기한다.
- `STUB+LOCAL`은 허용된 테스트 key fingerprint/표식을 확인하고 실 Fireblocks API key·private key, 외부 RPC, mainnet chain id를 fail-closed한다.
- 테스트 EVM 계정은 설치·run 때 런타임 경로에 생성하거나 주입한 테스트 전용 seed로 결정적으로 재현한다. seed 값은 Git·배포 예시에 저장하지 않고 reset 사이에만 보존하며, 운영 키·운영 주소를 입력으로 받지 않는다.
- Stub API·JWKS·제어 endpoint는 loopback/서버 내부 전용이다. 제어 endpoint를 BCM 업무망이나 외부 ingress에 공개하지 않는다.

## reset 소유권과 순서

`reset`은 **테스트 벤더·체인 환경만** 초기화한다.

| reset 대상 | 포함 | 제외 |
|---|---:|---:|
| Stub Vault·transaction·externalTxId 멱등 상태 | O | - |
| Stub Webhook queue·실패·재전송·오류 주입 상태 | O | - |
| Anvil snapshot, block/nonce, seed 잔액·ERC-20·contract 배포 | O | - |
| 기존 BCM PostgreSQL | - | X |
| 기존 Kafka topic·offset | - | X |
| 실 Fireblocks workspace·실 체인 | - | X |

안전한 순서는 BCM의 신규 제출·배치 작업 중지 → Stub 진행 중 작업 없음 확인 → Anvil snapshot 복원과 bootstrap manifest 검증 → Stub 상태 초기화·seed 재결합 → health check → BCM 작업 재개다. 활성 트래픽 중 reset은 허용하지 않는다. BCM DB에 남은 tx가 reset된 Stub 상태를 가리킬 수 있으므로, 전체 E2E 초기화는 실행별 전용 DB/topic·run id와 별도 BCM cleanup 절차가 소유한다. 개발자 공유 BCM DB를 유지한 채 Stub만 재기동·reset해도 원장 PK가 충돌하지 않도록 Stub `vendorTxId`는 `externalTxId`에서 결정적으로 파생한다. Webhook `notificationId`는 프로세스 인스턴스 식별자와 단조 증가 순번으로 새 알림마다 유일하고 발생 순서대로 만들며, 재기동 전후 충돌을 피한다. 같은 알림의 재배달은 저장된 payload를 그대로 사용하므로 같은 `notificationId`를 유지한다.

같은 seed와 같은 시나리오 입력은 같은 vault/address/asset/contract와 같은 의도된 상태 전이 순서를 만들어야 한다. 블록 hash처럼 실행 시각에 영향을 받는 값은 동일성 기준에서 제외하고 manifest와 논리 결과를 비교한다.

## 실제 Fireblocks 계약 테스트 승인 경계

로컬 Stub과 별도로 `FIREBLOCKS+TESTNET` golden contract test를 둔다. 기본 실행·일반 CI·폐쇄망 패키지 smoke에서는 호출하지 않는다.

| 등급 | 예 | 기본 정책 |
|---|---|---|
| 읽기 전용 | 인증, blockchain/asset 목록, 기존 거래·Webhook 조회, fee 견적 | 사용자 명시 승인 + 허용 workspace/API user + Secret이 있을 때만 수동 실행 |
| 자원 생성 | 테스트 Vault·wallet 생성 | 실행마다 별도 승인. 이름 prefix·정리 책임·잔존 자원 확인 필수 |
| 자금·정책 영향 | 거래 제출, contract call, Webhook PATCH·실패 알림 재전송 | 작업별 승인. 테스트 자산·수수료·TAP·수신 endpoint·정리 계획을 실행 전에 제시 |
| Mainnet mutation | 실제 자산 이동·정책 또는 Webhook 변경 | 자동화 기본 범위에서 금지. 별도 운영 변경 승인 없이는 실행하지 않음 |

계약 테스트는 응답 전체를 고정하지 않고 BCM이 실제 소비하는 필수 필드·enum·경로·헤더만 대조한다. golden 자료는 Secret, JWT, 서명, 주소 외 민감정보를 제거한 뒤 보관한다. 실제 서버 오류·rate·Webhook 시간 특성은 측정 환경과 시각을 함께 기록한다.

이 승인 경계는 다음 두 사실을 고정한다.

1. **Stub 테스트 통과만으로 Fireblocks 호환 완료라고 판정하지 않는다.**
2. **실 Fireblocks 쓰기 계약 테스트는 사용자가 매번 범위와 비용·자금 영향을 보고 승인한다.**

## 완료 판정

- 이 문서의 API·필드·상태 표마다 BCM 소비 계약 테스트가 있다.
- `STUB+LOCAL`의 정상·실패·응답 유실·Webhook 복구가 실제 Anvil receipt/log와 BCM 원장 결과로 이어진다.
- 잘못된 실행 조합, 실 Secret, 외부 RPC, mainnet chain id가 로컬 모드에서 시작 전에 거부된다.
- 원격 tarball이 Docker·PostgreSQL·Kafka 번들·runtime 다운로드 없이 설치→기동→reset→재기동된다.
- reset 전후 기존 BCM PostgreSQL·Kafka가 변경되지 않음을 검증한다.
- `REAL_LOCAL`, `SIMULATED_VENDOR`, `REAL_FIREBLOCKS_ONLY`, `UNSUPPORTED`가 테스트 리포트에 표시된다.
- smoke/full 실행의 단계·component·연관 ID가 같은 runId artifact와 로컬 Admin 진단에서 일치한다.
- Admin·Stub·Anvil을 제거하거나 기동하지 않아도 BCM production 모듈의 빌드·실행 경로가 정상이다.
- 실제 Fireblocks 계약 테스트는 위 승인 경계 밖에서 실행되지 않는다.

## 공식 계약 링크

- [Create a vault account](https://developers.fireblocks.com/api-reference/vaults/create-a-new-vault-account) · [Create a vault wallet](https://developers.fireblocks.com/api-reference/vaults/create-a-new-vault-wallet) · [Get vault asset balance](https://developers.fireblocks.com/api-reference/vaults/get-the-asset-balance-for-a-vault-account)
- [List blockchains](https://developers.fireblocks.com/api-reference/blockchains-%26-assets/list-blockchains) · [List assets](https://developers.fireblocks.com/api-reference/blockchains-%26-assets/list-assets)
- [Create a transaction](https://developers.fireblocks.com/api-reference/transactions/create-a-new-transaction) · [Get by external transaction id](https://developers.fireblocks.com/api-reference/transactions/get-a-specific-transaction-by-external-transaction-id)
- [Estimate network fee](https://developers.fireblocks.com/api-reference/transactions/estimate-the-required-fee-for-an-asset)
- [Get webhook](https://developers.fireblocks.com/api-reference/webhooks-v2/get-webhook-by-id) · [Update webhook](https://developers.fireblocks.com/reference/updatewebhook) · [Resend failed notifications](https://developers.fireblocks.com/api-reference/webhooks-v2/resend-failed-notifications)
- [Validate Webhook signatures](https://developers.fireblocks.com/reference/validating-webhooks) · [Webhook best practices](https://developers.fireblocks.com/reference/webhooks-best-practices)

window.OPENAPI = {
  "openapi": "3.1.0",
  "info": {
    "title": "Blockchain Manager API",
    "version": "0.15.0",
    "x-curl": true,
    "description": "블록체인 매니저는 사내의 별도 서비스로, 온체인 거래(노드 연동)를 담당한다.\n호출 쪽 백엔드(Service·Admin)는 이 HTTP API 로 계정·주소·잔액·거래를 다루고,\n온체인 상태 변경은 메시지 큐 이벤트로 받는다.\n\n## DAW-CORE 5분 Quickstart\n\n저장소를 clone한 뒤 Docker, Python 3, Foundry 1.7.1(`anvil`·`forge`)을 준비한다. JDK 25는 Gradle toolchain이 내려받으며,\n사내망처럼 자동 다운로드가 막힌 환경에서만 직접 설치한다. 실제 Fireblocks 자격증명 없이\n계약과 온체인 흐름을 확인하려면 저장소 루트에서 다음 한 명령을 실행한다.\n\n```bash\n./scripts/local.sh up stub\n```\n\n준비가 끝나면 이 문서를 `http://127.0.0.1:38080/api-docs/`에서 연다. 상단 Base URL은 자동으로\n`http://127.0.0.1:38080`이 선택된다. 아래 `계정 생성` → `입금 주소 발급` 순서로 예시 값을 수정해 **요청 실행**을 누르면\n실제 BCM과 Fireblocks Stub·Anvil에 같은 계약으로 요청한다. 입금부터 Kafka 이벤트·Admin 조사까지 한 번에 확인하려면\n`./scripts/local.sh test deposit` 또는 Admin의 로컬 시나리오를 사용한다.\n\nDAW-CORE 연동의 최소 구현 범위는 다음 네 가지다.\n\n1. `POST /accounts`의 (`accountType`, `ref`)를 안정적인 업무 키로 유지한다.\n2. `POST /accounts/{accountId}/addresses` 결과를 네트워크별로 저장하고 항목별 실패만 재시도한다.\n3. 출금은 `externalTxId`를 절대 재사용하지 않으며 응답 유실 때 같은 본문으로 재요청한다.\n4. Kafka 이벤트는 `eventId`로 멱등 처리하고 업무 원장 커밋 뒤 `PUT /events/{eventId}/completion`을 호출한다.\n   완료 확인 성공 뒤 Kafka offset을 커밋하며 `FINALIZED` 뒤 새 `eventId`의 `FAILED` 전이도 독립 처리한다.\n\n로컬 Kafka bootstrap 주소는 `127.0.0.1:9092`다. 토픽 이름과 파티션 키, `ChainEvent` 실전 payload는 아래\n**이벤트 (메시지 큐)** 절이 계약 정본이며, HTTP 실행 패널과 같은 문서 안에서 함께 확인한다.\n\n아래 규약은 **모든 엔드포인트에 공통** 적용된다.\n\n## 응답 형식\n\n성공·목록·에러 모두 같은 구조로 돌려준다. `meta.requestId` 로 요청을 추적한다. 스키마 이름은 단건이 `<타입>Response`, 목록이 `<타입>ListResponse` 다.\n\n단일 리소스:\n\n```json\n{\n  \"data\": {\n    \"accountType\": \"CUSTOMER\",\n    \"ref\": \"000123\",\n    \"accountId\": \"acct_018f3d4a-bf70-7c1a-8f2b-3c4d5e6f7890\"\n  },\n  \"meta\": {\n    \"requestId\": \"3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f\"\n  }\n}\n```\n\n페이지네이션 목록:\n\n```json\n{\n  \"data\": [\n    { \"txId\": \"tx-local-986a169a89dbf0713ad01d2d17eebd59360b155bfd42fe0a\", \"status\": \"FINALIZED\", \"amount\": \"1\" }\n  ],\n  \"meta\": {\n    \"requestId\": \"3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f\"\n  },\n  \"pagination\": {\n    \"nextCursor\": \"eyJsYXN0IjoxNzUxMzM2MDAwMDAwfQ\",\n    \"hasMore\": true\n  }\n}\n```\n\n에러:\n\n```json\n{\n  \"error\": {\n    \"code\": \"ACCOUNT_NOT_FOUND\",\n    \"message\": \"account not found\"\n  },\n  \"meta\": {\n    \"requestId\": \"3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f\"\n  }\n}\n```\n\n## 데이터 포맷\n\n- **시각** — ISO 8601, UTC, 밀리초. 예: `2026-07-13T04:05:06.789Z`\n- **금액** — 문자열(decimal). 예: `\"1.5\"`. float 가 아니라 decimal 로 파싱한다.\n- **필드명** — camelCase (`externalTxId` · `numOfConfirmations`)\n- **요청 추적** — 모든 응답에 `meta.requestId`\n- **온체인 해시** — 전파 후 채워짐(그 전엔 null), `txHash`\n\n## 에러 코드\n\n판단은 `error.code` 로 한다.\n\n| 코드 | HTTP | 뜻 |\n|---|---|---|\n| `VALIDATION_FAILED` | 400 | 요청 형식·값이 규약에 안 맞음 |\n| `ACCOUNT_NOT_FOUND` | 404 | 계정 없음 (주소 미발급과 구분) |\n| `ASSET_NOT_SUPPORTED` | 400 | 우리가 지원하지 않는 (네트워크, 토큰) — 요청 형식은 맞다 |\n| `NOT_FOUND` | 404 | 그 밖의 리소스 없음 |\n| `CONFLICT` | 409 | 같은 멱등 키에 다른 내용이 왔다 (예: 이미 쓴 externalTxId 로 금액·목적지가 다른 제출) |\n| `UNPROCESSABLE_ENTITY` | 422 | 요청 형식·값은 맞지만 **현재 리소스 상태나 선행 조건** 때문에 처리할 수 없음 (예: source event가 FINALIZED/완료 조건 미충족, 목적지 계정에 수신 주소 미발급). 조건이 갖춰지면 **같은 요청이 그대로 유효**하다 |\n| `SUBMIT_IN_PROGRESS` | 503 | 같은 `externalTxId` 의 앞선 제출이 처리 중이다 — **오류가 아니라 지연**이다. `Retry-After` 뒤에 같은 요청을 그대로 다시 보낸다 |\n| `CREATION_RETRY_LATER` | 503 | vault·wallet 생성의 새 키 호출을 보수적으로 미룬다. `retryAfterSeconds` 뒤 같은 업무 요청을 다시 보낸다 |\n| `PROVISIONING_PENDING` | 503 | 네트워크 지갑의 생성·조회 회수가 아직 진행 중이다 — **오류가 아니라 지연**이다. 매니저는 새 생성이나 키 회전 없이 조회만 재개하므로 `retryAfterSeconds` 뒤 같은 업무 요청을 그대로 다시 보낸다 |\n| `RELAY_REJECTED` | 502 | 대납 relay 가 전송을 못 대거나 거절 |\n| `INTERNAL` | 500 | 서버 내부 오류 |\n\n표의 HTTP는 단건·요청 전체 오류의 최상위 status다. 주소 batch는 부분 성공 계약이라 최상위 HTTP 200을 유지하고,\n네트워크별 `error`에 `CONFLICT`, `CREATION_RETRY_LATER` 또는 `PROVISIONING_PENDING`을 담는다. 뒤의 둘은 `retryAfterSeconds`도 함께 준다.\n\n`PROVISIONING_PENDING` 과 `CREATION_RETRY_LATER` 를 나눈 이유 — 후자는 Fireblocks 생성 키의 시간 기반 교체 대기라 기다린 뒤 **새 생성 호출**이 이어진다.\n전자는 원천이 보장하지 않는 재생성을 하지 않고 이미 낸 생성 의도를 **조회로만 회수**하는 대기다. 식별·소유 불일치나 서로 다른 지갑 여러 개처럼\n자동 연결이 불가능한 결과는 `CONFLICT` 로 확정되며 운영 해소 전에는 재시도해도 같은 답이 온다.\n\n`SUBMIT_IN_PROGRESS` 를 `CONFLICT` 와 나눈 이유 — `CONFLICT` 는 \"키를 잘못 썼다\"는 확정 오류라 재시도해도 같은 답이 온다.\n`SUBMIT_IN_PROGRESS` 는 잠시 뒤 성공할 상황이다. 둘을 한 코드로 묶으면 호출 쪽이 사고와 지연을 구분할 수 없다.\n\n`INTERNAL`(500) 은 모든 엔드포인트에서 날 수 있어, 오퍼레이션별 응답 표기에서는 생략한다.\n\n## 페이지네이션\n\n목록은 **커서 방식**이다. `limit`(기본 200, 최대 500)으로 크기를 정하고, 응답 `pagination.nextCursor` 를 다음 요청 `cursor` 로 넘겨 이어받는다. 지금 이어받을 페이지가 있는지는 `hasMore` 로 판단한다 — false 면 현재 시점 마지막 페이지다.\n\n`nextCursor` 는 **마지막 페이지에서도 항상 채워진다** — 이번 응답 마지막 항목의 다음 위치를 가리킨다. `order=asc` 조회에서는 이 커서를 보관했다가 나중에 같은 값으로 재요청하면 그 사이 새로 쌓인 내역만 이어받는다(증분 폴링). `order=desc`(기본, 최신순)는 커서가 과거 방향으로 진행하므로 페이지 순회용이다.\n\n`cursor`/`nextCursor` 는 **불투명 토큰**이라 파싱·구성 대상이 아니며, 받은 값을 그대로 전달한다(다음 위치·필터·정렬 방향이 토큰에 담겨 있다). 커서 요청에서는 첫 요청의 조회 조건이 토큰으로 이어지므로, 함께 보낸 다른 파라미터는 무시된다.\n\n## 인증\n\n**없음 (2026-08-05 확정)** — 호출 쪽과 매니저는 내부망 경계를 신뢰한다. securitySchemes 를 정의하지 않는다.\n\n## 멱등\n\n- **계정 생성** — `createAccount` 는 (`accountType`, `ref`) 로 멱등하다. 같은 값으로 재요청하면 매니저가 같은 결과를 돌려준다(호출 쪽이 별도 멱등키를 넣지 않는다).\n- **주소 발급** — `createDepositAddresses` 는 네트워크마다 `(accountId, network, symbol)` 로 멱등하다. 부분 실패해도 성공분은 남으므로 같은 요청을 그대로 재시도할 수 있다.\n- **출금 제출** — 본문 `externalTxId` 가 멱등 키다. **같은 키로 같은 내용을 재제출하면 처음의 `txId` 를 그대로 돌려준다** — 응답을 못 받아 재시도하는 경우가 정상 경로다. 같은 키인데 **내용이 다르면** `409 CONFLICT` 다. 어느 쪽이든 벤더로 중복 전송되지 않는다.\n\n## 이벤트 (메시지 큐)\n\n온체인 상태 변경(입금 감지·출금 확정 등)은 이 HTTP API 가 아니라 **메시지 큐 이벤트**로 온다. 호출 쪽은 토픽별 컨슈머로 받는다.\n\n```seq\n체인 -> Fireblocks: 온체인 상태 변경\nFireblocks -> 매니저: 웹훅 알림 push (서명 검증 후 수신)\n매니저 -> 큐: publish (4 토픽)\n큐 -> 소비 쪽: consume\n소비 쪽 -> 원장: 반영 (멱등)\n소비 쪽 -> 매니저: PUT /events/{eventId}/completion\n소비 쪽 -> 큐: 오프셋 커밋\n```\n\n| 토픽 | 담는 이벤트 | 파티션 키 |\n|---|---|---|\n| `deposit-events` | 고객 입금 (`DEPOSIT`) | 고객 accountId |\n| `withdrawal-events` | 외부 출금 (`WITHDRAWAL`) | 출금 풀 vault 의 accountId |\n| `internal-events` | 내부 이체 (`INTERNAL` — delta 정산) | 출발 계정 accountId |\n| `sweep-events` | DAW 요청 sweep 항목의 체인 상태·항목 결과 | 고객 accountId |\n\n귀속 불명 입금(매핑에 없는 주소)은 큐에 싣지 않는다 — 별도 알림 채널로 통지된다.\n\n**ChainEvent** — 큐로 오는 이벤트 형태 (타입 [ChainEvent](#schema-ChainEvent)):\n\n```json\n{\n  \"eventId\": \"0198c0de-7a2b-7c3d-8e4f-5a6b7c8d9e0f\",\n  \"type\": \"WITHDRAWAL\",\n  \"txId\": \"tx-local-986a169a89dbf0713ad01d2d17eebd59360b155bfd42fe0a\",\n  \"txHash\": \"0xe94fb7b189d0721ccf52330274c9da65b39909e52f2dc512c7a3efac8b5208a0\",\n  \"externalTxId\": \"wd-260713-0042\",\n  \"accountId\": \"acct_pool_02\",\n  \"network\": \"ETHEREUM\",\n  \"symbol\": \"USDC\",\n  \"to\": \"0xdd1b8bb7c9646d21e267bad5f12d011da294af89\",\n  \"from\": \"0x0da6aa405415ddc059a28e089309c7b47e0702ec\",\n  \"amount\": \"100\",\n  \"status\": \"FINALIZED\",\n  \"numOfConfirmations\": 12\n}\n```\n\n- `eventId` — 이벤트 고유 id (UUID v7). **중복 제거 기준은 이 값 하나다**\n- [`type`](#schema-EventType) — DEPOSIT · WITHDRAWAL · INTERNAL\n- [`status`](#schema-TxStatus) — 공통 상태 다섯 (아래 \"상태 (TxStatus) 기준\"). 소비 쪽은 이것으로만 판단한다\n- `amount` — 이동 금액. **문자열 decimal** 이다(정밀도). 입금은 `externalTxId` 가 없으므로 **금액의 출처가 이 값뿐이다**\n- `from` — 발신 주소. 입금은 항상 채워진다 — 입금 판별을 의뢰할 때 쓴다\n- `txHash` — 전파 후 채워짐\n- RBF 대체 거래는 별도 고객 거래가 아니다. 조회 응답과 이벤트의 `txId`·`externalTxId`는 최초 거래 값을 유지하고,\n  `txHash`는 root 계열에서 실제로 채굴된 승자 거래 값으로 바뀔 수 있다\n- 벤더의 `subStatus`·`networkStatus` 는 이벤트에 싣지 않는다 — 매니저가 번역에 쓰는 내부 값이다\n\n전달 보장:\n\n- **at-least-once** — 같은 이벤트가 드물게 두 번 올 수 있다. **`eventId` 유일 기준으로 중복을 버린다** — 한 거래(txId)에서 감지·확정·실패 이벤트가 각각 오므로 `txId` 로 중복 제거하면 뒤 이벤트가 버려진다.\n- **오프셋 커밋** — 원장 반영과 `eventId` 완료 확인이 모두 성공한 뒤에만.\n- **순서** — 같은 계정은 파티션 키가 보장.\n- ★ **한 거래의 순서는 매니저가 보장한다** — 한 `txId` 에 대해 받는 순서는 항상 `감지 → 확정` 또는 `감지 → 무효` 다. 매니저가 감지를 아직 발행하지 않은 상태에서 확정·거부 알림을 먼저 받으면 **감지 이벤트를 합성해 먼저 발행**한 뒤 그 상태를 발행한다. 소비 쪽은 \"감지 없는 확정\" 을 다루지 않는다.\n- **입금 시작 상태** — 입금은 `SUBMITTED` 없이 `CONFIRMED` 부터 온다 (`SUBMITTED` 는 우리가 제출하는 거래에서만 관찰).\n- `REJECTED`(일시적) ≠ `FAILED`(영구). 확정(`FINALIZED`) 판정은 **매니저가** `numOfConfirmations` 를 체인별 임계와 비교해 내린다 — 컨슈머는 `status` 로만 판단한다.\n\n## 상태 (TxStatus) 기준\n\n거래·이벤트의 `status` 는 이 다섯이 기준이다. 벤더 원어는 매니저가 이 다섯으로 번역한다. 아래 표의 `subStatus`·`networkStatus` 열은 **매니저가 번역에 쓰는 벤더 내부 값** — 이벤트에는 `status`(TxStatus) 만 싣는다.\n\n| 공통 상태 | 뜻 | 블록체인 상태 (Pending → Confirmed → Finalized) | 벤더(Fireblocks) 원어 | 대표 subStatus | networkStatus |\n|---|---|---|---|---|---|\n| `SUBMITTED` | 제출됨 — 서명·전파 준비 중, 아직 체인 미등장 (출금만 관찰) | 아직 없음 → 전파되면 Pending | PENDING_SIGNATURE · QUEUED · BROADCASTING | — | 서명 단계엔 없음 → BROADCASTING |\n| `CONFIRMED` | 전파 후 체인 등장, 컨펌 누적 중 (미확정) | Confirmed — 블록에 포함, finality 전 | CONFIRMING | PENDING_BLOCKCHAIN_CONFIRMATIONS | CONFIRMING |\n| `FINALIZED` | 확정 — 확정 정책(DCCP) 임계 컨펌 도달 | Finalized | COMPLETED | CONFIRMED | CONFIRMED |\n| `REJECTED` | 거부·차단 — 정책·스크리닝에 막힘. 영구 실패가 아니라 사람 개입 여지 | 출금 차단은 체인에 없음 · 입금 동결은 Finalized | REJECTED · BLOCKED | AUTO_FREEZE · FROZEN_MANUALLY · REJECTED_AML_SCREENING | 출금(전파 전 차단)은 없음 · 입금 동결은 CONFIRMED |\n| `FAILED` | 영구 실패 — 사유 동반 (수수료 부족·revert 등) | Pending 에서 증발 · revert 는 Confirmed 이후 | FAILED | DROPPED_BY_BLOCKCHAIN (reorg 증발) · 그 외 | FAILED (revert) · DROPPED (mempool 누락) |\n\n판단은 다섯(`status`)으로 한다. `REJECTED`(일시적) ≠ `FAILED`(영구) 구분이 원장·화면 처리를 가른다.\n\n이 다섯은 매니저와 호출 쪽 사이의 **계약 어휘**다 — 이 문서에 남아 있는 `CONFIRMING`·`COMPLETED` 표기는 전부 **벤더(Fireblocks) 원어**다.\n\n- ★ **`CONFIRMED` 는 미확정이다** — 벤더 subStatus/networkStatus 의 `CONFIRMED`(임계 도달, COMPLETED 동반)와 철자가 같지만 가리키는 단계가 다르다. 확정은 `FINALIZED` 다.\n- ★ **`FINALIZED` 는 체인 finality 가 아니다** — DCCP 정책 임계 도달일 뿐이고, `FINALIZED` → `FAILED`(reorg 증발, `DROPPED_BY_BLOCKCHAIN`) 전이가 존재한다. 상태에 서열을 매겨 \"뒤로 가면 무시\"로 구현하면 안 된다.\n"
  },
  "servers": [
    {
      "url": "https://{baseUrl}/blockchain/manage-api",
      "description": "매니저 API 베이스 URL",
      "variables": {
        "baseUrl": {
          "default": "api.example.com"
        }
      }
    }
  ],
  "tags": [
    {
      "name": "Accounts",
      "x-displayName": "계정·주소",
      "description": "계정과 입금 주소"
    },
    {
      "name": "Balances",
      "x-displayName": "잔액",
      "description": "잔액 조회"
    },
    {
      "name": "Transactions",
      "x-displayName": "거래",
      "description": "수수료 견적·출금 제출·거래 조회"
    },
    {
      "name": "Events",
      "x-displayName": "이벤트",
      "description": "DAW-CORE가 업무 원장 반영을 마친 이벤트의 완료 확인"
    },
    {
      "name": "Sweeps",
      "x-displayName": "Sweep",
      "description": "DAW-CORE가 완료 처리한 입금 이벤트를 근거로 요청하는 고객 vault batch sweep"
    },
    {
      "name": "Admin",
      "x-displayName": "관리자",
      "description": "운영자 도구 — 네트워크 채택과 자산 매핑. 호출 주체를 Admin 백엔드로 한정하는 **망 수준 제한이 별도로 필요하다**\n(경로를 나눈 것만으로는 경계가 생기지 않는다).\n상태를 바꾸는 오퍼레이션은 감사 흔적을 위해 `X-Employee-No` · `X-Branch-Code` 헤더를 요구한다.\n"
    }
  ],
  "paths": {
    "/accounts": {
      "post": {
        "tags": [
          "Accounts"
        ],
        "summary": "계정 생성",
        "description": "vault 를 만들고 `ref ↔ accountId` 매핑을 반환한다. `ref` 는 호출 쪽 계정 ID 를 그대로 쓴다.\n\n- (`accountType`, `ref`) 로 멱등하다 — 재요청하면 같은 `accountId` 를 돌려준다.\n- 매니저는 Fireblocks 호출 전에 고정 `accountId`와 현재 세대 `Idempotency-Key`를 생성 원장에 기록한다. 벤더 성공 뒤 로컬 저장이\n  실패한 재시도는 vault 이름의 exact match를 전 페이지 조회해 후보가 하나일 때만 원래 매핑을 복구한다.\n- 후보가 없으면 남은 24시간 창이 설정된 벤더 최장 호출시간 전체를 수용할 때만 현재 키를 재사용한다. 그렇지 않고 마지막 POST\n  준비 + 최장 호출시간 + 24시간 + 초 단위 정밀도 여유 1초의 안전시각도 지나지 않았으면 새 키 호출을\n  `503 CREATION_RETRY_LATER`와 `Retry-After`로 보류한다. 안전시각 뒤 최신 시도만 새 키를 준비한다.\n  준비 시각은 실제 POST 증거가 아니라 중복 방지용 상한이다.\n- 고객·시스템(운영) 계정을 같은 오퍼레이션으로 만든다. **두 유형의 ID 는 값이 겹칠 수 있어 `accountType` 이 필수**다.\n- 매니저는 `ref` 를 불투명 문자열로 다루고 내용을 파싱해 분기하지 않는다.\n",
        "operationId": "createAccount",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/CreateAccountRequest"
              },
              "examples": {
                "로컬_고객": {
                  "summary": "STUB+LOCAL에서 바로 실행할 고객 account",
                  "value": {
                    "accountType": "CUSTOMER",
                    "ref": "daw-local-customer-001"
                  }
                }
              }
            }
          }
        },
        "responses": {
          "201": {
            "description": "생성됨(또는 멱등 재요청)",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AccountResponse"
                },
                "examples": {
                  "생성_결과": {
                    "summary": "accountId는 실행마다 달라지며 다음 주소 발급에 사용한다",
                    "value": {
                      "data": {
                        "accountType": "CUSTOMER",
                        "ref": "daw-local-customer-001",
                        "accountId": "acct_018f3d4a-bf70-7c1a-8f2b-3c4d5e6f7890"
                      },
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  }
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          },
          "503": {
            "$ref": "#/components/responses/CreationRetryLater"
          }
        }
      }
    },
    "/accounts/{accountId}/addresses": {
      "parameters": [
        {
          "$ref": "#/components/parameters/AccountId"
        }
      ],
      "post": {
        "tags": [
          "Accounts"
        ],
        "summary": "입금 주소 여러 자산 한 번에 발급",
        "description": "한 토큰의 입금 주소를 여러 네트워크에 발급한다. `(accountId, network, symbol)` 로 **네트워크마다 멱등**하다.\n\n- 결과는 항목마다 `address` 또는 `error` 로 온다 — **둘 중 하나만** 채워진다. HTTP 는 항목 결과와 무관하게 `200` 이고, 응답은 요청과 같은 순서다.\n- 생성 회수 후보 복수·cursor 반복은 해당 네트워크의 `error.code=CONFLICT`다. 24시간 키 교체 cooldown은\n  `error.code=CREATION_RETRY_LATER`와 `error.retryAfterSeconds`로 반환해 재시도 가능한 지연임을 구분한다.\n- Dfns 원천의 네트워크 지갑은 생성 의도를 먼저 저장하고 최초 POST 한 번 뒤에는 조회로만 회수한다. 생성·회수가 진행 중이면 해당 네트워크의\n  `error.code=PROVISIONING_PENDING`과 `error.retryAfterSeconds`이고, 원천·네트워크·상관관계·조직 소유 불일치나 서로 다른 지갑 여러 개는\n  `error.code=CONFLICT`다. 어느 쪽도 새 생성이나 키 회전을 뜻하지 않으며, 같은 요청을 그대로 다시 보내면 매니저가 조회를 이어간다.\n  지갑이 준비되면 그 지갑 주소가 같은 네트워크 토큰들의 수신 주소가 된다 — 운영 설정에서 수신 주소 모델(EVM 계정 모델)을 확인한 네트워크에서만이며,\n  확인되지 않은 네트워크는 매핑이 있어도 `400 ASSET_NOT_SUPPORTED`다. 계정은 Dfns에서 네트워크 없는 논리 계정으로 등록되고 지갑은 첫 주소 발급에서 준비한다.\n- 지원하지 않는 네트워크가 **하나라도 섞이면 아무것도 발급하지 않고 `400`** 이다. 발급을 시도했다가 전부 실패한 것(`200`, 모든 항목에 `error`)과 구분된다.\n- **재시도는 같은 요청을 그대로 보낸다** — 이미 발급된 네트워크는 벤더를 부르지 않고 같은 주소가 오고, 실패분만 다시 시도된다. 실패분만 골라 보내도 결과는 같다.\n- 네트워크별 Fireblocks 호출 전에 생성 원장과 당시 vendor assetId를 고정한다. 벤더 성공 뒤 로컬 저장이 실패한 재시도는\n  해당 vault wallet의 주소를 전 페이지 조회해 후보가 하나일 때만 복구하며, 여러 후보 중 하나를 임의 선택하지 않는다.\n  후보가 없으면 남은 24시간 창이 설정된 벤더 최장 호출시간 전체를 수용할 때만 현재 키를 재사용한다. 그렇지 않으면 마지막\n  POST 준비 + 최장 호출시간 + 24시간 + 초 단위 정밀도 여유 1초의 안전시각 뒤 최신 시도만 새 키를 준비한다.\n- 한 요청 **20네트워크**까지. 네트워크마다 벤더를 한 번 부른다.\n- 네트워크 목록은 호출 쪽이 정한다 — 매니저가 토큰만 받아 네트워크를 채우지 않는다.\n",
        "operationId": "createDepositAddresses",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/CreateAddressesRequest"
              },
              "examples": {
                "로컬_TUSD": {
                  "summary": "STUB+LOCAL 입금 주소",
                  "value": {
                    "symbol": "TUSD",
                    "networks": [
                      "LOCAL"
                    ]
                  }
                },
                "두_네트워크": {
                  "summary": "최초 요청",
                  "value": {
                    "symbol": "USDC",
                    "networks": [
                      "ETHEREUM",
                      "BASE"
                    ]
                  }
                },
                "실패분만": {
                  "summary": "재시도 — 실패한 네트워크만 보내는 경우",
                  "value": {
                    "symbol": "USDC",
                    "networks": [
                      "BASE"
                    ]
                  }
                }
              }
            }
          }
        },
        "responses": {
          "200": {
            "description": "네트워크별 결과 — 전부 성공·일부 실패·전부 실패가 모두 이 응답이다",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/DepositAddressResultListResponse"
                },
                "examples": {
                  "로컬_성공": {
                    "summary": "로컬 Anvil에서 실제 생성된 형태",
                    "value": {
                      "data": [
                        {
                          "network": "LOCAL",
                          "symbol": "TUSD",
                          "address": "0xdd1b8bb7c9646d21e267bad5f12d011da294af89",
                          "memoTag": null,
                          "error": null
                        }
                      ],
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  },
                  "전체_성공": {
                    "summary": "전체 성공",
                    "value": {
                      "data": [
                        {
                          "network": "ETHEREUM",
                          "symbol": "USDC",
                          "address": "0xdd1b8bb7c9646d21e267bad5f12d011da294af89",
                          "memoTag": null,
                          "error": null
                        },
                        {
                          "network": "BASE",
                          "symbol": "USDC",
                          "address": "0x4a1dbedeb87aca726a7c5901b2ca68a2a35deee3",
                          "memoTag": null,
                          "error": null
                        }
                      ],
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  },
                  "부분_실패": {
                    "summary": "부분 실패 — 성공분은 그대로 남는다",
                    "description": "같은 요청을 재시도하면 ETHEREUM 은 같은 주소가 그대로 오고 BASE 만 다시 시도된다.",
                    "value": {
                      "data": [
                        {
                          "network": "ETHEREUM",
                          "symbol": "USDC",
                          "address": "0xdd1b8bb7c9646d21e267bad5f12d011da294af89",
                          "memoTag": null,
                          "error": null
                        },
                        {
                          "network": "BASE",
                          "symbol": "USDC",
                          "address": null,
                          "memoTag": null,
                          "error": {
                            "code": "INTERNAL",
                            "message": "address issuance failed"
                          }
                        }
                      ],
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  },
                  "전체_실패": {
                    "summary": "전체 실패 — 발급을 시도했고 전부 실패했다 (400 과 다르다)",
                    "value": {
                      "data": [
                        {
                          "network": "ETHEREUM",
                          "symbol": "USDC",
                          "address": null,
                          "memoTag": null,
                          "error": {
                            "code": "INTERNAL",
                            "message": "address issuance failed"
                          }
                        },
                        {
                          "network": "BASE",
                          "symbol": "USDC",
                          "address": null,
                          "memoTag": null,
                          "error": {
                            "code": "INTERNAL",
                            "message": "address issuance failed"
                          }
                        }
                      ],
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  }
                }
              }
            }
          },
          "400": {
            "description": "발급 전 거절 — 아무것도 발급되지 않았다",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/ErrorResponse"
                },
                "examples": {
                  "미지원_네트워크": {
                    "summary": "지원하지 않는 네트워크가 섞였다",
                    "value": {
                      "error": {
                        "code": "ASSET_NOT_SUPPORTED",
                        "message": "unsupported network for symbol: TRON/USDC"
                      },
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  },
                  "요청_형식_오류": {
                    "summary": "네트워크 배열이 비었거나 20개를 넘었다",
                    "value": {
                      "error": {
                        "code": "VALIDATION_FAILED",
                        "message": "networks must contain 1..20 items"
                      },
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  }
                }
              }
            }
          },
          "404": {
            "$ref": "#/components/responses/AccountNotFound"
          }
        }
      },
      "get": {
        "tags": [
          "Accounts"
        ],
        "summary": "발급된 입금 주소 조회",
        "description": "그 계정에 발급된 입금 주소를 돌려준다 — 매니저 DB 를 읽을 뿐 벤더 왕복이 없다.\n\n`symbol` · `network` 로 걸러 받을 수 있고 둘 다 없으면 그 계정의 전체다. 같은 토큰을 여러 네트워크로 받는 고객 화면은 `symbol` 하나만 걸어 한 번에 받는다.\n\n**미발급은 배열에 담기지 않는다** — 계정은 있는데 주소가 없으면 빈 배열이고, 계정 자체가 없으면 `404` 다. 발급(`POST`)과 경로가 같아 메서드만 다르다.\n",
        "operationId": "depositAddressesOf",
        "parameters": [
          {
            "name": "symbol",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string",
              "maxLength": 16
            },
            "description": "토큰 심볼로 거른다 (선택)",
            "example": "USDC"
          },
          {
            "name": "network",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string",
              "maxLength": 20
            },
            "description": "네트워크 코드로 거른다 (선택)",
            "example": "BASE"
          }
        ],
        "responses": {
          "200": {
            "description": "발급된 주소 목록 (미발급이면 빈 배열)",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/DepositAddressListResponse"
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "404": {
            "$ref": "#/components/responses/AccountNotFound"
          }
        }
      }
    },
    "/accounts/{accountId}/balances": {
      "parameters": [
        {
          "$ref": "#/components/parameters/AccountId"
        }
      ],
      "get": {
        "tags": [
          "Balances"
        ],
        "summary": "vault 잔액 조회",
        "description": "벤더가 보는 **vault 잔액** — 대사 재료이지 고객별 귀속 잔액이 아니다.\n\n- `network` · `symbol` 으로 거른다. 둘 다 없으면 **그 계정에 주소가 발급된 자산 전부**다.\n- **주소 없이 vault 에 들어온 자산은 나오지 않는다** — 매니저가 아는 자산 집합이 발급 기록뿐이다.\n- 계정은 있지만 조건에 맞는 발급 자산이 없으면 벤더를 호출하지 않고 `200`의 빈 `data` 배열을 돌려준다.\n  발급된 자산의 실제 잔액이 0이면 빈 배열이 아니라 그 자산과 문자열 `\"0\"` 잔액을 돌려준다.\n- 매니저에 발급 기록이 있는데 벤더 wallet을 읽을 수 없는 것은 미발급이 아니라 외부 drift다. 빈 배열이나 0으로 숨기지 않고\n  공통 `INTERNAL`(500) 계약으로 실패한다.\n- Fireblocks 원천은 자산마다 벤더를 한 번 부르고 `available`·`pending`·`locked`를 모두 채운다.\n- **Dfns 원천**은 발급 네트워크마다 그 계정의 네트워크 지갑 자산 목록을 한 번 읽는다. 지갑의 온체인 잔액이 `available`이고\n  `pending`·`locked`는 Dfns가 그 구분을 주지 않으므로 `null`이다(0으로 채우지 않는다). 지갑 자산 목록에 없는 발급 자산은 `\"0\"`이다.\n  벤더 `balance`를 최소 단위 정수로 읽는 해석과 미보유 `\"0\"` 규칙은 Baseline 수용 항목이다(설계 계약13). 정수가 아닌 응답 형식은 500으로 드러나지만 단위 정확성은 그 검사로 판별되지 않는다.\n  발급 기록이 있는데 원장에 준비 지갑이 없거나 응답 지갑이 다르면 외부 drift로 `INTERNAL`(500)이다.\n",
        "operationId": "balancesOf",
        "parameters": [
          {
            "name": "network",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string",
              "maxLength": 20
            },
            "description": "네트워크 코드로 거른다 (선택)",
            "example": "BASE"
          },
          {
            "name": "symbol",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string",
              "maxLength": 16
            },
            "description": "토큰 심볼로 거른다 (선택)",
            "example": "USDC"
          }
        ],
        "responses": {
          "200": {
            "description": "자산별 잔액 (해당 자산이 없으면 빈 배열)",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AssetBalanceListResponse"
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "404": {
            "$ref": "#/components/responses/AccountNotFound"
          }
        }
      }
    },
    "/admin/networks": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "네트워크 목록",
        "description": "쓸 수 있는 체인과, 그중 우리가 이름을 붙여 채택한 것을 함께 읽는다.\n\n- `adopted=true` 면 채택한 것만, `false` 면 아직 안 붙인 후보만.\n- `code` 는 채택했을 때만 채워진다. 채택 전 행을 가리킬 때 쓰는 `candidateId` 는 **해석하지 말고 그대로 되돌려 보내는 값**이다.\n- **채택 전 목록은 길다.** `q` 로 이름을 좁히고, EVM 이면 `chainId` 로 한 건까지 좁힌다.\n",
        "operationId": "networksOf",
        "parameters": [
          {
            "name": "q",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string"
            },
            "description": "Fireblocks 표시명 또는 채택한 BCM 네트워크 코드 일부로 좁힌다 — 대소문자를 가리지 않는다",
            "example": "base"
          },
          {
            "name": "chainId",
            "in": "query",
            "required": false,
            "schema": {
              "type": "integer",
              "format": "int64"
            },
            "description": "EIP-155 chainId 로 정확히 좁힌다 — EVM 이면 한 건이다. 비 EVM 에는 이 값이 없어 이름으로 찾는다",
            "example": 8453
          },
          {
            "name": "adopted",
            "in": "query",
            "required": false,
            "schema": {
              "type": "boolean"
            }
          },
          {
            "name": "testnet",
            "in": "query",
            "required": false,
            "schema": {
              "type": "boolean"
            }
          }
        ],
        "responses": {
          "200": {
            "description": "네트워크 목록",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/NetworkListResponse"
                }
              }
            }
          }
        }
      }
    },
    "/admin/networks/{code}": {
      "parameters": [
        {
          "name": "code",
          "in": "path",
          "required": true,
          "schema": {
            "type": "string",
            "pattern": "^[A-Z0-9_]{1,20}$"
          },
          "description": "우리 네트워크 코드",
          "example": "BASE"
        }
      ],
      "put": {
        "tags": [
          "Admin"
        ],
        "summary": "네트워크 채택",
        "description": "후보 하나에 우리 이름을 붙인다 — **이 한 번이 \"이 체인을 쓴다\"는 결정**이고, 누가 언제 했는지 남는다.\n\n같은 후보에 같은 이름을 다시 보내면 아무 일도 일어나지 않는다 — **`chainModel`까지 같을 때다.** 이름이 이미 **다른** 후보를 가리키거나 같은 채택에 **다른 `chainModel`**을 보내면 `409` 다 — 이미 발급된 주소가 가리키는 체인이 조용히 바뀌면 안 되고, 계정·자산 모델은 체인의 속성이라 뒤집을 값이 아니다.\n",
        "operationId": "adoptNetwork",
        "parameters": [
          {
            "$ref": "#/components/parameters/EmployeeNo"
          },
          {
            "$ref": "#/components/parameters/BranchCode"
          }
        ],
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/AdoptNetworkRequest"
              }
            }
          }
        },
        "responses": {
          "200": {
            "description": "채택됨",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/NetworkResponse"
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          }
        }
      },
      "delete": {
        "tags": [
          "Admin"
        ],
        "summary": "네트워크 채택 해제",
        "description": "자산 매핑이 하나라도 남아 있으면 `409` 다. 매핑을 먼저 지운다.",
        "operationId": "releaseNetwork",
        "parameters": [
          {
            "$ref": "#/components/parameters/EmployeeNo"
          },
          {
            "$ref": "#/components/parameters/BranchCode"
          }
        ],
        "responses": {
          "204": {
            "description": "해제됨"
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          }
        }
      }
    },
    "/admin/asset-candidates": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Fireblocks 자산 후보 검색",
        "description": "별도 동기화한 Fireblocks 자산 카탈로그 캐시에서 **심볼·표시명·컨트랙트 주소로 찾고 네트워크는 결과로 받는다.** `q=USDC` 하나면 동기화한 모든 네트워크의 관련 자산이 한 번에 온다 — 네트워크를 먼저 고르거나 BCM 코드를 입력할 필요가 없다.\n\n운영자가 **컨트랙트 주소를 눈으로 대조**하는 자리다. 발행사 공식 문서의 주소와 같은 행을 찾으면, 그 행의 `network` 와 `contractAddress` 를 그대로 등록에 쓴다.\n\n**동기화한 모든 Fireblocks 네트워크에서 찾는다.** BCM이 지원하지 않는 네트워크의 후보도 비교할 수 있지만 `registrationAllowed=false`이며 등록할 수 없다. 로컬에서는 전체 후보가 필요할 때 `./scripts/local.sh sync assets`를 실행한다.\n\n결과의 `symbol` 은 아직 우리 코드가 아닌 벤더 표기이며, 등록할 때 우리 `symbol` 값을 정한다 — 대개 같지만 같아야 하는 것은 아니다. 캐시는 탐색용이고 실제 등록은 Fireblocks에서 주소를 다시 해소한다.\n\n읽기 전용이고 아무것도 바꾸지 않는다.\n",
        "operationId": "assetCandidatesOf",
        "parameters": [
          {
            "name": "q",
            "in": "query",
            "required": true,
            "schema": {
              "type": "string",
              "minLength": 2,
              "maxLength": 64
            },
            "description": "심볼·표시명·컨트랙트 주소로 찾는다 — 대소문자를 가리지 않는다",
            "example": "USDC"
          },
          {
            "name": "network",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string"
            },
            "description": "특정 네트워크로 좁힌다 (선택)",
            "example": "BASE"
          }
        ],
        "responses": {
          "200": {
            "description": "자산 후보 목록",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AssetCandidateListResponse"
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          }
        }
      }
    },
    "/admin/asset-mappings": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "자산 매핑 목록",
        "description": "현재 활성인 (네트워크, 토큰) 매핑을 읽는다. `network` · `symbol` 으로 거른다.",
        "operationId": "assetMappingsOf",
        "parameters": [
          {
            "name": "network",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string"
            }
          },
          {
            "name": "symbol",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string"
            }
          }
        ],
        "responses": {
          "200": {
            "description": "매핑 목록",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AssetMappingListResponse"
                }
              }
            }
          }
        }
      },
      "post": {
        "tags": [
          "Admin"
        ],
        "summary": "자산 매핑 등록",
        "description": "우리 (네트워크, 토큰) 이 어느 자산인지 지정한다. 등록은 어쩌다 한 번이지만 여기서 틀리면 자금이 엉뚱한 체인으로 가므로 관문 넷을 지난다.\n벤더 재해소 관문은 데이터셋 원천에 따라 다르다.\n\n- **채택한 네트워크만** — 이름을 붙이지 않은 네트워크로는 등록할 수 없다 (`400`).\n- **Fireblocks 원천**: 후보의 **Fireblocks Asset ID와 컨트랙트 주소로** 지정한다. Asset ID·주소·네트워크가 모두 일치해야 한다 —\n  Fireblocks 최신 조회에서 하나라도 다르면 `400`, 둘 이상이면 `409` 다. 화면의 캐시 값이나 브라우저 입력을 그대로 신뢰하지 않는다.\n  `fireblocksAssetId` 가 없으면 `400`(`fireblocksAssetIdRequired`) 이다.\n- **Dfns 원천**: 자산을 **network와 contractAddress로** 지정한다. `fireblocksAssetId` 를 보내면 `400`(`fireblocksAssetIdNotApplicable`) 이다.\n  서버는 데이터셋 네트워크 행과 실행 설정의 Dfns network 일치(`networkBindingMismatch`)와 행의 계정·자산 모델(없으면 `assetModelUnsupported`)을 검증한다.\n  EVM 모델은 컨트랙트 주소 형식 `^0x[0-9a-fA-F]{40}$`(`contractAddressInvalid`), Solana 모델은 mint가 base58 32바이트 공개키(`mintAddressInvalid`)이고\n  `tokenStandard`(SPL·SPL_2022)가 필수(`tokenStandardRequired`)다. 네이티브·EVM에 `tokenStandard` 를 보내면 `tokenStandardNotApplicable` 이다.\n  검증을 지나면 Dfns 자산 키(`dfnsAssetKey`) 를 만든다. Dfns 원천은 `decimals` 도 필수(`decimalsRequired`)이고 Fireblocks 원천에 보내면 `decimalsNotApplicable` 이다.\n  Dfns 공개 명세에는 자산 카탈로그 API 가 없어 온체인 존재·decimals 는 운영자의 발행사 공식 자료로 대조한다.\n- **활성 매핑을 덮어쓰지 않는다** — 이미 활인 (네트워크, 토큰) 매핑은 `409` 다. 논리 해제된 행은 검증을 다시 통과한 뒤 재활성 또는 교체하고 전후 snapshot을 남긴다.\n- **한 자산은 한 매핑** — 다른 (네트워크, 토큰) 이 이미 그 자산이면 `409` 다.\n\n네이티브 자산(ETH 등)은 컨트랙트 주소가 없으므로 `contractAddress` 를 `null` 로 보낸다 — 그 네트워크의 네이티브 자산으로 해석한다.\n응답의 벤더 식별자는 벤더 이름을 붙인 필드로만 노출한다 — Fireblocks 원천은 `fireblocksAssetId`, Dfns 원천은 `dfnsAssetKey` 가 채워지고 다른 쪽은 `null` 이다.\n",
        "operationId": "registerAssetMapping",
        "parameters": [
          {
            "$ref": "#/components/parameters/EmployeeNo"
          },
          {
            "$ref": "#/components/parameters/BranchCode"
          }
        ],
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/RegisterAssetMappingRequest"
              }
            }
          }
        },
        "responses": {
          "201": {
            "description": "등록됨",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AssetMappingResponse"
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          }
        }
      }
    },
    "/admin/asset-mappings/{network}/{symbol}": {
      "parameters": [
        {
          "name": "network",
          "in": "path",
          "required": true,
          "schema": {
            "type": "string"
          }
        },
        {
          "name": "symbol",
          "in": "path",
          "required": true,
          "schema": {
            "type": "string"
          }
        }
      ],
      "delete": {
        "tags": [
          "Admin"
        ],
        "summary": "자산 매핑 논리 해제",
        "description": "잘못 등록한 것을 논리 해제하고 변경 전후 snapshot을 남긴다. **그 (네트워크, 토큰) 으로 발급된 주소가 하나도 없을 때만** 허용하고, 있으면 `409` 다 — 주소가 이미 나갔다면 매핑 수정이 아니라 사고 처리다.\n\n수정 오퍼레이션은 두지 않는다. 가리키는 자산을 바꾸면 이미 나간 주소와 앞으로 나갈 주소가 서로 다른 자산이 되기 때문이다.\n",
        "operationId": "deleteAssetMapping",
        "parameters": [
          {
            "$ref": "#/components/parameters/EmployeeNo"
          },
          {
            "$ref": "#/components/parameters/BranchCode"
          }
        ],
        "responses": {
          "204": {
            "description": "삭제됨"
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          }
        }
      }
    },
    "/admin/asset-mappings/bulk": {
      "post": {
        "tags": [
          "Admin"
        ],
        "summary": "자산 매핑 일괄 등록",
        "description": "검색 결과에서 선택한 자산을 최대 20개까지 한 번에 등록한다. 서버는 기존 매핑, 요청 내 중복,\n원천별 벤더 재해소 관문(단건 등록과 같다 — Fireblocks 최신 Asset ID·네트워크·컨트랙트 주소, Dfns 네트워크 행·자산 모델·주소 형식)을\n모두 먼저 검증한 뒤 현재 매핑과 변경 snapshot을 한 트랜잭션으로 저장한다. 한 항목이라도 실패하면 아무 항목도 저장하지 않는다. 항목 실패 응답의\n`error.details`는 요청 배열의 `index`, `network`, `symbol`, 수정 판단용 `reason`을 포함한다.\n",
        "operationId": "registerAssetMappings",
        "parameters": [
          {
            "$ref": "#/components/parameters/EmployeeNo"
          },
          {
            "$ref": "#/components/parameters/BranchCode"
          }
        ],
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/BulkRegisterAssetMappingsRequest"
              }
            }
          }
        },
        "responses": {
          "201": {
            "description": "모두 등록됨",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AssetMappingListResponse"
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          }
        }
      }
    },
    "/admin/transaction-investigations/{identifier}": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "거래 운영 조사",
        "description": "root txId, active·대체 txId, externalTxId 또는 sweep executionId 하나로 같은 논리 거래의\n제출·웹훅·공통 상태·outbox 발행·대사·boost·sweep·allowance·당시 fee quote를 연결한다.\n수신 원문 payload와 서명, 제출 calldata, 벤더 자산 id는 응답하지 않는다.\n",
        "operationId": "transactionInvestigationOf",
        "parameters": [
          {
            "name": "identifier",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string",
              "minLength": 1,
              "maxLength": 128
            }
          }
        ],
        "responses": {
          "200": {
            "description": "구조화된 거래 운영 조사 결과",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminTransactionInvestigationResponse"
                }
              }
            }
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          }
        }
      }
    },
    "/admin/sweep-request-investigations/{identifier}": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Sweep 요청 운영 조사",
        "description": "BCM sweepRequestId, DAW externalSweepRequestId, sweepItemId, executionId, txId, txHash,\n원천 eventId 또는 결과 eventId 하나로 요청부터 DAW 완료 확인까지 연결한다. 항목별 source event,\n실행 당시 policy·contract snapshot, 물리 거래, sweep 결과 event와 DAW completion을 조회 전용으로 반환한다.\n`retryable`과 `nextAction`은 화면이 추론하지 않고 서버가 현재 상태에서 계산한 값이다.\n",
        "operationId": "sweepRequestInvestigationOf",
        "parameters": [
          {
            "name": "identifier",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string",
              "minLength": 1,
              "maxLength": 128
            }
          }
        ],
        "responses": {
          "200": {
            "description": "Sweep 요청 세로줄 조사 결과",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminSweepRequestInvestigationResponse"
                }
              }
            }
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          }
        }
      }
    },
    "/admin/sweep-operations": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Sweep 요청·이벤트 운영 적체",
        "description": "접수·차단·실행·부분 성공·실패 요청과 pending item, sweep-events 발행 실패,\n발행 성공 후 DAW completion이 없는 건수와 가장 오래된 시각을 읽기 전용으로 반환한다.\n",
        "operationId": "sweepOperations",
        "responses": {
          "200": {
            "description": "Sweep 운영 적체 요약",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminSweepOperationsResponse"
                }
              }
            }
          }
        }
      }
    },
    "/admin/vault-reconciliations": {
      "post": {
        "tags": [
          "Admin"
        ],
        "summary": "Fireblocks vault와 BCM 계정 전체 대사 접수",
        "description": "실행 원장을 먼저 저장하고 즉시 접수한다. 별도 실행기가 Fireblocks workspace를 cursor 단위로 끝까지 읽어\nBCM 계정 snapshot과 대조하며, 동시에 활성인 전체 대사는 하나만 허용한다. 계정·vault·잔액을 변경하지 않는다.\n",
        "operationId": "startAdminVaultReconciliation",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/StartAdminVaultReconciliationRequest"
              }
            }
          }
        },
        "responses": {
          "202": {
            "description": "vault 대사 실행 접수",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminVaultReconciliationRunResponse"
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          }
        }
      }
    },
    "/admin/vault-reconciliations/{runId}": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Fireblocks vault 전체 대사 상태와 결과 page 조회",
        "description": "진행량과 안전한 실패 코드를 반환한다. 완료·부분 완료 결과는 실행에 고정된 검색/정렬 순서로 최대 100건만 반환한다.\nPARTIAL에서는 확인한 MANAGED·UNMANAGED만 제공하며 미확인 BCM 계정을 누락으로 추정하지 않는다.\n",
        "operationId": "adminVaultReconciliation",
        "parameters": [
          {
            "name": "runId",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string",
              "minLength": 1,
              "maxLength": 36
            }
          },
          {
            "name": "cursor",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string",
              "maxLength": 64
            },
            "description": "이전 응답의 opaque nextCursor"
          },
          {
            "name": "limit",
            "in": "query",
            "required": false,
            "schema": {
              "type": "integer",
              "minimum": 1,
              "maximum": 100,
              "default": 50
            }
          }
        ],
        "responses": {
          "200": {
            "description": "vault 대사 실행 상태와 bounded 결과 page",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminVaultReconciliationResponse"
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          }
        }
      }
    },
    "/admin/contracts": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Admin 컨트랙트 레지스트리 조회",
        "description": "불변 컨트랙트 버전, 현재 binding, 최신 독립 2-RPC evidence 상태를 조회한다. RPC URL·credential은 반환하지 않는다.",
        "operationId": "adminContracts",
        "responses": {
          "200": {
            "description": "컨트랙트 버전 목록",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminContractListResponse"
                }
              }
            }
          }
        }
      }
    },
    "/admin/policies": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Admin 실행 정책 조회",
        "description": "불변 정책 버전의 파생 상태와 배포 hard ceiling 통과 여부를 조회한다.",
        "operationId": "adminPolicies",
        "responses": {
          "200": {
            "description": "정책 버전 목록",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminPolicyListResponse"
                }
              }
            }
          }
        }
      }
    },
    "/admin/band-s": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Admin 밴드S 운영 원장 조회",
        "description": "DAW-CORE가 계산해 등록한 최신 100개 밴드S snapshot·simulation·이동안과 승인·실행 상태를\n같은 policy/input/proposal hash 문맥으로 조회한다. 상태와 실행 금지 사유는 서버가 파생하며,\n이 읽기 API는 실행·승인 기능이나 원문 credential을 노출하지 않는다.\n",
        "operationId": "adminBandS",
        "responses": {
          "200": {
            "description": "밴드S 제안과 실행 원장 목록",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminBandSListResponse"
                }
              }
            }
          }
        }
      }
    },
    "/admin/execution-gates": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Admin 비상 실행 게이트 조회",
        "description": "채택 네트워크별 출금·sweep·정상 allowance approve의 신규 실행 가능 상태를 조회한다.\n행이 없는 범위도 `OPEN`으로 포함하며, 기존 실행 복구와 비상 `approve(0)` 허용 여부는 서버가 계산한다.\n최신 TAP batch 차단·컨트랙트 pause·운영자 제거 외부 관찰 증적도 함께 반환한다.\n최대 300개 게이트와 네트워크별 최신 증적 100개를 반환하고 초과 여부는 `truncated`로 알린다.\n이 읽기 API는 중지·외부 조치·재개 mutation을 노출하지 않는다.\n",
        "operationId": "adminExecutionGates",
        "responses": {
          "200": {
            "description": "서버 계산 실행 게이트 현황",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminExecutionGateOverviewResponse"
                }
              }
            }
          }
        }
      }
    },
    "/admin/runtime-readiness": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Admin 로컬 첫 실행·Webhook runtime 준비 상태",
        "description": "Webhook 인박스·outbox의 안전한 집계와 마지막 수신 시각, sweep 실행 준비 상태를 조회한다. 원문 payload·서명·오류 원문은 반환하지 않는다.\n`NEVER_RECEIVED`는 아직 관찰이 없다는 뜻이며 그 사실만으로 장애를 판정하지 않는다. 읽기 전용 상태·복구 진입점만 제공한다.\n",
        "operationId": "adminRuntimeReadiness",
        "responses": {
          "200": {
            "description": "서버 계산 runtime 준비 상태",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminRuntimeReadinessResponse"
                }
              }
            }
          }
        }
      }
    },
    "/admin/change-requests/{requestId}": {
      "get": {
        "tags": [
          "Admin"
        ],
        "summary": "Admin 변경 요청 상세 조회",
        "description": "요청 snapshot, 서버 계산 diff·영향, 승인 정족수, 판단, 활성화 가능 여부와 금지 사유를 조회한다.",
        "operationId": "adminChangeRequestOf",
        "parameters": [
          {
            "name": "requestId",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string",
              "minLength": 1,
              "maxLength": 36
            }
          }
        ],
        "responses": {
          "200": {
            "description": "변경 요청 상세",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/AdminChangeRequestResponse"
                }
              }
            }
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          }
        }
      }
    },
    "/transactions": {
      "post": {
        "tags": [
          "Transactions"
        ],
        "summary": "출금 제출",
        "description": "출금(또는 내부 이체)을 제출한다. 응답은 벤더 tx id(`txId`)이고 상태 진행은 큐 이벤트로 따라간다(Events).\n\n- `externalTxId` 가 멱등 키다. **같은 키 + 같은 내용**을 다시 보내면 처음의 `txId` 를 돌려주므로 **재시도가 안전**하다.\n- 같은 키인데 **내용이 다르면** `409` 다.\n- 제출한 건은 `GET /transactions/external/{externalTxId}` 로 찾는다 — 출금은 출금 풀 vault 에서 나가 **고객 계정 목록에는 없다**.\n\n**\"같은 내용\"의 범위** — 자금이 어디서 어디로 얼마나 움직이는지를 규정하는 값만 본다:\n`from.type` · `from.accountId` · `to.type` · `to` 의 식별값(`address`·`accountId`·`walletId` 중 채워진 하나) · `network` · `symbol` · `amount`.\n\n- `note` 와 `travelRule` 은 **비교하지 않는다.** 메모는 자금 이동을 바꾸지 않고, 트래블룰 산출물은 다시 만들면 값이 달라질 수 있어 정당한 재시도를 막게 된다.\n- `amount` 는 금액으로 비교한다 — `\"1.50\"` 과 `\"1.5\"` 는 같다.\n- 나머지는 **문자 그대로** 비교한다. 주소·네트워크·심볼은 대소문자를 바꾸지 않으므로, 재시도할 때는 처음 보낸 문자열을 그대로 보내야 한다.\n\n**응답을 못 받았을 때** — `5xx` 나 타임아웃은 제출이 나갔는지 알 수 없다는 뜻이지 실패했다는 뜻이 아니다.\n같은 요청을 그대로 다시 보내면 되고(멱등), 확인만 하려면 `GET /transactions/external/{externalTxId}` 를 쓴다.\n\n**같은 키를 동시에 보냈을 때** — 앞선 요청이 아직 처리 중이면 `503 SUBMIT_IN_PROGRESS` 와 `Retry-After` 가 온다.\n중복 제출이 아니라 **아직 결과를 모른다**는 뜻이라, 그 시간만큼 기다렸다 같은 요청을 그대로 다시 보내면 된다.\n",
        "operationId": "submitTransaction",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/TransactionRequest"
              },
              "examples": {
                "로컬_전송": {
                  "summary": "accountId를 실제 생성 결과로 바꾼 실행한다",
                  "value": {
                    "externalTxId": "daw-local-withdrawal-001",
                    "from": {
                      "type": "ACCOUNT",
                      "accountId": "acct_018f3d4a-bf70-7c1a-8f2b-3c4d5e6f7890"
                    },
                    "to": {
                      "type": "ADDRESS",
                      "address": "0x4a1dbedeb87aca726a7c5901b2ca68a2a35deee3"
                    },
                    "network": "LOCAL",
                    "symbol": "TUSD",
                    "amount": "1",
                    "note": "DAW-CORE local integration",
                    "travelRule": null
                  }
                }
              }
            }
          }
        },
        "responses": {
          "202": {
            "description": "접수됨(제출)",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/SubmitResponse"
                },
                "examples": {
                  "로컬_접수": {
                    "value": {
                      "data": {
                        "txId": "tx-local-986a169a89dbf0713ad01d2d17eebd59360b155bfd42fe0a"
                      },
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  }
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "404": {
            "$ref": "#/components/responses/AccountNotFound"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          },
          "422": {
            "$ref": "#/components/responses/UnprocessableEntity"
          },
          "502": {
            "$ref": "#/components/responses/RelayRejected"
          },
          "503": {
            "$ref": "#/components/responses/SubmitInProgress"
          }
        }
      }
    },
    "/sweeps": {
      "post": {
        "tags": [
          "Sweeps"
        ],
        "summary": "고객 vault batch sweep 요청",
        "description": "DAW-CORE가 `DEPOSIT/FINALIZED` 이벤트를 업무 원장에 반영하고 event completion까지 성공한 뒤 요청한다.\n금액·vault 주소·컨트랙트는 보내지 않는다. BCM이 실행 직전 실제 잔액과 활성 정책/컨트랙트를 다시 검증한다.\n\n- 한 요청은 하나의 `network/symbol`과 서로 다른 고객 계정 1..N개로 구성한다.\n- 각 `sourceEventIds`는 해당 계정/자산의 완료된 입금 FINALIZED 이벤트여야 하며 다른 요청에서 재사용할 수 없다.\n- 같은 `externalSweepRequestId`와 같은 canonical body는 최초 응답을 반환하고, 다른 body는 `409`다.\n- 실행 gate가 중지됐으면 안전하게 `BLOCKED`로 접수하며 allowance 또는 제출을 시작하지 않는다.\n- 이 endpoint는 `BCM_DAW_INTEGRATION_ENABLED=true`인 내부/로컬 환경에서만 열린다.\n",
        "operationId": "requestSweep",
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "$ref": "#/components/schemas/SweepRequest"
              },
              "examples": {
                "BASE_USDC_두_계정": {
                  "value": {
                    "externalSweepRequestId": "daw-sweep-20260827-001",
                    "network": "BASE",
                    "symbol": "USDC",
                    "items": [
                      {
                        "accountId": "acct_018f3d4a-bf70-7c1a-8f2b-3c4d5e6f7890",
                        "sourceEventIds": [
                          "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891"
                        ]
                      },
                      {
                        "accountId": "acct_018f3d4a-bf70-7c1a-8f2b-3c4d5e6f7892",
                        "sourceEventIds": [
                          "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7892"
                        ]
                      }
                    ]
                  }
                }
              }
            }
          }
        },
        "responses": {
          "202": {
            "description": "신규 접수 또는 같은 본문의 멱등 재응답",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/SweepRequestResponse"
                },
                "examples": {
                  "접수됨": {
                    "value": {
                      "data": {
                        "sweepRequestId": "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7801",
                        "externalSweepRequestId": "daw-sweep-20260827-001",
                        "network": "BASE",
                        "symbol": "USDC",
                        "status": "ACCEPTED",
                        "requestedAt": "2026-08-27T01:02:03Z",
                        "items": [
                          {
                            "sweepItemId": "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7811",
                            "accountId": "acct_018f3d4a-bf70-7c1a-8f2b-3c4d5e6f7890",
                            "status": "PENDING"
                          }
                        ]
                      },
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  }
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          },
          "422": {
            "$ref": "#/components/responses/UnprocessableEntity"
          }
        }
      }
    },
    "/events/{eventId}/completion": {
      "put": {
        "tags": [
          "Events"
        ],
        "summary": "이벤트 업무 처리 완료 확인",
        "description": "DAW-CORE가 Kafka 이벤트를 `eventId`로 멱등 반영하고 자기 업무 트랜잭션을 커밋한 뒤 호출한다.\n성공 응답을 받은 다음 Kafka offset을 커밋한다.\n\n- 같은 `eventId` 재호출은 최초 `completedAt`을 유지한 같은 결과를 반환한다.\n- `txId`는 한 거래의 상태 이벤트들을 잇는 조회 키일 뿐 완료 키가 아니다.\n- 같은 거래의 `CONFIRMED`, `FINALIZED`, reorg `FAILED`는 서로 다른 `eventId`라 각각 완료해야 한다.\n- 아직 Kafka broker 발행 성공 전인 이벤트는 `409`다. 잠시 뒤 같은 `eventId`로 다시 호출한다.\n- 이 endpoint는 `BCM_DAW_INTEGRATION_ENABLED=true`인 내부/로컬 환경에서만 열린다.\n",
        "operationId": "completeEvent",
        "parameters": [
          {
            "name": "eventId",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string",
              "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
            },
            "description": "BCM이 상태 전이마다 발급한 UUID v7. 소비 dedup과 완료 확인의 유일 키",
            "example": "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891"
          }
        ],
        "responses": {
          "200": {
            "description": "최초 또는 이미 완료된 같은 결과",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/EventCompletionResponse"
                },
                "examples": {
                  "입금_FINALIZED_완료": {
                    "value": {
                      "data": {
                        "eventId": "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891",
                        "consumer": "DAW_CORE",
                        "completedAt": "2026-08-27T01:02:03Z",
                        "txId": "tx-91c",
                        "status": "FINALIZED",
                        "sweepRequestId": null,
                        "sweepItemId": null,
                        "executionId": null
                      },
                      "meta": {
                        "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
                      }
                    }
                  }
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          },
          "409": {
            "$ref": "#/components/responses/Conflict"
          }
        }
      }
    },
    "/transactions/external/{externalTxId}": {
      "parameters": [
        {
          "name": "externalTxId",
          "in": "path",
          "required": true,
          "schema": {
            "type": "string",
            "maxLength": 128
          },
          "description": "제출할 때 실은 우리 요청 키",
          "example": "wd-260713-0042"
        }
      ],
      "get": {
        "tags": [
          "Transactions"
        ],
        "summary": "우리 요청 키로 거래 조회",
        "description": "`externalTxId` 로 제출한 건을 찾는다. 출금은 고객 계정이 아니라 **출금 풀 vault 에서 나가므로 고객 계정 목록에는 나타나지 않는다**\n(출금 풀 계정에 귀속된다) — 호출 쪽이 자기 출금을 아는 키가 `externalTxId` 라 이 경로가 기본이다.\n\n**sweep·밴드S 같은 내부 운영 계열은 이 API 로도 찾을 수 없다**(`404`). 공개 거래는\n입금·출금·내부이체 셋이며, 내부 계열은 공통 이벤트도 내지 않는다.\n\n제출 응답을 못 받았을 때의 확인, 그리고 대사에서 우리 기록과 벤더 기록을 잇는 데 쓴다.\n\n**아직 제출 중이면 `503 SUBMIT_IN_PROGRESS`** 와 `Retry-After` 가 온다 — 제출 API 와 같은 뜻이다.\n그 키를 접수했지만 `txId` 가 아직 확정되지 않았다는 것이고, 오류가 아니라 지연이다.\n`404` 는 \"벤더에 없다\"가 아니라 **\"BCM 이 수용한 거래가 없다\"** 는 뜻이다.\n",
        "operationId": "transactionByExternalTxId",
        "responses": {
          "200": {
            "description": "조회 결과",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/TransferResponse"
                }
              }
            }
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          },
          "503": {
            "$ref": "#/components/responses/SubmitInProgress"
          }
        }
      }
    },
    "/transactions/{txId}": {
      "get": {
        "tags": [
          "Transactions"
        ],
        "summary": "거래 단건 조회",
        "description": "공개 거래 id(`txId`)로 거래 1건을 조회한다. `txId` 는 출금 제출 응답이나 큐 이벤트에서 얻는다.\n\n`txId` 는 **BCM 이 정하는 공개 식별자**다. 제출한 거래는 벤더 tx id 를 그대로 쓰지만,\n입금처럼 벤더 거래 id 가 없는 건은 BCM 이 온체인 값에서 만든 결정적 id 를 쓴다.\n어느 쪽이든 같은 논리 거래에 대해 값이 바뀌지 않는다.\n\n공개 거래는 **입금·출금·내부이체** 셋이다. sweep·밴드S 같은 내부 운영 계열은 `404` 다.\n",
        "operationId": "transactionOf",
        "parameters": [
          {
            "name": "txId",
            "in": "path",
            "required": true,
            "schema": {
              "type": "string"
            },
            "description": "공개 거래 id",
            "example": "tx-local-986a169a89dbf0713ad01d2d17eebd59360b155bfd42fe0a"
          }
        ],
        "responses": {
          "200": {
            "description": "거래",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/TransferResponse"
                }
              }
            }
          },
          "404": {
            "$ref": "#/components/responses/NotFound"
          }
        }
      }
    },
    "/accounts/{accountId}/transactions": {
      "parameters": [
        {
          "$ref": "#/components/parameters/AccountId"
        }
      ],
      "get": {
        "tags": [
          "Transactions"
        ],
        "summary": "거래 목록 조회",
        "description": "거래 이력을 **거래 시각(createdAt) 기준**으로 조회한다 — 기본 최신순, `order=asc` 면 과거→최신. 기간(`after`/`before`)·상태로 좁히고 커서로 페이지네이션한다.\n`order=asc` + `before` 생략 조합이면 마지막 `nextCursor` 를 보관했다가 재요청해 새로 쌓인 내역만 이어받는 증분 폴링이 된다.\n상태 변경 실시간 감지는 이 목록이 아니라 이벤트 큐가 담당한다(매니저의 웹훅 감지와 별개).\n\n**커서는 매니저가 발급한다** — 벤더 커서를 그대로 넘겨주지 않는다. 최초 요청의 필터와 정렬, 그리고 이어받을 위치를\n매니저가 토큰에 담으므로, 마지막 페이지에서도 `nextCursor` 가 채워지고 그 값으로 증분 폴링이 성립한다.\n`hasMore=false` 는 \"지금 시점에 더 없다\"는 뜻이지 커서가 끝났다는 뜻이 아니다.\n\n**매핑되지 않은 자산의 거래는 목록에 없다** — 등록하지 않은 벤더 자산의 관찰은 수용 단계에서 원장 행이 만들어지지 않는다.\n빠뜨린 건은 조용히 버리지 않고 운영 알림으로 올린다(등록 누락이면 고쳐야 할 설정이다).\n",
        "operationId": "transactionsOf",
        "parameters": [
          {
            "name": "after",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string",
              "format": "date-time"
            },
            "description": "시작 시각 — 거래 시각(createdAt) 기준 (ISO 8601 UTC).\n**첫 요청(`cursor` 없음)에는 필수**고, 없으면 `400 VALIDATION_FAILED` 다.\n`cursor` 가 있으면 조회 조건이 토큰에 들어 있어 이 값은 무시되므로 생략한다.\n",
            "example": "2026-07-01T00:00:00.000Z"
          },
          {
            "name": "before",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string",
              "format": "date-time"
            },
            "description": "종료 시각 — 거래 시각(createdAt) 기준 (ISO 8601 UTC). 생략하면 상한 없음 — 증분 폴링(`order=asc`) 조회는 생략한다.",
            "example": "2026-07-13T00:00:00.000Z"
          },
          {
            "name": "order",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string",
              "enum": [
                "asc",
                "desc"
              ],
              "default": "desc"
            },
            "description": "정렬 방향 — 거래 시각(createdAt) 기준. 기본 desc(최신순). 마지막 커서를 보관해 새 내역을 이어받는 증분 폴링은 `asc` 조회에서만 성립한다.",
            "example": "desc"
          },
          {
            "name": "status",
            "in": "query",
            "required": false,
            "schema": {
              "$ref": "#/components/schemas/TxStatus"
            },
            "description": "상태 필터 (선택)",
            "example": "FINALIZED"
          },
          {
            "name": "limit",
            "in": "query",
            "required": false,
            "schema": {
              "type": "integer",
              "minimum": 1,
              "maximum": 500,
              "default": 200
            },
            "description": "페이지 크기 — 기본 200, 최대 500. 1 미만이거나 500 초과면 `400 VALIDATION_FAILED`.",
            "example": 200
          },
          {
            "name": "cursor",
            "in": "query",
            "required": false,
            "schema": {
              "type": "string"
            },
            "description": "다음 위치 커서 — 이전 응답의 `pagination.nextCursor` 를 그대로 넣는다. 불투명 토큰이라 직접 만들거나 해석하지 않는다.\n첫 요청엔 생략. cursor 가 있으면 조회 조건은 토큰이 우선이라 함께 보낸 `after`/`before`·`status`·`order`·`limit` 는 무시된다.\n\n**정렬 기준이 바뀌는 배포에서는 그 전에 발급한 커서를 `400 VALIDATION_FAILED` 로 거절한다.**\n옛 위치를 새 정렬에 그대로 적용하면 건을 빠뜨리거나 겹쳐 준다. 그때는 커서 없이 처음부터 다시 받는다.\n",
            "example": "eyJsYXN0IjoxNzUxMzM2MDAwMDAwfQ"
          }
        ],
        "responses": {
          "200": {
            "description": "거래 목록",
            "content": {
              "application/json": {
                "schema": {
                  "$ref": "#/components/schemas/TransferListResponse"
                }
              }
            }
          },
          "400": {
            "$ref": "#/components/responses/ValidationFailed"
          },
          "404": {
            "$ref": "#/components/responses/AccountNotFound"
          }
        }
      }
    }
  },
  "components": {
    "parameters": {
      "EmployeeNo": {
        "name": "X-Employee-No",
        "in": "header",
        "required": true,
        "schema": {
          "type": "string",
          "maxLength": 6
        },
        "description": "조작한 직원 번호 — 감사 흔적으로 남는다",
        "example": "123456"
      },
      "BranchCode": {
        "name": "X-Branch-Code",
        "in": "header",
        "required": true,
        "schema": {
          "type": "string",
          "maxLength": 4
        },
        "description": "조작한 부점 코드",
        "example": "0001"
      },
      "AccountId": {
        "name": "accountId",
        "in": "path",
        "required": true,
        "schema": {
          "type": "string",
          "maxLength": 64
        },
        "description": "BCM이 발급한 계정 ID (DB bcm_acnt_m.acnt_id). 벤더 vault 또는 wallet ID와 구분한다.",
        "example": "acct_018f3d4a-bf70-7c1a-8f2b-3c4d5e6f7890"
      }
    },
    "schemas": {
      "Network": {
        "type": "object",
        "description": "우리가 쓸 수 있는 체인 하나. 벤더 카탈로그를 하루 한 번 동기화한 우리 표에서 읽는다.",
        "required": [
          "candidateId",
          "displayName",
          "testnet",
          "deprecated",
          "syncedAt",
          "chainModel"
        ],
        "properties": {
          "candidateId": {
            "type": "string",
            "description": "아직 채택하지 않은 행을 가리키는 손잡이 — 목록에서 받은 값을 그대로 되돌려 보내는 용도이고, 뜻을 해석하거나 보관하지 않는다"
          },
          "code": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "우리 네트워크 코드 — 채택했을 때만 채워진다",
            "example": "BASE"
          },
          "displayName": {
            "type": "string",
            "example": "Base"
          },
          "chainId": {
            "oneOf": [
              {
                "type": "integer",
                "format": "int64"
              },
              {
                "type": "null"
              }
            ],
            "description": "EIP-155 chainId — EVM 계열만",
            "example": 8453
          },
          "testnet": {
            "type": "boolean"
          },
          "deprecated": {
            "type": "boolean",
            "description": "더는 권장되지 않는 체인"
          },
          "syncedAt": {
            "type": "string",
            "description": "이 행을 마지막으로 동기화한 시각",
            "example": "20260806031045"
          },
          "chainModel": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "EVM",
                  "SOLANA"
                ]
              },
              {
                "type": "null"
              }
            ],
            "description": "체인의 계정·자산 모델 — 채택한 네트워크는 반드시 있고, 채택한 적이 없는 후보는 비어 있다"
          }
        }
      },
      "NetworkListResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/Network"
            }
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "NetworkResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/Network"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdoptNetworkRequest": {
        "type": "object",
        "required": [
          "candidateId",
          "chainModel"
        ],
        "properties": {
          "candidateId": {
            "type": "string",
            "description": "네트워크 목록에서 받은 값을 그대로 넣는다"
          },
          "chainModel": {
            "type": "string",
            "enum": [
              "EVM",
              "SOLANA"
            ],
            "description": "채택하는 체인의 계정·자산 모델. 제공자 정보가 아니라 체인의 속성이며, 자산 키 생성과 주소 동일성 비교가 이 값을 쓴다"
          }
        }
      },
      "AssetCandidate": {
        "type": "object",
        "description": "Fireblocks 자산 후보 하나. 미지원 네트워크 후보는 읽기 전용 비교 정보다.",
        "required": [
          "network",
          "networkDisplayName",
          "chainId",
          "testnet",
          "symbol",
          "displayName",
          "fireblocksAssetId",
          "assetClass",
          "decimals",
          "contractAddress",
          "catalogSyncedAt",
          "registrationAllowed",
          "registrationDisabledReason"
        ],
        "properties": {
          "network": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "BCM 지원 네트워크 코드. 미지원 Fireblocks 네트워크는 null",
            "example": "BASE"
          },
          "networkDisplayName": {
            "type": "string",
            "description": "Fireblocks가 표시하는 네트워크 이름",
            "example": "Base"
          },
          "chainId": {
            "oneOf": [
              {
                "type": "integer",
                "format": "int64"
              },
              {
                "type": "null"
              }
            ],
            "example": 8453
          },
          "testnet": {
            "type": "boolean",
            "description": "시험망 여부",
            "example": false
          },
          "symbol": {
            "type": "string",
            "description": "벤더가 이 자산에 붙인 표기 — 등록할 때 이 값을 그대로 쓰거나 우리 값을 따로 정한다",
            "example": "USDC"
          },
          "displayName": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "example": "USD Coin"
          },
          "fireblocksAssetId": {
            "type": "string",
            "description": "Fireblocks Console·지원 문의와 대조할 자산 식별자. 일반 업무 API에는 노출하지 않는다",
            "example": "USDC_BASE"
          },
          "assetClass": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "example": "FT"
          },
          "decimals": {
            "oneOf": [
              {
                "type": "integer"
              },
              {
                "type": "null"
              }
            ],
            "example": 6
          },
          "contractAddress": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "네이티브 자산은 null",
            "example": "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
          },
          "catalogSyncedAt": {
            "type": "string",
            "description": "이 후보가 속한 네트워크 자산 카탈로그의 마지막 성공 동기화 UTC 시각",
            "example": "20260824010000"
          },
          "registrationAllowed": {
            "type": "boolean",
            "description": "현재 BCM 지원 경계에서 이 후보를 등록할 수 있는지 서버가 판정한 값",
            "example": true
          },
          "registrationDisabledReason": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "등록할 수 없을 때 운영자가 확인할 이유",
            "example": null
          }
        }
      },
      "AssetCatalogSource": {
        "type": "object",
        "required": [
          "network",
          "networkDisplayName",
          "state",
          "catalogSyncedAt"
        ],
        "properties": {
          "network": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "example": "BASE"
          },
          "networkDisplayName": {
            "type": "string",
            "example": "Base"
          },
          "state": {
            "type": "string",
            "enum": [
              "READY",
              "STALE",
              "NEVER_SYNCED"
            ],
            "description": "마지막 성공이 48시간 이내면 READY, 더 오래됐으면 STALE, 성공 이력이 없으면 NEVER_SYNCED"
          },
          "catalogSyncedAt": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "example": "20260824010000"
          }
        }
      },
      "AssetCandidateSearchResult": {
        "type": "object",
        "required": [
          "items",
          "sources"
        ],
        "properties": {
          "items": {
            "type": "array",
            "maxItems": 50,
            "items": {
              "$ref": "#/components/schemas/AssetCandidate"
            }
          },
          "sources": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AssetCatalogSource"
            }
          }
        }
      },
      "AssetCandidateListResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AssetCandidateSearchResult"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AssetMapping": {
        "type": "object",
        "description": "등록된 (네트워크, 토큰) 하나. 벤더 식별자는 데이터셋 원천의 벤더 이름을 붙인 필드 하나만 채워진다 —\nFireblocks 원천 `{ \"fireblocksAssetId\": \"USDC_BASE\", \"dfnsAssetKey\": null }`,\nDfns 원천 `{ \"fireblocksAssetId\": null, \"dfnsAssetKey\": \"EthereumSepolia:Erc20:0x1c7d…7238\" }`.\n",
        "required": [
          "network",
          "symbol",
          "fireblocksAssetId",
          "dfnsAssetKey",
          "decimals",
          "registeredAt"
        ],
        "example": {
          "network": "BASE",
          "symbol": "USDC",
          "fireblocksAssetId": "USDC_BASE",
          "dfnsAssetKey": null,
          "contractAddress": "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
          "decimals": 6,
          "registeredAt": "20260806031045"
        },
        "properties": {
          "network": {
            "type": "string",
            "example": "BASE"
          },
          "symbol": {
            "type": "string",
            "example": "USDC"
          },
          "fireblocksAssetId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "현재 매핑이 사용하는 Fireblocks 자산 식별자. Fireblocks 원천에서만 채워지고 Dfns 원천은 null",
            "example": "USDC_BASE"
          },
          "dfnsAssetKey": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "현재 매핑이 사용하는 Dfns 자산 키 `<Network>:Native` 또는 `<Network>:<kind>:<locator>`(ERC-20은 소문자 컨트랙트, Solana는 `Spl`/`Spl2022`와 mint). Dfns 원천에서만 채워지고 Fireblocks 원천은 null",
            "example": "EthereumSepolia:Erc20:0x1c7d4b196cb0c7b01d743fbc6116a902379c7238"
          },
          "contractAddress": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "네이티브 자산은 null",
            "example": "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
          },
          "decimals": {
            "oneOf": [
              {
                "type": "integer",
                "minimum": 0,
                "maximum": 255
              },
              {
                "type": "null"
              }
            ],
            "description": "등록 시점에 확정한 소수 자릿수. Fireblocks 원천은 카탈로그 해소값, Dfns 원천은 운영자 등록값이다. 정밀도를 저장하기 전에 등록된 기존 매핑은 null",
            "example": 6
          },
          "registeredAt": {
            "type": "string",
            "example": "20260806031045"
          }
        }
      },
      "AssetMappingListResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AssetMapping"
            }
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AssetMappingResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AssetMapping"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminTransactionInvestigationResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AdminTransactionInvestigation"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminSweepRequestInvestigationResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AdminSweepRequestInvestigation"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminSweepRequestInvestigation": {
        "type": "object",
        "required": [
          "sweepRequestId",
          "externalSweepRequestId",
          "requester",
          "requesterEmployeeNo",
          "requesterBranchCode",
          "network",
          "symbol",
          "status",
          "itemCount",
          "requestedAt",
          "finishedAt",
          "retryable",
          "nextAction",
          "items",
          "truncatedSources"
        ],
        "properties": {
          "sweepRequestId": {
            "type": "string",
            "maxLength": 36
          },
          "externalSweepRequestId": {
            "type": "string",
            "maxLength": 128
          },
          "requester": {
            "type": "string",
            "enum": [
              "DAW_CORE"
            ]
          },
          "requesterEmployeeNo": {
            "type": "string",
            "maxLength": 6
          },
          "requesterBranchCode": {
            "type": "string",
            "maxLength": 4
          },
          "network": {
            "type": "string",
            "maxLength": 20
          },
          "symbol": {
            "type": "string",
            "maxLength": 16
          },
          "status": {
            "type": "string",
            "enum": [
              "ACCEPTED",
              "BLOCKED",
              "PROCESSING",
              "COMPLETED",
              "PARTIAL",
              "FAILED"
            ]
          },
          "itemCount": {
            "type": "integer",
            "minimum": 1
          },
          "requestedAt": {
            "type": "string",
            "format": "date-time"
          },
          "finishedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "retryable": {
            "type": "boolean"
          },
          "nextAction": {
            "type": "string"
          },
          "items": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminSweepRequestItem"
            }
          },
          "truncatedSources": {
            "type": "array",
            "items": {
              "type": "string"
            }
          }
        }
      },
      "AdminSweepRequestItem": {
        "type": "object",
        "required": [
          "sweepItemId",
          "sequence",
          "accountId",
          "status",
          "lastFailureCode",
          "retryable",
          "nextAction",
          "sourceEvents",
          "executions",
          "resultEvents"
        ],
        "properties": {
          "sweepItemId": {
            "type": "string",
            "maxLength": 36
          },
          "sequence": {
            "type": "integer",
            "minimum": 1
          },
          "accountId": {
            "type": "string",
            "maxLength": 64
          },
          "status": {
            "type": "string",
            "enum": [
              "PENDING",
              "PROCESSING",
              "COMPLETED",
              "FAILED"
            ]
          },
          "lastFailureCode": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 64
              },
              {
                "type": "null"
              }
            ]
          },
          "retryable": {
            "type": "boolean"
          },
          "nextAction": {
            "type": "string"
          },
          "sourceEvents": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminSweepLinkedEvent"
            }
          },
          "executions": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminSweepExecution"
            }
          },
          "resultEvents": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminSweepLinkedEvent"
            }
          }
        }
      },
      "AdminSweepExecution": {
        "type": "object",
        "required": [
          "executionId",
          "externalTransactionId",
          "status",
          "operatorAccountId",
          "contractAddress",
          "policyVersionId",
          "policySnapshotHash",
          "contractVersionId",
          "contractEvidenceId",
          "requestedAmount",
          "actualAmount",
          "itemStatus",
          "failureCode",
          "logIndex",
          "transactionId",
          "transactionHash",
          "requestedAt",
          "finishedAt"
        ],
        "properties": {
          "executionId": {
            "type": "string",
            "maxLength": 36
          },
          "externalTransactionId": {
            "type": "string",
            "maxLength": 128
          },
          "status": {
            "type": "string"
          },
          "operatorAccountId": {
            "type": "string",
            "maxLength": 64
          },
          "contractAddress": {
            "type": "string",
            "maxLength": 128
          },
          "policyVersionId": {
            "type": "string",
            "maxLength": 36
          },
          "policySnapshotHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "contractVersionId": {
            "type": "string",
            "maxLength": 36
          },
          "contractEvidenceId": {
            "type": "string",
            "maxLength": 36
          },
          "requestedAmount": {
            "type": "string"
          },
          "actualAmount": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "itemStatus": {
            "type": "string"
          },
          "failureCode": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 64
              },
              {
                "type": "null"
              }
            ]
          },
          "logIndex": {
            "oneOf": [
              {
                "type": "integer"
              },
              {
                "type": "null"
              }
            ]
          },
          "transactionId": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 64
              },
              {
                "type": "null"
              }
            ]
          },
          "transactionHash": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 128
              },
              {
                "type": "null"
              }
            ]
          },
          "requestedAt": {
            "type": "string",
            "format": "date-time"
          },
          "finishedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminSweepLinkedEvent": {
        "type": "object",
        "required": [
          "eventId",
          "eventType",
          "outboxStatus",
          "chainStatus",
          "itemOutcome",
          "failureCode",
          "publishedAt",
          "dawCompletedAt"
        ],
        "properties": {
          "eventId": {
            "type": "string",
            "maxLength": 36
          },
          "eventType": {
            "type": "string"
          },
          "outboxStatus": {
            "type": "string",
            "enum": [
              "P",
              "D",
              "F",
              "S"
            ]
          },
          "chainStatus": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "itemOutcome": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "failureCode": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 64
              },
              {
                "type": "null"
              }
            ]
          },
          "publishedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "dawCompletedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminSweepOperationsResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AdminSweepOperations"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminSweepOperations": {
        "type": "object",
        "required": [
          "acceptedRequestCount",
          "blockedRequestCount",
          "processingRequestCount",
          "partialRequestCount",
          "failedRequestCount",
          "pendingItemCount",
          "processingItemCount",
          "oldestPendingRequestedAt",
          "pendingEventCount",
          "failedEventCount",
          "awaitingDawCompletionCount",
          "oldestAwaitingDawCompletionAt"
        ],
        "properties": {
          "acceptedRequestCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "blockedRequestCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "processingRequestCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "partialRequestCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "failedRequestCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "pendingItemCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "processingItemCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "oldestPendingRequestedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "pendingEventCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "failedEventCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "awaitingDawCompletionCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "oldestAwaitingDawCompletionAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminTransactionInvestigation": {
        "type": "object",
        "required": [
          "summary",
          "timeline",
          "boosts",
          "sweepExecution",
          "allowances",
          "feeQuotes",
          "truncatedSources"
        ],
        "properties": {
          "summary": {
            "$ref": "#/components/schemas/AdminTransactionInvestigationSummary"
          },
          "timeline": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminTransactionTimelineEntry"
            }
          },
          "boosts": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminTransactionBoost"
            }
          },
          "sweepExecution": {
            "oneOf": [
              {
                "$ref": "#/components/schemas/AdminTransactionSweepExecution"
              },
              {
                "type": "null"
              }
            ]
          },
          "allowances": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminTransactionAllowance"
            }
          },
          "feeQuotes": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminTransactionFeeQuote"
            }
          },
          "truncatedSources": {
            "type": "array",
            "items": {
              "type": "string"
            },
            "description": "상세당 100건 상한으로 일부만 반환된 원장 이름"
          }
        }
      },
      "AdminTransactionInvestigationSummary": {
        "type": "object",
        "required": [
          "rootTransactionId",
          "activeTransactionId",
          "externalTransactionId",
          "transactionHash",
          "accountId",
          "network",
          "symbol",
          "transactionType",
          "status",
          "confirmationCount",
          "vendorSubStatus",
          "vendorNetworkStatus",
          "submissionStatus",
          "amount",
          "senderAccountId",
          "receiverType",
          "receiverValue",
          "sweepExecutionId",
          "submissionRequestedAt",
          "submissionRespondedAt",
          "vendorCreatedAt",
          "firstDetectedAt",
          "lastChangedAt",
          "reconciliationCheckedAt",
          "reconciliationCheckCount",
          "reconciliationStoppedAt"
        ],
        "properties": {
          "rootTransactionId": {
            "type": "string"
          },
          "activeTransactionId": {
            "type": "string"
          },
          "externalTransactionId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "transactionHash": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "accountId": {
            "type": "string"
          },
          "network": {
            "type": "string"
          },
          "symbol": {
            "type": "string"
          },
          "transactionType": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "status": {
            "type": "string"
          },
          "confirmationCount": {
            "type": "integer"
          },
          "vendorSubStatus": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "vendorNetworkStatus": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "submissionStatus": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "amount": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "정밀 십진 문자열"
          },
          "senderAccountId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "receiverType": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "receiverValue": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "sweepExecutionId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "submissionRequestedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "submissionRespondedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "vendorCreatedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ],
            "description": "벤더 시간축. **첫 벤더 관찰 전에는 `null`** 이다 — 제출 마감이 거래 행을 먼저 만들고\n제출 응답은 벤더 시각을 주지 않는다. BCM 수용 시각으로 대신 채우면 대사가 벤더 시각끼리\n비교한다는 규칙이 깨지므로 지어내지 않는다. 최초 감지 시각은 `firstDetectedAt` 이다.\n"
          },
          "firstDetectedAt": {
            "type": "string",
            "format": "date-time"
          },
          "lastChangedAt": {
            "type": "string",
            "format": "date-time"
          },
          "reconciliationCheckedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "reconciliationCheckCount": {
            "type": "integer"
          },
          "reconciliationStoppedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminTransactionTimelineEntry": {
        "type": "object",
        "required": [
          "source",
          "code",
          "status",
          "observedAt",
          "identifier",
          "deliveryStatus",
          "dawCompletedAt"
        ],
        "properties": {
          "source": {
            "type": "string"
          },
          "code": {
            "type": "string"
          },
          "status": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "observedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "identifier": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "deliveryStatus": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "P",
                  "D",
                  "F",
                  "S"
                ]
              },
              {
                "type": "null"
              }
            ]
          },
          "dawCompletedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminTransactionBoost": {
        "type": "object",
        "required": [
          "attemptSequence",
          "externalTransactionId",
          "status",
          "replacedTransactionId",
          "replacedTransactionHash",
          "newTransactionId",
          "feeLevel",
          "gasless",
          "requestedAt",
          "respondedAt"
        ],
        "properties": {
          "attemptSequence": {
            "type": "integer"
          },
          "externalTransactionId": {
            "type": "string"
          },
          "status": {
            "type": "string"
          },
          "replacedTransactionId": {
            "type": "string"
          },
          "replacedTransactionHash": {
            "type": "string"
          },
          "newTransactionId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "feeLevel": {
            "type": "string"
          },
          "gasless": {
            "type": "boolean"
          },
          "requestedAt": {
            "type": "string",
            "format": "date-time"
          },
          "respondedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminTransactionSweepExecution": {
        "type": "object",
        "required": [
          "executionId",
          "externalTransactionId",
          "status",
          "operatorAccountId",
          "contractAddress",
          "requestedTotalAmount",
          "actualTotalAmount",
          "transactionId",
          "transactionHash",
          "requestedAt",
          "finishedAt",
          "items"
        ],
        "properties": {
          "executionId": {
            "type": "string"
          },
          "externalTransactionId": {
            "type": "string"
          },
          "status": {
            "type": "string"
          },
          "operatorAccountId": {
            "type": "string"
          },
          "contractAddress": {
            "type": "string"
          },
          "requestedTotalAmount": {
            "type": "string"
          },
          "actualTotalAmount": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "transactionId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "transactionHash": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "requestedAt": {
            "type": "string",
            "format": "date-time"
          },
          "finishedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "items": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminTransactionSweepItem"
            }
          }
        }
      },
      "AdminTransactionSweepItem": {
        "type": "object",
        "required": [
          "sequence",
          "accountId",
          "sourceAddress",
          "requestedAmount",
          "actualAmount",
          "status",
          "failureCode",
          "logIndex"
        ],
        "properties": {
          "sequence": {
            "type": "integer"
          },
          "accountId": {
            "type": "string"
          },
          "sourceAddress": {
            "type": "string"
          },
          "requestedAmount": {
            "type": "string"
          },
          "actualAmount": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "status": {
            "type": "string"
          },
          "failureCode": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "logIndex": {
            "oneOf": [
              {
                "type": "integer"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminTransactionAllowance": {
        "type": "object",
        "required": [
          "accountId",
          "network",
          "symbol",
          "contractAddress",
          "cap",
          "observedAllowance",
          "status",
          "checkedAt"
        ],
        "properties": {
          "accountId": {
            "type": "string"
          },
          "network": {
            "type": "string"
          },
          "symbol": {
            "type": "string"
          },
          "contractAddress": {
            "type": "string"
          },
          "cap": {
            "type": "string"
          },
          "observedAllowance": {
            "type": "string"
          },
          "status": {
            "type": "string"
          },
          "checkedAt": {
            "type": "string",
            "format": "date-time"
          }
        }
      },
      "AdminTransactionFeeQuote": {
        "type": "object",
        "required": [
          "context",
          "level",
          "observedAt",
          "feePerByte",
          "gasPrice",
          "networkFee",
          "baseFee",
          "priorityFee"
        ],
        "properties": {
          "context": {
            "type": "string"
          },
          "level": {
            "type": "string"
          },
          "observedAt": {
            "type": "string",
            "format": "date-time"
          },
          "feePerByte": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "gasPrice": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "networkFee": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "baseFee": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "priorityFee": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "StartAdminVaultReconciliationRequest": {
        "type": "object",
        "properties": {
          "q": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 128
              },
              {
                "type": "null"
              }
            ],
            "description": "accountId, ref, Fireblocks vault id 또는 이름 검색"
          }
        }
      },
      "AdminVaultReconciliationRunResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AdminVaultReconciliationRun"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminVaultReconciliationResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AdminVaultReconciliation"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminVaultReconciliation": {
        "type": "object",
        "required": [
          "run",
          "items",
          "nextCursor"
        ],
        "properties": {
          "run": {
            "$ref": "#/components/schemas/AdminVaultReconciliationRun"
          },
          "items": {
            "type": "array",
            "maxItems": 100,
            "items": {
              "$ref": "#/components/schemas/AdminVault"
            }
          },
          "nextCursor": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminVaultReconciliationRun": {
        "type": "object",
        "required": [
          "runId",
          "query",
          "status",
          "vendorPageCount",
          "vendorVaultCount",
          "resultCount",
          "failureCode",
          "requestedAt",
          "startedAt",
          "finishedAt"
        ],
        "properties": {
          "runId": {
            "type": "string"
          },
          "query": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "status": {
            "type": "string",
            "enum": [
              "ACCEPTED",
              "RUNNING",
              "COMPLETED",
              "PARTIAL",
              "FAILED"
            ]
          },
          "vendorPageCount": {
            "type": "integer",
            "minimum": 0
          },
          "vendorVaultCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "resultCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "failureCode": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "requestedAt": {
            "type": "string",
            "format": "date-time"
          },
          "startedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "finishedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminVault": {
        "type": "object",
        "required": [
          "reconciliationStatus",
          "accountId",
          "accountType",
          "ref",
          "vendorVaultId",
          "vendorVaultName",
          "walletCount",
          "registeredAt"
        ],
        "properties": {
          "reconciliationStatus": {
            "type": "string",
            "enum": [
              "MANAGED",
              "UNMANAGED",
              "MISSING_IN_FIREBLOCKS"
            ]
          },
          "accountId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "accountType": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "CUSTOMER",
                  "SYSTEM"
                ]
              },
              {
                "type": "null"
              }
            ]
          },
          "ref": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "vendorVaultId": {
            "type": "string"
          },
          "vendorVaultName": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "walletCount": {
            "oneOf": [
              {
                "type": "integer"
              },
              {
                "type": "null"
              }
            ]
          },
          "registeredAt": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminContractListResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminContract"
            }
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminContract": {
        "type": "object",
        "required": [
          "versionId",
          "scopeId",
          "network",
          "use",
          "version",
          "address",
          "state",
          "runtimeCodeHash",
          "evidenceStatus",
          "evidenceValidUntil",
          "active"
        ],
        "properties": {
          "versionId": {
            "type": "string"
          },
          "scopeId": {
            "type": "string"
          },
          "network": {
            "type": "string"
          },
          "use": {
            "type": "string"
          },
          "version": {
            "type": "string"
          },
          "address": {
            "type": "string"
          },
          "state": {
            "type": "string",
            "enum": [
              "CANDIDATE",
              "VERIFIED",
              "ACTIVE",
              "PAUSED",
              "RETIRED"
            ]
          },
          "runtimeCodeHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "evidenceStatus": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "VALID",
                  "INVALID",
                  "STALE",
                  "ERROR"
                ]
              },
              {
                "type": "null"
              }
            ]
          },
          "evidenceValidUntil": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "active": {
            "type": "boolean"
          }
        }
      },
      "AdminPolicyListResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminPolicy"
            }
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminPolicy": {
        "type": "object",
        "required": [
          "versionId",
          "scopeId",
          "versionNumber",
          "schemaVersion",
          "state",
          "policyHash",
          "ceilingPassed",
          "active",
          "registeredAt"
        ],
        "properties": {
          "versionId": {
            "type": "string"
          },
          "scopeId": {
            "type": "string"
          },
          "versionNumber": {
            "type": "integer"
          },
          "schemaVersion": {
            "type": "string"
          },
          "state": {
            "type": "string",
            "enum": [
              "DRAFT",
              "IN_REVIEW",
              "APPROVED",
              "ACTIVE",
              "SUPERSEDED"
            ]
          },
          "policyHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "ceilingPassed": {
            "type": "boolean"
          },
          "active": {
            "type": "boolean"
          },
          "registeredAt": {
            "type": "string",
            "format": "date-time"
          }
        }
      },
      "AdminBandSListResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "type": "array",
            "maxItems": 100,
            "items": {
              "$ref": "#/components/schemas/AdminBandS"
            }
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminBandS": {
        "type": "object",
        "required": [
          "proposalId",
          "sourceProposalId",
          "snapshotId",
          "sourceRequestId",
          "policyVersionId",
          "snapshotHash",
          "inputHash",
          "observedAt",
          "expiresAt",
          "inputComplete",
          "issueCodes",
          "totalAssetKrwAmount",
          "observedHotKrwAmount",
          "observedColdKrwAmount",
          "effectiveHotKrwAmount",
          "hotRatio",
          "lowerRatio",
          "targetRatio",
          "upperRatio",
          "direction",
          "proposalHash",
          "totalKrwAmount",
          "afterHotRatio",
          "state",
          "requestId",
          "requestState",
          "approvalCount",
          "requiredApprovals",
          "executionId",
          "executionStatus",
          "reservedAt",
          "executionReady",
          "disabledReasons",
          "items"
        ],
        "properties": {
          "proposalId": {
            "type": "string"
          },
          "sourceProposalId": {
            "type": "string"
          },
          "snapshotId": {
            "type": "string"
          },
          "sourceRequestId": {
            "type": "string"
          },
          "policyVersionId": {
            "type": "string"
          },
          "snapshotHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "inputHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "observedAt": {
            "type": "string",
            "format": "date-time"
          },
          "expiresAt": {
            "type": "string",
            "format": "date-time"
          },
          "inputComplete": {
            "type": "boolean"
          },
          "issueCodes": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "totalAssetKrwAmount": {
            "type": "string"
          },
          "observedHotKrwAmount": {
            "type": "string"
          },
          "observedColdKrwAmount": {
            "type": "string"
          },
          "effectiveHotKrwAmount": {
            "type": "string"
          },
          "hotRatio": {
            "type": "string"
          },
          "lowerRatio": {
            "type": "string"
          },
          "targetRatio": {
            "type": "string"
          },
          "upperRatio": {
            "type": "string"
          },
          "direction": {
            "type": "string",
            "enum": [
              "HOT_TO_COLD",
              "COLD_TO_HOT"
            ]
          },
          "proposalHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "totalKrwAmount": {
            "type": "string"
          },
          "afterHotRatio": {
            "type": "string"
          },
          "state": {
            "type": "string",
            "enum": [
              "PROPOSED",
              "BLOCKED",
              "STALE",
              "PENDING",
              "APPROVED",
              "REJECTED",
              "EXPIRED",
              "EXECUTING",
              "PARTIAL",
              "COMPLETED",
              "FAILED"
            ]
          },
          "requestId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "requestState": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "PENDING",
                  "APPROVED",
                  "REJECTED",
                  "EXPIRED",
                  "EXECUTED"
                ]
              },
              {
                "type": "null"
              }
            ]
          },
          "approvalCount": {
            "type": "integer"
          },
          "requiredApprovals": {
            "type": "integer"
          },
          "executionId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "executionStatus": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "EXECUTING",
                  "PARTIAL",
                  "COMPLETED",
                  "FAILED"
                ]
              },
              {
                "type": "null"
              }
            ]
          },
          "reservedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "executionReady": {
            "type": "boolean",
            "description": "인증 경계를 제외한 도메인 예약 조건 충족 여부"
          },
          "disabledReasons": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "items": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminBandSItem"
            }
          }
        }
      },
      "AdminBandSItem": {
        "type": "object",
        "required": [
          "sequence",
          "dependsOnSequence",
          "legType",
          "network",
          "tokenSymbol",
          "sourceVaultId",
          "destinationVaultId",
          "destinationAddress",
          "amount",
          "krwAmount",
          "expectedFeeAmount",
          "itemHash",
          "executable",
          "blockReason",
          "executionStatus"
        ],
        "properties": {
          "sequence": {
            "type": "integer"
          },
          "dependsOnSequence": {
            "oneOf": [
              {
                "type": "integer"
              },
              {
                "type": "null"
              }
            ]
          },
          "legType": {
            "type": "string",
            "enum": [
              "INTERNAL_TO_EGRESS",
              "EXTERNAL_COLD",
              "COLD_DEPOSIT",
              "HOT_REDISTRIBUTE"
            ],
            "description": "INTERNAL_TO_EGRESS는 영속/API 호환 코드명이며 1차 설계에서는 출금 풀 등 hot vault에서 omnibus로 회수하는 내부이체를 뜻한다."
          },
          "network": {
            "type": "string"
          },
          "tokenSymbol": {
            "type": "string"
          },
          "sourceVaultId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "destinationVaultId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "destinationAddress": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "amount": {
            "type": "string"
          },
          "krwAmount": {
            "type": "string"
          },
          "expectedFeeAmount": {
            "type": "string"
          },
          "itemHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "executable": {
            "type": "boolean"
          },
          "blockReason": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "executionStatus": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "RESERVED",
                  "SUBMIT_INTENT",
                  "SUBMITTED",
                  "FINALIZED",
                  "FAILED",
                  "RECONCILED",
                  "RELEASED"
                ]
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminExecutionGateOverviewResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AdminExecutionGateOverview"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminRuntimeReadinessResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AdminRuntimeReadiness"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminRuntimeReadiness": {
        "type": "object",
        "required": [
          "observedAt",
          "webhook",
          "sweep"
        ],
        "properties": {
          "observedAt": {
            "type": "string",
            "format": "date-time"
          },
          "webhook": {
            "$ref": "#/components/schemas/AdminWebhookRuntime"
          },
          "sweep": {
            "$ref": "#/components/schemas/AdminSweepRuntime"
          }
        }
      },
      "AdminSweepRuntime": {
        "type": "object",
        "required": [
          "enabled",
          "state",
          "activeContractCount",
          "activePolicyCount",
          "executorLastRunAt",
          "executorLastSucceededAt",
          "disabledReasons"
        ],
        "properties": {
          "enabled": {
            "type": "boolean"
          },
          "state": {
            "type": "string",
            "enum": [
              "READY",
              "DISABLED"
            ]
          },
          "activeContractCount": {
            "type": "integer",
            "minimum": 0
          },
          "activePolicyCount": {
            "type": "integer",
            "minimum": 0
          },
          "executorLastRunAt": {
            "description": "bcm-bat sweep 실행기가 마지막 주기를 시작한 UTC 시각. 관찰 이력이 없으면 null이다.",
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "executorLastSucceededAt": {
            "description": "bcm-bat sweep 실행기가 마지막 주기를 성공 완료한 UTC 시각. 시작 시각보다 과거이거나 3분 넘게 오래되면 READY가 아니다.",
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "disabledReasons": {
            "type": "array",
            "items": {
              "type": "string"
            }
          }
        }
      },
      "AdminWebhookRuntime": {
        "type": "object",
        "required": [
          "state",
          "lastReceivedAt",
          "pendingInboxCount",
          "poisonedInboxCount",
          "pendingOutboxCount",
          "poisonedOutboxCount",
          "statusPath"
        ],
        "properties": {
          "state": {
            "type": "string",
            "enum": [
              "NEVER_RECEIVED",
              "HEALTHY",
              "BACKLOG",
              "POISONED"
            ]
          },
          "lastReceivedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "pendingInboxCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "poisonedInboxCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "pendingOutboxCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "poisonedOutboxCount": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "statusPath": {
            "type": "string",
            "enum": [
              "/admin/emergency"
            ]
          }
        }
      },
      "AdminExecutionGateOverview": {
        "type": "object",
        "required": [
          "observedAt",
          "truncated",
          "gates",
          "externalControls",
          "allowanceRevocations",
          "webhookRecoveries",
          "resumes"
        ],
        "properties": {
          "observedAt": {
            "type": "string",
            "format": "date-time"
          },
          "truncated": {
            "type": "boolean"
          },
          "gates": {
            "type": "array",
            "maxItems": 300,
            "items": {
              "$ref": "#/components/schemas/AdminExecutionGate"
            }
          },
          "externalControls": {
            "type": "array",
            "maxItems": 100,
            "items": {
              "$ref": "#/components/schemas/AdminExternalControlEvidence"
            }
          },
          "allowanceRevocations": {
            "type": "array",
            "maxItems": 100,
            "items": {
              "$ref": "#/components/schemas/AdminAllowanceRevocation"
            }
          },
          "webhookRecoveries": {
            "type": "array",
            "maxItems": 100,
            "items": {
              "$ref": "#/components/schemas/AdminWebhookRecovery"
            }
          },
          "resumes": {
            "type": "array",
            "maxItems": 100,
            "items": {
              "$ref": "#/components/schemas/AdminExecutionGateResume"
            }
          }
        }
      },
      "AdminExecutionGateResume": {
        "type": "object",
        "required": [
          "resumeId",
          "requestId",
          "network",
          "type",
          "state",
          "stoppedEventId",
          "contractVersionId",
          "contractEvidenceId",
          "revocationExecutionId",
          "causeEvidenceUri",
          "causeEvidenceHash",
          "requestedAt",
          "expiresAt",
          "requestedByEmployeeNo",
          "approvalCount",
          "requiredApprovals",
          "securityApprovalCount",
          "latestCheckStatus",
          "latestCheckObservedAt",
          "latestCheckValidUntil",
          "issues",
          "resumeReady",
          "disabledReasons",
          "retryable",
          "retryCondition",
          "statusPath"
        ],
        "properties": {
          "resumeId": {
            "type": "string",
            "maxLength": 36
          },
          "requestId": {
            "type": "string",
            "maxLength": 36
          },
          "network": {
            "type": "string",
            "maxLength": 20
          },
          "type": {
            "type": "string",
            "enum": [
              "WITHDRAWAL",
              "SWEEP",
              "APPROVE"
            ]
          },
          "state": {
            "type": "string",
            "enum": [
              "PENDING",
              "APPROVED",
              "BLOCKED",
              "READY",
              "RESUMED"
            ]
          },
          "stoppedEventId": {
            "type": "string",
            "maxLength": 36
          },
          "contractVersionId": {
            "type": "string",
            "maxLength": 36
          },
          "contractEvidenceId": {
            "type": "string",
            "maxLength": 36
          },
          "revocationExecutionId": {
            "type": "string",
            "maxLength": 36
          },
          "causeEvidenceUri": {
            "type": "string",
            "maxLength": 512
          },
          "causeEvidenceHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "requestedAt": {
            "type": "string",
            "format": "date-time"
          },
          "expiresAt": {
            "type": "string",
            "format": "date-time"
          },
          "requestedByEmployeeNo": {
            "type": "string",
            "maxLength": 6
          },
          "approvalCount": {
            "type": "integer",
            "minimum": 0
          },
          "requiredApprovals": {
            "type": "integer",
            "minimum": 2,
            "maximum": 2
          },
          "securityApprovalCount": {
            "type": "integer",
            "minimum": 0
          },
          "latestCheckStatus": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "READY",
                  "DRIFT",
                  "STALE",
                  "UNCONFIRMED",
                  "ERROR"
                ]
              },
              {
                "type": "null"
              }
            ]
          },
          "latestCheckObservedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "latestCheckValidUntil": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "issues": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "resumeReady": {
            "type": "boolean"
          },
          "disabledReasons": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "retryable": {
            "type": "boolean"
          },
          "retryCondition": {
            "type": "string"
          },
          "statusPath": {
            "type": "string",
            "enum": [
              "/admin/execution-gates"
            ]
          }
        }
      },
      "AdminWebhookRecovery": {
        "type": "object",
        "required": [
          "requestId",
          "webhookId",
          "state",
          "scope",
          "requiredEvents",
          "requestedAt",
          "requestedByEmployeeNo",
          "approvedAt",
          "approvedByEmployeeNo",
          "reason",
          "workTicket",
          "latestEvent",
          "callType",
          "calledAt",
          "resultAt",
          "previousStatus",
          "currentStatus",
          "missingRequiredEvents",
          "scopeFrom",
          "scopeTo",
          "scheduledNotificationCount",
          "errorCode",
          "retryable",
          "retryCondition",
          "statusPath"
        ],
        "properties": {
          "requestId": {
            "type": "string",
            "maxLength": 36
          },
          "webhookId": {
            "type": "string",
            "maxLength": 64
          },
          "state": {
            "type": "string",
            "enum": [
              "ACCEPTED",
              "IN_PROGRESS",
              "AMBIGUOUS",
              "FAILED",
              "COMPLETED"
            ]
          },
          "scope": {
            "type": "string",
            "enum": [
              "FAILED_LAST_24H"
            ]
          },
          "requiredEvents": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "requestedAt": {
            "type": "string",
            "format": "date-time"
          },
          "requestedByEmployeeNo": {
            "type": "string",
            "maxLength": 6
          },
          "approvedAt": {
            "type": "string",
            "format": "date-time"
          },
          "approvedByEmployeeNo": {
            "type": "string",
            "maxLength": 6
          },
          "reason": {
            "type": "string",
            "maxLength": 1000
          },
          "workTicket": {
            "type": "string",
            "maxLength": 128
          },
          "latestEvent": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "STATUS_INTENT",
                  "STATUS_OBSERVED",
                  "ACTIVATE_INTENT",
                  "ACTIVATED",
                  "RESEND_INTENT",
                  "RESEND_ACCEPTED",
                  "FAILED"
                ]
              },
              {
                "type": "null"
              }
            ]
          },
          "callType": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "STATUS_QUERY",
                  "ACTIVATE",
                  "RESEND_FAILED"
                ]
              },
              {
                "type": "null"
              }
            ]
          },
          "calledAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "resultAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "previousStatus": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "currentStatus": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "missingRequiredEvents": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "scopeFrom": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "scopeTo": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "scheduledNotificationCount": {
            "oneOf": [
              {
                "type": "integer",
                "minimum": 0
              },
              {
                "type": "null"
              }
            ]
          },
          "errorCode": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "retryable": {
            "type": "boolean"
          },
          "retryCondition": {
            "type": "string"
          },
          "statusPath": {
            "type": "string",
            "enum": [
              "/admin/execution-gates"
            ]
          }
        }
      },
      "AdminExecutionGate": {
        "type": "object",
        "required": [
          "network",
          "type",
          "state",
          "stoppedAt",
          "reason",
          "workTicket",
          "actorEmployeeNo",
          "sequence",
          "newExecutionAllowed",
          "existingExecutionRecoveryAllowed",
          "emergencyRevocationAllowed",
          "disabledReasons"
        ],
        "properties": {
          "network": {
            "type": "string"
          },
          "type": {
            "type": "string",
            "enum": [
              "WITHDRAWAL",
              "SWEEP",
              "APPROVE"
            ]
          },
          "state": {
            "type": "string",
            "enum": [
              "OPEN",
              "STOPPED"
            ]
          },
          "stoppedAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          },
          "reason": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "workTicket": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "actorEmployeeNo": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "sequence": {
            "oneOf": [
              {
                "type": "integer"
              },
              {
                "type": "null"
              }
            ]
          },
          "newExecutionAllowed": {
            "type": "boolean"
          },
          "existingExecutionRecoveryAllowed": {
            "type": "boolean"
          },
          "emergencyRevocationAllowed": {
            "type": "boolean"
          },
          "disabledReasons": {
            "type": "array",
            "items": {
              "type": "string"
            }
          }
        }
      },
      "AdminExternalControlEvidence": {
        "type": "object",
        "required": [
          "evidenceId",
          "network",
          "contractVersionId",
          "status",
          "completionReady",
          "snapshotHash",
          "tapSourceId",
          "tapBlocked",
          "pinnedBlockNumber",
          "expectedOperatorSetHash",
          "firstEndpointId",
          "firstPaused",
          "firstOperatorSetHash",
          "secondEndpointId",
          "secondPaused",
          "secondOperatorSetHash",
          "observedAt",
          "validUntil",
          "reason",
          "workTicket",
          "actorEmployeeNo",
          "issues"
        ],
        "properties": {
          "evidenceId": {
            "type": "string",
            "maxLength": 36
          },
          "network": {
            "type": "string",
            "maxLength": 20
          },
          "contractVersionId": {
            "type": "string",
            "maxLength": 36
          },
          "status": {
            "type": "string",
            "enum": [
              "CONFIRMED",
              "DRIFT",
              "STALE",
              "UNCONFIRMED",
              "ERROR"
            ]
          },
          "completionReady": {
            "type": "boolean"
          },
          "snapshotHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "tapSourceId": {
            "type": "string",
            "maxLength": 64
          },
          "tapBlocked": {
            "oneOf": [
              {
                "type": "boolean"
              },
              {
                "type": "null"
              }
            ]
          },
          "pinnedBlockNumber": {
            "type": "string",
            "pattern": "^\\d+$"
          },
          "expectedOperatorSetHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "firstEndpointId": {
            "type": "string",
            "maxLength": 64
          },
          "firstPaused": {
            "oneOf": [
              {
                "type": "boolean"
              },
              {
                "type": "null"
              }
            ]
          },
          "firstOperatorSetHash": {
            "oneOf": [
              {
                "type": "string",
                "pattern": "^[0-9a-f]{64}$"
              },
              {
                "type": "null"
              }
            ]
          },
          "secondEndpointId": {
            "type": "string",
            "maxLength": 64
          },
          "secondPaused": {
            "oneOf": [
              {
                "type": "boolean"
              },
              {
                "type": "null"
              }
            ]
          },
          "secondOperatorSetHash": {
            "oneOf": [
              {
                "type": "string",
                "pattern": "^[0-9a-f]{64}$"
              },
              {
                "type": "null"
              }
            ]
          },
          "observedAt": {
            "type": "string",
            "format": "date-time"
          },
          "validUntil": {
            "type": "string",
            "format": "date-time"
          },
          "reason": {
            "type": "string",
            "maxLength": 1000
          },
          "workTicket": {
            "type": "string",
            "maxLength": 128
          },
          "actorEmployeeNo": {
            "type": "string",
            "maxLength": 6
          },
          "issues": {
            "type": "array",
            "items": {
              "type": "string"
            }
          }
        }
      },
      "AdminAllowanceRevocation": {
        "type": "object",
        "required": [
          "executionId",
          "requestId",
          "network",
          "contractVersionId",
          "contractBindingRevision",
          "sweepContractAddress",
          "targetSnapshotHash",
          "status",
          "totalCount",
          "zeroConfirmedCount",
          "submittingCount",
          "failedCount",
          "registeredAt",
          "items",
          "retryable",
          "retryCondition",
          "statusPath"
        ],
        "properties": {
          "executionId": {
            "type": "string",
            "maxLength": 36
          },
          "requestId": {
            "type": "string",
            "maxLength": 36
          },
          "network": {
            "type": "string",
            "maxLength": 20
          },
          "contractVersionId": {
            "type": "string",
            "maxLength": 36
          },
          "contractBindingRevision": {
            "type": "integer",
            "format": "int64",
            "minimum": 0
          },
          "sweepContractAddress": {
            "type": "string",
            "maxLength": 128
          },
          "targetSnapshotHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "status": {
            "type": "string",
            "enum": [
              "READY",
              "IN_PROGRESS",
              "PARTIAL",
              "COMPLETED"
            ]
          },
          "totalCount": {
            "type": "integer",
            "minimum": 1
          },
          "zeroConfirmedCount": {
            "type": "integer",
            "minimum": 0
          },
          "submittingCount": {
            "type": "integer",
            "minimum": 0
          },
          "failedCount": {
            "type": "integer",
            "minimum": 0
          },
          "registeredAt": {
            "type": "string",
            "format": "date-time"
          },
          "items": {
            "type": "array",
            "minItems": 1,
            "items": {
              "$ref": "#/components/schemas/AdminAllowanceRevocationItem"
            }
          },
          "retryable": {
            "type": "boolean"
          },
          "retryCondition": {
            "type": "string"
          },
          "statusPath": {
            "type": "string",
            "enum": [
              "/admin/execution-gates"
            ]
          }
        }
      },
      "AdminAllowanceRevocationItem": {
        "type": "object",
        "required": [
          "sequence",
          "accountId",
          "network",
          "symbol",
          "sourceVaultId",
          "ownerAddress",
          "tokenContractAddress",
          "beforeObservedAllowance",
          "externalTransactionId",
          "latestStatus",
          "vendorTransactionId",
          "observedAllowance",
          "observedAt",
          "errorCode",
          "occurredAt"
        ],
        "properties": {
          "sequence": {
            "type": "integer",
            "minimum": 1
          },
          "accountId": {
            "type": "string",
            "maxLength": 64
          },
          "network": {
            "type": "string",
            "maxLength": 20
          },
          "symbol": {
            "type": "string",
            "maxLength": 16
          },
          "sourceVaultId": {
            "type": "string",
            "maxLength": 64
          },
          "ownerAddress": {
            "type": "string",
            "maxLength": 128
          },
          "tokenContractAddress": {
            "type": "string",
            "maxLength": 128
          },
          "beforeObservedAllowance": {
            "type": "string"
          },
          "externalTransactionId": {
            "type": "string",
            "maxLength": 128
          },
          "latestStatus": {
            "oneOf": [
              {
                "type": "string",
                "enum": [
                  "RESERVED",
                  "SUBMIT_INTENT",
                  "SUBMITTED",
                  "ZERO_CONFIRMED",
                  "FAILED"
                ]
              },
              {
                "type": "null"
              }
            ]
          },
          "vendorTransactionId": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 64
              },
              {
                "type": "null"
              }
            ]
          },
          "observedAllowance": {
            "type": "string"
          },
          "observedAt": {
            "type": "string",
            "format": "date-time"
          },
          "errorCode": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 64
              },
              {
                "type": "null"
              }
            ]
          },
          "occurredAt": {
            "oneOf": [
              {
                "type": "string",
                "format": "date-time"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "AdminChangeRequestResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/AdminChangeRequest"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "AdminChangeRequest": {
        "type": "object",
        "required": [
          "requestId",
          "targetType",
          "scopeId",
          "targetVersionId",
          "state",
          "risk",
          "snapshotHash",
          "diff",
          "impact",
          "reason",
          "workTicket",
          "requesterEmployeeNo",
          "requestedAt",
          "expiresAt",
          "requiredApprovals",
          "approvalCount",
          "securityApprovalRequired",
          "securityApprovalCount",
          "activationReady",
          "disabledReasons",
          "decisions"
        ],
        "properties": {
          "requestId": {
            "type": "string"
          },
          "targetType": {
            "type": "string",
            "enum": [
              "POLICY",
              "CONTRACT",
              "BAND_S",
              "ALLOWANCE_REVOKE",
              "EXECUTION_GATE"
            ]
          },
          "scopeId": {
            "type": "string"
          },
          "targetVersionId": {
            "type": "string"
          },
          "state": {
            "type": "string",
            "enum": [
              "PENDING",
              "APPROVED",
              "REJECTED",
              "EXPIRED",
              "CANCELLED",
              "ACTIVATED",
              "EXECUTED"
            ]
          },
          "risk": {
            "type": "string",
            "enum": [
              "GENERAL",
              "SECURITY",
              "RESUME",
              "FUND"
            ]
          },
          "snapshotHash": {
            "type": "string",
            "pattern": "^[0-9a-f]{64}$"
          },
          "diff": {
            "type": "string",
            "description": "서버가 생성한 JSON diff snapshot"
          },
          "impact": {
            "type": "string",
            "description": "서버가 생성한 JSON 영향 snapshot"
          },
          "reason": {
            "type": "string"
          },
          "workTicket": {
            "type": "string"
          },
          "requesterEmployeeNo": {
            "type": "string"
          },
          "requestedAt": {
            "type": "string",
            "format": "date-time"
          },
          "expiresAt": {
            "type": "string",
            "format": "date-time"
          },
          "requiredApprovals": {
            "type": "integer"
          },
          "approvalCount": {
            "type": "integer"
          },
          "securityApprovalRequired": {
            "type": "boolean"
          },
          "securityApprovalCount": {
            "type": "integer"
          },
          "activationReady": {
            "type": "boolean"
          },
          "disabledReasons": {
            "type": "array",
            "items": {
              "type": "string"
            }
          },
          "decisions": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/AdminChangeDecision"
            }
          }
        }
      },
      "AdminChangeDecision": {
        "type": "object",
        "required": [
          "employeeNo",
          "role",
          "decision",
          "opinion",
          "decidedAt"
        ],
        "properties": {
          "employeeNo": {
            "type": "string"
          },
          "role": {
            "type": "string",
            "enum": [
              "BCM_APPROVER",
              "BCM_SECURITY_APPROVER"
            ]
          },
          "decision": {
            "type": "string",
            "enum": [
              "APPROVE",
              "REJECT"
            ]
          },
          "opinion": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "decidedAt": {
            "type": "string",
            "format": "date-time"
          }
        }
      },
      "RegisterAssetMappingRequest": {
        "type": "object",
        "description": "원천별 유효 요청(그대로 보낼 수 있는 값) —\nFireblocks `{ \"network\": \"BASE\", \"symbol\": \"USDC\", \"fireblocksAssetId\": \"USDC_BASE\", \"contractAddress\": \"0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913\" }`,\nDfns EVM `{ \"network\": \"ETHEREUM_SEPOLIA\", \"symbol\": \"USDC\", \"contractAddress\": \"0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238\", \"decimals\": 6 }`,\nDfns Solana `{ \"network\": \"SOLANA_DEVNET\", \"symbol\": \"USDC\", \"contractAddress\": \"4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU\", \"tokenStandard\": \"SPL\", \"decimals\": 6 }`.\n`fireblocksAssetId`와 `tokenStandard`는 서로 다른 원천의 필드라 한 요청에 함께 오지 않는다.\n`decimals` 는 Dfns 원천에서만 보낸다 — Fireblocks 는 카탈로그가 정밀도를 소유한다.\n",
        "required": [
          "network",
          "symbol",
          "contractAddress"
        ],
        "example": {
          "network": "BASE",
          "symbol": "USDC",
          "fireblocksAssetId": "USDC_BASE",
          "contractAddress": "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
        },
        "properties": {
          "network": {
            "type": "string",
            "description": "채택한 네트워크 코드",
            "example": "BASE"
          },
          "symbol": {
            "type": "string",
            "description": "우리 심볼 — 여기서 정하고, 이후 모든 계약에서 이 값을 쓴다",
            "example": "USDC"
          },
          "fireblocksAssetId": {
            "type": "string",
            "minLength": 1,
            "maxLength": 64,
            "pattern": "^\\S{1,64}$",
            "description": "후보 목록에서 선택한 Fireblocks Asset ID. Fireblocks 원천에서는 필수이며 서버가 등록 직전에 Network·주소와 다시 검증한다.\nDfns 원천에서는 보내지 않는다(보내면 400).\n",
            "example": "USDC_BASE"
          },
          "contractAddress": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "발행사 공식 문서에서 확인한 컨트랙트 주소. 네이티브 자산이면 null. Dfns 원천에서는 이 값과 network(Solana는 mint 주소와 `tokenStandard`)가 자산 지정의 전부다\n",
            "example": "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
          },
          "tokenStandard": {
            "type": "string",
            "enum": [
              "SPL",
              "SPL_2022"
            ],
            "description": "Dfns 원천의 Solana 토큰(mint)에서만 필수 — 같은 mint 주소로 Token Program을 구분할 수 없어 운영자가 발행사 자료로 확인해 명시한다.\n네이티브 SOL·EVM·Fireblocks 원천에서는 보내지 않는다(보내면 400 `tokenStandardNotApplicable`).\n",
            "example": "SPL"
          },
          "decimals": {
            "type": "integer",
            "minimum": 0,
            "maximum": 255,
            "description": "발행사 공식 문서에서 확인한 소수 자릿수. Dfns 원천에서는 **필수**다 — Dfns 에는 자산 카탈로그가 없어 서버가 해소할 값이 없고,\n이 값이 없으면 최소 단위 관찰을 이벤트 금액으로 환산할 수 없다(없으면 400 `decimalsRequired`).\nFireblocks 원천에서는 보내지 않는다 — 카탈로그 값을 운영자 입력으로 덮지 않는다(보내면 400 `decimalsNotApplicable`).\n",
            "example": 6
          }
        }
      },
      "BulkRegisterAssetMappingsRequest": {
        "type": "object",
        "required": [
          "items"
        ],
        "example": {
          "items": [
            {
              "network": "BASE",
              "symbol": "USDC",
              "fireblocksAssetId": "USDC_BASE",
              "contractAddress": "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
            },
            {
              "network": "ETHEREUM",
              "symbol": "USDC",
              "fireblocksAssetId": "USDC_ETH",
              "contractAddress": "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
            }
          ]
        },
        "properties": {
          "items": {
            "type": "array",
            "minItems": 1,
            "maxItems": 20,
            "items": {
              "$ref": "#/components/schemas/RegisterAssetMappingRequest"
            }
          }
        }
      },
      "Meta": {
        "type": "object",
        "properties": {
          "requestId": {
            "type": "string",
            "description": "요청 추적 id (모든 응답에 포함)",
            "example": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
          }
        },
        "required": [
          "requestId"
        ]
      },
      "Pagination": {
        "type": "object",
        "properties": {
          "nextCursor": {
            "type": "string",
            "description": "다음 위치 커서 (불투명 토큰) — 다음 요청 `cursor` 로 그대로 전달. 마지막 페이지에서도 항상 채워지며, `order=asc` 조회면 보관해 뒀다가 이후 새로 쌓인 내역을 이어받는 시작점(증분 폴링)으로 쓴다.",
            "example": "eyJsYXN0IjoxNzUxMzM2MDAwMDAwfQ"
          },
          "hasMore": {
            "type": "boolean",
            "description": "지금 이어받을 다음 페이지가 있는지 — false 면 현재 시점 마지막 페이지",
            "example": true
          }
        },
        "required": [
          "nextCursor",
          "hasMore"
        ]
      },
      "ErrorBody": {
        "type": "object",
        "properties": {
          "code": {
            "type": "string",
            "description": "에러 코드 (API Conventions 표 참조)",
            "example": "ACCOUNT_NOT_FOUND"
          },
          "message": {
            "type": "string",
            "description": "사람이 읽는 설명 — 분기 판단은 `code` 로 한다",
            "example": "account not found"
          },
          "retryAfterSeconds": {
            "type": "integer",
            "format": "int64",
            "minimum": 1,
            "description": "재시도 가능한 일시 지연(`SUBMIT_IN_PROGRESS`·`CREATION_RETRY_LATER`·`PROVISIONING_PENDING`)에서 같은 업무 요청을 다시 보내기까지 기다릴 초"
          },
          "details": {
            "$ref": "#/components/schemas/ErrorDetails"
          }
        },
        "required": [
          "code",
          "message"
        ]
      },
      "ErrorDetails": {
        "type": "object",
        "description": "일괄 요청에서 실패한 항목. 단건·일반 오류에서는 생략한다.",
        "properties": {
          "index": {
            "type": "integer",
            "minimum": 0,
            "description": "요청 items의 0 기반 위치",
            "example": 1
          },
          "network": {
            "type": "string",
            "example": "BASE"
          },
          "symbol": {
            "type": "string",
            "example": "USDC"
          },
          "reason": {
            "type": "string",
            "example": "assetNotFound"
          }
        },
        "required": [
          "index",
          "network",
          "symbol",
          "reason"
        ]
      },
      "ErrorResponse": {
        "type": "object",
        "properties": {
          "error": {
            "$ref": "#/components/schemas/ErrorBody"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        },
        "required": [
          "error",
          "meta"
        ]
      },
      "Account": {
        "type": "object",
        "properties": {
          "accountType": {
            "$ref": "#/components/schemas/AccountType"
          },
          "ref": {
            "type": "string",
            "maxLength": 64,
            "description": "우리 참조 키 — 호출 쪽 계정 ID 그대로. 접두사는 붙지 않는다",
            "example": "000123"
          },
          "accountId": {
            "type": "string",
            "description": "BCM이 발급한 계정 ID (DB bcm_acnt_m.acnt_id). 벤더 vault 또는 wallet ID와 구분한다.",
            "example": "acct_018f3d4a-bf70-7c1a-8f2b-3c4d5e6f7890"
          }
        },
        "required": [
          "accountType",
          "ref",
          "accountId"
        ]
      },
      "Transfer": {
        "type": "object",
        "description": "거래 1건. 요청의 `from`/`to`(TransferPeer)는 여기선 확정된 온체인 주소 문자열로 나온다.\nRBF 대체 거래가 생겨도 `txId`·`externalTxId`는 최초 root 거래 값을 유지하고,\n`txHash`는 root 계열에서 실제로 채굴된 승자 거래 값으로 바뀔 수 있다.\n\n**응답은 BCM 이 검증하고 수용한 상태다.** 벤더의 최신값을 그때그때 덮어 보여주지 않는다 —\n허용 전이만 반영하고 늦게 온 관찰로 역행하지 않으며, 아직 우리가 받아들이지 않은 관찰은 나타나지 않는다.\n",
        "properties": {
          "txId": {
            "type": "string",
            "description": "최초 root 거래의 **공개 거래 id**. 제출한 거래는 벤더 tx id 이고,\n입금처럼 벤더 거래 id 가 없는 건은 BCM 이 온체인 값에서 만든 결정적 id 다.\n"
          },
          "txHash": {
            "type": [
              "string",
              "null"
            ],
            "description": "온체인 거래해시 — 전파 후 채워짐"
          },
          "externalTxId": {
            "type": [
              "string",
              "null"
            ],
            "description": "우리 요청 키"
          },
          "network": {
            "type": "string",
            "description": "네트워크 코드",
            "example": "ETHEREUM"
          },
          "symbol": {
            "type": "string",
            "description": "토큰 심볼",
            "example": "USDC"
          },
          "amount": {
            "type": "string",
            "description": "금액(문자열)"
          },
          "from": {
            "type": [
              "string",
              "null"
            ],
            "description": "발신 (확정 온체인 주소). **`SUBMITTED` 구간에는 비어 있을 수 있다** — 거래가 체인에 오르기 전에는\n벤더가 주소를 확정하지 않는다. 키는 항상 있고 값만 `null` 이다.\n"
          },
          "to": {
            "type": [
              "string",
              "null"
            ],
            "description": "목적지 (확정 온체인 주소). `from` 과 같은 이유로 `SUBMITTED` 구간에는 비어 있을 수 있다."
          },
          "status": {
            "$ref": "#/components/schemas/TxStatus"
          },
          "numOfConfirmations": {
            "type": "integer",
            "description": "누적 컨펌 수"
          },
          "createdAt": {
            "type": "string",
            "format": "date-time",
            "description": "**BCM 이 그 거래를 처음 원장에 수용한 시각.** 목록 정렬·기간 필터의 기준이다.\n벤더가 거래를 만든 시각이 아니다 — 벤더 시간축은 제공자마다 뜻이 달라(출금 접수 시각 · 입금 알림 시각)\n공개 계약의 기준으로 쓰지 않는다. 벤더 시각은 내부 대사에만 쓴다.\n"
          },
          "lastUpdated": {
            "type": "string",
            "format": "date-time",
            "description": "BCM 이 마지막 유효 관찰을 반영한 시각"
          }
        },
        "required": [
          "txId",
          "network",
          "symbol",
          "amount",
          "from",
          "to",
          "status",
          "numOfConfirmations",
          "createdAt",
          "lastUpdated"
        ],
        "example": {
          "txId": "tx-local-986a169a89dbf0713ad01d2d17eebd59360b155bfd42fe0a",
          "txHash": "0xe94fb7b189d0721ccf52330274c9da65b39909e52f2dc512c7a3efac8b5208a0",
          "externalTxId": "wd-260713-0042",
          "network": "ETHEREUM",
          "symbol": "USDC",
          "amount": "1.5",
          "from": "0x0da6aa405415ddc059a28e089309c7b47e0702ec",
          "to": "0xdd1b8bb7c9646d21e267bad5f12d011da294af89",
          "status": "FINALIZED",
          "numOfConfirmations": 12,
          "createdAt": "2026-07-13T04:05:06.789Z",
          "lastUpdated": "2026-07-13T04:06:10.120Z"
        }
      },
      "ChainEvent": {
        "type": "object",
        "description": "큐로 오는 온체인 상태 변경 이벤트 (HTTP 응답이 아니라 메시지 큐로 전달).\nRBF 대체 거래가 생겨도 `txId`·`externalTxId`는 최초 root 거래 값을 유지하고,\n`txHash`는 root 계열에서 실제로 채굴된 승자 거래 값으로 바뀔 수 있다.\n",
        "properties": {
          "eventId": {
            "type": "string",
            "format": "uuid",
            "description": "이벤트 고유 id (UUID v7) — 컨슈머 중복 제거 기준"
          },
          "type": {
            "$ref": "#/components/schemas/EventType"
          },
          "txId": {
            "type": "string",
            "description": "최초 root 거래의 **공개 거래 id**. 제출한 거래는 벤더 tx id 이고,\n입금처럼 벤더 거래 id 가 없는 건은 BCM 이 온체인 값에서 만든 결정적 id 다.\n"
          },
          "txHash": {
            "type": [
              "string",
              "null"
            ],
            "description": "온체인 거래해시 — 전파 후 채워짐"
          },
          "externalTxId": {
            "type": [
              "string",
              "null"
            ],
            "description": "우리 요청 키 (출금·내부이체)"
          },
          "accountId": {
            "type": "string",
            "description": "파티션 키 (BCM이 발급한 계정 ID)"
          },
          "network": {
            "type": "string",
            "description": "네트워크 코드",
            "example": "ETHEREUM"
          },
          "symbol": {
            "type": "string",
            "description": "토큰 심볼",
            "example": "USDC"
          },
          "to": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "목적지 주소 — 입금 판별. **입금은 항상 채워진다.** 출금·내부이체의 `SUBMITTED` 이벤트는 아직 체인에 오르기 전이라 비어 있을 수 있다",
            "example": "0xdd1b8bb7c9646d21e267bad5f12d011da294af89"
          },
          "from": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "발신 주소 — 입금은 항상 채워진다. 호출 쪽이 입금 판별을 의뢰할 때 쓴다",
            "example": "0x0da6aa405415ddc059a28e089309c7b47e0702ec"
          },
          "amount": {
            "type": "string",
            "description": "이동 금액 — 그 자산 단위의 **문자열 decimal**. 입금은 `externalTxId` 가 없어 이 값이 금액의 유일한 출처다. 숫자가 아니라 문자열인 것은 정밀도 때문이다",
            "example": "100"
          },
          "status": {
            "$ref": "#/components/schemas/TxStatus"
          },
          "numOfConfirmations": {
            "type": "integer",
            "description": "누적 컨펌 수"
          }
        },
        "required": [
          "eventId",
          "type",
          "txId",
          "accountId",
          "network",
          "symbol",
          "to",
          "amount",
          "status",
          "numOfConfirmations"
        ],
        "example": {
          "eventId": "0198c0de-7a2b-7c3d-8e4f-5a6b7c8d9e0f",
          "type": "WITHDRAWAL",
          "txId": "tx-local-986a169a89dbf0713ad01d2d17eebd59360b155bfd42fe0a",
          "txHash": "0xe94fb7b189d0721ccf52330274c9da65b39909e52f2dc512c7a3efac8b5208a0",
          "externalTxId": "wd-260713-0042",
          "accountId": "acct_pool_02",
          "network": "ETHEREUM",
          "symbol": "USDC",
          "to": "0xdd1b8bb7c9646d21e267bad5f12d011da294af89",
          "from": "0x0da6aa405415ddc059a28e089309c7b47e0702ec",
          "amount": "100",
          "status": "FINALIZED",
          "numOfConfirmations": 12
        }
      },
      "SweepEvent": {
        "type": "object",
        "description": "`sweep-events` 토픽의 고객 항목 1건 결과. 최상위 batch 거래 상태인 `chainStatus`와\n고객 leg 결과인 `itemOutcome`은 서로 덮어쓰지 않는다. 따라서 FINALIZED 거래 안에서\n특정 항목이 FAILED일 수 있다. 앞 실행이 잔액을 이미 모두 옮긴 후속 요청은 온체인 제출 없이\n`NOT_SUBMITTED / NO_SWEEP_REQUIRED`로 완료하며 물리 거래 식별자는 null이다. Kafka 파티션 키는 `accountId`다.\n",
        "required": [
          "eventId",
          "type",
          "sweepRequestId",
          "sweepItemId",
          "executionId",
          "txId",
          "vendorTxId",
          "txHash",
          "accountId",
          "network",
          "symbol",
          "requestedAmount",
          "actualAmount",
          "chainStatus",
          "itemOutcome",
          "failureCode"
        ],
        "properties": {
          "eventId": {
            "type": "string",
            "format": "uuid",
            "description": "상태 전이 1건의 소비·완료 키(UUID v7)"
          },
          "type": {
            "type": "string",
            "enum": [
              "SWEEP"
            ]
          },
          "sweepRequestId": {
            "type": "string",
            "maxLength": 36
          },
          "sweepItemId": {
            "type": "string",
            "maxLength": 36
          },
          "executionId": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 36
              },
              {
                "type": "null"
              }
            ],
            "description": "온체인 제출 없는 완료면 null"
          },
          "txId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "같은 온체인 거래 상태를 묶는 조회 키. 온체인 제출 없는 완료면 null"
          },
          "vendorTxId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "물리 Fireblocks transaction id. 온체인 제출 없는 완료면 null"
          },
          "txHash": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "accountId": {
            "type": "string",
            "maxLength": 64,
            "description": "Kafka 파티션 키"
          },
          "network": {
            "type": "string",
            "maxLength": 20
          },
          "symbol": {
            "type": "string",
            "maxLength": 16
          },
          "requestedAmount": {
            "type": "string",
            "description": "정밀 십진 문자열"
          },
          "actualAmount": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "체인 전체 실패면 null"
          },
          "chainStatus": {
            "type": "string",
            "enum": [
              "NOT_SUBMITTED",
              "FINALIZED",
              "FAILED"
            ]
          },
          "itemOutcome": {
            "type": "string",
            "enum": [
              "NO_SWEEP_REQUIRED",
              "SUCCEEDED",
              "FAILED"
            ]
          },
          "failureCode": {
            "oneOf": [
              {
                "type": "string",
                "maxLength": 64
              },
              {
                "type": "null"
              }
            ]
          }
        },
        "example": {
          "eventId": "0198c0de-7a2b-7c3d-8e4f-5a6b7c8d9e10",
          "type": "SWEEP",
          "sweepRequestId": "0198c0de-7a2b-7c3d-8e4f-5a6b7c8d9e11",
          "sweepItemId": "0198c0de-7a2b-7c3d-8e4f-5a6b7c8d9e12",
          "executionId": "0198c0de-7a2b-7c3d-8e4f-5a6b7c8d9e13",
          "txId": "tx-local-sweep-1",
          "vendorTxId": "tx-local-sweep-1",
          "txHash": "0xe94fb7b189d0721ccf52330274c9da65b39909e52f2dc512c7a3efac8b5208a0",
          "accountId": "acct_customer_01",
          "network": "BASE",
          "symbol": "USDC",
          "requestedAmount": "100",
          "actualAmount": "0",
          "chainStatus": "FINALIZED",
          "itemOutcome": "FAILED",
          "failureCode": "LEG_REVERTED"
        }
      },
      "TxStatus": {
        "type": "string",
        "enum": [
          "SUBMITTED",
          "CONFIRMED",
          "FINALIZED",
          "REJECTED",
          "FAILED"
        ],
        "description": "공통 상태 다섯 — 매니저와 호출 쪽 사이의 계약 어휘 (벤더 원어와 구분).",
        "x-enumDescriptions": {
          "SUBMITTED": "제출 — 체인 미등장",
          "CONFIRMED": "체인 등장·컨펌 누적 (미확정 — 확정은 FINALIZED)",
          "FINALIZED": "확정 — DCCP 임계 도달 (체인 finality 아님 — reorg 시 FAILED 전이 존재)",
          "REJECTED": "거부·차단 (일시적)",
          "FAILED": "영구 실패"
        }
      },
      "AccountType": {
        "type": "string",
        "enum": [
          "CUSTOMER",
          "SYSTEM"
        ],
        "description": "계정 유형 — `ref` 가 어느 ID 공간의 값인지 가린다. 접두사가 없어 두 유형의 ID 값이 겹칠 수 있다.",
        "x-enumDescriptions": {
          "CUSTOMER": "고객 계정",
          "SYSTEM": "시스템(운영) 계정"
        },
        "example": "CUSTOMER"
      },
      "EventType": {
        "type": "string",
        "enum": [
          "DEPOSIT",
          "WITHDRAWAL",
          "INTERNAL"
        ],
        "description": "이벤트 분류. 매니저가 발신자가 우리 vault 인지로 가른다. 귀속 불명 입금은 큐 대신 별도 알림 채널로 통지된다.",
        "x-enumDescriptions": {
          "DEPOSIT": "고객 입금 (매핑된 주소로 수신)",
          "WITHDRAWAL": "외부 출금",
          "INTERNAL": "내부 이체 — delta 정산 (sweep 은 매니저 내부 처리라 이벤트에 실리지 않는다)"
        }
      },
      "SubmitResult": {
        "type": "object",
        "properties": {
          "txId": {
            "type": "string",
            "description": "벤더 tx id",
            "example": "tx-local-986a169a89dbf0713ad01d2d17eebd59360b155bfd42fe0a"
          }
        },
        "required": [
          "txId"
        ]
      },
      "EventCompletion": {
        "type": "object",
        "required": [
          "eventId",
          "consumer",
          "completedAt",
          "txId",
          "status",
          "sweepRequestId",
          "sweepItemId",
          "executionId"
        ],
        "properties": {
          "eventId": {
            "type": "string",
            "description": "완료된 상태 전이 이벤트 UUID v7"
          },
          "consumer": {
            "type": "string",
            "enum": [
              "DAW_CORE"
            ]
          },
          "completedAt": {
            "type": "string",
            "format": "date-time",
            "description": "BCM이 최초 완료 요청을 받은 UTC 시각. 재호출해도 바뀌지 않는다"
          },
          "txId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "일반 거래 또는 sweep batch의 논리 거래 조회 키"
          },
          "status": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "일반 이벤트 status 또는 sweep event chainStatus"
          },
          "sweepRequestId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "sweepItemId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          },
          "executionId": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ]
          }
        }
      },
      "EventCompletionResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/EventCompletion"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "SweepRequest": {
        "type": "object",
        "required": [
          "externalSweepRequestId",
          "network",
          "symbol",
          "items"
        ],
        "properties": {
          "externalSweepRequestId": {
            "type": "string",
            "minLength": 1,
            "maxLength": 128,
            "description": "DAW-CORE 업무 요청 멱등 키"
          },
          "network": {
            "type": "string",
            "pattern": "^[A-Za-z0-9_-]{1,20}$",
            "example": "BASE"
          },
          "symbol": {
            "type": "string",
            "pattern": "^[A-Za-z0-9_-]{1,16}$",
            "example": "USDC"
          },
          "items": {
            "type": "array",
            "minItems": 1,
            "items": {
              "$ref": "#/components/schemas/SweepRequestItem"
            }
          }
        }
      },
      "SweepRequestItem": {
        "type": "object",
        "required": [
          "accountId",
          "sourceEventIds"
        ],
        "properties": {
          "accountId": {
            "type": "string",
            "minLength": 1,
            "maxLength": 64
          },
          "sourceEventIds": {
            "type": "array",
            "minItems": 1,
            "uniqueItems": true,
            "items": {
              "type": "string",
              "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
            }
          }
        }
      },
      "SweepRequestResult": {
        "type": "object",
        "required": [
          "sweepRequestId",
          "externalSweepRequestId",
          "network",
          "symbol",
          "status",
          "requestedAt",
          "items"
        ],
        "properties": {
          "sweepRequestId": {
            "type": "string",
            "description": "BCM 접수 원장 ID"
          },
          "externalSweepRequestId": {
            "type": "string"
          },
          "network": {
            "type": "string"
          },
          "symbol": {
            "type": "string"
          },
          "status": {
            "type": "string",
            "enum": [
              "ACCEPTED",
              "BLOCKED",
              "PROCESSING",
              "COMPLETED",
              "PARTIAL",
              "FAILED"
            ]
          },
          "requestedAt": {
            "type": "string",
            "format": "date-time"
          },
          "items": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/SweepRequestItemResult"
            }
          }
        }
      },
      "SweepRequestItemResult": {
        "type": "object",
        "required": [
          "sweepItemId",
          "accountId",
          "status"
        ],
        "properties": {
          "sweepItemId": {
            "type": "string",
            "description": "BCM이 부여한 고객 계정 항목 ID"
          },
          "accountId": {
            "type": "string"
          },
          "status": {
            "type": "string",
            "enum": [
              "PENDING",
              "PROCESSING",
              "COMPLETED",
              "FAILED"
            ]
          }
        }
      },
      "SweepRequestResponse": {
        "type": "object",
        "required": [
          "data",
          "meta"
        ],
        "properties": {
          "data": {
            "$ref": "#/components/schemas/SweepRequestResult"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        }
      },
      "CreateAccountRequest": {
        "type": "object",
        "required": [
          "accountType",
          "ref"
        ],
        "properties": {
          "accountType": {
            "$ref": "#/components/schemas/AccountType"
          },
          "ref": {
            "type": "string",
            "maxLength": 64,
            "description": "우리 참조 키 — 호출 쪽 계정 ID 그대로. 접두사가 붙지 않으므로 `accountType` 과 짝이어야 유일하다. 자리수·형식은 호출 쪽 규칙을 따른다. 초과 시 `400 VALIDATION_FAILED`.",
            "example": "000123"
          }
        }
      },
      "TransactionRequest": {
        "type": "object",
        "properties": {
          "externalTxId": {
            "type": "string",
            "maxLength": 128,
            "description": "우리 요청 키 — 승인 완료된 출금 지시 1건과 1:1. 재제출 중복 차단·완료 대응. 초과 시 `400 VALIDATION_FAILED`.",
            "example": "wd-260713-0042"
          },
          "from": {
            "allOf": [
              {
                "$ref": "#/components/schemas/TransferPeer"
              },
              {
                "properties": {
                  "type": {
                    "const": "ACCOUNT"
                  }
                }
              }
            ],
            "description": "보내는 쪽 — type=ACCOUNT 만 허용"
          },
          "to": {
            "allOf": [
              {
                "$ref": "#/components/schemas/TransferPeer"
              }
            ],
            "description": "목적지"
          },
          "network": {
            "type": "string",
            "description": "네트워크 코드",
            "example": "ETHEREUM"
          },
          "symbol": {
            "type": "string",
            "description": "토큰 심볼",
            "example": "USDC"
          },
          "amount": {
            "type": "string",
            "pattern": "^\\d{1,18}(\\.\\d{1,18})?$",
            "description": "금액(문자열 · 부동소수 금지). **0보다 커야 하고**, 정수부 최대 18자리 · 소수부 최대 18자리다.\n반올림 없이 그대로 보관할 수 있는 범위이며, 벗어나면 `400 VALIDATION_FAILED` 다.\n부호·지수 표기(`1e-3`)·앞뒤 공백은 허용하지 않는다.\n멱등 비교는 금액으로 하므로 `\"1.50\"` 과 `\"1.5\"` 는 같은 요청이다.\n",
            "example": "1.5"
          },
          "note": {
            "type": [
              "string",
              "null"
            ],
            "description": "벤더 거래 기록 메모"
          },
          "travelRule": {
            "description": "트래블룰 게이트가 만든 암호화 산출물 — 해외(Notabene) 출금만 싣고, 국내(VerifyVASP)·개인지갑은 null",
            "oneOf": [
              {
                "$ref": "#/components/schemas/TravelRule"
              },
              {
                "type": "null"
              }
            ]
          }
        },
        "required": [
          "externalTxId",
          "from",
          "to",
          "network",
          "symbol",
          "amount"
        ],
        "example": {
          "externalTxId": "wd-260713-0042",
          "from": {
            "type": "ACCOUNT",
            "accountId": "acct_pool_02"
          },
          "to": {
            "type": "ADDRESS",
            "address": "0xdd1b8bb7c9646d21e267bad5f12d011da294af89"
          },
          "network": "ETHEREUM",
          "symbol": "USDC",
          "amount": "1.5",
          "note": null,
          "travelRule": null
        }
      },
      "TransferPeer": {
        "type": "object",
        "description": "벤더 TransferPeerPath 대응. from·to 공통. type 에 따라 필요한 식별 필드가 정해진다.",
        "properties": {
          "type": {
            "$ref": "#/components/schemas/PeerType"
          },
          "address": {
            "type": [
              "string",
              "null"
            ],
            "description": "온체인 주소 (type=ADDRESS 일 때 필수)"
          },
          "accountId": {
            "type": [
              "string",
              "null"
            ],
            "description": "우리 계정 (type=ACCOUNT 일 때 필수)"
          },
          "walletId": {
            "type": [
              "string",
              "null"
            ],
            "description": "사전 등록 지갑 id (type=WHITELISTED 일 때 필수)"
          }
        },
        "required": [
          "type"
        ],
        "allOf": [
          {
            "if": {
              "properties": {
                "type": {
                  "const": "ADDRESS"
                }
              }
            },
            "then": {
              "required": [
                "address"
              ]
            }
          },
          {
            "if": {
              "properties": {
                "type": {
                  "const": "ACCOUNT"
                }
              }
            },
            "then": {
              "required": [
                "accountId"
              ]
            }
          },
          {
            "if": {
              "properties": {
                "type": {
                  "const": "WHITELISTED"
                }
              }
            },
            "then": {
              "required": [
                "walletId"
              ]
            }
          }
        ]
      },
      "PeerType": {
        "type": "string",
        "enum": [
          "ADDRESS",
          "ACCOUNT",
          "WHITELISTED"
        ],
        "x-enumDescriptions": {
          "ADDRESS": "온체인 주소 (외부 출금 → ONE_TIME_ADDRESS)",
          "ACCOUNT": "우리 계정 (내부 이동 → VAULT_ACCOUNT)",
          "WHITELISTED": "사전 등록 지갑 (→ EXTERNAL_WALLET)"
        }
      },
      "TravelRule": {
        "type": "object",
        "description": "트래블룰 게이트가 만든 **암호화 산출물**이다. 이 API(매니저)는 운반만 하고\n내용을 파싱하지 않으므로, 여기서는 내부 구조를 펼치지 않고 불투명한 객체로 둔다.\n\n- 실제 구조의 기준은 **IVMS101 표준 + 트래블룰 솔루션 스펙**(게이트 쪽 문서)이다.\n- 시나리오별로 실림 여부가 다르다 — 해외(Notabene)=메시지 있음, 국내(VerifyVASP)·개인지갑=없음(`null`).\n- 컴플라이언스가 내보내는 `travelRuleMessage`(암호화 문자열)를 호출 쪽이 이 필드로 실어 보낸다 — 정확한 형태는 구현 때 확정.\n",
        "additionalProperties": true
      },
      "AccountResponse": {
        "type": "object",
        "properties": {
          "data": {
            "$ref": "#/components/schemas/Account"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        },
        "required": [
          "data",
          "meta"
        ]
      },
      "CreateAddressesRequest": {
        "type": "object",
        "required": [
          "symbol",
          "networks"
        ],
        "properties": {
          "symbol": {
            "type": "string",
            "maxLength": 16,
            "description": "심볼 — 이 요청의 모든 네트워크에 공통",
            "example": "USDC"
          },
          "networks": {
            "type": "array",
            "minItems": 1,
            "maxItems": 20,
            "description": "주소를 받을 네트워크 1~20개. 빈 배열·초과는 `400 VALIDATION_FAILED`. 같은 네트워크가 두 번 들어오면 발급은 한 번만 하고 두 항목에 같은 결과를 담는다.",
            "items": {
              "type": "string",
              "maxLength": 20,
              "description": "네트워크 코드",
              "example": "ETHEREUM"
            }
          }
        }
      },
      "AssetBalance": {
        "type": "object",
        "description": "자산 하나의 지갑 잔액. 세 칸으로 접어 돌려준다. 제공자가 그 구분을 제공하지 않는 칸은 `null`이다 — 0으로 채워 알 수 없는 값을\n확정값처럼 보이게 하지 않는다. Fireblocks는 세 칸 모두 문자열, Dfns는 `available`만 문자열이고 `pending`·`locked`는 `null`이다.\n",
        "required": [
          "network",
          "symbol",
          "available",
          "pending",
          "locked"
        ],
        "properties": {
          "network": {
            "type": "string",
            "example": "BASE"
          },
          "symbol": {
            "type": "string",
            "example": "USDC"
          },
          "available": {
            "type": "string",
            "description": "가용 — 지금 출금에 쓸 수 있는 잔액. Dfns는 지갑의 온체인 잔액",
            "example": "10.5"
          },
          "pending": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "대기 — 들어왔지만 확정 전. 제공자가 주지 않으면 null (Dfns)",
            "example": "1.0"
          },
          "locked": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "잠김 — 나가는 중이거나 정책상 묶인 분 (벤더 lockedAmount + frozen). 둘 중 하나라도 모르면 null (Dfns)",
            "example": "0.3"
          }
        }
      },
      "AssetBalanceListResponse": {
        "type": "object",
        "properties": {
          "data": {
            "type": "array",
            "description": "자산별 잔액 — 요청 필터에 걸린 것만",
            "items": {
              "$ref": "#/components/schemas/AssetBalance"
            }
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        },
        "required": [
          "data",
          "meta"
        ]
      },
      "DepositAddress": {
        "type": "object",
        "description": "발급된 입금 주소 하나.",
        "required": [
          "network",
          "symbol",
          "address"
        ],
        "properties": {
          "network": {
            "type": "string",
            "example": "BASE"
          },
          "symbol": {
            "type": "string",
            "example": "USDC"
          },
          "address": {
            "type": "string",
            "description": "온체인 입금 주소",
            "example": "0xdd1b8bb7c9646d21e267bad5f12d011da294af89"
          },
          "memoTag": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "체인이 요구하는 태그·메모 — EVM 은 null"
          }
        }
      },
      "DepositAddressListResponse": {
        "type": "object",
        "properties": {
          "data": {
            "type": "array",
            "description": "발급된 주소 목록 — 미발급은 담기지 않는다",
            "items": {
              "$ref": "#/components/schemas/DepositAddress"
            }
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        },
        "required": [
          "data",
          "meta"
        ]
      },
      "DepositAddressResult": {
        "type": "object",
        "description": "네트워크 하나의 발급 결과 — 조회 항목(`DepositAddress`)과 같은 필드에 `error` 가 더해진 모양이다. 성공이면 `address`, 실패면 `error` 가 채워진다 (둘 중 하나만). **다섯 필드가 항상 있고, 해당 없으면 `null`** 이라 `error` 유무로 판단할 수 있다.",
        "required": [
          "network",
          "symbol",
          "address",
          "memoTag",
          "error"
        ],
        "properties": {
          "network": {
            "type": "string",
            "example": "ETHEREUM"
          },
          "symbol": {
            "type": "string",
            "example": "USDC"
          },
          "address": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "발급된 주소 — 실패 시 null",
            "example": "0xdd1b8bb7c9646d21e267bad5f12d011da294af89"
          },
          "memoTag": {
            "oneOf": [
              {
                "type": "string"
              },
              {
                "type": "null"
              }
            ],
            "description": "체인이 요구하는 태그·메모 — EVM 은 null"
          },
          "error": {
            "oneOf": [
              {
                "$ref": "#/components/schemas/ErrorBody"
              },
              {
                "type": "null"
              }
            ],
            "description": "실패 사유 — 성공 시 null. 코드 체계는 공통 에러 코드 표와 같다"
          }
        }
      },
      "DepositAddressResultListResponse": {
        "type": "object",
        "properties": {
          "data": {
            "type": "array",
            "description": "요청과 같은 순서의 네트워크별 결과",
            "items": {
              "$ref": "#/components/schemas/DepositAddressResult"
            }
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        },
        "required": [
          "data",
          "meta"
        ]
      },
      "TransferResponse": {
        "type": "object",
        "properties": {
          "data": {
            "$ref": "#/components/schemas/Transfer"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        },
        "required": [
          "data",
          "meta"
        ]
      },
      "TransferListResponse": {
        "type": "object",
        "properties": {
          "data": {
            "type": "array",
            "items": {
              "$ref": "#/components/schemas/Transfer"
            }
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          },
          "pagination": {
            "$ref": "#/components/schemas/Pagination"
          }
        },
        "required": [
          "data",
          "meta",
          "pagination"
        ]
      },
      "SubmitResponse": {
        "type": "object",
        "properties": {
          "data": {
            "$ref": "#/components/schemas/SubmitResult"
          },
          "meta": {
            "$ref": "#/components/schemas/Meta"
          }
        },
        "required": [
          "data",
          "meta"
        ]
      }
    },
    "responses": {
      "ValidationFailed": {
        "description": "요청 검증 실패",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/ErrorResponse"
            },
            "example": {
              "error": {
                "code": "VALIDATION_FAILED",
                "message": "amount must be a decimal string"
              },
              "meta": {
                "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
              }
            }
          }
        }
      },
      "NotFound": {
        "description": "리소스 없음",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/ErrorResponse"
            },
            "example": {
              "error": {
                "code": "NOT_FOUND",
                "message": "transaction not found"
              },
              "meta": {
                "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
              }
            }
          }
        }
      },
      "AccountNotFound": {
        "description": "계정 없음",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/ErrorResponse"
            },
            "example": {
              "error": {
                "code": "ACCOUNT_NOT_FOUND",
                "message": "account not found"
              },
              "meta": {
                "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
              }
            }
          }
        }
      },
      "Conflict": {
        "description": "상태·멱등 충돌",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/ErrorResponse"
            },
            "example": {
              "error": {
                "code": "CONFLICT",
                "message": "externalTxId already used"
              },
              "meta": {
                "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
              }
            }
          }
        }
      },
      "UnprocessableEntity": {
        "description": "요청 형식·값은 맞지만 현재 리소스 상태나 선행 조건 때문에 처리할 수 없다.\n조건이 갖춰지면 같은 요청이 그대로 유효하다 — 요청을 고쳐야 하는 `400` 과 구분한다.\n",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/ErrorResponse"
            },
            "example": {
              "error": {
                "code": "UNPROCESSABLE_ENTITY",
                "message": "request cannot be processed in the current resource state"
              },
              "meta": {
                "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
              }
            }
          }
        }
      },
      "SubmitInProgress": {
        "description": "같은 `externalTxId` 의 앞선 제출이 처리 중이다. 중복 제출이 아니라 아직 결과를 모른다는 뜻이고,\n`Retry-After` 초 뒤에 같은 요청을 그대로 다시 보내면 된다.\n",
        "headers": {
          "Retry-After": {
            "description": "다시 보내기까지 기다릴 초",
            "schema": {
              "type": "integer",
              "minimum": 1
            }
          }
        },
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/ErrorResponse"
            },
            "example": {
              "error": {
                "code": "SUBMIT_IN_PROGRESS",
                "message": "submission for this externalTxId is in progress"
              },
              "meta": {
                "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
              }
            }
          }
        }
      },
      "CreationRetryLater": {
        "description": "벤더 생성 여부가 불확실해 마지막 POST 준비 뒤 설정된 벤더 최장 호출시간, 24시간과 초 단위 정밀도 여유 1초가 모두 지날 때까지\n새 멱등 키 호출을 보수적으로 미룬다. `Retry-After` 뒤 같은 업무 요청을 다시 보낸다.\n",
        "headers": {
          "Retry-After": {
            "description": "다시 보내기까지 기다릴 초",
            "schema": {
              "type": "integer",
              "minimum": 1
            }
          }
        },
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/ErrorResponse"
            },
            "example": {
              "error": {
                "code": "CREATION_RETRY_LATER",
                "message": "vendor resource creation must be retried later",
                "retryAfterSeconds": 82800
              },
              "meta": {
                "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
              }
            }
          }
        }
      },
      "RelayRejected": {
        "description": "relay 가 전송을 대지 못함·거절 (대납 구성)",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/ErrorResponse"
            },
            "example": {
              "error": {
                "code": "RELAY_REJECTED",
                "message": "relay refused to sponsor gas"
              },
              "meta": {
                "requestId": "3f9a1c2e-7b4d-4e2a-9c1f-0a2b3c4d5e6f"
              }
            }
          }
        }
      }
    }
  }
};

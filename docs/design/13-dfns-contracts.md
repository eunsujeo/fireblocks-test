# Dfns 지갑·원천 식별·웹훅 연결 계약

상태: 2026-09-14 공개 명세·사용자 지정 Baseline 자료와 현행 코드를 대조했다.
웹훅 공통 수신 경계와 네트워크 지갑 생성·조회 포트/순수 회수 판정, V22 생성 의도·회수·완료 연결 원장을 구현했다.
V23의 VAULT/LOGICAL 계정 모델과 내부 논리 계정·네트워크 지갑 생성 유스케이스, 원문 증적 보관 포트를 구현했다.
V24 보호 원문 저장소와 내부 생성 서비스+실제 PostgreSQL 결합 복구 검증을 구현했다.
2026-09-15 공식 OpenAPI 1.1018.3 기반 인증(사용자 행위 서명)·지갑 생성/조회 HTTP 어댑터와 Pending/Conflict의 공개 HTTP 매핑을 구현했다.
공개 계정·주소 API를 `AccountOperations`로 제공자별 조립해 Dfns 논리 계정·네트워크 지갑 주소 발급을 연결했다.
Dfns 데이터셋의 자산 매핑 등록 관문(`ChainAssetResolver`)과 `GET /wallets/{walletId}/assets` 기반 잔액 계약을 구현했다.
2026-09-16 V25 계정·자산 모델 컬럼과 Solana 자산 키(mint·Token Program)·owner 주소 수신 모델, Dfns 웹훅 수신 프로토콜(HMAC 검증·envelope), 전송 제출·조회 어댑터를 구현했다.
이어서 웹훅 전송·온체인 이동 사건 관찰, 블록 깊이 확정 판정, 상태 번역, 온체인 이동의 귀속, 논리 거래 식별자와 hash 조회 index(V26), 등록 자산 정밀도(V27), 입금 판단과 **판단 워커 조립**을 구현했다 — `BCM_PROVIDER=dfns`에서 입금 경로는 수신부터 원장·outbox까지 조건부로 조립된다.
2026-09-17 출금 제출 계약(멱등 재제출 회수)을 고정하고 **출금 제출 유스케이스**와 V28 제출 시점 벤더 canonical 값 보관을 구현했다.
이어서 **전송 알림 판단**과 **발신 확정의 블록 좌표**를 구현해 출금이 제출부터 확정까지 이어지고, **내부이체**(계정 목적지 해소·수신측 중복 입금 방지)까지 구현했다. Sweep·이력 복구·RBF 계열은 아직 구현하지 않았다.
API 전체 기동 차단·Baseline 수용은 유지·미완료다.
[제공자 선택](12-provider-compatibility.md) · [전체 계획](../dfns-compatibility-plan.md) · [현행 API](../api/openapi.yaml)

## 자료의 적용 범위

사용자 지정 waas-wiki의 `WaaS 도입·구축/Dfns/02-deployment-backends.md`와 `04-vendor-questions.md`를 다시 대조했다.
배포 백엔드 자료는 Baseline을 Vault 기반 전체 플랫폼으로 설명하고 최소 릴리스를 1.929로 제시한다.
이 최소 버전은 실제 도입 릴리스 확정이나 아래 공개 API와의 동일성 증거가 아니다. 당시 배포 자료의 제품 설명과 우리 설계를 구분한다.
외부 wiki 동기화는 이 저장소의 구현 선행 조건이 아니다.

| 근거 | 공개 명세에서 확인한 사실 | BCM에서 적용할 경계 |
|---|---|---|
| [Wallet/Vault/Key](https://docs.dfns.co/core-concepts/vaults-wallets-and-keys) | Wallet은 네트워크상의 지갑이다. Vault 하위 지갑은 직접 서명하지 않고 Vault 전용 전송 경로로 제어한다. | Fireblocks vault를 Dfns Vault/Wallet으로 이름만 바꾸지 않는다. 기존 사용자 정의 Sweep에는 일반 조직 소유 Wallet 경로를 우선 검토한다. |
| [Create Wallet](https://docs.dfns.co/api-reference/wallets/create-wallet) | 생성에 network가 필요하고 `externalId`는 최대 100자의 외부 상관관계 값으로 설명된다. 반환 wallet ID·address·signingKey는 별개다. | 네트워크를 받지 않는 BCM 계정 생성에 임의 기본 체인을 넣지 않는다. wallet ID를 signing key ID로 대체하지 않는다. |
| [API Idempotency](https://docs.dfns.co/api-reference/idempotency) | Transfer/Broadcast의 동일 URL·본문·externalId 재요청과 충돌 처리가 문서화돼 있다. 종결된 요청은 같은 externalId로 새 실행을 만들지 않는다. | 이 설명을 Wallet 생성의 멱등 보장으로 확대하지 않는다. Fireblocks의 24시간 키 회전 정책을 Dfns에 재사용하지 않는다. |
| [List Wallets](https://docs.dfns.co/api-reference/wallets/list-wallets) | owner 필터와 limit/paginationToken, 응답 nextPageToken이 문서화돼 있다. | 공개 query에 없는 externalId exact 검색을 구현했다고 가정하지 않는다. 전체 페이지 조회도 동시 생성의 부재를 증명하지 못한다. |
| [Webhook 검증](https://docs.dfns.co/guides/developers/webhooks) | `X-DFNS-WEBHOOK-SIGNATURE`의 HMAC-SHA256, `sha256=` 형식, timestampSent 검사 예제가 있다. secret은 생성 응답에서 한 번 제공된다. | Fireblocks JWKS/RS512와 다른 검증기·secret 관리가 필요하다. 예제의 JSON 재직렬화와 BCM의 원문 byte[] 원칙 사이의 정확한 서명 입력은 실제 릴리스와 대조한다. |
| [Webhook Events](https://docs.dfns.co/api-reference/webhook-events) | 최상위 종류는 kind다. 재시도는 별도 ID이고 retryOf/deliveryAttempt가 사용되며 순서 보장은 없다. 이력 보존은 31일, 수동 retry API는 없다고 명시한다. | 알림 시도·논리 이동·CORE 이벤트를 분리한다. 복구는 이력 회수와 처리 재개로 설계하고 벤더 재전송 성공을 꾸며내지 않는다. |

공개 명세 열은 문서 확인이며 Baseline 실측이 아니다. 공개 명세에 있는 필드·동작은 출처와 버전을 고정해 어댑터·계약 테스트의 구현 근거로 사용한다.
실제 배포 릴리스 대조와 서명된 수신 원문 검증은 운영 연결 전 수용 항목이며 모든 어댑터 개발의 선행 조건으로 두지 않는다.

### 공식 OpenAPI 재확인과 구현 근거 (2026-09-14 사용자 정정)

사용자는 Dfns 공식 홈페이지에서 명세를 확인해 진행하도록 정정했다. [공식 문서 색인](https://docs.dfns.co/llms.txt)은
현재 OpenAPI와 버전별 파일을 제공한다. 아래 두 파일을 실제 다운로드하고 YAML 파싱·info.version·경로를 확인했다.
이는 다운로드 시점의 명세 식별이며 실제 Baseline 도입 버전을 선택했다는 뜻은 아니다.

| 공식 파일 | info.version | SHA-256 |
|---|---|---|
| [현재 OpenAPI](https://docs.dfns.co/openapi.yaml) | 2.0.54 | `e617d55cbcc4a31cf817dbc5ffce3d0159b36846ab922039b4a956e6dcea4849` |
| [버전별 OpenAPI](https://docs.dfns.co/openapi-versions/openapi-1.1018.3.yaml) | 1.1018.3 | `2c46e1d1a17fd37143192dd2191cb2771d29cf40c6f7974427f9a36acedc9439` |

두 파일 모두 `/wallets` POST/GET, `/wallets/{walletId}` GET, `/auth/action/init` POST와 `/auth/action` POST를 포함한다.
공식 [인증 흐름](https://docs.dfns.co/api-reference/auth/login-flows)과 위 Create Wallet 명세도 함께 대조한다.
구현 시 변경 가능한 최신 URL만 참조하지 않고 실제 채택한 버전·필드·응답 예시의 근거를 고정한다. 공개 예시는 실측 payload라고 표시하지 않는다.

- **바로 개발 가능:** 공개 명세에 근거한 인증·요청 서명 흐름, 지갑 생성/조회 DTO·HTTP 어댑터, 오류 매핑과 계약 테스트.
  기존 증적 저장소/DB 복구 작업과 연결하며 실제 자격·네트워크 호출 없이 개발할 수 있다.
- **별도 수용:** 도입 Baseline과 채택 명세의 차이, 지정 RPC·정책·권한·서명 원문, 실제 동시성/지연/장애 동작.
  공개 웹훅 예시와 로컬 암호 검증은 실제 Dfns가 발송한 서명 원문 수용을 대신하지 않는다.
- **보장 미확인 부분:** 위 Idempotency 문서는 Transfer/Broadcast를 대상으로 하며 Wallet 생성의 멱등 보장으로 확대하지 않는다.
  List Wallets는 externalId 서버 필터를 지원하지 않는다고 명시한다. 현재 최초 POST 1회·조회 회수 정책을 유지한다.

공식 명세가 있다는 사실만으로 미구현 실행 빈을 등록하거나 Dfns 기동 차단을 해제하지 않는다. 구현·공통 계약 검증·수용은 구분한다.

## 지갑 생성과 재시도

현재 `WalletVendorPort.createVault`·`AccountService`는 Fireblocks의 vault/asset wallet 모델을 사용한다.
V23은 `VAULT` 계정에만 vault ID를 필수로 요구하고 `LOGICAL` 계정에는 NULL을 요구한다. 이 포트에 가짜 Dfns vault ID를 반환해서는 안 된다.

생성 재시도는 domain의 `WalletCreationPolicy`로 분리한다. 입력은 현재 멱등키·키 등록 시각·마지막 POST 준비 시각·현재 UTC 시각이며,
출력은 기존 `VendorCallDecision`(키 유지/회전/대기)다. `WalletProvisioningPolicy`는 이 포트에 판정을 위임하고 후보 유일성·cursor 검사는 유지한다.
24시간 창·5분 호출 상한·저장 시각 정밀도 1초를 포함한 기존 알고리즘은 infra의 `FireblocksWalletCreationPolicy`가 소유하며
`fireblocks|local`에서만 조립한다. API는 이 포트만 요구하고, 시간 상한만으로 Fireblocks 정책을 기본 생성하지 않는다.
구현 누락 시 조립 실패로 처리한다. Dfns의 자동 생성/회수 정책은 확인된 보장과 보류 계약이 마련된 뒤 별도로 구현한다.

Dfns 연결의 설계 후보는 다음과 같다. 아래 구조를 적용할 때는 02·03·07·09와 OpenAPI 설명을 함께 변경한다.

| 단위 | Dfns 연결 후보 | 변경 전 확인할 것 |
|---|---|---|
| BCM 계정 | `(accountType, ref)`로 유일한 논리 계정과 불변 원천을 등록한다. | V23·LogicalAccountService로 내부 등록을 구현했고, 공개 계정 API의 Dfns 연결도 [계정·주소 API의 Dfns 연결](#계정주소-api의-dfns-연결--구현)로 구현했다. accountId는 BCM 발급 ID를 유지한다. |
| 네트워크 지갑 | `(accountId, origin, network/environment)`별 조직 소유 wallet을 연결한다. | 다른 chain의 동일 주소·공유 signing key를 동일 wallet로 합치지 않는다. 초기 chain은 실제 지원 조합 확정 뒤 선택한다. |
| 토큰 주소 | 같은 네트워크 wallet에 검증한 자산 locator를 연결한다. | USDC/KRWK 심볼만으로 자산을 찾지 않는다. contract/mint·정밀도는 별도 검증한다. 같은 체인에서 토큰마다 지갑을 새로 만들지 않는 후보이며 추가 체인 주소 모델은 후속이다. |
| 생성 의도 | 원천·네트워크·요청 해시·상관관계 ID·제출 시도/회수 결과를 먼저 보존한다. | Dfns createWallet의 중복·동시 호출·응답 유실 계약 확인 전 자동 재생성을 열지 않는다. |
| 조회/회수 | wallet ID가 있으면 그 자원을 검증하고, ID가 없으면 확인된 조회 수단으로 후보를 대조한다. | 후보 하나라도 원천·network·소유·상관관계가 모두 맞아야 한다. name만 같은 자원을 자동 연결하지 않는다. |

재시도 판정은 다음처럼 제한한다.

1. 최초 호출은 의도와 고정 상관관계 값을 먼저 저장한 뒤 수행한다. 완료 시 의도와 공개 주소 매핑을 같은 트랜잭션으로 저장한다.
2. 응답 유실·시간 초과·완료 저장 실패는 미제출로 되돌리지 않는다. 조회로 회수한 동일 자원만 연결한다.
3. 조회가 0개여도 이전 요청 미처리/지연 노출 가능성이 있으면 새 POST를 내지 않는다. 후보 2개 이상·cursor 반복·속성 불일치도 자동 연결하지 않는다.
4. 조회 불확실성 해소나 벤더가 보장하는 멱등 재요청 조건 없이 시간 경과만으로 키를 회전하지 않는다.
5. Fireblocks/로컬의 현재 회수·24시간/TTL 경계는 유지한다. Dfns의 보류 응답·재개 조건·운영 복구 API는 공개 API 계약 확정 전에 추가하지 않는다.

전송도 `externalId`를 CORE 요청 ID와 무조건 같은 길이/형식으로 복사하지 않는다. BCM 요청 최대 길이·벤더 필드 제약을 대조하고,
필요하면 불변 내부 매핑을 둔다. 같은 지시의 응답 유실을 새로운 externalId로 해결하는 것은 금지한다.

### 네트워크 지갑 공통 포트와 회수 판정 — 구현

domain의 `NetworkWalletProvisioningPort`는 `create(request, submission)`, `read(scope, vendorWalletId)`,
`candidates(request, pageCursor)`를 정의한다. `NetworkWalletScope`는 불변 `ProviderOrigin` 6필드·BCM accountId·BCM network 코드다.
네트워크 코드는 [07](07-asset-master.md)의 mainnet/testnet 구분을 유지하며 벤더 network 문자열은 어댑터에서 변환한다.
`NetworkWalletCreationRequest.correlationId`는 해당 scope의 생성 의도에 한 번 저장하는 값이다. 공개 API 키나 멱등 보장의 대용이 아니다.

`NetworkWalletObservation`은 검증한 원천·네트워크·wallet ID·상관관계·조직 소유 증거·주소 준비 여부를 전달한다.
이것은 BCM 정규화 계약이며 Dfns JSON/schema가 아니다. 조직 소유는 선택 자격/조회 범위와 자원의 소유 정보를 대조해 확인하고,
확인할 수 없으면 `UNVERIFIED`로 둔다. 요청의 origin을 응답에 복사하는 것만으로 확인됐다고 표시하지 않는다.
주소는 null일 수 있으며 빈 문자열을 준비 완료 주소로 받지 않는다. 지갑 주소가 있어도 토큰 수신 계정이 준비됐다는 뜻은 아니다.

`NetworkWalletRecoveryPolicy`의 결과는 다음 세 종류뿐이다. 새 생성/키 회전 결과는 제공하지 않는다.

| 결과 | 조건 | 후속 호출자의 책임 |
|---|---|---|
| `Ready(wallet)` | 조회 범위를 성공적으로 끝내고, 정확히 하나의 지갑이 원천·network·correlation·조직 소유 및 이미 저장된 wallet ID와 일치하며 주소가 있음 | 현재 의도를 잠그고 버전·scope·known ID를 재확인한 뒤 연결과 완료를 원자 저장. 이 순수 판정만으로 DB 동시성 보장이 생기지 않음 |
| `Pending(reason)` | 미완료 조회, 후보 미관찰, 주소 미준비 | 기존 의도를 보존하고 조회만 재개. ID를 알고 있다면 주소 대기/일시적 404에서도 같은 ID 유지 |
| `Conflict(reason)` | 식별/소유 불일치, 서로 다른 지갑 2개 이상, 같은 ID의 상충하는 관찰 | 자동 연결/POST 금지, 관찰 증적과 충돌을 보존. 미완료 조회 중 발견한 충돌도 대기로 숨기지 않음 |

페이지가 겹친 완전히 같은 관찰만 dedup한다. 같은 ID에 서로 다른 주소(미준비 null 포함)가 보이면 마지막 관찰을 임의 채택하지 않는다.
검증한 새 조회의 증거로 충돌 원인을 해소하는 운영 절차는 후속이다. 후보 API는 전체 조직 목록을 무조건 회수 후보로 취급하지 않고,
확인된 조회 방식으로 의도와 관련된 항목을 좁혀야 한다. 서버의 externalId exact 검색을 가정하지 않으며,
미지원 조회/HTTP 실패를 빈 성공 페이지로 치환하지 않는다. 반복 cursor는 호출 계층에서 거절한다.
`scanComplete=true`는 이 조회의 마지막 페이지를 성공적으로 읽었다는 뜻이다. 동시 생성·지연 노출의 부재 보장이 아니므로 0건이어도 생성하지 않는다.
known ID 단건 조회는 응답 검증까지 성공한 때만 완료 조회로 판정하며, null은 현재 미관찰로 처리한다.

포트에는 기본 성공/빈 조회 구현이 없다. 실행 어댑터(`DfnsNetworkWalletClient`)와 공개 계정·주소 API 조립은 이후 슬라이스에서 구현했고
`BCM_PROVIDER=dfns`에서만 조건부로 뜬다 — 전체 기동 차단과는 별개다. 기존 `AccountService`는 계속
Fireblocks `WalletVendorPort`와 기존 회수 정책을 쓴다. Dfns에 현재 vault 포트를 억지로 연결하거나 `WalletCreationPolicy`를 기본 제공하지 않는다.
내부 `NetworkWalletProvisioningService`는 생성 응답도 조회와 같은 원장 판정에 전달해 식별/소유 검사를 적용한다.
최초 제출 권한·재시작 복구는 [03의 V22 원장](03-bcm-db.md#v22-네트워크-지갑-원장--물리-저장-계약)과
`NetworkWalletProvisioningRepository`/`NetworkWalletProvisioningJdbcAdapter`로 구현했다.
예약과 최초 권한은 호출자 트랜잭션과 독립적으로 커밋하며, 회수 페이지의 증적·후보·cursor와 완료 연결을 원자 저장한다.
미완료 scan에서 유일하게 검증된 ID도 known ID로 고정해 다른 scan의 후보로 바꾸지 않는다.
V23 논리 계정과 내부 생성 서비스를 연결했다. HTTP 호출·원문 보관 어댑터·공개 주소 연결은 이후 슬라이스에서 구현했다 — 저장 결과나 조립 사실을 Dfns 수용으로 해석하지 않는다.

## 실행 원천과 저장 식별자

다음은 필요한 논리 키다. 초기 단일 데이터셋 원천의 물리 binding은 [03](03-bcm-db.md#제공자-원천-binding--후속-물리-계약)에서 상세화한다.
네트워크 지갑 생성·조회/연결 원장은 V22, 계정 모델 구분은 V23으로 구현했다. Dfns 요청/이동·수신 시도 연결 테이블은 후속이다.

| 식별 대상 | 반드시 분리할 내용 |
|---|---|
| 실행 원천 | 실행 모드(fireblocks/dfns/local), 실제 프로토콜 제공자, 플랫폼 instance/조직, 체인 환경. URL·API key를 원천 ID로 사용하지 않는다. |
| 계정/지갑 | BCM accountId, 벤더 자원 종류(VAULT/WALLET), 해당 원천에서의 자원 ID, network binding. 빈 vault ID나 합성 wallet ID로 필수 컬럼을 채우지 않는다. |
| 제출 | BCM externalTxId/요청 해시, 원천과 API 작업 종류, 벤더 request ID. Transfer Request·Transaction Request·블록체인 이동의 ID를 같은 의미로 보지 않는다. |
| 수신 시도 | 원천+delivery ID, 실제 종류/관련 자원, 원문·서명·해시·수신시각, retry 연관. 서명 시간과 사건 발생 시간도 구분한다. |
| 논리 이동 | network/chain 증거와 항목 식별자, 관련 벤더 요청들, 재편/대체 관계. txHash 하나로 모든 토큰 이동이나 모든 확정 단계를 합치지 않는다. |
| CORE 이벤트 | 기존 evnt_id와 감지→확정 순서. 벤더 delivery ID나 txId를 새 dedup 키로 채택하지 않는다. |

현행 `bcm_tx_l` PK·`bcm_sbmt_l`/sweep의 벤더 ID UNIQUE·인박스 noti_id·raw archive·대사 cursor가 원천 정보를 공유해야 한다.
한 프로세스에 제공자 하나만 조립해도 저장된 다른 조직/환경의 ID를 같은 제공자 ID로 잘못 제출할 수 있으므로 다음 DB 계약이 필요하다.

- API/Webhook/BAT가 동일한 영속 원천 binding을 검증한다. 설정과 다른 원천은 조회 후 제출·서명·회수·재시도 전에 거절한다.
- 기존 데이터의 백필은 실제 Fireblocks workspace/환경 증거로 수행한다. 환경변수에서 현재 선택값을 읽어 모든 행을 새 벤더로 덮어쓰지 않는다.
- 원천 없는 행·다른 원천·진행 중 의도·복구 cursor를 사전 점검한다. local은 기존 별도 데이터셋을 유지한다.
- 새로운 내부 거래 키가 필요해도 공개 `txId`(최초 root 벤더 ID)와 기존 이벤트 키를 소급 치환하지 않는다. API 호환 조회/매핑 및 백필·롤백 계약을 먼저 정한다.

단일 데이터셋 원천 대조는 V21과 세 앱 공통 guard로 구현했다. Dfns 자원 모델·어댑터와 수용이 남아 있으므로 `BCM_PROVIDER=dfns` 기동 차단을 유지한다.

## 웹훅 수신 구현과 Dfns 후속

이번에 구현한 공통 경계는 [설계12의 WebhookProtocol](12-provider-compatibility.md#웹훅-수신-프로토콜-경계)다.
Fireblocks의 서명 헤더와 `id/eventType/data.id` 추출을 infra/client로 옮긴다. 같은 원문·해시·서명·인박스 dedup·worker/outbox 처리를 유지한다.
서명 헤더 중복은 401로 거절한다. 비선택 헤더를 시도하거나 실패 후 다른 제공자 검증기로 바꾸지 않는다.

Dfns 연결 전에 다음 경계를 추가로 확정한다.

- 서명 입력이 실제 수신 바이트인지, 벤더가 요구하는 정규화가 있는지 서명된 원문으로 검증한다. JSON 예제만으로 서명 테스트를 만들지 않는다.
- secret의 출처/회전 세대와 검증에 사용한 키 식별 증적을 보존한다. secret 자체는 BCM 업무 DB·로그에 저장하지 않는다.
- timestampSent 단위·허용 오차·미래/오래된 요청 처리와 회전 중 검증 순서를 고정한다. HMAC 공유 secret의 검증은 비대칭 서명의 제3자 발신 증명과 의미가 다르다.
- `kind`에 맞춰 관련 request/chain event를 해석한다. wallet 생성·정책 이벤트의 ID를 거래 ID로 넣지 않는다. 확인 안 된 Dfns 종류를 Fireblocks 상태로 번역하지 않는다.
- 재전달 시도는 감사용으로 보존하되 논리 사건 단계별 처리는 한 번만 수행한다. retryOf만으로 전체 논리 이동을 dedup하지 않는다.
- 인증된 이력 조회 복구는 HTTP 실시간 수신과 다른 진입점이다. 과거 timestamp/누락된 실시간 서명을 성공으로 간주하거나 가짜 sign_vl로 인박스에 넣지 않는다.
  복구 출처·조회 범위·원문 증적·영속 cursor·겹치는 구간 재처리·동시 worker 통제의 저장 계약을 먼저 마련한다.
- 문서의 “오류여도 200 응답” 예제를 BCM에 적용하지 않는다. BCM은 durable inbox 수용 후에만 성공을 응답한다.
  장기 복구는 벤더 이력 보존 기간을 넘는 별도 관찰/보관 책임과 함께 정한다.

### Dfns 웹훅 수신 프로토콜 — 구현

근거: 공식 가이드 [Webhooks](https://docs.dfns.co/guides/developers/webhooks)(`.md` SHA-256 `db4a394386c5b8e14dc715768973e75098954e83fc28e7537cb090ac8624be78`, 2026-09-16 확인)와
채택 명세 1.1018.3의 조회 모델 `WebhookEvent`(필수 `id`·`date`·`kind`·`data`·`status`·`timestampSent`(Unix 초, 양수))·`WebhookWithSecret.secret`(생성 응답에서만 제공).
구현은 `DfnsWebhookSignatureVerifier`·`DfnsWebhookProtocol`(infra/client)이며 `DfnsClientConfig`가 `WebhookProtocol`은 모든 앱에, HMAC 검증기는 `bcm.webhook.ingestion.enabled=true`인 Webhook 앱에만 만든다.

| 항목 | 명세·가이드로 확인한 사실 | BCM 규칙 |
|---|---|---|
| 서명 헤더 | `X-DFNS-WEBHOOK-SIGNATURE: sha256=<hex>` — payload의 HMAC-SHA256, 키는 webhook secret | 접두사·64자 hex 형식이 아니면 거절. 헤더 없음·중복은 공통 경계가 401 |
| 서명 입력 | 가이드 예제는 **파싱한 payload를 다시 직렬화**(`JSON.stringify` / compact `json.dumps`)해 서명한다. 실제 발송 바이트와의 관계는 서술이 없다 | **수신 바이트 그대로** HMAC을 계산한다(CLAUDE.md 3절). 재직렬화하지 않고, 실패해도 다른 입력으로 재시도하지 않는다. 발송 바이트≠재직렬화 결과인 경우는 아래 수용 항목이다 |
| secret | 생성 응답에서 한 번만 제공, 회전은 새 webhook 생성 후 옛 것 삭제 | `bcm.dfns.webhook-secrets`(env, 1개 이상, 순서대로 대조)로만 주입하고 DB·로그에 남기지 않는다. 어느 secret이 맞았는지의 증적은 후속(인박스에 키 식별 컬럼 없음) |
| 재전송 방어 | 가이드 예제: `|now − timestampSent| < 5분` | `timestampSent`가 정수가 아니거나 없거나 `bcm.dfns.webhook-replay-tolerance-seconds`(기본 300) 밖이면 서명이 맞아도 거절. 비교는 상수 시간 |
| envelope | `id`(알림 ID)·`kind`(사건 종류)는 필수 문자열. 조회 모델 `WebhookEvent`의 `data`는 형식 미정의 객체다 — 실제로 전달되는 본문의 kind별 형식은 같은 명세의 `webhooks` 항목에 있다 | `notificationId=id`, `eventType=kind`. `vendorTransactionId`는 **형식이 문서화된 종류에서만** 채운다 — 아래 [웹훅 전송 사건 관찰](#웹훅-전송-사건-관찰--구현)의 `wallet.transfer.*`는 `data.transferRequest.id`를 쓰고, 나머지 종류는 근거가 없으므로 null로 둔다. 인박스 dedup 키는 `id`이며 재전달은 ID가 다르면 별도 수신이다 |
| 판단 | — | **입금·발신 판단 경로가 모두 조립됐다** — 워커가 [전송 알림 판단](#전송-알림-판단--구현)을 먼저 부르고, 전송 사건이 아니면 [판단 워커 조립](#판단-워커-조립--구현)의 온체인 이동 판단으로 넘긴다. `WebhookTransactionParser`의 Dfns 구현은 없다(Fireblocks 전용 경계). `BCM_PROVIDER=dfns` 전체 기동 차단은 유지한다 |

수용 항목: 실제 Baseline이 보낸 서명 원문(바이트)과 위 원문 검증의 일치, `timestampSent` 단위·허용 오차 적정성, 재전달 시도의 ID/`retryOf` 의미.

### 웹훅 전송 사건 관찰 — 구현

근거: 채택 명세 1.1018.3의 **`webhooks` 항목** — `wallet.transfer.requested`·`.broadcasted`·`.confirmed`·`.failed`·`.rejected`와
공통 `WebhookEnvelopeBase`, 그리고 각 사건의 `data.transferRequest`가 참조하는 `TransferRequest` schema다(파일 해시는 위 [공식 OpenAPI 재확인](#공식-openapi-재확인과-구현-근거-2026-09-14-사용자-정정) 표).
현재 OpenAPI 2.0.54도 같은 형식을 둔다. **수신 envelope(`WebhookEnvelopeBase`)와 조회 모델(`WebhookEvent`)은 서로 다른 schema다** —
[위 절](#dfns-웹훅-수신-프로토콜--구현)에서 `data`가 형식 미정의라고 적은 것은 조회 모델 쪽이고, 실제로 전달되는 본문의 kind별 형식은 `webhooks` 항목에 있다.
따라서 이 슬라이스는 수신 envelope schema를 그대로 요구하고, 어긋나면 조용히 버리지 않고 실패한다. 실제 Baseline이 보내는 본문의 일치는 수용 항목이다.

구현은 도메인 출력 포트 `NetworkTransferEventParser`(+`NetworkTransferEvent`·`NetworkTransferEventKind`)와 `DfnsNetworkTransferEventParser`(infra/client)다.
`TransferRequest` 정규화는 조회 어댑터와 **같은 코드**(`DfnsTransferRequests`)를 쓴다 — 두 경로가 같은 schema를 읽으므로 검사도 하나여야 한다.
**판단 워커·제출 원장에 연결했다** — [전송 알림 판단](#전송-알림-판단--구현)이 이 파서를 쓴다. `WebhookTransactionParser`의 Dfns 구현은 여전히 없다(Fireblocks 전용 경계다).

| 항목 | 명세로 확인한 사실 | BCM 규칙 |
|---|---|---|
| 사건 종류 | `webhooks` 항목과 `WebhookEventKind` enum은 지갑·서명·거래·전송·입금 감지·정책 등 24종을 둔다. 전송은 `wallet.transfer.{requested,broadcasted,confirmed,failed,rejected}` 다섯이고 각 설명은 "요청 생성/mempool 기록/온체인 확인/처리 실패/정책 거절"이다 | 이 다섯만 `NetworkTransferEventKind`로 옮긴다. 전송이 아닌 종류는 이 포트의 관심 밖(null)이며, 특히 `wallet.transaction.*`(임의 트랜잭션)과 `wallet.blockchainevent.*`(입금 감지)를 전송으로 합치지 않는다 — 각각 별도 계약이 필요하다 |
| 알림 메타(모든 종류 공통) | `WebhookEnvelopeBase`: 필수 `id`(`^whe-…$`)·`date`(UTC ISO 8601)·`timestampSent`·`deliveryAttempt`(정수 ≥1), 선택 `retryOf`(원본 알림 ID, 같은 형식·`minLength 1`) | 명세가 필수로 둔 것은 **모두 필수로 받는다** — `id`는 형식까지 검사하고, `date`는 UTC가 아니거나 형식이 다르면 거절하며, `deliveryAttempt`는 결손·비정수·0 이하를 거절한다(기본값 1을 지어내지 않는다). `retryOf`는 선택이지만 **있으면** 같은 형식이어야 하고 빈 값을 결손으로 축소하지 않는다. `timestampSent`는 앞선 서명 검증이 이미 필수로 검사하므로 여기서 다시 보지 않는다 |
| 전송 정보 | 전송 다섯 종류의 `data.transferRequest`는 조회 응답과 **같은 `TransferRequest`**다 | 조회와 같은 검사를 같은 코드로 적용한다 — `id` 형식(`^xfr-…$`)·`walletId`·`network`·`requester.userId`·`metadata`·`requestBody`의 `kind`/locator/`to`/`amount`·`status`·UTC `dateRequested`. 자산 키는 등록과 같은 규칙(EVM 주소 형식·Solana base58 32바이트)으로 만든다. 웹훅은 우리가 부른 응답이 아니므로 대조할 기대 지갑이 없다 — `walletId`는 사건이 알려준 값을 그대로 담고 업무 소유 판정은 판단 워커의 몫이다 |
| 상태의 출처 | 사건 종류와 `transferRequest.status`가 항상 짝을 이룬다는 서술은 **없다** | 업무 상태는 **종류가 아니라 `status`에서** 읽는다. 종류를 상태로 번역하지 않으며 `Confirmed`를 BCM `FINALIZED`로 옮기지 않는다(`TxStatus` 번역은 판단 워커와 함께 정한다) |
| 네트워크 | `transferRequest.network`는 명세 `Network` enum 값이다 | `bcm.dfns.networks`(BCM 코드 → 명세 값, 값 중복 금지)의 **역방향**으로 BCM 코드를 되찾는다. 매핑에 없는 네트워크의 전송 사건은 해석할 수 없으므로 **거절**한다 — 조용히 넘기면 관리 대상 이동을 놓친다 |
| 해석 실패 | — | 전송 종류인데 `data.transferRequest`가 없거나 형식이 다르면 `WebhookPayloadException`으로 올린다. 자금 이동 신호를 "해석 불가"로 축소하지 않는다. 메시지에는 필드 이름만 담고 원문 값은 담지 않는다(원문 증적은 인박스) |
| 수용 경계 | — | envelope 해석(`DfnsWebhookProtocol`)은 전송 종류에서 `data.transferRequest.id`가 명세 형식일 때만 `vendorTransactionId`를 채우고 **어긋나도 거절하지 않는다** — 인박스 수용(원문 보관)을 막지 않고 엄격한 해석은 판단 시점의 파서가 맡는다 |
| 범위 밖 | — | `wallet.transaction.*`·정책/지갑 사건, `WebhookTransactionParser`의 Dfns 구현, 재전달 dedup과 이력 복구. 입금 감지는 [웹훅 온체인 이동 사건 관찰](#웹훅-온체인-이동-사건-관찰--구현), 상태 번역은 [상태 번역](#상태-번역--구현), 워커 조립은 [판단 워커 조립](#판단-워커-조립--구현)으로 이후 구현했다 |

수용 항목: 실제 Baseline이 보내는 전송 사건 본문이 위 수신 envelope schema와 같은지(특히 `deliveryAttempt`·`retryOf`의 실제 제공 형태),
조직 웹훅이 BCM 미관리 네트워크의 전송 사건도 보내는지(보낸다면 위 "거절"을 건너뛰기로 바꿀지),
같은 전송의 종류-상태 조합이 실제로 어떻게 오는지(예: `wallet.transfer.confirmed`에 `Failed` 상태가 오는 경우), 재전달의 순서·중복 처리 기준.

### 웹훅 온체인 이동 사건 관찰 — 구현

근거: 채택 명세 1.1018.3 `webhooks`의 `wallet.blockchainevent.detected`("A wallet event has been confirmed on chain (e.g.: a deposit)")·
`wallet.blockchain_event.transfer.included`("included in a block but is not yet confirmed on chain", 일부 네트워크만)와 두 사건의
`data.blockchainEvent`(`WalletHistoryEvent`)·`data.wallet`(`Wallet`), 그리고 위 [알림 메타](#웹훅-전송-사건-관찰--구현)와 같은 `WebhookEnvelopeBase`다.
구현은 도메인 출력 포트 `NetworkChainEventParser`(+`NetworkChainEvent`·`NetworkChainEventKind`·`NetworkChainTransfer`·`NetworkChainDirection`·`NetworkChainTransferStatus`)와
`DfnsNetworkChainEventParser`(infra/client)다. **`dfns`에서 조립되어 입금 판단과 [발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)가 함께 소비한다**([판단 워커 조립](#판단-워커-조립--구현)).

| 항목 | 명세로 확인한 사실 | BCM 규칙 |
|---|---|---|
| 사건 종류 | 지갑 대상은 위 둘이다. `address_watch.blockchain_event.transfer.confirmed`는 **감시 주소**(키를 갖지 않는 등록 주소) 대상이고 `wallet.transaction.*`는 임의 트랜잭션이다 | 지갑 대상 둘만 `NetworkChainEventKind`로 옮긴다. 감시 주소 사건은 등록 주소 기능(`/address-watches`)을 쓰기로 정한 뒤에 다룬다 — 지갑 이동으로 합치지 않는다 |
| 이동 종류 | `WalletHistoryEvent`는 `kind`별 oneOf이고 문서화된 종류는 **28개**다. 모델 대상은 `NativeTransfer`·`Erc20Transfer`(locator `contract`)·`SplTransfer`/`Spl2022Transfer`(locator `mint`)이고 나머지는 NFT·UTXO·다른 체인 표준이다 | 이동 종류를 **목록으로** 자산 kind에 대응시킨다(`Erc20Transfer`→`Erc20`) — 이름이 다르므로 접미사를 잘라 추정하지 않는다. **문서화된** 미지원 종류는 대조 키(`vendorAssetId`)와 금액을 만들지 않고 원어(`vendorAssetKind`)만 남긴 채 사건을 보존한다 — 등록할 수 없는 자산이라도 이동 사실을 버리지 않는다(미지원 자산 판단은 판단 워커의 몫). **문서에 없는 종류는 어느 변형도 만족하지 않는 본문이므로 미지원으로 받아들이지 않고 거절한다** |
| 자산 키 | 모델 대상 변형의 locator 필드는 자산 조회·전송과 같은 이름이다 | 등록·잔액·전송과 **같은 키 규칙**(EVM 주소 형식·Solana base58 32바이트)으로 만든다. locator 형식이 깨졌으면 키를 만들지 않고 실패한다 |
| 필수 필드 | 변형마다 required가 다르다. 모든 변형의 교집합은 `walletId`·`direction`·`network`·`blockNumber`·`txHash`·`timestamp`·`status`·`metadata`·`kind` 아홉이다. 변형별로는 `NativeTransfer`가 `value`·`symbol`·`decimals`, `Erc20Transfer`가 `contract`·`from`·`to`·`value`·`decimals`, `SplTransfer`/`Spl2022Transfer`가 `mint`·`value`를 더 요구한다(Solana 변형은 `from`·`to`를 요구하지 않는다) | 공통 필수 아홉은 모든 종류에서 검사하고, 모델 대상은 그 변형이 요구하는 필드까지 검사한다. **관찰값에 담지 않는 필드도 결손이면 명세를 만족하지 않으므로 거절한다** — `symbol`·`decimals`처럼 폐기 예정 표기가 붙었어도 required 목록에 남아 있는 동안은 요구한다(아래 수용 항목). 문서화된 미지원 종류는 공통 필수만 검사한다 — 그 변형의 locator를 대조에 쓰지 않으므로 형식을 판단하지 않는다 |
| 지갑 결속 | 사건의 `walletId`와 함께 `data.wallet`(필수)이 온다. `Wallet`의 `id`·`network`는 필수이고 `address`는 **선택**이다 | `data.wallet`의 `id`·`network`가 사건의 `walletId`·`network`와 각각 같아야 한다 — 다르면 어느 지갑의 어느 네트워크 이동인지 증명하지 못하므로 거절한다. 지갑 주소는 있으면 담고 없으면 null이다(주소 대조·입금 귀속은 판단 워커) |
| 방향·상태 | `direction`은 `In`/`Out`, `status`는 `Included`/`Confirmed`이며 `Confirmed`는 "confirmed on chain by our indexing pipeline"이다 | 원어 그대로 옮기고 그 밖의 값은 거절한다. **`Confirmed`를 BCM 확정(DCCP)으로 번역하지 않는다** — 종류와 상태가 고정 대응한다는 서술도 없으므로 상태는 사건 본문에서 읽는다 |
| 금액 | `value`는 문자열이고 모델 대상 변형에서 필수다. **단위를 서술한 곳이 없다** | 전송 요청과 같은 규칙으로 **최소 단위 정수**(선행 0 금지)로 읽는다. 이는 BCM 해석이며 정수 검사는 비정수만 걸러낼 뿐 단위를 증명하지 못한다(아래 수용 항목) |
| 정밀도·심볼 | `metadata.asset`은 필수지만 그 안의 `symbol`·`decimals`·`verified`는 필수가 아니다. 최상위 동명 필드는 `@deprecated`이면서 일부 변형의 required 목록에 남아 있다 | 관찰값에 **담지 않는다** — 선택이자 폐기 예정인 벤더 필드에 업무 판단을 걸지 않는다. 담지 않는 것과 명세 필수 필드의 존재를 검사하는 것은 별개다(위 필수 필드 행). **정밀도의 출처는 등록 매핑이다**(03 V27의 `dcml_cnt`) — 관찰이 아니라 등록값으로 환산한다. Dfns 원천은 정밀도 없이 등록할 수 없다 |
| 체인 좌표 | 필수 `blockNumber`(number)·`txHash`·`timestamp`(문자열, 형식 서술 없음), 선택 `index`(문자열) | `blockNumber`는 정수·음수 아님만 받는다. `timestamp`는 **파싱하지 않고 원문 그대로** 둔다 — `date`·`dateRequested`와 달리 형식·시간대 서술이 없다. `index`는 있으면 원문으로 담는다 |
| 범위 밖 | — | `WebhookTransactionParser`의 Dfns 구현·미등록 자산 입금 경보 포트·감시 주소 기능·이력 복구. 입금 귀속·상태 번역·논리 사건/outbox·워커 조립은 [온체인 이동의 귀속](#온체인-이동의-귀속--구현) 이후 슬라이스에서 구현했다 |

**해결(2026-09-16 사용자 확정) — 정밀도는 등록 시점에 저장한다**([03 V27](03-bcm-db.md#v27-등록-자산의-정밀도-보관--물리-저장-계약)).
Dfns 원천은 카탈로그가 없어 운영자가 발행사 자료와 대조해 등록하며 정밀도 없이는 등록할 수 없다(`decimalsRequired`).
벤더가 사건마다 주는 `metadata.asset.decimals`에 의존하지 않는다 — 명세상 필수가 아니고 최상위 동명 필드는 폐기 예정이며,
벤더가 인덱싱 값을 바꾸면 같은 자산의 과거·미래 금액 해석이 달라진다. 환산은 도메인 `AssetDecimals.amountOf`로만 한다.

배경: [02](02-bcm-flow.md#상태-enum)는 이벤트에 금액을 싣고 DAW-CORE가 그 값으로만 입금 금액을 안다. Fireblocks는 `amountInfo.amount`(사람 단위)를
그대로 싣지만 Dfns 관찰은 최소 단위 정수다. 제공자마다 같은 `amount` 필드의 단위가 달라지면 조용한 금액 사고가 되므로 환산이 필요하고,
07이 "decimals 저장 확장은 DF1에서 확정한다"로 열어 둔 항목을 이번에 닫았다. **정밀도가 없는 자산(기존 NULL 행)은 환산하지 않는다.**

수용 항목: `value`의 단위(최소 단위인지)와 `timestamp`의 형식·시간대, 폐기 예정 표기가 붙은 `symbol`·`decimals`를 실제 Baseline이 계속 보내는지(required에서 빠지면 위 검사도 함께 푼다), 문서화된 미지원 이동 종류가 실제로 얼마나 오는지,
`Included`→`Confirmed` 재알림의 순서·중복과 두 종류가 같은 이동에 모두 오는지, Solana의 ATA 수신에서 `to`가 owner 주소인지 ATA 주소인지,
`Wallet.address`가 실제로 항상 오는지, 조직 웹훅이 BCM 미관리 네트워크의 이동 사건도 보내는지.

### 확정 판정 — 구현

근거: [CLAUDE.md 3절 확정 결정](../../CLAUDE.md)(2026-09-16 사용자 확정)과 [02의 Dfns 경로 확정 근거](02-bcm-flow.md#dfns-경로의-확정-근거-2026-09-16-사용자-확정).
명세 쪽 사실은 위 [온체인 이동 사건](#웹훅-온체인-이동-사건-관찰--구현)과 [전송 상태](#전송-제출조회-계약--구현) 표에 있다 —
Dfns는 **컨펌 수를 주지 않고** `Included`/`Confirmed`와 `blockNumber`만 준다. `Confirmed`의 설명은 "confirmed on chain by our indexing pipeline"이며
네트워크별 확인 지연은 벤더 문서의 별도 표에 있다.
구현은 도메인 `ChainHeadPort`·`BlockDepthFinality`와 `EvmChainHeadClient`(infra/client)다. **Webhook 앱의 `dfns` 조립에서만 만들어 입금 판단과 [발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)가 쓴다** — 출금의 확정도 같은 깊이 판정이다.

| 항목 | 근거 | BCM 규칙 |
|---|---|---|
| 확정 근거 | 벤더의 `Confirmed`는 reorg로 뒤집힐 수 있다(사용자 확정) | **벤더 상태 표기를 `FINALIZED`의 근거로 쓰지 않는다.** 사건의 `blockNumber`와 체인 head의 깊이를 직접 계산한다 |
| 깊이 산식 | — | 블록 자체가 1컨펌이다(`head − blockNumber + 1`). head가 사건 블록보다 낮게 보이면(관측 지연·재구성) **0**으로 본다 — 음수 컨펌을 만들지 않는다. 산식이 `Long` 범위를 넘으면 값을 지어내지 않고 실패해 확정을 보류한다 — 넘침을 음수나 포화값으로 바꾸면 확정을 잘못 낸다 |
| 임계 | 기존 `bcm.finality-confirmations.<network>`(02 DCCP 임계와 같은 설정) | 같은 설정을 그대로 쓴다 — 제공자마다 확정 임계 설정을 따로 두지 않는다. 값이 없거나 0 이하면 기존 `FinalityPolicyConfigurationException` 경로로 중단한다 |
| head 출처 | 위탁 RPC(`bcm.evm-rpc.networks.<network>.url`, EVM `eth_blockNumber`) | 설정에 없는 네트워크는 임의 endpoint를 고르지 않고 중단한다. RPC 오류·결손·형식 오류·범위 밖 값은 head로 받지 않는다 |
| 조회 실패 | — | 확정을 **보류**하고 재시도한다 — 실패를 감추지 않고 예외로 올린다. **모름을 "아직 미확정"으로 바꾸지 않는다**(바꾸면 늦은 확정이 영영 오지 않는다) |
| 범위 밖 | — | Solana의 확정(슬롯·commitment 모델이 EVM 블록 깊이와 다르다)·head 캐시/조회 주기·reorg 무효화(`FINALIZED → FAILED`) 관찰 경로. 상태 번역과 워커 조립은 이후 슬라이스에서 구현했다 |

수용 항목: 위탁 RPC endpoint의 운영 소유·가용성과 head 조회 주기·캐시 정책, 네트워크별 임계값(Dfns 문서의 확인 지연과 BCM 임계의 관계),
Solana 확정 모델(`finalized` commitment 사용 여부), reorg로 사건 블록이 사라졌을 때의 관찰 경로(벤더가 무효화 알림을 보내는지).

### 상태 번역 — 구현

근거: [02의 TxStatus 다섯과 전이 표](02-bcm-flow.md#상태-enum), [CLAUDE.md 3절의 확정 결정](../../CLAUDE.md), 그리고 위
[전송 상태](#전송-제출조회-계약--구현)·[온체인 이동 상태](#웹훅-온체인-이동-사건-관찰--구현) 표의 명세 사실.
구현은 `DfnsStatusTranslator`(infra/client)이며 기존 도메인 포트 `VendorStatusTranslator`를 그대로 구현한다. **Webhook 앱의 `dfns` 조립에서만 만들어 입금 판단·전송 알림 판단·발신 확정의 블록 좌표가 함께 쓴다**.

| 벤더 원어 | TxStatus | 근거 |
|---|---|---|
| `Pending`(지갑 정책 승인 대기) · `Executing`(승인 후 실행 중) · `Broadcasted`(mempool 기록) | `SUBMITTED` | 02의 `SUBMITTED`는 "서명·전파 준비 중, 체인 미등장"이다. mempool은 블록에 들어가기 전이다 |
| `Included`(블록 포함, 벤더 확인 전) · `Confirmed`(벤더 인덱싱 확인) | 깊이 ≥ 임계면 `FINALIZED`, 아니면 `CONFIRMED` | 둘 다 블록 좌표가 있는 온체인 관찰이라 **같은 깊이 판정**을 쓴다. 확정은 오직 블록 깊이로 내며(`bcm.finality-confirmations.<network>`와 비교) 벤더의 확인 표기는 확정의 근거도 **추가 관문도 아니다** — 관문으로 두면 `Confirmed` 알림이 늦거나 유실될 때 깊이가 차도 확정이 영영 나오지 않는다. 깊이가 임계에 못 미치면 02의 `CONFIRMED`("체인에 등장, 컨펌 누적 중 — 미확정")다 |
| `Failed`(시스템 실패 또는 온체인 실행 실패) | `FAILED` | 02의 `FAILED`는 영구 실패 |
| `Rejected`(정책 승인 거절) | `REJECTED` | 02의 `REJECTED`는 거부·차단이며 출금은 벤더 기준 종결 |
| 그 밖의 원어 | — | 임의 상태로 바꾸지 않고 `WebhookPayloadException`으로 거절한다 |

- **전송 응답만으로는 확정이 나오지 않는다** — `TransferRequest`에는 `blockNumber`가 없어 깊이를 계산할 수 없다. 그래서 전송 경로의 `Confirmed`는 `CONFIRMED`에 머물고,
  출금의 확정도 같은 거래의 **온체인 이동 사건**(`direction: Out`)에서 판정한다. 깊이를 모르는 관찰의 컨펌 수는 0이다.
- 관찰값에 담을 컨펌 수는 domain `BlockDepthFinality.confirmationCount`가 만든다 — `Int` 상한을 넘는 깊이는 상한으로 줄인다(어떤 임계보다도 커서 판정이 달라지지 않는다).
- **대사 종결 판정(`terminalStatusForReconciliation`)은 만들지 않는다**(항상 null) — Dfns 대사 경로(`VendorTransactionPort` 목록 조회)가 없고,
  `Confirmed`를 종결로 돌려주면 블록 깊이 확정 결정을 우회하게 된다. 포트 계약의 "대상 밖은 null"을 그대로 쓴다.
- Fireblocks의 동결 subStatus(`AUTO_FREEZE` 등)에 해당하는 개념은 Dfns 문서에 없다 — 없는 것을 만들지 않는다. `FINALIZED → REJECTED`(확정 후 동결) 전이의 Dfns 관찰 경로는 수용 항목이다.

수용 항목: 확정 후 동결·무효화에 해당하는 Dfns 관찰이 있는지(있다면 `REJECTED`·`FAILED` 역전이의 입구), `Rejected`가 정책 거절 외에도 쓰이는지,
`Broadcasted` 없이 `Confirmed`만 오는 경우가 있는지(02의 감지 이벤트 합성 규칙 적용 범위), 대사 경로를 만들 때의 Dfns 목록 조회 계약.

### 온체인 이동의 귀속 — 구현

근거: [02의 웹훅 계열 분류](02-bcm-flow.md#웹훅-계열-분류--제출-원장이-기준이다)·[입금](02-bcm-flow.md#입금)과 [09의 주소 매핑](09-asset-map.md),
그리고 위 [온체인 이동 사건](#웹훅-온체인-이동-사건-관찰--구현)의 관찰값. 구현은 도메인 순수 판정 `NetworkChainAttribution`과 조회 포트 `NetworkChainLedgerLookup`이다.
**저장·발행·상태 번역을 하지 않는다** — 어떤 업무 대상인지만 가른다. 입금 판단이 이 판정을 그대로 따른다([판단 워커 조립](#판단-워커-조립--구현)).

| 판정 순서 | 조건 | 결과 | 근거 |
|---|---|---|---|
| 1 | `direction = Out` | `Outgoing` | 02는 우리 발신을 주소가 아니라 **제출 원장**으로 가른다. 그 대조는 [발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)가 한다 |
| 2 | 모델링하지 않은 이동 종류(`vendorAssetId`가 없다) | `UnsupportedAsset` | 등록할 수 있는 자산이 아니다 |
| 3 | 등록 매핑 없음 | `UnmappedAsset` | Dfns 조직 지갑은 등록하지 않은 토큰도 받는다 |
| 4 | 매핑 network ≠ 관찰 network | **중단(데이터 결함)** | 자산 키가 벤더 network를 포함하므로 어긋날 수 없다 — 어긋나면 등록 데이터가 깨진 것이다 |
| 5 | 목적지 주소 없음 | `Unattributed(MISSING_DESTINATION)` | 명세상 `Native`·`Spl` 변형에서 `to`는 선택이다 |
| 6 | 발급 주소 아님 | `Unattributed(UNKNOWN_ADDRESS)` | 경보 대상 |
| 7 | 그 밖 | `Deposit(accountId, network, symbol)` | 발급 주소 → 계정으로 귀속한다 |

- **미지원·미등록 자산과 미귀속을 예외가 아니라 결과로 가른다.** Fireblocks 경로는 등록하지 않은 자산 알림을 원문 결함(`WebhookPayloadException`)으로 거절하지만,
  Dfns의 조직 지갑은 누구나 토큰을 보낼 수 있어 그것은 수신 실패가 아니라 **운영이 판단할 신호**다. 경보·이벤트 발행 여부는 판단 워커에서 정한다.
- 주소 조회는 **등록 매핑의 network·symbol**로 한다 — 관찰이 알려준 값이 아니라 우리가 등록한 값이 기준이다.
- 귀속은 발급 주소(`bcm_addr_m`) 대조로만 한다. 지갑 ID로 계정을 되찾는 역방향 조회는 두지 않았다 — 저장 계약이 아직 없고, 새 조회 경로를 추정으로 만들지 않는다.

수용 항목: Solana SPL 입금의 `to`가 owner 주소인지 **ATA 주소**인지(ATA면 발급 주소 대조가 빗나가 `UNKNOWN_ADDRESS`가 된다 — DF3.12의 수용 항목과 같은 결정이다),
`to`가 없는 이동 종류가 실제로 오는지, 미등록 자산 입금의 운영 처리(경보 수준·등록 유도), 우리 발신 이동의 제출 원장 대조 계약.

### 논리 거래 식별자 — 구현

근거: [03의 V26 물리 저장 계약](03-bcm-db.md#v26-dfns-논리-거래-식별자와-온체인-hash-조회--물리-저장-계약)(2026-09-16 사용자 확정)과
명세 사실 — 전송 요청에는 `xfr-…` ID가 있지만 **온체인 이동 사건(`WalletHistoryEvent`)에는 ID 필드가 없다**(이력 조회 응답도 같다).
구현은 도메인 순수 규칙 `NetworkChainTransactionId`와 V26 index다. 입금 판단이 이 규칙으로 원장 키를 만든다([판단 워커 조립](#판단-워커-조립--구현)).

| 항목 | 명세로 확인한 사실 | BCM 규칙 |
|---|---|---|
| 출금(우리 제출) | `TransferRequest.id`는 `^xfr-…$`로 64자 이내다 | 벤더가 준 ID를 논리 거래 ID로 그대로 쓴다 — 만들지 않는다 |
| 입금(관찰만) | 이동 사건은 `walletId`·`txHash`·`index`·`blockNumber`만 준다. **ID가 없다** | `dfns-` + SHA-256(network·hash·순번) 요약 52자(합 57자, `vndr_tx_id` 64자 안). 같은 이동은 몇 번을 다시 봐도 같은 ID여야 02의 전이 판정이 성립하므로 **결정적**이어야 한다 |
| 순번 | `index`는 어느 변형의 required에도 없다(**선택**) | 파생 ID의 **필수 입력**이다. 순번이 없으면 한 트랜잭션의 여러 이동이 같은 PK가 되어 서로 다른 계정·자산의 자금이 한 논리 거래로 합쳐진다. 순번 없는 사건은 ID를 지어내지 않고 실패시켜 **처리 보류**로 남긴다 |
| 파생 입력 | — | 길이를 앞에 붙여 이어 붙인다 — 구분자는 값 안의 문자에 따라 경계가 흔들려 서로 다른 이동을 같은 ID로 합칠 수 있다 |
| 표기 정규화 | EVM `txHash`는 16진수, Solana 서명은 base58 | **EVM hash만** 소문자로 맞춘다(대소문자에 정보가 없다). base58처럼 대소문자가 값의 일부인 형식은 건드리지 않는다 |
| 원문 보존 | — | 실제 hash는 기존 `bcm_tx_l.tx_hash`에 그대로 남긴다. 파생 ID로는 벤더 콘솔·체인 탐색기를 검색할 수 없으므로 운영 조사는 hash로 하고, 그 조회를 위해 V26 index를 추가했다 |
| 출금의 온체인 사건 | 같은 이동이 전송 알림과 온체인 이동 사건 양쪽으로 온다 | 발신(`direction: Out`) 사건은 **새 거래를 만들지 않는다** — 이 사건에서 읽는 것은 **`txHash`의 블록 좌표**뿐이고, 그 hash를 가진 우리 발신 거래(`ext_tx_id`가 있는 행) 전부에 적용한다. **이동이 어느 제출의 것인지는 묻지 않는다**(2026-09-17 사용자 확정) — 귀속은 벤더가 전송 요청에 결속해 준 `txHash`가 이미 해결했다. 그 hash의 발신 거래가 아직 없으면 만들지 않고 재시도로 남긴다. 확정을 블록 깊이로 판정하므로 이 좌표가 있어야 출금도 확정된다([발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)) |
| 범위 밖 | — | RBF 계열 대응(Dfns `replacementId` 계약 미정). 발신의 `tx_hash` 조회와 제출 원장 대조는 [발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)로, 입금의 원장 쓰기·outbox와 판단 워커 조립은 [입금 판단](#입금-판단--구현)·[판단 워커 조립](#판단-워커-조립--구현)으로 이후 구현했다 |

- 세 형태(Fireblocks UUID · Dfns 출금 `xfr-` · Dfns 입금 `dfns-`)가 공존한다. 원장 동작에는 영향이 없지만 운영자가 형태로 출처를 구분할 수 있어야 하므로 접두사를 남긴다.
- 파생 ID는 원문을 드러내지 않는다 — 요약값이라 hash를 되돌릴 수 없다. 조사 동선은 항상 `tx_hash`다.

수용 항목: `index`가 실제로 항상 오는지(오지 않는 사건이 있으면 그 이동은 처리 보류가 되므로 유일성을 줄 추가 필드를 먼저 계약해야 한다),
Dfns가 대체 제출(`replacementId`)에서 hash를 어떻게 바꾸는지와 그때의 root 유지 규칙, 발신 사건과 전송 알림의 도착 순서.

### 입금 판단 — 구현

근거: [02의 입금·전이 표·이벤트 계약](02-bcm-flow.md#입금), [03 V26(논리 거래 식별자)·V27(정밀도)](03-bcm-db.md), [CLAUDE.md 3절의 확정 결정],
그리고 위 [온체인 이동 사건 관찰](#웹훅-온체인-이동-사건-관찰--구현)·[귀속](#온체인-이동의-귀속--구현)·[상태 번역](#상태-번역--구현)·[확정 판정](#확정-판정--구현).
구현은 `DfnsChainEventDecision`(bcm-webhook)이며 호출자의 트랜잭션 안에서 원장 전이와 outbox 적재를 함께 수행한다.
**`dfns`에서 조립되어 인박스 워커가 호출한다**([판단 워커 조립](#판단-워커-조립--구현)).

| 단계 | 규칙 |
|---|---|
| 해석 | 온체인 이동 사건이 아니면 이 판단의 대상이 아니다(전송 알림·지갑/정책 사건은 각자의 경로) |
| 귀속 | `NetworkChainAttribution` 결과를 그대로 따른다. 입금은 여기서, **발신은 [발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)가** 원장·outbox를 쓴다. 미지원·미등록·미귀속은 **원장을 쓰지 않고 결과로만** 돌려준다 — 무엇을 경보로 올릴지는 워커가 정한다 |
| 정밀도 | 등록 매핑에 정밀도가 없으면(V27 이전 등록 행) 금액을 지어내지 않고 `MissingDecimals`로 멈춘다. 0이나 최소 단위를 그대로 싣지 않는다 |
| 확정 | 체인 head를 읽어 깊이를 관찰 컨펌 수로 담고 `VendorStatusTranslator`가 임계와 비교한다. **head 조회 실패는 예외로 올라가 확정을 보류**하고 인박스가 재시도한다 |
| 거래 ID | hash·순번 파생(03 V26). 순번이 없으면 고유 키를 만들 수 없어 실패한다 |
| 시각 | 벤더 시각(`vndr_crt_dttm`)은 형식이 서술된 알림 `date`(사건 발생 시각)에서 만든다 — 사건의 `timestamp`는 형식·시간대 서술이 없고, 입금에는 Fireblocks의 거래 `createdAt`에 해당하는 값이 없다(우리가 낸 제출이 아니다). 두 제공자의 시간축이 다르므로 **섞어서 한 구간으로 대사하지 않는다**(03) |
| 없는 값 | 제출 키(`externalTxId`)·`subStatus`·`networkStatus`는 Dfns에 해당 개념이 없어 `null`이다 — 없는 값을 만들지 않는다 |
| 발신 주소 | [02](02-bcm-flow.md#상태-enum)는 **입금 이벤트에 발신 주소가 항상 실린다**고 확정했고 DAW-CORE의 입금 판별 게이트가 그 값을 쓴다. 그런데 명세상 `Native`·`Spl` 변형의 `from`은 선택이다 — 없으면 이벤트를 만들지 않고 `MissingSender`로 멈춘다. 공개 계약을 비운 채 내보내거나 빈 문자열을 지어내지 않는다 |
| 금액 | 등록 정밀도로 사람 단위 금액을 만든다(`AssetDecimals.amountOf`) — 제공자와 무관하게 02 이벤트 금액의 단위는 하나다 |
| 발행 | 전이 표가 발행할 상태만 outbox에 적재하고(`EventType.DEPOSIT`) 알림 ID를 `traceId`로 남긴다. 발행할 상태가 없으면 이벤트도 없다 |
| 범위 밖 | **미판단 결과(미등록·미지원 자산 등)의 경보**·발신(제출 원장 대조)·`WebhookTransactionParser`의 Dfns 구현·이력 복구. 인박스 P/S/F 처리와 워커 조립은 [판단 워커 조립](#판단-워커-조립--구현)으로 이후 구현했고, 미귀속·poison 경보 포트는 기존 Webhook 경보 어댑터에 이미 연결돼 있다 |

수용 항목: **실제 입금 사건에 `from`이 채워져 오는지**(오지 않는 변형이 있으면 02의 공개 이벤트 계약을 바꿀지 그 입금을 보류할지 결정해야 한다), 입금 사건이 `Included`→`Confirmed`로 두 번 올 때의 전이(같은 거래 ID로 합류하는지), 미등록 자산 입금의 운영 경보 수준,
정밀도 없는 기존 매핑이 실제로 남아 있는지, 체인 head 조회 주기·캐시와 재시도 상한.

### 판단 워커 조립 — 구현

근거: [설계12의 조건부 조립](12-provider-compatibility.md#조건부-조립-구현-순서)과 위 [입금 판단](#입금-판단--구현).
구현은 `WebhookDecisionWork` 경계와 `DfnsWebhookDecisionTransaction`·`DfnsWebhookDecisionConfig`(bcm-webhook)다.

| 항목 | 규칙 |
|---|---|
| 경계 | 인박스 한 건을 판단하는 트랜잭션을 `WebhookDecisionWork`로 추상화하고 제공자마다 **하나만** 조립한다. 워커(`WebhookDecisionProcessor`)·경보 처리는 이 경계 뒤의 벤더 어휘를 모른다 |
| 조건부 | 기존 `WebhookDecisionTransaction`은 `fireblocks`·`local`, `DfnsWebhookDecisionTransaction`은 `dfns`에서만 만든다. Fireblocks 판단 경로의 동작은 바뀌지 않았다 |
| 확정 임계 | `ConfiguredFinalityPolicy`를 제공자 중립 위치로 옮겼다 — `bcm.finality-confirmations.<network>`는 두 제공자가 **같은 설정**을 쓴다(Fireblocks는 벤더 컨펌 수, Dfns는 블록 깊이와 비교) |
| 조립 위치 | 상태 번역기·체인 head는 **판단 경로에서만** 필요하므로 Webhook 앱에서만 만든다. 사건 해석기(`NetworkChainEventParser`)는 envelope 해석과 같이 모든 앱에서 만들 수 있다 |
| 인박스 상태 | 입금 판단 완료·미귀속(경보 후)은 처리 완료, payload 결함은 재시도/격리, **제출 원장에 없는 전송과 한 제출 키에 두 전송이 붙은 충돌은 상한을 기다리지 않고 즉시 격리**([전송 알림 판단](#전송-알림-판단--구현)의 미확인·충돌), 확정 임계 설정 오류는 `P`로 남겨 복구 뒤 재처리한다 — Fireblocks 경로와 같은 규율이다. **`vndr_cmpl_yn`은 남기지 않는다**(항상 `N`) — 03이 정의한 이 표식은 Fireblocks `data.status=COMPLETED`이며 원본 보관(`bcm_raw_tx_l`)의 `vndr_tx_id` 부분 index를 위한 값이다. Dfns 온체인 사건은 인박스 `vndr_tx_id`가 null이라 그 index의 대상이 아니고 Dfns 원본 보관 경로도 없다 — 의미 없는 표식을 남기지 않으며 보관 계약은 후속이다 |
| 판단 범위 | 전송 알림은 [전송 알림 판단](#전송-알림-판단--구현), 발신 이동 사건은 [발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)가 맡는다. 원장을 쓰지 않는 것은 **미지원/미등록 자산·정밀도 없음·발신 주소 없음**(처리 완료)과 그 hash의 발신 거래가 아직 없는 사건(**재시도**)이다 |
| 범위 밖 | 미등록 자산·미지원 종류·미확인 전송·발신 거래 없음의 경보 포트, `WebhookTransactionParser`의 Dfns 구현, 이력 복구, 기동 차단 해제. 출금 유스케이스·전송 알림 판단·발신 이동 대조는 이후 구현했다 |

수용 항목: 미판단 계열(미지원/미등록 자산·정밀도 없음·발신 주소 없음)을 처리 완료로 남기는 동안 잃는 정보의 운영 영향(이력 복구로 회수 가능한지), 그 경보 수준, Dfns 원본 보관(`bcm_raw_tx_l`) 경로와 그 색인 키.

### 전송 알림 판단 — 구현

근거: [02의 웹훅 계열 분류](02-bcm-flow.md#웹훅-계열-분류--제출-원장이-기준이다)와 위 [웹훅 전송 사건 관찰](#웹훅-전송-사건-관찰--구현)·[상태 번역](#상태-번역--구현).
구현은 도메인 순수 규칙 `NetworkTransferJudgement`와 `DfnsTransferEventDecision`(bcm-webhook)이며 판단 워커가 **온체인 이동 판단보다 먼저** 부른다.

우리가 낸 발신 거래의 `bcm_tx_l` 행을 만드는 경로다 — 이게 있어야 발신 온체인 이동 사건을 붙일 대상이 생긴다([발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)의 선행 조건).

| 항목 | 규칙 | 근거 |
|---|---|---|
| 계열 분류 | **제출 원장으로만** 가른다. 알림 종류·금액·주소 추정으로 가르지 않는다 | 02 "웹훅 계열 분류 — 제출 원장이 기준이다". 벤더 ID만으로 계정·업무 계열을 지어내면 남의 자금이 우리 원장에 들어온다 |
| 원장 조회 | ① 벤더 전송 ID(`vndr_tx_id`) → ② 제출 키(`ext_tx_id`) 순서로 본다 | ②는 02의 "웹훅이 벤더 응답보다 먼저 올 수 있다" 경로다. 응답을 못 받아 `vndr_tx_id`가 비어 있어도 알림이 돌려준 `externalId`로 같은 행에 닿는다 |
| 연결 | ②에서 찾았고 `vndr_tx_id`가 비어 있으면 이 알림이 채운다 | 02 "웹훅이 원장의 빈 `vndr_tx_id`를 채워 같은 결과에 도달한다" |
| 충돌 | ②에서 찾은 행에 **다른** 전송 ID가 이미 있으면 원장을 건드리지 않고 충돌로 올리며, **워커가 상한을 기다리지 않고 즉시 격리한다** | 한 제출 키에 전송이 둘 붙은 이중 제출 신호다. 재시도가 결과를 바꾸지 못하므로 처리 완료로 소거하면 신호가 사라진다(03 `sbmt_stcd` 전이 표) |
| 미확인 | 원장에 없으면 원장·이벤트를 만들지 않고 **격리한다** | 우리 지갑에서 **우리가 내지 않은 전송**이 나갔다는 뜻이라 그 자체로 이상 신호다. 처리 완료로 소거하면 원문이 인박스에서 사라져 같은 트랜잭션의 이동을 나중에 판단할 근거도 없어진다 |
| 업무 값의 출처 | 계정·네트워크·심볼·금액·목적지는 **원장에 적힌 우리 요청**에서 읽는다. 알림이 알려준 값으로 업무 귀속을 바꾸지 않는다 | 알림은 벤더가 보낸 관찰이고 업무 귀속의 근거는 우리가 승인·기록한 요청이다 |
| **확정** | **여기서 내지 않는다.** 관찰 컨펌 수를 `0`으로 두어 번역기가 확정을 내지 않게 한다 | 전송 알림에는 `blockNumber`가 없어 블록 깊이를 계산할 수 없다(CLAUDE.md 3절). 벤더의 `Confirmed` 표기를 확정 근거로 쓰지 않는다 — 출금의 확정은 같은 거래의 온체인 이동 사건에서 난다([발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)) |
| 이벤트 | 원장의 거래 구분이 고객 이벤트를 갖는 계열(출금·내부이체)일 때만 outbox에 적재한다. Sweep·밴드S는 원장만 잇는다 | `SubmissionTransactionType.customerEventType()` — 기존 계약 그대로다 |
| 범위 밖 | 미확인·충돌의 **경보 포트**, RBF(`replacementId`) 계열, 이력 복구. 발신 온체인 이동 대조와 그에 따른 확정은 [발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)로 구현했다 | |

수용 항목: 전송 알림과 온체인 이동 사건의 **도착 순서**(어느 쪽이 먼저 와도 같은 결과에 도달해야 한다),
실제 Baseline이 전송 알림에 `externalId`를 항상 실어 주는지(②의 회수 경로가 거기 달려 있다),
같은 전송의 알림이 여러 번 올 때의 `status` 진행, 미확인·충돌의 운영 경보 수준.

### 발신 확정의 블록 좌표 — 구현

근거: 위 [논리 거래 식별자](#논리-거래-식별자--구현)의 "출금의 온체인 사건" 행과 [03의 V26](03-bcm-db.md#v26-dfns-논리-거래-식별자와-온체인-hash-조회--물리-저장-계약).
구현은 도메인 순수 규칙 `NetworkChainOutgoingCoordinate`와 `DfnsChainEventDecision`의 발신 분기다.

**출금의 확정이 여기서 난다.** 전송 알림에는 `blockNumber`가 없어 깊이를 계산할 수 없고([전송 알림 판단](#전송-알림-판단--구현)),
블록 좌표는 온체인 이동 사건에만 있다.

**결정(2026-09-17 사용자 확정): 이동을 제출에 귀속시키지 않는다.** 벤더는 이동과 제출을 잇는 키를 주지 않는다
(`WalletHistoryEvent`에 전송 요청 ID가 없고 `TransferRequest`에 이동 순번이 없다). 그래서 "이 이동이 어느 제출의 것인가"는
**증명할 수 없는 물음**이고, 값이 닮았는지로 대신 판정하면 원장 밖 이동이 우리 제출의 확정을 만들어 낸다
(값이 같은 원장 밖 요청이 같은 트랜잭션에 섞이면 제출 원장만 보는 배제 조회로는 막지 못한다).

확정에 필요한 것은 그 물음이 아니다. 둘을 나눈다.

| 물음 | 성질 | 답의 출처 |
|---|---|---|
| 이 **이동**이 제출 A의 것인가 | **증명 불가** — 상관관계 키가 없다 | 묻지 않는다 |
| 제출 A의 온체인 거래는 무엇인가 | 벤더가 **전송 요청에 결속**해 알려준다 | `TransferRequest.txHash`([전송 알림 판단](#전송-알림-판단--구현)이 `bcm_tx_l`에 적는다) |
| `txHash`는 몇 번 블록인가 | **블록의 사실** — 어느 이동이 실어 왔든 답이 같다 | 온체인 이동 사건의 `blockNumber` |

| 항목 | 규칙 | 근거 |
|---|---|---|
| 거래 생성 | **새 거래를 만들지 않는다.** 전송 알림이 이미 만든 거래에 좌표를 적용한다 | 만들면 같은 자금이 논리 거래 둘이 된다. 발신 사건은 그 거래에 **블록 좌표를 주는 관찰**일 뿐이다 |
| 후보 조회 | `(ntwk_cd, tx_hash)`로 찾는다(V26 index). 조회는 거래 피처의 `TxStateService`를 통한다 | `tx_hash`만으로는 네트워크가 다른 같은 hash가 섞인다. 피처 간 접근은 Service로 한다(`docs/standards/architecture.md`) |
| 적용 대상 | 그 hash를 가진 **우리 발신 거래 전부**(제출 키 `ext_tx_id`가 있는 행). 여럿이어도 **모두 같은 블록**이므로 모두 적용한다 | 하나를 고르는 문제가 아니다 — 벤더가 여러 전송을 한 트랜잭션으로 냈어도 블록 좌표는 하나다. 그래서 "후보 여럿"이라는 모호함 자체가 없다 |
| 입금 행 | 같은 hash의 입금 행(`ext_tx_id`가 없는 행)은 **건드리지 않는다** | 입금은 제 사건에서 좌표를 받는다. 발신 사건이 남의 행 상태를 함께 옮기면 원인과 결과가 어긋난다 |
| 제출 조회 | 그 거래에 적힌 **제출 키로 직접** 찾는다. 값 대조로 고르지 않는다. 없으면 원장 결함이므로 감추지 않고 올린다 | 귀속은 `txHash` 결속이 이미 해결했다. 제출 원장은 이벤트의 업무 값(금액·목적지·거래 구분)을 얻으려고 읽는다 |
| 직렬화 경계 | 후보 조회 **전에** `(network, tx_hash)` advisory lock을 잡는다. **같은 hash의 거래 행을 만드는 모든 경로가 같은 경계에 참여한다** — 전송 알림 판단과 **입금 판단**(입금도 같은 hash로 행을 만든다). 한 경로라도 빠지면 팬텀 삽입이 남는다 | `(ntwk_cd, tx_hash)`에는 유일 제약이 없어 조회와 전이 사이에 같은 hash 행이 **새로 삽입**될 수 있다 — 기존 행의 `FOR UPDATE`로는 막히지 않는다 |
| 거래 없음 | 거래를 만들지 않고 **재처리 가능한 상태로 남긴다**(인박스 재시도) | 전송 알림이 늦게 올 수 있다(도착 순서는 수용 항목). 여기서 만들면 거래가 둘이 되고, **처리 완료로 닫으면 뒤늦은 알림이 거래를 만들어도 이 사건을 다시 실행할 트리거가 없어 그 출금은 영영 확정되지 않는다**. 우리가 내지 않은 발신이면 상한까지 해소되지 않아 격리된다 |
| 확정 | 사건의 `blockNumber`와 체인 head의 **블록 깊이**로 낸다. head를 못 읽으면 예외가 올라가 보류한다 | CLAUDE.md 3절 — 벤더의 `Confirmed` 표기를 확정 근거로 쓰지 않는다 |
| 업무 값 | 계정·네트워크·심볼·금액·목적지는 **제출 원장**에서 읽는다. 관찰의 최소 단위를 다시 환산하지 않는다 | 알림·사건은 벤더가 보낸 관찰이고 업무 귀속의 근거는 우리가 승인·기록한 요청이다 |
| 인박스 | 적용했으면 처리 완료. **그 hash의 발신 거래가 아직 없으면 재시도**로 남기고 상한에 닿으면 격리된다 | 도착 순서 때문일 수 있어 재시도가 실제로 결과를 바꾼다 — 조용히 처리 완료로 닫지 않는다 |
| 범위 밖 | 거래 없음의 **경보 포트**, RBF(`replacementId`) 계열, `FAILED` 재시도의 새 제출 키 발급 | |

원장 밖 전송 알림은 처리 완료로 소거하지 않고 **격리해 원문을 남긴다**([전송 알림 판단](#전송-알림-판단--구현)의 "미확인").
그 자체로 이상 신호이고, 소거하면 원문이 인박스에서 사라진다. 다만 이 격리는 **확정의 방어 수단이 아니다** — 방어는 위 표의 `txHash` 결속이 한다.

**기동 차단 해제 선행 조건(2026-09-18 추가) — 격리된 사건의 재처리 경로**: 아직 붙일 거래가 없는 발신 사건과 결속 전 수신 사건은 **재시도**로 남기는데,
인박스 상한(기본 3회·기준 30초 → 실제 창 약 90초)을 넘기면 `F`로 격리되고 **집기 쿼리는 `P`만 읽는다**.
그 뒤에 전송 알림이 와서 거래를 만들어도 **격리된 사건은 자동으로 다시 실행되지 않는다** — 그 출금의 블록 좌표와 `FINALIZED`가 영구 누락될 수 있다.
지금은 기동 차단 때문에 운영에 노출되지 않지만, **차단을 해제하려면 격리된 사건을 다시 처리하는 경로(이력 복구 또는 운영 재처리)를 먼저 계약해야 한다.**
창을 늘리는 것만으로는 닫히지 않는다 — 창은 유한하고 알림이 그보다 늦을 수 있다.

**기동 차단 해제 선행 조건(2026-09-17)**: 확정은 이제 벤더가 우리 `TransferRequest`에 결속해 준 `txHash`를 신뢰하는 데 서 있다.
그래서 **`TransferRequest.txHash`가 그 전송이 실제로 포함된 온체인 트랜잭션의 hash라는 것**을 Baseline에서 확인하기 전에는 발신 확정을 운영에 열지 않는다
(예: 대체 제출·batch 표기로 다른 값이 오는 경우가 없는지). 이력 조회(`GET /wallets/{walletId}/history`)가 같은 `txHash`의 이동 목록을 주므로 운영 연결 후 대조로 확인한다.

수용 항목: 전송 알림과 온체인 이동 사건의 **도착 순서와 그 간격**. 재시도는 [03 V29](03-bcm-db.md#v29-인박스-재시도-대기--물리-저장-계약)의 backoff를 따른다 —
기본값(상한 3회·기준 30초)에서 실제 대기 창은 `30 + 60 = 약 90초`다(상한에 닿는 시도는 그 자리에서 격리되므로 마지막 대기는 쓰이지 않는다).
그 창 안에 전송 알림이 오면 적용되고, 넘으면 격리돼 운영이 본다. 실제 간격이 그 창보다 길다는 것이 확인되면
`bcm.webhook-worker.retry-base-seconds`·상한을 조정하거나 재처리 경로(이력 복구)를 붙인다.
Dfns가 대체 제출에서 hash를 어떻게 바꾸는지와 그때 기존 거래의 유지 규칙, 거래 없음의 운영 경보 수준.

### 출금 제출 계약 — 확정

근거: [02의 출금·멱등·소유권·벤더 응답별 처리](02-bcm-flow.md#출금), [03의 제출 원장](03-bcm-db.md),
채택 명세 1.1018.3 `POST/GET /wallets/{walletId}/transfers`와 공식 [Idempotency](https://docs.dfns.co/api-reference/idempotency)
(`.md` SHA-256 `6de82575a0cb361689df4221ad6f6e8d195ed3927d5fbb5e23c79e7fe0817507`), 그리고 위 [전송 제출·조회 계약](#전송-제출조회-계약--구현).
**이 절은 계약이고, 구현은 아래 [출금 제출 유스케이스 — 구현](#출금-제출-유스케이스--구현)이다.**

02의 출금 계약(선기록·소유권·벤더 호출은 트랜잭션 밖·`FAILED`는 확실할 때만)은 제공자와 무관하게 그대로 쓴다. **회수 절차만 다르다.**

| 항목 | 명세로 확인한 사실 | Fireblocks(현행) | Dfns |
|---|---|---|---|
| 응답 유실·소유권 만료 회수 | `GET /wallets/{walletId}/transfers`의 query는 **`limit`·`paginationToken`뿐이다** — `externalId` 필터가 없다 | `GET /v1/transactions/external_tx_id/{externalTxId}` 단건 조회로 확인 | **같은 본문으로 다시 제출한다.** 공식 문서가 "같은 url·같은 본문(같은 `externalId`)을 재제출하면 처음 만들어진 엔티티를 `200`으로 돌려준다"고 규정한다 — 조회가 아니라 **멱등 재제출이 회수 수단**이다 |
| 회수의 안전성 | 종결(`Confirmed`/`Failed`/`Rejected`) 뒤에도 `externalId`는 그 엔티티에 영구 결속되고 재제출은 기존 엔티티를 `200`으로 돌려준다(새로 만들지 않는다). **명확한 보장은 여기까지다** — 앞 제출이 진행 중일 때의 응답은 서술이 없어 아래 수용 항목이다 | — | 그래서 02의 "조회를 건너뛰면 그게 곧 이중 출금"이 Dfns에서는 **같은 본문 재제출로 대체**된다. 본문이 같아야 한다는 조건이 안전장치다 |
| 같은 키 다른 내용 | 같은 `externalId`에 다른 본문·다른 지갑이면 `409`(`error.details.duplicate`) | `400` 전부를 조회로 확인 | 표식 있는 `409`는 **최초 제출에서는** 요청 자체가 거절된 것이 확실하므로 02 표의 `409`·`422` 계열로 `FAILED`다. **회수 재제출에서는 그렇게 읽지 않는다** — 앞 제출이 진행 중일 때의 응답이 아래 수용 항목이라 `409`가 진행 중 전송을 뜻할 수 있고, 종결로 적으면 나간 전송에 확정 거절을 돌려주게 된다(구현 절 참조). 표식 없는 `409`는 원인을 단정하지 않고 02의 "그 밖의 `4xx`"대로 `REQUESTED`로 둔다 |
| 재시도 | 실패한 전송의 재시도는 **새 `externalId`**가 필요하다(원래 키는 재사용 불가) | 같은 키로 재제출 | 같은 키로 재제출하면 기존 실패 엔티티를 돌려받을 뿐이다 — 새 제출을 만들려면 BCM이 **새 제출 키**를 발급해야 하며 그 발급 규칙은 출금 유스케이스에서 정한다 |
| 제출 키 길이 | `externalId`는 **1~50자**다 | `bcm_sbmt_l.ext_tx_id`는 03에서 **VARCHAR(128)** — 벤더 한계보다 훨씬 넓다 | 50자를 넘는 제출 키는 **자르지 않고 거절**한다. 원장이 받아들이는 길이와 벤더가 받는 길이가 다르므로 **제출 경로 입구(유스케이스)에서 먼저 거절**하고 원장에 `REQUESTED`를 만들지 않는다 — 원장에만 남고 영원히 제출되지 않는 행을 만들지 않기 위해서다. 공개 `externalTxId`가 50자를 넘는 경우가 실제로 있는지는 **DAW-CORE와 확인이 필요한 항목이다** |
| 자산·목적지 | 전송 본문은 등록 자산 키에서 되돌린 `kind`·locator와 `to`·`amount`(최소 단위)뿐이다 | vault·assetId 중심 | 최소 단위 금액은 등록 정밀도(03 V27)로 환산해 만든다. 수수료·대납·Travel Rule·memo는 각각 별도 계약 전이라 보내지 않는다 |
| 논리 거래 ID | — | 벤더 tx id | 벤더가 준 `xfr-…`를 그대로 쓴다(03 V26). 입금의 파생 ID와 형태가 다르다 |
| 확정 | 전송 응답에는 `blockNumber`가 없다 | `numOfConfirmations` 비교 | 출금의 확정도 **온체인 이동 사건**(`direction: Out`)의 블록 깊이로 판정한다. 발신 사건은 새 거래를 만들지 않고 `txHash`의 블록 좌표를 그 hash의 우리 발신 거래에 적용한다(03 V26) |

| 본문 재구성 | 회수의 안전성은 "같은 본문"에 달려 있는데 03의 제출 원장은 Dfns JSON 원문이 아니라 canonical 필드와 `req_hash`를 저장한다 | 본문은 저장된 canonical 값(등록 자산 키·목적지·최소 단위 금액·제출 키)에서 **결정적으로 다시 만든다** — 필드 집합과 순서가 고정이므로 같은 입력은 같은 바이트가 된다([전송 제출·조회 계약](#전송-제출조회-계약--구현)의 본문 규칙). 재구성 전에 `req_hash`로 같은 요청임을 확인하고, 어긋나면 재제출하지 않고 `409`와 같은 계열로 다룬다. 원문 JSON을 저장해 두었다가 그대로 보내는 방식은 쓰지 않는다 |
| 미결 제출 점검 | 02의 백그라운드 점검은 **조회만** 하고 재제출하지 않는다. Dfns에는 그 조회가 없다 | Dfns에서는 점검이 **회수하지 않는다** — 마지막 확인 시각·횟수만 갱신해 백오프·경보로 넘기고, 회수는 전체 요청 문맥과 새 소유권을 가진 **원 제출 경로**(API 재시도)만 한다. 멱등 재제출이라 점검기가 보내도 이중 지급은 아니지만, 재제출은 곧 자금 이동 시도이므로 백그라운드에 기본으로 열지 않는다. 점검기에 멱등 재제출을 허용할지는 **유스케이스에서 결정**한다(아래 수용 항목) |

수용 항목: 실제 Baseline에서 같은 본문 재제출이 문서대로 `200`을 주는지(특히 앞 제출이 진행 중일 때),
미결 제출 점검에 멱등 재제출을 허용할지(허용하면 오래된 `REQUESTED`가 자동 회수되고, 막으면 운영 개입이 필요하다),
`409` 본문의 `details.duplicate` 형태, 공개 `externalTxId`가 실제로 50자를 넘는 경우가 있는지(DAW-CORE 확인),
새 제출 키 발급 규칙(원 키와의 상관관계 보존 방식), 대납·수수료 정책이 필요한 네트워크.

### 출금 제출 유스케이스 — 구현

위 계약을 `DfnsTransferSubmissionService`(bcm-api)로 구현했다. 02의 **선기록·소유권·벤더 호출은 트랜잭션 밖**은 그대로이고 회수 수단이 다르다. **`FAILED` 판정 기준도 두 곳 다르다** — 종결 `FAILED`의 재시도를 열지 않고, 회수 재제출의 표식 있는 `409`를 확정 거절로 읽지 않는다(아래 표).

공개 `POST /transactions`의 제출 경계를 `TransactionSubmissionWork`로 추상화하고 **제공자마다 하나만** 조립한다 —
Fireblocks·로컬은 기존 `TransactionSubmissionService`(`@ConditionalOnFireblocksProtocol`), Dfns는 `DfnsSubmissionConfig`가 만드는 구현이다.
컨트롤러는 어느 제공자인지 모른다. 밴드S 실행(`BandSCommandService`)은 관리 제출을 구체 타입으로 쓰므로 같은 Fireblocks 조건으로 묶었다 —
`VendorTransactionPort` 구현이 Fireblocks뿐이라 이미 `dfns`에서 만들어질 수 없었고, 그 사실을 조건으로 드러낸 것이다.

| 단계 | 규칙 | 근거 |
|---|---|---|
| 제출 키 검사 | **원장에 적기 전에** 50자 초과를 거절한다(`VALIDATION_FAILED`). 자르지 않는다 | 원장 `ext_tx_id`는 VARCHAR(128), 벤더 `externalId`는 1~50자다. 그 사이 길이는 원장에는 들어가지만 벤더에 영영 나갈 수 없어, 제출되지 않는 `REQUESTED` 행이 남는다 |
| 본문 준비 | 목적지는 **주소만** 받는다(계정 간 내부이체는 [내부이체](#내부이체--확정)에서 우리가 주소로 해소해 넣는다 — **구현 완료**). 금액은 등록 정밀도(03 V27)로 최소 단위 정수로 환산하고, 정밀도가 없으면 거절한다 | 화이트리스트 지갑은 별도 계약 전이다. 단위가 뒤섞이면 조용한 금액 사고다 |
| 금액 환산 | `AssetDecimals.baseUnitsOf` — 자릿수가 정밀도보다 많으면 **반올림하지 않고 거절**한다 | 반올림은 곧 사용자가 지시하지 않은 금액을 보내는 것이고, 버리든 올리든 원장과 실제 이동이 어긋난다 |
| 선기록·소유권 | 제출 원장에 `REQUESTED`를 먼저 넣고 소유권(토큰+만료)을 함께 잡는다. 못 잡은 후발 요청은 기다리지 않고 `503` + `Retry-After` | 02 그대로. 기다리면 벤더 지연이 API 전체를 막는다 |
| 같은 키·다른 내용 | `req_hash`(hash 버전이 다르면 canonical 7값)로 대조해 어긋나면 `409` | 02 그대로 |
| 원장 우선 | **원장을 먼저 읽는다** — 이미 결말이 난 건은 현재 자산 매핑·지갑을 읽지 않고 답한다 | 매핑이 해제됐다고 원래 `txId`를 못 돌려주면 안 된다 |
| 회수 | `REQUESTED`이고 소유권을 뺏었으면 **같은 본문을 다시 제출**한다. 조회하지 않는다 | 벤더에 `externalId` 필터가 없다. 공식 Idempotency 계약이 **종결 뒤** 재제출에 기존 엔티티 `200`을 보장하므로 새 전송이 만들어지지 않는다. **진행 중일 때의 응답은 수용 항목**이라 `200`을 전제하지 않으며, 그래서 아래 `409` 행이 회수에서는 종결로 굳히지 않는다 |
| 본문 재구성 | **제출 시점에 원장에 적은 벤더 canonical 값(03 V28·V30: 지갑 ID·자산 키·최소 단위 금액·정밀도·목적지 주소)으로만** 만든다. 회수 시점의 매핑·정밀도를 다시 읽지 않는다. 저장값이 없는 행은 **재구성하지 않고 `422`로 거절**한다 | 회수 시점에 매핑이 교체되면 같은 키·같은 `req_hash`로 다른 본문이 나가 이중 전송이 된다. 추측한 본문을 보내는 것이 곧 그 사고다 |
| 응답 대조 | 제출·회수로 받은 관찰이 내 요청과 같은지 **돌려받은 값으로 다시 본다**(지갑·자산·목적지·금액). `externalId`는 **제출 응답에서는 어댑터가 이미 결속을 강제**하므로(없거나 다르면 어댑터가 실패시킨다 — 위 전송 어댑터의 응답 정규화) 이 경로에 `null`이 오지 않는다. 대조 규칙 자체는 `null`을 불일치로 보지 않는데, 그건 `externalId`가 선택인 **조회 관찰**에도 같은 규칙을 쓰기 위해서다 | 벤더의 본문 대조를 믿되 원장에 남길 값은 관찰에서 읽는다. 결속 강제는 한 곳(어댑터)에 두고 유스케이스는 그 결과를 다시 확인만 한다 |
| `409`(표식 있음) | **최초 제출에서만** `FAILED`로 굳힌다. **회수 재제출에서는 `REQUESTED`를 유지**하고 재시도 가능한 실패로 올린다 | 최초 제출의 표식은 우리가 보낸 적 없는 키를 벤더가 이미 갖고 있다는 뜻이라 확정 거절이 맞다(02의 `409`·`422` 계열). 회수에서는 **앞 제출이 진행 중일 때의 응답이 아직 수용 항목**이라 `409`가 진행 중 전송을 뜻할 가능성을 배제할 수 없다 — 종결로 적으면 실제로 나간 전송에 확정 거절을 돌려주고 지금은 되살릴 경로도 없다. 표식 없는 `409`는 어댑터가 일반 벤더 오류로 전파한다 |
| 그 밖의 벤더 오류·무응답 | 예외가 그대로 올라가 `REQUESTED`가 남는다 | 02 그대로 — 나갔는지 모르므로 지우지 않는다 |

**`FAILED` 재시도는 열지 않는다(이번 슬라이스의 결정).** 02의 멱등 표는 `FAILED`에서 "소유권을 잡고 다시 제출한다"지만 Dfns에서는 성립하지 않는다 —
같은 키 재제출은 **기존 실패 엔티티를 돌려줄 뿐**이라 새 전송이 만들어지지 않는다. 그렇다고 새 키를 발급하면 **이중 지급 위험**이 있다:
벤더 `Failed`는 시스템 실패와 **온체인 실행 실패**를 함께 뜻해(`onChainSubmitted = null`) 이미 체인에 나갔을 수 있다.
그래서 새 제출 키 발급은 **체인 종결이 확인된 경우로 제한**한다. 현재 구현은 확인 수단이 없어 `UNPROCESSABLE_ENTITY`로 거절한다 —
막아 두면 운영 개입이 필요하지만, 열어 두면 같은 자금이 두 번 나갈 수 있다. 확인 수단의 근거는 아래 절이다.

#### `Failed`의 뜻과 재시도 안전성 — 벤더 문의 결과 (2026-09-18)

출처는 **Dfns 문서 어시스턴트 답변**이다. 공개 명세·공식 가이드와 **구분해서** 읽는다 — 실제 Baseline 실측이 아니며 문의 시점의 문서 해석이다.
그럼에도 남기는 이유는 이 답이 **공식 가이드 한 곳의 오류를 벤더 쪽이 인정한 기록**이기 때문이다.

| 질문 | 답 |
|---|---|
| 브로드캐스트 전 실패는 어떤 상태인가 | **`Failed`다(`Rejected`가 아니다).** `Failed`는 온체인 실행 실패와 **브로드캐스트 전 실패를 모두** 덮는다. [Abort Transfer](https://docs.dfns.co/api-reference/wallets/abort-transfer)가 그런 `Failed`를 만드는 문서화된 경로다 |
| 공식 Idempotency 가이드의 "`Failed` = on-chain finality"는 | **보편적으로 참이 아니다** — abort·브로드캐스트 전 실패를 설명하지 못한다고 벤더 쪽이 인정했다 |
| `Failed`를 본 뒤 그 트랜잭션이 나중에 채굴될 수 있나 | **막을 보장이 없다.** 그리고 **두 하위 경우를 구분하는 필드가 `TransferRequest`에 없다** — 읽어서 가릴 수 없고 절차로만 해소된다 |
| 재시도 전에 안전하게 만드는 수단 | EVM은 [Cancel Transfer](https://docs.dfns.co/api-reference/wallets/cancel-transfer)다. 다만 **절대 보장은 아니다** — 이미 브로드캐스트된 건은 "success is not guaranteed … depends on whether the original transaction has already been mined". **EVM 전용**이며 비EVM 대응물은 문서에 없다 |

이 답으로 세 가지가 정해진다.

- **조회로 판정하는 길은 닫혔다.** `dateBroadcasted`·`txHash`의 부재를 "브로드캐스트 안 됨"으로 읽어도 된다는 보증이 없고, 구분 필드도 없다.
  값의 부재를 근거로 재시도를 열면 **보증 없는 추론 위에 이중 지급 방어를 세우는 것**이다.
- **취소 단독으로도 닫히지 않는다.** 이미 채굴된 건에는 실패할 수 있다.
- **그러나 취소의 결과를 체인에서 확인하면 닫힌다.** 그 nonce의 결말이 확정되면 세 경우가 모두 안전하다 —
  ① 브로드캐스트 전 실패면 대체가 예약 nonce를 소각해 원 전송이 영영 채굴될 수 없고,
  ② mempool에 살아 있었으면 대체가 원본을 밀어내며,
  ③ 이미 채굴됐으면 그 `Failed`는 **revert 종결**이라 자금이 움직이지 않았다.
  어느 쪽이 이겼는지는 [발신 확정의 블록 좌표](#발신-확정의-블록-좌표--구현)와 같은 블록 깊이 판정으로 읽는다 — 추론이 아니라 체인에서 읽은 사실이다.

그래서 재시도 계약의 형태는 **취소 → 그 nonce의 결말 확정 대기 → 새 제출 키 발급**이며, 확정 전에는 보류한다.

**2차 문의 결과(2026-09-18, 같은 출처)**

| 항목 | 답 | 우리 쪽 해석 |
|---|---|---|
| 브로드캐스트 전 실패의 nonce 소각 | 취소 문서의 3단계가 "Consume the nonce that was reserved but not used (if the transfer failed off-chain)"이고, **"success is not guaranteed" 단서는 브로드캐스트된 경우에만 붙는다** | 명시적 보장 문구가 아니라 **단서가 없다는 서술**이다. 설계는 이 서술에 기대되, 실제 확인은 Baseline 수용 항목으로 남긴다 |
| 이미 브로드캐스트된 전송에 취소를 부르면 | **거절하지 않는다.** 한 엔드포인트가 두 경우를 모두 받아 같은 nonce로 대체를 시도한다 | 취소 호출의 성공/실패로 두 경우를 가릴 수 없다. **결말은 체인에서 읽어야 한다**는 설계가 그대로 유지된다 |
| `replacementId` | 트랜잭션/전송 객체에 있으며 "The id of the replacement transaction (cancel or speed-up)"로 문서화 | 대체를 원 전송에서 따라갈 수 있다 |
| 가스 부담 주체·대납 적용 | **문서에 없다** | 미해결로 남긴다 |
| `externalId`로 전송 조회 | **없다.** 목록은 `limit`·`paginationToken`뿐이고 별도 조회 엔드포인트도 없다. 문서화된 회수 절차는 **같은 본문 재제출**이다 | DF3.24의 결정이 벤더 쪽 확인으로 굳었다 |
| 서로 다른 전송이 한 온체인 트랜잭션으로 합쳐지는가 | **문서가 긍정도 부정도 하지 않는다.** 다만 batching은 `UserOperations`(Sign & Broadcast API)의 구성이고 **Transfer API의 `TransferRequest`에 적용된다는 문서는 없다** | 기동 차단 해제 선행 조건은 **유지**한다. 다만 우리는 Sign & Broadcast 경로를 쓰지 않으므로 위험 표면이 그만큼 좁다 |
| `details`의 nonce | 설명의 **예시**일 뿐 네트워크별 구체 스키마는 문서화되지 않았다(`object`/`any`) | 여기에 기대지 않는다. 취소가 nonce를 내부에서 다루므로 필요도 없다 |
| `wallet.transfer.failed` 웹훅 payload | `data.transferRequest`가 **Get Transfer 응답과 같은 전체 `TransferRequest`**다 | `txHash`·`dateBroadcasted`·`reason`이 알림에 실려 온다 — 판정을 위해 되조회할 필요가 없다 |

**답변자의 논리 하나는 채택하지 않는다.** 어시스턴트는 브로드캐스트 전 실패를 "never signed/broadcast in the first place"라고 설명했는데,
취소 문서의 1단계는 "Extracting the nonce from **the original transfer's signed data**"다 — **서명은 됐고 브로드캐스트만 안 된 상태**가 있다는 뜻이다.
그 경우 서명된 트랜잭션이 존재하므로 "서명이 없어서 안전하다"는 설명은 성립하지 않는다. 안전의 근거는 **nonce 소각** 하나이며, 우리 설계도 그것만 쓴다.

**3차 문의 결과(2026-09-18, 같은 출처) — 취소의 상세**

| 항목 | 답 |
|---|---|
| nonce 동일성 | 취소는 **원 전송의 서명 데이터에서 nonce를 꺼내 같은 nonce로** 대체를 만든다(문서화된 3단계). 그 대체가 확정되면 원본은 영영 채굴될 수 없는데, 이는 **체인의 nonce 의미에서 따라오는 것**이지 벤더가 따로 보장 문구를 단 것이 아니다 |
| 요청 본문 | **없다.** 경로 파라미터(`walletId`·`transferId`)뿐이라 **수수료·우선순위를 넘길 수단이 없다** |
| 가스 부담 주체·대납 | **문서에 없다** |
| 정책 승인과의 관계 | **문서에 없다** — 대체가 `Pending`/`Rejected`로 갈 수 있는지, 거절되면 예약 nonce가 어떻게 되는지 |
| 대체 추적 | 응답이 트랜잭션 형태(`status` enum이 트랜잭션 생명주기)이므로 `GET /wallets/{walletId}/transactions/{transactionId}`가 대응한다. `replacementId`는 **원 전송 쪽**에 붙으며, 새 트랜잭션이 원 전송을 역참조하는 필드는 문서화되지 않았다. `wallet.transaction.*` 웹훅으로 보고되는지도 **명시되지 않았다** |
| 취소 호출의 멱등성 | **문서에 없다** — 두 번 부르면 거절인지 대체가 둘 생기는지 |
| 이미 채굴된 건에 부르면 | **문서에 없다** — 전용 오류 코드가 규정돼 있지 않다 |
| 비EVM | 취소·speed-up 모두 **EVM 한정**이고, 비EVM에서 원본이 결제될 수 없음을 확인하는 방법은 **문서에 아무것도 없다** |

**이 답들이 설계를 흔들지는 않는다.** nonce 동일성이 체인 의미에서 따라온다는 점이 오히려 가장 강한 근거다 —
벤더의 약속이 아니라 **체인의 성질**이라 문서가 바뀌어도 변하지 않는다. 대체가 확정되면 그 nonce는 소비됐고 원본은 채굴될 수 없다.

문서에 없는 것들은 **정확성이 아니라 가용성의 문제**다. 우리 설계가 fail-closed이기 때문이다 — **대체가 확정되지 않으면 새 키를 발급하지 않는다.**
가스가 없어 취소가 실패하든, 정책이 대체를 거절하든, 결과는 "재시도를 열지 않음"이고 자금 사고로 이어지지 않는다.
그래서 이 항목들은 구현을 막는 조건이 아니라 **Baseline 수용 항목과 운영 절차**로 옮긴다.

**Baseline 수용 항목(운영 전 실측)**: 취소의 가스 출처와 지갑 잔액이 빈 경우의 동작, 정책이 걸린 지갑에서 대체가 승인 대기/거절되는지와 그때 예약 nonce의 운명,
취소를 두 번 불렀을 때의 동작, 이미 채굴된 건의 응답, 대체가 `wallet.transaction.*`로 보고되는지, 그리고 브로드캐스트 전 실패에서 nonce 소각이 실제로 일어나는지.

**미해결(구현 전 결정)**: 대체 `TransactionRequest`의 관찰 경로 — **`wallet.transaction.*`는 우리가 파싱하지 않는 계열**이라 파서·조회 어댑터를 새로 만들어야 한다.
`externalId` 50자 안에서 새 키를 만드는 규칙(공식 권장은 `-retry-N` 접미사인데 기존 키가 길면 들어가지 않는다), 벤더에 보내는 키가 `ext_tx_id`와 달라질 때의 원장 컬럼 분리.
**비EVM(Solana)의 `FAILED` 재시도는 열지 않는다** — 원본이 결제될 수 없음을 확인할 방법이 문서에 없으므로 별도 계약 전까지 `422`를 유지한다.

수용 항목: 실제 Baseline에서 앞 제출이 **진행 중일 때** 같은 본문 재제출이 `200`을 주는지(문서는 종결 뒤만 명시가 분명하다),
제출 응답이 `externalId`를 항상 되돌려주는지(대조 강도가 달라진다), 공개 `externalTxId`가 실제로 50자를 넘는 경우가 있는지(DAW-CORE 확인),
새 제출 키 발급 규칙과 원 키와의 상관관계 보존 방식, 대납·수수료·Travel Rule·memo가 필요한 네트워크.

### 내부이체 — 확정

근거: 02의 [제출 계약](02-bcm-flow.md)과 위 [출금 제출 유스케이스](#출금-제출-유스케이스--구현). **구현 완료**(목적지 해소·수신측 중복 입금 방지).

Fireblocks는 목적지를 **vault ID**로 넘겨 벤더가 주소를 고르지만(`VendorTransactionDestination.Account`),
Dfns 전송 본문은 `to`(주소)뿐이다. 그래서 **우리가 목적지 계정의 주소를 해소해 넣는다**.
온체인 여부는 두 제공자가 같다 — Fireblocks vault↔vault도 서로 다른 주소 간 온체인 전송이다(02 "내부이체는 vault 에서 vault 로 가는 온체인 전송").

| 항목 | 규칙 | 근거 |
|---|---|---|
| 적용 순서 | **기존 키 판정이 먼저다** — 아래 검사는 원장에 그 키의 행이 **없을 때만** 수행한다([02 신규 키 선행 검사](02-bcm-flow.md#신규-키-선행-검사--제공자-공통-2026-09-18-사용자-확정)). 기존 `REQUESTED`의 회수는 **당시 저장한 목적지**로 진행하며 현재 발급 상태를 다시 보지 않는다 |
| 목적지 해소 | 수신 계정의 **그 `(network, symbol)`에 발급된 주소**를 원장에서 찾아 `to`에 넣는다. 벤더에 계정을 알려 주지 않는다 | Dfns 본문은 주소만 받는다. 발급 기록이 우리가 그 계정의 수신 주소라고 인정한 유일한 근거다 |
| 미발급 | **`422 UNPROCESSABLE_ENTITY`**로 거절하고 **제출 원장 행도 만들지 않는다** | 요청 형식·계정·자산은 유효하고 **목적지의 준비 상태** 때문에 지금 못 보내는 것이다. 행을 만들지 않아야 주소 발급 뒤 **같은 `externalTxId`로 정상 제출**할 수 있다. 주소 발급은 별도 API 계약이므로 제출 API가 생성 절차를 암묵적으로 이어받지 않는다 |
| 계정 없음 | `404` | 기존 계약 그대로 |
| 자산 미지원 | `400` | 기존 계약 그대로 |
| **자기 계정** | 송신 계정과 목적지 계정이 같으면 **`400 VALIDATION_FAILED`**로 거절한다. 벤더 호출·원장 생성 **전에** 막는다. **제공자 공통 정책**이다(2026-09-18 사용자 확정) | 같은 주소로 가는 온체인 전송이라 잔액은 그대로고 가스만 태운다. 시간이 지나도 해소되지 않는 **요청값 자체의 모순**이라 `422`가 아니라 `400`이다. Fireblocks 경로도 같은 정책을 구현했다 — 같은 요청이 제공자에 따라 다르게 답하면 안 된다 |
| 배포 시 유의 | 이미 동일 계정으로 `REQUESTED`인 행이 있으면 **그 건의 회수는 계속 허용**한다 | 새 요청은 막되 미결 제출의 회수까지 막으면 "나갔는지 모르는 거래"가 고립된다 |

#### 해소한 주소의 보관 — V30

`ADDRESS` 수신자는 `rcv_vl`이 곧 `to`라서 [회수](#출금-제출-유스케이스--구현)가 `rcv_vl`을 그대로 썼다.
**`ACCOUNT` 수신자는 `rcv_vl`이 accountId**라 그대로 두면 회수가 **accountId를 `to`에 넣어 보낸다** —
V28 canonical이 "제출 본문을 재구성하는 재료 한 벌"인데 본문의 `to`가 빠져 있었고, 주소 수신자만 지원하는 동안 드러나지 않았을 뿐이다.

그래서 [03의 V30](03-bcm-db.md#v30-제출-목적지-주소-보관--물리-저장-계약)으로 **해소한 목적지 주소를 따로 적는다**.

| 컬럼 | 뜻 |
|---|---|
| `rcv_vl` | **논리 목적지** — `ADDRESS`면 주소, `ACCOUNT`면 accountId. 그대로 둔다 |
| `vndr_dst_addr` | **벤더 본문에 실제로 보낸 주소.** 회수가 이 값을 쓴다 |

- 두 값이 같을 수 있다(`ADDRESS` 수신자). 그래도 따로 적는다 — 회수가 읽을 자리를 수신자 종류에 따라 바꾸지 않는다.
- Fireblocks·로컬은 vault ID를 넘기므로 `NULL`이다. **기존 Dfns 행은 `rcv_vl`로 백필**하고(전부 `ADDRESS` 수신자라 정확한 값이다) canonical 제약을 **다섯 값 all-or-none**으로 넓힌다 — 넷만 검사하면 목적지만 빠진 회수 snapshot을 허용하게 된다.
- **이벤트의 `to`도 이 값을 쓴다.** 02와 Fireblocks 경로에서 `ChainEvent.to`는 **온체인 목적지 주소**다 — `rcv_vl`을 그대로 실으면 내부이체 이벤트에 accountId가 나간다.

#### 수신측 중복 입금 방지

Dfns는 관리 계정 간 이동을 **송신 지갑의 `Out`과 수신 지갑의 `In`** 양쪽으로 알린다.
그런데 [온체인 이동의 귀속](#온체인-이동의-귀속--구현)은 **발급 주소로 들어온 모든 `In`을 입금으로** 가르므로,
그대로 두면 한 번의 내부이체에 **`INTERNAL` 이벤트와 `DEPOSIT` 이벤트가 둘 다** 나간다 —
DAW-CORE가 있지도 않은 입금을 인정하게 된다([호환 계획](../dfns-compatibility-plan.md)의 "관리 주소 간 이동 중복 입금 방지").

| 상황 | 규칙 | 근거 |
|---|---|---|
| 같은 `(ntwk_cd, tx_hash)`에 **우리 제출 거래**(`ext_tx_id`가 있는 행)가 있다 | 그 `In`은 우리가 낸 전송의 수신측이다 — **입금을 만들지 않고 처리 완료**로 남긴다 | 업무 이벤트는 제출 원장 쪽에서 이미 났다. 같은 자금을 두 계열로 발행하지 않는다 |
| 아직 없는데 **발신 주소가 우리 네트워크 지갑 주소**다 | **보류(재시도)** — 입금으로 확정하지 않는다 | 전송 알림이 늦으면 `tx_hash` 결속이 아직 없다. 여기서 입금을 만들면 되돌릴 수 없다 — 이벤트는 취소가 안 된다. 알림이 오면 위 행으로 해소되고, 상한까지 안 오면 격리돼 운영이 본다 |
| 발신 주소가 우리 것이 아니다 | **입금**으로 처리한다(현행 그대로) | 외부에서 온 진짜 입금이다 |

- **발신 주소 판정은 `bcm_ntwk_wlt_m`의 지갑 주소로 한다**(2026-09-18 사용자 확정) — 자산 발급 기록(`bcm_addr_m`)으로 하지 않는다.
  Dfns 제출의 선행 조건은 **네트워크 지갑과 자산 매핑**뿐이고 발신 계정에 그 `symbol`의 주소 행이 있을 필요가 없다
  (`DfnsTransferSubmissionService`는 `findWallet(scope)`만 본다). 다른 토큰 발급으로 지갑만 만들어진 계정에서 보내면
  발급 기록 조회는 **우리 주소를 못 알아보고 외부 입금으로 확정해 버린다**. "누구 주소냐"는 **소유권** 질문이므로 소유권 표로 답한다.
- 조회는 `(orgn_id, ntwk_cd, 주소)`다. [03 V30](03-bcm-db.md#v30-제출-목적지-주소-보관--물리-저장-계약)이 그 index를 함께 만든다.
- **현재 범위는 EVM이다** — 주소 비교는 대소문자를 무시한다(16진수라 대소문자에 정보가 없다). base58(Solana) 네트워크가 열리면
  대소문자가 값의 일부이므로 비교 규칙과 index를 그때 다시 정한다.
- 세 사건(`Out` · 전송 알림 · `In`)이 **어떤 순서로 와도** `INTERNAL` 한 계열만 발행되어야 한다. 전송 알림이 기준 거래 행을 만들므로
  그보다 먼저 온 사건은 보류(재시도)로 남았다가 수렴한다 — 즉시 같은 결과가 아니라 **재시도로 수렴**하는 성질이라 여섯 순열을 전부 검증한다.
- 보류는 [03 V29](03-bcm-db.md#v29-인박스-재시도-대기--물리-저장-계약)의 backoff를 따른다.

수용 항목: Dfns가 관리 계정 간 이동에서 실제로 양쪽 사건을 모두 보내는지와 그 간격,
**대납**(현재 정본은 내부이체 대납을 켜지 않는다 — 02) 없이 **송신 지갑에 native 가스가 있어야** 내부이체가 성립한다는 운영 전제,
목적지 주소를 발급한 뒤 재발급·교체가 일어나는 경우의 처리.

## 공개 API에서 선행할 변경

| 현행 공개 계약 | Dfns 연결 전 필요한 결정 |
|---|---|
| Account.accountId | BCM 발급 계정 ID로 설명 교정 완료. 논리 계정과 네트워크 wallet의 실행 연결은 아래 후속 계약을 따름 |
| Transfer.txId = 최초 root 벤더 ID | Dfns request 종류·체인 이동·대체 요청과의 대응, 기존 ID 조회 호환 |
| fireblocksAssetId / MISSING_IN_FIREBLOCKS | 벤더 중립 필드/상태 추가와 기존 소비자 처리. Dfns 값을 기존 Fireblocks 필드에 채우지 않음 |
| VendorBalance의 available/pending/frozen/locked | 확정(아래 [잔액 계약 — 구현](#잔액-계약--구현)): Dfns 온체인 잔액은 total·available, 제공하지 않는 pending/frozen/locked는 `null`(공개 API 0.12.0에서 nullable). 모르는 항목을 0으로 만들지 않음 |
| 웹훅 수동 재전송·생성 재시도 | 실제 제공하는 복구 방식과 오류/보류 계약, 운영 감사 기록 |

### 계정·주소 API의 후속 연결 계약

- 현행 계정 생성/조회 응답의 accountId·accountType·ref·registeredAt과 `(accountType, ref)` 멱등성을 보존한다.
  이번 OpenAPI 변경은 accountId/이벤트 파티션 키의 잘못된 vault 설명 3곳만 교정하며 필드·필수값·상태코드를 변경하지 않는다.
- Dfns 연결 시 계정 생성은 네트워크 없는 논리 계정 등록까지 완료하고, 네트워크 지갑은 첫 주소 발급에서 준비하는 설계다.
  기본 체인이나 wallet ID를 응답에 끼워 넣지 않는다. 현재 Fireblocks/로컬은 vault 생성 후 계정 완료라는 동작을 유지한다.
- 주소 발급은 현행 `(accountId, network, symbol)` 계약을 입구로 유지한다. 원천·계정·활성 자산 매핑 검증 후
  `(origin, accountId, network)` 의도를 예약한다. 같은 네트워크의 서로 다른 토큰 요청도 동일한 지갑 의도에 합류한다.
  주소 매핑 멱등성은 기존 `(accountId, network, symbol)`로 유지한다. 발급 시점 자산 locator·지갑 FK를 `bcm_addr_m`에 남기는 컬럼은 03의 후속 DDL 결정이며,
  현재는 활성 매핑 검증 뒤 지갑 주소만 저장한다.
- 지갑 회수 `Ready`와 토큰 수신 주소 완료는 별도다. chain별 수신 계정·tag/memo 모델 확인 전 wallet address를 모든 토큰 주소로 복사하지 않는다.
  현행 주소 API의 tag 미지원 과제와 추가 체인 구현은 별도로 남긴다.
- `Pending`/`Conflict`의 공개 HTTP 매핑은 아래 [보류·충돌 HTTP 계약](#보류충돌-http-계약--구현)으로 고정했다. Fireblocks의 현행
  `503 CREATION_RETRY_LATER`·`Retry-After`는 유지하며, Dfns에 Fireblocks 시간 기반 재생성을 허용하는 의미로 재사용하지 않는다.
  별도 API나 가짜 성공 응답은 추가하지 않았다. 공개 주소 API의 Dfns 연결은 아래 "계정·주소 API의 Dfns 연결 — 구현"으로 완료했다.

### 계정·주소 API의 Dfns 연결 — 구현

`AccountOperations`가 계정·주소 공개 유스케이스의 경계다. `fireblocks|local`은 기존 `AccountService`(vault/asset wallet), `dfns`는 `DfnsAccountService`
(논리 계정/네트워크 지갑)만 조립되며 컨트롤러·OpenAPI 계약은 같다. Fireblocks 생성 정책(`WalletProvisioningConfig`)은 Dfns에서 만들지 않는다.

| 공개 계약 | Dfns 구현 |
|---|---|
| `POST /accounts` | `LogicalAccountService`로 외부 호출 없이 `(accountType, ref)` 멱등 논리 계정 등록. 기본 체인·wallet ID를 응답에 넣지 않는다 |
| `POST /accounts/{id}/addresses` | 계정(LOGICAL)·활성 자산 매핑·수신 주소 모델을 **전체 먼저 검증**(미지원이 섞이면 400)한 뒤 네트워크마다 `(origin, accountId, network)` 지갑 의도를 예약해 `NetworkWalletProvisioningService`로 생성/회수한다. 같은 네트워크의 다른 토큰은 같은 의도에 합류한다. 결과는 `requireCompleted`로 항목별 `PROVISIONING_PENDING`(`bcm.dfns.provisioning-retry-after-seconds`)/`CONFLICT`/성공이다 |
| 수신 주소 | 지갑이 Ready면 원장(`bcm_ntwk_wlt_m`)의 지갑 주소를 그 네트워크 토큰의 `bcm_addr_m` 행으로 저장한다 — `bcm.dfns.account-address-networks`에 등록된(지갑 주소가 곧 토큰 수신 주소인 EVM 계정 모델) 네트워크에서만이다. 등록되지 않은 네트워크는 매핑이 있어도 `ASSET_NOT_SUPPORTED`다. tag/memo 모델 체인의 주소는 코드가 추정하지 않는다 |
| `GET /accounts/{id}/addresses` | 저장된 매핑 조회(공통) |
| `GET /accounts/{id}/balances` | 아래 [잔액 계약 — 구현](#잔액-계약--구현) — 발급 네트워크마다 준비 지갑의 자산 목록을 한 번 읽어 매핑 키와 대조한다 |

- 생성 의도의 ID와 `correlationId`는 `(originId, accountId, network)`에서 결정적으로 도출한 UUID(name-based)다. 제출 snapshot은 도메인 출력 포트
  `NetworkWalletSubmissionPort`가 만들며 Dfns 구현은 `createWalletBody(vendorNetwork, correlationId)`의 SHA-256·채택 명세 버전(`dfns-openapi-1.1018.3`)·벤더 network를 돌려준다.
  원장은 같은 scope에 다른 요청 snapshot이 오면 충돌로 거절하므로 재요청·같은 네트워크의 다른 토큰은 같은 seed로 기존 의도에 합류한다.
- 유스케이스는 도메인 출력 포트만 쓴다 — 벤더 설정 `bcm.dfns.*`는 조립부(`DfnsAccountConfig`)가 `NetworkWalletAddressPolicy`로 옮기고, 완료 지갑의 검증(의도 ID·원장 연결·주소)은
  지갑 피처 서비스 `NetworkWalletProvisioningService.provisionedWallet`이 담당한다. 계정 피처는 지갑 원장 Repository를 직접 읽지 않는다.
- 주소 저장 경합((계정, 네트워크, 심볼) PK)은 먼저 저장된 값을 돌려준다. 원장 지갑 ID가 완료 ID와 다르거나 주소가 없으면 저장하지 않고 충돌이다.
- **조립 범위**: `ConditionalOnDfnsProtocol`로 `DfnsClientConfig`(`bcm.dfns.*` 바인딩·서명기·HTTP 어댑터, 자격 누락은 빈 생성에서 실패)와
  `DfnsAccountConfig`(논리 계정·지갑 생성 서비스·주소 정책·`DfnsAccountService`)를 만들고, Fireblocks 쪽은 `FireblocksAccountConfig`·`WalletProvisioningConfig`가
  `ConditionalOnFireblocksProtocol`로 `AccountService`를 만든다. 이 절은 API 앱의 계정·주소 조립이며,
### 거래 조회 — 공통 원장을 읽는다 (2026-09-18 사용자 확정)

Dfns 조회를 따로 만들지 않는다. [02 거래 조회](02-bcm-flow.md#거래-조회--제공자-공통-2026-09-18-사용자-확정)가
**공개 조회를 BCM 원장만 읽도록** 확정했고, 그 이유의 절반이 Dfns에서 나왔다.

| 왜 벤더 조회로 못 하는가 | 근거 |
|---|---|
| 전송 목록에 `externalId` 필터가 없다 | 공식 OpenAPI 1.1018.3의 `GET /wallets/{walletId}/transfers`는 `limit`·`paginationToken`뿐이다([제공자별 회수 절차](#출금-제출-계약)) |
| 단건 조회가 `(walletId, transferId)`를 요구한다 | 공개 `txId` 하나로 부를 수 없다. 어느 지갑인지 먼저 알아야 하고 그건 우리 원장에만 있다 |
| **입금의 공개 `txId`가 벤더 ID가 아니다** | 입금은 전송 요청이 아니라 관찰이라 벤더 전송 ID가 없다. BCM이 `txHash`+index로 결정적 ID를 만든다 — 그 값으로 벤더를 되물을 수 없다 |

세 번째가 결정적이다. 앞의 둘은 "불편"이지만 이건 **되돌릴 경로가 없다**.

그래서 Dfns 경로는 조회용 벤더 포트를 늘리지 않는다. `NetworkTransferPort`의 조회는 **회수와 대사**에만 쓴다.

  Webhook 앱의 Dfns **입금** 판단 조립은 [판단 워커 조립](#판단-워커-조립--구현)에서 따로 구현했다. 출금 제출은 `DfnsSubmissionConfig`가 따로 조립하며, 거래 조회·Sweep·Admin 조회의 Dfns 조립은 후속이고,
  조립 여부와 무관하게 전체 컨텍스트의 `BCM_PROVIDER=dfns` 기동 차단(`ProviderConfiguration`)은 유지한다. 차단 해제는 Baseline 수용 뒤 사용자 결정이다.
- **후속**: tag/memo 체인 주소 모델, 이력 복구·RBF 계열. 자산 매핑 등록·잔액은 아래 두 절로, 웹훅의 입금·전송 알림·발신 확정의 블록 좌표는 위 세 절로 구현했다.

### Dfns 데이터셋의 자산 매핑 — 구현

현행 Admin 등록(07)은 "채택 네트워크 → 벤더 재해소 → 한 자산 한 매핑 → 현재 행+snapshot" 관문이며 벤더 재해소만 Fireblocks 카탈로그에 묶여 있었다.
재해소를 도메인 출력 포트 `ChainAssetResolver`로 분리해 `VendorAssetMappingService`는 원천을 모르고, 구현은 `fireblocks|local`이 `FireblocksChainAssetResolver`
(카탈로그 페이징·assetId/주소 대조, 기존 동작 그대로), `dfns`가 `DfnsChainAssetResolver`(`DfnsClientConfig`)다. 유스케이스는 결과의 `vendorAssetId`·`contractAddress`만 저장한다.

| 항목 | Dfns 계약 |
|---|---|
| 벤더 자산 식별 | Dfns에는 벤더 assetId가 없다. 채택 명세 1.1018.3 `GET /wallets/{walletId}/assets`의 자산 `kind`·locator로 **Dfns 자산 키** `<Network>:Native` / `<Network>:<Kind>:<locator>`를 만들어 `bcm_vndr_ast_m.vndr_ast_id`에 저장한다. ERC-20 locator는 소문자 컨트랙트 주소(주소 동일성은 대소문자 무관), Solana는 mint 그대로다. 등록(관문)과 잔액 관찰(어댑터)이 같은 규칙(`DfnsAssetKeys`)으로 만들어 문자열 동일성으로 대조한다 |
| 네트워크 행 | Dfns 공개 명세에는 블록체인/자산 카탈로그 API가 없어 일 1회 동기화가 없다. Dfns 데이터셋의 `bcm_blkc_m` 행은 DBA가 등록하는 데이터셋 seed다(03) — `vndr_blkc_id` = 채택 명세의 `Network` 값(예 `EthereumSepolia`), `ntwk_cd` = BCM 코드, `chain_id` = EIP-155(EVM만), `chain_mdl_dvcd` = `EVM`/`SOLANA`(V25). 관문은 이 값이 실행 설정 `bcm.dfns.networks[ntwk_cd]`와 같아야 등록한다(`networkBindingMismatch`) |
| 자산 모델 | 행의 `chain_mdl_dvcd`가 정한다(없으면 `assetModelUnsupported`). **EVM**: `contractAddress` null은 `Native`, 값이 있으면 명세 EVM 주소 형식 `^0x[0-9a-fA-F]{40}$`(`contractAddressInvalid`)의 `Erc20`이다. **SOLANA**: null은 `Native`(SOL), 값은 mint이며 base58 32바이트 공개키(`mintAddressInvalid`)와 운영자가 명시한 `tokenStandard`(`SPL`→`Spl`, `SPL_2022`→`Spl2022`, 없으면 `tokenStandardRequired`)로 키를 만든다 — 같은 mint 주소로 Token Program을 구분할 수 없고 카탈로그 원천도 없어 코드가 추정하지 않는다. 네이티브·EVM에 표준을 붙이면 `tokenStandardNotApplicable`이다 |
| Fireblocks 필드 | 요청 `fireblocksAssetId`는 Fireblocks 원천에서 필수(`fireblocksAssetIdRequired`), Dfns 원천에서는 있으면 거절(`fireblocksAssetIdNotApplicable`)이다. 응답은 원천의 벤더 이름을 붙인 필드만 채운다 — `fireblocksAssetId`(Fireblocks) / `dfnsAssetKey`(Dfns), 다른 쪽 null(OpenAPI 0.12.0 `AssetMapping`). Dfns 값을 Fireblocks 필드에 채우지 않는다 |
| 저장 길이 | `vndr_ast_id VARCHAR(128)`(03 V25)를 넘는 키는 자르지 않고 `vendorAssetIdTooLong`으로 거절한다. EVM(`EthereumSepolia:Erc20:`+40자 = 64)·Solana(`SolanaDevnet:Spl2022:`+44자 = 65) 키가 들어간다 |
| 벤더 재해소 한계 | 채택 명세의 `POST /networks/{network}/call-function`(온체인 read)은 응답 schema가 비어 있어 근거로 고정할 수 없다. 온체인 존재·decimals·발행사 대조는 운영자의 발행사 공식 자료(계획의 등록표)와 아래 수용 항목이며 코드가 추정하지 않는다 |

일괄 등록은 벤더 호출 없이 판정할 수 있는 항목 실패(`inspect` — Fireblocks 필수 assetId 누락, Dfns의 모든 검사)를 index 순서로 먼저 거절한 뒤 네트워크마다 관문을 한 번 부르고(`resolveAll`) 항목별 실패를 index로 표시한다. 해소된 벤더 자산이 요청 안에서 겹치면 저장 전에 `duplicateVendorAsset`이다.

### Solana 수신 주소와 자산 모델 — 구현

| 항목 | 계약 |
|---|---|
| 지갑 주소(명세 사실 + BCM 규칙) | 명세 `Wallet.address`는 지갑의 온체인 주소이고 Solana에서는 owner 공개키다(명세는 필드만 두며 SPL 수신 동작을 서술하지 않는다). BCM 규칙: 이 값을 EVM과 같이 `bcm_addr_m`의 토큰 수신 주소로 저장한다. "SPL 전송을 owner 주소로 보내면 보내는 쪽이 mint별 token account(ATA)를 만든다"는 Solana 관행이지 Dfns Baseline 확답이 아니며 아래 수용 항목이다. tag/memo 요구는 없다 |
| 발급 허용 | 운영자가 `bcm.dfns.account-address-networks`에 Solana 네트워크를 넣을 때만 발급한다. Dfns Baseline이 owner 주소로 들어온 SPL 입금을 지갑 자산으로 관찰하는지는 아래 수용 항목이며 확인 전에는 목록에 넣지 않는다. 코드는 ATA를 계산하지 않는다(ed25519 곡선 검사가 필요한 PDA 도출은 근거 있는 라이브러리 없이 구현하지 않음) |
| 자산 키 | `<Network>:Spl:<mint>` / `<Network>:Spl2022:<mint>` — 명세 자산 kind `Spl`·`Spl2022`의 `mint`와 같다. mint는 base58 32바이트로 형식만 검사하고 소유 프로그램은 운영자 지정이다 |
| 잔액(BCM 해석 규칙 — 수용 전) | 지갑 자산 관찰의 `Spl`·`Spl2022` 항목을 owner의 mint별 token account 합계로 해석한다(명세는 항목 단위를 서술하지 않음). 비ATA token account·동결·폐쇄 계정의 반영은 수용 항목이다. 관찰 경로의 mint도 등록과 같은 base58 32바이트 검사를 거치며 형식이 깨지면 목록 전체를 실패시킨다(미보유 0으로 축소하지 않음). 잔액 유스케이스는 EVM과 같다 |
| 미포함 | SPL 전송·수수료(SOL fee payer·rent)·집금·확정 모델은 Dfns 거래 조립과 계획의 Solana 게이트에서 다룬다. 이 절은 등록·주소·잔액 관찰까지다 |

### 잔액 계약 — 구현

| 항목 | 계약 |
|---|---|
| 원천(명세로 확인한 사실) | 채택 명세 1.1018.3 `GET /wallets/{walletId}/assets` — 응답 `walletId`·`network`·`assets[]{kind, <locator>, symbol?, decimals(number, 필수), balance(string, 필수), verified?}`. 설명은 "assets owned by the specified wallet"이다. 인증 토큰만 필요하고 사용자 행위 서명은 없다. 현재 OpenAPI 2.0.54와 공식 문서 페이지([Get Wallet Assets](https://docs.dfns.co/api-reference/wallets/get-wallet-assets), `.md` SHA-256 `24acec3a459fcae025a46f7aafd2bb6ae21b31a1da52bf22a28cbb10ada6e774`, 2026-09-15 확인)도 같은 필드만 두며 **`balance`의 단위와 미보유 토큰의 목록 포함 여부는 어디에도 서술되지 않는다** |
| 조회 단위 | 주소가 발급된 자산만 대상이다(Fireblocks와 같은 공개 계약). 발급 네트워크마다 원장의 준비 지갑(`NetworkWalletProvisioningService.readyWallet`)을 찾아 자산 목록을 **한 번** 읽고, 매핑의 `vendorAssetId`와 같은 Dfns 자산 키 항목이 그 자산의 잔액이다 |
| 필드 대응(BCM 해석 규칙 — 수용 전) | 명세가 `decimals`를 `balance`와 함께 필수로 두므로 BCM은 `balance`를 **최소 단위 정수 문자열**로, `decimals`를 같은 항목의 소수 자릿수로 해석해 `NetworkWalletAssetBalance.amount()`가 지수 표기 없는 소수 금액을 만든다(값 불변, 뒤따르는 0 제거, 0은 `"0"`). 이 해석은 공개 자료로 확정되지 않은 **BCM 규칙**이며 아래 수용 항목이다. `balance`가 정수 형식이 아니면(소수점·부호·지수) 0이나 원문으로 바꾸지 않고 실패한다 — 이 검사는 정수가 아닌 형식만 거절하며, 정수로 온 값의 단위가 실제로 최소 단위인지는 판별하지 못하므로 단위 정확성은 별도 수용 항목이다. **잔액 조회는 응답의 `decimals`를 쓴다** — 그 응답이 금액과 정밀도를 함께 주기 때문이다. 등록 매핑의 정밀도(03 V27)는 정밀도가 따라오지 않는 경로, 즉 최소 단위만 오는 **입금 사건의 금액 환산**에 쓴다. 두 경로가 같은 값을 써야 한다는 보장은 아직 없으며 어긋남 감시는 후속이다. `VendorBalance`는 total·available = 그 금액, pending·frozen·lockedAmount = `null`이다. 공개 `AssetBalance`의 `pending`·`locked`는 0.12.0부터 nullable이며 Fireblocks 응답은 그대로다 |
| 목록에 없는 자산(BCM 해석 규칙 — 수용 전) | 명세 설명 "assets owned by the specified wallet"을 근거로 목록에 없는 등록 자산은 미보유로 보고 `"0"`을 돌려준다. 미보유/미관찰의 구분은 응답만으로 할 수 없으므로 이 규칙은 아래 수용 항목이며, 확인 결과에 따라 유지하거나 "관찰 없음" 오류로 바꾼다. BCM이 만든 지갑은 생성 뒤부터 Dfns가 관찰한다는 점은 이 규칙의 전제이지 공개 자료의 확답이 아니다 |
| 형식 검사 | `walletId`가 요청 지갑과 다르거나 응답 `network` 원문이 scope 네트워크의 설정 매핑값(`bcm.dfns.networks`)과 다르면 실패다 — 매핑 없는 원문을 BCM 코드로 되돌리는 관찰용 fallback은 쓰지 않는다. 항목은 객체여야 하고 `kind`·`decimals`(정수 0..255 — 명세에 상한이 없어 모델링한 자산 표준의 uint8/u8 `decimals`를 BCM 정규화 한계로 둔다)·`balance`(정수 문자열)·`verified`(boolean)의 형식을 검사한다. 모델링한 kind(`Native`·`Erc20`·`Spl`·`Spl2022`)만 키로 정규화하고 그 밖의 kind는 대조 대상이 아니라 제외한다. 형식이 깨진 항목이 있으면 목록 전체를 신뢰하지 않고 실패한다(요청 자산만 골라 답하지 않음) |
| drift | 발급 기록이 있는데 준비 지갑이 없거나 응답 지갑·네트워크가 다르거나 같은 키가 둘이면 빈 배열·0으로 숨기지 않고 `INTERNAL`(500)이다. HTTP 오류는 상태·수신 바이트를 담은 `VendorApiException`으로 전파한다 |
| 미포함 | BCM 예약·컴플라이언스 보류 차감은 Dfns 거래 조립 뒤의 계약이다(계획 "잔액과 자금 통제"). 잔액 응답은 V24 증적 대상이 아니다(생성 의도 작업만 보관) |

### 전송 제출·조회 계약 — 구현

근거: 채택 명세 1.1018.3 `POST /wallets/{walletId}/transfers`(Transfer Asset)·`GET /wallets/{walletId}/transfers/{transferId}`(Get Transfer)와
공식 [Idempotency](https://docs.dfns.co/api-reference/idempotency)(`.md` SHA-256 `6de82575a0cb361689df4221ad6f6e8d195ed3927d5fbb5e23c79e7fe0817507`, 2026-09-16 확인).
구현은 도메인 출력 포트 `NetworkTransferPort`와 `DfnsNetworkTransferClient`(infra/client)다. **출금 제출 유스케이스가 이 포트를 쓴다**([출금 제출 유스케이스 — 구현](#출금-제출-유스케이스--구현)) — Sweep 연결은 후속이다.

| 항목 | 명세·문서로 확인한 사실 | BCM 규칙 |
|---|---|---|
| 요청 본문 | `kind`별 oneOf. EVM `Erc20{contract,to,amount}`, Solana `Spl`/`Spl2022{mint,to,amount}`, `Native{to,amount}`. `amount`는 최소 단위 정수 문자열(`^\d+$`), `externalId`는 1~50자 | 금액은 명세 패턴에 더해 **선행 0을 금지**한다(BCM 정규화 — 같은 금액의 표기를 하나로 고정해 응답 대조가 표기 차이로 어긋나지 않게 한다). 등록 자산 키(`<Network>:<Kind>:<locator>`)를 되돌려 `kind`·locator를 만든다 — 모델링한 네 kind만 전송하고 그 밖은 거절한다. 키의 network가 scope와 다르면 호출 전에 거절한다. 선택 필드(`priority`·`memo`·`feeSponsorId`·`travelRule`·`createDestinationAccount`·`useDurableNonce`)는 **보내지 않는다**(각각 수수료·대납·Travel Rule 계약이 따로 필요) |
| 멱등 | 같은 URL·본문·`externalId` 재요청은 기존 엔티티와 `200`. 같은 `externalId`로 **다른 본문/지갑**이면 `409`(`details.duplicate`). 종결(`Confirmed`/`Failed`/`Rejected`) 뒤에는 `externalId`가 그 엔티티에 영구 결속되고 재제출도 기존 엔티티를 돌려준다. 재시도는 **새 `externalId`**가 필요하다 | 제출 키(`bcm_sbmt_l.ext_tx_id`)를 `externalId`로 쓰되 50자를 넘으면 자르지 않고 거절한다(03의 키는 VARCHAR(128)이라 벤더 한계보다 넓다 — 거절 지점은 아래 [출금 제출 계약](#출금-제출-계약--확정)). 공식 문서가 규정한 표식(`error.details.duplicate`)이 있는 `409`만 조회 없이 "같은 키 다른 내용"으로 판정해 `Conflict` 결과(수신 바이트·`details.duplicate.id` 포함)로 돌려주고 자동 재제출하지 않는다. **표식 없는 `409`는 원인을 단정하지 않고** 일반 벤더 오류로 전파한다(다른 409 원인의 존재는 미확인). 실패한 전송의 재시도를 같은 키로 만들지 않는다 |
| 사용자 행위 서명 | Transfer Asset은 `Wallets:Transfers:Create` 권한과 사용자 행위 서명을 요구한다 | 지갑 생성과 같은 `/auth/action/init`→`/auth/action`→`X-DFNS-USERACTION` 흐름을 쓰고, 서명 경로는 지갑 ID가 들어간 실제 경로다. 응답을 받지 못한 실패 뒤 자동 재호출은 없다 |
| 응답 상태 | `Pending`(지갑 정책 승인 대기) · `Executing`(승인 후 실행 중, 짧은 구간) · `Broadcasted`(mempool 기록) · `Confirmed`(Dfns 인덱싱 파이프라인이 온체인 확인) · `Failed`(시스템 실패 또는 **온체인 실행 실패**, 문서상 재시도 없음) · `Rejected`(정책 승인에서 거절) | 도메인 `NetworkTransferStatus`로 옮기고 종결 여부(`Confirmed`/`Failed`/`Rejected`)와 체인 제출 여부(`onChainSubmitted: Boolean?`)만 판단한다. **`Failed`는 `null`**이다 — 시스템 실패와 온체인 실행 실패를 함께 뜻해 상태 원어만으로 제출 여부를 확정할 수 없고, 모름을 false로 바꾸지 않는다. **`Confirmed`를 BCM `FINALIZED`로 번역하지 않는다** — DCCP 임계는 별개이며 `TxStatus` 번역은 웹훅 판단 워커와 함께 정한다 |
| 응답 정규화 | 필수 `id`(`xfr-…`)·`walletId`·`network`·`requester`·`requestBody`·`metadata`·`status`·`dateRequested`. 선택 `txHash`·`externalId`·`fee`·`reason`·`approvalId`·`replacementId` | 필수 필드를 검사한다 — `id`는 명세 형식(`^xfr-…$`), `requester.userId`·`metadata`는 있어야 하고 `requestBody`의 `to`·`amount`도 필수이며 `dateRequested`는 UTC ISO 8601이어야 한다(형식이 다르거나 UTC가 아니면 감사 시각으로 받지 않는다). **제출 응답은 보낸 요청과 결속을 증명해야 한다** — `walletId`·`network`가 요청 scope와, `requestBody`의 `kind`·locator·`to`·`amount`가 보낸 값과, 되돌아온 `externalId`가 우리 제출 키와 같아야 한다(없거나 다르면 실패). 다르면 수신 바이트를 담아 실패한다. 관찰에는 목적지·금액을 함께 담아 호출자도 대조할 수 있다. 조회 `404`는 미관찰(null)이다 |
| 범위 밖 | — | 정책 승인(`Pending`) 운영 흐름, 대체 제출(cancel/speed-up)·boost, fee sponsor·Travel Rule, Sweep 컨트랙트 호출, 전송 응답의 원문 증적 보관(V24는 지갑 생성 의도 FK 전용이라 별도 원장이 필요하다). 제출 원장 연결과 출금 유스케이스는 [출금 제출 유스케이스 — 구현](#출금-제출-유스케이스--구현)으로 구현했다 |

수용 항목: 실제 `409` 본문 형식과 `details.duplicate`·**멱등 외 409 원인의 존재 여부**, 제출 응답이 `externalId`를 항상 되돌려주는지,
`Failed`에서 온체인 제출 여부를 가릴 관찰 필드(`txHash`·`details`), `Pending` 정책 승인의 운영 흐름·타임아웃, `Broadcasted`→`Confirmed` 지연과 재조회 주기,
`fee`·`priority`의 실제 동작, Solana `createDestinationAccount`(ATA rent 부담)와 EVM 대납의 필요 여부.

### 보류·충돌 HTTP 계약 — 구현

`NetworkWalletCreationIntent.requireCompleted(retryAfterSeconds)`가 저장 상태를 공개 계약으로 번역한다. 번역 결과는 [OpenAPI](../api/openapi.yaml) 에러 코드 표를 따른다.

| 저장 상태 | 공개 계약 | 호출자 의미 |
|---|---|---|
| `COMPLETED` | 연결된 wallet ID 반환 → 주소 발급 후속 | 준비된 지갑. 토큰 수신 주소 완료와는 별개 |
| `PREPARED`·`SUBMITTING`·`RECOVERING` | `ProvisioningPendingException` → `PROVISIONING_PENDING`(503, `Retry-After`/`retryAfterSeconds`). 주소 batch에서는 해당 네트워크 항목의 `error` | 오류가 아니라 지연. 매니저는 새 POST·키 회전 없이 조회만 재개하므로 같은 요청을 그대로 다시 보낸다 |
| `CONFLICT` | `ConflictException("networkWallet", intentId)` → `CONFLICT`(409 또는 항목 `error`) | 확정 오류. 운영 해소 전에는 재시도해도 같은 답이다 |

- `retryAfterSeconds`는 호출 계층의 폴링 정책이며 벤더 보장이 아니다. 1 이상이어야 하고 예외가 값을 검증한다.
- 저장된 대기 사유(`INCOMPLETE_SCAN`·`NOT_OBSERVED`·`ADDRESS_NOT_READY`)와 의도 ID는 예외 맥락에만 남기고 공개 `message`에는 싣지 않는다.
- `CREATION_RETRY_LATER`는 Fireblocks 생성 키의 시간 기반 교체 대기(기다린 뒤 새 생성 호출)이므로 Dfns 보류에 재사용하지 않는다.
- 수용 테스트: domain `NetworkWalletCreationIntentTest`, API `ApiExceptionHandlerTest`(503·`Retry-After`·내부 사유 미노출),
  `AccountSpecComplianceTest`(주소 batch 항목의 `PROVISIONING_PENDING`/`CONFLICT`가 스펙 schema와 일치), `ErrorCodeTest`(11종).

### 내부 생성 유스케이스와 증적 보관 입구

`LogicalAccountService`는 명시적으로 선택된 Dfns 원천에서 외부 호출 없이 논리 계정을 예약한다.
`NetworkWalletProvisioningService`는 AccountQueryService로 LOGICAL 계정과 원천을 확인한 뒤 V22 원장과 생성 포트를 연결한다.
두 클래스는 이후 `DfnsAccountConfig`가 `BCM_PROVIDER=dfns`에서만 조립한다 — 조건부 조립은 전체 기동 차단 해제가 아니다. Fireblocks/로컬 AccountService의 공개 계약은 유지한다.

1. 같은 scope는 최초 의도/상관관계·요청 hash/버전/벤더 network snapshot으로 합류한다. 검증된 자산/네트워크 매핑으로
   seed를 만드는 것은 호출자의 책임이다. 포트 create에도 고정 submission snapshot을 전달해 실행 중 현재 매핑으로 바꾸지 않는다.
2. PREPARED의 최초 claim 승자만 create를 한 번 호출한다. 경합 패자는 현재 의도를 반환하며 같은 호출 안에서 재제출하지 않는다.
   create 오류·응답 유실·증적 보관 실패는 전파하고 제출 이력을 남긴다. 후속 요청은 조회만 수행한다.
3. 포트 응답은 정규화 값과 실제 응답 byte[]를 함께 전달한다. NetworkWalletEvidenceStore에 원천·의도·작업 종류·조회 위치와
   같은 바이트를 보관하고 반환 hash가 SHA-256과 일치해야 V22에 기록한다. JSON 재직렬화·임의 URI로 보관 성공을 대체하지 않는다.
   보호 저장소는 [03의 V24](03-bcm-db.md#v24-네트워크-지갑-응답-증적-보관--물리-저장-계약)로 구현했다. 벤더 schema 대조는 후속이며 테스트의 내부 바이트는 Dfns payload가 아니다.
4. 생성 응답도 원천/소유/상관관계 검사를 거쳐 완료/대기/충돌로 저장한다. 이전 revision의 늦은 응답은 원장을 덮어쓰지 못한다.
5. 회수는 호출당 한 페이지만 읽어 저장한다. 진행 중 scan은 저장 cursor에서 이어가고, 끝난 대기는 새 scan으로 조회한다.
   새 scan에서 known ID가 있으면 단건 read, 없으면 후보 조회를 사용한다. 진행 중 목록 scan은 known ID를 얻어도 그 cursor를 끝까지 따른다.
   조회 실패·증적 보관 실패에는 cursor를 전진시키지 않는다. 0건/404는 재생성 허가가 아니다.
6. COMPLETED/CONFLICT는 외부 호출 없이 저장 상태를 반환한다. Pending/Conflict의 공개 HTTP 매핑은 위 [보류·충돌 HTTP 계약](#보류충돌-http-계약--구현)으로 고정했고, 공개 주소 API의 Dfns 연결은 [계정·주소 API의 Dfns 연결](#계정주소-api의-dfns-연결--구현)로 구현했다. 준비된 지갑 주소는 `DfnsAccountService`가 `bcm_addr_m`에 저장한다 — 후속은 03에 적은 지갑 FK와 발급 시점 자산 snapshot 컬럼이다.
7. 벤더 호출이 2xx가 아닌 응답이나 해석 불가한 2xx 응답으로 실패해도 수신 바이트가 있으면 같은 작업 종류로 먼저 보관한 뒤 오류를 전파한다.
   원장 페이지·cursor·연결은 기록하지 않는다. 응답을 받지 못한 실패(연결·timeout)는 보관할 바이트가 없다. 보관 자체가 실패하면 그 실패를 벤더 오류에 suppressed로 붙여 함께 전파한다.

### 응답 증적 보관 계약 — 구현

`NetworkWalletEvidenceJdbcAdapter`가 `NetworkWalletEvidenceStore`/`NetworkWalletEvidenceArchive`를 구현한다. 저장 계약은 03의 V24를 따른다.
보관 대상은 아래 공식 명세 작업의 **응답 본문 바이트**이며 요청 본문·인증 헤더·서명 secret은 보관하지 않는다.
작업 종류는 BCM 내부 값이고 아래 대응은 위 [공식 OpenAPI 1.1018.3](#공식-openapi-재확인과-구현-근거-2026-09-14-사용자-정정)에서 확인한 경로다.

| BCM 작업 | 공식 명세 작업 (1.1018.3) | 응답 본문에서 확인한 것 |
|---|---|---|
| `CREATE` | `POST /wallets` 200 | `Wallet` 객체(`allOf` Wallet + additionalProperties false). `id`·`network`·`signingKey`·`status`·`dateCreated`·`custodial`·`tags` 필수, `address`·`externalId`는 선택 |
| `READ` | `GET /wallets/{walletId}` 200 | 같은 `Wallet` 객체. 404/빈 본문도 그대로 보관하며 미관찰로 처리한다 |
| (오류) | 위 세 작업의 2xx 아닌 응답·해석 불가 응답 | 수신 바이트를 같은 작업 종류로 보관하고 오류를 전파한다. 증적 행의 존재는 성공 관찰이 아니며 상태는 예외·로그에 남는다 |
| `DISCOVER` | `GET /wallets` 200 | `items[]`(Wallet)와 `nextPageToken`. query는 `limit`·`paginationToken`·`owner`·`ownerId`·`ownerUsername`만 있고 externalId 서버 필터는 없다 |

- 어댑터는 응답을 정규화한 값과 같은 바이트를 서비스에 전달하고 서비스가 SHA-256을 계산한다. DB가 계산해 CHECK로 본문과 대조한 `body_hash`와 일치해야 V22 페이지에 기록한다.
  저장소 SQL은 `body` 컬럼을 읽지 않으며 runbook의 앱 역할 권한으로 실행 가능해야 한다.
- 증적 행의 존재는 지갑 준비 완료나 Dfns 수용이 아니다. `address` 부재는 주소 대기이며 `status`·`custodial`의 의미 해석은 HTTP 어댑터 연결 시 고정한다.
- **별도 수용:** 실제 Baseline 릴리스가 위 schema와 같은지, 서명/인증 원문, 실제 지연·장애 동작. 현재 결합 테스트의 바이트는 BCM 내부 표기이며 Dfns payload가 아니다.
  이 저장소가 있다는 사실로 `BCM_PROVIDER=dfns` 기동 차단을 해제하지 않는다.

내부 생성 서비스와 실제 PostgreSQL 원장·증적 저장소를 결합한 검증 결과는 [설계12](12-provider-compatibility.md#응답-증적-보관과-서비스db-결합-검증-2026-09-15)에 기록했다.

### 인증·요청 서명 어댑터 — 구현

`infra/client`의 `dfns` 패키지가 공식 OpenAPI 1.1018.3의 `securitySchemes`(Bearer `authenticationToken`, `X-DFNS-USERACTION`)와
`POST /auth/action/init`·`POST /auth/action`을 구현한다. 근거 문서는 다음 세 가지이며 다운로드 시점의 SHA-256을 함께 기록한다.

| 근거 | 확인한 내용 | SHA-256 (2026-09-15 다운로드) |
|---|---|---|
| [버전별 OpenAPI 1.1018.3](https://docs.dfns.co/openapi-versions/openapi-1.1018.3.yaml) | init 요청 필드(`userActionServerKind`·`userActionHttpMethod`·`userActionHttpPath`·`userActionPayload`), 응답 `challenge`·`challengeIdentifier`·`allowCredentials.key[]`, `/auth/action`의 `firstFactor`(Key: `credId`·`clientData`·`signature`, 선택 `algorithm`)와 응답 `userAction` | `2c46e1d1…9439` (위 표) |
| [Credentials data](https://docs.dfns.co/api-reference/auth/credentials-data.md) | Key credential의 clientData는 `{"challenge":"…","type":"key.get"}` — 키를 알파벳순으로, 구분자 뒤 공백 없이 stringify하고 base64url 인코딩한다. challenge는 이미 base64url이므로 재인코딩하지 않는다. 형식이 틀리면 서명 검증 실패 | `b7347896b824885d1e657ff2fa3358905e7aeefb1b0d4863871fef74a28f1cf8` |
| [User Action Signing flows](https://docs.dfns.co/api-reference/auth/signing-flows.md) | 흐름 순서(init → 서명 → `/auth/action` → 원 요청의 `X-DFNS-USERACTION` 헤더), 직접 호출 예제의 `crypto.sign(undefined, clientData, privateKey)` | `c07ddc196d8ff8b44007969cc76fc606688d81107316af73cc00f4f524859cac` |

- `DfnsCredentialSigner`: PKCS#8 개인키(EC·RSA·Ed25519)로 위 clientData 바이트를 서명한다. EC는 SHA-256/DER, RSA는 SHA-256 PKCS#1, Ed25519는 순수 서명 —
  예제의 `crypto.sign(undefined, …)` 기본 동작과 같다. `algorithm` 필드는 보내지 않는다(명세: 미지정 시 키로 결정).
- `DfnsUserActionClient`: init의 `userActionPayload`에는 실제로 보낼 본문 바이트를 그대로 문자열로 넣고 `userActionServerKind=Api`를 보낸다.
  명세가 필수로 정의한 `allowCredentials.key` 배열에 설정된 credential ID가 있어야 서명한다 — 목록이 없거나 배열이 아니면 진행하지 않는다.
  받은 `userAction`은 이어지는 한 요청에만 쓰고 저장·재사용하지 않는다. 인증 단계의 HTTP 오류·결손·형식 오류(서명 입력이 될 수 없는 challenge 포함) 응답도
  수신 바이트를 예외에 담아 전파해 같은 보관 규칙을 따른다.
- `DfnsProperties`(`bcm.dfns.*`): `base-url`(기본값 없음 — Baseline은 고객 환경 배포), `auth-token`, `credential-id`, `credential-private-key-pem|file`,
  timeout, `candidate-page-size`(1..500), `networks`(BCM 코드 → 명세 `network` 값, 값 중복 금지), `account-address-networks`(지갑 주소가 토큰 수신 주소인
  네트워크, `networks` 키의 부분집합), `provisioning-retry-after-seconds`(보류 안내 초, 기본 5). `VendorExecutionLimits`는 재시도 없는 연결+응답 상한과 생성 흐름 HTTP 3회다.
  `DfnsClientConfig`가 `BCM_PROVIDER=dfns`에서만 바인딩한다(위 "계정·주소 API의 Dfns 연결").
- **별도 수용:** 두 공식 자료의 clientData 예제가 다르다 — 참조 문서는 `challenge`·`type` 두 필드만 요구하고, 흐름 가이드 예제는 `origin`·`crossOrigin`을 추가한다.
  구현은 참조 문서의 형식을 따르며 실제 Baseline이 두 필드를 요구하는지는 운영 연결 전 수용 항목이다. `userAction` 토큰의 유효기간·재사용 가능 여부,
  429 처리, 실제 서명 원문도 명세에 없으므로 수용에서 확인한다.

### 지갑 생성·조회 HTTP 어댑터 — 구현

`DfnsNetworkWalletClient`가 `NetworkWalletProvisioningPort`를 구현한다. 위 [응답 증적 보관 계약](#응답-증적-보관-계약--구현)의 세 작업과 대응하며,
응답은 HTTP 상태와 무관하게 받은 바이트 그대로 서비스에 전달해 V24에 보관되고 정규화 값은 같은 바이트에서 해석한다.

| 포트 | 명세 호출 | 어댑터 규칙 |
|---|---|---|
| `create` | `POST /wallets` + 사용자 행위 서명 | 본문은 `{"network": submission.vendorNetwork, "externalId": correlationId}` 두 필드뿐이다. 본문 SHA-256이 의도에 저장된 `requestHash`와 다르면 HTTP 호출 없이 실패한다(고정 snapshot 계약). 2xx가 아니면 상태와 수신 바이트를 담아 전파하고 자동 재호출하지 않는다 |
| `read` | `GET /wallets/{walletId}` | 404는 본문을 보존한 미관찰(null)이다. 응답 `id`가 요청 ID와 다르면 오류다. 그 밖의 오류는 수신 바이트와 함께 전파한다 |
| `candidates` | `GET /wallets?limit&paginationToken` | 명세에 externalId 서버 필터가 없으므로 페이지 항목을 `externalId == correlationId`로 좁힌다. `nextPageToken`은 없거나 null이면 조회 끝, 비어 있지 않은 문자열이면 다음 위치이며 그 밖의 형식은 오류다(조회 완료로 오해하지 않는다). 반복 cursor 거절은 원장이 맡는다. 오류·`items` 결손을 빈 페이지로 바꾸지 않는다. 경로·쿼리는 URI 변수로 한 번만 인코딩해 opaque 토큰을 손상시키지 않는다 |

정규화 규칙 — 모두 명세 `Wallet` schema의 필드 설명에 근거한다.

- 명세 `Wallet`의 필수 `id`·`network`·`signingKey`(객체, `id` 문자열)·`status`·`custodial`(boolean)이 형식대로 있어야 정규화한다. 하나라도 결손·형식 오류면
  그 지갑을 조직 소유로 승인하지 않고 응답 전체를 오류로 전파한다(수신 바이트는 보관). `dateCreated`·`tags`는 사용하지 않아 검사하지 않는다.
- `vendorWalletId` = `id`, `correlationId` = `externalId`, `address` = `address`. 선택 문자열은 없거나 null이면 null이고 문자열이 아니면 형식 오류다.
  명세에 minLength가 없는 `address`·`externalId`의 빈 문자열은 null로 둔다(주소 대기·상관관계 없음 — 빈 값을 준비 완료나 상관관계 값으로 받지 않는다).
  minLength 1인 `signingKey.delegatedTo`·`vaultId`의 빈 문자열은 형식 오류다.
- 목록 응답은 필터 전에 모든 항목이 객체이고 `externalId`가 없거나 문자열인지 검사한다. 형식이 깨진 항목을 버리고 나머지로 완료 연결하지 않는다.
  검사를 통과한 항목만 `externalId == correlationId`로 좁힌다. `nextPageToken`은 재요청 `paginationToken`(minLength 1)이 되므로 빈 문자열은 재개 불가 → 오류다.
- `network`: 설정 `networks`로 BCM 코드로 되돌린다. 매핑에 없는 값은 원문 그대로 둬 회수 판정의 `NETWORK_MISMATCH`로 드러나게 한다.
- `ownership`: `custodial=true`(명세: 조직 소유)이고 `signingKey.delegatedTo`가 없고 `vaultId`(Vault 통제·읽기 전용 지갑)가 없고 `status=Active`일 때만 `ORGANIZATION`,
  그 밖은 `OTHER`다. Active가 아닌 지갑은 조직이 사용할 수 있는 자원으로 인정하지 않으며 상태 원문은 증적에 남는다. 판정 필드 결손은 위 규칙대로 오류이므로
  `UNVERIFIED`는 이 어댑터가 만들지 않는다.
- 다른 원천의 scope는 호출 전에 거절한다. JSON 객체가 아닌 본문은 정규화하지 않고 오류다.

계약 테스트 22건(`DfnsCredentialSignerTest` 4·`DfnsNetworkWalletClientTest` 18)은 MockRestServiceServer로 헤더·본문·서명 검증·오류 전파·토큰 인코딩·필수 필드 검사를 고정하며
응답 JSON은 명세 schema/예시 필드로 만든 표기다. 실제 서비스+실제 DB 결합은 `DfnsNetworkWalletEvidenceIntegrationTest`가 검증한다.
검증 결과는 [설계12](12-provider-compatibility.md#dfns-인증지갑-http-어댑터와-보류충돌-계약-검증-2026-09-15)에 기록했다.
어댑터는 `DfnsClientConfig`가 `BCM_PROVIDER=dfns`에서만 등록하고 공개 주소 API 연결은 위 "계정·주소 API의 Dfns 연결"을 따른다. API 전체 기동 차단은 유지한다.

## 다음 구현의 수용 자료

| 확인 항목 | 필요한 증거 | 그 전에도 가능한 작업 |
|---|---|---|
| 실제 Baseline 릴리스와 공개 schema의 일치 | 릴리스/이미지와 채택 명세 버전의 연결, 배포 지원 범위, clientData의 `origin` 요구 여부, `userAction` 유효기간 | 공식 버전별 OpenAPI에 근거한 인증/HTTP 어댑터·계약 테스트와 공개 계정·주소 API의 조건부 조립까지 구현 완료. 남은 것은 실제 Baseline 릴리스 대조이며 전체 기동 차단은 유지된다 |
| createWallet 중복·회수 | 동시 동일요청·응답 유실·조회 지연·충돌·재시작 결과와 보장 범위, 429 동작 | 목록/단건 조회 HTTP 어댑터·계약 테스트 완료. 최초 POST 1회·원장/증적 결합 복구는 내부 대역과 HTTP 어댑터 모두로 검증 완료 |
| 웹훅 원문·서명·retry | 서명된 바이트(재직렬화 없이 수신 바이트로 검증되는지), timestamp 단위·오차, kind별 `data` 형식, 실제 retry/이력 응답과 ID 연결 | Dfns HMAC 검증기·envelope 해석·Webhook 앱 조립 조건과 **판단 워커(입금·전송 알림)** 구현 완료(위 "Dfns 웹훅 수신 프로토콜 — 구현"·"판단 워커 조립 — 구현"·"전송 알림 판단 — 구현"). 발신 확정의 블록 좌표까지 구현 완료(위 "발신 확정의 블록 좌표 — 구현"). 이력 복구와 RBF 계열은 후속 |
| 조직 Wallet·초기 체인/USDC·KRWK | 지원 조합·자산 locator·소유·정책/가스 권한 | 체인 식별/확정/대납 인터페이스 설계; 추가 체인 실구현은 후속 |
| 자산 등록의 온체인 대조 | 채택 명세 `POST /networks/{network}/call-function`의 실제 응답 형식(ERC-20 `decimals()`·`symbol()` read), Solana mint 소유 프로그램 확인 원천 | Dfns 데이터셋 등록 관문(설정·네트워크 행·모델·주소/mint 형식·키 길이)은 구현 완료. 온체인 대조·Token Program은 발행사 공식 자료로 운영자가 확인 |
| Solana owner 주소 수신 | Baseline이 owner 주소로 받은 SPL/Token-2022 입금을 지갑 자산(`Spl`/`Spl2022`)으로 관찰하는지, 비ATA token account·동결 계정 반영, ATA 생성 rent 부담 주체 | 등록 관문·자산 키·잔액 관찰 구현 완료. `account-address-networks`에 Solana를 넣는 것은 이 확인 뒤 운영 결정 |
| 지갑 자산 목록의 의미 | `balance`의 단위(BCM 해석: 최소 단위 정수 — 공개 명세·문서에 서술 없음)·`decimals` 출처, 미보유/0 잔액 토큰의 목록 포함 여부, 생성 전 입금 토큰의 관찰 시점 | 잔액 어댑터·유스케이스·계약 테스트 완료. 정수 형식 검사는 정수가 아닌 형식만 거절하고 단위 정확성은 판별하지 못한다. 미보유 자산 `"0"` 규칙은 이 확인 뒤 유지/변경 |

사용자 지정 wiki의 질문은 벤더 확답이 아니다. 자료가 없는 항목을 임의로 채우거나 실벤더 호출로 확인하지 않는다.

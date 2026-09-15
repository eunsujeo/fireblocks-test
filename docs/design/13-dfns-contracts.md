# Dfns 지갑·원천 식별·웹훅 연결 계약

상태: 2026-09-14 공개 명세·사용자 지정 Baseline 자료와 현행 코드를 대조했다.
웹훅 공통 수신 경계와 네트워크 지갑 생성·조회 포트/순수 회수 판정, V22 생성 의도·회수·완료 연결 원장을 구현했다.
V23의 VAULT/LOGICAL 계정 모델과 내부 논리 계정·네트워크 지갑 생성 유스케이스, 원문 증적 보관 포트를 구현했다.
V24 보호 원문 저장소와 내부 생성 서비스+실제 PostgreSQL 결합 복구 검증을 구현했다. Dfns HTTP 어댑터·공개 API 연결은 미구현이다.
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
| BCM 계정 | `(accountType, ref)`로 유일한 논리 계정과 불변 원천을 등록한다. | V23·LogicalAccountService로 내부 등록을 구현했다. 외부 지갑 없이 계정 생성이 완료되는 공개 HTTP 연결은 후속이며 accountId는 BCM 발급 ID를 유지한다. |
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

포트에는 기본 성공/빈 조회 구현이 없다. 실제 실행 어댑터·공개 API 조립은 아직 없으며 기존 `AccountService`는 계속
Fireblocks `WalletVendorPort`와 기존 회수 정책을 쓴다. Dfns에 현재 vault 포트를 억지로 연결하거나 `WalletCreationPolicy`를 기본 제공하지 않는다.
내부 `NetworkWalletProvisioningService`는 생성 응답도 조회와 같은 원장 판정에 전달해 식별/소유 검사를 적용한다.
최초 제출 권한·재시작 복구는 [03의 V22 원장](03-bcm-db.md#v22-네트워크-지갑-원장--물리-저장-계약)과
`NetworkWalletProvisioningRepository`/`NetworkWalletProvisioningJdbcAdapter`로 구현했다.
예약과 최초 권한은 호출자 트랜잭션과 독립적으로 커밋하며, 회수 페이지의 증적·후보·cursor와 완료 연결을 원자 저장한다.
미완료 scan에서 유일하게 검증된 ID도 known ID로 고정해 다른 scan의 후보로 바꾸지 않는다.
V23 논리 계정과 내부 생성 서비스를 연결했다. 실제 HTTP 호출·원문 보관 어댑터·자산 주소 연결은 아직 없으며 저장 결과를 Dfns 수용으로 해석하지 않는다.

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

## 공개 API에서 선행할 변경

| 현행 공개 계약 | Dfns 연결 전 필요한 결정 |
|---|---|
| Account.accountId | BCM 발급 계정 ID로 설명 교정 완료. 논리 계정과 네트워크 wallet의 실행 연결은 아래 후속 계약을 따름 |
| Transfer.txId = 최초 root 벤더 ID | Dfns request 종류·체인 이동·대체 요청과의 대응, 기존 ID 조회 호환 |
| fireblocksAssetId / MISSING_IN_FIREBLOCKS | 벤더 중립 필드/상태 추가와 기존 소비자 처리. Dfns 값을 기존 Fireblocks 필드에 채우지 않음 |
| VendorBalance의 available/pending/frozen/locked | Dfns가 제공하는 값과 BCM 계산값의 구분. 모르는 항목을 0으로 만들어 호환 완료로 처리하지 않음 |
| 웹훅 수동 재전송·생성 재시도 | 실제 제공하는 복구 방식과 오류/보류 계약, 운영 감사 기록 |

### 계정·주소 API의 후속 연결 계약

- 현행 계정 생성/조회 응답의 accountId·accountType·ref·registeredAt과 `(accountType, ref)` 멱등성을 보존한다.
  이번 OpenAPI 변경은 accountId/이벤트 파티션 키의 잘못된 vault 설명 3곳만 교정하며 필드·필수값·상태코드를 변경하지 않는다.
- Dfns 연결 시 계정 생성은 네트워크 없는 논리 계정 등록까지 완료하고, 네트워크 지갑은 첫 주소 발급에서 준비하는 설계다.
  기본 체인이나 wallet ID를 응답에 끼워 넣지 않는다. 현재 Fireblocks/로컬은 vault 생성 후 계정 완료라는 동작을 유지한다.
- 주소 발급은 현행 `(accountId, network, symbol)` 계약을 입구로 유지한다. 원천·계정·활성 자산 매핑 검증 후
  `(origin, accountId, network)` 의도를 예약한다. 같은 네트워크의 서로 다른 토큰 요청도 동일한 지갑 의도에 합류한다.
  준비된 지갑에 검증한 자산 locator를 연결하며 주소 매핑 멱등성은 기존 `(accountId, network, symbol)`로 유지한다.
- 지갑 회수 `Ready`와 토큰 수신 주소 완료는 별도다. chain별 수신 계정·tag/memo 모델 확인 전 wallet address를 모든 토큰 주소로 복사하지 않는다.
  현행 주소 API의 tag 미지원 과제와 추가 체인 구현은 별도로 남긴다.
- `Pending`/`Conflict`는 내부 판정이며 아직 HTTP 상태/오류에 매핑하지 않는다. Dfns 연결 전 보류 응답·조회/재개·운영 충돌 해소 계약을
  OpenAPI와 수용 테스트로 고정한다. Fireblocks의 현행 `503 CREATION_RETRY_LATER`·`Retry-After`는 유지하며,
  Dfns에 Fireblocks 시간 기반 재생성을 허용하는 의미로 재사용하지 않는다. 별도 API나 가짜 성공 응답은 이번에 추가하지 않는다.

### 내부 생성 유스케이스와 증적 보관 입구

`LogicalAccountService`는 명시적으로 선택된 Dfns 원천에서 외부 호출 없이 논리 계정을 예약한다.
`NetworkWalletProvisioningService`는 AccountQueryService로 LOGICAL 계정과 원천을 확인한 뒤 V22 원장과 생성 포트를 연결한다.
두 클래스는 아직 기본 Spring 실행 빈/API에 연결하지 않는다. Fireblocks/로컬 AccountService의 공개 계약은 유지한다.

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
6. COMPLETED/CONFLICT는 외부 호출 없이 저장 상태를 반환한다. Pending/Conflict의 공개 HTTP 매핑과 자산 수신 주소 완료는 후속이다.

### 응답 증적 보관 계약 — 구현

`NetworkWalletEvidenceJdbcAdapter`가 `NetworkWalletEvidenceStore`/`NetworkWalletEvidenceArchive`를 구현한다. 저장 계약은 03의 V24를 따른다.
보관 대상은 아래 공식 명세 작업의 **응답 본문 바이트**이며 요청 본문·인증 헤더·서명 secret은 보관하지 않는다.
작업 종류는 BCM 내부 값이고 아래 대응은 위 [공식 OpenAPI 1.1018.3](#공식-openapi-재확인과-구현-근거-2026-09-14-사용자-정정)에서 확인한 경로다.

| BCM 작업 | 공식 명세 작업 (1.1018.3) | 응답 본문에서 확인한 것 |
|---|---|---|
| `CREATE` | `POST /wallets` 200 | `Wallet` 객체(`allOf` Wallet + additionalProperties false). `id`·`network`·`signingKey`·`status`·`dateCreated`·`custodial`·`tags` 필수, `address`·`externalId`는 선택 |
| `READ` | `GET /wallets/{walletId}` 200 | 같은 `Wallet` 객체. 404/빈 본문도 그대로 보관하며 미관찰로 처리한다 |
| `DISCOVER` | `GET /wallets` 200 | `items[]`(Wallet)와 `nextPageToken`. query는 `limit`·`paginationToken`·`owner`·`ownerId`·`ownerUsername`만 있고 externalId 서버 필터는 없다 |

- 어댑터는 응답을 정규화한 값과 같은 바이트를 서비스에 전달하고 서비스가 SHA-256을 계산한다. 저장소가 저장 컬럼에서 다시 계산한 hash와 일치해야 V22 페이지에 기록한다.
- 증적 행의 존재는 지갑 준비 완료나 Dfns 수용이 아니다. `address` 부재는 주소 대기이며 `status`·`custodial`의 의미 해석은 HTTP 어댑터 연결 시 고정한다.
- **별도 수용:** 실제 Baseline 릴리스가 위 schema와 같은지, 서명/인증 원문, 실제 지연·장애 동작. 현재 결합 테스트의 바이트는 BCM 내부 표기이며 Dfns payload가 아니다.
  이 저장소가 있다는 사실로 `BCM_PROVIDER=dfns` 기동 차단을 해제하지 않는다.

내부 생성 서비스와 실제 PostgreSQL 원장·증적 저장소를 결합한 검증 결과는 [설계12](12-provider-compatibility.md#응답-증적-보관과-서비스db-결합-검증-2026-09-15)에 기록했다.

## 다음 구현의 수용 자료

| 확인 항목 | 필요한 증거 | 그 전에도 가능한 작업 |
|---|---|---|
| 실제 Baseline 릴리스와 공개 schema의 일치 | 릴리스/이미지와 채택 명세 버전의 연결, 배포 지원 범위 | 공식 버전별 OpenAPI에 근거한 DTO·인증/HTTP 어댑터·계약 테스트 |
| createWallet 중복·회수 | 동시 동일요청·응답 유실·조회 지연·충돌·재시작 결과와 보장 범위 | 목록/단건 조회 HTTP 어댑터·계약 테스트. 최초 POST 1회·원장/증적 결합 복구는 내부 대역으로 검증 완료 |
| 웹훅 원문·서명·retry | 서명된 바이트, timestamp, 실제 retry/이력 응답과 ID 연결 | 공통 수신 순서·보존·선택 구현 회귀 |
| 조직 Wallet·초기 체인/USDC·KRWK | 지원 조합·자산 locator·소유·정책/가스 권한 | 체인 식별/확정/대납 인터페이스 설계; 추가 체인 실구현은 후속 |

사용자 지정 wiki의 질문은 벤더 확답이 아니다. 자료가 없는 항목을 임의로 채우거나 실벤더 호출로 확인하지 않는다.

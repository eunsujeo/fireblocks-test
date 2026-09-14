# 실행 제공자 선택·호환 계약

상태: DF0 코드/API 목록 대조·DF1 공통 경계 설계·DF3 Fireblocks/로컬 조립과 DB 원천 대조 구현. 전체 Dfns 호환 완료가 아니다.
[전체 계획](../dfns-compatibility-plan.md) · [코드/API 인벤토리](evidence/92-provider-compatibility-inventory.md)

## 범위와 실행 선택

동일 API·업무·이벤트·복구를 Fireblocks·Dfns·로컬에서 제공한다. 시작 시 `BCM_PROVIDER` 값 하나로 구성하고
동일 배포의 API/Webhook/BAT가 같은 제공자·조직·환경을 사용한다. Admin은 BCM API를 사용한다.
요청별 벤더 선택·자동 fallback·실행 중 교체는 하지 않는다. 추가 체인 실구현과 #51은 후속이다.

| 값 | 구성 | 아직 필요한 일 |
|---|---|---|
| `fireblocks` | 현행 Fireblocks 클라이언트·정책/서명 경계, 조건부 조립 구현 | 실환경 수용·실제 원천 등록/자격 대조 |
| `dfns` | 내부 Baseline API·Dfns 어댑터/웹훅 | schema/실측·인증·어댑터·지원표·정책/대납 수용 |
| `local` | 기존 Fireblocks 프로토콜 Stub·Anvil 실제 EVM, 조건부 조립 구현 | Dfns Stub·공통 시나리오 확장 |

`local`은 설정에서 선택하는 실행 구성이다. 체인 환경과 실제 프로토콜 제공자는 내부에서 구분한다.
Dfns Stub을 통한 로컬 어댑터 시험은 별도 시험 구성이다. 실제 Baseline 설치를 로컬 실행의 선행 조건으로 두지 않는다.
현재 `fireblocks`·`local` 조립을 지원한다. `dfns`는 어댑터가 없어 기동 시 명시적으로 거절하며 다른 제공자로 대체하지 않는다.

## 공통 포트와 구현 책임

기존 포트를 우선 사용한다. 벤더를 바꾸기 위해 API부터 모든 인터페이스를 새 이름으로 복제하지 않는다.

| 경계 | 유지/분리할 계약 | 벤더 구현이 맡을 일 |
|---|---|---|
| `WalletVendorPort` | 계정/주소 생성 의도·회수·잔액 응답 | 현재 vault 중심 메서드는 Fireblocks 계약. Dfns wallet provisioning·논리 계정 매핑은 별도 상세 계약 필요 |
| `VendorTransactionPort` | 제출·externalTxId 조회·거래/목록·접수/거절 구분 | 인증·ID·API별 멱등/조회·비용·원시 상태 |
| `VendorContractCallPort` | 집금 호출 의도·접수/조회 결과 | 승인·대납·최종 서명 내용·방송 경로·수신 증적 |
| `VendorAssetCatalogPort` | 후보 수집과 채택 자산의 분리 | 벤더 네트워크/자산 ID·페이지·정밀도·온체인 주소 대조 |
| `VendorStatusTranslator` | 원시 관찰→업무 상태와 종결 대사 범위 | 실제 상태 의미. confirmationCount 중심 계약은 멀티체인 확장 전에 타입 분리 |
| `WebhookProtocol`·`WebhookSignatureVerifier`·`WebhookTransactionParser` | 원문 바이트 검증·파싱·인박스/논리 사건 연결 | 수신 헤더/envelope는 WebhookProtocol로 분리. 키 조회·payload/재전달 ID·논리 사건 대응은 벤더별 책임 |
| `VendorNetworkFeePort` | 견적·원금/수수료 단위 구분 | 체인/계정별 견적·대납/실비. 현행 fee 필드를 Dfns 결과에 임의로 채우지 않음 |
| `VendorWebhookRecoveryPort` | 구독 상태·복구 실행/감사 | 재전송과 이력 재처리의 실제 수행 구분. 미지원 API의 가짜 성공 금지 |
| `VendorExecutionLimits` | 단일 호출/전체 제출 흐름의 최장 시간 | 재시도·백오프·응답 불명 회수까지 포함한 양수 상한 산정 |
| `WalletCreationPolicy` | 현재 키·등록/POST 준비/현재 시각 → 유지/회전/대기 | Fireblocks의 24시간 창·안전 회전은 FireblocksWalletCreationPolicy 소유. Dfns의 생성 보장을 추정하지 않음 |

### 먼저 분리하는 호출 시간 계약

`VendorExecutionLimits`는 domain의 순수 인터페이스로 둔다. 시크릿·URL·HTTP 라이브러리를 포함하지 않는다.

- `maximumCallMillis`: 재시도·백오프까지 포함한 논리 API 한 호출의 최장 시간.
- `maximumSubmissionFlowMillis`: 제출과 응답 불명 회수를 포함한 전체 흐름의 최장 시간. 앞 값의 2배라고 공통 코드에서 가정하지 않는다.
- `FireblocksProperties`가 현행 산식 그대로 이 인터페이스를 구현한다. 추후 선택된 Dfns 구성도 같은 계약을 제공한다.
- 최초 분리한 5개 조립 지점 중 거래 제출·전체 지갑 대사·미응답 제출 회수·boost는 이 시간 계약을 받는다.
  지갑 생성은 후속 `WalletCreationPolicy`를 받고, 선택된 Fireblocks 구현이 시간 계약을 사용한다.
- 기존 `claim TTL > 전체 흐름`, `회수 주기 > 단일 호출`, `boost TTL > 조회+전체 제출`의 엄격한 부등식과 overflow 검사를 유지한다.
- 시간 계약 분리는 Dfns 멱등 보존 시간을 확정하지 않는다. 24시간 창은 `FireblocksWalletCreationPolicy`로 옮겼고 Fireblocks/로컬에서만 조립한다.

이 단계는 빈 생성 수·기동 설정·HTTP 계약·DB를 바꾸지 않는다. 기존 Fireblocks 설정 빈을 인터페이스로 주입한다.
테스트는 Fireblocks 설정이 아닌 서로 다른 호출/제출 상한을 가진 대역으로 각 안전 경계를 검증하고 기존 테스트를 함께 실행한다.

## 조건부 조립 구현 순서

1. 공통 포트 사용처와 구체 Fireblocks 설정 의존을 정리한다. 먼저 위 시간 계약을 분리한다.
2. `ClientConfig`의 Fireblocks 설정/서명기와 컴포넌트 스캔을 제공자별 구성으로 나눈다. 웹훅 키 조회·수수료·복구 작업도 같은 조건을 적용한다.
3. 선택 설정은 기동 시 한 번 검증한다. 미설정/잘못된 값·선택 자격 누락·지원되지 않는 조합은 실패시킨다. 비선택 자격은 요구하지 않는다.
4. 기존 스크립트·systemd env 예제·CI의 Fireblocks/로컬 설정을 같은 변경에서 갱신한다. 설정 전환만으로 기존 실행 경로를 깨뜨리지 않는다.
5. 각 프로세스의 구현 1개·비선택 외부 호출 0·선택 설정 불일치·미지원 제출을 검사한다. 전체 배포의 설정 일치는 공통 설정 배포와 운영 readiness 증적에서 대조한다.

공통 업무 서비스는 환경변수를 읽지 않는다. Spring 조립은 infra/client와 각 실행 모듈, 순수 판정·포트는 domain이 소유한다.
현재 저장소의 `bcm.fireblocks` 설정을 `bcm.provider`로 통째로 바꾸지 않는다. 선택값과 제공자별 상세 설정은 별개다.

## 현재 기동 계약 (2026-09-14)

- API·Webhook·BAT가 공통 `ProviderConfiguration`을 사용한다. `BCM_PROVIDER`는 필수이며 소문자 값 하나만 받는다.
  누락·빈 값·오타 및 미구현 `dfns`는 일반 빈 생성 전에 실패한다. Dfns 차단 시 Fireblocks 설정 바인딩·키 파일 읽기·클라이언트 생성은 하지 않는다.
- `ConditionalOnFireblocksProtocol`은 `fireblocks` 또는 `local`에서만 현행 설정·서명기·클라이언트·상태 번역/parser·JWKS 검증기·EVM 조회를 조립한다.
  수수료·웹훅 복구·거래 대사/회수는 같은 선택된 포트를 사용하고 기존 배치 실행 게이트를 유지한다.
- 선택된 Fireblocks 프로토콜의 API key와 PKCS#8 키(PEM 또는 파일 중 하나)는 기동 시 필수다. 실행 조립부가 키 파싱도 수행한다.
  기존의 자격 없는 부트스트랩은 더 이상 지원하지 않는다. 테스트는 실행 중 생성한 일회성 키를 주입한다.
- `local`은 API/JWKS 및 설정된 모든 EVM RPC URL을 내부 HTTP(S) 주소로 제한하고 API key marker `bcm-local-stub`를 요구한다.
  허용 호스트는 localhost·IPv4 loopback/RFC1918·IPv6 loopback/ULA다. DNS 이름과 URL 내 자격은 거절한다.
  이는 설정 검증이다. Stub의 테스트 키 fingerprint·고정 체인 ID 검증과 네트워크 egress 통제를 대신하지 않는다.
- 기존 `BCM_VENDOR_MODE`·`BCM_CHAIN_MODE`를 함께 주입하면 `local`은 STUB+LOCAL, `fireblocks`는 FIREBLOCKS+TESTNET/MAINNET과 일치해야 한다.
  vendor-mode는 미설정 가능하다. chain-mode는 원천 검증 도입 후 필수이며 제공자를 추정하는 용도로 쓰지 않는다.
  세 앱은 같은 DB 원천과 기대 설정을 대조한다.
- `scripts/local.sh up fireblocks`는 `BCM_PROVIDER=fireblocks`, `up stub`과 로컬 smoke/BAT 실행은 `local`을 주입한다.
  폐쇄망 env 예제도 `local`을 명시한다. 직접 실행하는 API/Webhook/BAT는 동일 값을 각각 주입한다.
  `FIREBLOCKS_JWKS_URL` 별칭은 세 앱 모두 지원하고 정식 `BCM_FIREBLOCKS_WEBHOOK_JWKS_URL` 설정도 유지한다.

웹훅 수신 헤더는 현재 두 실행 경로 모두 `Fireblocks-Webhook-Signature`다. 헤더/envelope 선택은 WebhookProtocol로 분리했다. Dfns 실제 검증·메타데이터 채택은 [연결 계약](13-dfns-contracts.md)을 따른다.
자산/지갑 원천 ID, Dfns 인증/멱등/지원 기능표, 추가 체인 인터페이스는 이번 조립 변경의 완료 범위가 아니다.

## 웹훅 수신 프로토콜 경계

`WebhookProtocol`은 domain의 순수 포트이며 선택된 서명 헤더 이름과 검증 후 envelope 해석을 제공한다.
`WebhookEnvelope`의 `notificationId`는 수신 알림 ID, `eventType`은 벤더 사건 종류, nullable `vendorTransactionId`는 거래 연결값이다.
이 포트는 CORE `evnt_id` 생성이나 논리 사건 dedup을 대신하지 않는다.

Controller는 전체 HTTP 헤더에서 선택된 헤더 값 하나만 꺼낸다. 헤더 이름은 대소문자를 구분하지 않으며,
값이 없거나 여러 개면 임의 선택 없이 기존 INVALID_SIGNATURE→401 경로로 보낸다. 원문 서명 값은 trim/재인코딩하지 않는다.
Service는 서명 검증→선택된 protocol의 envelope 파싱→동일 byte[]의 해시/원문과 실제 서명 저장 순서를 지킨다.
검증 실패는 파싱·적재하지 않고, 파싱/저장 오류는 성공으로 감추지 않는다. 200은 인박스 적재/중복 수용 후에만 반환한다.

`FireblocksWebhookProtocol`은 기존 `id`/`eventType`/`data.id` 해석을 infra/client로 옮긴 구현이며,
Fireblocks/로컬에서만 조립된다. 기존 parser·RS512 검증·worker·outbox·DB/공개 API 계약을 유지한다.
Dfns HMAC·timestamp·`kind`·이력 복구 구현은 별도 수용 계약을 갖추기 전 등록하지 않는다.

## 저장·API 변경 선행 조건

설정 선택과 지갑/자금 이전을 구분한다. 저장된 원천과 선택 제공자가 다르면 실행을 거절해야 한다.
원천 식별/기존 행 백필·DB 범위·진행 거래 처리는 [03의 단일 데이터셋 binding 계약](03-bcm-db.md#제공자-원천-binding--후속-물리-계약)을 따른다.
V21의 물리 테이블/제약·조회 Repository·기동 전 guard를 구현했다. 실제 데이터셋의 원천 확인/등록과 권한 부여는 DBA 절차다.

`fireblocksAssetId`, `MISSING_IN_FIREBLOCKS`는 현재 공개 API/BFF 계약에 남아 있다. Dfns 값을 이름만 바꿔 끼우지 않는다.
기존 소비자 필드 보존과 벤더 중립 필드 추가/해석을 OpenAPI에서 별도로 확정한 뒤 생성물·BFF를 함께 갱신한다.
`WebhookController`의 헤더 선택과 수신 envelope 파싱은 WebhookProtocol로 분리했다. Dfns의 원천·재전달/이력 복구 증적은 [연결 계약](13-dfns-contracts.md)에서 별도 설계한다. 원문 바이트·해시·실제 서명 감사는 보존한다.

## 원천 검증의 실행 설정과 시작 순서

API/Webhook/BAT는 `BCM_ORIGIN_ID`(`bcm.origin.id`), `BCM_ORIGIN_PLATFORM_INSTANCE_ID`
(`bcm.origin.platform-instance-id`), `BCM_ORIGIN_VENDOR_ORGANIZATION_ID`(`bcm.origin.vendor-organization-id`),
`BCM_CHAIN_MODE`(`bcm.chain-mode`)를 필수로 받는다. ID는 공백 없는 앞뒤 경계의 1~64자이며 자동 기본값이 없다.
프로토콜은 선택된 구현에 고정한다(`fireblocks|local` → `fireblocks`, `dfns` → `dfns`). 별도 설정으로 바꾸지 않는다.
원천 ID·실행 모드·프로토콜·설치 ID·조직 ID·체인 환경을 모두 DB와 정확히 비교한다.

공유 `ProviderOriginConfiguration`은 설정/DB 대조에 성공한 `verifiedProviderOrigin` 빈을 만든다.
BeanFactoryPostProcessor는 빈을 생성하지 않고 API/Webhook/BAT의 실행 빈과 client/messaging 빈에 이 빈의 선행 의존을 추가한다.
기존 제공자 선택 검사는 먼저 완료되며, 원천 조회용 persistence·프레임워크 기반 빈은 이 의존에서 제외한다.
외부 호출·runner·scheduled job·relay·HTTP 요청 수용 전에 실패해야 하므로 ApplicationRunner에서 늦게 검사하지 않는다.
원천 조회는 SELECT만 수행하고 조회 실패 원인을 보존한다. binding의 DML 권한은 DBA가 회수하며 앱에 등록/수정 API를 제공하지 않는다.
기존 데이터셋에는 원천 확인과 V21 적용/등록이 필요하다. 테스트와 신규 로컬 Stub 데이터셋은 고정된 시험 원천으로 별도 초기화한다.

## DF0/DF1에서 남은 결정

| 항목 | 담당 역할 | 적용 시점 |
|---|---|---|
| 초기 실제 EVM 네트워크·USDC/KRWK 발행/주소 | 사용자·CORE/상품·BCM | 실벤더 PoC 전에 고정. 현재는 로컬 테스트 자산을 실제 발행 증거로 쓰지 않음 |
| Baseline 릴리스·RPC·지갑/승인·대납 제공 조건 | Dfns·플랫폼·노드·보안 | DF2 수용. 공개 문서만으로 지원 완료 처리 금지 |
| 지갑 모델·멱등 창·자산 공개 필드·원천 DB 식별 | BCM·CORE·DBA | 해당 어댑터/DDL 구현 전 |
| 처리량·감지/대사 지연·RTO/RPO | 운영·CORE·BCM | DF8 성능/운영 인수 전 |

위 항목이 남아 있으므로 DF0/DF1 전체 완료로 체크하지 않는다. 인벤토리·공통 경계 분리·원천 binding 설계는 PLAN의 개별 완료 단위로 추적한다.

## 첫 구현 검증 (2026-09-14)

- 새로운 벤더 시간 대역을 기존 구체 설정 인자에 전달했을 때 API/BAT 테스트 컴파일이 타입 불일치로 실패하는 것을 먼저 확인했다.
- 공통 포트로 변경한 뒤 API 5건·BAT 13건·client 4건, 합계 22건이 실패/오류/skip 없이 통과했다. 신규 6건은 시간 경계 5건과 실제 설정 바인딩 1건이다.
- 전체 `ktlintCheck` 통과. 기존 테스트·HTTP/DDL·실행 설정은 변경하지 않았다. Dfns 호출/전체 호환·운영 배포·Phase converge 증거는 아니다.

## 조건부 조립 검증 (2026-09-14)

- 조립 계약 14건을 먼저 작성해 기존 코드에서 12건 실패를 확인한 뒤 구현했다. 최종 조립 검사 19건은 필수 선택/정확한 값·환경변수 바인딩·단일 포트·비선택 설정 제외·자격 누락/잘못된 키·로컬 URL/IPv6 ULA·기존 모드 충돌을 검증한다.
- API/Webhook/BAT의 실제 SpringApplication 기동 경로에서 누락/오타/Dfns를 각각 거절하고 Fireblocks 빈 정의·DB/업무 작업의 선행 생성을 막는 9건을 검증했다.
  간소 컨텍스트 runner의 테스트 설정 중복 스캔을 확인해 실제 Boot 기동 경로로 검사했다.
- 기존 Fireblocks client 64건, API 실행/API 계약·아키텍처·로컬 Stub→Anvil 이체 41건, Webhook 4건, BAT 기존 1건과 신규 local 1건을 함께 검증했다.
  신규 29건 포함 총 139건, 실패/오류/skip 0. 기존 통합 테스트는 명시적 선택과 일회성 키 공급만 추가했고 assertion을 완화하지 않았다.
- 전체 `ktlintCheck`, `scripts/tests/local-test.sh`, `scripts/tests/local-distribution-test.sh`, `scripts/tests/fireblocks-contract-test-test.sh` 통과.
  마지막 결합 실행 중 client 검사 도구가 EOFException으로 종료해 client 검사를 단독 재실행했고 83건·ktlint 통과를 확인했다.
  실제 벤더 호출·배포·커밋·Phase converge는 수행하지 않았다. 전체 Dfns 호환 완료가 아니다.

## 웹훅 공통 경계 검증 (2026-09-14)

- 대체 제공자 대역으로 선택 헤더/envelope 경계 6건을 먼저 작성하고 기존 코드의 타입/생성자 컴파일 실패를 확인했다.
  새 포트와 Fireblocks 구현을 연결한 뒤 검증 순서·원문/해시/실제 서명·비선택/누락/중복 헤더 거절·파싱 실패 전파가 통과했다.
- 기존 WebhookIngestionServiceTest는 같은 ObjectMapper 대역을 새 Fireblocks protocol에 주입한다. 기존 결손 필드·서명 실패·계측 장애 검증은 유지했다.
  WebhookIntegrationTest의 외부 헤더는 동일한 문자열이며 이전 Controller 상수 참조만 제거했다.
- client 22건·Webhook 59건·API 34건·BAT 2건 = 117건, 실패/오류/skip 0. 새 포트의 Fireblocks/로컬 단일 조립, RS512 검증,
  DB 인박스/worker/outbox·Kafka 순서, 로컬 Stub→Anvil 이체, API/프로세스 경계·아키텍처 회귀를 실행했다.
- 전체 ktlintCheck 통과. 최초 새 parser 체인 표현의 줄바꿈 오류는 수정 후 통과했다.
  Dfns payload/서명 fixture를 창작하지 않았고 실제 Dfns 호출·DDL/OpenAPI 변경·배포·커밋·Phase converge는 수행하지 않았다.

## 생성 재시도 정책 분리 검증 (2026-09-14)

- 변경 전 WalletProvisioningPolicy·AccountService·설정 바인딩·FireblocksProperties 테스트가 통과한 상태에서 분리했다.
- 신규 WalletCreationPolicyBindingTest의 인터페이스 부재 컴파일 실패를 먼저 확인했다. 구현 후 시간 계약만으로는
  생성 정책을 추정하지 않는 조립 실패, 원본 입력 전달 및 유지/회전/대기 판정 보존을 검증했다.
- 기존 시간 경계 테스트 4건을 domain에서 client의 FireblocksWalletCreationPolicyTest로 옮겼으며 입력·assertion을 유지했다.
  AccountService와 호출 시간 조립 테스트는 명시적으로 Fireblocks 정책을 주입하고 기존 assertion을 유지한다.
- 최종 선택 회귀: domain 1 · client 27 · API 68 · Webhook 3 · BAT 2 = **101건**, 실패/오류/skip 0.
  계정 생성/회수·AccountSpecCompliance·로컬 실제 EVM 이체·Architecture·ProviderAssembly/Startup·BAT 양 모드 기동을 포함한다.
  `./gradlew`의 해당 모듈/클래스 선택 실행과 전체 `ktlintCheck`가 `BUILD SUCCESSFUL in 38s`로 끝났다.
- Dfns 동작 수용·DB binding SQL/guard·벤더 실호출·배포·독립 Phase converge는 이번 검증 범위가 아니다.

## DB 원천 검증 (2026-09-14)

- 원천 비교와 guard 부재 테스트의 컴파일 실패를 확인한 뒤 domain/공유 config/V21/조회 어댑터를 구현했다.
  DB 조회 실패의 원인 예외를 보존하고 원천 미등록·필수 설정 누락·다른 원천을 자동 보정하지 않는다.
- API/Webhook/BAT의 실제 SpringApplication에서 각 8개 실패 시나리오를 검사했다. 원천/설치/조직/체인/제공자 불일치,
  테이블 누락, 별도 schema의 미등록 binding, 전역 lazy 초기화를 포함한다. HTTP 벤더 대역 호출 0과 클라이언트/서명기·Job·Controller·relay 생성 0을 확인했다.
- PostgreSQL PK/UNIQUE/CHECK·감사 컬럼·전체 식별자 조회·조회 전용 역할의 INSERT/UPDATE/DELETE/TRUNCATE 거절을 확인했다.
  이전 V1/V20의 미완료 생성 의도·보관 파티션의 실제 fixture 원문/해시/서명을 V21 적용 및 명시 등록 전후로 보존했다.
- 기존 통합 fixture는 Fireblocks 원천을 명시 등록하며 로컬 시험은 별도 DB에 로컬 원천을 등록한다. 환경 변경으로 기존 행을 덮어쓰지 않는다.
  기존 테이블 전체 목록 assertion에는 V21 테이블만 추가했다. 다른 기존 업무 assertion은 완화하지 않았다.
- 최종 선택 실행: domain 3 · application 3 · persistence 11 · client 19 · API 59 · Webhook 53 · BAT 13 = **161건**, 실패/오류/skip 0.
  `ktlintCheck`를 포함한 Gradle 실행은 `BUILD SUCCESSFUL in 36s`다. 처음의 신규 테스트 annotation 줄 길이 오류는 수정 후 재검증했다.
- `scripts/tests/local-test.sh`·`scripts/tests/local-distribution-test.sh` 통과. 실제 네트워크가 차단된 임시 PostgreSQL 컨테이너 두 개에서 새 entrypoint를 검증했다.
  Stub 데이터셋만 원천을 등록하고 Fireblocks 데이터셋은 미등록 상태를 유지한다. 따옴표/달러가 포함된 instance ID도 원문 그대로 등록됨을 확인했다.
- 실환경 DB/역할 적용·벤더 호출·배포·Dfns 어댑터/Stub·독립 Phase converge는 미수행이다. 운영 역할의 권한은 [runbook](../runbooks/provider-origin.md)으로 별도 확인한다.

## 네트워크 지갑 인터페이스·회수 판정 검증 (2026-09-14)

- [13](13-dfns-contracts.md#네트워크-지갑-공통-포트와-회수-판정--구현)의 BCM 내부 포트·입출력·Ready/Pending/Conflict 판정을 구현했다.
  테스트를 먼저 작성해 타입 부재 컴파일 실패를 확인한 뒤 구현했다. Dfns HTTP payload를 창작하지 않고 내부 정규화 fixture로 검사한다.
- 신규 14건은 미관찰·미완료 조회·주소 대기·동일 관찰 dedup·복수 wallet·상충 주소·원천/network/correlation/소유 불일치,
  known ID 보존·잘못된 식별자를 검증한다. 자동 재생성 허가 결과는 없다. DB CAS/페이지 수집/실제 응답 검증은 후속 호출자의 책임이다.
- domain 18 · API 49 = **67건**, 실패/오류/skip 0. 기존 WalletProvisioningPolicy·AccountService·AccountSpecCompliance,
  Architecture·ProviderStartup을 포함한다. `ktlintCheck`를 포함한 선택 실행은 `BUILD SUCCESSFUL in 24s`로 완료했다.
- OpenAPI는 accountId 설명 3곳만 변경됐다. YAML 구조 대조로 나머지 값·필드·상태코드가 같음을 확인했고,
  `python3 docs/api/build.py`로 재생성했다. `node --test docs/api/try-it.test.mjs` 11건 통과.
- 기존 Fireblocks/로컬 서비스의 포트는 유지한다. Dfns 실제 연결·신규 DB 영속화/업무 조립·실벤더 호출·배포·독립 Phase converge는 미수행이다.

## 네트워크 지갑 원장 검증 (2026-09-14)

- [03의 V22](03-bcm-db.md#v22-네트워크-지갑-원장--물리-저장-계약)에 의도·페이지/후보 증적·지갑 연결의 물리 계약을 먼저 상세화했다.
  새 Repository 부재 컴파일 실패를 확인한 뒤 V22·domain 상태/Repository·JDBC 저장 어댑터를 구현했다.
- 새 PostgreSQL 테스트 16건은 동시 예약/최초 권한 1개, 호출자 롤백과 시간 경과에도 권한 재부여 0,
  0건 회수·cursor 재개/반복·이전 revision/scan 거절, ID 고정·원천 충돌 증적·다른 계정의 wallet 중복 격리를 검증한다.
  완료 UPDATE 실패를 DB trigger로 주입해 연결·페이지·후보·cursor가 함께 롤백되고 이전 POST 준비 이력은 보존됨을 확인했다.
- 중간 검토에서 미완료 조회의 known ID 보존 테스트가 실제 실패하는 것을 확인하고 보완했다.
  조회를 새로 시작하더라도 검증된 ID를 다른 ID로 교체하지 않는다. 포트의 실제 POST 호출 수 검증은 업무 유스케이스 연결 후 수행한다.
- V22 PK/FK/CHECK·감사 컬럼·상관관계 UNIQUE와 기존 계정/미완료 vault 의도의 보존을 PostgreSQL에서 검사했다.
  Bootstrap의 정확한 테이블 목록에 신규 4개만 추가했으며 기존 assertion을 완화하지 않았다. V1~20 SQL은 HEAD와 동일하다.
- 최종 선택 실행: domain 17 · persistence 41 · API 60 · Webhook 3 · BAT 2 = **123건**, 실패/오류/skip 0.
  기존 계정 저장/생성/HTTP 계약·아키텍처·제공자 기동 차단·BAT Fireblocks/로컬 기동을 포함하고 전체 ktlintCheck가 통과했다.
  마지막 결합 실행은 `BUILD SUCCESSFUL in 25s`다.
- 기존 계정/주소 테이블과 공개 API는 이번 단계에서 변경하지 않았다. 원문 보관 어댑터·논리 계정 모델·실행 유스케이스,
  실제 Dfns/벤더 호출·운영 DB 적용·배포·독립 Phase converge는 미수행이다. Dfns 기동 차단을 유지한다.

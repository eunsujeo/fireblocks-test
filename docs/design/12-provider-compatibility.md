# 실행 제공자 선택·호환 계약

상태: DF0 코드/API 목록 대조·DF1 공통 경계 설계·DF3 Fireblocks/로컬 조립·DB 원천 대조·네트워크 지갑 원장/증적·공식 명세 기반 Dfns HTTP 어댑터 구현. 전체 Dfns 호환 완료가 아니다.
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
현재 **기동을 지원하는 조립은 `fireblocks`·`local`뿐**이다. `dfns`는 계정·주소·잔액·자산 등록 관문·웹훅 수신·전송 어댑터를 구현했지만
실행 조립과 Baseline 수용이 끝나지 않아 기동 시 명시적으로 거절하며 다른 제공자로 대체하지 않는다(아래 "현재 기동 계약").

## 공통 포트와 구현 책임

기존 포트를 우선 사용한다. 벤더를 바꾸기 위해 API부터 모든 인터페이스를 새 이름으로 복제하지 않는다.

| 경계 | 유지/분리할 계약 | 벤더 구현이 맡을 일 |
|---|---|---|
| `WalletVendorPort` | 계정/주소 생성 의도·회수·잔액 응답 | 현재 vault 중심 메서드는 Fireblocks 계약. Dfns는 `NetworkWalletProvisioningPort`·`NetworkWalletAssetPort`(지갑 자산 잔액)로 분리했고 `VendorBalance`의 제공되지 않는 구분은 null이다(계약13) |
| `ChainAssetResolver` | Admin 자산 등록의 벤더 재해소 관문 | Fireblocks는 카탈로그 assetId·주소 대조, Dfns는 데이터셋 네트워크 행·자산 모델·주소 형식 대조와 Dfns 자산 키 생성(계약13) |
| `VendorTransactionPort` | 제출·externalTxId 조회·거래/목록·접수/거절 구분 | 현재 vault 중심 메서드는 Fireblocks 계약. Dfns 지갑 전송은 `NetworkTransferPort`로 분리했다(계약13) |
| `VendorContractCallPort` | 집금 호출 의도·접수/조회 결과 | 승인·대납·최종 서명 내용·방송 경로·수신 증적 |
| `VendorAssetCatalogPort` | 후보 수집과 채택 자산의 분리 | 벤더 네트워크/자산 ID·페이지·정밀도·온체인 주소 대조 |
| `VendorStatusTranslator` | 원시 관찰→업무 상태와 종결 대사 범위 | 실제 상태 의미. confirmationCount 중심 계약은 멀티체인 확장 전에 타입 분리. Dfns는 `DfnsStatusTranslator`가 전송 여섯·이동 둘의 원어를 번역하고 컨펌 수 자리에 블록 깊이를 받는다(계약13) |
| `FinalityPolicy`·`ChainHeadPort` | 네트워크별 확정 임계와 확정 판정의 입력 | Fireblocks는 벤더 `numOfConfirmations`를 임계와 비교한다. Dfns는 컨펌 수를 받지 못해 `blockNumber`와 체인 head의 깊이를 직접 계산한다(CLAUDE.md 3절·계약13) |
| `WebhookProtocol`·`WebhookSignatureVerifier`·`WebhookTransactionParser` | 원문 바이트 검증·파싱·인박스/논리 사건 연결 | 수신 헤더/envelope는 WebhookProtocol로 분리. 키 조회·payload/재전달 ID·논리 사건 대응은 벤더별 책임. Dfns 전송 사건은 `NetworkTransferEventParser`, 온체인 이동(입금) 사건은 `NetworkChainEventParser`로 분리했고 귀속은 도메인 순수 판정 `NetworkChainAttribution`이 맡는다(계약13) |
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
  계정·주소 유스케이스는 `AccountOperations` 경계로 나눠 `fireblocks|local`은 `AccountService`와 `WalletProvisioningConfig`, `dfns`는
  `ConditionalOnDfnsProtocol`의 `DfnsClientConfig`(`bcm.dfns.*`·서명기·지갑 HTTP 어댑터)·`DfnsAccountConfig`·`DfnsAccountService`만 조립한다
  ([계약13](13-dfns-contracts.md#계정주소-api의-dfns-연결--구현)). 기동 차단 상태에서 구현·조립된 Dfns 슬라이스는 계정·주소·잔액,
  Admin 자산 등록 관문(`DfnsChainAssetResolver`), 웹훅 수신 프로토콜(`DfnsWebhookProtocol`은 모든 앱, HMAC 검증기는 Webhook 앱),
  그리고 웹훅 판단 워커(`DfnsWebhookDecisionConfig`·`DfnsWebhookDecisionTransaction` — **입금과 전송 알림 판단 모두**)이며
  출금 제출 유스케이스(`DfnsSubmissionConfig`), 발신 확정의 블록 좌표, 내부이체(계정 목적지 해소·수신측 중복 입금 방지)이며 거래 조회·Sweep·Admin 조회는 후속이다. **외부에서 기동 가능한 범위**는 여전히 `fireblocks|local`뿐이고 API·Webhook·BAT 전체 컨텍스트의
  `dfns` 기동 차단은 그대로다.
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
판단 트랜잭션은 `WebhookDecisionWork` 경계로 추상화해 제공자마다 하나만 조립한다 — 기존 `WebhookDecisionTransaction`은 `fireblocks`·`local`,
`DfnsWebhookDecisionTransaction`은 `dfns`에서만 만들고 워커·경보 처리는 경계 뒤의 벤더 어휘를 모른다([계약13](13-dfns-contracts.md#판단-워커-조립--구현)).
Dfns HMAC 검증기·`kind` envelope 해석은 [계약13](13-dfns-contracts.md#dfns-웹훅-수신-프로토콜--구현)대로 구현해 `dfns`에서만 조립한다(검증기는 Webhook 앱 한정). **입금·전송 알림·발신 이동 대조 판단이 모두 조립됐고** 이력 복구와 RBF 계열이 후속이다.
전송 사건(`wallet.transfer.*`)의 해석은 `WebhookTransactionParser`와 별개인 `NetworkTransferEventParser`로, 온체인 이동 사건
(`wallet.blockchainevent.detected`·`wallet.blockchain_event.transfer.included`)은 `NetworkChainEventParser`로 두었다 — 둘 다 벤더 관찰을 그대로 담고
업무 상태 번역·논리 사건 연결은 판단 워커의 몫이다. 알림 메타(`WebhookEnvelopeBase`)는 domain `VendorWebhookDelivery`로 공통이다.
계약과 한계는 [계약13](13-dfns-contracts.md#웹훅-전송-사건-관찰--구현)과 [온체인 이동](13-dfns-contracts.md#웹훅-온체인-이동-사건-관찰--구현)에 있다.

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

## 계정 모델 분리와 내부 생성 유스케이스 검증 (2026-09-14)

- V23은 기존 계정을 VAULT로 보존하고 LOGICAL의 vault ID는 NULL로 제한한다. 원천 프로토콜과 모델 불변을 DB에서도 검사한다.
  논리 계정 예약은 독립 커밋하며 동시 요청이 같은 `(유형, ref)` 계정으로 합류한다.
- 새 모델·생성 서비스 테스트의 구현 부재 실패를 먼저 확인했다. 논리 계정의 vault 대사 거절도 실패를 확인한 뒤 구현했다.
  기존 Account 조회 SQL의 신규 컬럼 누락은 구현을 수정했고 기존 테스트의 assertion을 변경하지 않았다.
- 신규 23건은 모델 제약, PostgreSQL 동시 예약/롤백, 기존 계정·미완료 의도의 V23 업그레이드 보존,
  생성 호출 권한 1개, 응답 유실 뒤 POST 없이 조회, 증적 보관 실패/해시 불일치, known ID·cursor 재개를 검증한다.
  서비스의 벤더/증적/원장 대역 테스트와 실제 PostgreSQL 원장 테스트는 별도이며 서비스+DB 결합 검증은 후속이다.
- 선택 회귀: domain 17 · application 12 · persistence 47 · API 60 · BAT 44 · Webhook 7 = **187건**,
  실패/오류/skip 0. 계정·전송·조회·Sweep·allowance 회수·vault 대사와 실제 로컬 Stub→Anvil 이체를 포함한다.
  API/BAT production·test 컴파일도 통과했다. 전체 ktlint는 Sweep 줄바꿈 수정 후 별도 실행에서 `BUILD SUCCESSFUL in 1s`로 통과했다.
- 테스트 로그는 `/tmp/bcm-logical-wallet-verified.log`, 최종 스타일 검사는 `/tmp/bcm-logical-ktlint-final.log`다.
  전자는 테스트 성공 후 스타일 실패를 포함하며 후자의 성공으로 해당 실패를 해소했다. V1~22와 공개 API 계약은 수정하지 않았다.
- 내부 서비스는 기본 실행 빈/공개 API에 연결하지 않는다. 증적은 원문 바이트를 전달하고 저장 hash를 확인하는 포트까지만 구현했다.
  실제 보호 원문 저장소·Dfns 어댑터·자산 수신 주소 연결·실벤더 호출·운영 적용·독립 converge는 미수행이며 Dfns 기동 차단을 유지한다.

## 응답 증적 보관과 서비스·DB 결합 검증 (2026-09-15)

- [03의 V24](03-bcm-db.md#v24-네트워크-지갑-응답-증적-보관--물리-저장-계약)에 원문 접근권한·보관/조회·무결성·실패 전파 계약을 먼저 고정했다.
  `bcm_ntwk_wlt_evdc_l`은 응답 바이트를 BYTEA로 보관하고 길이·SHA-256을 DB가 계산해 CHECK로 강제하며 UPDATE/DELETE를 trigger로 거절한다.
  `NetworkWalletEvidenceJdbcAdapter`가 저장 포트와 메타데이터 조회(`NetworkWalletEvidenceArchive`)를 구현하고 원문은 반환하지 않는다.
- 저장 어댑터 부재 컴파일 실패를 확인한 뒤 구현했다. 새 PostgreSQL 테스트 8건은 바이트 그대로 보관·DB 계산 hash 반환·참조 조회,
  빈 본문, 수정/삭제 거절, 본문과 다른 hash/길이 거절, 다른 scope/원천 거절, 호출자 롤백 뒤 증적 보존, 참조 형식 검사, PK/FK/CHECK/감사 컬럼을 검증한다.
- 내부 생성 서비스와 실제 원장·증적 저장소·논리 계정 저장소를 별도 Dfns 데이터셋(`ProviderOriginTestDatabase.createDfns`)에서 결합한 슬라이스 6건은
  최초 생성 1 POST와 CREATE 증적 hash 일치, 응답 유실 뒤 새 인스턴스의 조회 회수(POST 0), 증적 저장 실패 전파와 이후 조회 재개,
  원장 저장 실패에서 증적 보존·페이지/연결 롤백·재개, 실제 DB 권한 경쟁의 create 1회, 완료 재요청의 외부 호출/증적 추가 0을 검증한다.
  API 조립 지점 대신 테스트 전용 앵커 설정을 쓰며 기본 실행 빈·공개 API에는 여전히 연결하지 않는다. `BCM_PROVIDER=dfns` 기동 차단은 유지한다.
- 벤더 포트는 내부 대역이고 응답 바이트는 BCM 내부 표기다. 보관 대상 작업과 응답 schema는 [계약13](13-dfns-contracts.md#응답-증적-보관-계약--구현)에서
  공식 OpenAPI 1.1018.3으로 기록했으며 실제 Baseline 응답·서명 원문 대조는 운영 연결 전 수용 항목이다.
- 선택 회귀: domain 105 · application 21 · persistence(wallet·account·provider) 57 · API(Bootstrap·wallet·Architecture·ProviderStartup) 32 = **215건**,
  실패/오류/skip 0. Bootstrap의 정확한 테이블 목록에 신규 1개만 추가했고 기존 assertion을 완화하지 않았다. 변경 모듈 전체 ktlintCheck 통과.
- bcm-api 테스트 의존에 Boot 관리 `spring-boot-starter-data-jdbc-test`를 추가했다(persistence가 이미 사용하는 좌표, 별도 커밋·lockfile 갱신).
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 bd0066f..ed7fe82, design-sync→code-reviewer 순차)**:
  Critical 1건 — 저장 SQL의 `RETURNING encode(sha256(body), 'hex')`가 runbook의 앱 역할(body SELECT 없음)에서는 실행되지 않아 증적 저장이 거절된다.
  Major 1건 — 동시 요청 테스트가 실행 순서에 의존한다(한 요청이 POST 대기 중 다른 요청이 조회 복구로 revision을 올리면 첫 요청이 충돌로 끝난다).
  그 외 V24 SQL↔03, 계약13↔어댑터, 공식 OpenAPI 버전/해시, 내부 링크 120개, OpenAPI 생성물 3개, Bootstrap 변경 사유, 의존성 실존·분리는 정합으로 확인됐다.
- **반영**: RETURNING을 CHECK로 본문과 대조된 `body_hash` 컬럼으로 바꿔 저장·조회 SQL이 `body`를 참조하지 않게 했다.
  runbook과 같은 GRANT만 가진 역할로 저장·메타데이터 조회 성공과 `body` SELECT·UPDATE/DELETE 거절을 검증하는 PostgreSQL 테스트를 추가했다(증적 9건).
  동시 요청 테스트는 예약 직후 barrier로 두 요청이 같은 PREPARED를 읽는 경쟁(create 1회)과, POST 응답 게이트로 대기 중 다른 요청이 조회로 완료한 뒤
  늦은 생성 응답이 CREATE 증적만 남기고 `ConflictException`으로 원장을 덮어쓰지 못하는 경로(계약13 4항)로 나눠 결정적으로 검증한다(결합 7건).
  재실행: persistence wallet 25건(증적 9·원장 16) · API 18건(결합 7·Bootstrap 11) 실패/오류/skip 0, 변경 모듈 ktlintCheck 통과.
- **독립 converge 2차(같은 Codex reviewer 세션, 수정 delta 5624e3b·85816c0, design-sync→code-reviewer 순차)**: 이전 Critical 1건·Major 1건 해소 확인,
  신규 Critical/Major/Minor 없음. `RETURNING body_hash`가 기존 DB 계산·CHECK 계약을 유지하고, 제한 역할 테스트의 GRANT가 runbook 양식과 같으며,
  두 동시 요청 테스트가 barrier/게이트로 순서에 의존하지 않음을 확인했다. 검토 기준 commit은 85816c0이다. 실벤더 호출·운영 DB 적용·배포는 미수행이다.

## Dfns 인증·지갑 HTTP 어댑터와 보류·충돌 계약 검증 (2026-09-15)

- 계약은 [계약13](13-dfns-contracts.md#보류충돌-http-계약--구현)에 먼저 고정했다. Pending은 새 `PROVISIONING_PENDING`(503, `Retry-After`/`retryAfterSeconds`),
  Conflict는 기존 `CONFLICT`다. `CREATION_RETRY_LATER`는 Fireblocks 시간 기반 재생성 대기라 재사용하지 않았다. OpenAPI 0.11.0의 에러 표·주소 batch 설명·
  `retryAfterSeconds` 설명을 갱신하고 생성물(spec.js·api.md·api.html)을 재생성했다. 별도 endpoint·가짜 성공 응답은 추가하지 않았다.
- domain `NetworkWalletCreationIntent.requireCompleted`·`ProvisioningPendingException`, API `ErrorCode`(11종)·resolver·handler·주소 batch 항목 `retryAfterSeconds`를 구현했다.
  수용 테스트: domain 5건, `ApiExceptionHandlerTest` 1건(503·헤더·내부 사유 미노출), `AccountSpecComplianceTest` 1건(항목 `PROVISIONING_PENDING`/`CONFLICT`가 스펙 schema와 일치), `ErrorCodeTest` 갱신.
- `infra/client`의 `dfns` 패키지: `DfnsProperties`·`DfnsCredentialSigner`·`DfnsUserActionClient`·`DfnsNetworkWalletClient`(+ `DfnsHttp`·`DfnsRestClientFactory`).
  근거는 공식 OpenAPI 1.1018.3과 공식 Credentials data·Signing flows 문서이며 다운로드 해시를 계약13에 기록했다. 어떤 실행 모듈도 조립하지 않으며 `BCM_PROVIDER=dfns` 기동 차단을 유지한다.
- 계약 테스트 22건(MockRestServiceServer, 실호출 0; 리뷰 반영으로 5건 추가): clientData 형식·EC/RSA/Ed25519 서명의 공개키 검증, init 본문(`userActionPayload`=실제 본문 바이트·`Api`·경로/메서드),
  `/auth/action` 본문(kind Key·credId·`algorithm` 미전송)과 서명 검증, `POST /wallets`의 `X-DFNS-USERACTION`·같은 바이트 본문, 원문 바이트 보존, 저장 requestHash 불일치 시 호출 0,
  allowCredentials 불일치·인증 단계 오류/결손 시 생성 호출 0, 생성 4xx 상태 전파, 필수 필드 결손 거절, 단건 조회 404 원문 보존/오류/ID 불일치, 목록 query·externalId 필터·nextPageToken,
  custodial/위임/Vault/status 소유 판정, 목록 오류·items 결손 전파, 다른 원천 거절, 생성 본문 두 필드·externalId 100자 제한.
- 결합 슬라이스 `DfnsNetworkWalletEvidenceIntegrationTest` 3건(HTTP mock + 실제 PostgreSQL 원장·V24): 생성 응답 바이트가 그대로 보관되고 DB 계산 해시=원장 페이지 해시,
  생성 응답 유실 뒤 목록 페이지 원문 보관과 externalId 필터 후보로 완료, known ID 404 원문 보관과 같은 ID 대기(`NOT_OBSERVED`). 같은 모듈의 두 슬라이스가 한 Dfns 데이터셋을 공유하도록
  `DfnsDatasetTestSupport`로 데이터셋 생성을 JVM당 한 번으로 묶었다(기존 회수 슬라이스 7건은 assertion 변경 없음).
- 선택 회귀: domain 110 · application 21 · persistence(wallet·account·provider) 58 · client(fireblocks·dfns) 104 · API(wallet·web·account·Bootstrap·Architecture·ProviderStartup) 68 = **361건**, 실패/오류/skip 0. 변경 모듈(domain·client·bcm-api) ktlintCheck 통과. 실벤더 호출·운영 적용·조건부 조립·기동 차단 해제는 미수행이다.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 080e8c1..818b9e5, design-sync→code-reviewer 순차)**:
  design-sync Major 1(오류 응답 바이트가 V24에 보관되기 전 유실 — 보관 계약 불일치)·Minor 1(계약13의 HTTP 매핑 진행 상태 문구 모순).
  code-reviewer Critical 3 — ① 2xx 아닌 응답·해석 불가 응답의 수신 바이트가 증적 보관 전에 예외로 사라짐,
  ② `nextPageToken`이 있어도 문자열이 아니면 next=null이 되어 원장이 조회 완료로 판단, ③ `signingKey` 결손을 위임 없음으로 취급해 ORGANIZATION 승인.
  Major 3 — ④ 미리 인코딩한 query를 `uri(String)`에 넘겨 페이지 토큰 이중 인코딩(Spring 7.0.9 재현), ⑤ 명세 필수 `allowCredentials.key` 결손 시 검사 생략,
  ⑥ `exchange(..., false)`로 응답을 닫지 않음. 오류 표/ErrorCode, 공식 문서 해시, 생성물 신선도, 링크 80개, 테스트 개변 없음, 신규 의존성 없음은 정합으로 확인됐다.
- **반영**: `VendorApiException`에 수신 바이트(`responseBody()`)를 추가하고 서비스가 성공·실패 응답 모두 같은 작업 종류로 먼저 보관한 뒤 오류를 전파한다
  (원장 페이지/cursor 미전진, 보관 실패는 suppressed로 함께 전파). 어댑터는 필수 `id`·`network`·`signingKey.id`·`status`·`custodial` 형식을 검사하고
  선택 필드는 없거나 문자열이어야 하며 `nextPageToken`의 다른 형식은 오류다. `allowCredentials.key` 배열이 없으면 서명하지 않는다.
  URI는 UriBuilder 변수로 한 번만 인코딩하고 `exchange`는 자동 닫기를 사용한다. 계약13(7항·증적 표·정규화 규칙·인증 절), 03 V24 문구, 계약13:222를 갱신했다.
  재실행: domain 110 · application 23 · persistence 58 · client 107 · API 68 = **366건**, 실패/오류/skip 0. 변경 모듈(domain·application·client·bcm-api) ktlintCheck 통과.
- **독립 converge 2차(같은 Codex reviewer 세션, 수정 delta 818b9e5..fce8254, design-sync→code-reviewer 순차)**: 이전 Critical②·Major④⑤⑥·Minor 해소,
  Critical①은 부분 해소(challenge 형식 오류가 원문 없는 일반 예외로 전파), Critical③은 해당 경로 해소. 신규 Critical 2 — 목록 항목이 객체가 아니거나 `externalId`가
  문자열이 아니면 필터에서 버려져 나머지로 완료 연결될 수 있음, 서명 입력이 될 수 없는 challenge 응답이 증적 보관을 건너뜀. Major 1 — 명세에 minLength가 없는
  `address`·`externalId`의 빈 문자열을 오류로 바꿔 기존 빈 주소→주소 대기 계약이 회귀. design-sync는 같은 세 항목을 계약13과의 불일치(Major 3)로 보고했다.
- **반영**: 목록 항목은 필터 전에 객체·`externalId` 형식을 검사하고 통과한 항목만 `externalId == correlationId`로 좁힌다. challenge 형식은 원문을 가진 인증 단계에서
  검사해 수신 바이트를 담은 `VendorApiException`으로 전파한다. 선택 문자열 규칙을 나눠 `address`·`externalId`의 빈 문자열은 null(주소 대기·상관관계 없음),
  minLength 1인 `delegatedTo`·`vaultId`와 재요청 토큰 `nextPageToken`의 빈 문자열은 오류로 둔다. 계약13의 정규화·인증 절을 같은 규칙으로 갱신했다.
  재실행: client 109(dfns 22) · API wallet 10 · 변경 모듈 ktlintCheck 통과. domain·application·persistence·API 나머지 코드는 1차 반영 뒤 변경이 없다.
- **독립 converge 3차(같은 Codex reviewer 세션, 수정 delta fce8254..07ebbdd, design-sync→code-reviewer 순차)**: 이전 Critical 2건·Major 1건 해소 확인,
  신규 Critical/Major 없음. 문서 Minor 1건(계약13의 계약 테스트 집계 20건 → 실제 22건)만 남아 계약13을 바로잡았다. 검토 기준 commit은 07ebbdd이다.
  실벤더 호출·운영 적용·조건부 조립·`BCM_PROVIDER=dfns` 기동 차단 해제·push는 미수행이다.

## Dfns 계정·주소 API 연결과 조건부 조립 검증 (2026-09-15)

- 계약은 [계약13](13-dfns-contracts.md#계정주소-api의-dfns-연결--구현)에 먼저 고정했다. `AccountOperations`로 제공자별 유스케이스를 나누고
  `DfnsAccountService`가 논리 계정 등록, 주소 모델·매핑 선검증, `(origin, accountId, network)` 지갑 의도 예약, Ready 지갑 주소의 `bcm_addr_m` 저장,
  `PROVISIONING_PENDING`/`CONFLICT` 번역, 잔액 조회 422 거절을 구현한다. `bcm.dfns.account-address-networks`·`provisioning-retry-after-seconds`를 추가했다.
  OpenAPI 0.11.1은 balancesOf에 Dfns 422와 주소 발급 설명을 추가했다(생성물 재생성).
- 조립: `ConditionalOnDfnsProtocol`, `DfnsClientConfig`(설정 검증은 빈 생성 시점), `DfnsAccountConfig`. `AccountService`·`WalletProvisioningConfig`는
  `ConditionalOnFireblocksProtocol`로 한정했다. `ProviderConfiguration`의 `dfns` 기동 차단은 유지한다(차단 해제는 Baseline 수용 뒤 사용자 결정).
- 검증: `DfnsAccountServiceTest` 단위 11건(멱등 논리 계정, 고정 본문 해시/벤더 network 의도, 기존 주소 재사용, 보류/충돌 번역, 미지원 모델·매핑 선거절,
  VAULT/없는 계정 거절, batch의 전체 400 vs 항목 오류, 같은 네트워크 다른 토큰 합류, 저장 경합·원장 불일치, 잔액 422, 비Dfns 원천 거절).
  `DfnsAccountAssemblyIntegrationTest` 5건(`bcm.provider=dfns` 슬라이스 컨텍스트 + 실제 PostgreSQL Dfns 데이터셋 + 공식 명세 형태의 로컬 HTTP 서버):
  Dfns 유스케이스/어댑터만 조립되고 Fireblocks 계정 서비스·생성 정책 빈 없음, 논리 계정 멱등, 첫 발급의 init→action→POST /wallets와 `X-DFNS-USERACTION`,
  같은 네트워크 다른 토큰·재요청의 외부 호출 0, 주소 미준비 → `ADDRESS_NOT_READY` 보류(설정 재시도 초) → 단건 조회로 완료, 미지원 네트워크 혼합 400, 잔액 422.
  `BootstrapIntegrationTest`에 fireblocks 컨텍스트의 Dfns 빈 0·`AccountOperations`=`AccountService` 검증을 추가했다.
- 선택 회귀: domain 110 · application 23 · persistence(wallet·account·provider) 58 · client 109 · API(account 유스케이스·wallet·web·account·config·Bootstrap·Architecture·ProviderStartup·ProviderOriginStartup) 129 = **429건**, 실패/오류/skip 0. 변경 모듈 ktlintCheck 통과. 실벤더 호출·운영 적용·기동 차단 해제·push는 미수행이다.
- **후속**: tag/memo 체인 주소 모델, 거래·Sweep·Admin·웹훅 조립. 자산 매핑 등록 경로와 잔액 계약은 아래 절로 구현했다.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 fd793f2..3458739, design-sync→code-reviewer 순차)**:
  design-sync Major 1(03의 `bcm_addr_m` 자산 매핑 snapshot 연결 요구와 구현의 저장 방식 불일치)·Minor 1(13·03·09·DfnsProperties의 구현 상태 문구 미갱신).
  code-reviewer Critical 2 — ① 유스케이스(`DfnsAccountService`)가 infra 설정(`DfnsProperties`)과 HTTP 어댑터 정적 함수에 직접 의존,
  ② account 피처가 wallet Repository(`findWallet`)를 직접 조회. Major 1 — 조립 테스트가 Fireblocks 조립부를 등록하지 않아 조건부 제외를 검증하지 못함.
  결정적 seed·주소 PK 경합 처리·주소 모델 허용 목록·잔액 422·기존 테스트 개변 없음·신규 의존성 없음은 정합으로 확인됐다.
- **반영**: 도메인 출력 포트 `NetworkWalletSubmissionPort`(제출 snapshot 생성, Dfns 어댑터가 구현)와 도메인 정책 `NetworkWalletAddressPolicy`
  (조립부가 `bcm.dfns.*`에서 생성)를 두어 유스케이스의 infra 의존을 제거했다. 완료 지갑 검증은 지갑 피처 서비스 `NetworkWalletProvisioningService.provisionedWallet`으로
  옮겨 account 피처가 지갑 원장을 읽지 않는다(ArchitectureTest 검토 목록에 `DfnsAccountService` 추가). `AccountService`·`DfnsAccountService`는 애노테이션 대신
  `FireblocksAccountConfig`·`DfnsAccountConfig`가 제공자별로 등록한다. 조립 테스트는 Fireblocks 조립부도 함께 등록해 제외를 검증한다.
  03의 `bcm_addr_m` 행을 "현재 지갑 주소만 저장, wallet FK·발급 시점 locator 컬럼은 후속 DDL 결정"으로 명확히 하고 13·09·DfnsProperties 문구를 갱신했다.
  재실행: domain 112 · application 24 · client 109 · API 129 = **374건**(persistence 58은 변경 없음), 실패/오류/skip 0. 변경 모듈 ktlintCheck 통과.
- **독립 converge 2차(같은 Codex reviewer 세션, 수정 delta 3458739..d23bef0, design-sync→code-reviewer 순차)**: 이전 Critical 2·Major 1·design-sync Major 1·Minor 1 해소 확인,
  신규 Critical/Major/Minor 없음. 결정적 seed·제출 snapshot·주소 경합·Pending/Conflict·잔액 422·전체 Dfns 기동 차단이 유지되고 기존 테스트 변경은 책임 이동에 따른 재배치임을 확인했다.
  검토 기준 commit은 d23bef0이다. 실벤더 호출·운영 적용·기동 차단 해제·push는 미수행이다.

## Dfns 데이터셋 자산 매핑 등록과 잔액 계약 검증 (2026-09-15)

- 계약은 [계약13](13-dfns-contracts.md#dfns-데이터셋의-자산-매핑--구현)·[잔액 계약](13-dfns-contracts.md#잔액-계약--구현)에 먼저 고정하고 07(Dfns 데이터셋의 등록)·03(기존 자산 테이블 사용 규칙·후속 DDL)·09를 갱신했다.
  Admin 등록의 벤더 재해소를 도메인 포트 `ChainAssetResolver`로 분리했다 — `FireblocksChainAssetResolver`(기존 카탈로그 페이징·assetId/주소 대조 이동, `FireblocksAssetConfig`)와
  `DfnsChainAssetResolver`(`DfnsClientConfig`; `bcm.dfns.networks`↔`bcm_blkc_m.vndr_blkc_id` 일치, EVM(`chain_id`) 모델만, 명세 EVM 주소 형식, Dfns 자산 키 `<Network>:Native|Erc20:<소문자 contract>`, 64자 길이).
  `VendorAssetMappingService`는 원천을 모르며 네트워크마다 관문을 한 번 부르고 항목별 실패를 index로 표시한다. Dfns 공개 명세에 카탈로그 API가 없고 채택 명세 Call Function 응답이 비어 있어
  온체인 대조는 수용 항목으로 남겼다(운영자의 발행사 공식 자료 대조).
- 잔액: `NetworkWalletAssetPort`(도메인)·`DfnsNetworkWalletClient.assets`(`GET /wallets/{walletId}/assets`, Bearer만)·`NetworkWalletAssetBalance.amount()`(최소 단위 정수+decimals → 소수 문자열)·
  `NetworkWalletProvisioningService.readyWallet`(원장 준비 지갑, account 피처가 지갑 Repository를 읽지 않음)·`DfnsAccountService.balancesOf`(발급 네트워크마다 한 번 관찰, 매핑 키 대조, 미보유 `"0"`, drift는 500).
  `VendorBalance`의 total·pending·frozen·lockedAmount를 nullable로 바꿨고(Fireblocks는 모두 채움) 공개 `AssetBalance.pending/locked`는 nullable이다.
- OpenAPI 0.12.0: `AssetBalance` pending/locked nullable, balancesOf의 Dfns 설명과 422 제거, `RegisterAssetMappingRequest.fireblocksAssetId` 선택(패턴 `^\S{1,64}$`), `AssetMapping`에 nullable `fireblocksAssetId`·`dfnsAssetKey`,
  등록 오퍼레이션 설명에 원천별 관문·오류 사유(생성물 재생성). Admin 응답은 `ProviderOrigin`의 프로토콜로 벤더 이름 필드 하나만 채운다.
- 검증: `DfnsChainAssetResolverTest` 6(키 생성·Fireblocks 필드 거절·binding 불일치·비EVM·주소 형식·길이), `DfnsNetworkWalletClientTest` +3(자산 정규화·kind 제외, 결손/형식 오류 14종, HTTP 오류·ID·원천 거절),
  `NetworkWalletAssetBalanceTest` 3, `NetworkWalletProvisioningServiceTest` +1(readyWallet), `VendorAssetMappingServiceTest` +2(Fireblocks id 필수, 중립 관문 저장·중복 자산 거절)와 기존 16건은 생성자만 교체,
  `DfnsAccountServiceTest` 잔액 3(한 번 관찰·미보유 0·빈 배열/404·drift·벤더 오류), `AccountSpecComplianceTest` +1(null pending/locked), `AdminAssetControllerDfnsOriginTest` 2, 기존 Admin 슬라이스에 원천 빈 추가,
  `DfnsAccountAssemblyIntegrationTest` 실제 PostgreSQL Dfns 데이터셋(seed `vndr_blkc_id=EthereumSepolia`)+로컬 HTTP: 조립(Dfns 관문·자산 포트만, Fireblocks 관문 없음), 잔액 1회 `GET /wallets/{id}/assets`·USDC 1.5/KRWK 0,
  등록 관문 저장·snapshot 1행·Fireblocks id/비EVM 거절. `ArchitectureTest` 검토 목록에 `VendorAssetMappingService`.
- 선택 회귀: domain 115 · application 25 · client 118 · persistence(wallet·account·asset) 68 · API(asset·account 유스케이스·account·AdminAsset·wallet·web·config·Architecture·Bootstrap·ProviderStartup) 158 = **484건**, 실패/오류/skip 0.
  변경 모듈 ktlintCheck 통과. `VendorAssetMapping`에 넣었던 길이 require는 기존 영속성 테스트(길이 결함은 데이터 오류)와 어긋나 제거하고 관문에서만 검사한다. 실벤더 호출·운영 적용·DDL 변경·`BCM_PROVIDER=dfns` 기동 차단 해제·push는 미수행이다.
- **후속**: tag/memo 체인 주소 모델(Solana 자산 locator·`vndr_ast_id` 확장 DDL), 거래·Sweep·Admin·웹훅의 Dfns 조립, 수용 항목(Call Function 응답 형식, 지갑 자산 목록의 단위/미보유 의미).
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 bb835d0..072549a, design-sync→code-reviewer 순차)**:
  design-sync Major 3 — ① 계약13 잔액 절이 최소 단위 변환·미보유 0을 확정으로 쓰는데 수용 표는 가정으로 둠, ② 설계에 없는 `decimals` 0..77 상한, ③ 응답 network를 BCM 코드로 되돌리는 fallback 때문에
  BCM 코드 문자열이 그대로 오면 불일치 검사를 통과. code-reviewer Critical 2 — C1 = ③(잘못된 network 응답이 0 잔액으로 반환될 수 있음), C2 = 잔액 단위·미보유 의미의 공개 근거 부재(추측 금지 기준);
  Major 2 — `decimals` 상한이 등록 관문과 어긋나 요청하지 않은 자산 때문에 조회가 실패할 수 있음, Fireblocks 필수 assetId 검사가 카탈로그 호출 뒤에 있고 테스트가 외부 호출 0을 검증하지 않음;
  Minor 1 — 생성 api.md 예시가 `fireblocksAssetId`·`dfnsAssetKey`를 모두 채움. 자산 키·64자·DBA seed·nullable 응답·조건부 조립·테스트 재배치는 정합으로 확인됐다.
- **반영**: assets 응답 `network` 원문을 scope의 설정 매핑값과 직접 대조하고 fallback을 쓰지 않는다(BCM 코드·다른 Dfns network 응답 거절 테스트 추가). 공식 문서 페이지(Get Wallet Assets `.md`, 해시 계약13)와 현재 OpenAPI 2.0.54를 재확인해
  단위·미보유 서술이 없음을 계약13에 기록하고, 잔액 절을 "명세로 확인한 사실"과 "BCM 해석 규칙 — 수용 전"으로 나눠 정수 형식 검사의 한계(정수가 아닌 형식만 거절, 단위 정확성은 별도 수용)를 명시했다(OpenAPI balancesOf 설명도 같게).
  `decimals` 상한은 모델링한 자산 표준의 uint8/u8(255)로 근거를 두고 경계 테스트(255/256)를 추가했다. 관문 포트에 벤더 호출 없는 항목 선검사 `inspect`를 두어 단건·일괄(정상 ID와 누락 ID 혼합 포함) 모두 카탈로그 호출 전에 누락 항목의 index로 거절하고 테스트가 외부 호출 0을 검증한다.
  OpenAPI `AssetMapping`에 원천별 예시 객체를 두고 생성물을 재생성했다. 재실행: domain(vendor) 18 · client(dfns) 31 · API(asset·account·AdminAsset·wallet) 107, 실패 0. 변경 모듈 ktlintCheck 통과.
- **독립 converge 2차(같은 Codex reviewer 세션, 수정 delta 072549a..8c8893d, design-sync→code-reviewer 순차)**: Critical 2·design-sync ③·Minor 해소 확인, Critical 없음. 남은 Major 2 —
  ① 계약13·설계12가 정수 형식 검사로 단위 해석 오류까지 드러난다고 과장(정수 형식만 거절하며 단위 정확성은 판별 불가), ② Fireblocks 필수 assetId 선검사가 단건·전체 누락만 해당하고
  정상 ID와 누락 ID가 섞인 일괄 요청은 카탈로그를 호출.
- **반영**: 계약13·OpenAPI·설계12 문구를 "정수가 아닌 형식만 거절, 단위 정확성은 별도 수용"으로 고쳤다. 관문 포트에 벤더 호출 없는 항목 선검사 `inspect`를 추가해 유스케이스가 단건·일괄 모두
  카탈로그 호출 전에 index 순서로 거절한다(Fireblocks: assetId 누락, Dfns: 모든 검사). 혼합 일괄 요청이 index 1·`fireblocksAssetIdRequired`로 거절되고 외부 호출 0인 테스트를 추가했다.
  재실행: domain(vendor) 18 · client(dfns) 31 · API(asset·account·AdminAsset·wallet·Architecture) 119, 실패 0. 변경 모듈 ktlintCheck 통과.
- **독립 converge 3차(같은 Codex reviewer 세션, 수정 delta 8c8893d..f4e4e38, design-sync→code-reviewer 순차)**: 잔여 Major 2건 해소 확인, 신규 Critical/Major/Minor 없음.
  `inspect == null`을 등록 성공으로 취급하지 않고 이후 `resolveAll`을 수행하는 점, 기존 stub의 object 전환이 assertion 동일임을 확인했다. 검토 기준 commit은 f4e4e38이다.
  실벤더 호출·운영 적용·기동 차단 해제·push는 미수행이다.

## Dfns Solana 자산 모델과 계정·자산 모델 컬럼 검증 (2026-09-16)

- 계약은 [계약13](13-dfns-contracts.md#solana-수신-주소와-자산-모델--구현)·[03 V25](03-bcm-db.md#v25-dfns-계정자산-모델과-자산-키-길이--물리-저장-계약)·07("Dfns 데이터셋의 등록")·09에 먼저 고정했다.
  V25: `bcm_blkc_m.chain_mdl_dvcd`(EVM/SOLANA, CHECK, Dfns seed만 채움·동기화가 덮지 않음)와 `bcm_vndr_ast_m.vndr_ast_id` VARCHAR(128). 도메인 `ChainModel`·`TokenStandard`(SPL/SPL_2022),
  `VendorBlockchainCatalog.chainModel`, `ChainAssetLocator.tokenStandard`, 등록 명령·Admin 요청 `tokenStandard`(OpenAPI 0.12.1, 재생성).
- Dfns 관문은 행의 모델로 분기한다 — 없으면 `assetModelUnsupported`, EVM은 기존 규칙(+표준 지정 시 `tokenStandardNotApplicable`), SOLANA는 네이티브 SOL 또는 mint(base58 32바이트 형식 검사 `mintAddressInvalid`,
  운영자 명시 표준 필수 `tokenStandardRequired`)로 `<Network>:Spl|Spl2022:<mint>` 키를 만든다. Fireblocks 관문은 표준 지정을 카탈로그 호출 전에 거절한다. Solana 수신 주소는 지갑 owner 주소이며
  ATA는 계산·저장하지 않고, `account-address-networks` 등록은 Baseline 수용 뒤 운영 결정으로 남겼다(계약13 수용 표 2행 추가).
- 검증: `DfnsChainAssetResolverTest` 10(Solana 네이티브/SPL/Token-2022 키, 표준 누락·mint 형식·EVM에 표준 거절, 모델 없는 행 거절, base58 32바이트 판정), `VendorBlockchainCatalogPersistenceTest` +1(모델 왕복·동기화가 덮지 않음·CHECK),
  `VendorAssetMappingPersistenceTest` 길이 결함 경계 65→129(V25 컬럼 확장에 따른 상수 변경, assertion 동일), `VendorAssetMappingServiceTest` +1(Fireblocks 표준 거절),
  `AdminAssetControllerDfnsOriginTest` +1(tokenStandard 전달·enum 밖 400), `DfnsAccountAssemblyIntegrationTest`(seed에 모델 컬럼, Solana mint Token-2022 등록 성공·표준 누락 거절).
- 선택 회귀: domain 115 · application 25 · client 35(dfns) · persistence(asset·wallet·account·migration) 73 · API(asset·account 유스케이스·account·AdminAsset·wallet·web·config·Architecture·Bootstrap) 148, 실패 0.
  전체 모듈 compileKotlin/compileTestKotlin·변경 모듈 ktlintCheck 통과. 실벤더 호출·운영 DB 적용·기동 차단 해제·push는 미수행이다.
- **후속**: 거래·Sweep·Admin·웹훅의 Dfns 조립(그 뒤 `BCM_PROVIDER=dfns` 기동 차단 해제 결정), 수용 항목(Call Function 응답 형식, 지갑 자산 목록 단위/미보유, Solana owner 주소 수신·비ATA 계정·rent).
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 820223f..c1b913a, design-sync→code-reviewer 순차)**: Critical 1 — 잔액 관찰 경로의 `Spl`/`Spl2022` mint가 등록 경로와 달리
  base58 32바이트 검사를 거치지 않아 손상된 응답이 유효 등록 키와 불일치하면 미보유 0으로 축소됨(에러 억제). Major 2 — 생성 api.md의 대표 요청이 Fireblocks 예시에 `tokenStandard`를 합쳐 구현이 거절하는 요청,
  07·13·DfnsProperties가 Solana owner 주소 수신·token account 합계를 확정처럼 서술(같은 정본의 수용 표와 불일치). V25·chainModel 영속·모델별 오류·OpenAPI 필드·테스트 분리는 정합으로 확인됐다.
- **반영**: `DfnsAssetKeys.of`가 `Spl`/`Spl2022` locator를 등록과 같은 `spl()`(base58 32바이트)로 만들고 형식 오류는 수신 바이트를 담은 `VendorApiException`으로 전파한다(잘못된 base58·33바이트·mint 누락 응답 거절 테스트 3건 추가).
  OpenAPI `RegisterAssetMappingRequest`·`BulkRegisterAssetMappingsRequest`에 원천별 유효 예시 객체를 두고 생성물을 재생성했다. 07·13(지갑 주소·잔액 행)·DfnsProperties 주석을 "명세 사실 / BCM 규칙 / 수용 전 가정"으로 나눠 표현했다.
  재실행: client(dfns) 35 · API(AdminAsset·wallet·account 유스케이스) 72, 실패 0. ktlintCheck 통과.
- **독립 converge 2차(같은 Codex reviewer 세션, 수정 delta c1b913a..51e4b3c, design-sync→code-reviewer 순차)**: Critical 1·Major 2 해소 확인, Critical/Major 없음. Minor 1 — OpenAPI 설명의 Dfns 예시가 축약 주소라
  "유효 요청"으로 소개하기에 부정확. **반영**: 설명 예시를 그대로 보낼 수 있는 전체 주소로 바꾸고 생성물을 재생성했다(문서만, 코드 변경 없음).
- **독립 converge 3차(같은 Codex reviewer 세션, 문서 delta 51e4b3c..151f3e1)**: 이전 Minor 해소 확인, 신규 Critical/Major/Minor 없음. 검토 기준 commit은 151f3e1이다.
  실벤더 호출·운영 DB 적용·기동 차단 해제·push는 미수행이다.

## Dfns 웹훅 수신 프로토콜 검증 (2026-09-16)

- 계약은 [계약13](13-dfns-contracts.md#dfns-웹훅-수신-프로토콜--구현)에 먼저 고정했다(공식 가이드 Webhooks `.md` 해시·명세 `WebhookEvent`). `DfnsWebhookSignatureVerifier`는 `sha256=<hex>` HMAC-SHA256을
  **수신 바이트**로 계산해 상수 시간 비교하고 `bcm.dfns.webhook-secrets`(env, 회전용 복수)를 순서대로 대조하며 `timestampSent`(정수 필수)가 `webhook-replay-tolerance-seconds`(기본 300) 밖이면 거절한다.
  가이드 예제의 재직렬화 서명과 원문 검증의 관계는 수용 항목으로 남겼고 실패 시 다른 입력으로 재시도하지 않는다. `DfnsWebhookProtocol`은 `id`·`kind`만 envelope로 읽고 형식 미정의 `data`에서 거래 ID를 추정하지 않는다(`vendorTransactionId=null`).
- 조립: `DfnsClientConfig`가 `WebhookProtocol`은 모든 앱에, HMAC 검증기는 `bcm.webhook.ingestion.enabled=true`(bcm-webhook application.yaml 고정)에서만 만들고 secret 누락은 조립에서 실패한다. API/BAT는 secret 없이 기동한다.
  Dfns `kind` 상태 번역·`WebhookTransactionParser`·`VendorStatusTranslator`는 미구현이라 Webhook 앱의 Dfns 판단 워커는 조립되지 않으며 `BCM_PROVIDER=dfns` 전체 기동 차단은 유지한다.
- 검증: `DfnsWebhookSignatureVerifierTest` 5(현재/이전 secret·대소문자 hex, 다른 secret·변조·재직렬화 사본 거절, 헤더 형식 7종, timestampSent 결손/형식/경계 ±300, 생성자 요구),
  `DfnsWebhookProtocolTest` 2, `DfnsClientConfigTest` 2(API 조립엔 검증기 없음, 수신 조립은 secret 필수), `DfnsWebhookIngestionTest`(bcm-webhook) 2(실제 검증기·envelope + 공통 컨트롤러/서비스: 200 적재 원문·해시·서명 보존, 헤더 없음/중복/변조/오래된 timestamp 401).
- 선택 회귀: client(dfns·config) 63 · webhook(Dfns 수신·경계·수신 서비스·ProviderStartup) 16, 실패 0. 변경 모듈 ktlintCheck 통과. 실벤더 호출·실제 서명 원문 수용·기동 차단 해제·push는 미수행이다.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 e78a9e1..b3fdf58, design-sync→code-reviewer 순차)**: Critical 2 — ① `timestampSent` 양수 제약 미구현·`abs(now − sent)`의 Long overflow로 극단 음수값이
  허용 오차 검사를 우회, ② 테스트에 고정 secret·토큰 문자열(CLAUDE.md 0절 "테스트도 env 참조만"). design-sync Major = ①, Minor 1 — 설계12 기동 계약 절이 Dfns 조립을 계정·주소로만 서술해 신규 웹훅 조립 기록과 충돌.
  원문 바이트 검증·secret 순서 대조·재시도 금지·envelope 규칙·판단 워커 미조립·기동 차단 유지는 정합으로 확인됐다.
- **반영**: 양수 검사를 먼저 하고 감산 없이 `subtractExact/addExact` 상·하한으로 비교한다(0·음수·`Long.MIN_VALUE`·`Long.MIN_VALUE+now`·`Long.MAX_VALUE` 거절 테스트 추가). 테스트 secret·토큰은 실행마다
  `SecureRandom`으로 생성해 소스에 고정값을 두지 않는다. 설계12 기동 계약 절을 "차단 상태에서 구현·조립된 슬라이스"와 "외부 기동 가능 범위"로 나눠 갱신했다. 재실행: client 63 · webhook 16, 실패 0, ktlintCheck 통과.
- **독립 converge 2차(같은 Codex reviewer 세션, 수정 delta b3fdf58..1d20198, design-sync→code-reviewer 순차)**: 이전 Critical 2·Major 1·Minor 1 해소 확인, 신규 Critical/Major/Minor 없음. 검토 기준 commit은 1d20198이다.
  실벤더 호출·실제 서명 원문 수용·기동 차단 해제·push는 미수행이다.

## Dfns 전송 제출·조회 어댑터 검증 (2026-09-16)

- 계약은 [계약13](13-dfns-contracts.md#전송-제출조회-계약--구현)에 먼저 고정했다(채택 명세 `POST/GET /wallets/{id}/transfers`와 공식 Idempotency 문서 해시).
  도메인 포트 `NetworkTransferPort`·`NetworkTransferRequest`(제출 키 ≤50자·금액 최소 단위 정수)·`NetworkTransferStatus`(명세 여섯 값, 종결/체인 제출 여부만 판단)·
  `NetworkTransferSubmission`(Accepted/Conflict)를 추가하고 `DfnsNetworkTransferClient`가 구현한다. Fireblocks의 vault 중심 `VendorTransactionPort`는 그대로 둔다.
- 어댑터: 등록 자산 키를 되돌려(`DfnsAssetKeys.parse`, 생성 규칙과 같은 검사) `kind`·locator를 만들고 `to`·`amount`·`externalId`만 보낸다. 수수료·대납·Travel Rule·memo는 보내지 않는다.
  제출은 지갑 ID가 들어간 실제 경로로 사용자 행위 서명을 거치고, 공식 표식(`error.details.duplicate`)이 있는 409만 조회 없이 `Conflict`(수신 바이트·duplicate ID)로 돌려주며 자동 재제출하지 않는다.
  표식 없는 409는 원인을 단정하지 않고 벤더 오류로 전파한다. 응답은 명세 필수 필드(`id` 형식·`requester.userId`·`metadata`·`requestBody.to`/`amount`·UTC `dateRequested`)를 검사하고
  제출 응답은 `walletId`·`network`·자산 키·목적지·금액·되돌아온 `externalId`가 모두 요청과 같아야 정규화한다. 조회 404는 미관찰(null)이고 `Confirmed`를 `FINALIZED`로 번역하지 않는다.
- **조립하지 않는다** — 실행 빈으로 등록하지 않는 내부 대역이며 제출 원장(`bcm_sbmt_l`)·출금/내부이체 유스케이스·Sweep·정책 승인(`Pending`) 흐름·대체 제출·전송 응답 증적은 후속이다.
  `BCM_PROVIDER=dfns` 전체 기동 차단도 그대로다.
- 검증: `NetworkTransferContractTest` 4(상태 원어 대응·종결/제출 집합, 제출 키 50자·금액 형식, 식별자 공백, 충돌 바이트 사본),
  `DfnsNetworkTransferClientTest` 6(서명 경로·본문 필드 정확 일치·정규화, Solana `Spl2022`/네이티브 본문, 409 충돌·duplicate ID, 멱등 표식 없는 409·403 전파, 응답 불일치·결손 25종, 조회 404/ID 불일치, 호출 전 거절 6종).
- 선택 회귀: domain 119 · client 138, 실패 0. 전체 모듈 compileKotlin/compileTestKotlin·변경 모듈 ktlintCheck 통과. DDL·공개 API 변경 없음. 실벤더 호출·운영 적용·push는 미수행이다.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 0ee8725..cd8f32e, design-sync→code-reviewer 순차)**: Critical 4 — ① 제출 성공 응답이 목적지·금액·제출 키와 결속되지 않고
  명세 필수 필드(`requester`·`metadata`·`id` 형식)를 검사하지 않음, ② `FAILED.broadcast=false`가 "시스템 실패 또는 **온체인 실행 실패**"라는 공식 의미와 충돌, ③ 근거 없이 모든 409를 멱등 충돌로 확정,
  ④ 새 테스트에 고정 인증 자격 문자열. Minor 2 — 금액의 선행 0 금지가 문서에 없음, 설계12 도입부의 "어댑터 없음" 문구가 현재 구현과 모순.
  자산 키 역파싱·같은 본문 바이트 서명·충돌 바이트 보존·실행 빈 미등록은 정합으로 확인됐다.
- **반영**: 제출 응답을 요청과 대조한다 — 자산 키·`to`·`amount`·`externalId`가 모두 같아야 하고 `id`는 명세 형식, `requester.userId`·`metadata`는 필수다. 관찰에 `destinationAddress`·`amountBaseUnits`를 넣어
  호출자도 사후 대조할 수 있게 했다. 상태의 제출 여부를 `onChainSubmitted: Boolean?`로 바꿔 `FAILED`는 `null`(상태만으로 판별 불가)이다. 409는 공식 문서가 규정한 `error.details.duplicate` 표식이 있을 때만
  충돌로 판정하고 나머지는 수신 바이트를 담은 벤더 오류로 전파한다. 테스트 자격값은 실행마다 생성한다. 금액의 선행 0 금지를 계약13에 BCM 정규화 규칙으로 적었고 설계12 도입부를 고쳤다.
  재실행: domain 119 · client 138, 실패 0, ktlintCheck 통과.
- **독립 converge 2차(같은 Codex reviewer 세션, 수정 delta cd8f32e..fb80e51, design-sync→code-reviewer 순차)**: 이전 Critical ②③④와 Minor 2건 해소 확인. Critical①은 부분 해소 —
  `dateRequested`를 비어 있지 않은 문자열로만 검사해 명세의 UTC ISO 8601 계약을 지키지 않음(Critical 1). Minor 2 — 어댑터 KDoc과 설계12 구현 요약이 이전 409 판정·좁은 대조 범위를 설명.
- **반영**: `dateRequested`를 `OffsetDateTime`으로 파싱하고 UTC 오프셋만 받는다(형식 오류·비UTC·공백 포함 값 거절 테스트 4종 추가). 어댑터 KDoc과 설계12 요약을 현재 판정·대조 범위로 갱신했다.
  재실행: domain 119 · client 138, 실패 0, ktlintCheck 통과.
- **독립 converge 3차(같은 Codex reviewer 세션, 수정 delta fb80e51..4be1251, design-sync→code-reviewer 순차)**: 잔여 Critical·Minor 해소 확인, 신규 Critical/Major 없음.
  Minor 1(검증 기록의 응답 결손 사례 수 12종 → 실제 25종)만 남아 위 검증 줄을 바로잡았다. 검토 기준 commit은 4be1251이다.
  실벤더 호출·운영 적용·실행 조립·기동 차단 해제·push는 미수행이다.

## Dfns 웹훅 전송 사건 관찰 검증 (2026-09-16)

- 계약은 [계약13](13-dfns-contracts.md#웹훅-전송-사건-관찰--구현)에 먼저 고정했다. 근거는 채택 명세 1.1018.3의 `webhooks` 항목(`wallet.transfer.*` 다섯)과
  `WebhookEnvelopeBase`·`TransferRequest` schema이며 현재 2.0.54도 같은 형식을 둔다. 수신 envelope(`WebhookEnvelopeBase`)와 조회 모델(`WebhookEvent`)이 서로 다른 schema라는 점을
  계약에 적고, 실제 Baseline이 보내는 본문의 일치를 수용 항목으로 남겼다.
- 도메인: 출력 포트 `NetworkTransferEventParser`와 `NetworkTransferEvent`(알림 ID·종류·발생 시각·전달 시도·`retryOf`·전송 관찰)·`NetworkTransferEventKind`(문서화된 다섯)를 추가했다.
  **종류는 상태를 결정하지 않는다** — 업무 상태는 `transferRequest.status`에서 읽고, 종류와 상태가 짝을 이룬다는 서술이 없으므로 모델이 강제하지 않는다.
- 어댑터: `DfnsNetworkTransferEventParser`는 전송이 아닌 종류를 null로 흘리고, 전송 종류인데 `data.transferRequest`가 없거나 형식이 다르면 `WebhookPayloadException`으로 올린다
  (자금 이동 신호를 "해석 불가"로 버리지 않는다). `network`는 `bcm.dfns.networks`의 역방향으로 BCM 코드를 되찾고 매핑 밖 네트워크는 거절한다.
  알림 메타는 수신 envelope schema대로 요구한다 — `id`는 형식(`whe-…`)까지, `date`는 UTC ISO 8601, `deliveryAttempt`는 필수·1 이상 정수이고 `retryOf`는 있으면 같은 형식이어야 한다.
  `timestampSent`는 앞선 서명 검증이 이미 필수로 검사하므로 파서가 다시 보지 않는다.
- 재사용: `TransferRequest` 정규화를 `DfnsTransferRequests`로 분리해 조회 어댑터와 웹훅 파서가 **같은 검사**를 쓴다. 실패 예외만 경로별로 다르다
  (HTTP는 수신 바이트를 담은 `VendorApiException`, 웹훅은 `WebhookPayloadException`). `DfnsNetworkTransferClient`의 동작은 바뀌지 않았다.
- envelope: `DfnsWebhookProtocol`은 전송 종류에서 `data.transferRequest.id`가 명세 형식일 때만 `vendorTransactionId`를 채우고, 어긋나도 **거절하지 않는다** —
  인박스 수용(원문 보관)을 막지 않고 엄격한 해석은 판단 시점의 파서가 맡는다.
- **조립하지 않는다** — 파서는 실행 빈으로 등록하지 않는 내부 대역이다. 입금 감지(`wallet.blockchainevent.*`)·`wallet.transaction.*`·`WebhookTransactionParser`/`VendorStatusTranslator`의 Dfns 구현·
  재전달 dedup·이력 복구·판단 워커 조립은 후속이며 `BCM_PROVIDER=dfns` 전체 기동 차단도 그대로다.
- 검증: `NetworkTransferEventContractTest` 3(문서화된 다섯 종류만 대응·유사 종류 7종 거절, 종류-상태 독립, 전달 시도/알림 메타 불변식 4종),
  `DfnsNetworkTransferEventParserTest` 8(정상 해석·알림 메타, 다섯 종류와 종류≠상태, 전송 아닌 종류 5종 null, Solana `Spl2022`와 깨진 mint, 전송 정보 결손·설정 밖 네트워크 4종,
  알림 메타 결손·형식 오류 15종, 전송 필수 필드 13종, 비JSON 4종), `DfnsWebhookProtocolTest` 3(전송 ID 결속, 추정 금지 7종, 결손 거절).
- 선택 회귀: domain 122 · client 147 · webhook 75, 실패 0. 전체 ktlintCheck 통과. DDL·공개 API 변경 없음. 실벤더 호출·운영 적용 없음.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 6d99ee7..37e2dec, design-sync 먼저)**: Major 2 — ① **채택 명세 1.1018.3에도 `webhooks` 항목의 `wallet.transfer.*`와
  `WebhookEnvelopeBase`·`TransferRequest`가 있다**. 조회 모델 `WebhookEvent.data`만 보고 "판 차이"를 단정한 기록이 사실과 다르다. ② 그 잘못된 전제로 채택 명세의 필수 필드를 완화했다 —
  `deliveryAttempt`는 `WebhookEnvelopeBase`의 필수 필드이고 `retryOf`도 제공되면 `minLength 1`·ID 형식을 요구한다. design-sync 실패로 code-reviewer는 수행하지 않았다.
  공통 정규화 추출·종류가 아닌 `status`에서 상태를 읽는 판단·설정 밖 네트워크의 BCM 정책 분리·수신/판단의 느슨·엄격 경계·실행 빈 미등록은 정합으로 확인됐다.
- **반영**: 기록된 해시와 같은 1.1018.3 파일에서 `webhooks` 항목(전송 다섯·입금 감지 둘)과 `WebhookEnvelopeBase`(필수 `id`·`date`·`timestampSent`·`deliveryAttempt`)를 직접 확인하고
  계약13·설계12·PLAN·PROGRESS를 채택 명세 근거로 고쳤다. 수신 envelope와 조회 모델이 다른 schema라는 구분을 계약13의 DF3.13 행에도 적었다. `NetworkTransferEvent.deliveryAttempt`를
  `Int?`→`Int`로 되돌리고 파서가 결손·비정수·0 이하를 거절하며, 알림 ID는 `whe-…` 형식을, `retryOf`는 있으면 같은 형식을 요구한다(빈 값을 결손으로 축소하지 않는다).
  `timestampSent`를 파서가 재검사하지 않는 이유(서명 검증기가 이미 필수 검사)를 계약13·설계12·KDoc에 적었다. 재실행: domain 122 · client 147 · webhook 75, 실패 0, 전체 ktlintCheck 통과.
- **독립 converge 2차(Codex, 범위 37e2dec..3b5af8a, design-sync→code-reviewer 순차)**: 이전 Major 2건 해소 확인, 신규 Critical/Major/Minor 0으로 통과했다(검토 기준 3b5af8a).

## Dfns 웹훅 온체인 이동 사건 관찰 검증 (2026-09-16)

- 계약은 [계약13](13-dfns-contracts.md#웹훅-온체인-이동-사건-관찰--구현)에 먼저 고정했다. 근거는 채택 명세 1.1018.3 `webhooks`의 `wallet.blockchainevent.detected`·
  `wallet.blockchain_event.transfer.included`와 `WalletHistoryEvent`·`Wallet`이다.
- 도메인: 출력 포트 `NetworkChainEventParser`와 `NetworkChainEvent`·`NetworkChainEventKind`(지갑 대상 둘)·`NetworkChainTransfer`·`NetworkChainDirection`(In/Out)·
  `NetworkChainTransferStatus`(Included/Confirmed)를 추가했다. 알림 메타는 전송 사건과 공통인 `VendorWebhookDelivery`로 뽑아 두 사건이 같은 값을 쓴다.
- 어댑터: `DfnsNetworkChainEventParser`가 이동 종류(`Erc20Transfer` 등)를 **목록으로** 자산 kind에 대응시켜 등록·잔액·전송과 같은 키 규칙으로 `vendorAssetId`를 만든다.
  문서화된 미지원 이동 종류는 키와 금액을 만들지 않고 원어만 남긴 채 **사건은 보존**하고, 문서에 없는 종류는 어느 변형도 만족하지 않으므로 거절한다.
  필수 필드는 변형별로 다르므로 모든 변형의 공통 필수 아홉과 모델 대상 변형의 추가 필수(`symbol`·`decimals`·`from`·`to`·locator·`value`)를 함께 검사한다 —
  관찰값에 담지 않는 필드도 결손이면 명세를 만족하지 않는다. `data.wallet`의 `id`·`network`가 사건과 같아야 하고, 설정 밖 네트워크·형식 오류는 `WebhookPayloadException`으로 거절한다.
- 판단하지 않는 것: `Confirmed`를 BCM 확정으로 번역하지 않고, 종류와 상태가 고정 대응한다고 보지 않으며, 정밀도·심볼은 관찰값에 담지 않는다(선택·폐기 예정 벤더 필드에 업무 판단을 걸지 않는다). 입금 이벤트의 금액은 등록 매핑의 정밀도(03 V27 `dcml_cnt`)로 환산한다.
  `timestamp`는 형식 서술이 없어 파싱하지 않고 원문 그대로 둔다. `value`의 최소 단위 해석은 BCM 규칙이며 수용 항목이다.
- **조립하지 않는다** — 파서는 실행 빈으로 등록하지 않는 내부 대역이다. 입금 귀속(`bcm_addr_m` 대조)·논리 사건/outbox·미등록 자산 입금 경보·감시 주소 기능·
  `WebhookTransactionParser`/`VendorStatusTranslator`의 Dfns 구현·판단 워커 조립은 후속이며 `BCM_PROVIDER=dfns` 기동 차단도 그대로다.
- 검증: `NetworkChainEventContractTest` 3(지갑 대상 두 종류와 유사 종류 4종 거절·방향/상태 원어 대응, 모델 밖 종류의 키·금액 없음, 금액 형식 5종과 음수 블록·빈 식별자 7종),
  `DfnsNetworkChainEventParserTest` 10(토큰 입금 해석, 두 종류와 상태 독립, 네이티브/Solana mint 키, 문서화된 미지원 종류 5종 보존과 미문서 종류 4종 거절,
  변형별 추가 필수 8종과 Solana 변형의 선택 필드, 이동 아닌 종류 4종 null, 본문·지갑 결손과 지갑 ID/network 불일치·설정 밖 네트워크 6종,
  공통 필수·형식 오류 14종, 값 범위 3종과 알림 메타 4종, 비JSON 4종).
- 선택 회귀: domain 125 · client 157 · webhook 75, 실패 0. 전체 ktlintCheck 통과. DDL·공개 API 변경 없음. 실벤더 호출·운영 적용 없음.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 119cdd9..89a8b91, design-sync 먼저)**: Major 3 — ① `WalletHistoryEvent`는 oneOf라 변형마다 required가 다른데
  모델 대상 변형의 필수 필드(`metadata`/`metadata.asset`, Native의 `symbol`·`decimals`, Erc20의 `from`·`to`·`decimals`)를 검사하지 않았다. 담지 않는 것과 검사하지 않는 것은 별개다.
  ② "모델 밖 종류 보존"이 문서화된 종류와 임의 문자열을 구분하지 않아 schema를 만족하지 않는 본문도 정상 관찰로 받았다. ③ `data.wallet`의 필수 `network`를 대조하지 않아 ID만 같고
  네트워크가 다른 본문으로도 자산 키를 만들 수 있었다. design-sync 실패로 code-reviewer는 수행하지 않았다. `VendorWebhookDelivery` 공통화·설정 밖 네트워크 정책·`Confirmed` 미번역·
  원문 `timestamp`는 정합으로 확인됐다.
- **반영**: `DfnsChainEventKinds`에 명세 이동 종류 28개와 모든 변형의 required 교집합 아홉, 모델 대상 변형의 추가 필수를 두고 파서가 모두 검사한다. 문서에 없는 종류는 미지원으로
  수용하지 않고 거절하며, 문서화된 미지원 종류만 공통 필수만 검사해 보존한다. `data.wallet.network`도 사건의 `network`와 대조한다. 정상 fixture를 명세 required대로 채우고
  변형별 결손·미문서 종류·network 불일치 사례를 테스트에 넣었으며, 폐기 예정 표기가 붙은 `symbol`·`decimals`가 required에서 빠지면 검사도 함께 푼다는 조건을 수용 항목에 적었다.
  재실행: domain 125 · client 157 · webhook 75, 실패 0, 전체 ktlintCheck 통과.
- **독립 converge 2차(Codex, 범위 89a8b91..750ddff, design-sync→code-reviewer 순차)**: 이전 Major 3건 해소 확인(28종 목록이 명세와 누락·초과 없이 일치, 네 변형 required 일치),
  신규 Critical/Major/Minor 0으로 통과했다(검토 기준 750ddff).

## Dfns 확정 판정의 블록 깊이 계약 검증 (2026-09-16)

- 사용자 확정(2026-09-16): 벤더의 `Confirmed`는 reorg로 뒤집힐 수 있으므로 Dfns 경로의 `FINALIZED`는 **블록 깊이로 직접 계산**한다. 결정은 [CLAUDE.md 3절](../../CLAUDE.md)과
  [02](02-bcm-flow.md#dfns-경로의-확정-근거-2026-09-16-사용자-확정)에, 계약은 [계약13](13-dfns-contracts.md#확정-판정--구현)에 고정했다.
- 도메인: `ChainHeadPort`(네트워크 → head 블록 번호)와 순수 규칙 `BlockDepthFinality`를 추가했다. 블록 자체가 1컨펌이고, head가 사건 블록보다 낮게 보이면 0으로 본다(음수 금지).
  깊이 계산이 `Long` 범위를 넘으면 값을 지어내지 않고 실패해 확정을 보류한다. 임계는 제공자별로 나누지 않고 기존 `bcm.finality-confirmations.<network>`(`FinalityPolicy`)를 그대로 쓴다.
- 어댑터: `EvmChainHeadClient`가 위탁 RPC(`bcm.evm-rpc.networks.<network>.url`)에 `eth_blockNumber`를 보낸다. 미설정 네트워크는 임의 endpoint를 고르지 않고,
  RPC 오류·결손·비16진수·부호 있는 64비트 범위 밖 값은 head로 받지 않는다. 실패는 감추지 않고 올려 **확정을 보류**한다 — 모름을 "아직 미확정"으로 바꾸지 않는다.
- **조립하지 않는다** — 실행 빈으로 등록하지 않는 내부 대역이다. `VendorStatusTranslator`의 Dfns 구현·판단 워커 조립·head 캐시/조회 주기·Solana 확정 모델·
  reorg 무효화 관찰 경로는 후속이며 `BCM_PROVIDER=dfns` 기동 차단도 그대로다. Fireblocks 경로의 `numOfConfirmations` 비교는 바뀌지 않았다.
- 검증: `BlockDepthFinalityTest` 4(깊이 산식과 head 미달 0, 임계 이상만 확정, `Long` 범위 넘침의 실패와 경계값, 음수 블록·0 이하 임계 거절 4종),
  `EvmChainHeadClientTest` 4(`eth_blockNumber` 요청·결과 해석, 미설정 네트워크 중단, RPC 오류·결손·형식·범위 6종, HTTP 실패 전파).
- 선택 회귀: domain 129 · client 161, 실패 0. 전체 ktlintCheck 통과. DDL·공개 API 변경 없음. 실벤더 호출·운영 적용 없음.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 a3fdd8c..620850d, design-sync 먼저)**: Major 1 — `head − blockNumber + 1`이 head가 `Long` 최대이고 사건 블록이 0일 때
  넘쳐 음수 컨펌이 되고, `finalized`가 그 값을 예외 없이 false로 돌려 "실패를 확정 전 상태로 치환하지 않는다"는 원칙과 어긋난다. design-sync 실패로 code-reviewer는 수행하지 않았다.
  블록 자체 1컨펌 산식·head 미달 0 처리의 안전 방향·두 제공자의 임계 설정 공유·Solana 범위 밖 표기·Fireblocks 경로 무변경은 정합으로 확인됐다.
- **반영**: `Math.incrementExact`로 유일한 넘침 지점을 예외로 올려 확정을 보류한다(뺄셈은 `head >= blockNumber` 보장 뒤라 넘치지 않는다). 넘침을 음수·포화값으로 바꾸지 않는다는 규칙을
  계약13 깊이 산식 행과 설계12에 적고, `Long.MAX_VALUE` 넘침과 양쪽 경계값을 테스트에 넣었다. 재실행: domain 129 · client 161, 실패 0, 전체 ktlintCheck 통과.
- **독립 converge 2차(Codex, 범위 620850d..17ecb24, design-sync→code-reviewer 순차)**: 이전 Major 해소 확인, 신규 Critical/Major/Minor 0으로 통과했다(검토 기준 17ecb24).

## Dfns 상태 번역 검증 (2026-09-16)

- 계약은 [계약13](13-dfns-contracts.md#상태-번역--구현)에 고정했다. 02의 `TxStatus` 다섯과 전이 표는 제공자와 무관하게 그대로 쓰고, 벤더 원어만 Dfns 것으로 바꾼다.
- `DfnsStatusTranslator`가 기존 도메인 포트 `VendorStatusTranslator`를 구현한다 — 새 포트를 만들지 않았다. 받는 원어는 전송 상태 여섯과 온체인 이동 상태 둘이며
  목록 밖 원어는 `WebhookPayloadException`으로 거절한다(임의 상태로 바꾸지 않는다).
- `Pending`·`Executing`·`Broadcasted` → `SUBMITTED`(체인 미등장), `Failed` → `FAILED`, `Rejected` → `REJECTED`.
  온체인 관찰 둘(`Included`·`Confirmed`)은 **관찰의 컨펌 수(블록 깊이)가 임계 이상일 때만** `FINALIZED`이고 아니면 `CONFIRMED`다 — 확정은 오직 깊이로 내고 벤더의 확인 표기를 추가 관문으로 두지 않는다(CLAUDE.md 3절).
- 전송 응답에는 `blockNumber`가 없어 깊이를 모른다. 그래서 전송 경로의 `Confirmed`는 `CONFIRMED`에 머물고 출금의 확정도 온체인 이동 사건(`direction: Out`)에서 판정한다.
  관찰 컨펌 수는 `BlockDepthFinality.confirmationCount`가 만들며 `Int` 상한을 넘는 깊이는 상한으로 줄인다.
- **대사 종결 판정은 만들지 않는다**(항상 null) — Dfns 대사 경로가 없고 `Confirmed`를 종결로 돌려주면 블록 깊이 결정을 우회한다. 포트 계약의 "대상 밖은 null"을 쓴다.
  Fireblocks의 동결 subStatus에 해당하는 Dfns 개념은 문서에 없어 만들지 않았고, 확정 후 동결·무효화 관찰 경로는 수용 항목이다.
- **조립하지 않는다** — 실행 빈 미등록이며 `WebhookTransactionParser`의 Dfns 구현·입금 귀속·논리 사건/outbox·판단 워커 조립은 후속이다. Fireblocks 번역은 바뀌지 않았다.
- 검증: `DfnsStatusTranslatorTest` 6(제출 3종, 실패·거절, 온체인 관찰 둘의 임계 경계 8종과 벤더 확인 없이도 확정·깊이 모를 때 미확정, 네트워크별 임계 조회와 불필요한 조회 없음, 목록 밖 원어 7종·음수 컨펌 거절, 대사 판정 7종 null),
  `BlockDepthFinalityTest`에 관찰 컨펌 수 상한 축소 4종 추가.
- 선택 회귀: domain 130 · client 167, 실패 0. 전체 ktlintCheck 통과. DDL·공개 API 변경 없음. 실벤더 호출·운영 적용 없음.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 f93cb0a..134f1e3, design-sync 먼저)**: Major 1 — `Included`를 깊이와 무관하게 `CONFIRMED`로 두면,
  벤더의 `Confirmed` 알림이 늦거나 유실될 때 RPC로 충분한 깊이를 관찰해도 확정이 영영 나오지 않는다. 확정을 앞당기지는 않지만 확정과 잔액 반영을 막는다.
  design-sync 실패로 code-reviewer는 수행하지 않았다. `Broadcasted`→`SUBMITTED`·`Rejected`→`REJECTED`·대사 판정 null·`Int` 포화·전송 경로 공백 표기는 정합으로 확인됐다.
- **반영**: 온체인 관찰 둘(`Included`·`Confirmed`)에 **같은 깊이 판정**을 적용한다 — 벤더의 확인 표기는 확정의 근거도 추가 관문도 아니다. 계약13 상태 번역 표·설계12·PLAN·KDoc을
  같은 문구로 고치고, 두 원어의 임계 경계와 `Included`+충분한 깊이 → `FINALIZED`를 테스트에 넣었다. 재실행: domain 130 · client 167, 실패 0, 전체 ktlintCheck 통과.
- **독립 converge 2차(Codex, 범위 134f1e3..3ef3012, design-sync→code-reviewer 순차)**: 이전 Major 해소 확인, 신규 Critical/Major/Minor 0으로 통과했다(검토 기준 3ef3012).

## Dfns 온체인 이동 귀속 판정 검증 (2026-09-16)

- 계약은 [계약13](13-dfns-contracts.md#온체인-이동의-귀속--구현)에 고정했다. 02의 웹훅 계열 분류(우리 발신은 제출 원장, 입금은 주소)와 09의 주소 매핑을 그대로 쓴다.
- 도메인 순수 판정 `NetworkChainAttribution`과 조회 포트 `NetworkChainLedgerLookup`(`assetOf`·`accountOfDepositAddress`)을 추가했다.
  저장·발행·상태 번역을 하지 않고 업무 대상만 가른다 — 상태 번역은 `DfnsStatusTranslator`, 발행은 판단 워커의 몫이다.
- 판정 순서: 발신(`Outgoing`) → 미지원 이동 종류(`UnsupportedAsset`) → 미등록 자산(`UnmappedAsset`) → 매핑 network 어긋남(중단) →
  목적지 없음/발급 주소 아님(`Unattributed`) → 관리 입금(`Deposit`). 주소 조회는 **등록 매핑의 network·symbol**로 한다.
- **미지원·미등록·미귀속을 예외가 아니라 결과로 가른다** — Fireblocks 경로는 미등록 자산을 원문 결함으로 거절하지만, Dfns 조직 지갑은 등록하지 않은 토큰도 받으므로
  수신 실패가 아니라 운영이 판단할 신호다. 등록 매핑과 관찰의 network가 어긋나는 것만 데이터 결함으로 중단한다.
- 귀속은 발급 주소(`bcm_addr_m`) 대조로만 한다. 지갑 ID → 계정의 역방향 조회는 저장 계약이 없어 두지 않았다. Solana SPL의 `to`가 ATA면 `UNKNOWN_ADDRESS`가 되며 이는 수용 항목이다.
- **조립하지 않는다** — 판단 워커·경보·논리 사건/outbox·제출 원장 대조는 후속이며 `BCM_PROVIDER=dfns` 기동 차단도 그대로다. Fireblocks 귀속 경로는 바뀌지 않았다.
- 검증: `NetworkChainAttributionTest` 6(관리 입금, 발신 우선 판정, 미지원·미등록 자산, 목적지 없음·미등록 주소, 매핑 network 어긋남 중단, 주소 조회 인자).
- 선택 회귀: domain 136, 실패 0. 전체 ktlintCheck 통과. DDL·공개 API 변경 없음. 실벤더 호출·운영 적용 없음.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 5266583..1501ac1, design-sync→code-reviewer 순차)**: Critical/Major/Minor 0으로 통과했다(검토 기준 1501ac1).
  판정 순서와 02의 계열 분류·입금 귀속 일치, 발신을 출금으로 확정하지 않고 대조 대상으로만 분리한 점, 미지원·미등록 자산을 결과로 보존하면서 Fireblocks 경로를 바꾸지 않은 점,
  등록 매핑 network 불일치만 내부 데이터 결함으로 중단하는 경계, 지갑 ID 역방향 조회·Solana ATA 동작을 추측하지 않은 점, 도메인 무의존을 확인했다.

## Dfns 논리 거래 식별자와 hash 조회 index 검증 (2026-09-16)

- 사용자 확정(2026-09-16): Dfns 입금의 논리 거래 ID는 `txHash`·`index`에서 파생한 결정적 값으로 만들고 `bcm_tx_l.vndr_tx_id` 폭을 넓히지 않는다.
  계약은 [03 V26](03-bcm-db.md#v26-dfns-논리-거래-식별자와-온체인-hash-조회--물리-저장-계약)과 [계약13](13-dfns-contracts.md#논리-거래-식별자--구현)에 고정했다.
- 근거: Fireblocks는 입금에도 벤더 거래 ID를 주지만 Dfns 이동 사건에는 ID 필드가 없다. `0x` 뺀 EVM hash가 이미 64자라 읽을 수 있는 형태로는 접두사·순번을 넣을 자리가 없고,
  PK를 넓히면 참조 테이블·인덱스와 기존 Fireblocks 데이터 마이그레이션이 함께 필요하다.
- 도메인: `NetworkChainTransactionId`가 `dfns-` + SHA-256(network·hash·순번) 요약 52자(합 57자)를 만든다. 순번은 명세상 선택이지만 **파생 ID의 필수 입력**이다 — 없으면 한 트랜잭션의 여러 이동이 같은 PK가 되므로 ID를 지어내지 않고 실패시켜 처리 보류로 남긴다. 입력은 길이를 앞에 붙여 이어 붙여 경계가 흔들리지 않게 하고,
  EVM hash만 소문자로 정규화한다(base58 서명은 대소문자가 값의 일부라 건드리지 않는다). 출금은 벤더가 준 `xfr-…`를 그대로 쓴다.
- DDL: V26 `idx_bcm_tx_hash`(`bcm_tx_l (tx_hash) WHERE tx_hash IS NOT NULL`) **추가 전용** 마이그레이션. 컬럼·PK·제약을 바꾸지 않으며 V18과 같은 온라인 생성(`CREATE INDEX CONCURRENTLY`)을 써서 생성 중 쓰기를 막지 않는다.
  hash는 RBF 계열·재관찰로 중복될 수 있어 UNIQUE로 두지 않는다. 운영 적용은 기존 규칙대로 DBA가 먼저 수행한다.
- **조립하지 않는다** — 원장 쓰기·`tx_hash` 조회 Repository·판단 워커 조립은 후속이며 `BCM_PROVIDER=dfns` 기동 차단도 그대로다. Fireblocks 식별자 경로는 바뀌지 않았다.
- 검증: `NetworkChainTransactionIdTest` 6(결정성·길이·원문 미노출, network/hash/순번 구분, 순번 없는 이동 거절, 입력 경계 합쳐짐 방지, EVM 대소문자 정규화와 base58 미정규화, 빈 입력 거절),
  `V26TransactionHashLookupPersistenceTest` 1(**업그레이드 경로** — V25까지 적용해 거래 1,500건을 쌓은 뒤 V26을 적용하고, index 정의의 부분 조건과 유효 상태(`indisvalid`)·hash 조회의 index 사용·같은 hash 중복 저장 허용을 확인).
- 선택 회귀: domain 142 · persistence 234, 실패 0. 전체 ktlintCheck 통과. 공개 API 변경 없음. 실벤더 호출·운영 적용 없음.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 a19f372..5085f1b, design-sync 먼저)**: Critical 1 — 명세에서 선택 필드인 `index`의 결손을 빈 값으로 해시해,
  한 트랜잭션의 여러 이동이 순번 없이 오면 같은 `vndr_tx_id`가 되고 상태 머신이 뒤 관찰로 계정·자산을 덮어써 서로 다른 고객의 자금이 한 논리 거래로 합쳐진다.
  Major 2 — ① 비고유 `tx_hash`만으로 발신 거래를 찾는 계약이 복수 후보 처리를 정의하지 않았다, ② 일반 `CREATE INDEX`가 큰 원장에서 생성 동안 쓰기를 막아 Fireblocks 경로까지 멈춘다.
  Minor 1 — 마이그레이션 테스트가 기존 데이터 위 업그레이드 경로와 실제 partial predicate를 검증하지 않았다. design-sync 실패로 code-reviewer는 수행하지 않았다.
  52자 절단의 충돌 위험·길이 접두 직렬화·EVM 소문자 정규화와 기존 컬럼 무변경은 정합으로 확인됐다.
- **반영**: 순번을 파생 ID의 **필수 입력**으로 바꿔 공백·결손이면 ID를 만들지 않고 실패시킨다(처리 보류). 계약13에 "순번" 행을 따로 두어 명세상 선택이라는 사실과 BCM 규칙을 분리하고
  수용 항목에 남겼다. 발신 사건 대조를 `(ntwk_cd, tx_hash)` 단일 후보 + 제출 원장 대응으로 좁히고 그 밖은 중단한다. V26을 V18과 같은 온라인 생성으로 바꿨다.
  마이그레이션 테스트를 업그레이드 경로로 다시 써 index 정의의 부분 조건과 `indisvalid`를 직접 확인한다. 재실행: domain 142 · persistence 234, 실패 0, 전체 ktlintCheck 통과.
- **독립 converge 2차(Codex, 범위 5085f1b..1aaf76a, design-sync→code-reviewer 순차)**: 이전 4건 모두 해소 확인, 신규 Critical/Major/Minor 0으로 통과했다(검토 기준 1aaf76a).

## 등록 자산 정밀도 보관 검증 (2026-09-16)

- 사용자 확정(2026-09-16): 이벤트 금액 환산에 쓸 정밀도는 **등록 시점에 저장**한다. 벤더가 사건마다 주는 값에 의존하면 벤더가 인덱싱 값을 바꾸는 순간
  같은 자산의 과거·미래 금액 해석이 달라진다. 계약은 [03 V27](03-bcm-db.md#v27-등록-자산의-정밀도-보관--물리-저장-계약)·[07](07-asset-master.md)·[계약13](13-dfns-contracts.md)에 고정했다.
- 배경: 07은 "소수 자릿수는 현재 매핑에 보관하지 않는다"였다. Fireblocks가 사람 단위 금액을 보내 환산이 필요 없었기 때문이며, Dfns 관찰은 최소 단위 정수라
  02의 이벤트 금액을 만들 수 없었다. DF3.16에서 "정밀도는 등록 매핑에서 읽는다"고 적은 것은 사실과 달랐고 이번에 바로잡았다.
- DDL: V27 `bcm_vndr_ast_m.dcml_cnt SMALLINT NULL CHECK (0..255)` — **추가 전용**이라 기존 행·Fireblocks 데이터를 건드리지 않는다. 상한은 ERC-20 uint8·SPL u8 한계다.
- 도메인: `AssetDecimals`(상한·최소 단위 표기·`amountOf` 환산)를 두고 `VendorAssetMapping.decimals`·`ChainAssetLocator.decimals`를 추가했다.
  잔액 관찰(`NetworkWalletAssetBalance`)도 같은 상한·표기를 쓰도록 모아 중복 상수를 없앴다.
- 원천별 관문: Fireblocks는 카탈로그가 정밀도를 소유하므로 운영자 입력을 `decimalsNotApplicable`로 거절하고 해소값을 저장한다.
  Dfns는 카탈로그가 없어 운영자 등록값을 요구하며(`decimalsRequired`) 범위 밖은 `decimalsOutOfRange`다 — 등록을 통과시킨 뒤 입금에서 환산이 막히는 것보다 등록에서 막는다.
- 영속: 조회·등록·교체·변경 snapshot에 정밀도를 함께 담는다. OpenAPI 0.13.0(요청 `decimals`, 응답 `AssetMapping.decimals` nullable).
- 회귀에서 드러난 기존 결함 하나를 함께 고쳤다 — DF3.13에서 `DfnsClientConfig`에 Jackson 의존 빈이 생긴 뒤 `DfnsAccountAssemblyIntegrationTest`의 슬라이스 컨텍스트가
  `ObjectMapper` 없이 뜨지 못했다. 그때 bcm-api 테스트를 돌리지 않아 놓친 회귀이며 슬라이스 설정에 Jackson 빈을 제공해 복구했다.
- 검증: `AssetDecimalsTest` 4(상한, 환산 6종, 잘못된 표기·범위 7종, 등록 매핑 불변식), `DfnsChainAssetResolverTest`에 정밀도 필수·범위·보존 검증 추가,
  `VendorAssetMappingPersistenceTest`에 실제 PostgreSQL 왕복과 등록·교체 snapshot의 before/after 정밀도 검증 추가, 조립 테스트에 `decimalsRequired` 거절 추가.
- 전체 회귀: 11개 모듈 **1,266건, 실패 0**(domain 146 · application 25 · support 37 · test-support 51 · client 168 · messaging 3 · persistence 235 · API 308 · Admin 59 · Webhook 75 · BAT 159). 전체 ktlintCheck 통과.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 11777d9..fc66615, design-sync 먼저)**: Major 3 — ① 07의 현재 매핑 DDL 블록이 물리 스키마와 어긋났다
  (`dcml_cnt` 없음, `vndr_ast_id`가 V25 이전 폭, snapshot 필드 목록에 정밀도 없음), ② OpenAPI 0.13.0이 DAW-ADMIN BFF 생성 타입에 반영되지 않아 `generate.py --check`가 stale을 알렸고
  생성 타입이 `dfnsAssetKey`·`decimals`를 갖지 않았다(DF3.11·DF3.12부터 밀려 있던 부채), ③ 계약13 잔액 절과 `NetworkWalletAssetPort` KDoc의 "매핑에 별도 정밀도를 보관하지 않는다"가
  V27과 충돌한다. Minor 1 — OpenAPI의 Dfns 예시에 필수 `decimals`가 없어 그대로 보내면 거절된다. design-sync 실패로 code-reviewer는 수행하지 않았다.
  V27 SQL의 추가 전용 성격·NULL 허용·snapshot의 `CAST(... AS INT)` 보존은 정합으로 확인됐다.
- **반영**: 07 DDL을 실제 스키마에 맞추고 BFF 타입을 재생성했으며(테스트 7곳을 이름 인자로 전환), 정밀도의 두 출처 경계를 계약13·KDoc에 적었다 —
  **잔액 조회는 응답의 `decimals`**(응답이 금액과 정밀도를 함께 준다), **등록 매핑의 정밀도는 정밀도가 따라오지 않는 입금 사건 환산**에 쓰며 두 값의 일치 보장은 없어 어긋남 감시는 후속이다.
  OpenAPI 예시에 `decimals`를 넣고 문서를 재생성했다.
- **독립 converge 2차(범위 fc66615..07a37cc)**: Major 1 — 07 DDL에 컬럼은 더했으나 실제 named CHECK(`ck_bcm_vndr_ast_dcml`)가 제약 목록에 없어 부분 해소였다. → 제약을 그대로 적었다.
- **독립 converge 3차(design-sync 범위 07a37cc..b69288a, code-reviewer 범위 11777d9..b69288a 순차)**: 이전 지적 모두 해소 확인, 신규 Critical/Major/Minor 0으로 통과했다(검토 기준 b69288a).

## Dfns 입금 판단 유스케이스 검증 (2026-09-16)

- 계약은 [계약13](13-dfns-contracts.md#입금-판단--구현)에 고정했다. 앞선 관찰(DF3.16)·귀속(DF3.19)·번역(DF3.18)·확정(DF3.17)·식별자(DF3.20)·정밀도(DF3.21)를 하나로 잇는 유스케이스다.
- `DfnsChainEventDecision`(bcm-webhook)이 호출자의 트랜잭션 안에서 원장 전이(`TxStateService`)와 outbox 적재(`OutboxEventService`)를 수행한다.
  입금만 원장을 쓰고 발신·미지원 자산·미등록 자산·미귀속·정밀도 없음·**발신 주소 없음**은 **원장을 쓰지 않고 결과로만** 돌려준다 — 경보·무시 판단은 워커의 몫이다.
- 확정은 체인 head 깊이를 관찰 컨펌 수로 담아 번역기가 임계와 비교한다(임계 비교는 번역기 소유라 유스케이스가 정책을 직접 읽지 않는다). **head 조회 실패는 예외로 올라가 확정을 보류**하고,
  순번 없는 사건은 **외부 호출 전에** 실패한다 — 결정적 payload 오류가 RPC 장애에 가려 재시도되지 않게 한다.
- 없는 값을 만들지 않는다 — 제출 키·`subStatus`·`networkStatus`는 `null`이고, 벤더 시각은 형식이 서술된 알림 `date`에서 만든다(사건 `timestamp`는 형식 서술이 없다).
  02가 확정한 "입금 이벤트의 발신 주소는 항상 채워진다"를 지키기 위해 명세상 선택인 `from`이 없으면 이벤트를 만들지 않고 멈춘다. `vndr_crt_dttm`의 제공자별 의미 차이는 03에 적었다.
  금액은 등록 정밀도로 환산해 02 이벤트 금액의 단위를 제공자와 무관하게 하나로 유지한다.
- 귀속 결과에 등록 정밀도를 함께 실어(`LedgerAsset.decimals`·`Deposit.decimals`) 유스케이스가 매핑을 다시 읽지 않게 했다.
- **조립하지 않는다** — 실행 빈 미등록이며 인박스 P/S/F 처리·워커 조립·경보 포트·발신의 제출 원장 대조는 후속이다. `BCM_PROVIDER=dfns` 기동 차단도 그대로다.
- 검증: `DfnsChainEventDecisionTest` 9(깊이 기반 확정과 등록 정밀도 환산·거래 ID·시각·없는 값, 임계 미달의 미확정, **감지→확정 두 이벤트의 순서·서로 다른 evnt_id·같은 거래 ID**, 발행할 상태 없음, 입금 아닌 결과 5종의 무쓰기,
  정밀도 없음의 중단, 발신 주소 없음의 중단, 순번 없음의 실패, head 조회 실패의 전파), `NetworkChainAttributionTest`에 정밀도 전달 검증 추가.
- 전체 회귀: 11개 모듈 **1,276건, 실패 0**. 전체 ktlintCheck 통과. DDL·공개 API 변경 없음. 실벤더 호출·운영 적용 없음.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 8fc9a7e..9670640, design-sync 먼저)**: Critical 1 — 명세상 `Native`·`Spl` 변형의 `from`이 선택이라 발신 주소 없는 입금 이벤트를
  DAW-CORE에 보낼 수 있었다. [02](02-bcm-flow.md#상태-enum)는 "입금 이벤트의 발신 주소는 항상 채워진다"를 확정했고 OpenAPI도 같은 계약이라 입금 판별 입력이 깨진다.
  Major 1 — `vndr_crt_dttm`을 03이 Fireblocks 거래 `createdAt`(대사 시간축)으로 정의했는데 알림 사건 시각을 넣으면 의미가 충돌한다.
- **반영**: `from`이 없으면 원장·outbox를 쓰지 않고 `MissingSender`로 멈춘다(체인 head도 읽지 않는다). 계약13에 "발신 주소" 행과 수용 항목을 넣었다.
  03의 `vndr_crt_dttm` 정의를 제공자별로 명시했다 — Fireblocks는 거래 `createdAt`, Dfns 입금은 알림 `date`(입금에는 거래 생성 개념이 없고 사건 `timestamp`는 형식 서술이 없다) —
  두 값이 같은 뜻이 아니므로 **제공자를 섞어 한 구간으로 대사하지 않는다**는 점과 Dfns 대사 시간축은 대사 구현과 함께 정한다는 점을 적었다.
- **독립 converge 2차(범위 9670640..18fb3eb, code-reviewer 포함)**: Critical 1 — Dfns 경로의 outbox 순서 계약(감지→확정 두 이벤트)을 테스트가 고정하지 않았다.
  Minor 2 — 주입받은 `FinalityPolicy`가 쓰이지 않아 잘못된 의존을 암시했고, 순번 검사가 체인 head RPC 뒤라 결정적 payload 오류가 RPC 장애에 가려질 수 있었다.
- **반영**: 두 상태를 순서대로 적재하고 `evnt_id`가 서로 다르며 거래 ID는 같은지 검증하는 테스트를 넣었다. 쓰이지 않던 `FinalityPolicy` 의존을 제거하고(임계 비교는 번역기 소유),
  순번 검사를 외부 호출 앞으로 옮겼다. 재실행: 11개 모듈 1,276건, 실패 0.
- **독립 converge 3차(범위 18fb3eb..761d99d, design-sync→code-reviewer 순차)**: 이전 3건 모두 해소 확인, 신규 Critical/Major/Minor 0으로 통과했다(검토 기준 761d99d).

## Dfns 판단 워커 조립 검증 (2026-09-16)

- 계약은 [계약13](13-dfns-contracts.md#판단-워커-조립--구현)에 고정했다. 인박스 판단을 `WebhookDecisionWork` 경계로 추상화하고 제공자마다 하나만 조립한다 —
  `WebhookDecisionProcessor`·경보 처리는 경계 뒤의 벤더 어휘를 모른다.
- 기존 `WebhookDecisionTransaction`에 `@ConditionalOnFireblocksProtocol`을 달고 `DfnsWebhookDecisionTransaction`(`@ConditionalOnDfnsProtocol`)을 추가했다.
  Fireblocks 판단 경로의 동작·테스트는 바뀌지 않았다.
- `ConfiguredFinalityPolicy`를 `infra/client/fireblocks`에서 제공자 중립 위치(`infra/client/config`)로 옮기고 조건을 제거했다 —
  `bcm.finality-confirmations.<network>`는 두 제공자가 같은 설정을 쓴다(Fireblocks는 벤더 컨펌 수, Dfns는 블록 깊이와 비교).
- 상태 번역기·체인 head는 판단 경로에서만 필요하므로 Webhook 앱에서만 만든다(API/BAT 컨텍스트는 그대로). 사건 해석기는 envelope 해석과 같이 `DfnsClientConfig`에 둔다.
- 인박스 상태 규율은 Fireblocks와 같다 — 판단 완료·미귀속(경보 후)은 처리 완료, payload 결함은 재시도/격리, 확정 임계 설정 오류는 `P`로 남긴다.
  다만 `vndr_cmpl_yn`은 남기지 않는다 — 03이 Fireblocks `data.status=COMPLETED`와 원본 보관 색인을 위해 정의한 표식이고, Dfns 온체인 사건은 인박스 `vndr_tx_id`가 null이라 그 색인 대상이 아니다.
- **입금만 판단한다** — 전송 알림·발신 이동·미지원/미등록 자산·정밀도 없음·발신 주소 없음은 원장을 쓰지 않고 처리 완료로만 남긴다.
  제출 원장 대조 경로가 없어 출금 판단이 불가능하므로 **기동 차단 해제 조건에 "출금·발신 판단 구현"이 함께 걸린다**.
- 검증: `DfnsWebhookDecisionTransactionTest` 8(대기 없음, 입금 완료와 보관 표식 미기록, 벤더 확인 여부와 무관한 표식 미기록, 미귀속 경보의 우리 어휘·hash, 미판단 계열 6종의 처리 완료,
  payload 결함의 재시도·격리, 설정 오류의 P 유지와 충돌·일반 오류 감싸기, 예기치 못한 실패 기록),
  `WebhookDecisionAssemblyTest` 2(dfns에서 Dfns 경계·번역기·head·원장 조회 조립, fireblocks·local에서 Dfns 빈 0).
- 전체 회귀: 11개 모듈 **1,286건, 실패 0**. 전체 ktlintCheck 통과. DDL·공개 API 변경 없음. `BCM_PROVIDER=dfns` 기동 차단은 유지된다.
- **독립 converge 1차(Codex gpt-6-astra high, 별도 reviewer 세션, 범위 e747427..a5496c8, design-sync 먼저)**: Major 2 — ① 앞선 슬라이스들이 "내부 대역·빈 미등록·워커 미연결"로 적어 둔 절과 KDoc이
  실제 조립 상태와 달라졌다, ② Dfns 이동 상태 `Confirmed`에 `vndr_cmpl_yn=Y`를 남기는데 03은 이 표식을 Fireblocks `data.status=COMPLETED`이자 원본 보관(`bcm_raw_tx_l`)의
  `vndr_tx_id` 부분 index용으로 정의한다 — Dfns 온체인 사건은 인박스 `vndr_tx_id`가 null이라 색인 대상도 아니다. design-sync 실패로 code-reviewer는 수행하지 않았다.
  조건부 조립의 배타성·`ConfiguredFinalityPolicy` 이동·전송 알림 제한 표기는 정합으로 확인됐다.
- **반영**: 조립된 경로(사건 해석기·번역기·체인 head·입금 판단)의 문구를 현재 상태로 갱신하고 여전히 연결되지 않은 전송·발신 경로와 구분했다.
  `vndr_cmpl_yn`은 남기지 않으며(항상 `N`) 그 이유와 Dfns 원본 보관 계약을 수용 항목으로 적었다.
- **독립 converge 2차(Codex gpt-5.6-luna medium — 1차 중 rate limit로 모델이 바뀌었다, design-sync 범위 a5496c8..HEAD·code-reviewer 범위 e747427..HEAD 순차)**:
  이전 Major 2건 해소 확인, 신규 Critical/Major 0. Minor 1 — 조립 검증이 `ApplicationContextRunner`라 실제 앱 컨텍스트의 워커·경보 어댑터 연결까지는 보지 못한다.
  이는 `BCM_PROVIDER=dfns` 전체 기동이 아직 차단돼 전체 컨텍스트를 띄울 수 없기 때문이며, **기동 차단 해제와 함께 processor를 포함한 조립 검증을 추가한다**(후속).

## Dfns 출금 제출 계약 확정 (2026-09-17)

- **코드 변경이 없는 계약 슬라이스다.** 제출 유스케이스를 쓰기 전에 02의 회수 절차가 Dfns에서 성립하지 않는다는 사실을 확인해 계약을 먼저 고정했다.
- 확인한 사실: 채택 명세의 `GET /wallets/{walletId}/transfers` query는 **`limit`·`paginationToken`뿐**이다 — `externalId` 필터가 없다.
  그래서 02의 "응답 유실·소유권 만료 뒤 `externalTxId` 조회로 확인"을 Dfns에서 그대로 할 수 없다.
- 대체 수단: 공식 Idempotency 계약이 "같은 url·같은 본문(같은 `externalId`) 재제출 → 처음 만들어진 엔티티를 `200`" 을 보장하므로
  **같은 본문 재제출이 회수 수단**이다. 종결 상태 뒤에도 `externalId`가 영구 결속돼 새 거래를 만들지 않으며, 본문이 다르면 `409`라는 점이 안전장치다.
  실패한 전송의 재시도는 **새 제출 키**가 필요하고 그 발급 규칙은 출금 유스케이스에서 정한다.
- 02에 "제공자별 회수 절차" 절을 더해 Fireblocks 단건 조회와 Dfns 멱등 재제출을 나란히 적었다. 선기록·소유권은 그대로다. (`FAILED` 판정 기준은 이후 [출금 제출 유스케이스 검증](#dfns-출금-제출-유스케이스-검증-2026-09-17)에서 두 곳이 갈렸다.)
- **공개 API 영향**: 벤더 `externalId`는 1~50자인데 `bcm_sbmt_l.ext_tx_id`는 VARCHAR(128)로 훨씬 넓다. 50자를 넘는 키는 자르지 않고 거절하되
  **제출 경로 입구에서 먼저 거절해** 원장에만 남고 영원히 제출되지 않는 행을 만들지 않는다. 실제로 그런 키가 오는지는 **DAW-CORE 확인 항목**이다.
- 회수의 전제인 "같은 본문"은 저장된 canonical 값에서 결정적으로 다시 만든다(필드 집합·순서 고정, `req_hash`로 같은 요청 확인) — 원문 JSON을 보관해 재사용하지 않는다.
- 미결 제출 점검은 Dfns에서 **회수하지 않는다** — 확인 시각·횟수만 갱신해 경보로 넘기고 회수는 원 제출 경로만 한다. 점검기에 멱등 재제출을 허용할지는 유스케이스에서 정한다.
- 계약은 [계약13](13-dfns-contracts.md#출금-제출-계약--확정)에 있다. 제출 유스케이스·발신 이동 대조·새 제출 키 발급은 후속이며 `BCM_PROVIDER=dfns` 기동 차단도 그대로다.
- **독립 converge(Codex, 별도 reviewer 세션, design-sync 3라운드 — 코드 변경이 없어 code-reviewer는 수행하지 않았다)**:
  1차 Major 3 — ① 제출 키 폭을 VARCHAR(64)로 적었으나 03의 실제 폭은 **128**이라 50자 제한의 영향 판단 근거가 어긋났다,
  ② Dfns 미결 제출 점검의 동작이 비어 있어 이중 제출 또는 영구 미결 위험이 남았다, ③ 회수의 전제인 "같은 본문"을 얻는 규칙이 없었다(원장은 원문 JSON을 저장하지 않는다).
  2차 Major 2 — 앞 절에 남은 VARCHAR(64) 표기, 03 전이 표에 제공자별 예외가 없어 물리 상태 전이 계약이 모호했다. 3차 잔여 0으로 승인.
- **반영**: 폭을 실제 스키마로 바로잡고 50자 초과 키를 **제출 경로 입구에서** 거절해 원장에만 남는 행을 만들지 않기로 했다.
  미결 점검은 Dfns에서 회수하지 않고 확인 시각·횟수만 갱신하며(회수는 웹훅·원 제출 경로의 멱등 재제출), 03 전이 표에도 그 예외를 적었다.
  본문은 저장된 canonical 값에서 결정적으로 재구성하고 `req_hash`로 같은 요청임을 먼저 확인한다.

## 리뷰어 체크리스트 갱신과 소급 정합 점검 (2026-09-17)

- **코드 동작을 바꾸지 않는 슬라이스다.** 리뷰 규칙을 고치고, 그 규칙으로 기존 Dfns 범위 전체를 다시 점검했다.
- 문제의 출발점: `.claude/` 전체에 `Dfns`·계약13·설계12 언급이 **한 건도 없었다**. 점검 항목이 01·02·03·08과 `evidence/9x`만 가리켜,
  DF3.1~3.24 동안 지금 작업의 정본이 체크리스트 밖에 있었다. 대조가 이뤄진 것은 구현 세션이 프롬프트마다 범위와 문서를 지정했기 때문이지 규칙 때문이 아니다.
- `.claude/agents/design-sync.md`·`code-reviewer.md`·`test-writer.md`와 [converge 규칙](../ai/converge-review.md)을 고쳤다(커밋 `fc06776`).
  - 정본에 12·13 추가, Dfns 근거를 채택 공식 명세로 명시(Fireblocks의 `evidence/9x`와 분리), 계약13 **"수용 항목"을 단정으로 쓰는 역방향 검사** 신설.
  - **제공자 경계** 항목 신설 — 조건부 조립의 배타성, 제공자 중립 구성요소가 벤더 조건에 묶였는지, 문서가 적은 구현 상태와 실제 조립의 일치.
  - 생성물 신선도에 `bcm-admin/openapi/generate.py --check` 추가 — 이 누락으로 BFF 생성 타입이 DF3.11~3.21 동안 stale로 남았던 전력이 있다.
  - 락을 오래 잡는 DDL을 Critical로 명시, 확정 결정 확인을 CLAUDE.md 3절 **전체**로 바꾸고 Dfns 확정 판정·제공자 선택을 예시에 추가.
  - design-sync에도 Critical/Major/Minor와 판정 어휘를 고정하고, 읽기 전용이 도구가 아니라 지시로 보장된다는 사실과 확인 절차를 적었다.
- **구현 세션의 기계 점검**: 생성물 2종 신선, `domain` 모듈의 Spring/Jackson/JDBC import 0건, 조건부 애노테이션 배타(`dfns` vs `fireblocks|local`),
  시크릿은 전부 테스트 내 생성 키쌍·플레이스홀더. V23 `ADD COLUMN NOT NULL DEFAULT`는 PG11+ 재작성 없음, V24 인덱스는 같은 마이그레이션의 새 테이블이라 둘 다 문제 아님.
  **`EvmErc20Client`가 `@ConditionalOnFireblocksProtocol`에 묶여** `dfns`에서 `Erc20ContractPort`·`SweepBatchContractPort`·`SweepBatchReceiptPort` 빈이 없는 점은
  현행 계약 위반은 아니지만(Dfns Sweep이 후속, 전체 기동 차단) **Dfns Sweep 연결이나 차단 해제 때 중립 조립 또는 Dfns 구현이 필요하다** — 리뷰어도 같은 판단이다.
- **독립 converge(Codex gpt-5.6-sol high, 별도 reviewer 세션, 소급 design-sync 1회 + 델타 4회. 코드 동작 변경이 없어 code-reviewer는 수행하지 않았다)**:
  1차(범위 `3c60082..b8b26a1`) Major 3 — ① 07의 현재 매핑 DDL이 V1의 전역 `UNIQUE (vndr_ast_id)`를 그대로 적었다. V11이 그 제약을 제거하고
  `WHERE actv_yn='Y'` 부분 UNIQUE로 바꿨으므로 **비활성 매핑의 벤더 ID는 재사용할 수 있는데 문서대로면 반대로 읽힌다**(Dfns 이전 시대의 드리프트),
  ② 03이 Dfns 물리 스키마를 "아직 적용하지 않는다"와 "V22~V27 구현 완료"로 동시에 설명, ③ 12·13과 KDoc 5곳이 이미 조건부 조립된 경로를 "미연결"로 기록.
  2차 Major 2·Minor 1, 3차 Major 1, 4차 Major 2 — 모두 같은 종류(현재 상태 드리프트)의 잔여였고 호환 계획 도입부·경계 표, 02의 실행 흐름 머리말까지 번졌다.
  5차 **통과 — 정합**(Critical 0, Major 0).
- **반영**: 07 DDL을 부분 UNIQUE·`ck_bcm_vndr_ast_actv`·`ck_bcm_vndr_ast_dcml`로 바로잡고 제약 표·등록 시퀀스도 활성 행 기준으로 고쳤다.
  나머지는 **"`BCM_PROVIDER=dfns`에서만 조건부로 조립된다"와 "`ProviderConfiguration`의 전체 기동 차단이 유지된다"를 항상 함께 적는** 규칙으로 통일해,
  조립 사실이 Baseline 수용이나 차단 해제로 읽히지 않게 했다. 범위 밖 목록에서는 실제로 구현한 항목만 뺐다 —
  `WebhookTransactionParser`의 Dfns 구현·감시 주소·이력 복구·발신 제출 원장 대조·미판단 결과의 경보는 미구현으로 남겼고 리뷰어가 확인했다.
- 설계12의 날짜가 붙은 슬라이스별 검증 기록은 당시 상태의 이력이므로 현재 상태 문구로 보지 않는다(리뷰어와 합의).
- 공개 API·DB·실행 동작 변경 없음. `BCM_PROVIDER=dfns` 기동 차단도 그대로다.

## Dfns 출금 제출 유스케이스 검증 (2026-09-17)

- 계약13 [출금 제출 유스케이스 — 구현](13-dfns-contracts.md#출금-제출-유스케이스--구현)을 `DfnsTransferSubmissionService`(bcm-api)로 구현했다.
- **제공자 경계**: 공개 `POST /transactions`의 제출을 `TransactionSubmissionWork`로 추상화하고 제공자마다 하나만 조립한다.
  Fireblocks·로컬은 기존 `TransactionSubmissionService`(`@ConditionalOnFireblocksProtocol`), Dfns는 `DfnsSubmissionConfig`가 만든다.
  `VendorTransactionPort` 구현이 `FireblocksClient`뿐이라 기존 서비스와 `BandSCommandService`는 이미 `dfns`에서 만들어질 수 없었고,
  조건부 애노테이션은 그 사실을 드러낸 것이다(동작 변경 아님). 제출 조립은 계정 조립(`DfnsAccountConfig`)과 **나눠 둔다** —
  한 설정에 묶으면 계정만 필요한 조립 지점까지 제출 원장·전송 포트 의존을 끌고 간다.
- **02와 같은 것**: 선기록(`REQUESTED` 먼저) → 소유권(토큰+만료) → 벤더 호출은 트랜잭션 밖 → 같은 키·다른 내용은 `409` →
  소유권 실패는 기다리지 않고 `503`+`Retry-After` → 그 밖의 벤더 오류·무응답은 `REQUESTED` 유지.
- **02와 다른 것(회수)**: 벤더에 `externalId` 조회가 없어 **같은 본문 재제출**이 회수다. 본문은 저장된 canonical 값에서 결정적으로 재구성하고
  원문 JSON을 보관하지 않는다. 받은 관찰은 지갑·자산·목적지·금액으로 다시 대조한다. `externalId` 결속은 **어댑터가 강제한다** — 제출 응답에 없거나 다르면 어댑터가 실패시키므로 이 경로에 `null`이 오지 않는다. 도메인 대조 규칙이 `null`을 불일치로 보지 않는 것은 `externalId`가 선택인 **조회 관찰**에 같은 규칙을 쓰기 위해서다.
- **입구 거절**: 제출 키가 50자를 넘으면 **원장에 적기 전에** 거절한다(`VALIDATION_FAILED`). 원장 폭(128)이 벤더 한계(50)보다 넓어
  그 사이 길이는 원장에만 남고 영영 제출되지 않는다. 등록 정밀도가 없거나 금액 자릿수가 정밀도를 넘어도 같은 자리에서 거절한다 —
  `AssetDecimals.baseUnitsOf`는 **반올림하지 않는다**(반올림은 지시하지 않은 금액을 보내는 것이다).
- **`FAILED` 재시도는 열지 않는다**(이번 슬라이스의 결정). 같은 키 재제출은 기존 실패 엔티티를 돌려줄 뿐이고, 새 키 발급은
  벤더 `Failed`가 시스템 실패와 온체인 실행 실패를 함께 뜻해(`onChainSubmitted = null`) **이중 지급**이 될 수 있다.
  새 키는 체인 미제출이 확인된 경우로 제한하며 그 확인 수단(발신 확정의 블록 좌표)이 생긴 뒤에 연다 — 그때까지 `UNPROCESSABLE_ENTITY`로 거절한다.
- **범위 밖**: 발신 온체인 사건의 확정 반영, 새 제출 키 발급 규칙, 미결 제출 점검의 Dfns 동작,
  내부이체·Sweep·대납·수수료·Travel Rule·memo. `BCM_PROVIDER=dfns` 전체 기동 차단도 그대로다.
- **반영(1차 design-sync Critical 2)**: ① 회수 본문이 저장값에서 재구성되지 않고 회수 시점의 매핑·정밀도를 다시 읽었다 —
  V28로 제출 시점의 벤더 canonical 한 벌을 보관하고 그 값으로만 본문을 만든다. 저장값이 없으면 재구성하지 않고 `422`로 거절한다.
  원장을 먼저 읽어 이미 `SUBMITTED`인 건은 현재 매핑을 읽지 않는다. ② 회수 재제출의 표식 있는 `409`를 `FAILED`로 굳혔다 —
  최초 제출에서만 확정 거절로 두고 회수에서는 `REQUESTED`를 유지한다(진행 중 응답이 수용 항목이라 종결로 적으면 나간 전송에 확정 거절이 나간다).
- **반영(2차 Critical 1·Major 6)**: **상태를 읽고 소유권을 잡는 사이 다른 요청이 `FAILED`로 바꾸면 공용 `tryClaim`이 그 행을 되살려
  Dfns가 금지한 `FAILED → REQUESTED`가 다시 열렸다** — 판정과 전이가 같은 조건으로 원자적이어야 한다. `tryClaimRequested`(REQUESTED 전용)를 더해
  Dfns가 그것만 쓰고, 못 잡았을 때 최신 상태가 `FAILED`면 재시도 불가로 답한다. 그 밖에 `base_amt` 폭(정밀도 255면 최소 단위가 256자를 넘는다),
  V28의 즉시 검증 CHECK(→ `NOT VALID` + 별도 `VALIDATE`, 트랜잭션 밖), 03 정본 DDL 누락, 02·03 전이 표의 Dfns 예외, canonical 결손 거절의 순서를 고쳤다.
- **반영(3~5차 Major 7)**: 코드 설명과 계약 문구가 수용 항목 경계를 넘어 단정하던 곳 — "`FAILED` 판정은 같고 회수 수단만 다르다",
  "같은 본문 재제출은 기존 엔티티 `200`"(명확한 보장은 **종결 뒤**이고 진행 중은 수용 항목), `externalId` 결속의 강제 지점(어댑터가 강제하고
  도메인의 `null` 관용은 **조회 관찰**용) — 을 코드·KDoc·02·03·12·13에서 일치시켰다.
- **독립 converge(Codex gpt-5.6-sol high, 별도 reviewer 세션 — 컨텍스트가 찬 세션은 판정 근거가 얕아져 중간에 새 세션으로 교체했다)**:
  design-sync 5라운드(Critical 2 → Critical 1·Major 6 → Major 4 → Major 2 → Major 1·Minor 1) 뒤 **통과**(Critical 0·Major 0·Minor 1),
  이어서 **code-reviewer 공식 판정 "커밋 가능"**(Critical 0, Improvement 2, Minor 1).
  Improvement 2건도 반영했다 — 회수 `409`에서 벤더 상태·응답 원문을 버리지 않고 예외에 실어 자금 불확실 구간의 조사 정보를 남기고,
  Critical의 직접 원인이던 경합을 **실제 PostgreSQL 두 연결**로 재현해 고정했다(잠금 대기 뒤 조건 재평가로 되살리지 않음).
- 검증: 전체 **1,317 테스트 0 실패**, 전체 ktlintCheck·`git diff --check`·생성물 2종 통과. 벤더 실호출 없음.

## Dfns 전송 알림 판단 검증 (2026-09-17)

- 계약13 [전송 알림 판단 — 구현](13-dfns-contracts.md#전송-알림-판단--구현)을 `NetworkTransferJudgement`(domain)와 `DfnsTransferEventDecision`(bcm-webhook)으로 구현했다.
- **왜 먼저인가**: PLAN의 다음 항목은 "발신 이동 대조"였는데, 그건 **기존 거래를 찾아 붙이는** 일이다.
  그런데 Dfns 경로에는 발신 거래의 `bcm_tx_l` 행을 만드는 경로가 없었다 — 입금은 `DfnsChainEventDecision`이 쓰지만 전송 알림은 워커가 처리 완료로만 남겼다.
  대조만 먼저 만들면 후보가 늘 없어 동작하지 않는 코드가 되므로 **선행 조건인 전송 알림 판단을 이 슬라이스로 분리**했다.
  전송 알림에는 `blockNumber`가 없어 확정을 낼 수 없다는 점도 두 슬라이스의 자연스러운 경계다.
- **계열은 제출 원장으로만 가른다**(02) — 알림 종류·금액·주소 추정으로 가르지 않는다. 원장 조회는 벤더 전송 ID → 제출 키 순이며,
  두 번째 경로가 02의 "웹훅이 벤더 응답보다 먼저 올 수 있다"를 받는다. 비어 있던 `vndr_tx_id`는 이 알림이 채운다.
- **한 제출 키에 다른 전송 ID**가 이미 붙어 있으면 원장을 건드리지 않고 충돌로 올리며, 워커가 상한을 기다리지 않고 **즉시 격리**한다(이중 제출 신호를 처리 완료로 소거하지 않는다). **원장에 없는 전송**은 원장·이벤트를 만들지 않는다 —
  벤더 ID만으로 계정·업무 계열을 지어내면 남의 자금이 우리 원장에 들어온다.
- 업무 값(계정·네트워크·심볼·금액·목적지)은 **원장에 적힌 우리 요청**에서 읽는다. 알림이 알려준 값으로 업무 귀속을 바꾸지 않는다.
- **확정은 내지 않는다** — 관찰 컨펌 수를 `0`으로 두어 번역기가 확정을 내지 않게 한다(CLAUDE.md 3절). 출금의 확정은 온체인 이동 대조에서 난다(후속).
- 조립: 판단 워커가 **전송 알림을 먼저** 판단하고 전송 사건이 아니면 온체인 이동 판단으로 넘긴다. 두 파서는 서로 다른 `kind` 집합만 읽는다.
  `DfnsClientConfig`가 전송 파서를 실행 빈으로 등록하고(이전에는 내부 대역이었다) `DfnsWebhookDecisionConfig`가 판단을 조립한다.
- **범위 밖**: 미확인·충돌의 경보 포트, 발신 온체인 이동 대조와 그에 따른 확정, RBF(`replacementId`), 이력 복구.
  `BCM_PROVIDER=dfns` 전체 기동 차단도 그대로다.
- **독립 converge(Codex gpt-5.6-sol high, 별도 reviewer 세션)**: 1차 Critical 1 — **충돌을 미확인과 함께 묶어 처리 완료로 소거**하고 있었다.
  판단 계층은 충돌을 식별했지만 워커가 `Ignored`로 소비해, 이중 제출 신호가 재시도·격리·경보 없이 영구 처리 완료됐다 — 03·계약13·결과 타입 KDoc이 모두 "즉시 격리"라고 규정한 것과 정면으로 어긋났다.
  Fireblocks 워커의 `quarantineNow`와 같은 규율로 고쳤다. Major 2 — 계약13·KDoc 4곳이 "전송 파서는 워커에 연결되지 않았다"로 남았고,
  도메인 KDoc이 미확인을 "경보로 넘긴다"고 단정했는데 경보 포트는 범위 밖이다. 둘 다 반영했다.
- **2차 지적 반영**: ① **피처 소유권 위반** — 판단 유스케이스가 제출 피처의 Repository를 직접 주입받아 조회·기록했다.
  정본(`docs/standards/architecture.md`)은 피처 간 접근을 Service로 제한하고 기존 Fireblocks 판단 경로도 `SubmissionObservationService`를 쓴다.
  Service로 바꾸고, **`ArchitectureTest`의 검사 대상에 새 판단 클래스 둘을 넣어** 같은 위반이 다시 나면 잡히게 했다(그 목록에 없어서 이번에 못 잡았다).
  ② outbox 재시도 상한 설정 키가 기존 판단 경로(`bcm.webhook-worker.outbox-max-attempts`)와 달라 override 시 전송 알림만 운영 설정을 무시했다 — 같은 키로 통일했다.
  ③ 이벤트의 심볼·금액·목적지가 제출 원장 값인지와 격리 사유가 고정 안전 문자열인지를 테스트로 고정했다 — 알림 fixture의 목적지·금액을 원장 값과 **다르게** 두어 구현이 알림값을 쓰도록 회귀하면 깨지게 했다(처음에는 같은 값이라 회귀를 못 잡았다). ④ 파서 빈 KDoc 정정.
- **남은 개선(후속)**: 실제 PostgreSQL 결합 테스트 — `markSubmitted → bcm_tx_l → outbox → inbox S`의 일괄 커밋·롤백,
  마지막 `markProcessed` 실패 시 전체 롤백, 같은 제출 키의 전송 ID 경합, 서로 다른 알림 ID로 온 같은 상태 알림의 outbox 중복 방지.
  **"기동 차단 때문에 불가능"이라고 적었던 것은 틀렸다**(리뷰어 지적) — 전체 `BcmWebhookApplication` 컨텍스트는 막혀 있지만,
  `DfnsAccountAssemblyIntegrationTest`처럼 `@DataJdbcTest` + 명시적 `@Import`로 `ProviderConfiguration`을 제외하고
  Dfns 조립 + 실제 PostgreSQL을 붙이는 선례가 이미 있다. Webhook 쪽에 같은 방식의 슬라이스 harness를 만들면 **지금 검증할 수 있다** —
  다음 슬라이스에서 붙인다. 현재 코드에서 구체적 원자성 결함은 발견되지 않았다.
- **독립 converge 최종**: design-sync **통과**(Critical·Major·Minor 0), code-reviewer **커밋 가능**(Critical 0).
  라운드는 design-sync 3회(Critical 1·Major 2 → Major 1 → 통과) 뒤 code-reviewer 2회(Critical 1 → 커밋 가능)였다.
- 검증: 전체 1,332 테스트 0 실패, 전체 ktlintCheck·`git diff --check`·생성물 2종 통과. 벤더 실호출 없음.

## Dfns 발신 확정의 블록 좌표 검증 (2026-09-17)

- 계약13 [발신 확정의 블록 좌표 — 구현](13-dfns-contracts.md#발신-확정의-블록-좌표--구현)을 `NetworkChainOutgoingCoordinate`(domain)와 `DfnsChainEventDecision`의 발신 분기로 구현했다.
  아래 1~2차 기록은 **이동을 제출에 귀속시키려 한 설계**이고, 3차 지적으로 그 전제를 버렸다(맨 아래 항목).
- **출금의 확정이 여기서 난다** — 전송 알림에는 `blockNumber`가 없고 블록 좌표는 온체인 이동 사건에만 있다. 이 대조가 없으면 출금은 영영 확정되지 않았다.
- **새 거래를 만들지 않는다.** `(ntwk_cd, tx_hash)`(V26 index)로 전송 알림이 만든 거래를 찾아 그 거래의 전이로 반영한다.
  **후보가 정확히 하나이고 제출 원장에 대응할 때만** 붙인다 — hash는 유일하지 않으므로(한 트랜잭션에 여러 이동) 하나를 고르는 규칙을 지어내면
  **다른 거래에 남의 확정이 붙는다**. 02의 "제출 원장이 기준"을 hash 일치로 대체하지 않는다.
- 인박스 처리: 붙였으면 처리 완료. **후보 여럿·제출 원장 대응 없음·관찰 불일치는 즉시 격리**(시간이 지나도 해소되지 않는다), **후보 없음만 재시도**로 남긴다 — 전송 알림이 늦게 올 수 있고, 처리 완료로 닫으면 뒤늦은 알림이 거래를 만들어도 그 사건을 다시 실행할 트리거가 없어 **출금이 영영 확정되지 않는다**.
- 업무 값(계정·네트워크·심볼·금액·목적지)은 제출 원장에서 읽고 관찰의 최소 단위를 다시 환산하지 않는다.
- **조회는 거래 피처의 `TxStateService`를 통한다.** 처음에는 `TxRecordRepository`를 직접 주입했는데
  **지난 슬라이스에서 `ArchitectureTest`에 넣은 검사가 그 위반을 바로 잡아냈다** — 같은 종류의 Critical을 리뷰 전에 막은 첫 사례다.
- **범위 밖**: 후보 없음·대응 없음의 경보 포트, RBF(`replacementId`) 계열, `FAILED` 재시도의 새 제출 키 발급.
  `BCM_PROVIDER=dfns` 전체 기동 차단도 그대로다.
- **독립 converge 1차(Codex gpt-5.6-sol high)**: **Critical 2** — ① 이동이 전송 알림보다 먼저 오면 후보 없음을 **처리 완료로 닫아** 출금 확정이 영구 유실됐다.
  계약에 "도착 순서는 수용 항목"이라 적고도 그 경우를 성공으로 닫은 것이다. ② **"현재 단일 후보"는 이동과 제출의 유일 대응을 증명하지 않는다** —
  한 트랜잭션에 우리 이동 A·B가 있고 A의 알림만 먼저 와 있으면 B의 사건이 A에 붙는다. Major 2 — `NoSubmission`의 계약과 동작이 반대, 상태 드리프트 6곳.
  ①은 후보 없음을 재시도로 남기고(상한에 닿으면 격리) ②는 **관찰과 제출 canonical(03 V28)·목적지 대조**를 붙임 조건에 더해 반영했다.
  대응 없음·불일치는 재시도로 풀리지 않으므로 즉시 격리로 갈랐다.
- **2차 지적 반영**: ① 관찰 대조에 **자산 키**가 빠져 같은 지갑·목적지·금액의 다른 자산 이동이 잘못 붙을 수 있었다 —
  V28 canonical의 `vendorAssetId`를 대조에 더했다. 목적지를 모든 네트워크에서 `ignoreCase`로 비교한 것도 고쳤다 —
  EVM 16진 주소일 때만 무시하고 Solana base58은 대소문자를 구분한다(값의 일부다).
  ② **후보 없음을 재시도로 바꾼 것만으로는 부족했다** — 인박스에 backoff가 없어 워커 주기(500ms)마다 다시 집히고
  상한(3회)을 1~2초 만에 소진해, 결과가 "조용한 유실"에서 "곧바로 격리"로 바뀔 뿐이었다.
  **V29로 인박스에 재시도 대기(`next_attmpt_dttm`)를 도입**했다 — 사용자 결정으로 이번 슬라이스에 포함했다.
  제공자 중립이라 **Fireblocks의 일시 실패 재시도도 함께 고쳐진다**(지금까지 1.5초 만에 3회를 태웠다).
- **3차 지적 반영**: ① **기본 대기 창이 약 3.5분이 아니라 약 90초**였다 — 상한에 닿는 시도는 그 자리에서 격리되므로 마지막 대기는 쓰이지 않는다.
  실제 창(`30 + 60`)을 03·계약13에 적었다. ② `recordUnexpectedFailure`는 시도 횟수를 모르는 경로라 **매번 첫 대기**를 썼다 —
  대기 계산을 **저장소가 `rtry_cnt`로 하도록** 옮겨 모든 실패 경로가 같은 규율을 따른다. SQL과 도메인 식이 어긋나지 않게 영속 테스트로 고정했다.
  ③ 03 정본 `CREATE TABLE bcm_whk_l` 블록과 인덱스 목록에 V29를 반영하고, 트랜잭션 밖 실행이라 `ADD COLUMN IF NOT EXISTS`로 재실행 안전하게 했다.
  ④ 계약13의 파서·head·번역기·귀속·판단 범위 6곳의 "입금 전용/후속" 표기를 고쳤다.
- **4차 지적 반영**: ① SQL 대기 계산이 `bigint`로 먼저 곱해 **상한을 적용하기 전에 overflow**가 날 수 있었다(큰 기준 대기·높은 상한).
  `numeric`으로 계산해 `LEAST` 뒤에 변환하도록 고치고 상한·대량 시도 구간을 영속 테스트로 고정했다.
  ② 정본 여러 곳이 붙임 조건을 "단일 후보 + 제출 원장 대응"으로 **축약**해, 그대로 재구현하면 같은 금액의 다른 자산을 붙일 수 있었다 —
  03·13·PLAN에 관찰 일치(지갑·자산 키·금액·목적지)를 함께 적었다. ③ 발신을 "미판단·후속"으로 적은 잔여 4곳. ④ 도메인 KDoc의 계산 위치 표기.
- **code-reviewer 1차(첫 수행) Critical 2건 — 사용자 결정으로 이번 슬라이스에 반영**:
  ① **canonical이 같은 이동 둘을 구분할 근거가 없다.** 서로 다른 제출이 같은 지갑·자산·목적지·금액이면,
  A의 전송 알림만 먼저 온 상태에서 B의 이동이 A에 붙는다. 이력 조회·`TransferRequest` 양쪽을 다시 확인했지만
  **벤더가 이동과 제출을 잇는 키를 주지 않는다**(`WalletHistoryEvent`에 전송 요청 ID 없음, `TransferRequest`에 이동 순번 없음).
  → **같은 canonical의 제출이 아직 hash를 못 받았으면 붙이지 않고 보류**한다(그쪽 알림이 오면 해소되므로 격리가 아니라 재시도).
  ② **후보 판정과 상태 전이가 원자적이지 않다** — `(ntwk_cd, tx_hash)`에 유일 제약이 없어 조회와 전이 사이에 같은 hash 행이 새로 삽입될 수 있고,
  기존 행의 `FOR UPDATE`로는 막히지 않는다. → 거래를 만드는 쪽과 붙이는 쪽이 **같은 `(network, txHash)` advisory lock**을 잡는다.
- **2차 지적 반영(Critical 2)**: ① 미결 제출 배제 조회가 목적지를 **정확 일치**로 비교해, EVM checksum 표기만 다른 같은 주소의 제출을 놓쳤다 —
  안전조건이 그 경우 다시 열린다. 이 조회는 "배제하지 못하면 붙이지 않는다"의 입력이라 **붙임 규칙보다 넓게**(소문자 비교) 보는 것이 맞고, 그렇게 고쳐 영속 테스트로 고정했다.
  ② advisory lock에 **입금 판단이 참여하지 않았다** — 입금도 같은 `(network, tx_hash)`로 거래 행을 만든다. 경계 획득을 `decide` 진입부로 옮겨 모든 온체인 사건이 참여한다.
  Major 2건(정본·KDoc의 네 번째 조건 누락, "전송 알림이 오면 해소된다"는 과한 보장)과 Minor 1건도 함께 반영했다 —
  전송 알림의 `txHash`는 선택이라 hash 없이 올 수 있고 장기 `REQUESTED`도 미결로 남으므로, 해소가 보장되지 않고 창을 넘기면 격리된다는 경계를 적었다.
- **Improvement 1(실제 결합 검증) 일부 반영**: 이번 슬라이스의 핵심인 **직렬화 경계를 실제 PostgreSQL 두 연결로 고정**했다 —
  경계를 쥔 쪽이 커밋하기 전에는 같은 `(network, hash)`의 새 행이 들어오지 못하고, 다른 `(network, hash)`는 서로 막지 않는다.
  입금 판단도 같은 경계를 잡는지는 판단 단위 테스트로 고정했다.
  **Improvement 3(전체 트랜잭션 결합 테스트) 반영**: `DfnsWebhookDecisionSliceIntegrationTest`(`@DataJdbcTest` + 명시 `@Import`,
  별도 Dfns 데이터셋)로 `markSubmitted → bcm_tx_l → outbox → inbox S`가 **함께 커밋**되고, outbox 적재만 실패해도 넷이 **모두 되돌아오며**
  실패 기록은 업무 트랜잭션 밖이라 남는 것을 실제 PostgreSQL로 고정했다. 벤더 재전달은 알림 ID가 달라 인박스 dedup이 막지 못하므로
  **같은 상태의 관찰이 outbox를 다시 쌓지 않는 것**과, 발신 이동 사건이 그 거래에 붙어 확정을 내는 경로까지 한 흐름으로 관통한다.
  조립은 실행 앱과 같은 `DfnsWebhookDecisionConfig`를 그대로 쓰고 **체인 head만 대역**이다(위탁 RPC는 외부 호출이다).
  슬라이스는 실행 앱이 훑지 않는 패키지에 둔다 — 안에 두면 시험용 빈이 전체 컨텍스트에 섞여 Fireblocks 조립의 단일 후보 주입이 깨진다
  (`@TestConfiguration`으로도 걸러지지 않아 실제로 깨졌다). 테스트 전용 Boot 관리 스타터 1건은 별도 커밋이다.
- **code-reviewer 2차 Critical 1건**: 발신 귀속이 **가정이 깨질 때 fail-closed가 아니었다**. 원장 밖 전송 알림이 처리 완료로 소거되어 배제 증거가 남지 않고,
  벤더가 원장 밖 요청 B를 우리 제출 A와 같은 트랜잭션으로 합치면 B의 이동이 A에 붙는다 — 제출 원장만 보는 배제 조회로는 막지 못한다.
  ① **원장 밖 전송 알림을 격리**해 원문과 사실을 남긴다(소거하면 같은 트랜잭션의 이동을 나중에 판단할 근거도 사라진다).
  ② 이 가정을 운영 수용 항목이 아니라 **`BCM_PROVIDER=dfns` 기동 차단 해제의 선행 검증 조건**으로 올렸다 — 이력 조회로 확인하기 전에는 발신 확정을 운영에 열지 않는다.
- **Improvement 반영**: 배제 조회의 case-fold를 **EVM 주소 형태일 때만** 적용한다(base58까지 접으면 서로 다른 Solana 주소가 같아져 정상 건이 부당하게 막힌다).
  직렬화 테스트의 실행기 누수와 `sleep` 의존을 없애고 contender-ready latch로 결정적으로 바꿨으며, network가 다른 경우도 함께 고정했다.
- 검증: 신규 결합 4건 포함 전체 1,367 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과. 벤더 실호출 없음.
  (`DepositEventKafkaIntegrationTest`가 한 번 Kafka 컨테이너 타이밍으로 실패했고 재실행에서 통과했다 — 환경 플레이크다.)
- **code-reviewer 3차 Critical 2건 — 전제를 바꿨다(2026-09-17 사용자 확정)**:
  ① 격리는 **증거를 남길 뿐 붙임 판단이 그것을 읽지 않아** 원래 경로가 그대로 열려 있었다(B의 이동이 canonical 일치로 A에 붙는다).
  ② 결합 테스트의 롤백 단언이 **마지막 쓰기까지 닿지 않았다** — outbox 에서 실패시키면 인박스 `S` 갱신은 아예 시도되지 않아
  "인박스가 P"가 롤백의 증거가 못 되고, `markProcessed`가 별도 트랜잭션으로 빠지는 회귀도 통과했다.
- ②는 **마지막 쓰기를 실패시키는 경우**를 더해 닫았고, ①은 **설계를 바꿔** 닫았다. 근본 원인은 증명할 수 없는 물음을 확정의 관문으로 세운 것이다 —
  "이 **이동**이 제출 A의 것인가"는 상관관계 키가 없어 증명할 수 없는데, 값이 닮았는지로 대신 판정하고 있었다.
  확정에 필요한 것은 그 물음이 아니라 **"`txHash`가 몇 번 블록인가"**이고, 그건 어느 이동이 실어 왔든 답이 같은 블록의 사실이다.
  귀속은 **벤더가 우리 `TransferRequest`에 결속해 준 `txHash`**가 이미 해결했다(전송 알림이 `bcm_tx_l`에 적는다).
- 그래서 **이동을 제출에 귀속시키지 않는다.** 그 hash를 가진 우리 발신 거래(`ext_tx_id`가 있는 행) 전부에 같은 좌표를 적용한다 —
  여럿이어도 모두 같은 블록이라 "후보 여럿"이라는 모호함 자체가 없어지고, 제출은 그 거래에 적힌 **제출 키로 직접** 찾는다.
  **없어진 것**: canonical 일치 판정·단일 후보 판정·미결 제출 배제 조회(`existsUnresolvedWithSameCanonical`)와 그 EVM case-fold 분기.
  **남는 것**: `(network, txHash)` advisory lock(팬텀 삽입 방어는 그대로 필요), V29 backoff, 원장 밖 전송 알림의 격리(이상 신호로서).
  기동 차단 해제의 선행 조건도 "한 트랜잭션 안의 같은 canonical 이동은 우리 것"이라는 미검증 가정에서
  **`TransferRequest.txHash`가 그 전송이 실제로 포함된 트랜잭션의 hash인지 확인**으로 바꿨다. 추가 RPC 호출은 없다 — 체인 head 조회는 지금과 같다.
- 검증: 신규 결합 7건 포함 전체 1,361 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과. 벤더 실호출 없음.
- **design-sync 4차 Major 2건**: ① 계약13 안에 폐기한 "네 조건 붙임"이 세 곳(논리 거래 식별자 표·판단 범위·확정 대조표)에 현행 규칙으로 남아 정본이 서로 모순됐다.
  ② `FAILED` 재시도의 선행 조건을 "발신 확정의 블록 좌표로 체인 미제출을 확인"이라고 적었는데 **그 기능은 미제출을 증명하지 못한다** —
  관찰된 hash의 깊이만 말하고, 사건이나 hash가 오지 않았다는 사실은 웹훅 유실·지연과 구분되지 않는다.
  ①은 세 곳을 새 계약으로 고쳤고, ②는 **확인 수단이 아직 없다**고 바로잡고 후보(이력 조회·위탁 RPC)와 "없음을 증명하는 범위를 먼저 정한다"를 02·13·PLAN에 적었다.
  Minor(삭제한 타입·anchor가 문서·KDoc에 잔존)도 함께 정리했다. 코드 동작 변경은 없다.
- **converge 통과(2026-09-17)**: Codex(`gpt-5.6-sol high`) 독립 읽기 전용 세션이 design-sync → code-reviewer를 순차 수행해 **둘 다 통과**했다
  (Critical 0·Major 0). 검토 기준 커밋은 `1be0c40`, 델타 `14bc4f2..1be0c40`, 설계 전제 변경 전체 `300be08..1be0c40`, 슬라이스 전체 `98bde80..1be0c40`.
  리뷰어는 3차 Critical 2건이 닫힌 것을 역으로 확인했고(귀속 구멍은 `ext_tx_id` 보유 행 전부 적용 + 제출 키 직접 조회로, 롤백 단언은 인박스 `S` 갱신 실패로),
  제거한 방어(canonical 대조·단일 후보·배제 조회)가 이동 귀속을 판정할 때만 필요했으므로 제거가 타당하다고 판정했다.
  Minor 3건(제출 정책 KDoc의 옛 "발신 이동 대조" 문구, 바뀐 절 이름 잔존, 설계12 EOF 빈 줄)은 `f7afbd6`으로 반영했다 — 주석·문구뿐이고 동작 변경은 없다.

## Dfns `Failed` 재시도 안전성 — 벤더 문의 기록 (2026-09-18)

- 출처는 **Dfns 문서 어시스턴트 답변**이며 공개 명세·공식 가이드와 구분한다. Baseline 실측이 아니다. 계약은 [계약13](13-dfns-contracts.md#failed의-뜻과-재시도-안전성--벤더-문의-결과-2026-09-18).
- **왜 물었나**: DF3.27 리뷰에서 "`FAILED` 재시도를 열려면 체인 미제출을 확인해야 하는데 그 수단이 없다"가 드러났고,
  문의 준비 중 **Dfns 문서끼리 어긋나는 것**을 찾았다 — 공식 Idempotency 가이드는 "`Failed` = on-chain finality(서명·브로드캐스트·블록 포함)"라 하고,
  OpenAPI `status` 표와 Cancel/Abort 엔드포인트는 "브로드캐스트 전 `Failed`가 있다"고 한다. 특히 **Abort는 그런 `Failed`를 만드는 기능**이다.
- **답**: ① 브로드캐스트 전 실패도 `Failed`다(`Rejected`가 아니다) ② Idempotency 가이드의 문장은 **보편적으로 참이 아니다**(벤더 쪽 인정)
  ③ `Failed`를 본 뒤 그 트랜잭션이 채굴되지 않는다는 보장이 없고 **두 하위 경우를 구분하는 필드가 `TransferRequest`에 없다**
  ④ EVM은 Cancel Transfer가 의도된 수단이나 이미 브로드캐스트된 건에는 성공이 보장되지 않으며 **비EVM 대응물은 없다**.
- **설계에 준 영향**: 조회로 판정하는 안을 버렸다 — 필드 부재를 근거로 재시도를 열면 보증 없는 추론 위에 이중 지급 방어를 세우게 된다(DF3.27에서 이동 귀속을 버린 것과 같은 이유다).
  취소 단독도 절대 보장이 아니므로, **취소 → 그 nonce의 결말을 블록 깊이로 확정 → 새 키 발급**으로 짠다.
  세 경우가 모두 안전해진다 — 예약 nonce 소각 / mempool 대체 / 이미 채굴됐다면 그 `Failed`는 revert 종결이라 자금이 움직이지 않았다.
- **미해결**: 취소 대체 트랜잭션의 가스 부담 주체와 `replacementId` 추적, `externalId` 50자 안의 새 키 규칙, 벤더 키와 `ext_tx_id`의 원장 분리, 비EVM 경로.
- 코드 변경 없음 — 현재도 `FAILED` 재시도는 `422`로 거절한다.
- **2차 문의(2026-09-18, 같은 출처)**: 취소의 nonce 소각은 브로드캐스트 전 경우에 **단서 없이** 서술돼 있고("success is not guaranteed"는 브로드캐스트된 경우 한정),
  이미 브로드캐스트된 전송에도 **취소가 거절되지 않고 같은 nonce로 대체를 시도**한다 — 호출 결과로 두 경우를 가릴 수 없으므로 **결말을 체인에서 읽는** 설계가 그대로 유지된다.
  `externalId` 조회는 **없음**이 확인돼 DF3.24의 "같은 본문 재제출이 회수 수단" 결정이 굳었다. 전송이 한 온체인 트랜잭션으로 합쳐지는지는 **문서가 긍정도 부정도 하지 않아**
  기동 차단 해제 선행 조건을 유지하되, batching은 우리가 쓰지 않는 Sign & Broadcast(`UserOperations`) 구성이라 위험 표면은 좁다.
  `wallet.transfer.failed` 웹훅이 **Get Transfer와 같은 전체 `TransferRequest`**를 실어 오므로 판정에 되조회가 필요 없다.
- **답변자의 논리 하나는 채택하지 않았다** — 브로드캐스트 전 실패를 "never signed"라고 설명했는데 취소 문서 1단계는 "the original transfer's **signed data**"에서 nonce를 꺼낸다고 한다.
  서명된 트랜잭션이 존재하는 경우가 있다는 뜻이라 "서명이 없어 안전하다"는 설명은 성립하지 않는다. 안전의 근거는 **nonce 소각** 하나다.
- **새로 드러난 구현 범위**: 취소 응답은 `TransferRequest`가 아니라 **`TransactionRequest`**이고 생명주기가 **`wallet.transaction.*`** 계열이다 —
  현재 파서는 `wallet.transfer.*`와 `wallet.blockchainevent.*`만 읽으므로 대체를 추적하려면 **관찰 경로를 하나 더** 만들어야 한다.
- **여전히 미해결**: 취소의 가스 부담 주체, 정책 승인 경유 여부와 대체가 거절될 때 예약 nonce의 운명, 취소 호출의 멱등성, 이미 채굴된 건의 응답, 비EVM 경로.
- **3차 문의(2026-09-18, 취소 상세)**: nonce 동일성은 문서화된 메커니즘이고 **그 대체가 확정되면 원본이 채굴될 수 없다는 건 체인의 nonce 의미에서 따라온다** —
  벤더의 약속이 아니라 체인의 성질이라 가장 단단한 근거다. 반면 **가스 부담 주체·정책 승인과의 관계·취소의 멱등성·이미 채굴된 건의 응답·비EVM 대응물은 전부 "문서에 없음"**이고,
  취소는 **요청 본문이 없어** 수수료·우선순위를 넘길 수단조차 없다.
- **그럼에도 설계는 흔들리지 않는다** — 우리 경로가 fail-closed이기 때문이다. 대체가 확정되지 않으면 새 키를 발급하지 않으므로,
  가스가 없든 정책이 거절하든 결과는 "재시도를 열지 않음"이고 자금 사고가 아니다. 문서 공백은 **정확성이 아니라 가용성**의 문제라 Baseline 수용 항목·운영 절차로 옮겼다.
- **비EVM은 닫아 둔다** — Solana에는 취소 대응물도, 원본이 결제될 수 없음을 확인할 방법도 문서에 없다. 별도 계약 전까지 그 네트워크의 `FAILED` 재시도는 `422`다.

## Dfns 내부이체 계약 확정 (2026-09-18)

- 계약은 [계약13 내부이체](13-dfns-contracts.md#내부이체--확정)·[02 신규 키 선행 검사](02-bcm-flow.md#신규-키-선행-검사--제공자-공통-2026-09-18-사용자-확정)·[03 V30](03-bcm-db.md#v30-제출-목적지-주소-보관--물리-저장-계약). **코드 변경 없음**.
- **왜 계약부터인가**: Fireblocks는 목적지를 vault ID로 넘겨 벤더가 주소를 고르지만 Dfns 본문은 `to`(주소)뿐이라 **우리가 해소**해야 하고, 그 순간 세 가지가 새로 정해져야 했다 —
  해소 실패의 응답, 자기 계정 전송, 그리고 해소한 주소를 어디에 적을지. 온체인 여부는 차이가 아니다(Fireblocks vault↔vault도 온체인이다).
- **V28의 구멍이 드러났다**: 회수(같은 본문 재제출)가 `rcv_vl`을 `to`로 쓰는데 `ACCOUNT` 수신자는 그게 accountId다 — 내부이체를 열면 **accountId를 주소 자리에 보낸다**.
  canonical이 "본문을 재구성하는 재료 한 벌"인데 `to`가 빠져 있었고 주소 수신자만 지원하는 동안 드러나지 않았다. **V30 `vndr_dst_addr`**로 분리하고 `rcv_vl`은 논리 목적지로 남긴다.
  같은 이유로 **Dfns 이벤트의 `to`도 이 값을 쓴다** — 그러지 않으면 내부이체 이벤트에 accountId가 나가 공개 계약(`to`는 온체인 주소)과 어긋난다.
- **수신측 중복 입금**을 함께 계약했다(사용자 선택). Dfns는 관리 계정 간 이동을 송신 `Out`·수신 `In` 양쪽으로 알리는데 귀속은 발급 주소로 온 모든 `In`을 입금으로 가른다 —
  그대로면 한 번의 내부이체에 `INTERNAL`과 `DEPOSIT`이 둘 다 나가 DAW-CORE가 없는 입금을 인정한다. 같은 hash에 우리 제출 거래가 있으면 입금을 만들지 않고,
  아직 결속이 없는데 발신 주소가 우리 발급 주소면 **보류**한다 — 이벤트는 취소가 안 되므로 확정보다 보류가 맞다.
- **자기 계정 전송 거절은 제공자 공통**으로 올렸다(사용자 선택). 현재 Fireblocks 경로는 막지 않는다 — 같은 요청이 제공자에 따라 다르게 답하면 안 된다.
  전환 시 이미 `REQUESTED`인 동일 계정 행의 **회수는 계속 허용**한다.
- **공개 계약**: OpenAPI 0.14.0 — `/transactions`에 `422`를 더하고 `UNPROCESSABLE_ENTITY` 설명을 "현재 리소스 상태·선행 조건 때문에 처리 불가, 조건이 갖춰지면 같은 요청이 유효"로 일반화했다.
  기존 설명이 sweep의 source event 조건에만 한정돼 있었다.
- **진행 방식 변경**: 선택할 부분은 **Codex 세션에 먼저 자문하고 사용자에게 선택지를 드린다**(2026-09-18 사용자 지시). 이 슬라이스의 세 결정이 첫 적용이며,
  Codex가 셋 다 동의하면서 수신측 중복 입금·이벤트 `to`·오류 코드 11종(문서엔 6종으로 낡음)을 함께 짚었다.
- **design-sync Critical 3건 반영**:
  ① V30이 컬럼만 더하고 기존 행을 두면 **V28로 만든 기존 Dfns `REQUESTED` 행의 회수가 불가능해진다**(회수는 항상 새 컬럼을 읽는다) —
  기존 행은 전부 `ADDRESS` 수신자라 `rcv_vl`이 곧 보낸 주소이므로 **백필**하고, canonical 제약도 **다섯 값 all-or-none**으로 넓혔다(넷만 검사하면 목적지만 빠진 snapshot을 허용한다).
  ② 선행 검사가 **기존 키 판정보다 앞서면 멱등 계약이 깨진다** — 같은 키·다른 내용의 `409`가 `400`으로 가려지고 기존 `SUBMITTED`의 `202`가 막힌다.
  **기존 키 판정이 항상 먼저**이고 선행 검사는 **새 키에만** 적용한다고 순서를 명시했다. 기존 행의 동일 계정 요청은 `REQUESTED` 회수 허용 / `SUBMITTED` `202` / `FAILED` 재시도 금지로 갈랐다 —
  **회수는 이미 벌어진 일의 불확실성 해소이고 재시도는 새 자금 이동**이다.
  ③ 발신 주소 판정을 **자산 발급 기록이 아니라 네트워크 지갑 소유권**으로 바꿨다(사용자 선택). Dfns 제출은 그 `symbol`의 주소 발급을 요구하지 않으므로,
  발급 기록으로 물으면 다른 토큰으로 지갑만 만들어진 계정의 내부이체를 **외부 입금으로 확정해 버린다**. V30에 `(orgn_id, ntwk_cd, lower(wlt_addr))` index를 함께 둔다.
- Minor 2건도 반영: `.claude/rules/error-handling.md`의 "`error.code` 6종"(실제 11종)을 개수 대신 정본 참조로 바꾸고, 전송 어댑터 표의 "내부이체는 별도 계약 전" 문구를 "계약 확정·구현 후속"으로 고쳤다.
- 검증: 전체 1,361 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과. OpenAPI 생성물 재생성.

## Dfns 내부이체 제출 구현 (2026-09-18)

- 계약은 [계약13 내부이체](13-dfns-contracts.md#내부이체--확정)·[02 신규 키 선행 검사](02-bcm-flow.md#신규-키-선행-검사--제공자-공통-2026-09-18-사용자-확정)·[03 V30](03-bcm-db.md#v30-제출-목적지-주소-보관--물리-저장-계약).
- **계정 목적지를 우리가 해소한다** — Dfns 본문은 `to`(주소)뿐이다. 발급 주소가 없으면 `422`이고 **제출 원장 행도 만들지 않는다**(발급 뒤 같은 키로 정상 제출).
  화이트리스트 지갑은 여전히 받지 않는다.
- **V30으로 목적지 주소를 분리했다.** 회수는 이제 `vndr_dst_addr`를 읽는다 — 전에는 `rcv_vl`을 `to`로 썼는데 내부이체에서는 그게 accountId라 **주소 자리에 계정이 나갔다**.
  `rcv_vl`은 논리 목적지로 남겨 "어느 계정으로 보냈나"를 잃지 않는다. 기존 Dfns 행은 전부 `ADDRESS` 수신자라 `rcv_vl`로 **백필**하고 canonical 제약을 다섯 값 all-or-none으로 넓혔다.
  **이벤트의 `to`도 이 값을 쓴다** — 02·공개 계약에서 `to`는 온체인 주소다.
- **기존 키 판정을 주소 해소보다 앞으로 옮겼다.** 뒤집으면 같은 키·다른 내용의 `409`가 `400`으로 가려지고 기존 `REQUESTED`가 현재 발급 상태 때문에 회수되지 못한다.
  `req_hash`도 **논리 목적지**로 만든다 — 해소한 주소로 만들면 주소가 재발급될 때 같은 업무 요청이 다른 요청으로 보인다. 주소 수신자는 논리값이 곧 주소라 해시가 달라지지 않는다.
- **자기 계정 전송 거절은 제공자 공통**이다. 처음에 Fireblocks 경로에 원장 조회를 무조건 추가했다가 기존 테스트 둘이 깨졌다 —
  판정을 도메인의 순수 함수로 먼저 보고 **자기 전송일 때만 원장을 읽도록** 고쳤다. 보통 요청에는 조회가 늘지 않는다.
  `REQUESTED`·`SUBMITTED`는 통과시키고 `FAILED` 재시도만 막는다.
- **범위 밖**: 수신측 `In` 중복 입금 방지(다음 슬라이스), 화이트리스트 목적지, 대납. `BCM_PROVIDER=dfns` 전체 기동 차단도 그대로다.
- 검증: 신규 13건 포함 전체 1,374 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과. 벤더 실호출 없음.

## Dfns 수신측 중복 입금 방지 구현 (2026-09-18)

- 계약은 [계약13 내부이체](13-dfns-contracts.md#내부이체--확정)의 "수신측 중복 입금 방지". 구현은 도메인 순수 규칙 `NetworkChainIncomingReceipt`와 `DfnsChainEventDecision`의 입금 분기다.
- **무엇이 문제였나**: 벤더는 관리 계정 간 이동을 **송신 `Out`·수신 `In` 양쪽**으로 알리는데 [귀속](13-dfns-contracts.md#온체인-이동의-귀속--구현)은 발급 주소로 들어온 모든 `In`을 입금으로 가른다.
  내부이체를 여는 순간 한 번의 이동에 `INTERNAL`과 `DEPOSIT`이 둘 다 나가 **DAW-CORE가 있지도 않은 입금을 인정**한다.
- 같은 `(ntwk_cd, tx_hash)`에 **우리 발신 거래**(`ext_tx_id`가 있는 행)가 있으면 입금을 만들지 않고 닫는다. 판정은 발신 좌표와 같은 규칙을 재사용한다(`NetworkChainOutgoingCoordinate`) — "우리 발신 거래"의 정의를 한 곳에 둔다.
- 아직 결속이 없는데 **발신 주소가 우리 네트워크 지갑**이면 **보류(재시도)**다. 이벤트는 취소가 안 되므로 확정보다 보류가 맞다 — 전송 알림이 오면 위 분기로 해소되고 상한까지 안 오면 격리된다.
- **발신 판정은 소유권으로 한다.** 자산 발급 기록(`bcm_addr_m`)으로 물으면 다른 토큰으로 지갑만 만들어진 계정의 내부이체를 **외부 입금으로 확정해 버린다** — 제출은 그 `symbol`의 주소 발급을 요구하지 않는다.
  조회는 V30의 `(orgn_id, ntwk_cd, lower(wlt_addr))` index를 쓰고 현재 범위가 EVM이라 대소문자를 무시한다.
- 저장소 직접 참조를 피하려고 `NetworkWalletQueryService`를 두고 조립이 그 서비스를 연결한다(`docs/standards/architecture.md`).
- **도착 순서 검증**: 세 사건(발신 · 전송 알림 · 수신)의 **여섯 순열을 전부** 슬라이스에 넣었다. 어느 순서든 거래 행 하나가 `FINALIZED`로 남고
  outbox에는 `internal-events`만 쌓인다. 순서 독립은 **구조적 보장이 아니다** — 전송 알림이 기준 거래 행을 만들고 나머지 둘이 그 행을 읽으므로,
  전송 알림보다 먼저 온 사건은 **보류(재시도)로 남았다가 뒤에 수렴한다**. 그래서 대표 순열이 아니라 전수로 고정했다.
  대기가 0인 슬라이스에서는 재시도가 즉시 다시 집혀 순서가 고정되지 않으므로 **실제 설정의 V29 대기를 테스트가 흉내 낸다**.
- **범위 밖**: 화이트리스트 목적지, 대납, Sweep. `BCM_PROVIDER=dfns` 전체 기동 차단도 그대로다.
- 검증: 신규 12건 포함 전체 1,386 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과. 벤더 실호출 없음.
- **design-sync Critical 1건·Major 2건 반영(2026-09-18)**:
  ① **선행 검사가 내용 대조보다 앞서 멱등 판정을 가렸다** — 같은 키·다른 내용의 `409`가 `400`으로 바뀌고, Dfns는 자기 계정 `FAILED`에 `422`를 줘
  "제공자 공통"이라던 정책이 제공자별로 갈렸다. 기존 행이 있으면 선행 검사가 답하지 않게 하고, **내용 대조 뒤 `FAILED` 분기에서** 양쪽 모두 `400`으로 답하도록 옮겼다.
  ② 정본 네 곳이 내부이체를 아직 후속으로 서술하고 있어 구현 상태와 반대였다 — 함께 고쳤다.
  ③ **보류가 영구 격리로 이어지는 경로**를 인정하고 기동 차단 해제 선행 조건에 넣었다: 재시도 창(기본 약 90초)을 넘겨 `F`가 되면
  집기 쿼리가 `P`만 읽으므로 뒤늦은 전송 알림이 거래를 만들어도 **그 사건은 다시 실행되지 않는다**. 창을 늘리는 것으로는 닫히지 않으므로 재처리 경로를 먼저 계약한다.
  세 번째 도착 순서도 슬라이스에 더했다 — 출금의 발신 사건 선도착(확정 생존성)과, **내부이체의 세 사건(발신 사건 → 전송 알림 → 수신 사건)**이 어떤 순서로 와도 `internal-events` 한 계열만 남는 경우다.
  Minor(끊어진 anchor, 지갑 소유권 조회 주석)도 고쳤다. **남긴 것**: V29 상태에 기존 행을 넣고 V30을 적용하는 백필 업그레이드 테스트는 후속이다.
- 검증(반영 후): 전체 1,389 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과.
- **design-sync 2차 반영(2026-09-18)**: Critical 1이 좁은 의미로만 닫혔다는 지적을 받아 **Fireblocks 경로를 계약대로 고쳤다**(사용자 결정: 지금 고친다).
  `prepare()`가 원장 조회보다 먼저 돌아 기존 키의 멱등 응답·`409`·회수가 **현재 자원 오류에 가려질 수 있었다**.
  요청만으로 정해지는 값(`LogicalSubmission`: 수신자 종류·값·거래 구분)과 벤더 자원(vault·자산 매핑·목적지)을 나누고,
  벤더 자원은 **실제로 제출할 때만** 읽도록 지연시켰다. 판정·해시·게이트·claim은 논리값만 쓴다. `submitManaged`(밴드S)는 이미 만든 값을 그대로 넘긴다.
  기존 `SUBMITTED`가 **현재 자산 매핑이 사라져도 최초 `txId`로 답하는지**를 테스트로 고정했다.
  Major 2건도 닫았다 — 정본 네 곳에 남아 있던 "내부이체는 후속"을 마저 고치고, 세 번째 도착 순서를 **내부이체 세 사건**(발신 사건 → 전송 알림 → 수신 사건)으로 실제 검증하도록 슬라이스를 더했다.
  이전 기록의 "세 번째 도착 순서" 주장은 출금 확정 생존성만 덮고 있었다.
- 검증(2차 반영 후): 전체 1,391 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과. **남긴 것**: V29→V30 백필 업그레이드 테스트(Minor, 별도 슬라이스).
- **design-sync 3차 반영(2026-09-18)**: 2차 수정이 **절반만 고친 것**이라는 지적을 받아 나머지를 닫았다.
  ① `prepare()`를 세 갈래로 미뤘지만 그중 하나만 제대로 늦춰져 있었다 — **신규 키**는 `REQUESTED` 행과 소유권을 **먼저 넣고** 자원을 읽어
  자원이 깨져 있으면 제출할 수 없는 행과 살아 있는 소유권이 남았고(02 "원장에 행을 만들기 전에"와 반대),
  **기존 `REQUESTED` 회수**는 인자 평가 때문에 자원 조회가 벤더 조회보다 앞서 현재 매핑이 깨진 것만으로 이미 벤더에 있는 거래를 못 찾았다(02 "벤더에 `externalTxId`로 조회부터").
  벤더 자원 조회를 `initialAttempt`의 **삽입 직전, 같은 트랜잭션 안으로** 옮겨 읽기를 늘리지 않고 경합 창도 없앴고, 회수는 **벤더 조회 뒤에** 자원을 읽는다.
  `submitManaged`(밴드S)의 자산 매핑도 같은 이유로 제출 시점으로 미뤘다.
  ② **Dfns가 `404`와 `422`를 가르지 못했다** — 목적지 계정 존재를 보지 않고 주소 조회가 비면 전부 `422`였다.
  계약13이 "계정 없음 `404` · 주소 미발급 `422`"로 나누고 Fireblocks는 이미 그렇게 답하므로, 새 키 준비에만 계정 존재 확인을 넣었다.
  ③ **도착 순서 주장이 검증 범위보다 넓었다** — 세 사건의 여섯 순열을 전부 검증하도록 슬라이스를 바꿨다.
  순서 독립이 **구조적 보장이 아니라 재시도로 수렴하는 성질**임을 확인해 정본 문구도 그렇게 고쳤다.
  ④ 03의 현행 `CREATE TABLE bcm_sbmt_l`과 계약13 "본문 재구성" 표가 V30 이전(네 값)에 머물러 같은 문서 안에서 충돌했다 — 다섯 값으로 맞췄다.
  Minor(계약13이 Fireblocks를 아직 "막지 않는다"고 현재형으로 서술)도 고쳤다.
- 검증(3차 반영 후): 전체 1,397 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과.
  **남긴 것**: V29→V30 백필 업그레이드 테스트(Minor, 별도 슬라이스).
  **남은 경쟁 조건**: 최초 원장 조회 뒤 선행 검사 중 다른 요청이 같은 키를 넣으면 그 삽입이 선행 검사 오류에 가려진다(TOCTOU).
  선행 검사 실패 직전 원장 재조회나 키 단위 직렬화가 필요하며 별도로 계약한다.
- **design-sync 4차 반영(2026-09-18)**: 3차 수정이 출금에만 적용됐다는 지적을 닫았다.
  ① `initialAttempt`의 기존 행 조회가 **`WITHDRAWAL` 조건 안에만** 있어 내부이체·밴드S는 기존 키가 있어도 `prepare()`가 먼저 돌고
  INSERT 충돌로 뒤늦게 알아냈다. 매핑이 해제된 기존 밴드S·내부이체 키가 최초 `txId` 대신 자산 오류로 막혔다 —
  "기존 키 판정이 항상 앞선다"(02)가 거래 구분에 따라 갈렸던 것이다. 조회를 거래 구분 밖으로 꺼냈다.
  ② **Dfns에서 자산 미지원 `400`이 주소 미발급 `422`에 가려졌다** — 목적지 해소가 자산 매핑 조회보다 앞서, 미지원 자산이면서 주소도 없는 요청이 `422`가 됐다.
  미지원 자산은 주소를 발급해도 해소되지 않으므로(02) 매핑 조회를 앞으로 옮겼다.
  ③ 순열 파라미터화에서 **"전송 알림 뒤 수신 사건이 outbox를 늘리지 않는다"는 event-id 동일성 단언이 빠져** topic만 보고 있었다.
  보류를 하나씩 풀어 처리 순서를 고정하고, 수신 사건 전후의 event-id 목록이 같은지 그 건에서 직접 본다.
- 검증(4차 반영 후): 전체 1,400 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과.
- **code-reviewer 반영(2026-09-18)**: design-sync 통과 뒤 code-reviewer가 Critical 2건·Minor 1건을 냈다.
  ① **V30이 `transaction=off`인데 옛 CHECK를 먼저 지우고 새것을 만들었다** — 문장마다 커밋되므로 중간에 죽으면
  canonical 제약이 **아예 없는 상태**가 남고, 재실행은 이미 사라진 제약을 지우려다 또 죽는다.
  옛 제약을 끝까지 남긴 채 `ck_bcm_sbmt_vndr_canonical_v30`을 새로 만들어 `VALIDATE`한 뒤에야 옛 이름을 놓도록 바꾸고,
  모든 문장을 재실행 안전하게(`IF NOT EXISTS`·`IF EXISTS`) 만들었다.
  `CREATE INDEX CONCURRENTLY`는 **V31로 분리**했다 — `IF NOT EXISTS`는 실패로 남은 invalid index를 "있음"으로 보고 건너뛰어
  마이그레이션은 성공했는데 쓰이지 않는 인덱스가 남는다. V26처럼 먼저 지우고 다시 만든다.
  ② **`ownsWalletAddress`가 클래스의 `REQUIRES_NEW`를 그대로 물려받았다** — 수신 사건 판정은 이미 인박스 행과
  `(network, txHash)` 잠금을 쥔 채 부르므로, 새 트랜잭션을 열면 그 잠금을 쥔 채 커넥션을 하나 더 요구해 풀이 마른다.
  "피처 서비스는 바깥 트랜잭션에 참여한다"(`docs/standards/architecture.md`)에도 어긋난다. 이 조회만 `REQUIRED`·read-only로 재정의했다.
  ③ Minor — `PreparedSubmission`이 읽히지 않는 논리값 세 개를 들고 있어 `LogicalSubmission`과의 분리를 흐렸다. 벤더 본문에 필요한 값만 남겼다.
  ④ **①의 1차 수정도 완전하지 않았다** — 마지막에 옛 제약을 지우는 문장을 두면, 그 뒤에 죽었을 때 재실행이
  **유일하게 남은** `_v30`을 지우고 다시 만들다 또 죽어 canonical 제약이 하나도 없는 상태가 될 수 있다.
  옛 제약은 지우지 않고 함께 남긴다 — 다섯 값을 만족하면 넷도 반드시 만족하므로 충돌하지 않는다.
  ⑤ ④의 테스트에서 **접두사 단언은 실제로 완화였다** — PostgreSQL은 CHECK를 이름 알파벳순으로 평가하므로
  둘 다 위반하는 행은 접미사 없는 V28 제약이 먼저 걸린다. "정해져 있지 않다"는 내 전제가 틀렸고,
  접두사로 두면 옛 제약을 다시 지워도 `_v30` 오류가 같은 접두사를 포함해 통과한다.
  옛 이름을 **정확히**(닫는 따옴표까지) 단언해 그 이름이 나오는 것 자체를 옛 제약 생존의 증거로 삼고,
  `pg_constraint`에서 **두 제약의 공존을 직접 확인**하는 테스트를 따로 넣었다. **V30만이 막는 경우**
  (넷은 다 있고 목적지만 없는 행)도 그대로 고정한다.
- 검증(code-reviewer 반영 후): 전체 1,401 테스트 0 실패, 전체 ktlintCheck·`git diff --check` 통과. design-sync 4회·code-reviewer 3회를 거쳐 통과했다.
## 거래 조회 — Q0 계약 확정 (2026-09-18)

사용자 확정: **공개 거래 조회는 제공자 공통으로 BCM 원장만 읽는다. `createdAt`은 BCM이 처음 수용한 시각이다.
외부 공개 전환은 Fireblocks·Dfns 동시에 한다.**

**Q0의 범위는 공개 응답·상태 매핑·시각 의미까지다**(2026-09-18 사용자 확정). 백필 절차·전환 게이트·마이그레이션 상세는
Q1이 실제로 돌려 보며 확정한다 — Q0 리뷰 3라운드에서 Major가 6·5·6으로 줄지 않았고, 매번 직전 반영이 만든 후속 구멍이었다.
문서만으로 실행 세부를 미리 닫으려 한 것이 원인이다.

- 계약 정본: [02 거래 조회](02-bcm-flow.md#거래-조회--제공자-공통-2026-09-18-사용자-확정) · 물리 저장은 [03 V32](03-bcm-db.md#v32-거래-조회-원장--물리-저장-계약-2026-09-18-사용자-확정) · Dfns 쪽 근거는 [계약13](13-dfns-contracts.md).
- **혼합안은 검토했고 버렸다.** "단건만 벤더"는 출금에서는 되지만 입금에서 성립하지 않는다 — Dfns 입금의 공개 `txId`가
  벤더 ID가 아니라 BCM이 `txHash`+index로 만든 값이라 벤더로 되돌릴 경로가 없다.
- **Fireblocks 공개 응답이 바뀐다** — `createdAt`이 벤더 거래 시각에서 BCM 수용 시각으로, 목록 정렬 키도 함께 바뀐다.
  벤더 시각(`vndr_crt_dttm`)은 대사 전용으로 남는다. 이 차이를 제공자별 정상 계약으로 02·OpenAPI에 적지 않는다 —
  전환 전까지는 `dfns` 기동 차단이 유지되므로 외부 계약 차이가 실제로 노출되지 않는다.
- **공개 read 전환 조건은 Q0이 정하지 않는다.** Q0이 확정하는 것은 "전환은 두 제공자 동시에 한다"와
  "Q1이 끝나기 전에는 전환하지 않는다"까지다. 데이터 잔여·배포 순서 같은 조건은 실제로 돌려 봐야 정해지므로
  [Q1 착수 조건](#q1-착수-조건--초안)이 확정한다.
- **design-sync Major 6건 반영(2026-09-18)**: 내가 쓴 계약에 닫히지 않은 구멍이 여섯 있었다.
  ① 목록 귀속이 OpenAPI("출금은 계정별 목록에 없다")와 정면 충돌했다 — **고객 계정 목록에는 없고 출금 풀 계정 목록에는 있다**로 좁히고,
  sweep·밴드S는 공개 조회 전체에서 제외했다(공통 `ChainEvent`를 안 내는 내부 계열이라 이벤트에 없는 것이 조회에만 나오면 대조가 깨진다).
  거르려면 구분이 필요한데 **입금은 제출 행이 없어** join으로 못 거르므로 `bcm_tx_l.tx_dvcd`를 비정규화했다.
  ② **제출 원장 `FAILED`의 조회 응답이 없었다** — 제출 거절로 거래 엔티티가 안 생긴 건은 `404`, 수용된 거래가 실패한 건은 `200 FAILED`로 나눴다.
  ③ 제출 직후 거래 행 생성과 `vndr_crt_dttm NOT NULL`을 **동시에 만족시킬 방법이 없었다** — 제출 응답은 `txId`만 준다. `NULL`을 허용하고 첫 관찰에서 채운다.
  ④ 공개 `txId`를 여전히 "벤더 tx id"라고 설명하고 있어 Dfns 입금(BCM 생성 ID)과 맞지 않았다 — 제공자 중립으로 고쳤다.
  ⑤ **전환 전 발급 커서의 처리 계약이 없었다** — 정렬 키가 바뀌므로 구 커서를 그대로 쓰면 누락·중복이 난다. `400`으로 거절한다.
  ⑥ 주소 불일치의 결과가 "덮지 않는다"까지만 있었다 — **관찰 전체를 격리**(fail-closed)하고 체인별 비교 규칙을 함께 적었다.
  Minor 3건(13 절 위치, "상태는 전진만 한다" 표현, V32 온라인 인덱스 규율)도 고쳤다.
- **2차 Major 5건 반영(2026-09-18)**: 1차 반영이 만든 후속 구멍이었다.
  ① `tx_dvcd`를 새로 만들어 놓고 **백필 계약을 안 적었다** — 기존 행이 전부 `NULL`이라 predicate가 조용히 지운다.
  제출 건은 `bcm_sbmt_l.tx_dvcd`, 입금은 **실제 발행된 `DEPOSIT` 이벤트가 있을 때만**, 근거 없으면 격리로 정했다.
  ② **거래 행 자체가 없는 `SUBMITTED` 제출**이 전환 시점에 남는다 — 지금까지 거래 행은 웹훅이 와야 생겼다.
  백필이 그 행을 만들지 않으면 이미 수용된 출금이 `404`가 된다. 게이트에 넣었다.
  ③ sweep·밴드S를 목록에서 뺐지만 **단건·`externalTxId` 조회 결과를 안 적었다** — `404`로 닫고 OpenAPI에도 적었다.
  "제출 마감이 `SUBMITTED` outbox도 만든다"도 공개 유형에만 해당하도록 한정했다.
  ④ `vndr_crt_dttm`을 `NULL` 허용으로 바꿨는데 **Admin 거래 조사가 그 값을 필수로 파싱**한다 — Admin `vendorCreatedAt`도 nullable로 계약화했다.
  ⑤ 공개 `txId`의 "벤더 tx id" 문구가 03 ERD·이벤트 설명·컬럼표와 계약13 두 곳에 **남아 있었다** — 전부 제공자 중립으로 고쳤다.
  Minor(원장 조회로 바뀌었는데 페이지 상한 사유가 "벤더 한도"로 남음)도 고쳤다.
- **3차 Major 6건 반영(2026-09-18)**: 또 후속 구멍이었다.
  ① Admin BFF 생성 타입이 stale — `generate.py --check`가 실제로 실패했다. 재생성했다.
  ② Admin 거래 조사의 `vndr_crt_dttm` non-null 파싱을 Q2로 미루면 **Q1 백필이 만드는 `NULL` 행에서 바로 깨진다** — 백필과 같은 배포로 당겼다.
  ③ 거래 행 백필이 **선행 `SUBMITTED` outbox와 `frst_dtct_dttm`을 안 정했다** — 없으면 소비자가 `CONFIRMED`부터 받아 감지→확정 순서가 깨지고,
  실행 시각을 쓰면 과거 제출이 전부 최신 거래로 올라온다. `rsp_dttm` 기준으로 고정했다.
  ④ 게이트가 `NULL`과 행 부재만 봐서 **허용값 밖 `tx_dvcd`가 조용히 통과**할 수 있었다(허용값 `CHECK`는 전환 뒤에 건다).
  교차 원장 일치·선행 outbox·catch-up 잔여까지 여덟 항목으로 늘리고, 검사 범위를 `bcm_tx_l` 전체로 고정했다.
  ⑤ 입금 `tx_dvcd`의 근거가 **`bcm_outbox_l`뿐**임을 확인했다 — relay는 outbox를 지우지 않고 상태만 바꾸므로 근거가 남는다(내 우려는 틀렸다).
  `bcm_raw_tx_l`은 Fireblocks COMPLETED 원문만 담고 `EventType`을 보존하지 않아 대체 근거가 못 된다. 근거가 사라진 행은 추정하지 않고 전환을 막는다.
  ⑥ "벤더 tx id" 문구가 03 흡수 불가 설명·결정 이력·outbox 집합체 ID 주석에 **또 남아 있었다** — 세 곳 다 고쳤다.
  Minor 3건(공개 "세 유형"→"제출 가능한 두 유형", V32 "세 컬럼"→"네 컬럼", 미매핑 자산 제외 설명)도 고쳤다.
- **4차 반영(2026-09-18)**: 범위 좁히기가 덜 끝나 있었다 — Q0 본문의 활성화 게이트가 여전히 규범형이라
  같은 전환 조건이 **Q0 확정 계약과 Q1 초안 두 지위를 동시에** 가졌다. Q0은 "전환은 두 제공자 동시" ·
  "Q1 전에는 전환하지 않는다"까지만 정하고 나머지는 Q1에 완전히 넘겼다.
  Q1 착수 조건에 **`vndr_crt_dttm` null-safe 전환 순서**를 표로 더했다 — Admin만 고치면 `NULL` 행을 읽는
  Webhook·BAT가 먼저 깨진다(`TxRecord`·`TxEntity`·`TxJdbcAdapter`도 같은 배포). `DROP NOT NULL`이 백필보다 앞선다.
  Minor 3건(게이트 "셋"→전체, 계약13 끊어진 anchor, ERD `actv_tx_id` 표현)도 고쳤다.
- **5차 반영(2026-09-18)**: 내가 만든 모순 하나 — "`vndr_crt_dttm`을 첫 관찰에서 채운다"고 써놓고,
  03의 기존 set-once 규칙은 **그 컬럼을 갱신문에서 아예 빼라**고 한다. 실제 `TxJdbcAdapter`도 건드리지 않고
  `TxStateMachine`이 계산한 값을 persistence가 버린다. 그대로면 제출 마감이 만든 행은 **영원히 `NULL`**이고 대사에서도 빠진다.
  set-once의 **예외**로 `NULL → 값` 한 번만 허용한다고 03 두 곳에 적고, Q1 순서에 **첫 관찰 writer 배포를 백필보다 앞에** 넣었다.
- **6차 반영(2026-09-18)**: 그 writer를 `WHERE vndr_crt_dttm IS NULL`로 적은 것이 또 틀렸다 —
  행 조건으로 쓰면 시각이 채워진 거래의 **이후 상태·컨펌·hash·RBF 갱신이 전부 0행**이 되어 충돌이 된다.
  `COALESCE` 컬럼 단위 병합으로 바꿨다. 또한 활성 거래의 **`FAILED` 보류 분기**는 `candidate()`를 거치지 않아
  writer만 더해도 바인딩 값이 계속 `null`이다 — 수락된 모든 관찰 경로가 `previous ?: observation`을 넘기도록 조건에 넣고
  검증 대상 네 경로(일반·기존값 보존·RBF 승자·`FAILED` 보류)를 명시했다.
### Q1 착수 조건 — 초안

Q0은 **공개 응답·상태 매핑·시각 의미**까지만 닫는다. 아래는 Q0 리뷰 3라운드에서 나온 백필·전환 조건의 **초안**이며,
**Q1이 실제 마이그레이션과 함께 확정한다**. 문서만으로 미리 닫으면 실행 불가능한 계약이 남는다는 것이 3라운드의 교훈이다.

#### 백필 — 무엇을 어디서 채우는가

네 컬럼은 **nullable 추가 전용**으로 먼저 열고, 기존 행을 채운 뒤에야 공개 read를 전환한다.

- 출금·내부이체·sweep·밴드S는 `bcm_sbmt_l`에서 채운다 — `trsf_amt`, 목적지는 `vndr_dst_addr`(없으면 `rcv_vl`이 주소인 경우만), `tx_dvcd`는 `bcm_sbmt_l.tx_dvcd` 그대로.
- 입금은 제출 원장이 없다. 금액·주소는 `bcm_outbox_l` payload와 `bcm_raw_tx_l` 원문에서 채운다.
  `tx_dvcd`의 근거는 **`bcm_outbox_l`뿐이다** — 그 거래로 실제 `DEPOSIT` 이벤트를 만든 행이 있을 때만 `DEPOSIT`으로 적는다.

  ```sql
  o.vndr_tx_id = t.vndr_tx_id AND o.topic = 'deposit-events' AND o.payload ->> 'type' = 'DEPOSIT'
  ```

  `evnt_stcd = 'S'`를 요구하지 않는다 — 발송 대기·실패 이벤트도 **거래 유형은 이미 `DEPOSIT`**이다.
  relay는 outbox를 지우지 않고 상태만 바꾸므로 이 근거는 남아 있다.
  **`bcm_raw_tx_l`은 대체 근거가 아니다** — Fireblocks의 마지막 COMPLETED 원문만 담고 BCM의 `EventType`을 보존하지 않는다.
- `src_addr`는 제출 원장에 없다 — 발신 계정의 `bcm_ntwk_wlt_m.wlt_addr`(Dfns)나 관찰 원문에서 얻는다.
- **근거가 없는 행은 추정하지 않는다.** "`NULL`이면 입금"으로 읽는 것은 `tx_dvcd`를 비정규화한 이유("없는 근거로 판정하지 않는다")와 정면으로 어긋난다.
  근거가 사라진 행(외부 절차로 outbox가 정리된 경우 등)은 `tx_dvcd`를 `NULL`로 남기고 **전환을 막는다** — 아래 게이트가 0을 요구한다.
  이 행들을 예외 원장으로 옮겨 전환을 진행할지는 **실제 잔여 건수를 센 뒤 따로 정한다**. 추정으로 채워 통과시키지 않는다.

**전환 시점에 거래 행 자체가 없는 제출이 남는다.** 지금까지 `bcm_tx_l`은 웹훅이 와야 생겼으므로,
`bcm_sbmt_l`이 `SUBMITTED`이고 `vndr_tx_id`도 있는데 거래 행은 없는 건이 있다.
백필은 **그 행들을 만들어야 한다** — 만들지 않으면 이미 수용된 출금이 전환 직후 `404`가 되어 응답 계약("`SUBMITTED`면 `200`")을 어긴다.

만들 때 두 가지를 함께 정한다.

| 값 | 무엇으로 |
|---|---|
| `frst_dtct_dttm` | **`bcm_sbmt_l.rsp_dttm`**(벤더 접수 응답 시각). 마이그레이션 실행 시각을 쓰면 과거 제출이 전부 최신 거래로 올라와 목록 정렬이 뒤집힌다 |
| 선행 `SUBMITTED` outbox | 공개 유형인데 그 거래의 `SUBMITTED` 이벤트가 없으면 **함께 만든다**. 없으면 뒤이어 온 `CONFIRMED`·`FINALIZED`만 받게 되어 감지→확정 순서 계약이 깨진다 |
| `vndr_crt_dttm` | 채우지 않는다(`NULL`). 진짜 벤더 시각이 없고 지어내면 대사가 틀린 창으로 비교한다 |

#### 공개 read 전환 게이트 — 전부 0이어야 한다

아래가 **모두 0**이어야 전환한다. 하나라도 남으면 목록·단건이 조용히 거래를 빠뜨린다.

검사 범위는 **`bcm_tx_l` 전체**다. "공개 대상만"으로 좁히면 `tx_dvcd`가 없는 행을 공개 대상인지 판정할 수 없어 순환이 된다.

| 확인 | 왜 |
|---|---|
| `tx_dvcd IS NULL` 건수 | predicate가 그 행을 조용히 지운다. 공개 대상 여부를 판정할 근거 자체가 없다 |
| `tx_dvcd`가 허용 집합 밖인 건수 | 허용값 `CHECK`는 전환 뒤에 걸므로, 오타 같은 임의 값이 **`NULL` 검사를 통과한 채 predicate에서만 빠진다** |
| 제출 거래의 `bcm_tx_l.tx_dvcd <> bcm_sbmt_l.tx_dvcd` 건수 | 두 원장이 같은 거래를 다른 유형으로 안다 |
| 공개 대상 행의 `trsf_amt IS NULL` 건수 | 금액 없는 거래는 공개 응답을 만들 수 없다 |
| `bcm_sbmt_l`이 `SUBMITTED`·`vndr_tx_id` 있음인데 `bcm_tx_l`에 행이 없는 건수 | 수용된 출금이 `404`가 된다 |
| 공개 `SUBMITTED` 건에 선행 `SUBMITTED` outbox가 없는 건수 | 감지→확정 순서 계약이 깨진다 |
| 제출 원장과 거래 행의 `ext_tx_id`·계정·네트워크·심볼·금액 불일치 건수 | 백필이 엉뚱한 행을 이었다는 뜻이다 |
| Q2 writer 배포 뒤 **다시 돌린** catch-up 백필의 잔여 건수 | 백필과 새 writer 사이에 들어온 거래가 남는다 |

전환이 끝나 이것들이 0으로 유지되면 `tx_dvcd`에 `NOT NULL`과 허용값 `CHECK`를 **별도 후속 마이그레이션으로** 건다 —
V30에서 배운 대로, 제약 추가와 백필을 같은 파일에 섞지 않는다.

**`vndr_crt_dttm`을 읽는 모든 경로의 null-safe 전환이 이 백필보다 먼저 또는 같은 배포에 들어간다.**
백필이 만드는 행은 의도적으로 `vndr_crt_dttm`이 `NULL`이다. 순서도 함께 정한다.

| 순서 | 무엇 |
|---|---|
| 1 | `ALTER TABLE bcm_tx_l ALTER COLUMN vndr_crt_dttm DROP NOT NULL` — 백필이 행을 만들기 전에 |
| 2 | 공용 거래 읽기 경로를 nullable로 — `TxRecord.vendorCreatedAt` · `TxEntity` · `TxJdbcAdapter`. Webhook·BAT도 이 모델을 쓴다 |
| 3 | Admin 거래 조사 경로 — 어댑터·도메인·DTO·BFF 생성 타입 |
| 4 | **`vndr_crt_dttm` 첫 관찰 writer** — 아래 네 조건을 다 만족해야 한다 |
| 5 | 그 뒤에 거래 행 백필 |

4단계 writer의 조건:

- **컬럼 단위 병합**이다 — `SET vndr_crt_dttm = COALESCE(vndr_crt_dttm, :vendorCreatedAt)`.
  갱신문의 `WHERE`에 `IS NULL`을 넣으면 시각이 채워진 거래의 **이후 모든 갱신이 0행**이 되어 충돌로 처리된다.
- **수락된 모든 관찰 경로**가 `previous.vendorCreatedAt ?: observation.vendorCreatedAt`을 넘긴다 —
  일반 관찰·RBF 승자뿐 아니라 **활성 거래의 `FAILED` 보류 분기**(`TxStateMachine`)도 포함한다.
  그 분기는 `candidate()`를 거치지 않고 `previous.copy(...)`를 저장하므로, writer만 더해도 **바인딩되는 값이 계속 `null`**이다.
  `lastChangedAt`과 대사 상태를 실제로 갱신하는 경로라 무시된 관찰이 아니다.
- 행 잠금·상태·outbox 갱신과 **같은 트랜잭션**에서 쓴다.
- **격리·무시된 관찰은 시각도 적지 않는다** — 받아들이지 않은 관찰의 시각을 남기면 대사가 그 창으로 비교한다.

검증은 네 경로를 각각 본다 — 일반 관찰 / 기존값 보존 / RBF 승자 / `FAILED` 보류.

**4가 5보다 앞서야 한다.** 현재 `TxJdbcAdapter`의 두 갱신문은 `vndr_crt_dttm`을 아예 건드리지 않는다(set-once라 제외돼 있다).
`TxStateMachine`은 첫 관찰값을 계산하는데 persistence가 버린다. 순서를 뒤집으면 **백필과 writer 배포 사이에 온 관찰의 벤더 시각이 영구히 `NULL`로 남고**,
대사는 `NULL` 행을 제외하므로 복구 범위에서도 빠진다.

Admin만 고치면 **`NULL` 행을 읽는 Webhook·BAT가 먼저 깨진다**.

**0이나 빈 문자열로 메우지 않는다.** 금액이 없는 행은 "금액 0"이 아니라 "아직 모른다"이고, 둘을 섞으면 회계 대사가 틀린 값을
정상으로 읽는다. 금액 미백필 건이 **0이 되기 전에는 공개 read를 전환하지 않는다** — 남은 건은 Fireblocks 벤더 조회로 회수한다.

- 남은 슬라이스: **Q1** 스키마 확장·백필 → **Q2** 공통 쓰기 경로(제출 시 거래 행 생성·웹훅 시 금액/주소 보강) → **Q3** 공통 조회와 동시 cutover.
  각 슬라이스는 실패 테스트를 먼저 고정한다.

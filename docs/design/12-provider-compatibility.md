# 실행 제공자 선택·호환 계약

상태: DF0 코드/API 목록 대조·DF1 공통 경계 설계. 전체 Dfns 호환 완료가 아니다.
[전체 계획](../dfns-compatibility-plan.md) · [코드/API 인벤토리](evidence/92-provider-compatibility-inventory.md)

## 범위와 실행 선택

동일 API·업무·이벤트·복구를 Fireblocks·Dfns·로컬에서 제공한다. 시작 시 `BCM_PROVIDER` 값 하나로 구성하고
동일 배포의 API/Webhook/BAT가 같은 제공자·조직·환경을 사용한다. Admin은 BCM API를 사용한다.
요청별 벤더 선택·자동 fallback·실행 중 교체는 하지 않는다. 추가 체인 실구현과 #51은 후속이다.

| 값 | 구성 | 아직 필요한 일 |
|---|---|---|
| `fireblocks` | 현행 Fireblocks 클라이언트·정책/서명 경계, 조건부 조립 구현 | 실환경 수용·원천 식별 검증 |
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
| `WebhookSignatureVerifier`·`WebhookTransactionParser` | 원문 바이트 검증·파싱·인박스/논리 사건 연결 | 서명 헤더·키 조회·payload/재전달 ID. 현행 Controller의 Fireblocks 헤더 고정은 별도 분리 필요 |
| `VendorNetworkFeePort` | 견적·원금/수수료 단위 구분 | 체인/계정별 견적·대납/실비. 현행 fee 필드를 Dfns 결과에 임의로 채우지 않음 |
| `VendorWebhookRecoveryPort` | 구독 상태·복구 실행/감사 | 재전송과 이력 재처리의 실제 수행 구분. 미지원 API의 가짜 성공 금지 |
| `VendorExecutionLimits` | 단일 호출/전체 제출 흐름의 최장 시간 | 재시도·백오프·응답 불명 회수까지 포함한 양수 상한 산정 |

### 먼저 분리하는 호출 시간 계약

`VendorExecutionLimits`는 domain의 순수 인터페이스로 둔다. 시크릿·URL·HTTP 라이브러리를 포함하지 않는다.

- `maximumCallMillis`: 재시도·백오프까지 포함한 논리 API 한 호출의 최장 시간.
- `maximumSubmissionFlowMillis`: 제출과 응답 불명 회수를 포함한 전체 흐름의 최장 시간. 앞 값의 2배라고 공통 코드에서 가정하지 않는다.
- `FireblocksProperties`가 현행 산식 그대로 이 인터페이스를 구현한다. 추후 선택된 Dfns 구성도 같은 계약을 제공한다.
- 지갑 생성, 거래 제출, 전체 지갑 대사, 미응답 제출 회수, boost의 5개 조립 지점은 구체 Fireblocks 설정 대신 이 계약을 받는다.
- 기존 `claim TTL > 전체 흐름`, `회수 주기 > 단일 호출`, `boost TTL > 조회+전체 제출`의 엄격한 부등식과 overflow 검사를 유지한다.
- 시간 계약 분리는 Dfns 멱등 보존 시간을 확정하지 않는다. 현행 `WalletProvisioningPolicy`의 24시간 창은 별도 지갑 계약 변경 전까지 Fireblocks/로컬에만 적용한다.

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
  두 기존 변수는 미설정 가능하며 제공자를 추정하는 용도로 쓰지 않는다. 프로세스 간 환경 일치와 DB 원천 검증은 후속이다.
- `scripts/local.sh up fireblocks`는 `BCM_PROVIDER=fireblocks`, `up stub`과 로컬 smoke/BAT 실행은 `local`을 주입한다.
  폐쇄망 env 예제도 `local`을 명시한다. 직접 실행하는 API/Webhook/BAT는 동일 값을 각각 주입한다.
  `FIREBLOCKS_JWKS_URL` 별칭은 세 앱 모두 지원하고 정식 `BCM_FIREBLOCKS_WEBHOOK_JWKS_URL` 설정도 유지한다.

웹훅 수신 헤더는 현재 두 실행 경로 모두 `Fireblocks-Webhook-Signature`다. Dfns 헤더·메타데이터 계약은 어댑터 구현 전에 분리한다.
자산/지갑 원천 ID, Dfns 인증/멱등/지원 기능표, 추가 체인 인터페이스는 이번 조립 변경의 완료 범위가 아니다.

## 저장·API 변경 선행 조건

설정 선택과 지갑/자금 이전을 구분한다. 저장된 원천과 선택 제공자가 다르면 실행을 거절해야 한다.
원천 식별/기존 행 백필·DB 범위·진행 거래 처리의 물리 계약은 03에서 확정한 뒤 구현한다. 이번 시간 계약 분리에는 DDL 변경이 없다.

`fireblocksAssetId`, `MISSING_IN_FIREBLOCKS`는 현재 공개 API/BFF 계약에 남아 있다. Dfns 값을 이름만 바꿔 끼우지 않는다.
기존 소비자 필드 보존과 벤더 중립 필드 추가/해석을 OpenAPI에서 별도로 확정한 뒤 생성물·BFF를 함께 갱신한다.
`WebhookController`의 고정 헤더도 선택한 검증 전략에 전달할 메타데이터 계약을 먼저 정한다. 원문 바이트·해시·실제 서명 감사는 보존한다.

## DF0/DF1에서 남은 결정

| 항목 | 담당 역할 | 적용 시점 |
|---|---|---|
| 초기 실제 EVM 네트워크·USDC/KRWK 발행/주소 | 사용자·CORE/상품·BCM | 실벤더 PoC 전에 고정. 현재는 로컬 테스트 자산을 실제 발행 증거로 쓰지 않음 |
| Baseline 릴리스·RPC·지갑/승인·대납 제공 조건 | Dfns·플랫폼·노드·보안 | DF2 수용. 공개 문서만으로 지원 완료 처리 금지 |
| 지갑 모델·멱등 창·자산 공개 필드·원천 DB 식별 | BCM·CORE·DBA | 해당 어댑터/DDL 구현 전 |
| 처리량·감지/대사 지연·RTO/RPO | 운영·CORE·BCM | DF8 성능/운영 인수 전 |

위 항목이 남아 있으므로 DF0/DF1 전체 완료로 체크하지 않는다. 코드/API 인벤토리와 시간 계약 분리만 독립 완료 단위다.

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

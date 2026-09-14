# Fireblocks·Dfns·로컬 호환 인벤토리

2026-09-14 코드 대조. 기준 HEAD `675d61f5d32b97d122a7d0e633f5c7a404739ae1` + 이번 작업 트리.
명세 파일의 SHA-256: `1d696b1be5a295281399a092bdb88eb422c14471e0a74e7bb616fc734d6144dd`.
현재 구현/시험 파일의 존재를 기록하며 모든 테스트를 이번 세션에서 실행했다는 뜻은 아니다. 외부 벤더 수용 결과가 아니다.
[구현 계약](../12-provider-compatibility.md) · [범위/단계](../../dfns-compatibility-plan.md)

## HTTP 계약 29개

모든 행은 세 환경의 공통 목표다. Fireblocks는 아래 현행 구현·테스트가 있고, Dfns 어댑터는 미구현이다.
로컬은 현행 Fireblocks Stub/Anvil 경로를 사용하되 각 행의 전체 로컬 수용 사례는 재검증 대상으로 둔다.
인증·오류·nullable·금액 문자열·UTC·멱등·cursor도 동일 계약으로 비교한다. 기능 담당은 해당 BCM API/업무 팀, 실제 벤더 지원 확인은 Dfns/플랫폼 담당과 공동 수행한다.

| operationId | HTTP | 영역 | 현행 구현 | 회귀 기준 파일 |
|---|---|---|---|---|
| `createAccount` | `POST /accounts` | 계정·주소·잔액 | [AccountController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/account/AccountController.kt) | [AccountControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/account/AccountControllerTest.kt) |
| `createDepositAddresses` | `POST /accounts/{accountId}/addresses` | 계정·주소·잔액 | [AccountController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/account/AccountController.kt) | [AccountControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/account/AccountControllerTest.kt) |
| `depositAddressesOf` | `GET /accounts/{accountId}/addresses` | 계정·주소·잔액 | [AccountController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/account/AccountController.kt) | [AccountControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/account/AccountControllerTest.kt) |
| `balancesOf` | `GET /accounts/{accountId}/balances` | 계정·주소·잔액 | [AccountController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/account/AccountController.kt) | [AccountControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/account/AccountControllerTest.kt) |
| `networksOf` | `GET /admin/networks` | 네트워크·자산 | [AdminAssetController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminAssetController.kt) | [AdminAssetControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminAssetControllerTest.kt) |
| `adoptNetwork` | `PUT /admin/networks/{code}` | 네트워크·자산 | [AdminAssetController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminAssetController.kt) | [AdminAssetControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminAssetControllerTest.kt) |
| `releaseNetwork` | `DELETE /admin/networks/{code}` | 네트워크·자산 | [AdminAssetController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminAssetController.kt) | [AdminAssetControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminAssetControllerTest.kt) |
| `assetCandidatesOf` | `GET /admin/asset-candidates` | 네트워크·자산 | [AdminAssetController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminAssetController.kt) | [AdminAssetControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminAssetControllerTest.kt) |
| `assetMappingsOf` | `GET /admin/asset-mappings` | 네트워크·자산 | [AdminAssetController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminAssetController.kt) | [AdminAssetControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminAssetControllerTest.kt) |
| `registerAssetMapping` | `POST /admin/asset-mappings` | 네트워크·자산 | [AdminAssetController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminAssetController.kt) | [AdminAssetControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminAssetControllerTest.kt) |
| `deleteAssetMapping` | `DELETE /admin/asset-mappings/{network}/{symbol}` | 네트워크·자산 | [AdminAssetController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminAssetController.kt) | [AdminAssetControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminAssetControllerTest.kt) |
| `registerAssetMappings` | `POST /admin/asset-mappings/bulk` | 네트워크·자산 | [AdminAssetController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminAssetController.kt) | [AdminAssetControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminAssetControllerTest.kt) |
| `transactionInvestigationOf` | `GET /admin/transaction-investigations/{identifier}` | 거래 조사 | [AdminTransactionInvestigationController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminTransactionInvestigationController.kt) | [AdminTransactionInvestigationControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminTransactionInvestigationControllerTest.kt) |
| `sweepRequestInvestigationOf` | `GET /admin/sweep-request-investigations/{identifier}` | 집금 조사 | [AdminSweepRequestInvestigationController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminSweepRequestInvestigationController.kt) | [AdminSweepRequestInvestigationControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/admin/AdminSweepRequestInvestigationControllerTest.kt) |
| `sweepOperations` | `GET /admin/sweep-operations` | 집금 조사 | [AdminSweepRequestInvestigationController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminSweepRequestInvestigationController.kt) | [AdminGovernanceQueryServiceTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/application/admin/AdminGovernanceQueryServiceTest.kt) |
| `startAdminVaultReconciliation` | `POST /admin/vault-reconciliations` | 지갑 대사 | [AdminVaultReconciliationController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminVaultReconciliationController.kt) | [AdminVaultReconciliationServiceTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/application/admin/AdminVaultReconciliationServiceTest.kt) |
| `adminVaultReconciliation` | `GET /admin/vault-reconciliations/{runId}` | 지갑 대사 | [AdminVaultReconciliationController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminVaultReconciliationController.kt) | [AdminVaultReconciliationServiceTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/application/admin/AdminVaultReconciliationServiceTest.kt) |
| `adminContracts` | `GET /admin/contracts` | 운영 설정 | [AdminGovernanceController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminGovernanceController.kt) | [AdminGovernanceQueryServiceTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/application/admin/AdminGovernanceQueryServiceTest.kt) |
| `adminPolicies` | `GET /admin/policies` | 운영 설정 | [AdminGovernanceController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminGovernanceController.kt) | [AdminGovernanceQueryServiceTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/application/admin/AdminGovernanceQueryServiceTest.kt) |
| `adminBandS` | `GET /admin/band-s` | 운영 설정 | [AdminGovernanceController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminGovernanceController.kt) | [AdminGovernanceQueryServiceTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/application/admin/AdminGovernanceQueryServiceTest.kt) |
| `adminExecutionGates` | `GET /admin/execution-gates` | 운영 설정 | [AdminGovernanceController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminGovernanceController.kt) | [AdminGovernanceQueryServiceTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/application/admin/AdminGovernanceQueryServiceTest.kt) |
| `adminRuntimeReadiness` | `GET /admin/runtime-readiness` | 운영 설정 | [AdminGovernanceController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminGovernanceController.kt) | [AdminGovernanceQueryServiceTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/application/admin/AdminGovernanceQueryServiceTest.kt) |
| `adminChangeRequestOf` | `GET /admin/change-requests/{requestId}` | 운영 설정 | [AdminGovernanceController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/admin/AdminGovernanceController.kt) | [AdminGovernanceQueryServiceTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/application/admin/AdminGovernanceQueryServiceTest.kt) |
| `submitTransaction` | `POST /transactions` | 거래 | [TransactionController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/transaction/TransactionController.kt) | [TransactionControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/transaction/TransactionControllerTest.kt) |
| `requestSweep` | `POST /sweeps` | 집금 요청 | [SweepRequestController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/sweep/SweepRequestController.kt) | [SweepRequestControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/sweep/SweepRequestControllerTest.kt) |
| `completeEvent` | `PUT /events/{eventId}/completion` | 이벤트 완료 | [EventCompletionController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/event/EventCompletionController.kt) | [EventCompletionControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/event/EventCompletionControllerTest.kt) |
| `transactionByExternalTxId` | `GET /transactions/external/{externalTxId}` | 거래 | [TransactionController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/transaction/TransactionController.kt) | [TransactionControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/transaction/TransactionControllerTest.kt) |
| `transactionOf` | `GET /transactions/{txId}` | 거래 | [TransactionController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/transaction/TransactionController.kt) | [TransactionControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/transaction/TransactionControllerTest.kt) |
| `transactionsOf` | `GET /accounts/{accountId}/transactions` | 거래 | [TransactionController](../../../blockchain-manager-app/bcm-api/src/main/kotlin/com/whatto/bcm/app/api/transaction/TransactionController.kt) | [TransactionControllerTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/transaction/TransactionControllerTest.kt) |

## 기능·운영 영역 24개

F=현행 코드 경로, L=로컬 시험 방식, D=Dfns 잔여 작업. 파일별/실행별 상세 증적은 각 구현 세션에서 추가한다.
`미구현`은 벤더 제품 미지원 판정이 아니라 이 저장소의 구현 상태다. #51은 첫 호환 범위와 분리한다.

| ID | 영역 | F / L | D·추가 검증 |
|---|---|---|---|
| C01 | 계정 생성·조회 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 기존 accountId/ref와 고객·회사 구분 유지. 응답 유실·동시 생성·저장 실패에도 공개 매핑 1개 |
| C02 | 주소 발급·재조회 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | account/network/token 매핑, 동일 주소 공유, Tag/Memo 영속, 생성 의도·회수 원장 보존 |
| C03 | 잔액 응답 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | total/available/pending/frozen/locked의 의미와 단위 대조. 미발급 `[]`, 실제 0, vendor drift 오류 구분 |
| C04 | 주소별 온체인 잔고 | 주소별 잔고 저장·직접 집금 신규 책임 미구현 / DF7 후속 | PLAN #51: 기준 블록·관찰 이력·이동 증적·예약·외부 cold·소유/용도 식별. 공동 주소 중복 집계 0 |
| C05 | 출금·내부이체 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 주소/관리 계정/등록 목적지, 금액 문자열, 정책·컴플라이언스 입력, 중복 요청·응답 유실 처리 유지 |
| C06 | 거래 조회·목록 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | txId/externalTxId/계정 조회, 필터·정렬·안정 커서, 대체 거래 묶음과 과거 Fireblocks 거래 조회 유지 |
| C07 | 입금·출금 이벤트 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | DEPOSIT/WITHDRAWAL/INTERNAL 분류, 관리 주소 간 이동 중복 입금 방지, 감지→확정 순서 |
| C08 | 확정·reorg | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 네트워크별 CONFIRMED/FINALIZED 의미 유지. 무효화·재포함·FINALIZED 후 새 FAILED 이벤트 검증 |
| C09 | 웹훅 수신 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 원문 바이트·해시·서명 감사, 인증·키 교체, 전달 시도와 논리 사건 중복 구분, 역순·다중 구독 대응 |
| C10 | outbox·CORE 완료 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 상태+outbox+처리 표시 원자성, account 파티션 순서, eventId dedup, 완료 회신·미완료 보관 유지 |
| C11 | DAW Sweep 접수 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 완료된 FINALIZED sourceEvent 귀속, 요청 hash·충돌·다중 요청 합류·분할·STOP 중 접수 계약 유지 |
| C12 | 집금 권한 준비·회수 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | EVM 제한 approve/approve(0)·실제 allowance; Solana 제한 위임/철회·프로그램 통제 별도 검증. 구/신 권한 중복 개방 방지 |
| C13 | 배치 집금 실행 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | EVM approve+transferFrom, Solana 별도 확정한 집금 경로. 공통 실행 의도 선기록·claim·목적지·운영자·상한·중복 execution 차단 |
| C14 | Sweep 항목 결과 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | execution 1:N item, 요청량/실이동량, EVM 부분 성공과 Solana 원자 실행 단위 구분, chainStatus/itemOutcome 분리·재시도·reorg 복구 |
| C15 | 가스·수수료 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | Ethereum/Base 대납·SOL fee payer/rent, 견적·실제 비용·native 단위·sponsor 충전/고갈·실패 비용. 법정화폐 정산은 별도 계약 |
| C16 | boost·막힘 점검 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | EVM nonce 대체 경합; Solana 동일 서명 재방송·만료 후 새 시도와 원본 확인. 논리 txId 유지, 최종 자산 이동 1회 |
| C17 | 거래 대사·원문 보관 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 벤더별 종결 매핑, 누락 창·페이지 재개·중복 종결 제외, 원문 장기 보관·보존 기간·정리 조건 |
| C18 | 웹훅 수동 복구 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 구독 조회·활성화·누락 회수의 운영 결과 유지. 실제 재전송과 이력 조회/재처리를 구분해 감사 |
| C19 | Network·Asset 관리 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 전체 후보/채택 목록 분리, 정확한 chain/token 식별, decimals·native 연결, 일괄 등록 원자성·실패 index |
| C20 | Vault/Wallet 전체 대사 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 비동기 실행·cursor·재개·bounded memory, 완료 전 MISSING 미확정, 벤더별 원천 분리 |
| C21 | 정책·컨트랙트 운영 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 불변 version/hash·요청/승인/활성화 분리·hard ceiling·독립 검증·실행 시 evidence·drift 차단 |
| C22 | 비상·밴드S·cold | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | STOP/재개 비대칭, 기존 정족수, 고정 cold 목적지·풀 최소잔액·오프라인 cold 서명 경계 |
| C23 | Admin·감사·관측 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | 전 API·BFF·로컬 진단·JMX·배치/웹훅 경보·모든 식별자의 연결, 시크릿/원문 비노출 |
| C24 | 운영·시험·전환 | 현행 코드·벤더 mock / Stub·Anvil 또는 정책 모사, 사례별 재검증 | Fireblocks/Dfns/로컬 공통 계약·실제 로컬 EVM E2E, BCM Linux/systemd와 Dfns K8s/Vault 운영 분리, 위탁 RPC·플랫폼 복구·재시작·롤백 훈련 |

## 조립·로컬 실행 근거

- [ClientConfig](../../../blockchain-manager-infra/client/src/main/kotlin/com/whatto/bcm/infra/client/config/ClientConfig.kt)
- [WebhookController](../../../blockchain-manager-app/bcm-webhook/src/main/kotlin/com/whatto/bcm/app/webhook/api/WebhookController.kt)
- [RuntimeModeBoundary](../../../blockchain-manager-test-support/src/main/kotlin/com/whatto/bcm/testsupport/config/RuntimeModeBoundary.kt)
- [LocalChainEnvironment](../../../blockchain-manager-test-support/src/main/kotlin/com/whatto/bcm/testsupport/chain/LocalChainEnvironment.kt)
- [StatefulFireblocksStubIntegrationTest](../../../blockchain-manager-test-support/src/test/kotlin/com/whatto/bcm/testsupport/stub/StatefulFireblocksStubIntegrationTest.kt)
- [LocalFireblocksInternalTransferIntegrationTest](../../../blockchain-manager-app/bcm-api/src/test/kotlin/com/whatto/bcm/app/api/transaction/LocalFireblocksInternalTransferIntegrationTest.kt)
- [SubmissionRecoveryJobTest](../../../blockchain-manager-app/bcm-bat/src/test/kotlin/com/whatto/bcm/app/bat/submission/SubmissionRecoveryJobTest.kt)
- [StallCheckJobTest](../../../blockchain-manager-app/bcm-bat/src/test/kotlin/com/whatto/bcm/app/bat/stall/StallCheckJobTest.kt)

## 이번에 확인한 구현 차이

- `ClientConfig`는 `BCM_PROVIDER=fireblocks|local`에서만 Fireblocks/EVM 설정과 JWT signer를 조립한다. 공통 ProviderConfiguration이 선택값/자격/로컬 주소를 검증하고 Dfns는 구현 전 기동을 거절한다.
- 지갑 생성/제출/지갑 대사/제출 회수/boost의 시간 검사가 Fireblocks 구체 설정에 의존했다. 첫 구현에서 `VendorExecutionLimits`로 분리했다.
- `WalletProvisioningPolicy`는 24시간 멱등 창을 사용한다. Dfns 지갑 생성에 그대로 재사용하려면 별도 근거/계약이 필요하다.
- 웹훅 Controller의 헤더 선택과 수신 envelope 파싱은 WebhookProtocol로 분리했다. FireblocksWebhookProtocol이 현행 필드를 해석하며 Dfns HMAC·실제 원문·재전달/복구 증적은 후속이다.
- 자산 API/BFF의 `fireblocksAssetId`, 지갑 대사의 `MISSING_IN_FIREBLOCKS`는 공개 계약 차이다. 별도 API 설계 없이 치환하지 않는다.
- `BCM_PROVIDER` 선택은 구현했다. 원천 불일치 거절·Dfns 인증/클라이언트·Dfns Stub은 아직 구현되지 않았다.
- 최초 실제 체인/토큰 배포·Baseline 릴리스/지원·규모/SLO는 미확정이다. 로컬 ERC-20 테스트 자산으로 실제 USDC/KRWK 발행을 입증하지 않는다.

---
title: Fireblocks QnA — 담당자 확답 정리
status: To Do
---

Fireblocks 담당자에게 문의해 받은 답변을 질문 단위로 모은다.

## Rate limit — 담당자 확답 (2026-07)

**Q.** 폴링 트래픽이 "이상 트래픽"으로 감지돼 rate limit 에 걸릴 수 있나?
**A.** 아니다. rate limit 은 결정론적 per-60초 요청 카운터뿐이다. 이상·스파이크 감지, 적응형 스로틀, 임시 밴·누적 페널티가 없다. 꾸준한 폴링은 "이상"으로 취급되지 않고, 닿을 수 있는 건 정적 분당 한도뿐이다.

**Q.** 한도는 API user 마다 따로 받나?
**A.** 아니다. 워크스페이스 단위로 모든 API user 가 공유한다. 전용 Viewer(읽기전용) user 도 자기 예산이 없다 — 워크스페이스 총량으로 설계해야 한다.

**Q.** 우리가 쓰는 거래 조회 엔드포인트의 분당 한도는?
**A.** 목록 `GET /v1/transactions` 는 1,000/분, 단건 `GET /v1/transactions/{txId}` 는 1,500/분이다. 두 카운터는 독립이라 서로 경쟁하지 않는다. 최고 tier 라 더 높은 값은 없다. (계약 후 계정 설정 시 부여 — 현재 기본값 아님)

**Q.** 한도를 넘기지 않으려면 무엇으로 조절하나?
**A.** 모든 응답(성공·거절)에 `X-RateLimit-Limit`·`Remaining`·`WindowSize`·`Retry-After` 헤더가 온다. `Remaining`·`Retry-After` 로 선제 조절하고, 429 는 지수 백오프로 받는다(보조).

**Q.** 확정이 안 되고 오래 걸리는 tx 는 계속 조회해도 되나?
**A.** 안 된다. 고정 주기로 계속 조회하면 진행 중 tx 가 쌓여 사용량이 커진다. 지수 백오프(30초 → 1분 → 5분 → 15분 → 1시간) + 분당 단건 호출 총량 상한(최근·변화 가능성 높은 것 우선) + 일정 나이를 넘으면 조회를 멈추고 저빈도 대사나 운영 알림으로 넘기는 것이 필수다.

**Q.** 우리가 계획한 폴링 물량은 한도 안에 드나?
**A.** 목록 1,000/분 · 단건 1,500/분 안에 든다고 확인받았다(백오프 정책 포함 조건).

**Q.** 상태 추적에 폴링과 웹훅 중 무엇을 권장하나?
**A.** 웹훅이다. 반복 폴링의 rate 부담·타이밍 edge case 가 없다. 폴링은 인바운드 웹훅이 막힌 환경(은행 보안 정책 등)의 지원 대안으로 본다.

## 트랜잭션 조회 API — 담당자 확답 (2026-07)

**Q.** `after`/`before` 필터는 무엇을 기준으로 거르나?
**A.** 항상 생성 시각(createdAt) 기준이다. `orderBy` 와 무관하다. 그래서 조회 창을 벗어난 뒤 상태가 바뀐 tx 는 목록에 다시 뜨지 않는다 — 그런 건 단건 조회로 따로 쫓는다.

**Q.** `orderBy` 를 지정해도 되나?
**A.** 지정하면 next-page 커서가 반환되지 않는다. 그래서 지정하지 않는다.

**Q.** 특정 tx 의 갱신을 추적하는 권장 방법은?
**A.** 단건 조회(`GET /v1/transactions/{txId}`)가 권장 패턴이다. 요청량이 최소이고 rate limit 안에 넉넉히 든다.

**Q.** 확정·실패로 끝난 tx 의 객체도 더 바뀌나?
**A.** 바뀐다. 종결 이후에도 tx 객체(blockInfo 포함)는 체인에 변화가 생기면 갱신된다.

**Q.** EVM 과 UTXO 를 다르게 다뤄야 하나?
**A.** 아니다. API 표면 동작이 동일해 체인 타입 분기가 필요 없다.

**Q.** EVM(account 기반) 입금 통지는 언제 생성되나?
**A.** 채굴(mined) 시점에 생성된다.

**Q.** 한 번에 몇 건까지 받나? `after` 를 안 주면?
**A.** `limit` 은 기본 200·최대 500이다. `after` 미지정 시 기본 조회 범위가 "지난 90일"이고 바뀔 수 있어, 항상 명시한다.

**Q.** 조회는 어떤 권한으로 가능한가?
**A.** Viewer(읽기전용) 권한으로 가능하다 — 폴러를 읽기전용 user 로 분리할 수 있다. 단 rate 예산은 워크스페이스 공유다.

## 트랜잭션 상태·reorg — 담당자 확답 (2026-07)

**Q.** `numOfConfirmations` 값이 reorg 때 줄어들 수 있나?
**A.** 아니다. 한 번 올라간 값은 다시 줄지 않는다(늘기만 한다). reorg 로 블록이 교체돼도 감소하지 않고 그 자리에 멈춘다. 자산별 요구 컨펌 수가 상한이라, 그 이상으로는 안 늘고 COMPLETED 도 그 임계에서 뜬다.

**Q.** reorg 로 재채굴되면 tx 객체는 어떻게 바뀌나?
**A.** blockInfo(blockHash·blockHeight)가 새 블록으로 갱신되고, status 는 CONFIRMING 으로 유지된다.

**Q.** 재채굴되지 못하고 탈락하면?
**A.** `FAILED` + subStatus `DROPPED_BY_BLOCKCHAIN` 으로 전이한다.

**Q.** `BLOCKED` 는 최종 상태인가?
**A.** 최종(terminal)이다. 정책 규칙에 막힌 출금에 뜨고, 자금은 재사용하도록 즉시 풀린다. 이후 상태 전이는 없다.

**Q.** `REJECTED` 는 최종 상태인가?
**A.** 방향에 따라 다르다. 출금(outgoing) `REJECTED` 는 최종이라 자금이 즉시 반환된다. 입금(incoming) `REJECTED`(동결 케이스)는 최종이 아니라 보류(hold)로 다뤄야 한다 — Admin 이 해제(unfreeze)할 때까지 자금이 묶여 있고, Admin 조치로 결과가 바뀔 수 있다.

**Q.** 동결·해제 같은 상태 변화를 웹훅·조회로 잡을 수 있나?
**A.** 잡을 수 있다. status/subStatus 변화(동결·해제 포함)는 `transaction.updated` 웹훅을 발생시키고 `GET /v1/transactions/{txId}` 에도 반영된다.

## 키 생성(DKG) 시점 — 담당자 확답 (2026-08)

**Q.** 암호학적 키 생성(DKG)이 일어나는 시점은 언제인가? 최초 MPC key share·HD 루트를 만드는 시점만인가, 이후 vault 를 만드는 시점도 포함인가?
**A.** 세 시점뿐이다.
- (a) 워크스페이스 오너가 최초 온보딩을 완료하는 시점. 오너가 계정 등록 후 Fireblocks 모바일 앱을 QR 코드로 페어링하고, PIN/생체인증을 설정하고, 리커버리 패스프레이즈를 확정하는 과정에서 오너 디바이스와 Fireblocks 측 엔드포인트 간 DKG 가 수행돼 워크스페이스 마스터 키(3개 key share)가 최초로 생성된다. 키 백업(리커버리 패스프레이즈 기반)도 같은 세션에서 함께 등록된다.
- (b) 서명 권한 사용자·디바이스(추가 signer/admin 의 모바일 앱, API Co-Signer)가 워크스페이스에 합류하는 시점. 해당 디바이스용 key share 가 발급된다. 오너의 명시적 승인이 선행 조건이고, 동일 master seed 기반이라 마스터 키와 지갑 주소는 변하지 않는다.
- (c) 새 서명 알고리즘 키셋(예: EdDSA) 추가 시 해당 알고리즘에 대한 별도 DKG 1회.

**Q.** vault account·vault wallet·입금 주소 생성도 키 생성인가? 이때 Co-Signer 나 모바일 앱이 개입하나?
**A.** 아니다. 기존 마스터 키로부터의 BIP44 deterministic derivation(`m/44/coinType/vaultAccountId/change/index`)이라 새 엔트로피·새 key share·MPC 세션이 발생하지 않는다. 따라서 이 시점에 Co-Signer 나 모바일 서명 디바이스는 개입하지 않는다.

**Q.** 키 생성 절차를 우리가 다시 진행해 볼 수 있나?
**A.** 미답. 참고 문서 3건(Owner MPC key generation · Fireblocks' MPC wallet infrastructure · Fireblocks Wallets Overview)만 안내받았다 — 재수행 가능 여부·절차는 재문의 대상.

## Gasless Relay 과금 — 담당자 확답 (2026-08-18)

Fireblocks-managed Relay 의 과금·정산 조건. 원문은 [sources/fireblocks-support/2026-08-18](https://github.com/eunsujeo/eunpus/blob/main/blockchain-manager/sources/fireblocks-support/2026-08-18__gasless-relay-billing-conversation.md), 설계 반영은 [블록체인 매니저 — 가스 대납 적용](../../블록체인매니저/가스대납/00-overview.md).

**Q.** Universal Gasless 는 Boost 를 지원하나? Boost·실패·재시도 비용은 어떻게 처리되나?
**A.** 자동 boost 없음 — 막힌 거래는 수동 RBF 로 올린다(공개 문서와 일치). 비용은 셋으로 갈린다.
- **온체인에서 revert 된 거래 — 청구된다.** 네트워크가 gas 를 이미 소비했기 때문.
- 브로드캐스트 전 차단(정책 차단·검증 실패) — 미청구. 네트워크에 도달한 것이 없다.
- **RBF 재제출 — 교체 거래분이 청구된다.** 같은 nonce 로 원본이 드랍되므로 이중 청구는 없다.

**Q.** Fireblocks 가 gas 를 선지불하고 월별로 청구하나?
**A.** 맞다. 선지불 후 월말 통합 인보이스.

**Q.** 청구 통화는 USD 인가? 결제 수단은?
**A.** **USD.** 네이티브 토큰이나 벤더가 매입한 ETH 기준의 가격 책정은 수탁 지위(custodian)로 비칠 수 있어 의도적으로 배제했다고 밝혔다. 결제는 기존 Fireblocks 계약 프로세스를 따른다.

**Q.** gas 비용은 누가 어떻게 정하나? 상한을 걸 수 있나?
**A.** **네트워크가 정한다** — 전송 시점의 base fee + priority fee 와 그 거래가 소비한 gas. 벤더 마크업 없음 — **청구액 = relay 가 실제 지불한 금액 그대로.** **Relay 사용 시 건별 gas 상한은 지정할 수 없다.**

**Q.** gas 실비 외 추가 수수료가 있나?
**A.** 프리미엄 기능이라 청구는 두 항목 — **월 구독료 + gas 실비 상환.** 건별 relay 수수료 없음, gas 에 대한 퍼센트 마크업 없음.

### 과금 후속 문의 — 확정 문안 (2026-08-27) · 회신 대기

기존 확답과 겹치지 않는 잔여 3건으로 추림 — 마크업·revert/RBF 청구·USD 청구·월말 인보이스는 이미 확답이라 제외.

> Hi, a few follow-up questions on Universal Gasless billing, building on your earlier answers (USD billing, no markup, monthly invoice).
> 1. What is the exact monthly subscription fee, and is it charged per workspace?
> 2. For the USD conversion of native gas, which price source and reference timestamp are used?
> 3. Could you provide estimated monthly cost ranges for 100K / 1M / 10M Universal Gasless transactions per month on Ethereum and Base, separating the subscription fee from estimated gas, with the gas-price and per-transaction gas usage assumptions used — including first-time EIP-7702 account upgrade transactions? Ranges are sufficient.

**Q.** 월 구독료의 정확한 금액과 Workspace 단위 부과 여부는?
**A.** 회신 대기.

**Q.** 네이티브 가스 USD 환산의 가격 출처·기준 시각은?
**A.** 회신 대기.

**Q.** Ethereum·Base 월 10만/100만/1,000만 건 시나리오의 월 비용 범위는? (구독료·가스 분리, 가스 가격·건당 가스 사용량 가정, 최초 EIP-7702 업그레이드 거래 포함)
**A.** 회신 대기.

## KeyLink — 공식 자료 확정·담당자 문의 (2026-08-27)

예산 산정을 위한 문의다. "공식 자료로 확정한 내용"은 수집한 Fireblocks 공식 자료에서 확인한 것이고, 기술 4문항은 2026-08-28 담당자 확답으로 확정했다. 견적은 영업 담당 회신 대기.

### 공식 자료로 확정한 내용

**Q.** KeyLink의 서명 경로는 어떻게 구성되나? API Co-Signer가 HSM에 직접 연결하나?
**A. (공식 자료 확인)** 아니다. 경로는 **Fireblocks Co-Signer Engine → Fireblocks Agent → Customer Server → HSM**이다. Agent가 Fireblocks의 서명 요청을 폴링해 Customer Server로 전달하고, Customer Server가 HSM에 서명을 요청한 뒤 결과를 역경로로 반환한다. ([KeyLink Overview](../../../../sources/fireblocks/markdown/2026-05-22__support-fireblocks-io__fireblocks-key-link-overview-extracted.txt))

**Q.** Fireblocks Agent가 실제 개인키나 MPC key share를 보유하나?
**A. (공식 자료 확인)** 보유하지 않는다. 개인키는 고객 HSM에 남고 HSM이 단독 서명한다. Fireblocks는 고객이 등록한 validation key와 proof of ownership으로 서명키를 확인하고 결과 서명을 검증한다. KeyLink는 일반 API Co-Signer의 MPC 공동서명 구조와 다르다. ([Getting Started with KeyLink](../../../../sources/fireblocks/markdown/2026-05-22__support-fireblocks-io__getting-started-with-fireblocks-key-link-extracted.txt))

**Q.** KeyLink Agent에 AWS Nitro나 Intel SGX가 필요한가?
**A. (공식 자료 확인)** KeyLink 설치 절차에는 해당 요구가 없다. Nitro·SGX·Google Confidential Space는 MPC key share를 보관하는 API Co-Signer의 실행 환경이고, KeyLink Agent는 고객이 호스팅하는 오픈소스 TypeScript 중계 서비스다.

**Q.** Agent는 Fireblocks Workspace와 어떻게 연결되나?
**A. (공식 자료 확인)** Signer 역할 API user를 만들고 Admin Quorum 승인을 거쳐 발급받은 pairing token으로 Agent를 연결한다. 각 Policy rule의 designated signer도 이 Agent와 페어링한 API user로 지정해야 한다. 재등록(re-enroll)은 Owner 승인이 필요하다. ([Getting Started with KeyLink](../../../../sources/fireblocks/markdown/2026-05-22__support-fireblocks-io__getting-started-with-fireblocks-key-link-extracted.txt))

**Q.** Vault와 HSM signing key의 결합 제약은 무엇인가?
**A. (공식 자료 확인)** KeyLink Vault Account에는 ECDSA key 하나와 EdDSA key 하나를 배정할 수 있고, 한 Vault에 배정한 key는 다른 Vault에 재사용할 수 없다. 따라서 필요한 HSM key 수는 Vault 수와 지원할 서명 알고리즘을 기준으로 산정해야 한다. ([Set Up Your Fireblocks Vault with KeyLink](../../../../sources/fireblocks/markdown/2026-05-22__support-fireblocks-io__set-up-your-fireblocks-vault-with-key-link-extracted.txt))

**Q.** 공식 자료에서 확인되는 HSM과 배치 방식은 어디까지인가?
**A. (공식 자료 확인)** Thales Luna HSM 연동과 FIPS 140-3 Level 3 하드웨어 사용은 확인된다. Hot·Warm·Cold signing workflow를 지원하며 Cold 방식의 전달 수단으로 USB·SFTP·data diode가 제시돼 있다. 다만 정확한 Luna 모델·펌웨어, Hot·Warm의 네트워크 구성과 성능 기준은 공개 자료만으로 확정할 수 없다. ([Fireblocks and Thales](../../../../sources/fireblocks/markdown/2026-05-22__fireblocks-com__enterprise-digital-asset-security-thales.md))

### 담당자에게 보낸 메신저 (2026-08-27 확정 문안)

벤더만 답할 수 있는 항목으로 추려 5문항으로 확정 — 고객이 선택·산정할 항목(배치 토폴로지, Customer Server 사양, 구성 요소 수량, 원격 HSM 연결)과 구축 단계 세부(펌웨어·Luna Client·PKCS#11 조합, endpoint·port·timeout·재시도, failover·재페어링)는 뺐다.

> We also have a few questions about KeyLink, covering both technical and pricing topics.
> 1. Is there a supported or recommended Thales Luna model? If no specific model is required, what cryptographic algorithms and interface requirements must the HSM meet?
> 2. Does Fireblocks provide either a production-ready Customer Server implementation or reference code with Thales Luna integration, or is the customer expected to build it based on the Fireblocks interface specifications?
> 3. What are the recommended host specifications and supported operating systems for the Fireblocks Agent? Are there any restrictions on running it in a VM or container?
> 4. Can multiple Agent instances be connected to one workspace for HA and DR? Do you have a reference architecture for production and DR deployments?
> 5. Beyond our existing Fireblocks contract, are there any additional KeyLink fees or mandatory Professional Services costs? Could you provide an estimate for development, UAT, production, and DR workspaces?

### 담당자 확답 (2026-08-28)

가격(5번)만 영업 담당(Ben Han·Shane Verner) 회신으로 넘어갔고, 기술 4문항은 확답을 받았다. 원문: [CSM 답변](../../../../sources/fireblocks/markdown/2026-08-28__fireblocks-csm__key-link-thales-luna-qna.txt).

**Q.** 지원·권장 Thales Luna 모델이 있는가? 특정 모델 요건이 없다면 HSM 이 충족할 암호 알고리즘·인터페이스 조건은?
**A.** 지정·인증된 Luna 모델은 없다 — KeyLink 는 의도적으로 HSM 종류를 가리지 않고, 요건은 하드웨어가 아니라 알고리즘·인터페이스 층에 있다. HSM 은 서명키용 **ECDSA secp256k1 과 EdDSA ed25519** 를 지원해야 하고(API 가 받는 알고리즘은 이 둘뿐), 이를 **PKCS#11** 로 노출해야 한다. trust root 인 validation key 는 **RSA-2048**.
담당자가 Thales 의 Fireblocks 통합 가이드를 인용한 내용(Thales 문서는 직접 확인 전): secp256k1 은 Luna 7.x 전 펌웨어에서 동작, **ed25519 는 펌웨어 7.8.9 이상** 필요. Thales 는 Luna Network HSM 펌웨어 7.8.4 + Luna Client 10.3.0 으로 통합을 검증했고, 그 Client 와 호환되는 펌웨어면 다른 Luna 모델도 지원한다고 밝혔다. 담당자 권고 — 자산 범위가 비트코인·EVM 이면 현행 Luna 7.x 로 충분, Solana 같은 ed25519 체인을 지원할 계획이면 조달 시 7.8.9 이상을 명시.

**Q.** Thales Luna 연동이 구현된 production-ready Customer Server 구현체·레퍼런스 코드를 제공하는가, 고객이 인터페이스 스펙 기반으로 직접 구축하는가?
**A.** 인터페이스 계약과 동작하는 참조 코드를 공개하고, 프로덕션 구현은 고객이 만들고 소유한다. 계약은 오픈소스 [fireblocks/fireblocks-agent](https://github.com/fireblocks/fireblocks-agent) 저장소의 `api/customer-server.api.yml`. 같은 저장소 `examples/server` 에 완전한 참조 서버가 있고 **Thales Luna 빌드가 포함**돼 있다. 이 코드는 명시적으로 참조용이며 프로덕션 소프트웨어가 아니다. 직접 구축을 원치 않으면 **KeyLink Flow** — 운영 콘솔을 갖춘 패키지형 온라인 서버 — 가 제품화된 대안으로, 맞춤 개발의 대부분을 대체한다. 참고 문서로 [Fireblocks Key Link Overview](https://support.fireblocks.io/hc/en-us/articles/14228517105052-Fireblocks-Key-Link-Overview) 를 안내받았다. KeyLink Flow 의 호스팅 주체·HSM 연결·과금은 확인 안 됨.

**Q.** Fireblocks Agent 의 권장 호스트 사양·지원 OS 와 VM·컨테이너 실행 제약은?
**A.** 하드 최소치가 아닌 배포 가이드로: OS = Ubuntu 22.04 LTS 이상 또는 Docker 를 지원하는 Linux 배포판 · 메모리 = 환경당 8 GB · 스토리지 = 100 GB SSD, 암호화 · 런타임 = Docker · 네트워크 = Fireblocks 엔드포인트로 안정적인 아웃바운드, 방화벽은 그 엔드포인트로 한정. Luna client 는 Customer Server 호스트에 설치되므로 그 호스트는 어플라이언스와 NTLS 연결이 되고 Luna client 로 등록돼야 한다. 망분리 cold 환경은 완전 네트워크 격리, 전달은 암호화 매체·SFTP·data diode. VM·컨테이너는 완전 지원 — Docker 가 표준 배포 모델이고, Agent 는 stateless 설계라 재시작·재배포가 단순하다.

**Q.** 한 Workspace 에 다중 Agent 를 연결하는 HA·DR 구성이 가능한가? 운영·DR 레퍼런스 아키텍처가 있는가?
**A.** 키 복구와 구성 요소 이중화를 나눠 답했다.
키 복구 — MPC workspace 는 키 자료 백업·복구용 별도 DR 서비스가 필요하지만, KeyLink 는 키가 고객 HSM 안에만 있어 그 요건이 구조적으로 없다. 백업·복구는 Luna 자체 기능(HA group · partition cloning · Luna Backup HSM)으로 한다. Fireblocks 쪽 문제로 고객이 자기 키에 접근 못 하는 시나리오는 없다.
구성 요소 이중화 — 한 workspace 에 여러 Agent 를 페어링할 수 있고 제약은 둘이다: Agent 마다 고유 identity 와 Fireblocks 측 전용 메시지 큐를 갖는다 · 서명키는 특정 Agent user 에 묶여 그 키의 요청은 그 Agent 로 간다. 이 때문에 현재 권장 토폴로지는 **active/active 가 아니라 active/passive**. KeyLink 에 이 구성 요소들의 내장 HA·DR 자동화는 없어 Agent·Customer Server 의 프로세스 감시와 failover 는 고객이 설계한다(Professional Services 범위). 미전달 서명 요청은 Fireblocks 측 큐에 **최대 7일 durable 보존, at-least-once 전달** — Agent 중단·재시작으로 요청이 사라지지 않고 재접속 시 재전달된다. 레퍼런스 아키텍처 문서 제공 여부는 답에 없었다.
이 7일 큐와 Pending Signature 2시간 timeout 의 관계는 확인 안 됨 — 후속 문의 대상.

**Q.** 기존 계약 외 KeyLink 추가 사용료·필수 Professional Services 비용이 있는가? 개발·UAT·운영·DR Workspace 기준 견적은?
**A.** 견적은 영업 담당(Ben Han·Shane Verner) 회신 대기. 확답된 구조: KeyLink 는 Fireblocks 구독의 **유료 add-on** · Professional Services 구현 패키지는 **별도 견적** · Luna 하드웨어와 Thales 라이선스는 **Thales 에서 직접 구매**, Fireblocks 계약에 포함되지 않는다.

## Webhooks V2 재전송 범위 — 공식 문서 기준 (2026-08-31)

- [`resend_failed`](https://developers.fireblocks.com/api-reference/webhooks-v2/resend-failed-notifications)는 호출 시점 기준 최근 24시간 안의 실패 알림만 다시 배차한다. `startTime` 기본값과 하한이 모두 24시간 전이다.
- [migration guide](https://developers.fireblocks.com/reference/webhook-v2-migration-guide)의 “원 이벤트 뒤 최대 30일”은 `resourceId`로 지정하는 재전송 범위다. 전체 실패 알림을 한 번에 다시 보내는 `resend_failed`의 범위가 아니다.
- [`resend_by_query`](https://developers.fireblocks.com/api-reference/webhooks-v2/resend-notifications-by-query)는 현재 endpoint reference 기준 최근 72시간 안에서 조회하고, 한 요청의 `startTime`~`endTime` 구간은 최대 24시간이다. 30일 일반 문구를 query endpoint의 계약으로 확대하지 않는다.
- Blockchain Manager 수동 복구는 `resend_failed`만 사용하므로 기본 24시간을 유지하고, 그보다 오래된 거래 공백은 tx 대사로 회수한다.

## 대기 중인 문의 (회신 전)

**Q.** 웹훅 전달에 순서 보장이 있나? 한 알림이 전달 실패로 재시도 중일 때, 그 뒤에 생긴 알림은 먼저 전달되나?
**A.** 미확인. 알림마다 독립적인 실패 상태·재시도 일정을 갖는 것은 실측으로 확인됐으므로(재전송 API 가 알림 단위로 집계) 뒤 알림이 먼저 도착한다고 보고 설계했다 — 그 결과 `CONFIRMING` 없이 `COMPLETED` 가 먼저 올 수 있다. 이 역전은 매니저가 흡수하고(감지 이벤트를 합성해 먼저 발행), DAW-CORE 에는 감지 → 확정 순서로만 나간다(흐름 문서 허용 전이 표). 벤더 확답을 받으면 이 전제를 확정한다.

**Q.** WRITE 계열(`POST /transactions` 등)의 분당 한도는?
**A.** 미확인. 위 1,000/1,500 은 거래 조회 두 엔드포인트 한정이라, 출금·sweep·boost 제출량의 근거가 아직 없다 — 후속 문의 대상.

**Q.** Universal Gasless로 제출된 EVM 토큰 거래에 `replaceTxByHash`와 `useGasless=true`를 함께 넣어 RBF할 수 있고, 관리형 relay가 대체 거래의 gas도 부담하나?
**A.** **비용 측면은 확답을 받았다**(위 Gasless Relay 과금) — 막힌 거래는 수동 RBF 로 올리고, relay 가 교체 거래의 gas 를 내며 그 분량이 청구된다(원본은 드랍, 이중 청구 없음). **API 파라미터 조합**(`replaceTxByHash` + `useGasless=true`)이 실제로 동작하는지는 여전히 미확인 — sandbox 실측 전에는 자동 boost 기능 게이트를 기본 비활성으로 두고 경보만 한다.

**Q.** RBF Create Transaction에 최초 전송의 `travelRuleMessage`를 다시 실어야 하나, 아니면 `replaceTxByHash`가 원 거래의 컴플라이언스 맥락을 승계하나?
**A.** 미확인. 매니저는 개인정보 보관 경계를 지키기 위해 `travelRuleMessage` 원문을 제출 원장에 저장하지 않는다. 재전달이 필수라면 현재의 무인 자동 boost는 성립하지 않으므로 함께 확인한다.

**Q.** 자산별 확정 임계 값은 얼마로 하나?
**A.** 논의 후 확정 예정(테스트넷 3, 메인넷은 협의 값). Base 의 컨펌 단위와 블록 간격 상수 유효성도 함께 확인한다.

**Q.** vault 가 스스로 제출하지 않은 제3자 거래(사전 서명 authorization·사전 allowance·위임 코드 경유)로 잔액이 출금될 때, vault 별 거래 기록과 웹훅(v2)이 생성되나? 생성된다면 어떤 형태인가(개별 tx vs 제출 거래의 networkRecords)?
**A.** **실측으로 답 나옴 (2026-08-10)** — 위 "PoC 실측으로 확정한 사실" 절 참조. 우리 vault 가 제출한 배치라면 `networkRecords` 에 원천 vault·금액이 귀속되고 `network_records.processing_completed` 도 온다. 남은 것은 한 배치의 이동을 수십 건으로 올렸을 때의 레코드 개수·이벤트 지연이다.

**Q.** Universal Gasless 로 upgrade 된 vault 의 위임 지갑 코드가, 지정 운영자(감사된 배치 sweep 컨트랙트)의 일괄 인출을 허용하는 구성이 가능한가? 안 되면 로드맵에 있거나, 우리가 지정한 감사된 코드로의 위임을 허용하는 경로가 있나?
**A.** 미확인. 7702 배치 노선의 성립 조건 — 안 되면 그 노선 자체가 닫힌다. 현재 결정은 자산 구분 없이 건별 전송이다.

**Q.** TYPED_MESSAGE(EIP-712) 서명에 TAP 으로 내용 기반 제약(특정 컨트랙트·도메인·수신 주소 한정 등)을 걸 수 있나? 분당 서명 처리량과 권장 상한은?
**A.** 미확인. 3009 배치 노선의 성립 조건 — 이 서명은 곧 자금 이동 권한이라 정책 통제가 보안의 핵심이고, 처리량이 배치 크기(M)·주기 설계의 상한이 된다. 배치 재검토 시에 판단할 항목이다.

**Q.** ERC-20 `approve` 를 API 로 낼 때 별도 `APPROVE` operation 과 approve calldata 를 넣은 `CONTRACT_CALL` 중 어느 경로를 써야 하나? TAP 이 승인 대상·토큰·**승인 금액(allowance) 상한**을 어디까지 강제하고, 제3자 `transferFrom` 은 vault 별 거래 레코드·웹훅에 어떤 형태로 잡히나?
**A.** 제출 경로와 기록 형태는 **실측 완료 (2026-08-10)** — 위 실측 절 참조. `APPROVE` 로는 제출 불가(400·1401), `CONTRACT_CALL` 로 내면 통하고 기록은 `operation=APPROVE`. 스키마 enum 에 이름이 있는 것과 제출 경로로 쓸 수 있는 것이 다르다.

**남은 미확인은 정책 쪽** — `APPROVE` transactionType·`applyForApprove` 로 승인 대상·토큰을 넘어 **승인 금액 상한**까지 강제할 수 있는가([정책](https://developers.fireblocks.com/reference/configure-transaction-authorization-policy)), Console 의 Approve Amount Cap 이 API 제출에도 적용되는가([Amount Cap](https://developers.fireblocks.com/docs/interact-with-smart-contracts)), CONTRACT_CALL approve 에 Universal Gasless 를 적용할 수 있고 relay 처리량은 얼마인가. 정책 상한이 없어도 유한 allowance 는 calldata 로 지정할 수 있지만 독립적인 오승인 방어선이 약해진다.

## Universal Gasless 유효 창 — 담당자 확답 (2026-08)

원문: `sources/fireblocks/markdown/2026-08-24__fireblocks-csm__universal-gasless-validity-window.txt`

**Q.** Universal Gasless (EIP-7702) 에 ERC-4337 `validUntil` 같은 온체인 유효 창을 적용할 수 있나?
**A.** 이미 있고, 값은 고정이다. delegate 컨트랙트 (UniversalGaslessDelegate) 가 서명받는 EIP-712 구조체 `AuthorizedExecutions(Execution[] calls, uint256 deadline, bytes32 mode, uint256 nonce, address relayer)` 에 `deadline` 필드가 있고, `execute()` 가 **nonce 를 소비하기 전에** `block.timestamp <= deadline` 을 검사해 늦으면 revert 한다 — `validUntil` 과 같은 의미다. `validAfter` (하한) 와 블록 번호 기반 변형은 없다.

**Q.** deadline 값을 우리가 정할 수 있나?
**A.** 없다. **서명 시각 + 2시간**으로 enclave 안에서 계산되는 설계 고정값이라, 여기에 닿는 API 필드가 없다.

**Q.** 서명이 유출되면 제3자가 제출할 수 있나?
**A.** 없다. relayer 주소 (msg.sender) 가 EIP-712 digest 에 포함되어 **지정 relayer 만 그 서명을 제출**할 수 있다 (담당자 표현: ERC-4337 기본에는 이에 상응하는 것이 없다). nonce 도 단회 사용이라 재사용이 안 된다.

**Q.** 브로드캐스트 전 만료 (`configurations.expiresAfterSeconds`) 는 gasless 거래에도 적용되나?
**A.** 적용된다 — 공유 거래 생성 경로에 있어 gasless 예외가 없다. **기본 비활성**이고 테스트하려면 요청해야 활성화해 준다. 유효 범위는 **10분~24시간**이고 Console 의 워크스페이스 기본값도 같은 한도다. dev 문서의 '300' 은 오기이며 '600'초로 수정 예정이라고 했다. 만료 시 유예 없이 거래가 소멸하고, 지정 서명자에게 발급되는 signing token 도 그에 맞춰 짧아진다 (enclave 강제).

**Q.** 두 메커니즘의 관계는?
**A.** 독립이고 정렬할 수 없다 — `expiresAfterSeconds` 는 승인·큐 지연 통제 (창 안에 승인·서명 안 되면 전파 전 소멸, 10분 하한), 컨트랙트 `deadline` 은 온체인 유효 창 (서명된 연산이 그 시각 이후 체인에 오르지 못함, 2시간 고정). 오늘 쓸 수 있는 통제는 넷이다: 2시간 온체인 유효 (고정) · relayer-bound 서명 · 단회 nonce · 옵션인 10분~24시간 pre-signature TTL.

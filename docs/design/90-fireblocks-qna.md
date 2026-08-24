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

## 공식 문서로 확인한 사실 (참고)

담당자 메신저 답변이 아니라 Fireblocks 공식 문서에서 확인한 것 — 답변마다 원본 출처 링크를 단다.

**Q.** 웹훅에 2xx 를 못 주면 어떻게 되나?
**A.** 지수 백오프로 총 10회 재시도(합산 약 8시간) 후 failed 로 마킹한다. ([Responses & retries](https://developers.fireblocks.com/reference/webhooks-gettingstarted-responsesretries))

**Q.** 실패 처리된 알림을 다시 받을 수 있나?
**A.** Webhooks V2의 `resend_failed`는 호출 시점 기준 최근 24시간 안의 실패 알림을 다시 배차한다. migration guide의 최대 30일은 resource/query 기반 재전송까지 포함한 상위 설명이므로 이 endpoint의 범위와 구분한다. 24시간보다 오래된 공백은 거래 조회·대사로 복구한다. ([Resend failed webhook notifications](https://developers.fireblocks.com/reference/resend-failed-webhook-notifications))

**Q.** 수신 오류율이 높으면?
**A.** 오류율이 높은 endpoint 는 벤더가 자동 비활성화한다(circuit breaker). 재활성화 전까지 웹훅이 오지 않는다. ([Responses & retries](https://developers.fireblocks.com/reference/webhooks-gettingstarted-responsesretries))

**Q.** 웹훅 서명은 어떻게 검증하나?
**A.** JWKS 방식이다 — `Fireblocks-Webhook-Signature` 헤더, 공개키는 자동 조회·로테이션. ([Validating webhooks](https://developers.fireblocks.com/reference/validating-webhooks))

**Q.** 확정으로 볼 컨펌 수는 어떻게 정해지나?
**A.** DCCP(확정 정책)가 정한다. 기본은 대부분 체인 1(이더·Base 포함)·ETC 372·컨트랙트 호출 3 권장. 한도는 EVM 최소 1·이더 최대 100·신규 EVM L2 최대 30. 커스텀 임계는 정책 템플릿을 Support 에 제출해 승인 후 반영된다. ([About the DCCP](https://support.fireblocks.io/hc/en-us/articles/360013034359-About-the-Deposit-Control-and-Confirmation-Policy))

**Q.** 일반 EVM 거래를 API로 boost할 수 있나?
**A.** 가능하다. Create Transaction에 원 거래의 온체인 hash를 `replaceTxByHash`로 넣으면 같은 nonce의 더 높은 수수료 거래를 만든다. 새 거래는 원 거래가 CONTRACT_CALL이어도 TRANSFER로 생성된다. ([Boost Transactions](https://developers.fireblocks.com/reference/boost-transactions))

**Q.** Fireblocks가 일반 발신 거래를 자동 boost해 주나?
**A.** 공식 문서에서 자동 boost가 명시된 것은 Gas Station의 auto-fueling 거래다. 일반 EVM 발신은 Console Boost 또는 API RBF 절차와 `transaction.alert.stuck`의 권장 조치 `BOOST_TRANSACTION`이 문서화돼 있어, 일반 거래의 자동 처리를 보장한다고 보지 않는다. ([Gas Station Autoboost](https://support.fireblocks.io/hc/en-us/articles/14406592622748-Gas-Station-Autoboost) · [Transaction events](https://developers.fireblocks.com/reference/webhooks-structures-eventtypes-transaction))

**Q.** stuck 이벤트는 무엇을 주나?
**A.** EVM에서 vault+base asset의 거래가 `CONFIRMING`으로 막히면 `transaction.alert.stuck`이 발생할 수 있고, 가장 오래 막힌 Fireblocks tx id와 tx hash, 경과 블록·시간, 권장 `BOOST_TRANSACTION`을 준다. 알림은 개입 워크플로를 만드는 신호이고 자동 해결 완료 통지가 아니다. ([Transaction events](https://developers.fireblocks.com/reference/webhooks-structures-eventtypes-transaction))

## PoC 실측으로 확정한 사실 (2026-08)

문서·답변이 아니라 **실물로 직접 관찰**한 것 — 상세는 [수신 PoC 결과보고](../설계/97-webhook-poc-result.md)와 [approve 배치 sweep PoC 결과보고](../설계/95-approve-pull-poc-result.md).

**Q.** 재시도 간격은 실제로 얼마인가?
**A.** 분 단위 배증이다 — 첫 재시도 +21~60초, 이후 1분 → 1분 → 3분 → 6분 → … 으로 벌어지고, 도착이 분 tick(:00)에 정렬된다. 공식 문서의 "지수 백오프 10회·~8시간" 원리는 맞지만 세부 간격 수치(10·30·120초…로 알려진 것)와는 다르다. (2026-08-03, 알림 2건×2회 실측)

**Q.** `resend_failed` 는 실제로 어떻게 동작하나?
**A.** `202 {"total":N}` 을 반환하는데 **N 은 호출 시점에 실패 상태인 알림 수만** 센다 — 벤더 재시도로 이미 전달돼 2xx 를 받은 알림은 제외된다. 재전송은 즉시가 아니라 **다음 분 tick** 에 도착했다(호출 51초 뒤). 회수 수단으로 유효함을 실측 확인.

**Q.** v2 알림 payload 의 실물 구조는?
**A.** 최상위 `id`(알림 UUID)·`eventType`·`resourceId`(tx id)·`webhookId`·`workspaceId`·`createdAt`(epoch ms), tx 는 `data.{id,status,subStatus,numOfConfirmations,source,destination,txHash,blockInfo,amountInfo,…}`. `transaction.created` 가 이미 `CONFIRMING`+`txHash` 를 담고 온다(입금은 체인 감지 시점부터). 금액은 `amountInfo` 의 **문자열 값**을 쓰는 게 정밀도 안전. 원본 샘플은 fbhook 저장소 docs/payload-samples/.

**Q.** payload 를 JSON/JSONB 컬럼에 저장하면?
**A.** 키 재정렬·공백 정규화로 **와이어 바이트가 소실**된다 — DB 에서 꺼낸 값으로는 detached JWS 재검증이 불가했다(실측 401). 원문 해시·서명 재검증은 수신 시점 바이트로 해야 한다.

**Q.** 배치 컨트랙트가 `transferFrom` 으로 여러 vault 의 잔액을 한 거래에 모을 때, vault 별 거래 기록과 웹훅이 어떻게 오나? (2026-08-10 이더리움 Sepolia · KBKRW · 이동 2건)
**A.** 우리 vault 가 배치를 `CONTRACT_CALL` 로 제출한 경우로 확인했다. 그 거래의 `networkRecords` 에 **원천 vault 가 귀속되고 `netAmount` 도 나온다.** `transaction.network_records.processing_completed` 도 온다. 단 최상위 거래는 제출 1건뿐이고(원천 vault·옴니버스 각각의 최상위 거래는 생기지 않는다), 레코드마다 우리 vault 는 한쪽에만 채워진다 — 같은 이동이 받는 vault 관점과 보내는 vault 관점으로 두 번 들어오고, 토큰이 움직이지 않은 호출 관계까지 더해 이동 한 건당 레코드 3개가 된다.

시사점 — 배치 sweep 의 감지·대사는 성립하지만 **network records 를 펼쳐 보낸 vault 기준 행만 골라 쓰는 처리가 필수**다. 서명만 넘겨 외부가 제출하는 모델에서도 같은지는 재보지 않았다.

**Q.** ERC-20 `approve` 는 어떤 operation 으로 제출하나? 스키마 enum 의 `APPROVE` 를 쓰면 되나?
**A.** **`APPROVE` 로는 제출할 수 없다** — `400 {"message":"Cannot perform transaction","code":1401}`(두 가지 body 형태 모두). `CONTRACT_CALL` 에 approve calldata 를 실어 보내면 통하고(200 → COMPLETED, 온체인 allowance 반영), **조회하면 그 거래의 `operation` 이 `APPROVE`** 로 나온다. 즉 `APPROVE` 는 제출용이 아니라 벤더가 calldata 를 보고 붙이는 분류 라벨이다. TAP 의 `APPROVE` transactionType·`applyForApprove` 도 이 분류 위에 있을 것으로 보이나 정책 적용은 미실측.

## 웹훅 이벤트 — network records (참고)

`transaction.network_records.processing_completed` 가 어떤 거래에서 생기는지 공식 문서로 확인한 것과, 같은 원리를 우리 맥락에 적용한 예시.

**Q.** network records 는 어떤 거래에서 생기나?
**A.** 한 Fireblocks 거래가 여러 온체인 거래를 묶을 때(주로 컨트랙트 호출)다. 상위 거래의 자산은 **항상 그 네트워크의 기준 자산**(ETH 등)이고, 실제 토큰 이동은 network records 를 펼쳐야 드러난다. 단순 전송(단건 TRANSFER)이면 이 값이 비어 있어 이벤트가 뜨지 않는다. ([CONTRACT_CALL 특수 케이스](https://community.fireblocks.com/t/what-is-the-special-case-for-the-contract-call-transactions/707) · [networkRecords 정의](https://developers.fireblocks.com/reference/transaction-webhooks))

**예시 1 — 스왑 (문서화된 벤더 사례).** Uniswap 에서 USDC → DAI 스왑, CONTRACT_CALL 한 건(자산 ETH):

| # | network record | 내용 |
|---|---|---|
| 1 | 나가는 USDC | 스왑에 넣은 토큰 |
| 2 | 들어오는 DAI | 스왑으로 받은 토큰 |
| 3 | 나가는 ETH | 수수료 |

> 아래 둘은 같은 원리를 우리 맥락에 적용한 예시 — 벤더가 문서화한 사례는 아니다.

**예시 2 — 컨트랙트 기반 정산: 일괄 지급(disperse).** 여러 수취인에게 USDC 를 한 번에 지급, CONTRACT_CALL 한 건(자산 ETH). 수취인 N명이면 토큰 record N개 + gas 1개, 실제 "누구에게 얼마"는 network records 를 펼쳐야 보인다:

| # | network record | 내용 |
|---|---|---|
| 1 | 나가는 USDC → 수취인 A | |
| 2 | 나가는 USDC → 수취인 B | |
| 3 | 나가는 USDC → 수취인 C | |
| 4 | 나가는 ETH | 수수료 |

**예시 3 — 브리지: 체인 간 이동.** USDC 를 이더리움 → Base 로 브리지, 소스 체인의 브리지 컨트랙트에 CONTRACT_CALL(자산 ETH). 목적지 체인 도착은 별개의 온체인 거래로 따로 관측:

| # | network record | 내용 |
|---|---|---|
| 1 | 나가는 USDC | 브리지 컨트랙트로 lock/burn |
| 2 | 나가는 ETH | 수수료 |

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

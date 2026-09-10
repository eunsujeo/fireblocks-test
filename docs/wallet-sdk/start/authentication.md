---
title: 표면과 인증
description: 어떤 API를 누가 부르고, 무엇이 인증에서 나오는가
---

_읽는 사람: 파트너사 개발자. 표면 다섯과 각 표면에서 인증이 확정하는 것을 다룹니다._

표면은 계약 소유자와 인증 방식이 하나로 정해지는 API 경로 묶음입니다. 표면이 다섯이고, 부르는
주체와 인증 방식이 갈라서 나뉜 것이지 기능으로 나뉜 것이 아닙니다.

| 표면 | 부르는 쪽 | 무엇을 하나 |
|---|---|---|
| [Wallet API](/reference/wallet) | 파트너사 서버 | 계정·지갑 발급, 입금 조회, 집금, 출금 |
| [Admin API](/reference/admin) | 운영자 콘솔 | 카탈로그·테넌트·API 키·운영자 관리, 승인 게이팅 |
| [Policy API](/reference/policy) | Wallet 서버 | 정책 판단 요청, 룰 저작·발행, 어드민 인증 |
| [Travel Rule Gateway API](/reference/travel-rule) | Wallet 서버, 트래블룰 제공자 | 트래블룰 정보 교환 |
| Internal API | 정책 엔진 | 평가에 필요한 사실 조회(PIP 원천) |

## 파트너사가 부르는 표면

일상적인 연동에서 부르는 것은 Wallet API 하나입니다. 나머지 넷은 운영자 콘솔이 쓰거나
서비스끼리 부릅니다.

그 하나로 무엇을 어떤 순서로 부르는지는 [첫 요청](/start/first-request)에 있습니다.

**Internal API는 파트너사가 부르지 않습니다.** 정책 엔진은 자금을 움직이는 요청이 서명 인프라로
가기 전에 그 요청을 심사하는 별도 시스템입니다([자세히](/policy)). 그 엔진이 판단에 필요한 사실을
평가 시점에 읽어 오는 수집 경로가 PIP이고([자세히](/policy/runtime/pip)), Internal API가 그
원천입니다. 호출자는 서비스 신원으로 인증합니다. 문서에 실려 있는 이유는 그 사실들이 무엇인지가
정책 판단을 읽는 데 필요해서입니다.

## Wallet 서버의 게이트웨이 역할

Wallet 서버가 게이트웨이를 겸합니다. Policy API와 Travel Rule Gateway API는 외부에 열려 있지
않습니다. 파트너사는 Wallet API만 부르고, 그 뒤에서 Wallet 서버가 두 서비스를 호출합니다.

**예외가 하나 있습니다.** 비동기 PIP 결과는 파트너가 서명해서 인바운드로 제출합니다. 인증 근거가
다른 별도 경로입니다.

운영자 콘솔도 Wallet 서버를 거칩니다. 정책 엔진에 직접 붙지 않고, 콘솔이 받은 어드민 세션 자격을
Wallet 서버가 그대로 실어 보냅니다.

트래블룰 게이트웨이의 인바운드를 부르는 것은 상대 거래소가 아니라 트래블룰 제공자입니다. 상대
거래소는 그 제공자를 거쳐 닿습니다.

## 스코프의 출처

스코프 키는 인증이 확정하는 격리 식별자이고, 요청이 싣지 않습니다. API 키는 파트너사가 파트너
대상 API를 부를 때 제시하는 인증 수단입니다.

파트너사는 그 키로 인증합니다. 그 키가 어느 workspace와 어느 tenant에 속하는지를 서버가
알고 있고, 모든 조회와 쓰기가 그 값으로 스코프됩니다. workspace는 Wallet SDK를 도입한 회사
하나이고, tenant는 workspace 아래에서 Account를 묶는 계정 체계
하나입니다([계정 계층](/accounts-wallets/accounts)).

**요청은 스코프 키를 싣지 않습니다.** `workspaceId`나 `tenantId`를 본문·경로·쿼리로 받지
않습니다. 요청 값은 위조할 수 있어서, 그것을 격리 키로 쓰면 남의 테넌트 데이터를 지목하는
요청이 그대로 통과합니다.

## `accountId`의 성격

`accountId`는 스코프 키가 아닙니다. Wallet SDK가 발급해 Account를 지칭하는 값이고, Account는
그 서비스의 최종 사용자 한 명을 wallet 안에서 대리하는 virtual
사용자입니다([계정 계층](/accounts-wallets/accounts)).

같은 식별자라도 표면에 따라 성격이 갈립니다. `accountId`는 요청이 정하는 값이라 그 자체로는
아무것도 보증하지 않습니다.

```
GET /api/v1/accounts/{accountId}/deposits
```

이 경로의 `accountId`는 리소스 식별자입니다. 서버는 이 조회를 **인증에서 확정된 `tenantId`와
함께** 수행합니다. tenant 없이 accountId만으로 조회하면 그것이 곧 cross-tenant 유출입니다.

계정 하위 자산도 같습니다. 입금·출금·주소록·지갑 전부 별도 기준을 만들지 않고 tenant 단위로
묶어, 이 성질이 쿼리 형태로 강제됩니다.

## 운영자 인증

**운영자 표면은 API 키가 아니라 사람을 식별합니다.** 어드민 세션은 로그인과 2단계 인증을 거쳐
받는 불투명 크리덴셜입니다. 그 세션에 워크스페이스와 역할이 묶여 있습니다.

콘솔은 Wallet 서버를 거쳐 정책 엔진의 어드민 표면에 닿습니다. 가입·로그인·2단계 인증이 거치는
경로는 [개통](/accounts-wallets/onboarding)에 있습니다.

## 다음으로

- [첫 요청](/start/first-request) — 계정을 만들고 입금 주소를 받기까지
- [계정 계층](/accounts-wallets/accounts) — Workspace·Tenant·Account 세 계층과 `accountId`의 성격
- [용어](/start/terminology) — 이 페이지가 쓰는 낱말의 한 줄 정의

계약이 약속한 것과 지금 동작이 갈리는 지점은 내부 기록이 따로 모읍니다. 파트너사가 그중 만나는
것은 `Idempotency-Key`가 아직 재시도 중복을 막지 못한다는 사실 하나이고, 그 서술은
[첫 요청](/start/first-request)에 있습니다.

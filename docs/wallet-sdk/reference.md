---
title: API 레퍼런스
description: 표면 다섯 개의 엔드포인트 전수
---

_읽는 사람: 파트너사 개발자. 엔드포인트 전수를 표면별로 나눠 다룹니다._

계약 소유자와 인증 방식이 하나로 정해지는 API 경로 묶음인 표면([자세히](/start/authentication))이
다섯 가지 있습니다. 부르는 주체와 인증 방식이 갈라서 다섯으로 나뉜 것입니다. 먼저 어느 표면인지를
고르고 그 안에서 엔드포인트를 찾는 편이 빠릅니다.

헤더의 드롭다운으로 표면 사이를 오갈 수 있습니다.

## 파트너사가 쓰는 표면

### [Wallet API](/reference/wallet)

<SpecDownload file="openapi.WalletApi.yaml" />

일상적인 연동에서 부르는 것은 이것 하나입니다. 계정과 지갑을 만들고, 입금을 조회하고, 집금과
출금을 요청합니다.

인증은 파트너사 API 키입니다. 스코프는 그 키가 정하고 요청이 싣지 않습니다.

- **관련 서술**: [자금 이동](/fund-flows) · 설계 기록의 「식별자」

## 운영자가 쓰는 표면

### [Admin API](/reference/admin)

<SpecDownload file="openapi.WalletAdminApi.yaml" />

카탈로그(네트워크·토큰), 테넌트, API 키를 관리하고 승인 게이팅을 처리합니다. 격리 키가
workspace입니다.

승인 게이팅은 어드민 액션이 승인자의 확인을 거쳐야 실행되는
통제입니다([자세히](/policy/sequences/approval-gate)).

- **관련 서술**: [카탈로그](/accounts-wallets/catalog) · [계정 계층](/accounts-wallets/accounts)

### [Policy API](/reference/policy)

<SpecDownload file="openapi.PolicyApi.yaml" />

정책 판단 요청과 조회, 룰 저작·발행, 어드민 인증입니다. 사람이 콘솔로 접근하는 어드민 표면과
서비스가 부르는 판단 표면이 같은 포트에 있습니다. 어느 쪽인지는 경로가 정합니다. 겹치면 어드민
세션 인증이 우선하고, 엇갈린 자격은 401입니다.

- **관련 서술**: [정책 엔진](/policy) · [표면과 인증](/start/authentication)

## 서비스끼리 부르는 표면

파트너사는 이 둘을 직접 부르지 않습니다. Wallet 서버가 게이트웨이를 겸해 대신 호출합니다.
Wallet 서버는 파트너 대상 API를 받아 정책 엔진에 판단을 묻고 서명 인프라에 제출하는 Wallet SDK의
서버 컴포넌트입니다([자세히](/policy/architecture)). 문서에 실린 이유는 그 계약이 무엇인지가 자금
흐름과 정책 판단을 읽는 데 필요해서입니다.

### [Travel Rule Gateway API](/reference/travel-rule)

<SpecDownload file="openapi.TravelRuleGateway.yaml" />

가상자산 이전 시 송·수신 정보를 사업자끼리 교환하는 규제 의무인
트래블룰([자세히](/#함께-제공하는-모듈))의 정보 교환입니다. 판단 주체는 wallet이고, 상대 VASP에는 단일
boolean만 냅니다. 그 이상을 내면 상대가 파트너사의 최종 사용자를 열거할 수 있습니다.

- **관련 서술**: [이동 종류](/fund-flows/movements) · [에코시스템](/#함께-제공하는-모듈)

### Internal API


자금을 움직이는 요청이 서명 인프라로 가기 전에 그 요청을 심사하는 별도 시스템인 정책
엔진([자세히](/policy/architecture))이 평가 전에 사실을 읽는 경로입니다. vault 소유, 토큰 카탈로그,
주소 등재 상태가 여기서 나옵니다.

**조회 순서 자체가 격리의 근거입니다.** 토큰 카탈로그 조회는 요청이 신고한 workspace가 아니라
vault 소유 해석에서 얻은 workspace로 스코프합니다. vault 소유 해석은 vault 식별자 하나로 소유
workspace와 tenant를 확정하는 조회입니다([자세히](/policy/runtime/pip)).

- **관련 서술**: [PIP](/policy/runtime/pip) · [도메인 모델](/accounts-wallets/domain-model)

## 계약의 기준 문서

계약의 기준 문서는 TypeSpec입니다. 이 레퍼런스는 `docs/wallet-system/tsp/`에서 생성됩니다.
엔드포인트·스키마·에러 코드가 바뀌면 그쪽이 먼저 바뀌고 여기가 따라옵니다.

표면마다 있는 내려받기 버튼은 그 TypeSpec에서 생성한 OpenAPI 3.1 파일을 줍니다. 이 사이트의
레퍼런스 페이지도 같은 파일에서 만들어지므로, 받은 파일과 화면의 내용은 같은 빌드에서 나온 것입니다.
클라이언트 코드 생성이나 모의 서버에 넣을 때 씁니다.

계약이 바뀐 것은 [변경 이력](/changelog)에 적습니다. 계약과 서술이 갈려 있는 것은
설계 기록의 「알려진 제약」에 모아 두었습니다. **에러 코드 이름과 주소록 접두사가 지금
그렇습니다.**

**여기 실린 것이 다 동작하지는 않습니다.** 어느 오퍼레이션이 아직 모형 응답이고 어느 것이
계약뿐인지는 설계 기록의 「구현 대기」가 빌드마다 코드에서 다시 셉니다.

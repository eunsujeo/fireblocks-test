---
title: 계정과 지갑
description: 두 계층이 왜 따로 있고 각각 무슨 역할인지
---

_읽는 사람: 파트너사 개발자. 계정 계층과 지갑 계층이 왜 따로 있고 각각 무슨 역할인지를 다룹니다._

계정과 지갑은 파트너사가 Wallet SDK에서 가장 먼저 만드는 상태입니다. 이 페이지는 두 계층이 왜
따로 있고 각각 무슨 역할을 하는지를 설명합니다. 각 개념의 정의와 API 계약은 계층별 페이지에
있습니다.

## 계정 계층은 데이터가 누구의 것인지를 정합니다

한 파트너사 안에 계정 체계가 여럿 있고, 한 계정 체계 안에 사용자가 여럿 있습니다. Wallet SDK는
이 세 단위를 Workspace, Tenant, Account 세 계층으로 나눕니다([계정 계층](/accounts-wallets/accounts)).

이 계층이 중요한 이유는 데이터를 가르는 기준이 여기서 나오기 때문입니다. 파트너사 대상 API는
Tenant 단위로 데이터를 가르고, 어느 Tenant인지는 요청 본문이 아니라 인증에서
정해집니다([표면과 인증](/start/authentication)).

같은 사람이 두 계정 체계에 가입하면 Account가 둘 생기는 것도 이 계층 때문입니다.

Workspace를 만드는 경로는 아직 정해지지 않았습니다. 지금은 플랫폼 운영자가 승인을 거쳐
만듭니다([온보딩](/accounts-wallets/onboarding)).

## 지갑 계층은 자산을 어디에 담는지를 정합니다

자산은 어느 네트워크 위의 어느 토큰인지로 구분합니다. 같은 USDC라도 네트워크가 다르면 다른
Token입니다([카탈로그](/accounts-wallets/catalog)).

Wallet은 토큰 하나를 담는 단위입니다([지갑 계층](/accounts-wallets/wallets)). 그런데 이
단위만으로는 여러 토큰을 가진 실체를 나타낼 수 없습니다.

유저 유통 지갑 하나가 USDC와 KRWK를 함께 담아야 하기 때문입니다. 따라서 Wallet 여럿을 담는 상위
단위가 필요합니다.

Wallet SDK는 이 상위 단위를 Vault라 부릅니다. Workspace나 Account가 실제로 소유하는 자산의
단위는 이 Vault입니다.

## 파트너사는 Vault 단위로 보면 됩니다

파트너사 관점에서는 Vault에 Token을 담고, 자산의 이동은 Vault 단위로 일어난다고 보면 됩니다.
API가 어느 식별자를 받는지는 [지갑 계층](/accounts-wallets/wallets)의 발급 단위 표에 있습니다.

## 다음으로

- [도메인 모델](/accounts-wallets/domain-model) — 엔티티가 서로를 어떻게 소유하는가
- [계정 계층](/accounts-wallets/accounts) — 세 계층의 정의와 `referenceId`, 상태 계약
- [지갑 계층](/accounts-wallets/wallets) — Vault와 Wallet의 정의, 발급 단위 표
- [카탈로그](/accounts-wallets/catalog) — Network와 Token의 등록 주체와 가시성
- [온보딩](/accounts-wallets/onboarding) — 이 상태를 처음 세우는 절차

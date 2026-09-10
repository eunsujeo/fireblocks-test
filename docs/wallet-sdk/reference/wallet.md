---
seo:
  description: Wallet API API reference.
sidebar:
  label: Overview
title: Wallet API
---
<ApiOverview source="reference-wallet" />

## Accounts

파트너사의 최종 사용자를 wallet 안에서 대리하는 계정. 삭제 없이 활성·비활성만 전환한다.

<ApiTagOperations source="reference-wallet" tag="accounts" />

## Account Wallets

계정에 귀속된 토큰별 입금 지갑. 온체인 주소가 여기 붙는다.

<ApiTagOperations source="reference-wallet" tag="account-wallets" />

## Address Books

출금 목적지 등록. 등재 여부는 거절 사유가 아니라 정책 입력이다.

<ApiTagOperations source="reference-wallet" tag="address-books" />

## Deposits

온체인 관측으로 기록된 유입. 확정되면 기본 동결로 들어간다.

<ApiTagOperations source="reference-wallet" tag="deposits" />

## Withdrawals

workspace vault에서 외부 주소로의 출금. 자금 귀속에 따라 표면이 갈린다.

<ApiTagOperations source="reference-wallet" tag="withdrawals" />

## Networks

블록체인 네트워크 카탈로그. 계열에 따라 주소 체계가 갈린다.

<ApiTagOperations source="reference-wallet" tag="networks" />

## Sweeps

동결 유입 건을 지목해 집금 vault로 옮긴다. 해제와 이동이 원자적이다.

<ApiTagOperations source="reference-wallet" tag="sweeps" />

## Tokens

네트워크 위의 자산. 유일성은 (workspace, network, 컨트랙트 주소)에서 성립한다.

<ApiTagOperations source="reference-wallet" tag="tokens" />

## Travel Rules

트래블룰 정보 교환. 판단 주체는 wallet이고 상대 VASP에는 단일 boolean만 낸다.

<ApiTagOperations source="reference-wallet" tag="travel-rules" />

## Workspace Vault Transfers

workspace vault 사이의 자산 이동. 동결되지 않은 자산만 움직인다.

<ApiTagOperations source="reference-wallet" tag="workspace-vault-transfers" />

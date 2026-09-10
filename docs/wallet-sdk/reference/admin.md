---
seo:
  description: Wallet Admin API API reference.
sidebar:
  label: Overview
title: Wallet Admin API
---
<ApiOverview source="reference-admin" />

## Approvals

maker-checker 승인. 정족수 obligation의 이행 표면이고, 요청자와 승인자는 같을 수 없다.

<ApiTagOperations source="reference-admin" tag="approvals" />

## Auth

어드민 인증. 세션과 2FA를 다룬다.

<ApiTagOperations source="reference-admin" tag="auth" />

## Networks

블록체인 네트워크 카탈로그. 계열에 따라 주소 체계가 갈린다.

<ApiTagOperations source="reference-admin" tag="networks" />

## Policies

정책 엔진 연동 표면.

<ApiTagOperations source="reference-admin" tag="policies" />

## Roles

어드민 역할과 권한 부여.

<ApiTagOperations source="reference-admin" tag="roles" />

## Tenants

파트너사 단위 스코프. 파트너 대상 API의 격리 키다.

<ApiTagOperations source="reference-admin" tag="tenants" />

## API Keys

파트너사 API 키 발급·회수. 스코프가 여기서 정해진다.

<ApiTagOperations source="reference-admin" tag="api-keys" />

## Tokens

네트워크 위의 자산. 유일성은 (workspace, network, 컨트랙트 주소)에서 성립한다.

<ApiTagOperations source="reference-admin" tag="tokens" />

## Users

어드민 사용자 관리.

<ApiTagOperations source="reference-admin" tag="users" />

## Workspace Vaults

workspace에 귀속된 vault. 용도는 태그로 고객이 정의한다.

<ApiTagOperations source="reference-admin" tag="workspace-vaults" />

## Initialize

최초 어드민 부트스트랩. 한 번만 통과한다.

<ApiTagOperations source="reference-admin" tag="initialize" />

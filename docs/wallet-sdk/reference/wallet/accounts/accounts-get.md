---
search:
  tags:
    - Accounts
    - GET
seo:
  description: >-
    accountId로 계정을 상세 조회한다. 상태로 걸러내지 않으므로 비활성 계정도… Reference for the GET
    /api/v1/accounts/{accountId} endpoint in the Wallet API API.
sidebar:
  label: 계정 상세 조회
  badge: GET
title: 계정 상세 조회
type: openapi-operation
---
accountId로 계정을 상세 조회한다.
상태로 걸러내지 않으므로 비활성 계정도 status와 함께 반환한다.

Errors:
- 404 ACCOUNT_NOT_FOUND: accountId로 매칭되는 계정 없음

<Operation source="reference-wallet" id="accounts-get" />

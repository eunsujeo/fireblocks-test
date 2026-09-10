---
search:
  tags:
    - Accounts
    - GET
seo:
  description: >-
    referenceId로 계정을 상세 조회한다. referenceId 쿼리 파라미터가 있을 때만 이 핸들러로… Reference for
    the GET /api/v1/accounts endpoint in the Wallet API API.
sidebar:
  label: 계정 referenceId 조회
  badge: GET
title: 계정 referenceId 조회
type: openapi-operation
---
referenceId로 계정을 상세 조회한다.
referenceId 쿼리 파라미터가 있을 때만 이 핸들러로 라우팅된다.
referenceId는 같은 고객사(tenant) 안에서 유일하므로 단건을 반환한다.
상태로 걸러내지 않으므로 비활성 계정도 status와 함께 반환한다.

Errors:
- 404 ACCOUNT_NOT_FOUND: referenceId로 매칭되는 계정 없음

<Operation source="reference-wallet" id="accounts-get-by-reference-id" />

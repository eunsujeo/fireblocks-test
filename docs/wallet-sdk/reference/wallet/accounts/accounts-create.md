---
search:
  tags:
    - Accounts
    - POST
seo:
  description: >-
    referenceId에 매핑되는 계정을 생성한다. 요청 안에서 종결되며 승인을 거치지 않는다. Reference for the POST
    /api/v1/accounts endpoint in the Wallet API API.
sidebar:
  label: 계정 생성
  badge: POST
title: 계정 생성
type: openapi-operation
---
referenceId에 매핑되는 계정을 생성한다. 요청 안에서 종결되며 승인을 거치지 않는다.

referenceId는 같은 고객사(tenant) 안에서 유일하다. 이미 쓰인 값으로 생성하면 거부되며,
그 경우 아래 referenceId 조회로 기존 계정을 찾는다.

Errors:
- 400 ACCOUNT_ALREADY_EXISTS: 같은 tenant에 그 referenceId의 계정이 이미 있음
- 400 INVALID_REQUEST_BODY: referenceId가 비었거나 허용 길이를 넘음

<Operation source="reference-wallet" id="accounts-create" />
